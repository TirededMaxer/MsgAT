// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Text.Json;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

internal sealed class QtWorkspaceFrame
{
    private readonly ConditionalWeakTable<MessageItem, RenderedBody> _bodies = new();
    private readonly ConditionalWeakTable<MessageItem, RenderedBody> _quotes = new();
    private readonly ConditionalWeakTable<ConversationItem, RenderedBody> _summaries = new();
    private readonly RenderedBody _reply = new();

    private sealed class RenderedBody
    {
        internal string? Source;
        internal string? Hidden;
        internal string Value = "";
        internal string[] Links = [];
    }

    internal void Clear()
    {
        _bodies.Clear();
        _quotes.Clear();
        _summaries.Clear();
        _reply.Source = _reply.Hidden = null;
        _reply.Value = "";
    }

    internal string MessageText(MessageItem row, string body, string hidden)
    {
        var cached = _bodies.GetValue(row, static _ => new RenderedBody());
        if (cached.Source != body || cached.Hidden != hidden)
        {
            cached.Source = body;
            cached.Hidden = hidden;
            try
            {
                cached.Value = MessageTextBlock.CopyableText(body, hidden, []);
                cached.Links = body.Contains("://", StringComparison.Ordinal) ? WebPreviewFetcher.Candidates(body) : [];
            }
            catch (InvalidOperationException) { cached.Value = ""; cached.Links = []; }
        }
        return cached.Value;
    }

    internal string[] MessageLinks(MessageItem row, string hidden)
    {
        if (!row.IsText || row.IsRevoked || !row.Content.Contains("://", StringComparison.Ordinal)) return [];
        MessageText(row, row.Content, hidden);
        return _bodies.TryGetValue(row, out var body) ? body.Links : [];
    }

    private string ReplyPreview(MainViewModel model, MessageItem target, string hidden)
    {
        var source = target.IsLiveLocation || target.IsLocation ? target.CardTitle : model.ReplyPreview;
        return PreviewText(_reply, source, hidden);
    }

    private static string PreviewText(RenderedBody preview, string source, string hidden)
    {
        if (preview.Source != source || preview.Hidden != hidden)
        {
            preview.Source = source;
            preview.Hidden = hidden;
            preview.Value = ConversationSummary.Render(source, hidden);
        }
        return preview.Value;
    }

