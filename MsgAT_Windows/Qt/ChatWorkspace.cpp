// SPDX-License-Identifier: AGPL-3.0-only
#include "ChatWorkspace.h"
#include "ChatRows.h"
#include "CallWindow.h"
#include "SettingsWindow.h"
#include "ChatComposer.h"
#include "ChatPalette.h"
#include "FluentChrome.h"
#include "ConversationList.h"
#include "MessageSearch.h"
#include "AttachmentReview.h"
#include "AttachmentPreview.h"
#include "MessageTimeline.h"
#include <QAction>
#include <QApplication>
#include <QBoxLayout>
#include <QComboBox>
#include <QCursor>
#include <QDragEnterEvent>
#include <QDropEvent>
#include <QFrame>
#include <QSet>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QMenu>
#include <QMimeData>
#include <QScrollBar>
#include <QShortcut>
#include <QSignalBlocker>
#include <QSplitter>
#include <QStackedLayout>
#include <QStyleHints>
#include <QTimer>
#include <QToolButton>
#include <QStyle>
#include <QUrl>

namespace MsgAT {

namespace {

void labelButton(QToolButton *button, const QString &label) {
    button->setAccessibleName(label);
    button->setToolTip(label);
}

QStringList localFiles(const QMimeData &mime) {
    QStringList paths;
    for (const auto &url : mime.urls())
        if (url.isLocalFile()) paths.append(url.toLocalFile());
    return paths;
}

} // namespace

ChatWorkspace::ChatWorkspace(QWidget *parent) : QWidget(parent) {
    setAcceptDrops(true);
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    split_ = new QSplitter(this);
    split_->setChildrenCollapsible(false);
    split_->setHandleWidth(1);
    layout->addWidget(split_);
    notice_ = new QLabel(this);
    notice_->setTextFormat(Qt::PlainText);
    notice_->setWordWrap(true);
    notice_->setContentsMargins(14, 8, 14, 8);
    installChatPalette(notice_, ChatSurface::Timeline);
    notice_->setAutoFillBackground(true);
    notice_->hide();
    layout->addWidget(notice_);
    noticeTimer_ = new QTimer(this);
    noticeTimer_->setSingleShot(true);
    noticeTimer_->setInterval(4000);
    connect(noticeTimer_, &QTimer::timeout, this, [this] { presentNotice({}); });
    sidebarPane_ = new QWidget(split_);
    auto *sidebarPane = sidebarPane_;
    auto *sidebarRoot = new QHBoxLayout(sidebarPane);
    sidebarRoot->setContentsMargins(0, 0, 0, 0);
    sidebarRoot->setSpacing(0);
    auto *rail = new QFrame(sidebarPane);
    rail->setObjectName(QStringLiteral("workspaceRail"));
    installChatPalette(rail, ChatSurface::Rail);
    rail->setAutoFillBackground(true);
    rail->setFixedWidth(56);
    auto *railLayout = new QVBoxLayout(rail);
    railLayout->setContentsMargins(10, 13, 10, 12);
    railLayout->setSpacing(7);
    sidebarRoot->addWidget(rail);
    auto *sidebar = new QWidget(sidebarPane);
    installChatPalette(sidebar, ChatSurface::ConversationList);
    sidebar->setAutoFillBackground(true);
    sidebarRoot->addWidget(sidebar, 1);
    auto *sidebarLayout = new QVBoxLayout(sidebar);
    sidebarLayout->setContentsMargins(0, 0, 0, 0);
    sidebarLayout->setSpacing(0);
    sidebarTitle_ = new QLabel(sidebar);
    auto *brand = sidebarTitle_;
    auto brandFont = brand->font();
    brandFont.setPointSizeF(18);
    brandFont.setWeight(QFont::DemiBold);
    brand->setFont(brandFont);
    brand->setForegroundRole(QPalette::Text);
    brand->setContentsMargins(15, 13, 15, 8);
    sidebarLayout->addWidget(brand);
    auto *sidebarToolbar = new QVBoxLayout;
    sidebarToolbar->setContentsMargins(13, 3, 13, 11);
    sidebarToolbar->setSpacing(8);
    workspaceMenuButton_ = new QToolButton(rail);
    workspaceMenuButton_->setPopupMode(QToolButton::InstantPopup);
    setFluentButton(workspaceMenuButton_, Symbol::Menu);
    auto *workspaceMenu = new QMenu(workspaceMenuButton_);
    contactsAction_ = workspaceMenu->addAction(QString{});
    addFriendAction_ = workspaceMenu->addAction(QString{});
    requestsAction_ = workspaceMenu->addAction(QString{});
    manageFoldersAction_ = workspaceMenu->addAction(QString{});
    workspaceMenu->addSeparator();
    savedAction_ = workspaceMenu->addAction(QString{});
    stickerLibraryAction_ = workspaceMenu->addAction(QString{});
    sendQueueAction_ = workspaceMenu->addAction(QString{});
    forwardQueueAction_ = workspaceMenu->addAction(QString{});
    callHistoryAction_ = workspaceMenu->addAction(QString{});
    workspaceMenu->addSeparator();
    settingsAction_ = workspaceMenu->addAction(QString{});
    appLockAction_ = workspaceMenu->addAction(QString{});
    lockNowAction_ = workspaceMenu->addAction(QString{});
    connect(contactsAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("contacts"); });
    connect(addFriendAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("addFriend"); });
    connect(requestsAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("requests"); });
    connect(manageFoldersAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("manageFolders"); });
    connect(savedAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("saved"); });
    connect(stickerLibraryAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("stickerLibrary"); });
    connect(sendQueueAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("sendQueue"); });
    connect(forwardQueueAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("forwardQueue"); });
    connect(callHistoryAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("callHistory"); });
    connect(settingsAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("settings"); });
    connect(appLockAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("appLock"); });
    connect(lockNowAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("lockNow"); });
    workspaceMenuButton_->setMenu(workspaceMenu);
    railLayout->addWidget(workspaceMenuButton_);
    auto railButton = [rail, railLayout](Symbol symbol) {
        auto *button = new QToolButton(rail);
        setFluentButton(button, symbol);
        railLayout->addWidget(button);
        return button;
    };
    contactsRail_ = railButton(Symbol::Contacts);
    savedRail_ = railButton(Symbol::Bookmark);
    railLayout->addStretch();
    settingsRail_ = railButton(Symbol::Settings);
    connect(contactsRail_, &QToolButton::clicked, contactsAction_, &QAction::trigger);
    connect(savedRail_, &QToolButton::clicked, savedAction_, &QAction::trigger);
    connect(settingsRail_, &QToolButton::clicked, settingsAction_, &QAction::trigger);
    contactsAction_->setIcon(fluentIcon(Symbol::Contacts));
    addFriendAction_->setIcon(fluentIcon(Symbol::AddContact));
    savedAction_->setIcon(fluentIcon(Symbol::Bookmark));
    stickerLibraryAction_->setIcon(fluentIcon(Symbol::Sticker));
    settingsAction_->setIcon(fluentIcon(Symbol::Settings));
    folders_ = new FluentComboBox(sidebar);
    folders_->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon);
    folders_->setMinimumContentsLength(7);
    sidebarToolbar->addWidget(folders_, 1);
    connect(folders_, &QComboBox::activated, this, [this](int index) {
        const auto key = folders_->itemData(index).toString();
        if (!locked_ && !key.isEmpty()) emit folderRequested(key);
    });
    searchButton_ = new QToolButton(sidebar);
    searchButton_->setObjectName(QStringLiteral("searchPill"));
    searchButton_->setIcon(fluentIcon(Symbol::Search));
    searchButton_->setIconSize(QSize(16, 16));
    searchButton_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    searchButton_->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    searchButton_->setMinimumHeight(34);
    sidebarToolbar->insertWidget(0, searchButton_);
    sidebarLayout->addLayout(sidebarToolbar);
    conversations_ = new ConversationList(sidebar);
    sidebarLayout->addWidget(conversations_, 1);
    conversationMenu_ = new QMenu(conversations_);
    conversations_->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(conversations_, &QWidget::customContextMenuRequested, this, [this](const QPoint &point) {
        openConversationMenu(conversations_->indexAt(point), conversations_->viewport()->mapToGlobal(point));
    });
    const auto openCurrentConversationMenu = [this] {
        const auto index = conversations_->currentIndex();
        if (!index.isValid()) return;
        conversations_->scrollTo(index);
        openConversationMenu(index, conversations_->viewport()->mapToGlobal(conversations_->visualRect(index).bottomLeft()));
    };
    for (const auto &key : {QKeySequence(Qt::Key_Menu), QKeySequence(QStringLiteral("Shift+F10"))}) {
        auto *shortcut = new QShortcut(key, conversations_);
        shortcut->setContext(Qt::WidgetShortcut);
        connect(shortcut, &QShortcut::activated, this, openCurrentConversationMenu);
    }
    connect(conversationMenu_, &QMenu::aboutToHide, this, [this] {
        QTimer::singleShot(0, this, &ChatWorkspace::reportHistoryViewport);
    });
    archiveBanner_ = new QWidget(sidebar);
    auto *archiveLayout = new QHBoxLayout(archiveBanner_);
    archiveLayout->setContentsMargins(10, 3, 10, 5);
    archiveText_ = new QLabel(archiveBanner_);
    archiveText_->setTextFormat(Qt::PlainText);
    archiveText_->setWordWrap(true);
    undoArchive_ = new QToolButton(archiveBanner_);
    undoArchive_->setAutoRaise(true);
    archiveLayout->addWidget(archiveText_, 1);
    archiveLayout->addWidget(undoArchive_);
    sidebarLayout->addWidget(archiveBanner_);
    connect(undoArchive_, &QToolButton::clicked, this, [this] {
        if (applyConversationAction(QJsonObject{{"command", "undoArchive"}, {"version", archiveUndoVersion_}}))
            undoArchive_->setEnabled(false);
    });
    search_ = new MessageSearch(this);
    const auto openSearch = [this] {
        if (locked_ || searchLabels_.isEmpty()) return;
        reportHistoryViewport();
        search_->showFor(selected_, searchLabels_);
        reportHistoryViewport();
    };
    connect(searchButton_, &QToolButton::clicked, this, openSearch);
    connect(new QShortcut(QKeySequence(QKeySequence::Find), this), &QShortcut::activated, this, openSearch);
    search_->request = [this](int action, const QByteArray &state) {
        return !locked_ && dialogRequest && dialogRequest(action, state);
    };
    peers_ = new ChatRows(ChatRows::Kind::Conversations, this);
    conversations_->setModel(peers_);
    detailContainer_ = new QWidget(split_);
    detailPages_ = new QStackedLayout(detailContainer_);
    detailPages_->setContentsMargins(0, 0, 0, 0);
    emptyChat_ = new QLabel(detailContainer_);
    emptyChat_->setTextFormat(Qt::PlainText);
    emptyChat_->setAlignment(Qt::AlignCenter);
    emptyChat_->setWordWrap(true);
    emptyChat_->setContentsMargins(20, 20, 20, 20);
    emptyChat_->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Ignored);
    installChatPalette(emptyChat_, ChatSurface::Timeline);
    emptyChat_->setAutoFillBackground(true);
    detailPages_->addWidget(emptyChat_);
    detail_ = new QWidget(detailContainer_);
    detailPages_->addWidget(detail_);
    auto *column = new QVBoxLayout(detail_);
    column->setContentsMargins(0, 0, 0, 0);
    column->setSpacing(0);
    installChatPalette(detail_, ChatSurface::Timeline);
    detail_->setAutoFillBackground(true);
    auto *headerSurface = new QFrame(detail_);
    headerSurface->setObjectName(QStringLiteral("chatHeader"));
    installChatPalette(headerSurface, ChatSurface::Chrome);
    headerSurface->setAutoFillBackground(true);
    auto *header = new QHBoxLayout(headerSurface);
    header->setContentsMargins(13, 9, 15, 9);
    header->setSpacing(5);
    column->addWidget(headerSurface);
    back_ = new QToolButton(detail_);
    back_->setAutoRaise(true);
    setFluentButton(back_, Symbol::Back);
    header->addWidget(back_);
    chatAvatar_ = new QLabel(headerSurface);
    chatAvatar_->setFixedSize(38, 38);
    chatAvatar_->setAlignment(Qt::AlignCenter);
    chatAvatar_->setObjectName(QStringLiteral("chatAvatar"));
    header->addWidget(chatAvatar_);
    header->addSpacing(5);
    title_ = new QLabel(headerSurface);
    title_->setTextFormat(Qt::PlainText);
    title_->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    QFont heading = font();
    heading.setWeight(QFont::DemiBold);
    heading.setPointSizeF(12.0);
    title_->setFont(heading);
    title_->setForegroundRole(QPalette::Text);
    header->addWidget(title_, 1);
    chatSearchButton_ = new QToolButton(detail_);
    chatSearchButton_->setAutoRaise(true);
    setFluentButton(chatSearchButton_, Symbol::Search);
    header->addWidget(chatSearchButton_);
    connect(chatSearchButton_, &QToolButton::clicked, this, openSearch);
    callButton_ = new QToolButton(detail_);
    callButton_->setAutoRaise(true);
    setFluentButton(callButton_, Symbol::Call);
    callButton_->setPopupMode(QToolButton::InstantPopup);
    auto *calls = new QMenu(callButton_);
    voiceCall_ = calls->addAction(fluentIcon(Symbol::Call), QString{});
    videoCall_ = calls->addAction(fluentIcon(Symbol::Video), QString{});
    connect(voiceCall_, &QAction::triggered, this, [this] { startCall(false); });
    connect(videoCall_, &QAction::triggered, this, [this] { startCall(true); });
    callButton_->setMenu(calls);
    header->addWidget(callButton_);
    chatMenuButton_ = new QToolButton(detail_);
    chatMenuButton_->setAutoRaise(true);
    setFluentButton(chatMenuButton_, Symbol::More);
    chatMenuButton_->setPopupMode(QToolButton::InstantPopup);
    auto *chatMenu = new QMenu(chatMenuButton_);
    sharedMediaAction_ = chatMenu->addAction(fluentIcon(Symbol::Image), QString{});
    pinnedMessagesAction_ = chatMenu->addAction(fluentIcon(Symbol::Pin), QString{});
    chatQueueAction_ = chatMenu->addAction(fluentIcon(Symbol::Send), QString{});
    historyNavigationAction_ = chatMenu->addAction(fluentIcon(Symbol::History), QString{});
    connect(sharedMediaAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("sharedMedia"); });
    connect(pinnedMessagesAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("pinnedMessages"); });
    connect(chatQueueAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("chatQueue"); });
    connect(historyNavigationAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("historyNavigation"); });
    chatMenuButton_->setMenu(chatMenu);
    header->addWidget(chatMenuButton_);
    auto historyButton = [this, header](int action, Symbol symbol) {
        auto *button = new QToolButton(detail_);
        button->setAutoRaise(true);
        setFluentButton(button, symbol);
        header->addWidget(button);
        connect(button, &QToolButton::clicked, this, [this, action] {
            if (!locked_ && !selected_.isEmpty()) emit historyRequested(action, selected_);
        });
        return button;
    };
    older_ = historyButton(6, Symbol::Up);
    newer_ = historyButton(7, Symbol::Down);
    latest_ = historyButton(8, Symbol::Latest);
    auto *historyNotice = new QHBoxLayout;
    historyError_ = new QLabel(detail_);
    historyError_->setTextFormat(Qt::PlainText);
    historyError_->setWordWrap(true);
    retry_ = new QToolButton(detail_);
    retry_->setAutoRaise(true);
    retry_->setIcon(fluentIcon(Symbol::Reset));
    retry_->setIconSize(QSize(16, 16));
    retry_->setMinimumHeight(36);
    retry_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    connect(retry_, &QToolButton::clicked, this, [this] {
        if (!locked_ && !selected_.isEmpty()) emit historyRequested(9, selected_);
    });
    historyNotice->addSpacing(16);
    historyNotice->addWidget(historyError_, 1);
    historyNotice->addWidget(retry_);
    historyNotice->addSpacing(16);
    column->addLayout(historyNotice);
    messages_ = new MessageTimeline(detail_);
    history_ = new ChatRows(ChatRows::Kind::Messages, this);
    messages_->setModel(history_);
    messageMenu_ = new QMenu(messages_);
    messages_->setContextMenuPolicy(Qt::CustomContextMenu);
    const auto openMessageMenu = [this](const QModelIndex &index, const QPoint &globalPosition) {
        if (locked_ || !index.isValid()) return;
        const auto id = history_->idAt(index);
        messageMenu_->clear();
        if (!selecting_ && index.data(MessageTimeline::CanReply).toBool() && !composer_->isReadOnly()) {
            auto *reply = messageMenu_->addAction(messageLabels_.value("reply").toString());
            connect(reply, &QAction::triggered, this, [this, id] {
                if (applyMessageAction(QStringLiteral("reply"), id)) composer_->setFocus();
            });
        }
        if (!selecting_ && index.data(MessageTimeline::CanCopy).toBool()) {
            auto *copy = messageMenu_->addAction(messageLabels_.value("copy").toString());
            connect(copy, &QAction::triggered, this, [this, id] { applyMessageAction(QStringLiteral("copy"), id); });
        }
        auto addAction = [&](const char *key, bool visible, bool enabled = true, const char *label = nullptr) {
            if (!visible) return;
            const auto command = QString::fromLatin1(key);
            auto *action = messageMenu_->addAction(messageLabels_.value(label ? QString::fromLatin1(label) : command).toString());
            action->setEnabled(enabled);
            connect(action, &QAction::triggered, this, [this, command, id] { applyMessageAction(command, id); });
        };
        const bool idle = editTarget_.isEmpty() && !selecting_;
        addAction("inspectText", idle && index.data(MessageTimeline::InspectFormattedText).toBool());
        addAction("quoteSelection", idle && index.data(MessageTimeline::CanCopy).toBool() && !composer_->isReadOnly());
        addAction("jumpQuote", idle && index.data(MessageTimeline::HasReplyQuote).toBool());
        if (idle) {
            const auto runs = index.data(MessageTimeline::RichRuns).value<QJsonArray>();
            const auto peer = selected_;
            int count = 0;
            for (const auto &value : runs) {
                const auto run = value.toObject();
                const auto token = run.value("action").toString();
                const auto label = run.value("label").toString();
                if (token.isEmpty() || label.isEmpty() || label == messageLabels_.value("openLink").toString()) continue;
                auto *action = messageMenu_->addAction(label);
                connect(action, &QAction::triggered, this, [this, peer, id, token] {
                    if (selected_ == peer) applyMessageAction(QStringLiteral("richText"), id, false, token);
                });
                if (++count == 16) break;
            }
        }
        const auto links = index.data(MessageTimeline::VisibleLinks).toStringList();
        if (idle && !links.isEmpty()) {
            auto *linkMenu = links.size() > 1 ? messageMenu_->addMenu(messageLabels_.value("openLink").toString()) : messageMenu_;
            const auto peer = selected_;
            for (const auto &link : links) {
                auto label = links.size() == 1 ? messageLabels_.value("openLink").toString() : QString(link).replace("&", "&&");
                auto *action = linkMenu->addAction(label);
                connect(action, &QAction::triggered, this, [this, id, peer, link] {
                    if (selected_ == peer) applyMessageAction(QStringLiteral("openLink"), id, false, link);
                });
            }
        }
        const bool caption = index.data(MessageTimeline::HasAttachment).toBool();
        addAction(caption ? "editCaption" : "editMessage", idle && index.data(MessageTimeline::CanEdit).toBool());
        addAction("retry", !selecting_ && index.data(MessageTimeline::CanRetry).toBool(), !index.data(MessageTimeline::Retrying).toBool());
        addAction("cancelSend", idle && index.data(MessageTimeline::CanCancelSend).toBool());
        addAction("revoke", !selecting_ && index.data(MessageTimeline::CanRevoke).toBool());
        addAction("pin", idle && index.data(MessageTimeline::CanPin).toBool());
        addAction("openCard", idle && !index.data(MessageTimeline::LinkPreviewText).toString().isEmpty(), true, "preview");
        const auto selected = index.data(MessageTimeline::MessageSelected).toBool();
        addAction("toggleSelection", index.data(MessageTimeline::Selectable).toBool(), true, selected ? "deselect" : "select");
        addAction("save", idle && index.data(MessageTimeline::CanSaveOrForward).toBool());
        addAction("forward", idle && index.data(MessageTimeline::CanSaveOrForward).toBool());
        addAction("delete", idle && index.data(MessageTimeline::CanDeleteLocally).toBool());
        if (!messageMenu_->isEmpty()) {
            messageMenu_->popup(globalPosition);
            reportHistoryViewport();
        }
    };
    connect(messages_, &QWidget::customContextMenuRequested, this, [this, openMessageMenu](const QPoint &point) {
        openMessageMenu(messages_->indexAt(point), messages_->viewport()->mapToGlobal(point));
    });
    const auto openCurrentMessageMenu = [this, openMessageMenu] {
        const auto index = messages_->currentIndex();
        if (!index.isValid()) return;
        messages_->scrollTo(index);
        openMessageMenu(index, messages_->viewport()->mapToGlobal(messages_->visualRect(index).bottomLeft()));
    };
    for (const auto &key : {QKeySequence(Qt::Key_Menu), QKeySequence(QStringLiteral("Shift+F10"))}) {
        auto *shortcut = new QShortcut(key, messages_);
        shortcut->setContext(Qt::WidgetShortcut);
        connect(shortcut, &QShortcut::activated, this, openCurrentMessageMenu);
    }
    connect(messageMenu_, &QMenu::aboutToHide, this, [this] {
        QTimer::singleShot(0, this, &ChatWorkspace::reportHistoryViewport);
    });
    preview_ = new AttachmentPreview(this);
    preview_->request = [this](int action, const QByteArray &state) {
        return !locked_ && dialogRequest && dialogRequest(action, state);
    };
    const auto openPreview = [this](const QModelIndex &index, bool allowLinkPreview, const QString &tile = {}) {
        if (locked_ || selected_.isEmpty() || !index.isValid()) return;
        const auto id = history_->idAt(index);
        if (index.data(MessageTimeline::HasAttachment).toBool()) preview_->showFor(selected_, tile.isEmpty() ? id : tile);
        else if (index.data(MessageTimeline::HasCard).toBool() ||
                 (allowLinkPreview && !index.data(MessageTimeline::LinkPreviewText).toString().isEmpty()))
            applyMessageAction(QStringLiteral("openCard"), id);
        reportHistoryViewport();
    };
    connect(messages_, &QListView::clicked, this, [this, openPreview, openMessageMenu](const QModelIndex &index) {
        if (selecting_) {
            const bool extend = QApplication::keyboardModifiers().testFlag(Qt::ShiftModifier);
            applyMessageAction(QStringLiteral("toggleSelection"), history_->idAt(index), extend);
            return;
        }
        const auto point = messages_->viewport()->mapFromGlobal(QCursor::pos());
        if (messages_->retryReceiptContains(index, point)) {
            openMessageMenu(index, messages_->viewport()->mapToGlobal(point));
            return;
        }
        const auto action = messages_->richAction(index, point);
        if (!action.isEmpty()) {
            applyMessageAction(QStringLiteral("richText"), history_->idAt(index), false, action);
            return;
        }
        const auto tile = messages_->mediaTarget(index, point);
        if (!tile.isEmpty() || index.data(MessageTimeline::InlineMedia).value<QJsonArray>().isEmpty())
            openPreview(index, messages_->previewContains(index, point), tile);
    });
    connect(messages_, &QListView::activated, this, [this, openPreview](const QModelIndex &index) {
        if (!selecting_) openPreview(index, true);
    });
    toggleSelectionKey_ = new QShortcut(QKeySequence(Qt::Key_Space), messages_);
    toggleSelectionKey_->setContext(Qt::WidgetShortcut);
    connect(toggleSelectionKey_, &QShortcut::activated, this, [this] {
        if (selecting_) applyMessageAction(QStringLiteral("toggleSelection"), history_->idAt(messages_->currentIndex()));
    });
    historyViewportTimer_ = new QTimer(this);
    historyViewportTimer_->setSingleShot(true);
    historyViewportTimer_->setInterval(32);
    connect(historyViewportTimer_, &QTimer::timeout, this, &ChatWorkspace::reportHistoryViewport);
    connect(messages_->verticalScrollBar(), &QScrollBar::valueChanged, this, [this] {
        if (presenting_) return;
        updateLatestButton();
        if (!historyViewportTimer_->isActive()) historyViewportTimer_->start();
    });
    column->addWidget(messages_, 1);
    selectionBar_ = new QWidget(detail_);
    auto *selectionLayout = new QHBoxLayout(selectionBar_);
    selectionLayout->setContentsMargins(14, 5, 12, 5);
    selectionText_ = new QLabel(selectionBar_);
    selectionText_->setTextFormat(Qt::PlainText);
    selectionLayout->addWidget(selectionText_, 1);
    selectionMenuButton_ = new QToolButton(selectionBar_);
    selectionMenuButton_->setIcon(fluentIcon(Symbol::More));
    selectionMenuButton_->setIconSize(QSize(16, 16));
    selectionMenuButton_->setMinimumHeight(36);
    selectionMenuButton_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    selectionMenuButton_->setPopupMode(QToolButton::InstantPopup);
    auto *selectionMenu = new QMenu(selectionMenuButton_);
    copySelection_ = selectionMenu->addAction(QString{});
    saveSelection_ = selectionMenu->addAction(QString{});
    forwardSelection_ = selectionMenu->addAction(QString{});
    deleteSelection_ = selectionMenu->addAction(QString{});
    selectionMenuButton_->setMenu(selectionMenu);
    selectionLayout->addWidget(selectionMenuButton_);
    cancelSelection_ = new QToolButton(selectionBar_);
    cancelSelection_->setAutoRaise(true);
    setFluentButton(cancelSelection_, Symbol::Close);
    selectionLayout->addWidget(cancelSelection_);
    column->addWidget(selectionBar_);
    connect(copySelection_, &QAction::triggered, this, [this] { applyMessageAction(QStringLiteral("copySelected")); });
    connect(saveSelection_, &QAction::triggered, this, [this] { applyMessageAction(QStringLiteral("save")); });
    connect(forwardSelection_, &QAction::triggered, this, [this] { applyMessageAction(QStringLiteral("forward")); });
    connect(deleteSelection_, &QAction::triggered, this, [this] { applyMessageAction(QStringLiteral("delete")); });
    connect(cancelSelection_, &QToolButton::clicked, this, [this] { applyMessageAction(QStringLiteral("clearSelection")); });
    replyBar_ = new QWidget(detail_);
    auto *replyLayout = new QHBoxLayout(replyBar_);
    replyLayout->setContentsMargins(14, 5, 12, 2);
    auto *quote = new QVBoxLayout;
    quote->setSpacing(2);
    replyTitle_ = new QLabel(replyBar_);
    replyText_ = new QLabel(replyBar_);
    for (auto *label : {replyTitle_, replyText_}) {
        label->setTextFormat(Qt::PlainText);
        label->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
        quote->addWidget(label);
    }
    replyTitle_->setFont(heading);
    replyLayout->addLayout(quote, 1);
    cancelReply_ = new QToolButton(replyBar_);
    cancelReply_->setAutoRaise(true);
    setFluentButton(cancelReply_, Symbol::Close);
    replyLayout->addWidget(cancelReply_);
    connect(cancelReply_, &QToolButton::clicked, this, [this] {
        const bool accepted = editTarget_.isEmpty() ? applyMessageAction(QStringLiteral("cancelReply")) :
            applyMessageAction(QStringLiteral("cancelEdit"), editTarget_);
        if (accepted) composer_->setFocus();
    });
    column->addWidget(replyBar_);
    linkPreviewButton_ = new QToolButton(detail_);
    linkPreviewButton_->setIcon(fluentIcon(Symbol::Options));
    linkPreviewButton_->setIconSize(QSize(16, 16));
    linkPreviewButton_->setMinimumHeight(36);
    linkPreviewButton_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    linkPreviewButton_->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
    linkPreviewButton_->setPopupMode(QToolButton::InstantPopup);
    linkPreviewButton_->hide();
    auto *linkMenu = new QMenu(linkPreviewButton_);
    linkPreviewButton_->setMenu(linkMenu);
    connect(linkMenu, &QMenu::aboutToShow, this, [this, linkMenu] {
        linkMenu->clear();
        const auto peer = selected_, text = composer_->toPlainText();
        auto add = [this, linkMenu, peer, text](const QString &label, const QString &command, const QString &value = {}) {
            auto *action = linkMenu->addAction(label);
            connect(action, &QAction::triggered, this, [this, peer, text, command, value] {
                if (locked_ || peer != selected_ || text != composer_->toPlainText() || !dialogRequest) return;
                auto request = QJsonDocument(QJsonObject{{"peer", peer}, {"text", text}, {"command", command},
                    {"value", value}}).toJson(QJsonDocument::Compact);
                dialogRequest(38, request);
                request.fill(0);
            });
            return action;
        };
        const auto &options = linkPreviewOptions_;
        auto *toggle = add(options.value("label").toString(), QStringLiteral("toggle"));
        toggle->setCheckable(true);
        const bool enabled = options.value("enabled").toBool();
        toggle->setChecked(enabled);
        if (!enabled) return;
        const auto target = options.value("target").toString();
        for (const auto &choice : options.value("choices").toArray()) {
            const auto url = choice.toString();
            if (url != target) add(url, QStringLiteral("choose"), url);
        }
        if (target.isEmpty()) return;
        linkMenu->addSeparator();
        if (options.value("retry").toBool()) add(options.value("retryLabel").toString(), QStringLiteral("retry"));
        add(options.value("placement").toString(), QStringLiteral("placement"));
        if (options.value("canResize").toBool()) add(options.value("resize").toString(), QStringLiteral("resize"));
        add(options.value("remove").toString(), QStringLiteral("remove"));
    });
    column->addWidget(linkPreviewButton_);
    auto *composerCard = new QFrame(detail_);
    composerCard->setObjectName(QStringLiteral("composerCard"));
    auto *composerBar = new QHBoxLayout(composerCard);
    composerBar->setContentsMargins(7, 6, 7, 6);
    composerBar->setSpacing(4);
    attachment_ = new AttachmentReview(this);
    attachment_->request = [this](int action, const QByteArray &state) {
        return !locked_ && dialogRequest && dialogRequest(action, state);
    };
    attachment_->copyCaption = [this](const QString &text) {
        return !locked_ && copyText && copyText(selected_, text);
    };
    attachmentButton_ = new QToolButton(detail_);
    attachmentButton_->setAutoRaise(true);
    setFluentButton(attachmentButton_, Symbol::Attach);
    attachmentButton_->setStyleSheet(QStringLiteral("QToolButton::menu-indicator { image: none; }"));
    attachmentButton_->setPopupMode(QToolButton::InstantPopup);
    auto *addMenu = new QMenu(attachmentButton_);
    chooseFiles_ = addMenu->addAction(QString{});
    stickersAction_ = addMenu->addAction(QString{});
    shareContact_ = addMenu->addAction(QString{});
    shareLocation_ = addMenu->addAction(QString{});
    attachmentButton_->setMenu(addMenu);
    composerBar->addWidget(attachmentButton_, 0, Qt::AlignBottom);
    connect(chooseFiles_, &QAction::triggered, this, [this] {
        if (locked_ || attachmentOptions_.isEmpty()) return;
        reportHistoryViewport();
        attachment_->showFor(selected_, attachmentOptions_);
        reportHistoryViewport();
    });
    connect(shareContact_, &QAction::triggered, this, [this] { openWorkspaceCommand("shareContact"); });
    connect(stickersAction_, &QAction::triggered, this, [this] { openWorkspaceCommand("stickers"); });
    connect(shareLocation_, &QAction::triggered, this, [this] { openWorkspaceCommand("shareLocation"); });
    recordButton_ = new QToolButton(detail_);
    recordButton_->setAutoRaise(true);
    setFluentButton(recordButton_, Symbol::Record);
    composerBar->addWidget(recordButton_, 0, Qt::AlignBottom);
    connect(recordButton_, &QToolButton::clicked, this, [this] { openWorkspaceCommand("record"); });
    stickerButton_ = new QToolButton(composerCard);
    setFluentButton(stickerButton_, Symbol::Sticker);
    connect(stickerButton_, &QToolButton::clicked, stickersAction_, &QAction::trigger);
    composerBar->addWidget(stickerButton_, 0, Qt::AlignBottom);
    composer_ = new ChatComposer(composerCard);
    composer_->setObjectName(QStringLiteral("chatInput"));
    composerBar->addWidget(composer_, 1);
    send_ = new QToolButton(detail_);
    send_->setAutoRaise(true);
    setFluentButton(send_, Symbol::Send, true);
    composerBar->addWidget(send_, 0, Qt::AlignBottom);
    deliveryButton_ = new QToolButton(detail_);
    setFluentButton(deliveryButton_, Symbol::Options);
    deliveryButton_->setStyleSheet(QStringLiteral("QToolButton::menu-indicator { image: none; }"));
    deliveryButton_->setAutoRaise(true);
    deliveryButton_->setPopupMode(QToolButton::InstantPopup);
    auto *delivery = new QMenu(deliveryButton_);
    deliveryOptions_ = delivery->addAction(QString{});
    sendSilently_ = delivery->addAction(QString{});
    scheduleDraft_ = delivery->addAction(QString{});
    clearDelivery_ = delivery->addAction(QString{});
    connect(deliveryOptions_, &QAction::triggered, this, [this] { openWorkspaceCommand("sendOptions"); });
    connect(sendSilently_, &QAction::triggered, this, [this] {
        if (composer_->acceptsSubmit()) applyMessageAction(QStringLiteral("sendSilently"));
    });
    connect(scheduleDraft_, &QAction::triggered, this, [this] {
        if (composer_->acceptsSubmit()) openWorkspaceCommand("scheduleDraft");
    });
    connect(clearDelivery_, &QAction::triggered, this, [this] { applyMessageAction(QStringLiteral("clearDelivery")); });
    deliveryButton_->setMenu(delivery);
    composerBar->addWidget(deliveryButton_, 0, Qt::AlignBottom);
    auto *composerInset = new QVBoxLayout;
    composerInset->setContentsMargins(14, 3, 14, 13);
    composerInset->addWidget(composerCard);
    column->addLayout(composerInset);
    composer_->copySelection = [this](const QString &value) {
        return !locked_ && !selected_.isEmpty() && copyText && copyText(selected_, value);
    };
    composer_->formatSelection = [this](const QString &text, int start, int end, const QString &format) {
        if (locked_ || selected_.isEmpty() || !dialogRequest) return false;
        auto request = QJsonDocument(QJsonObject{{"peer", selected_}, {"target", editTarget_}, {"text", text},
            {"start", start}, {"end", end}, {"format", format}}).toJson(QJsonDocument::Compact);
        const bool accepted = dialogRequest(37, request);
        request.fill(0);
        return accepted;
    };
    composer_->pasteMedia = [this](const QMimeData &mime) {
        if (!canReceiveAttachments()) return false;
        const auto paths = localFiles(mime);
        if (!paths.isEmpty()) {
            reportHistoryViewport();
            attachment_->showFor(selected_, attachmentOptions_, paths);
            return attachment_->isVisible();
        }
        return (mime.hasImage() || mime.hasFormat("image/png")) && openWorkspaceCommand("pasteImage");
    };
    auto send = [this] {
        if (locked_ || !composer_->acceptsSubmit()) return;
        const auto text = composer_->toPlainText();
        if (composer_->allowsEmpty || !text.trimmed().isEmpty()) emit sendRequested(selected_, editTarget_, text);
    };
    connect(send_, &QToolButton::clicked, this, send);
    connect(composer_, &ChatComposer::sendRequested, this, send);
    connect(composer_, &ChatComposer::draftChanged, this, [this](const QString &draft) {
        if (!locked_ && !selected_.isEmpty()) emit draftChanged(selected_, editTarget_, draft);
    });
    split_->addWidget(sidebarPane_);
    split_->addWidget(detailContainer_);
    split_->setStretchFactor(0, 0);
    split_->setStretchFactor(1, 1);
    split_->setSizes({288, 720});
    connect(split_, &QSplitter::splitterMoved, this, [this] {
        if (twoPanes_) sidebarWidth_ = split_->sizes().first();
    });
    auto choose = [this](const QModelIndex &index) {
        const auto id = peers_->idAt(index);
        if (locked_ || id.isEmpty()) return;
        if (id == selected_) {
            showingList_ = false;
            layoutPanes();
        } else {
            reportHistoryViewport();
            emit conversationRequested(id);
        }
    };
    connect(conversations_, &QListView::clicked, this, choose);
    connect(conversations_, &QListView::activated, this, choose);
    connect(back_, &QToolButton::clicked, this, [this] {
        showingList_ = true;
        layoutPanes();
        reportHistoryViewport();
        conversations_->setFocus();
    });
    clear();
}

