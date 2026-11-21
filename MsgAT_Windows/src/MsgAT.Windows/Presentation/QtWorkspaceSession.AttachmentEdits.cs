// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows.Interop;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Views;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private sealed record ReviewedPhoto(EditedPhotoContent Content, PhotoAdjustment Adjustment);

    private bool EditAttachmentSelection(AttachmentReview review, int action, JsonElement payload)
    {
        if (payload.GetProperty("path").GetString() is not { } path ||
            path.Contains('\0') || !Path.IsPathFullyQualified(path)) return false;
        if (action == 30)
        {
            review.Trims.Remove(path);
            ReplaceReviewedPhoto(review, path, null);
            return true;
        }

        if (!_model.CanCompose || ReadWindowHandle(payload) is not { } owner) return false;
        var mime = EncryptedObjectStore.MimeForPath(path);
        var isVideo = mime.StartsWith("video/", StringComparison.Ordinal);
        if (!isVideo && !PhotoCompression.Supports(mime)) return false;

        review.Editing = true;
        PresentAttachment(review, "");
        _ownerContext.Post(_ => OpenAttachmentEditor(review, path, owner, mime, isVideo), null);
        return true;
    }

    private void OpenAttachmentEditor(AttachmentReview review, string path, nint owner, string mime, bool isVideo)
    {
        if (!CanUseAttachmentReview(review)) return;
        var status = "";
        try
        {
            if (isVideo)
            {
                var editor = new VideoTrimDialog(path, review.Trims.GetValueOrDefault(path));
                if (ShowAttachmentEditor(editor) && CanUseAttachmentReview(review))
                {
                    if (editor.Result is { } trim) review.Trims[path] = trim;
                    else review.Trims.Remove(path);
                }
            }
            else
            {
                var editor = new PhotoEditDialog(path, null, mime,
                    review.Photos.GetValueOrDefault(path)?.Adjustment ?? PhotoAdjustment.Original);
                if (ShowAttachmentEditor(editor))
                {
                    if (!CanUseAttachmentReview(review))
                    {
                        if (editor.Result is { } stale) CryptographicOperations.ZeroMemory(stale.Bytes);
                    }
                    else status = StorePhotoEdit(review, path, mime, editor);
                }
            }
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException or
            InvalidOperationException or ExternalException)
        {
            status = AppStrings.Get(isVideo ? "UiVideoOpenError" : "UiPhotoOpenError");
        }
        finally
        {
            review.Editor = null;
            review.Editing = false;
            if (CanUseAttachmentReview(review)) PresentAttachment(review, status);
        }

        bool ShowAttachmentEditor(System.Windows.Window editor)
        {
            review.Editor = editor;
            var interop = new WindowInteropHelper(editor);
            interop.Owner = owner;
            return editor.ShowDialog() == true;
        }
    }

    private static string StorePhotoEdit(AttachmentReview review, string path, string mime, PhotoEditDialog editor)
    {
        if (editor.Result is not { } result)
        {
            ReplaceReviewedPhoto(review, path, null);
            return "";
        }

        var retained = review.Photos.Where(photo => photo.Key != path).Sum(photo => photo.Value.Content.Bytes.LongLength);
        if (retained + result.Bytes.LongLength > 64L * 1024 * 1024)
        {
            CryptographicOperations.ZeroMemory(result.Bytes);
            return AppStrings.Get("UiUndoOtherEdit");
        }

        var filename = string.Concat(Path.GetFileNameWithoutExtension(path).Take(160)) +
            (mime == "image/png" ? "-edited.png" : "-edited.jpg");
        ReplaceReviewedPhoto(review, path, new(new(filename, result.Bytes), editor.Adjustment));
        return "";
    }

    private static void ReplaceReviewedPhoto(AttachmentReview review, string path, ReviewedPhoto? photo)
    {
        if (review.Photos.Remove(path, out var previous)) CryptographicOperations.ZeroMemory(previous.Content.Bytes);
        if (photo is not null) review.Photos[path] = photo;
    }

    private static void ClearAttachmentEdits(AttachmentReview review)
    {
        foreach (var photo in review.Photos.Values) CryptographicOperations.ZeroMemory(photo.Content.Bytes);
        review.Photos.Clear();
        review.Trims.Clear();
    }
}
