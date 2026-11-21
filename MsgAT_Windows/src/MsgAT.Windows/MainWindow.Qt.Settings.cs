// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Presentation;
using MsgAT.Windows.Views;

namespace MsgAT.Windows;

public partial class MainWindow
{
    private void OpenQtSettings(AppServices services)
    {
        if (_qtWorkspace is not { IsOpen: true } workspace) return;
        workspace.ShowSettings(services.Messaging, () => QtSettingsOverview(services), () => QtNetworkSnapshot(services.Settings.Snapshot),
            action => ApplyQtSettingsAsync(services, action));
    }

    private async Task<QtSettingsResult> ApplyQtSettingsAsync(AppServices services, QtSettingsAction action)
    {
        if (_appLocked || _exiting || !ReferenceEquals(_services, services)) return new();
        switch (action.Command)
        {
            case "theme" when Enum.IsDefined((AppThemeMode)action.Value):
                SetInterfaceTheme((AppThemeMode)action.Value);
                break;
            case "language" when Enum.IsDefined((AppLanguage)action.Value):
                SetInterfaceLanguage((AppLanguage)action.Value);
                break;
            case "textSize" when SettingsDialog.ChatTextPercentages.Contains(action.Value):
                SetInterfaceTextSize(action.Value);
                break;
            case "tray":
                if (!SetCloseToTray(action.Enabled)) return new("UiCloseToTraySaveFailed");
                break;
            case "copyIdentity":
                return new(MessageTextBlock.CopyToClipboard(services.Settings.MyUuid, this, showSuccess: false)
                    ? "UiMessageCopied" : "UiMessageCopyFailed");
            case "loadProfile":
                var snapshot = services.Settings.Snapshot;
                var avatar = await Task.Run(() => ReadQtProfileAvatar(services.Objects, snapshot.AvatarPath));
                return new(Preferences: new { page = "profile", nickname = snapshot.Nickname ?? "",
                    hasAvatar = !string.IsNullOrEmpty(snapshot.AvatarPath) }, ProfileImage: avatar);
            case "cropProfileAvatar":
                if (action.ProfileText is not { } path || !Path.IsPathFullyQualified(path)) return new("UiProfileImageUnavailable");
                var generation = _appLockGeneration;
                var crop = new AvatarCropDialog(path);
                if (ShowWorkspaceDialog(crop, action.Owner) && !_appLocked && !_exiting && generation == _appLockGeneration &&
                    ReferenceEquals(_services, services))
                    return new(ProfileImage: crop.Result);
                if (crop.Result is { } unusedAvatar) CryptographicOperations.ZeroMemory(unusedAvatar);
                return new();
            case "saveProfile":
                var nickname = action.ProfileText?.Trim();
                if (string.IsNullOrEmpty(nickname) || Encoding.UTF8.GetByteCount(nickname) > 256)
                    return new("UiProfileSaveFailed");
                var photo = !action.RemoveAvatar && action.StagedAvatar is { } staged ? new ReadOnlyMemory<byte>(staged) : (ReadOnlyMemory<byte>?)null;
                return new(await SaveProfileAsync(services, nickname, photo, action.RemoveAvatar) ? null : "UiProfileSaveFailed");
            case "loadPrivacy":
                var read = await services.Messaging.ReadReceiptsEnabledAsync();
                var activity = await services.Messaging.ActivityPrivacyAsync();
                var autoDownload = await services.Messaging.AutomaticAttachmentDownloadsAsync();
                return new(Preferences: new { page = "privacy", read, online = activity.Online, typing = activity.Typing, autoDownload });
            case "savePrivacy" when action.Privacy is { } privacy:
                return new(await services.Messaging.SetPrivacyAsync(privacy.Read, privacy.Online, privacy.Typing,
                    autoDownload: privacy.AutoDownload) ? null : "UiPrivacySaveError");
            case "loadNotifications":
                var alerts = services.Messaging.NotificationSettings;
                return new(Preferences: new { page = "notifications", alerts = alerts.Enabled, sound = alerts.PlaySound });
            case "saveNotifications" when action.Notifications is { } notifications:
                var previous = services.Messaging.NotificationSettings;
                if (!await services.Messaging.SetNotificationSettingsAsync(notifications)) return new("UiNotificationSaveError");
                if (!notifications.Enabled || (previous.PlaySound && !notifications.PlaySound))
                    ((App)System.Windows.Application.Current).DismissMessageNotifications();
                break;
            case "systemNotifications":
                return new(MessageNotificationsDialog.TryOpenSystemSettings() ? null : "UiNotificationSystemOpenFailed");
            case "loadStorage":
            case "refreshStorage":
                return await ReadQtStorageAsync(services);
            case "cacheLimit":
                if (!EncryptedObjectStore.AutomaticCacheLimits.Contains(action.CacheLimit) ||
                    !await services.Messaging.SetAutomaticCacheLimitAsync(action.CacheLimit)) return new("UiCacheLimitError");
                return await ReadQtStorageAsync(services);
            case "clearCache":
                if (action.CacheCategories is not { Length: > 0 } categories ||
                    categories.Any(category => !Enum.IsDefined(category)) ||
                    !await services.Messaging.ClearAttachmentCacheAsync(categories)) return new("UiCacheClearError");
                return await ReadQtStorageAsync(services);
            case "loadExportFolder":
                return QtExportFolder(services.Settings);
            case "saveExportFolder":
                var folder = action.ExportFolder;
                if (folder is not null && !Path.IsPathFullyQualified(folder)) return new("UiExportFolderFailed");
                await Task.Run(() => services.Settings.Update(value => value with { AttachmentExportFolder = folder }));
                return QtExportFolder(services.Settings);
            case "resetIdentity":
                await CommitIdentityResetAsync(services);
                break;
            case "saveNetwork" when action.Network is { } input:
                if (input.ValidationIssue() is { } issue) return new(issue);
                await services.SaveNetworkSettingsAsync(input.ApplyTo(services.Settings.Snapshot));
                break;
        }
        return new();
    }

