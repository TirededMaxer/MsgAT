// SPDX-License-Identifier: AGPL-3.0-only
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Presentation;
using MsgAT.Windows.Views;

namespace MsgAT.Windows;

public partial class MainWindow
{
    private async Task ChangeQtConversationAsync(QtConversationAction action)
    {
        if (_appLocked || _exiting || _callWindow?.CoversChat == true || _qtWorkspace is not { IsOpen: true } workspace ||
            _viewModel is not { IsWorkspaceReady: true } model) return;
        if (action.Command == "undoArchive")
        {
            if (action.Version == _archiveUndoAnimation && _archiveUndo is { } notice)
                await ChangeArchiveAsync(notice.Peer, !notice.Archived, showUndo: false);
            return;
        }

        if (action.Folder != model.FolderKey ||
            model.VisibleConversations.FirstOrDefault(row => row.Uuid == action.Peer) is not { } conversation) return;
        var peer = conversation.Uuid;
        var folderId = model.CurrentCustomFolderId;
        var pinned = conversation.PinOrderIn(folderId) > 0;

        switch (action.Command)
        {
            case "pin" when !pinned:
            case "unpin" when pinned:
                await model.ChangeConversationAsync(peer, action.Command, folderId: folderId);
                break;
            case "pinUp" or "pinDown" when pinned:
                var before = action.Command == "pinUp";
                var pins = model.VisibleConversations.TakeWhile(row => row.IsPinned).ToArray();
                var index = Array.IndexOf(pins, conversation);
                var neighbor = index + (before ? -1 : 1);
                if (index >= 0 && neighbor >= 0 && neighbor < pins.Length && action.Target == pins[neighbor].Uuid)
                    await model.MovePinAsync(model.FolderKey, peer, pins[neighbor].Uuid, before);
                break;
            case "archive" when !conversation.Archived:
            case "unarchive" when conversation.Archived:
                await ChangeArchiveAsync(peer, !conversation.Archived);
                break;
            case "read" when conversation.HasUnread:
            case "unread" when !conversation.HasUnread:
            case "unmute" when conversation.IsMuted:
                await model.ChangeConversationAsync(peer, action.Command);
                break;
            case "muteHour" or "muteEightHours" or "muteTwoDays" or "muteAlways":
                var hours = action.Command switch { "muteHour" => 1, "muteEightHours" => 8, "muteTwoDays" => 48, _ => 0 };
                long? until = hours > 0 ? DateTimeOffset.UtcNow.AddHours(hours).ToUnixTimeMilliseconds() : null;
                await model.ChangeConversationAsync(peer, "mute", until);
                break;
            case "clear" or "delete":
                var deleting = action.Command == "delete";
                var title = AppStrings.Get(deleting ? "UiDeleteFriend" : "UiClearMessages");
                var text = AppStrings.Format(deleting ? "UiDeleteFriendConfirm" : "UiClearMessagesConfirm", conversation.Alias);
                var generation = _appLockGeneration;
                if (!ShowWorkspaceDialog(new ConfirmationDialog(text, title, confirmLabel: title), workspace.HostWindow) ||
                    generation != _appLockGeneration || _appLocked || _exiting || _callWindow?.CoversChat == true ||
                    !ReferenceEquals(model, _viewModel) || !model.Conversations.Contains(conversation)) return;
                if (deleting) await model.DeleteFriendAsync(peer);
                else await model.ClearMessagesAsync(peer);
                break;
        }
    }

    private void PresentQtArchiveUndo()
    {
        if (_archiveUndo is { } notice && !_appLocked && !_exiting)
            _qtWorkspace?.PresentArchiveUndo(_archiveUndoAnimation,
                AppStrings.Get(notice.Archived ? "UiChatArchived" : "UiChatUnarchived"), AppStrings.Get("UiUndo"));
    }
}
