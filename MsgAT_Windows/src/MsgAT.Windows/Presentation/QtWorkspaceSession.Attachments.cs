// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private sealed class AttachmentReview(string peer, long generation,
        Func<IReadOnlyList<string>, MediaSendChoice, Action<string>?, Task<PreparationSendResult>> send)
    {
        internal readonly string Peer = peer;
        internal readonly long Generation = generation;
        internal readonly Func<IReadOnlyList<string>, MediaSendChoice, Action<string>?, Task<PreparationSendResult>> Send = send;
        internal readonly Dictionary<string, VideoTrim> Trims = new(StringComparer.Ordinal);
        internal readonly Dictionary<string, ReviewedPhoto> Photos = new(StringComparer.Ordinal);
        internal System.Windows.Window? Editor;
        internal string? PreparationId;
        internal bool Editing;
        internal bool Busy;
    }

    private AttachmentReview? _attachmentReview;

    private bool HandleAttachment(int action, JsonElement payload)
    {
        var peer = payload.GetProperty("peer").GetString();
        var generation = payload.GetProperty("generation").GetInt64();
        if (action == 18)
        {
            if (peer is null || _attachmentModel.SelectedConversation?.Uuid != peer ||
                !_attachmentModel.IsChatContentVisible || _attachmentModel.HasMessageEdit ||
                _attachmentModel.CaptureConfirmedMediaBatchSender() is not { } sender) return false;
            ClearAttachmentReview();
            _attachmentReview = new AttachmentReview(peer, generation, sender);
            return true;
        }
        if (_attachmentReview is not { } review || review.Peer != peer || review.Generation != generation) return false;
        if (action == 21) { ClearAttachmentReview(); return true; }
        if (action == 20)
        {
            if (review.PreparationId is { } preparation) _attachmentModel.CancelPreparation(preparation);
            return true;
        }
        if (!CanUseAttachmentReview(review) || review.Busy || review.Editing) return false;
        if (action is 29 or 30) return EditAttachmentSelection(review, action, payload);
        if (!_attachmentModel.CanCompose) return false;

        var paths = Strings(payload.GetProperty("paths"));
        var captions = Strings(payload.GetProperty("captions"));
        if (paths.Length is < 1 or > 10 ||
            paths.Distinct(StringComparer.OrdinalIgnoreCase).Count() != paths.Length ||
            paths.Any(path => !Path.IsPathFullyQualified(path) || path.Contains('\0')) || captions.Length != paths.Length)
            return false;
        if (captions.Any(caption => Encoding.UTF8.GetByteCount(caption) > 48 * 1024))
        {
            PresentAttachment(review, AppStrings.Get("UiCaptionTooLong"));
            return true;
        }
        var scheduled = payload.TryGetProperty("sendAt", out var timestamp) ? (long?)timestamp.GetInt64() : null;
        if (scheduled <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())
        {
            PresentAttachment(review, AppStrings.Get("UiChooseFuture"));
            return true;
        }
        var asFile = payload.GetProperty("asFile").GetBoolean();
        var allVisual = paths.All(path =>
        {
            var mime = EncryptedObjectStore.MimeForPath(path);
            return mime.StartsWith("image/", StringComparison.Ordinal) || mime.StartsWith("video/", StringComparison.Ordinal);
        });
        var choice = new MediaSendChoice(captions[0], new SendOptions(scheduled, payload.GetProperty("silent").GetBoolean()),
            AsFile: asFile, AsAlbum: !asFile && paths.Length > 1 && allVisual && payload.GetProperty("album").GetBoolean(),
            CompressPhotos: !asFile && payload.GetProperty("photos").GetBoolean(),
            CompressVideos: !asFile && payload.GetProperty("videos").GetBoolean(),
            VideoTrims: paths.Where(review.Trims.ContainsKey).ToDictionary(path => path, path => review.Trims[path], StringComparer.Ordinal),
            EditedPhotos: paths.Where(review.Photos.ContainsKey).ToDictionary(path => path, path => review.Photos[path].Content, StringComparer.Ordinal),
            ItemCaptions: captions);
        review.Busy = true;
        PresentAttachment(review, AppStrings.Get("UiPreparationWaiting"));
        TrackAttachmentWork(SendAttachmentAsync(review, paths, choice));
        return true;

        static string[] Strings(JsonElement value) => value.EnumerateArray().Select(item => item.GetString() ?? "").ToArray();
    }

    internal void TrackAttachmentWork(Task operation)
    {
        var previous = _attachmentModel.WorkspaceAttachmentCompletion;
        _attachmentModel.WorkspaceAttachmentCompletion = previous.IsCompletedSuccessfully ? operation : Task.WhenAll(previous, operation);
    }

    private bool CanUseAttachmentReview(AttachmentReview review) =>
        _view != 0 && IsVisible(_view) && ReferenceEquals(_attachmentReview, review) &&
        _attachmentModel.IsChatContentVisible && _attachmentModel.SelectedConversation?.Uuid == review.Peer;

    private async Task SendAttachmentAsync(AttachmentReview review, string[] paths, MediaSendChoice choice)
    {
        PreparationSendResult result;
        try
        {
            result = await review.Send(paths, choice, id =>
            {
                review.PreparationId = id;
                UpdateAttachmentReview();
            });
        }
        finally
        {
            review.Busy = false;
            review.PreparationId = null;
            if (!ReferenceEquals(_attachmentReview, review)) ClearAttachmentEdits(review);
        }
        if (!CanUseAttachmentReview(review)) return;
        PresentAttachment(review, result == PreparationSendResult.Failed ? AppStrings.Get("UiAttachmentSendFailed") : "",
            sent: result == PreparationSendResult.Queued);
    }

    private void UpdateAttachmentReview()
    {
        if (_attachmentReview is not { } review || !CanUseAttachmentReview(review) || !review.Busy) return;
        var progress = _attachmentModel.PendingPreparations.FirstOrDefault(row => row.Id == review.PreparationId);
        if (progress is not null) PresentAttachment(review, progress.Status, progress.Progress.CanCancel);
    }

    private void CheckAttachmentReview()
    {
        if (_attachmentReview is not { } review || CanUseAttachmentReview(review)) return;
        PresentAttachment(review, "", dismiss: true);
        ClearAttachmentReview();
    }

    private void ClearAttachmentReview()
    {
        var review = _attachmentReview;
        _attachmentReview = null;
        if (review is null) return;
        if (review.PreparationId is { } preparation) _attachmentModel.CancelPreparation(preparation);
        try { review.Editor?.Close(); }
        finally { if (!review.Busy) ClearAttachmentEdits(review); }
    }

    private unsafe void PresentAttachment(AttachmentReview review, string status, bool canCancel = false,
        bool sent = false, bool dismiss = false)
    {
        if (_view == 0) return;
        var frame = JsonSerializer.SerializeToUtf8Bytes(new
        {
            generation = review.Generation, busy = review.Busy, editing = review.Editing,
            edits = review.Trims.Keys.Concat(review.Photos.Keys), status, canCancel, sent, dismiss
        });
        try { fixed (byte* data = frame) Present(_view, data, frame.Length, 3); }
        finally { CryptographicOperations.ZeroMemory(frame); }
    }
}