ChatWorkspace::~ChatWorkspace() = default;

void ChatWorkspace::clear() {
    locked_ = true;
    if (callWindow_) callWindow_->reset();
    if (settings_) settings_->reset();
    presentNotice({});
    workspaceMenuButton_->menu()->hide();
    callButton_->menu()->hide();
    chatMenuButton_->menu()->hide();
    attachmentButton_->menu()->hide();
    deliveryButton_->menu()->hide();
    messageMenu_->hide();
    messageMenu_->clear();
    selectionMenuButton_->menu()->hide();
    selectionBar_->hide();
    selectionText_->clear();
    selecting_ = false;
    messages_->setSelectionMode(QAbstractItemView::SingleSelection);
    toggleSelectionKey_->setEnabled(false);
    conversationMenu_->hide();
    conversationMenu_->clear();
    conversationLabels_ = {};
    presentArchiveUndo({});
    replyBar_->hide();
    replyTitle_->clear();
    replyText_->clear();
    messageLabels_ = {};
    search_->reset();
    searchLabels_ = QJsonObject{};
    attachment_->reset();
    preview_->reset();
    attachmentOptions_ = QJsonObject{};
    historyViewportTimer_->stop();
    folderChoices_ = QJsonArray{};
    folders_->clear();
    split_->hide();
    twoPanes_ = false;
    peers_->clear();
    history_->clear();
    messages_->retainMedia({});
    selected_.clear();
    editTarget_.clear();
    title_->clear();
    chatAvatar_->clear();
    historyGeneration_ = -1;
    initialHistory_ = hasNewer_ = false;
    for (auto *control : {older_, newer_, latest_, retry_}) control->hide();
    historyError_->hide();
    composer_->reset();
    linkPreviewButton_->menu()->close();
    linkPreviewButton_->menu()->clear();
    linkPreviewButton_->setText({});
    linkPreviewButton_->setAccessibleName({});
    linkPreviewButton_->hide();
    linkPreviewOptions_ = {};
    send_->setEnabled(false);
    showingList_ = true;
}