    // Capture on the owner dispatcher after selection, draft and history notifications settle.
    // The native view receives presentation values only and never opens the vault.
    internal byte[] Capture(MainViewModel? model, bool locked, int chatTextPercentage, bool appLockEnabled)
    {
        if (locked || model is not { IsWorkspaceReady: true })
            return JsonSerializer.SerializeToUtf8Bytes(new { locked = true });
        var hidden = AppStrings.Get("UiHiddenMessageText");
        var selected = model.SelectedConversation;
        var conversations = model.VisibleConversations.Select(row =>
        {
            var preview = PreviewText(_summaries.GetValue(row, static _ => new RenderedBody()), row.Preview, hidden);
            var receipt = row.Receipt;
            var mute = row.MuteUntil;
            var muteDescription = !row.IsMuted ? "" : mute == ConversationIndexStore.PermanentMuteUntil
                ? AppStrings.Get("UiConversationMuted")
                : AppStrings.Format("UiConversationMutedUntil", DateTimeOffset.FromUnixTimeMilliseconds(mute).LocalDateTime.ToString("g", CultureInfo.CurrentCulture));
            return new
            {
                id = row.Uuid, title = row.Alias, preview, time = row.ActivityTime,
                initials = row.Alias.Length == 0 ? "" : StringInfo.GetNextTextElement(row.Alias),
                unread = row.HasUnread ? row.UnreadLabel : "",
                draft = row.IsDraftPreview ? AppStrings.Get("UiDraftPrefix") : "",
                receipt = ConversationSummary.ReceiptMark(receipt), receiptStatus = receipt.ReceiptStatus,
                pinned = row.IsPinned, archived = row.Archived, hasUnread = row.HasUnread, muteDescription,
                accessible = Join(row.Alias, preview, row.ActivityTime, receipt.StatusName,
                    row.HasUnread ? row.UnreadDescription : "", row.IsPinned ? AppStrings.Get("UiChatPinned") : "", muteDescription)
            };
        });
        var messages = model.Messages.Where(row => selected is not null && model.IsChatContentVisible &&
            row.Source.ConversationUuid == selected.Uuid && !row.HiddenByAlbum).Select(row =>
        {
            var hasCard = row.IsContactCard || row.IsLocation || row.IsLiveLocation;
            var preview = row.LinkPreview;
            var body = hasCard ? row.CardTitle : row.DisplayContent;
            if (row.IsText && !row.IsRevoked)
            {
                body = MessageText(row, body, hidden);
                if (body.Length == 0) body = AppStrings.Get("UiNewMessage");
            }
            var quote = row.HasReplyQuote ? Join(row.ReplyQuoteAuthor,
                PreviewText(_quotes.GetValue(row, static _ => new RenderedBody()), row.ReplyQuoteText, hidden)) : "";
            return new
            {
                id = row.MessageId, body = quote.Length == 0 ? body : quote + "\n\n" + body,
                hasQuote = row.HasReplyQuote, links = MessageLinks(row, hidden), outgoing = row.IsMine, highlighted = row.IsJumpHighlighted ||
                    row.AlbumMembers.Any(item => item.IsJumpHighlighted), joinsAbove = row.JoinsOlder,
                joinsBelow = row.JoinsNewer, day = row.TimelineDay,
                unread = row.StartsUnread ? AppStrings.Get("UiUnreadMessages") : "",
                unreadIncoming = !row.IsMine && !row.IsRead && !row.IsRevoked,
                hasAttachment = row.AttachmentInfo is not null && !row.IsRevoked,
                hasCard,
                preview = preview is null ? "" : Join(preview.Site, preview.Title, preview.Summary),
                previewAbove = preview?.AboveText == true,
                selectable = MainViewModel.CanSelectMessage(row) && model.CanChangeSelection,
                canKeep = MainViewModel.CanSelectMessage(row) && !row.HasExpiration && model.CanChangeSelection,
                selected = row.IsSelected || row.AlbumMembers.Any(member => member.IsSelected),
                canDelete = !row.IsSending && !row.IsLiveLocation && model.CanChangeSelection,
                canPin = !row.IsRevoked && !row.IsSending && model.CanChangeSelection,
                canReply = !row.IsRevoked, canCopy = row.IsText && !row.IsRevoked && !string.IsNullOrWhiteSpace(row.Content),
                canEdit = model.CanCompose && row.CanEditContent, canRevoke = row.CanModify,
                canRetry = row.CanRetrySend, retrying = row.IsRetryingSend,
                canCancelSend = row.IsSending,
                metadata = Join(row.EditedText.Trim(), row.ShortTimeText, ConversationSummary.ReceiptMark(row.Receipt)),
                accessible = Join(body, quote, preview?.Title ?? "", row.EditedText.Trim(), row.TimeText, row.StatusName)
            };
        });
        return JsonSerializer.SerializeToUtf8Bytes(new
        {
            locked = false, peer = selected?.Uuid, title = selected?.Alias,
            theme = (int)WindowAppearance.Theme, chatTextPercentage,
            folder = model.FolderKey,
            folders = (model.ShowArchiveEntry ? model.FolderChoices.Append(model.ArchiveEntry) : model.FolderChoices)
                .Select(entry => new
                {
                    id = entry.Key,
                    text = entry.HasUnread ? $"{entry.Title} ({entry.UnreadLabel})" : entry.Title,
                    accessible = entry.AccessibleName
                }),
            folderLabel = AppStrings.Get("UiChatFolders"),
            conversationActions = new {
                pin = AppStrings.Get("UiPinChat"), unpin = AppStrings.Get("UiUnpinChat"),
                pinUp = AppStrings.Get("UiMovePinUp"), pinDown = AppStrings.Get("UiMovePinDown"),
                archive = AppStrings.Get("UiArchiveChat"), unarchive = AppStrings.Get("UiUnarchiveChat"),
                read = AppStrings.Get("UiMarkChatRead"), unread = AppStrings.Get("UiMarkChatUnread"),
                notifications = AppStrings.Get("UiConversationNotifications"), unmute = AppStrings.Get("UiUnmute"),
                muteHour = AppStrings.Get("UiConversationMuteOneHour"), muteEightHours = AppStrings.Get("UiConversationMuteEightHours"),
                muteTwoDays = AppStrings.Get("UiConversationMuteTwoDays"), muteAlways = AppStrings.Get("UiConversationMuteUntilEnabled"),
                clear = AppStrings.Get("UiClearMessages"), delete = AppStrings.Get("UiDeleteFriend")
            },
            editor = Editor(model),
            reply = model.IsChatContentVisible && !model.HasMessageEdit && model.ReplyTarget is { } target
                ? new { title = model.ReplyTitle, preview = ReplyPreview(model, target, hidden) } : null,
            edit = model.IsChatContentVisible && model.HasMessageEdit
                ? new { title = model.EditingTitle, preview = PreviewText(_reply, model.EditingPreview, hidden), canCancel = model.CanCancelEdit }
                : null,
            messageActions = new {
                reply = AppStrings.Get("UiReply"), copy = AppStrings.Get("UiCopyMessageText"),
                cancelReply = AppStrings.Get("UiCancelReply"), cancelEdit = AppStrings.Get("UiCancelEdit"),
                editMessage = AppStrings.Get("UiEditMessage"), editCaption = AppStrings.Get("UiEditCaption"),
                retry = AppStrings.Get("UiSendRetry"), revoke = AppStrings.Get("UiRevoke"),
                cancelSend = AppStrings.Get("UiSendCancelAction"),
                pin = AppStrings.Get("UiPinnedMessages"),
                quoteSelection = AppStrings.Get("UiQuoteSelectedText"),
                jumpQuote = AppStrings.Get("UiQuoteJump"), openLink = AppStrings.Get("UiOpenLink"),
                preview = AppStrings.Get("UiLinkPreviews"),
                select = AppStrings.Get("UiSelect"), deselect = AppStrings.Get("UiDeselectMessage"),
                save = AppStrings.Get("UiSaveMessage"), forward = AppStrings.Get("UiForward"), delete = AppStrings.Get("UiLocalDeleteAction")
            },
            selection = new {
                active = model.IsChatContentVisible && model.IsSelecting, label = model.SelectionText,
                canUse = model.CanUseSelection, canKeep = model.CanKeepSelection,
                actions = AppStrings.Get("UiChatMoreActions"), cancel = AppStrings.Get("UiCancel"),
                copy = AppStrings.Get("UiCopySelectedMessages"), save = AppStrings.Get("UiSaveSelectedMessages"),
                forward = AppStrings.Get("UiForwardSelectedMessages"), delete = AppStrings.Get("UiLocalDeleteAction")
            },
            workspace = new {
                label = AppStrings.Get("UiSettings"), contacts = AppStrings.Get("UiContacts"), addFriend = AppStrings.Get("UiAddFriend"),
                requests = AppStrings.Get("UiFriendRequests"), settings = AppStrings.Get("UiSettings"),
                manageFolders = AppStrings.Get("UiChatFolders"),
                saved = AppStrings.Get("UiSavedMessages"), stickerLibrary = AppStrings.Get("UiStickerLibrary"),
                sendQueue = AppStrings.Get("UiSendingQueue"),
                forwardQueue = AppStrings.Get("UiForwardingQueue"), callHistory = AppStrings.Get("UiCallHistory"),
                appLock = AppStrings.Get(appLockEnabled ? "UiDisableAppLock" : "UiEnableAppLock"),
                lockNow = AppStrings.Get("UiLockNow"), lockEnabled = appLockEnabled
            },
            calls = new {
                enabled = selected is not null && model.IsChatContentVisible && !model.IsSelecting,
                label = AppStrings.Get("UiChannelCalls"), voice = AppStrings.Get("UiVoiceCall"), video = AppStrings.Get("UiVideoCall")
            },
            chatActions = new {
                enabled = selected is not null && model.IsChatContentVisible && !model.IsSelecting,
                label = AppStrings.Get("UiChatMoreActions"), sharedMedia = AppStrings.Get("UiSharedMedia"),
                pinnedMessages = AppStrings.Get("UiPinnedMessages"), sendQueue = AppStrings.Get("UiSendingQueue"),
                history = AppStrings.Get("UiHistoryNavigation")
            },
            attachment = new {
                menu = AppStrings.Get("UiAttachments"),
                attach = AppStrings.Get("UiAddFiles"), title = AppStrings.Get("UiSendAttachment"),
                record = AppStrings.Get("UiVoiceMessage"),
                stickers = AppStrings.Get("UiStickers"),
                shareContact = AppStrings.Get("UiShareContact"), shareLocation = AppStrings.Get("UiLocationShare"),
                recipient = selected is null ? "" : AppStrings.Format("UiRecipient", selected.Alias),
                add = AppStrings.Get("UiAddFiles"), up = AppStrings.Get("UiMoveUp"),
                down = AppStrings.Get("UiMoveDown"), remove = AppStrings.Get("UiRemoveAttachment"),
                editPhoto = AppStrings.Get("UiEditPhoto"), editVideo = AppStrings.Get("UiEditVideo"),
                caption = AppStrings.Get("UiCaption"), asFile = AppStrings.Get("UiSendAsFile"),
                album = AppStrings.Get("UiSendAsAlbum"), photos = AppStrings.Get("UiCompressPhotos"),
                videos = AppStrings.Get("UiCompressVideos"), silent = AppStrings.Get("UiSilent"),
                schedule = AppStrings.Get("UiScheduleNextMessage"), submit = AppStrings.Get("UiSend"),
                cancel = AppStrings.Get("UiCancel"), cancelPreparation = AppStrings.Get("UiPreparationCancel"),
                failed = AppStrings.Get("UiAttachmentSendFailed"), captionTooLong = AppStrings.Get("UiCaptionTooLong"),
                duplicate = AppStrings.Get("UiAttachmentAlreadySelected"), limit = AppStrings.Get("UiAttachmentLimit"),
                quiet = model.SendOptions.Silent, scheduled = model.SendOptions.SendAt,
                editorLabels = EditorLabels("UiCaption")
            },
            search = new {
                title = AppStrings.Get("UiSearchMessages"),
                all = AppStrings.Get("UiSearchAllConversations"),
                type = AppStrings.Get("UiSearchMessageType"),
                kinds = new[] { AppStrings.Get("UiSearchAllTypes"), AppStrings.Get("UiSearchTextType"), AppStrings.Get("UiAttachments") },
                date = AppStrings.Get("UiDateLabel"), more = AppStrings.Get("UiContactMore"),
                retry = AppStrings.Get("UiRetry"), open = AppStrings.Get("UiOpen"), close = AppStrings.Get("UiClose")
            },
            history = new {
                generation = model.HistoryNavigationVersion,
                initial = model.InitialHistoryLoading,
                target = model.InitialScrollTarget is { } initialTarget
                    ? model.Messages.FirstOrDefault(row => row.MessageId == initialTarget)?.AlbumAnchor?.MessageId ?? initialTarget
                    : null,
                offset = model.InitialHistoryPosition?.Offset,
                older = model.HasOlderMessages,
                newer = model.HasNewerMessages,
                busy = model.HistoryPageLoading,
                failed = model.HistoryLoadFailed,
                olderLabel = AppStrings.Get("UiOlderHistory"),
                newerLabel = AppStrings.Get("UiNewerHistory"),
                latestLabel = AppStrings.Get("UiGoToLatest"),
                errorLabel = AppStrings.Get("UiHistoryLoadFailed"),
                retryLabel = AppStrings.Get("UiRetry")
            },
            backLabel = AppStrings.Get("UiBackToConversations"), listLabel = AppStrings.Get("UiConversationList"),
            selectChatLabel = AppStrings.Get("UiSelectChat"),
            conversations, messages
        });
    }

