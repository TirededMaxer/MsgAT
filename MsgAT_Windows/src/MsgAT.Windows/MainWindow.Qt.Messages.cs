// SPDX-License-Identifier: AGPL-3.0-only
using MsgAT.Windows.Presentation;

namespace MsgAT.Windows;

public partial class MainWindow
{
    private async Task ChangeQtMessagesAsync(QtMessageCollectionAction action)
    {
        if (_appLocked || _exiting || _callWindow?.CoversChat == true || _qtWorkspace is not { IsOpen: true } workspace ||
            _viewModel is not { CanChangeSelection: true } model || model.SelectedConversation?.Uuid != action.Peer ||
            model.SelectionVersion != action.SelectionVersion || action.Ids.Count == 0) return;
        switch (action.Command)
        {
            case "pin" when !action.FromSelection && action.Ids.Count == 1:
                if (model.Messages.FirstOrDefault(item => item.MessageId == action.Ids[0] &&
                        item.Source.ConversationUuid == action.Peer && !item.IsRevoked && !item.IsSending) is { } message)
                    await ChangeMessagePinAsync(message, owner: workspace.HostWindow);
                break;
            case "quoteSelection" when !action.FromSelection && action.Ids.Count == 1:
                if (model.Messages.FirstOrDefault(item => item.MessageId == action.Ids[0] &&
                        item.Source.ConversationUuid == action.Peer && !item.IsRevoked && item.IsText) is { } quoted)
                    await QuoteMessageTextAsync(quoted, workspace.HostWindow);
                break;
            case "copySelected" when action.FromSelection:
                await CopySelectedMessagesAsync(workspace.HostWindow);
                break;
            case "save":
                await SaveMessagesAsync(action.Ids);
                break;
            case "forward":
                ForwardMessages(action.Ids, workspace.HostWindow);
                break;
            case "delete":
                await DeleteLocalMessagesAsync(action.Peer, action.Ids, fromSelection: action.FromSelection, owner: workspace.HostWindow);
                break;
            case "cancelSend" when action.Ids.Count == 1:
                if (model.Messages.FirstOrDefault(item => item.MessageId == action.Ids[0] &&
                        item.Source.ConversationUuid == action.Peer && item.IsSending) is { } pending)
                    await CancelQueuedSendAsync(pending, workspace.HostWindow);
                break;
        }
    }
}
