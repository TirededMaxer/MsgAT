// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QJsonArray>
#include <QWidget>
#include <functional>
#include <memory>

class QLabel;
class QAction;
class QComboBox;
class QDragEnterEvent;
class QDropEvent;
class QMenu;
class QModelIndex;
class QShortcut;
class QSplitter;
class QStackedLayout;
class QTimer;
class QToolButton;

namespace MsgAT {
class ChatRows;
class ChatComposer;
class ConversationList;
class MessageTimeline;
class MessageSearch;
class AttachmentReview;
class AttachmentPreview;
class CallWindow;
class SettingsWindow;

class ChatWorkspace final : public QWidget {
    Q_OBJECT
public:
    explicit ChatWorkspace(QWidget *parent = nullptr);
    ~ChatWorkspace() override;
    void present(const QByteArray &frame);
    void presentComposer(const QJsonObject &state, bool changedPeer);
    void presentSearch(const QJsonObject &state);
    void presentAttachment(const QJsonObject &state);
    void dismissAttachment();
    void presentPreview(const QJsonObject &state);
    void presentImage(qint64 generation, const uchar *pixels, int width, int height, int stride);
    void presentNotice(const QString &message);
    void presentArchiveUndo(const QJsonObject &state);
    void presentCall(const QJsonObject &state);
    void presentSettings(const QJsonObject &state);
    bool isCallWindowActive() const;
    int sidebarWidth() const { return sidebarWidth_; }
    void restoreSidebarWidth(int width);
    void dismissSearch();
    void reportHistoryViewport();
    void clear();
    std::function<bool(const QString &, const QString &)> copyText;
    std::function<bool(int, const QByteArray &)> dialogRequest;
signals:
    void conversationRequested(const QString &peer);
    void folderRequested(const QString &key);
    void draftChanged(const QString &peer, const QString &target, const QString &draft);
    void sendRequested(const QString &peer, const QString &target, const QString &draft);
    void historyRequested(int action, const QString &peer);
    void historyViewportChanged(const QByteArray &state);
protected:
    void resizeEvent(QResizeEvent *event) override;
    void dragEnterEvent(QDragEnterEvent *event) override;
    void dropEvent(QDropEvent *event) override;
private:
    void layoutPanes();
    void updateLatestButton();
    void startCall(bool video);
    bool openWorkspaceCommand(const char *command);
    bool canReceiveAttachments() const;
    bool applyMessageAction(const QString &command, const QString &id = {}, bool extending = false);
    void openConversationMenu(const QModelIndex &index, const QPoint &globalPosition);
    bool applyConversationAction(const QJsonObject &state);
    QSplitter *split_;
    ConversationList *conversations_;
    QMenu *conversationMenu_;
    QJsonObject conversationLabels_;
    QWidget *archiveBanner_;
    QLabel *archiveText_;
    QToolButton *undoArchive_;
    qint64 archiveUndoVersion_ = 0;
    QComboBox *folders_;
    QJsonArray folderChoices_;
    MessageTimeline *messages_;
    MessageSearch *search_;
    AttachmentReview *attachment_;
    AttachmentPreview *preview_;
    std::unique_ptr<CallWindow> callWindow_;
    SettingsWindow *settings_ = nullptr;
    QToolButton *attachmentButton_;
    QToolButton *recordButton_;
    QAction *chooseFiles_, *shareContact_, *shareLocation_;
    QToolButton *deliveryButton_;
    QAction *deliveryOptions_, *sendSilently_, *scheduleDraft_, *clearDelivery_;
    QJsonObject attachmentOptions_;
    QToolButton *searchButton_, *chatSearchButton_;
    QToolButton *workspaceMenuButton_;
    QAction *contactsAction_, *addFriendAction_, *requestsAction_, *settingsAction_;
    QAction *manageFoldersAction_;
    QAction *savedAction_, *sendQueueAction_, *forwardQueueAction_, *callHistoryAction_;
    QAction *appLockAction_, *lockNowAction_;
    QToolButton *callButton_;
    QAction *voiceCall_, *videoCall_;
    QToolButton *chatMenuButton_;
    QAction *sharedMediaAction_, *pinnedMessagesAction_, *chatQueueAction_;
    QJsonObject searchLabels_;
    ChatRows *peers_, *history_;
    QWidget *detailContainer_, *detail_;
    QStackedLayout *detailPages_;
    QLabel *title_, *emptyChat_;
    QToolButton *back_;
    ChatComposer *composer_;
    QWidget *replyBar_;
    QLabel *replyTitle_, *replyText_;
    QToolButton *cancelReply_;
    QMenu *messageMenu_;
    QJsonObject messageLabels_;
    QWidget *selectionBar_;
    QLabel *selectionText_;
    QToolButton *selectionMenuButton_, *cancelSelection_;
    QAction *copySelection_, *saveSelection_, *forwardSelection_, *deleteSelection_;
    QShortcut *toggleSelectionKey_;
    bool selecting_ = false;
    QToolButton *send_;
    QToolButton *older_, *newer_, *latest_, *retry_;
    QLabel *historyError_;
    QTimer *historyViewportTimer_;
    QLabel *notice_;
    QTimer *noticeTimer_;
    QString selected_;
    QString editTarget_;
    qint64 historyGeneration_ = -1;
    int theme_ = -1, chatTextPercentage_ = 0;
    int sidebarWidth_ = 288;
    bool twoPanes_ = false;
    bool initialHistory_ = false, hasNewer_ = false;
    bool presenting_ = false;
    bool showingList_ = true, locked_ = true;
};
} // namespace MsgAT