void ChatWorkspace::present(const QByteArray &frame) {
    const auto state = QJsonDocument::fromJson(frame).object();
    if (state.value("locked").toBool(true)) { clear(); return; }
    const auto peer = state.value("peer").toString();
    const bool changed = selected_ != peer;
    const auto history = state.value("history").toObject();
    const auto generation = history.value("generation").toInteger();
    const bool loading = history.value("initial").toBool();
    const bool resetPosition = changed || historyGeneration_ != generation || (initialHistory_ && !loading);
    historyGeneration_ = generation;
    initialHistory_ = loading;
    const auto *scroll = messages_->verticalScrollBar();
    const bool wasFollowing = !hasNewer_ && scroll->value() == scroll->maximum();
    const auto oldFirst = history_->idAt(history_->index(0, 0));
    const QPersistentModelIndex anchor(messages_->indexAt(QPoint(1, 1)));
    const int top = anchor.isValid() ? messages_->visualRect(anchor).top() : 0;
    presenting_ = true;
    const auto theme = state.value("theme").toInt();
    if (theme_ != theme) {
        theme_ = theme;
        QGuiApplication::styleHints()->setColorScheme(theme == 1 ? Qt::ColorScheme::Light :
            theme == 2 ? Qt::ColorScheme::Dark : Qt::ColorScheme::Unknown);
    }
    const auto textPercentage = qBound(85, state.value("chatTextPercentage").toInt(100), 130);
    const bool changedTextSize = textPercentage != chatTextPercentage_;
    if (changedTextSize) {
        chatTextPercentage_ = textPercentage;
        auto font = QApplication::font();
        font.setPointSizeF(10.5 * textPercentage / 100.0);
        messages_->setFont(font);
        font.setPointSizeF(12.0 * textPercentage / 100.0);
        composer_->setFont(font);
    }
    if (changed) {
        if (search_->isVisible()) search_->reject();
        messageMenu_->hide();
        chatMenuButton_->menu()->hide();
        dismissAttachment();
        history_->clear();
        messages_->retainMedia({});
        selected_ = peer;
        showingList_ = peer.isEmpty();
    }
    locked_ = false;
    messageLabels_ = state.value("messageActions").toObject();
    conversationLabels_ = state.value("conversationActions").toObject();
    if (folders_->currentData().toString() != state.value("folder").toString()) conversationMenu_->hide();
    const auto edit = state.value("edit").toObject();
    const auto context = edit.isEmpty() ? state.value("reply").toObject() : edit;
    const auto selection = state.value("selection").toObject();
    const bool wasSelecting = selecting_;
    selecting_ = selection.value("active").toBool();
    toggleSelectionKey_->setEnabled(selecting_);
    const bool showContext = !selecting_ && !context.isEmpty();
    const bool changedReplyBar = showContext != !replyBar_->isHidden();
    replyTitle_->setText(context.value("title").toString());
    replyText_->setText(context.value("preview").toString());
    cancelReply_->setAccessibleName(messageLabels_.value(edit.isEmpty() ? "cancelReply" : "cancelEdit").toString());
    cancelReply_->setToolTip(cancelReply_->accessibleName());
    cancelReply_->setEnabled(edit.isEmpty() || edit.value("canCancel").toBool());
    replyBar_->setVisible(showContext);
    selectionBar_->setVisible(selecting_);
    selectionText_->setText(selecting_ ? selection.value("label").toString() : QString{});
    selectionMenuButton_->setText(selection.value("actions").toString());
    selectionMenuButton_->setAccessibleName(selectionMenuButton_->text());
    copySelection_->setText(selection.value("copy").toString());
    saveSelection_->setText(selection.value("save").toString());
    forwardSelection_->setText(selection.value("forward").toString());
    deleteSelection_->setText(selection.value("delete").toString());
    copySelection_->setEnabled(selection.value("canUse").toBool());
    deleteSelection_->setEnabled(copySelection_->isEnabled());
    saveSelection_->setEnabled(selection.value("canKeep").toBool());
    forwardSelection_->setEnabled(saveSelection_->isEnabled());
    cancelSelection_->setAccessibleName(selection.value("cancel").toString());
    cancelSelection_->setToolTip(cancelSelection_->accessibleName());
    cancelSelection_->setEnabled(selection.value("canUse").toBool());
    if (wasSelecting != selecting_) {
        messages_->setSelectionMode(selecting_ ? QAbstractItemView::NoSelection : QAbstractItemView::SingleSelection);
        if (selecting_) { messages_->clearSelection(); messages_->setFocus(); }
        else selectionMenuButton_->menu()->hide();
    }
    const auto workspace = state.value("workspace").toObject();
    workspaceMenuButton_->setAccessibleName(workspace.value("label").toString());
    workspaceMenuButton_->setToolTip(workspaceMenuButton_->accessibleName());
    contactsAction_->setText(workspace.value("contacts").toString());
    labelButton(contactsRail_, contactsAction_->text());
    addFriendAction_->setText(workspace.value("addFriend").toString());
    requestsAction_->setText(workspace.value("requests").toString());
    manageFoldersAction_->setText(workspace.value("manageFolders").toString());
    savedAction_->setText(workspace.value("saved").toString());
    labelButton(savedRail_, savedAction_->text());
    stickerLibraryAction_->setText(workspace.value("stickerLibrary").toString());
    sendQueueAction_->setText(workspace.value("sendQueue").toString());
    forwardQueueAction_->setText(workspace.value("forwardQueue").toString());
    callHistoryAction_->setText(workspace.value("callHistory").toString());
    settingsAction_->setText(workspace.value("settings").toString());
    labelButton(settingsRail_, settingsAction_->text());
    appLockAction_->setText(workspace.value("appLock").toString());
    lockNowAction_->setText(workspace.value("lockNow").toString());
    lockNowAction_->setVisible(workspace.value("lockEnabled").toBool());
    const auto calls = state.value("calls").toObject();
    callButton_->setVisible(calls.value("enabled").toBool());
    labelButton(callButton_, calls.value("label").toString());
    voiceCall_->setText(calls.value("voice").toString());
    videoCall_->setText(calls.value("video").toString());
    const auto chatActions = state.value("chatActions").toObject();
    chatMenuButton_->setVisible(chatActions.value("enabled").toBool());
    chatMenuButton_->setAccessibleName(chatActions.value("label").toString());
    chatMenuButton_->setToolTip(chatMenuButton_->accessibleName());
    sharedMediaAction_->setText(chatActions.value("sharedMedia").toString());
    pinnedMessagesAction_->setText(chatActions.value("pinnedMessages").toString());
    chatQueueAction_->setText(chatActions.value("sendQueue").toString());
    historyNavigationAction_->setText(chatActions.value("history").toString());
    searchLabels_ = state.value("search").toObject();
    attachmentOptions_ = state.value("attachment").toObject();
    attachmentButton_->setAccessibleName(attachmentOptions_.value("menu").toString());
    attachmentButton_->setToolTip(attachmentButton_->accessibleName());
    chooseFiles_->setText(attachmentOptions_.value("attach").toString());
    stickersAction_->setText(attachmentOptions_.value("stickers").toString());
    shareContact_->setText(attachmentOptions_.value("shareContact").toString());
    shareLocation_->setText(attachmentOptions_.value("shareLocation").toString());
    labelButton(recordButton_, attachmentOptions_.value("record").toString());
    labelButton(stickerButton_, stickersAction_->text());
    {
        const QSignalBlocker blocked(folders_);
        const auto choices = state.value("folders").toArray();
        if (folderChoices_ != choices) {
            folders_->clear();
            for (const auto &value : choices) {
                const auto option = value.toObject();
                folders_->addItem(option.value("text").toString(), option.value("id").toString());
                folders_->setItemData(folders_->count() - 1, option.value("accessible").toString(), Qt::AccessibleTextRole);
            }
            folderChoices_ = choices;
        }
        folders_->setCurrentIndex(folders_->findData(state.value("folder").toString()));
        folders_->setAccessibleName(state.value("folderLabel").toString());
    }
    searchButton_->setText(searchLabels_.value("title").toString());
    searchButton_->setAccessibleName(searchButton_->text());
    labelButton(chatSearchButton_, searchButton_->text());
    hasNewer_ = history.value("newer").toBool();
    const bool busy = loading || history.value("busy").toBool();
    const bool failed = history.value("failed").toBool();
    auto setHistoryAction = [busy](QToolButton *button, const QString &label) {
        button->setAccessibleName(label);
        button->setToolTip(label);
        button->setEnabled(!busy);
    };
    setHistoryAction(older_, history.value("olderLabel").toString());
    setHistoryAction(newer_, history.value("newerLabel").toString());
    setHistoryAction(latest_, history.value("latestLabel").toString());
    setHistoryAction(retry_, history.value("retryLabel").toString());
    retry_->setText(retry_->accessibleName());
    older_->setVisible(history.value("older").toBool());
    newer_->setVisible(hasNewer_);
    historyError_->setText(history.value("errorLabel").toString());
    historyError_->setVisible(failed);
    retry_->setVisible(failed);
    back_->setAccessibleName(state.value("backLabel").toString());
    conversations_->setAccessibleName(state.value("listLabel").toString());
    emptyChat_->setText(state.value("selectChatLabel").toString());
    peers_->replace(state.value("conversations").toArray());
    const auto selectedIndex = peers_->byId(peer);
    if (conversations_->currentIndex() != selectedIndex) conversations_->setCurrentIndex(selectedIndex);
    sidebarTitle_->setText(state.value("listLabel").toString());
    title_->setText(state.value("title").toString());
    chatAvatar_->setText(state.value("initial").toString());
    presentComposer(state.value("editor").toObject(), changed);
    const bool changedRows = history_->replace(state.value("messages").toArray());
    split_->show();
    layoutPanes();
    if (changedReplyBar || wasSelecting != selecting_) detail_->layout()->activate();
    const bool relayout = changedRows || changedTextSize || changedReplyBar || wasSelecting != selecting_;
    if (relayout || resetPosition) messages_->doItemsLayout();
    const bool prepended = !oldFirst.isEmpty() && history_->byId(oldFirst).row() > 0;
    if (resetPosition) {
        const auto target = history_->byId(history.value("target").toString());
        if (!target.isValid()) messages_->scrollToBottom();
        else if (const auto offset = history.value("offset"); offset.isDouble()) {
            messages_->scrollTo(target, QAbstractItemView::PositionAtTop);
            messages_->verticalScrollBar()->setValue(scroll->value() + messages_->visualRect(target).top() -
                                                     qRound(offset.toDouble()));
        } else messages_->scrollTo(target, QAbstractItemView::PositionAtCenter);
    } else if (relayout && wasFollowing && !prepended) messages_->scrollToBottom();
    else if (relayout && anchor.isValid()) {
        messages_->scrollTo(anchor, QAbstractItemView::PositionAtTop);
        messages_->verticalScrollBar()->setValue(scroll->value() + messages_->visualRect(anchor).top() - top);
    }
    presenting_ = false;
    updateLatestButton();
    if (!historyViewportTimer_->isActive()) historyViewportTimer_->start();
}