    private static byte[]? ReadQtProfileAvatar(EncryptedObjectStore objects, string? reference)
    {
        if (reference is null || !EncryptedObjectStore.IsReference(reference)) return null;
        byte[]? bytes = null;
        try
        {
            if (objects.Metadata(reference, "avatar") is not { Available: true, Size: > 0 and <= 512 * 1024 } metadata)
                return null;
            using var input = objects.OpenRead(reference);
            bytes = new byte[checked((int)metadata.Size)];
            input.ReadExactly(bytes);
            return bytes;
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or CryptographicException or
            OperationCanceledException or Microsoft.Data.Sqlite.SqliteException or FormatException)
        {
            if (bytes is not null) CryptographicOperations.ZeroMemory(bytes);
            return null;
        }
    }

    private static async Task<QtSettingsResult> ReadQtStorageAsync(AppServices services)
    {
        AttachmentCacheUsage usage;
        try { usage = await services.Messaging.AttachmentCacheUsageAsync(); }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException or
            Microsoft.Data.Sqlite.SqliteException or IOException)
        {
            return new("UiCacheLoadError");
        }
        return new(Preferences: new
        {
            page = "storage",
            summary = AppStrings.Format("UiCacheReceivedSize", AttachmentCacheDialog.Size(usage.Bytes)),
            categories = Enum.GetValues<AttachmentCacheCategory>().Where(usage.BytesByCategory.ContainsKey).Select(category =>
            {
                var bytes = usage.BytesByCategory[category];
                return new { value = (int)category, bytes, title = $"{AttachmentCacheDialog.CategoryLabel(category)}  ·  {AttachmentCacheDialog.Size(bytes)}" };
            }),
            limit = usage.AutomaticLimitBytes,
            limits = EncryptedObjectStore.AutomaticCacheLimits.Select(value => new { value, title = AttachmentCacheDialog.LimitLabel(value) }),
            budget = usage.AutomaticLimitBytes > 0 ? AppStrings.Format("UiCacheAutoRemaining",
                AttachmentCacheDialog.Size(usage.AutomaticBudgetRemainingBytes)) : "",
            budgetUsed = usage.AutomaticLimitBytes > 0 ? 1d - (double)usage.AutomaticBudgetRemainingBytes / usage.AutomaticLimitBytes : 0d,
            pending = usage.PendingBytes > 0 ? AppStrings.Format("UiCacheRemoving", AttachmentCacheDialog.Size(usage.PendingBytes)) : ""
        });
    }

    private static QtSettingsResult QtExportFolder(SecureSettingsStore settings) =>
        new(Preferences: new { page = "exportFolder", folder = settings.Snapshot.AttachmentExportFolder });

    private object QtSettingsOverview(AppServices services)
    {
        var settings = services.Settings.Snapshot;
        var identity = services.Settings.MyUuid;
        var pending = IdentityLifecycle.PendingCount;
        return new
        {
            identity,
            alias = string.IsNullOrWhiteSpace(settings.Nickname) ? identity[..Math.Min(identity.Length, 12)] : settings.Nickname,
            revocations = pending > 0 ? AppStrings.Format("UiPendingIdentityRevocations", pending) : "",
            tray = _closeToTray,
            theme = (int)WindowAppearance.Theme, language = (int)AppStrings.Language, size = _chatTextPercentage,
            themes = Choices(((int)AppThemeMode.System, "UiFollowSystem"), ((int)AppThemeMode.Light, "UiLight"), ((int)AppThemeMode.Dark, "UiDark")),
            languages = Choices(((int)AppLanguage.System, "UiFollowSystem"), ((int)AppLanguage.English, "UiEnglish"),
                ((int)AppLanguage.SimplifiedChinese, "UiSimplifiedChinese")),
            sizes = SettingsDialog.ChatTextPercentages.Select(value => new
                { value, title = (value / 100d).ToString("P0", AppStrings.InterfaceCulture) }),
            labels = Strings(("title", "UiSettings"), ("search", "UiSearchSettings"), ("empty", "UiNoSettingsResults"),
                ("identity", "UiContactPublicId"), ("copyIdentity", "UiCopyMyUuid"), ("profile", "UiEditProfile"),
                ("network", "UiNetwork"), ("advanced", "UiAdvancedNetwork"), ("turn", "UiTurnRelay"),
                ("fingerprint", "UiCertificatePin"), ("notifications", "UiNotificationSettings"), ("privacy", "UiPrivacy"),
                ("language", "UiLanguage"), ("theme", "UiTheme"), ("textSize", "UiTextSize"), ("tray", "UiCloseToTray"),
                ("storage", "UiStorageUsage"), ("exportFolder", "UiExportFolder"), ("reset", "UiResetIdentity"),
                ("close", "UiClose"), ("back", "UiBackToSettings"), ("cancel", "UiCancel"), ("save", "UiSave"), ("retry", "UiRetry"),
                ("server", "UiServerAddress"), ("port", "UiServerPort"), ("turnServer", "UiTurnServer"), ("turnPort", "UiTurnPort"),
                ("turnUser", "UiTurnUsername"), ("turnPassword", "UiTurnPassword"), ("keepPassword", "UiTurnKeepPassword"),
                ("turnTls", "UiTurnTls"), ("developmentCertificate", "UiAllowDevelopmentCertificate"),
                ("readReceipts", "UiReadReceipts"), ("online", "UiShareOnline"), ("typing", "UiShareTyping"),
                ("autoDownload", "UiAutomaticDownloads"), ("activityNote", "UiActivityPrivacyNote"), ("readNote", "UiReadPrivacyNote"),
                ("alerts", "UiMessageAlerts"), ("alertSound", "UiMessageAlertSound"),
                ("systemNotifications", "UiOpenNotificationSystemSettings"), ("cacheLimit", "UiCacheAutoLimit"),
                ("cacheLimitNote", "UiCacheLimitNote"), ("clearCache", "UiCacheClear"), ("clearTitle", "UiCacheClearTitle"),
                ("clearWarning", "UiCacheClearWarning"), ("clearSelected", "UiCacheClearSelectedSize"), ("confirm", "UiConfirm"),
                ("exportDefault", "UiExportFolderDefault"), ("exportChoose", "UiExportFolderChoose"),
                ("nickname", "UiProfileNickname"), ("nicknameTooLong", "UiProfileNicknameTooLong"),
                ("avatar", "UiProfileAvatar"), ("chooseAvatar", "UiProfileChooseAvatar"), ("changeAvatar", "UiProfileChangeAvatar"),
                ("removeAvatar", "UiProfileRemoveAvatar"), ("resetWarning", "UiResetWarningRestart")),
            terms = Strings(("identity", "UiSettingsTermsIdentity"), ("profile", "UiSettingsTermsProfile"),
                ("network", "UiSettingsTermsNetwork"), ("advanced", "UiSettingsTermsAdvanced"), ("turn", "UiSettingsTermsTurn"),
                ("certificate", "UiSettingsTermsCertificate"), ("notifications", "UiSettingsTermsNotifications"),
                ("privacy", "UiSettingsTermsPrivacy"), ("language", "UiSettingsTermsLanguage"), ("theme", "UiSettingsTermsTheme"),
                ("size", "UiSettingsTermsSize"), ("tray", "UiSettingsTermsTray"), ("storage", "UiSettingsTermsStorage"),
                ("exportFolder", "UiSettingsTermsExportFolder"), ("reset", "UiSettingsTermsReset"))
        };

        static object[] Choices(params (int Value, string Resource)[] values) =>
            values.Select(choice => (object)new { value = choice.Value, title = AppStrings.Get(choice.Resource) }).ToArray();
        static Dictionary<string, string> Strings(params (string Key, string Resource)[] values) =>
            values.ToDictionary(pair => pair.Key, pair => AppStrings.Get(pair.Resource), StringComparer.Ordinal);
    }

    private static object QtNetworkSnapshot(SecureSettings settings) => new
    {
        server = settings.ServerAddress ?? "",
        port = settings.ServerPort == 0 ? "" : settings.ServerPort.ToString(CultureInfo.InvariantCulture),
        turnServer = settings.TurnServer ?? "", turnPort = settings.TurnPort.ToString(CultureInfo.InvariantCulture),
        turnUser = settings.TurnUsername ?? "", hasPassword = !string.IsNullOrEmpty(settings.TurnPassword),
        turnTls = settings.TurnUseTls, fingerprint = settings.ServerCertificateSha256 ?? "",
        developmentCertificate = settings.AllowDevelopmentCertificate
    };
}
