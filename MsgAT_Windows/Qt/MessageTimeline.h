// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

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
        VisibleLinks
    };

    explicit MessageTimeline(QWidget *parent = nullptr);
    void setModel(QAbstractItemModel *model) override;
    QList<QModelIndex> readableRows() const;
    bool previewContains(const QModelIndex &index, QPoint point) const;
    using QListView::doItemsLayout;

protected:
    void resizeEvent(QResizeEvent *event) override;
};

} // namespace MsgAT