void ChatWorkspace::updateLatestButton() {
    const auto *scroll = messages_->verticalScrollBar();
    latest_->setVisible(!locked_ && !initialHistory_ && history_->rowCount() > 0 &&
                        (hasNewer_ || scroll->value() < scroll->maximum()));
}

void ChatWorkspace::startCall(bool video) {
    if (locked_ || selected_.isEmpty() || !callButton_->isVisible() || !dialogRequest) return;
    auto request = QJsonDocument(QJsonObject{{"peer", selected_}, {"video", video}}).toJson(QJsonDocument::Compact);
    dialogRequest(31, request);
    request.fill(0);
}

bool ChatWorkspace::openWorkspaceCommand(const char *command) {
    if (locked_ || !dialogRequest) return false;
    auto request = QJsonDocument(QJsonObject{{"command", QString::fromLatin1(command)},
        {"hwnd", QString::number(qulonglong(window()->winId()), 16)}, {"peer", selected_}}).toJson(QJsonDocument::Compact);
    const bool accepted = dialogRequest(32, request);
    request.fill(0);
    return accepted;
}

bool ChatWorkspace::canReceiveAttachments() const {
    return !locked_ && !selected_.isEmpty() && !attachment_->isVisible() &&
        attachmentButton_->isEnabled() && composer_->acceptsSubmit();
}