    internal static byte[] CaptureEditor(MainViewModel model, (int Start, int End)? formatting = null) =>
        JsonSerializer.SerializeToUtf8Bytes(Editor(model, formatting));

    private static object Editor(MainViewModel model, (int Start, int End)? formatting = null)
    {
        var visible = model.IsChatContentVisible;
        var edit = visible ? model.EditTargetId : null;
        var preview = model.WebPreview;
        var label = AppStrings.Get("UiLinkPreviews");
        return new
        {
            peer = model.SelectedConversation?.Uuid,
            draft = !visible ? "" : edit is null ? model.Draft : model.EditText,
            editTarget = edit,
            canCompose = visible && (edit is null ? model.CanCompose : model.CanEditText),
            canSend = visible && (edit is null ? model.CanCompose && model.HasDraft && !model.TextSendPending : model.CanSubmitEdit),
            allowsEmpty = edit is not null && model.EditingAttachment,
            sendLabel = AppStrings.Get(edit is null ? "UiSend" : "UiSave"),
            formatting = formatting is { } selection ? new { start = selection.Start, end = selection.End } : null,
            linkPreview = visible && edit is null && model.CanCompose && model.HasPreviewLinks ? new
            {
                label, enabled = model.PreviewEnabled, target = model.PreviewTarget, choices = model.PreviewChoices,
                title = !model.HasWebPreview ? label : preview is not null
                    ? (string.IsNullOrWhiteSpace(preview.Title) ? preview.Url : preview.Title)
                    : AppStrings.Get(model.PreviewLoading ? "UiLoading" : "UiLinkPreviewUnavailable"),
                retry = model.CanRetryWebPreview, canResize = preview?.Image is not null,
                retryLabel = AppStrings.Get("UiRetry"), remove = AppStrings.Get("UiRemoveLinkPreview"),
                placement = AppStrings.Get(model.PreviewAboveText ? "UiMoveLinkPreviewBelow" : "UiMoveLinkPreviewAbove"),
                resize = AppStrings.Get(model.PreviewCompactImage ? "UiLinkPreviewLargeImage" : "UiLinkPreviewSmallImage")
            } : null,
            delivery = new {
                options = AppStrings.Get("UiSendOptions"), silent = AppStrings.Get("UiSendWithoutSound"),
                schedule = AppStrings.Get("UiScheduleNextMessage"), clear = AppStrings.Get("UiClearSendOptions"),
                summary = visible ? model.SendOptionsSummary : ""
            },
            editorLabels = EditorLabels(edit is not null && model.EditingAttachment ? "UiCaption" : "UiMessagePlaceholder")
        };
    }

    private static object EditorLabels(string placeholder) => new
    {
        placeholder = AppStrings.Get(placeholder), undo = AppStrings.Get("UiUndo"),
        redo = AppStrings.Get("UiRedo"), cut = AppStrings.Get("UiCut"), copy = AppStrings.Get("UiCopy"),
        paste = AppStrings.Get("UiPaste"), all = AppStrings.Get("UiSelectAll"), format = AppStrings.Get("UiFormatting"),
        bold = AppStrings.Get("UiFormatBold"), italic = AppStrings.Get("UiFormatItalic"),
        underline = AppStrings.Get("UiFormatUnderline"), strike = AppStrings.Get("UiFormatStrike"),
        code = AppStrings.Get("UiFormatCode"), spoiler = AppStrings.Get("UiFormatSpoiler"),
        quote = AppStrings.Get("UiFormatQuote"), code_block = AppStrings.Get("UiFormatCodeBlock")
    };

    private static string Join(params string[] values) => string.Join(" · ", values.Where(value => value.Length > 0));
}
