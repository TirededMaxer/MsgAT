// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QHash>
#include <QImage>
#include <QListView>

namespace MsgAT {

class MessageTimeline final : public QListView {
public:
    // DisplayRole is safe-to-display plain text. The owner formats edited/time/receipt
    // and day labels in the active language and supplies the accessible description.
    enum Role {
        Outgoing = Qt::UserRole + 1,
        ContinuesFromPrevious,
        ContinuesInNext,
        Metadata,
        DayLabel,
        UnreadDivider,
        SearchHighlight,
        UnreadIncoming,
        HasAttachment,
        MessageId,
        CanReply,
        CanCopy,
        CanEdit,
        CanRetry,
        Retrying,
        CanRevoke,
        HasCard,
        Selectable,
        CanSaveOrForward,
        MessageSelected,
        CanDeleteLocally,
        CanCancelSend,
        CanPin,
        LinkPreviewText,
        LinkPreviewAbove,
        HasReplyQuote,
        VisibleLinks,
        Revoked,
        InspectFormattedText,
        RichRuns,
        ReplyText,
        InlineMedia,
        InlineMediaCount,
        InlineMediaStatus
    };

    explicit MessageTimeline(QWidget *parent = nullptr);
    void setModel(QAbstractItemModel *model) override;
    QList<QModelIndex> readableRows() const;
    QString richAction(const QModelIndex &index, QPoint point) const;
    QString mediaTarget(const QModelIndex &index, QPoint point) const;
    QStringList visibleMedia() const;
    void retainMedia(const QStringList &ids);
    void showMedia(const QString &id, const QImage &image);
    QImage imageFor(const QString &id) const;
    bool previewContains(const QModelIndex &index, QPoint point) const;
    using QListView::doItemsLayout;

protected:
    void resizeEvent(QResizeEvent *event) override;
private:
    QHash<QString, QImage> inlineImages_;
};

} // namespace MsgAT