void ChatWorkspace::dragEnterEvent(QDragEnterEvent *event) {
    if (canReceiveAttachments() && event->possibleActions().testFlag(Qt::CopyAction) && !localFiles(*event->mimeData()).isEmpty()) {
        event->setDropAction(Qt::CopyAction);
        event->accept();
    }
}

void ChatWorkspace::dropEvent(QDropEvent *event) {
    if (!canReceiveAttachments() || !event->possibleActions().testFlag(Qt::CopyAction)) return;
    const auto paths = localFiles(*event->mimeData());
    if (paths.isEmpty()) return;
    reportHistoryViewport();
    attachment_->showFor(selected_, attachmentOptions_, paths);
    if (!attachment_->isVisible()) return;
    event->setDropAction(Qt::CopyAction);
    event->accept();
}

bool ChatWorkspace::applyMessageAction(const QString &command, const QString &id, bool extending, const QString &value) {
    if (locked_ || selected_.isEmpty() || !dialogRequest) return false;
    auto request = QJsonDocument(QJsonObject{{"command", command}, {"peer", selected_}, {"id", id},
        {"extending", extending}, {"value", value}})
        .toJson(QJsonDocument::Compact);
    const bool accepted = dialogRequest(33, request);
    request.fill(0);
    return accepted;
}

