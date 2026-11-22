// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Presentation;
using MsgAT.Windows.Views;

namespace MsgAT.Windows;

public partial class MainWindow
{
    private QtWorkspaceSession? _qtWorkspace;
    private Window? _qtWorkspaceDialog;
    private string? _qtWindowGeometry;
    private int _qtSidebarWidth;
    private bool _qtLaunchQueued;
    internal bool UseQtWorkspace { get; set; }
    internal bool IsWorkspaceActive => _qtWorkspace?.IsForeground == true || IsActive || HasActiveQtWorkspaceDialog();

    private bool CanShowQtWorkspace => UseQtWorkspace && !_appLocked && !_exiting &&
        _viewModel is { IsWorkspaceReady: true, IsChatContentVisible: true } && !_reviewingRecording && _recordingDialog is null &&
        (_services?.Calls.IsIncoming != true || _qtWorkspace?.IsOpen == true);

    private void ScheduleQtWorkspace()
    {
        if (_qtLaunchQueued || !CanShowQtWorkspace || !IsVisible) return;
        if (_qtWorkspace is not null)
        {
            TryShowQtWorkspace();
            return;
        }
        _qtLaunchQueued = true;
        Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, new Action(() =>
        {
            _qtLaunchQueued = false;
            if (!CanShowQtWorkspace || !IsVisible || _viewModel is not { } model || _qtWorkspace is not null) return;
            try
            {
                _qtWorkspace = new QtWorkspaceSession(model,
                    () => _appLocked || _exiting,
                    target =>
                    {
                        if (model.CanChangeSelection && ConfirmConversationNavigation(model, target.Uuid,
                                nativeOwner: _qtWorkspace?.HostWindow ?? 0) &&
                            model.Conversations.Contains(target))
                            model.SelectedConversation = target;
                    },
                    text => !_appLocked && !_exiting && MessageTextBlock.CopyToClipboard(text, this, showSuccess: false),
                    Dispatcher, Hide, RequestQtClose, QtPresenceChanged, StartCallAsync, OpenQtWorkspaceCommandAsync,
                    () => ConfirmDiscardMessageEdit(model, nativeOwner: _qtWorkspace?.HostWindow ?? 0), RetrySendAsync, OpenMessageCard,
                    ChangeQtConversationAsync, ChangeQtMessagesAsync);
                _qtWorkspace.SetChatTextPercentage(_chatTextPercentage);
                _qtWorkspace.SetAppLockEnabled(_appLockEnabled);
                _qtWorkspace.Run(_qtWindowGeometry, _qtSidebarWidth);
            }
            catch (Exception error)
            {
                UseQtWorkspace = false;
                Trace.TraceError("Qt workspace startup failed ({0}).", error.GetType().Name);
                if (!_exiting)
                {
                    Show();
                    MessageBox.Show(this, AppStrings.Get("UiQtWorkspaceUnavailable"),
                        AppStrings.Get("UiAppStartError"), MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
            finally
            {
                _qtWorkspace = null;
                if (!_exiting && !IsVisible) Show();
            }
        }));
    }

    internal bool TryShowQtWorkspace()
    {
        if (!CanShowQtWorkspace || _qtWorkspace is not { IsOpen: true } workspace) return false;
        Hide();
        workspace.Show();
        PresentQtArchiveUndo();
        if (_services?.Calls is { } calls && !workspace.HasCallPresentation) TryPresentQtCall(calls);
        return true;
    }

    private void RequestQtClose()
    {
        if (_exiting || _qtWorkspace is not { } workspace) return;
        var app = (App)Application.Current;
        if (_closeToTray)
        {
            SaveWindowPlacement();
            if (app.HasTrayIcon) workspace.Hide();
            else workspace.Minimize();
        }
        else Dispatcher.BeginInvoke(new Action(app.ExitApplication));
    }

    private void QtPresenceChanged()
    {
        UpdateActivityForeground();
        UpdateChatActivity();
        if (_qtWorkspace is { IsForeground: true, HasCallPresentation: false } &&
            _services?.Calls is { IsIncoming: true } incoming) TryPresentQtCall(incoming);
    }

    private void MaskQtWorkspace()
    {
        if (_qtWorkspace is not { IsOpen: true } workspace) return;
        var wasActive = workspace.IsForeground || HasActiveQtWorkspaceDialog();
        workspace.Refresh();
        workspace.Hide();
        if (_qtWorkspaceDialog is RecordingDialog recording) recording.MaskAndClose();
        else _qtWorkspaceDialog?.Close();
        if (wasActive && !_exiting)
        {
            Show();
            Activate();
        }
    }

    private async Task OpenQtWorkspaceCommandAsync(string command, nint owner)
    {
        if (!CanShowQtWorkspace || _qtWorkspaceDialog is not null || _viewModel is not { } model || _services is not { } services)
            return;
        switch (command)
        {
            case "pasteImage":
                var paste = PasteComposerMediaAsync(imageOnly: true);
                _qtWorkspace?.TrackAttachmentWork(paste);
                await paste;
                break;
            case "contacts":
                var directory = new ContactsDialog(services, peer => OpenConfirmedContactConversation(model, peer));
                directory.ConfirmOpenChat = peer => ConfirmConversationNavigation(model, peer, directory);
                ShowWorkspaceDialog(directory, owner);
                break;
            case "addFriend":
                await OpenAddFriendAsync(owner);
                break;
            case "requests":
                ShowWorkspaceDialog(new FriendRequestsDialog(model), owner);
                break;
            case "settings":
                OpenQtSettings(services);
                break;
            case "manageFolders":
                ShowWorkspaceDialog(new ConversationFoldersDialog(services, model.Conversations.ToArray(),
                    () => !_appLocked && !_exiting && ReferenceEquals(_viewModel, model)), owner);
                break;
            case "saved":
                OpenSavedMessages(owner);
                break;
            case "stickerLibrary":
                ShowWorkspaceDialog(new StickerLibraryDialog(services), owner);
                break;
            case "stickers" when model.SelectedConversation is { } stickerPeer:
                var generation = _appLockGeneration;
                var stickers = new StickerLibraryDialog(services, async (item, token) =>
                {
                    if (_appLocked || _exiting || generation != _appLockGeneration || !ReferenceEquals(_viewModel, model) ||
                        model.SelectedConversation?.Uuid != stickerPeer.Uuid || model.CaptureStickerSender() is not { } send)
                        return MsgAT.Windows.Core.Storage.PreparationSendResult.Failed;
                    return await send(item, token);
                }, () => model.PendingPreparations.LastOrDefault(row => row.IsSticker));
                ShowWorkspaceDialog(stickers, owner);
                break;
            case "sendQueue":
                OpenSendQueue(owner: owner);
                break;
            case "chatQueue" when model.SelectedConversation is { } queuePeer:
                OpenSendQueue(queuePeer.Uuid, owner);
                break;
            case "sharedMedia" when model.SelectedConversation is { } mediaPeer:
                OpenSharedMedia(mediaPeer.Uuid, owner: owner);
                break;
            case "pinnedMessages" when model.SelectedConversation is { } pinnedPeer:
                OpenPinnedMessages(pinnedPeer.Uuid, owner);
                break;
            case "historyNavigation":
                OpenHistoryNavigation(owner: owner);
                break;
            case "forwardQueue":
                OpenForwardingQueue(owner);
                break;
            case "callHistory":
                await OpenCallHistoryAsync(owner);
                break;
            case "appLock":
                await AuthenticateAppLockAsync(!_appLockEnabled, owner);
                break;
            case "lockNow":
                LockApp();
                if (_appLocked && !_exiting && !IsVisible) { Show(); Activate(); }
                break;
            case "record":
                OpenVoiceRecorder(owner);
                break;
            case "sendOptions":
                await OpenSendOptionsAsync(scheduleDraft: false, owner: owner);
                break;
            case "scheduleDraft":
                await OpenSendOptionsAsync(scheduleDraft: true, owner: owner);
                break;
            case "shareContact":
                OpenShareContact(owner);
                break;
            case "shareLocation":
                OpenShareLocation(owner);
                break;
        }
    }

    private bool ShowWorkspaceDialog(Window dialog, nint owner)
    {
        if (_appLocked || _exiting) return false;
        if (owner == 0)
        {
            dialog.Owner = this;
            return dialog.ShowDialog() == true;
        }

        if (_qtWorkspace is not { IsOpen: true } workspace || _qtWorkspaceDialog is not null) return false;
        _qtWorkspaceDialog = dialog;
        try
        {
            dialog.Owner = null;
            var interop = new WindowInteropHelper(dialog);
            interop.Owner = owner;
            dialog.Loaded += (_, _) => workspace.UpdateCallPresentation();
            return dialog.ShowDialog() == true;
        }
        finally { _qtWorkspaceDialog = null; }
    }

    private bool IsQtWorkspaceDialog(Window dialog)
    {
        if (_qtWorkspaceDialog is not { } root) return false;
        for (Window? current = dialog; current is not null; current = current.Owner)
            if (ReferenceEquals(current, root)) return true;
        return false;
    }

    private bool HasActiveQtWorkspaceDialog() => _qtWorkspaceDialog is not null &&
        System.Windows.Application.Current.Windows.OfType<Window>().Any(window => window.IsActive && IsQtWorkspaceDialog(window));

    private void ShowWorkspaceNotice(string message)
    {
        if (_appLocked || _exiting) return;
        if (IsVisible) TransientNotice.Show(this, message);
        else _qtWorkspace?.ShowNotice(message);
    }

    internal void StopQtWorkspace() => _qtWorkspace?.Close();
}
