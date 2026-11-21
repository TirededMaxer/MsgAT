// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private sealed class SettingsPresentation(long id, MessagingService messaging, Func<object> overview, Func<object> network,
        Func<QtSettingsAction, Task<QtSettingsResult>> apply)
    {
        internal readonly long Id = id;
        internal readonly MessagingService Messaging = messaging;
        internal readonly Func<object> Overview = overview;
        internal readonly Func<object> Network = network;
        internal readonly Func<QtSettingsAction, Task<QtSettingsResult>> Apply = apply;
        internal bool Busy;
        internal string? Validation;
        internal string? Feedback;
        internal byte[]? ProfileAvatar;
        internal volatile bool StorageOpen;
        internal int StorageNotificationQueued;
    }

    private SettingsPresentation? _settings;
    private long _settingsSequence;

    internal void ShowSettings(MessagingService messaging, Func<object> overview, Func<object> network,
        Func<QtSettingsAction, Task<QtSettingsResult>> apply)
    {
        if (_view == 0 || !IsVisible(_view) || _settings is not null) return;
        var settings = _settings = new SettingsPresentation(++_settingsSequence, messaging, overview, network, apply);
        IdentityLifecycle.PendingCountChanged += SettingsRevocationsChanged;
        messaging.AttachmentProgressChanged += SettingsAttachmentProgressChanged;
        PublishSettings(settings, open: true, updateOverview: true, updateNetwork: true);
    }

    private bool HandleSettings(JsonElement payload)
    {
        if (_settings is not { } settings || payload.GetProperty("id").GetInt64() != settings.Id) return false;
        var command = payload.GetProperty("command").GetString();
        if (command == "close")
        {
            if (settings.Busy) return false;
            ClearSettings(dismiss: false);
            return true;
        }
        if (settings.Busy) return false;
        if (command == "discardProfileAvatar")
        {
            ReleaseProfileAvatar(settings);
            return true;
        }
        if (command == "leaveStorage")
        {
            settings.StorageOpen = false;
            return true;
        }
        if (command == "validateNetwork")
        {
            settings.Validation = ReadNetworkInput(payload, forSave: false).ValidationIssue();
            settings.Feedback = settings.Validation;
            PublishSettings(settings);
            return true;
        }

        if (command is not ("saveNetwork" or "theme" or "language" or "textSize" or "tray" or "copyIdentity" or
            "loadProfile" or "cropProfileAvatar" or "saveProfile" or
            "loadPrivacy" or "savePrivacy" or "loadNotifications" or "saveNotifications" or
            "systemNotifications" or "loadStorage" or "refreshStorage" or "cacheLimit" or "clearCache" or
            "loadExportFolder" or "saveExportFolder" or "resetIdentity") ||
            ReadWindowHandle(payload) is not { } owner) return false;

        var input = command == "saveNetwork" ? ReadNetworkInput(payload, forSave: true) : null;
        if (input is not null && (settings.Validation = input.ValidationIssue()) is not null)
        {
            settings.Feedback = settings.Validation;
            PublishSettings(settings);
            return false;
        }
        var value = command is "theme" or "language" or "textSize" ? payload.GetProperty("value").GetInt32() : 0;
        var enabled = command == "tray" && payload.GetProperty("value").GetBoolean();
        var privacy = command == "savePrivacy" ? new QtPrivacyDraft(payload.GetProperty("read").GetBoolean(),
            payload.GetProperty("online").GetBoolean(), payload.GetProperty("typing").GetBoolean(),
            payload.GetProperty("autoDownload").GetBoolean()) : null;
        var notifications = command == "saveNotifications" ? new MessageAlertSettings(payload.GetProperty("alerts").GetBoolean(),
            payload.GetProperty("sound").GetBoolean()) : null;
        var cacheLimit = command == "cacheLimit" ? payload.GetProperty("limit").GetInt64() : 0;
        var cacheCategories = command == "clearCache"
            ? payload.GetProperty("categories").EnumerateArray().Select(item => (AttachmentCacheCategory)item.GetInt32()).Distinct().ToArray()
            : null;
        var exportFolder = command == "saveExportFolder" ? payload.GetProperty("folder").GetString() : null;
        var profileText = command switch
        {
            "saveProfile" => payload.GetProperty("nickname").GetString(),
            "cropProfileAvatar" => payload.GetProperty("path").GetString(),
            _ => null
        };
        var removeAvatar = command == "saveProfile" && payload.GetProperty("removeAvatar").GetBoolean();
        var action = new QtSettingsAction(command, owner, value, enabled, input, privacy, notifications,
            cacheLimit, cacheCategories, exportFolder, profileText, removeAvatar);
        var isSave = input is not null || privacy is not null || notifications is not null || command == "saveProfile";
        settings.Busy = true;
        if (command == "loadStorage") settings.StorageOpen = true;
        settings.Feedback = isSave || (command is "cacheLimit" or "saveExportFolder") ? "UiSaving" : command switch
        {
            "loadPrivacy" or "loadNotifications" or "loadProfile" => "UiLoading",
            "loadStorage" => "UiCalculating",
            _ => null
        };
        PublishSettings(settings);
        _ownerContext.Post(_ =>
        {
            if (IsCurrentSettings(settings)) TrackMessageAction(ApplyAsync());
        }, null);
        return true;

        async Task ApplyAsync()
        {
            var avatar = command == "saveProfile" ? settings.ProfileAvatar : null;
            if (command == "loadProfile") ReleaseProfileAvatar(settings);
            if (command == "saveProfile") settings.ProfileAvatar = null;
            byte[]? image = null;
            try
            {
                QtSettingsResult result;
                try { result = await settings.Apply(action with { StagedAvatar = avatar }); }
                catch (Exception error) when (error is IOException or InvalidOperationException or UnauthorizedAccessException or
                    OperationCanceledException or ArgumentException or CryptographicException or Microsoft.Data.Sqlite.SqliteException)
                {
                    Trace.TraceWarning("Qt settings command failed ({0}).", error.GetType().Name);
                    result = new(command switch
                    {
                        "loadPrivacy" => "UiPrivacyLoadError", "savePrivacy" => "UiPrivacySaveError",
                        "saveNotifications" => "UiNotificationSaveError",
                        "loadStorage" or "refreshStorage" => "UiCacheLoadError",
                        "cacheLimit" => "UiCacheLimitError", "clearCache" => "UiCacheClearError",
                        "saveExportFolder" => "UiExportFolderFailed",
                        "saveProfile" => "UiProfileSaveFailed", "cropProfileAvatar" => "UiProfileImageUnavailable",
                        _ => "UiSettingsSaveFailed"
                    });
                }
                image = result.ProfileImage;
                if (!IsCurrentSettings(settings)) return;
                var saved = isSave && result.Feedback is null;
                if (avatar is not null && !saved)
                {
                    settings.ProfileAvatar = avatar;
                    avatar = null;
                }
                if (image is not null && command == "cropProfileAvatar")
                {
                    ReleaseProfileAvatar(settings);
                    settings.ProfileAvatar = image;
                }
                settings.Busy = false;
                settings.Feedback = result.Feedback;
                if (command == "clearCache" && result.Feedback != "UiCacheClearError") ClearReceivedAttachmentPreview();
                var updateOverview = (command is "theme" or "language" or "textSize" or "tray") || (saved && command == "saveProfile");
                PublishSettings(settings, updateOverview: updateOverview, updateNetwork: saved && input is not null,
                    saved: saved, preferences: result.Preferences, completed: command, profileImage: image);
            }
            finally
            {
                if (avatar is not null) CryptographicOperations.ZeroMemory(avatar);
                if (image is not null && !ReferenceEquals(settings.ProfileAvatar, image)) CryptographicOperations.ZeroMemory(image);
            }
        }
    }

    private bool IsCurrentSettings(SettingsPresentation settings) => _view != 0 && IsVisible(_view) && ReferenceEquals(_settings, settings);

    private static NetworkSettingsInput ReadNetworkInput(JsonElement payload, bool forSave)
    {
        string Text(string key) => payload.GetProperty(key).GetString() ?? "";
        bool Boolean(string key) => payload.GetProperty(key).GetBoolean();
        return new(Text("server"), Text("port"), Text("turnServer"), Text("turnPort"), forSave ? Text("turnUser") : "",
            forSave ? Text("turnPassword") : "", forSave && Boolean("turnTls"), Text("fingerprint"),
            forSave && Boolean("developmentCertificate"), forSave && Boolean("keepPassword"));
    }

    private void SettingsRevocationsChanged(object? sender, EventArgs args)
    {
        _ownerContext.Post(_ =>
        {
            if (_settings is { } settings && IsCurrentSettings(settings)) PublishSettings(settings, updateOverview: true);
        }, null);
    }

    private void SettingsAttachmentProgressChanged(object? sender, AttachmentProgress progress)
    {
        if (progress.Phase == AttachmentPhase.Uploading ||
            (progress.Phase == AttachmentPhase.Downloading && progress.Bytes != 0)) return;
        var settings = _settings;
        if (settings is null || !settings.StorageOpen || Interlocked.Exchange(ref settings.StorageNotificationQueued, 1) != 0) return;
        _ownerContext.Post(_ =>
        {
            Interlocked.Exchange(ref settings.StorageNotificationQueued, 0);
            if (settings.StorageOpen && IsCurrentSettings(settings)) PublishSettings(settings, storageChanged: true);
        }, null);
    }

    private void ClearSettings(bool dismiss = true)
    {
        if (_settings is not { } settings) return;
        _settings = null;
        ReleaseProfileAvatar(settings);
        IdentityLifecycle.PendingCountChanged -= SettingsRevocationsChanged;
        settings.Messaging.AttachmentProgressChanged -= SettingsAttachmentProgressChanged;
        if (dismiss) PresentSettingsFrame(new { dismiss = true });
    }

    private static void ReleaseProfileAvatar(SettingsPresentation settings)
    {
        if (settings.ProfileAvatar is { } avatar) CryptographicOperations.ZeroMemory(avatar);
        settings.ProfileAvatar = null;
    }

    private void PublishSettings(SettingsPresentation settings, bool open = false, bool updateOverview = false,
        bool updateNetwork = false, bool saved = false, object? preferences = null, string? completed = null,
        bool storageChanged = false, byte[]? profileImage = null)
    {
        var key = settings.Feedback;
        PresentSettingsFrame(new
        {
            id = settings.Id, open, busy = settings.Busy, valid = settings.Validation is null,
            overview = updateOverview ? settings.Overview() : null, network = updateNetwork ? settings.Network() : null,
            message = key is null ? "" : AppStrings.Get(key), saved, preferences, completed, storageChanged, profileImage
        });
    }

    private unsafe void PresentSettingsFrame<T>(T frame)
    {
        if (_view == 0) return;
        var data = JsonSerializer.SerializeToUtf8Bytes(frame);
        try { fixed (byte* bytes = data) Present(_view, bytes, data.Length, 8); }
        finally { CryptographicOperations.ZeroMemory(data); }
    }
}

internal sealed record QtSettingsAction(string Command, nint Owner, int Value, bool Enabled, NetworkSettingsInput? Network,
    QtPrivacyDraft? Privacy, MessageAlertSettings? Notifications, long CacheLimit,
    AttachmentCacheCategory[]? CacheCategories, string? ExportFolder, string? ProfileText, bool RemoveAvatar,
    byte[]? StagedAvatar = null)
{
    public override string ToString() => "Settings action";
}

internal sealed record QtPrivacyDraft(bool Read, bool Online, bool Typing, bool AutoDownload);
internal sealed record QtSettingsResult(string? Feedback = null, object? Preferences = null, byte[]? ProfileImage = null);