void ChatWorkspace::openConversationMenu(const QModelIndex &index, const QPoint &globalPosition) {
    if (locked_ || !index.isValid()) return;
    const auto peer = peers_->idAt(index);
    const auto folder = folders_->currentData().toString();
    if (peer.isEmpty() || folder.isEmpty()) return;
    conversationMenu_->clear();
    auto add = [this, &peer, &folder](QMenu *menu, const char *label, const char *key, const QString &target = {}) {
        const QJsonObject request{{"command", QString::fromLatin1(key)}, {"peer", peer},
                                  {"folder", folder}, {"target", target}};
        auto *action = menu->addAction(conversationLabels_.value(QLatin1String(label)).toString());
        connect(action, &QAction::triggered, this, [this, request] { applyConversationAction(request); });
    };
    const bool pinned = index.data(ConversationList::Pinned).toBool();
    add(conversationMenu_, pinned ? "unpin" : "pin", pinned ? "unpin" : "pin");
    if (pinned) {
        const auto previous = index.siblingAtRow(index.row() - 1);
        const auto next = index.siblingAtRow(index.row() + 1);
        if (previous.data(ConversationList::Pinned).toBool()) add(conversationMenu_, "pinUp", "pinUp", peers_->idAt(previous));
        if (next.data(ConversationList::Pinned).toBool()) add(conversationMenu_, "pinDown", "pinDown", peers_->idAt(next));
    }
    const bool archived = index.data(ConversationList::Archived).toBool();
    add(conversationMenu_, archived ? "unarchive" : "archive", archived ? "unarchive" : "archive");
    const bool unread = index.data(ConversationList::HasUnread).toBool();
    add(conversationMenu_, unread ? "read" : "unread", unread ? "read" : "unread");

    auto *notifications = conversationMenu_->addMenu(conversationLabels_.value("notifications").toString());
    const auto muteStatus = index.data(ConversationList::MuteDescription).toString();
    if (!muteStatus.isEmpty()) {
        notifications->addAction(muteStatus)->setEnabled(false);
        add(notifications, "unmute", "unmute");
        notifications->addSeparator();
    }
    for (const auto *key : {"muteHour", "muteEightHours", "muteTwoDays", "muteAlways"}) add(notifications, key, key);
    conversationMenu_->addSeparator();
    add(conversationMenu_, "clear", "clear");
    add(conversationMenu_, "delete", "delete");
    conversationMenu_->popup(globalPosition);
    reportHistoryViewport();
}

bool ChatWorkspace::applyConversationAction(const QJsonObject &state) {
    if (locked_ || !dialogRequest) return false;
    auto request = QJsonDocument(state).toJson(QJsonDocument::Compact);
    const bool accepted = dialogRequest(34, request);
    request.fill(0);
    return accepted;
}

void ChatWorkspace::reportHistoryViewport() {
    if (locked_ || presenting_ || initialHistory_ || selected_.isEmpty()) return;
    const bool shown = messages_->isVisible();
    const auto *scroll = messages_->verticalScrollBar();
    const auto row = messages_->indexAt(QPoint(1, 1));
    QJsonArray readable;
    if (shown && !selecting_ && window()->isActiveWindow() && !window()->isMinimized() && !search_->isVisible() &&
        !conversationMenu_->isVisible() && !messageMenu_->isVisible() &&
        !attachment_->isVisible() && !preview_->isVisible())
        for (const auto &index : messages_->readableRows()) readable.append(history_->idAt(index));
    const auto visual = shown && !window()->isMinimized() ? messages_->visibleMedia() : QStringList{};
    messages_->retainMedia(visual);
    QJsonArray media;
    for (const auto &id : visual) media.append(id);
    const QJsonObject state{
        {"peer", selected_},
        {"generation", historyGeneration_},
        {"shown", shown},
        {"latest", !hasNewer_ && scroll->value() == scroll->maximum()},
        {"anchor", history_->idAt(row)},
        {"offset", row.isValid() ? messages_->visualRect(row).top() : 0},
        {"readable", readable},
        {"media", media},
        {"animateMedia", window()->isActiveWindow() && !search_->isVisible() && !preview_->isVisible()}
    };
    emit historyViewportChanged(QJsonDocument(state).toJson(QJsonDocument::Compact));
}

void ChatWorkspace::presentInlineImage(qint64 generation, const QString &id, const QImage &image) {
    if (locked_ || historyGeneration_ != generation || !messages_->visibleMedia().contains(id)) return;
    messages_->showMedia(id, image);
}

void ChatWorkspace::presentComposer(const QJsonObject &state, bool changedPeer) {
    if (locked_ || selected_ != state.value("peer").toString()) return;
    const auto target = state.value("editTarget").toString();
    const bool changedTarget = target != editTarget_;
    editTarget_ = target;
    composer_->present(state, changedPeer || changedTarget);
    if (!changedPeer && changedTarget && isVisible() && !composer_->isReadOnly()) composer_->setFocus();
    const auto sendLabel = state.value("sendLabel").toString();
    send_->setAccessibleName(sendLabel);
    send_->setToolTip(sendLabel);
    if (changedPeer || changedTarget)
        send_->setIcon(fluentIcon(editTarget_.isEmpty() ? Symbol::Send : Symbol::Check, true));
    send_->setEnabled(state.value("canSend").toBool());
    const bool canAttach = editTarget_.isEmpty() && state.value("canCompose").toBool();
    attachmentButton_->setEnabled(canAttach);
    recordButton_->setEnabled(canAttach);
    stickerButton_->setEnabled(canAttach);
    linkPreviewOptions_ = state.value("linkPreview").toObject();
    if (changedPeer || linkPreviewOptions_.isEmpty()) {
        linkPreviewButton_->menu()->close();
        linkPreviewButton_->menu()->clear();
    }
    const auto previewLabel = linkPreviewOptions_.value("label").toString();
    linkPreviewButton_->setText(linkPreviewOptions_.value("title").toString());
    labelButton(linkPreviewButton_, previewLabel);
    linkPreviewButton_->setVisible(!linkPreviewOptions_.isEmpty());
    const auto delivery = state.value("delivery").toObject();
    const auto optionsLabel = delivery.value("options").toString();
    const auto summary = delivery.value("summary").toString();
    deliveryButton_->setAccessibleName(summary.isEmpty() ? optionsLabel : optionsLabel + QStringLiteral(" · ") + summary);
    deliveryButton_->setToolTip(deliveryButton_->accessibleName());
    if (deliveryButton_->property("optionsActive").toBool() != !summary.isEmpty()) {
        deliveryButton_->setProperty("optionsActive", !summary.isEmpty());
        deliveryButton_->style()->unpolish(deliveryButton_);
        deliveryButton_->style()->polish(deliveryButton_);
        deliveryButton_->update();
    }
    deliveryButton_->setVisible(editTarget_.isEmpty());
    deliveryButton_->setEnabled(canAttach);
    deliveryOptions_->setText(optionsLabel);
    sendSilently_->setText(delivery.value("silent").toString());
    sendSilently_->setEnabled(send_->isEnabled());
    scheduleDraft_->setText(delivery.value("schedule").toString());
    scheduleDraft_->setEnabled(send_->isEnabled());
    clearDelivery_->setText(delivery.value("clear").toString());
    clearDelivery_->setVisible(!summary.isEmpty());
}

void ChatWorkspace::presentSearch(const QJsonObject &state) {
    if (!locked_) search_->present(state);
}

void ChatWorkspace::presentAttachment(const QJsonObject &state) {
    if (!locked_) attachment_->present(state);
}

void ChatWorkspace::presentNotice(const QString &message) {
    if (message.isEmpty()) {
        noticeTimer_->stop();
        notice_->clear();
        notice_->hide();
    } else if (!locked_ && isVisible() && !window()->isMinimized()) {
        notice_->setText(message);
        notice_->show();
        noticeTimer_->start();
    }
}

void ChatWorkspace::presentArchiveUndo(const QJsonObject &state) {
    const auto message = state.value("message").toString();
    archiveUndoVersion_ = state.value("version").toInteger();
    archiveText_->setText(message);
    undoArchive_->setText(state.value("undo").toString());
    undoArchive_->setEnabled(!message.isEmpty() && !locked_);
    archiveBanner_->setVisible(!message.isEmpty() && !locked_);
}

void ChatWorkspace::presentCall(const QJsonObject &state) {
    if (state.value("id").toString().isEmpty()) {
        if (callWindow_) callWindow_->reset();
        return;
    }
    if (!callWindow_) {
        callWindow_ = std::make_unique<CallWindow>();
        callWindow_->request = [this](const QByteArray &message) { return dialogRequest && dialogRequest(35, message); };
    }
    auto placed = state;
    if (auto *modal = qobject_cast<QDialog *>(QApplication::activeWindow()); modal && modal != callWindow_.get()) {
        for (auto *owner = modal->parentWidget(); owner; owner = owner->parentWidget()) {
            if (owner != window()) continue;
            placed.insert("anchor", QString::number(qulonglong(modal->winId()), 16));
            placed.insert("compact", true);
            break;
        }
    }
    callWindow_->present(placed);
}

void ChatWorkspace::presentSettings(const QJsonObject &state) {
    if (!settings_) {
        if (!state.value("open").toBool() || locked_) return;
        settings_ = new SettingsWindow(this);
        settings_->request = [this](const QByteArray &message) { return !locked_ && dialogRequest && dialogRequest(36, message); };
    }
    settings_->present(state);
}

bool ChatWorkspace::isCallWindowActive() const {
    if (!callWindow_ || !callWindow_->isVisible() || callWindow_->isMinimized()) return false;
    for (auto *candidate = QApplication::activeWindow(); candidate; candidate = candidate->parentWidget())
        if (candidate == callWindow_.get()) return true;
    return false;
}

void ChatWorkspace::dismissAttachment() {
    if (attachment_->isVisible()) attachment_->reset();
    if (preview_->isVisible()) preview_->reset();
}

void ChatWorkspace::presentPreview(const QJsonObject &state) {
    if (!locked_) preview_->present(state);
}

void ChatWorkspace::presentImage(qint64 generation, const uchar *pixels, int width, int height, int stride) {
    if (!locked_) preview_->presentImage(generation, pixels, width, height, stride);
}

void ChatWorkspace::dismissSearch() {
    if (search_->isVisible()) search_->reject();
}

void ChatWorkspace::layoutPanes() {
    if (locked_) return;
    const bool narrow = width() < 640;
    sidebarPane_->setVisible(!narrow || showingList_);
    detailContainer_->setVisible(!narrow || (!selected_.isEmpty() && !showingList_));
    auto *page = selected_.isEmpty() ? static_cast<QWidget *>(emptyChat_) : detail_;
    if (detailPages_->currentWidget() != page) detailPages_->setCurrentWidget(page);
    const bool twoPanes = !narrow;
    if (twoPanes && !twoPanes_) {
        const auto restored = qBound(1, sidebarWidth_, width() - 1);
        split_->setSizes({restored, width() - restored});
    }
    twoPanes_ = twoPanes;
    back_->setVisible(narrow);
    chatSearchButton_->setVisible(narrow);
}

void ChatWorkspace::restoreSidebarWidth(int width) {
    if (width > 0) sidebarWidth_ = width;
}

void ChatWorkspace::resizeEvent(QResizeEvent *event) {
    QWidget::resizeEvent(event);
    layoutPanes();
}
} // namespace MsgAT
