// SPDX-License-Identifier: AGPL-3.0-only
#include "ChatRows.h"
#include "ConversationList.h"
#include "MessageTimeline.h"
#include <QSet>

namespace MsgAT {

ChatRows::ChatRows(Kind kind, QObject *parent) : QAbstractListModel(parent), kind_(kind) {}
int ChatRows::rowCount(const QModelIndex &parent) const { return parent.isValid() ? 0 : int(rows_.size()); }

QString ChatRows::idAt(const QModelIndex &index) const {
    if (index.model() != this || index.row() < 0 || index.row() >= rows_.size()) return {};
    return rows_[index.row()].value("id").toString();
}

QModelIndex ChatRows::byId(const QString &id) const {
    for (int i = 0; i < rows_.size(); ++i)
        if (rows_[i].value("id").toString() == id) return index(i);
    return {};
}

QVariant ChatRows::data(const QModelIndex &index, int role) const {
    if (index.model() != this || index.row() < 0 || index.row() >= rows_.size()) return {};
    const auto &row = rows_[index.row()];
    if (role == Qt::AccessibleTextRole) return row.value("accessible").toString();
    if (kind_ == Kind::Conversations) {
        switch (role) {
        case Qt::DisplayRole: return row.value("title").toString();
        case ConversationList::Preview: return row.value("preview").toString();
        case ConversationList::ActivityTime: return row.value("time").toString();
        case ConversationList::AvatarInitials: return row.value("initials").toString();
        case ConversationList::UnreadLabel: return row.value("unread").toString();
        case ConversationList::ReceiptLabel: return row.value("receipt").toString();
        case ConversationList::DraftLabel: return row.value("draft").toString();
        case ConversationList::Pinned: return row.value("pinned").toBool();
        case ConversationList::Archived: return row.value("archived").toBool();
        case ConversationList::HasUnread: return row.value("hasUnread").toBool();
        case ConversationList::MuteDescription: return row.value("muteDescription").toString();
        default: return {};
        }
    }
    switch (role) {
    case Qt::DisplayRole: return row.value("body").toString();
    case MessageTimeline::MessageId: return row.value("id").toString();
    case MessageTimeline::CanReply: return row.value("canReply").toBool();
    case MessageTimeline::CanCopy: return row.value("canCopy").toBool();
    case MessageTimeline::CanEdit: return row.value("canEdit").toBool();
    case MessageTimeline::CanRetry: return row.value("canRetry").toBool();
    case MessageTimeline::Retrying: return row.value("retrying").toBool();
    case MessageTimeline::CanRevoke: return row.value("canRevoke").toBool();
    case MessageTimeline::HasCard: return row.value("hasCard").toBool();
    case MessageTimeline::Selectable: return row.value("selectable").toBool();
    case MessageTimeline::CanSaveOrForward: return row.value("canKeep").toBool();
    case MessageTimeline::MessageSelected: return row.value("selected").toBool();
    case MessageTimeline::CanDeleteLocally: return row.value("canDelete").toBool();
    case MessageTimeline::CanCancelSend: return row.value("canCancelSend").toBool();
    case MessageTimeline::Revoked: return row.value("revoked").toBool();
    case MessageTimeline::RichRuns: return row.value("runs").toArray();
    case MessageTimeline::ReplyText: return row.value("quote").toString();
    case MessageTimeline::InlineMedia: return row.value("media").toArray();
    case MessageTimeline::InlineMediaCount: return row.value("mediaCount").toInt();
    case MessageTimeline::InlineMediaStatus: return row.value("mediaStatus").toString();
    case MessageTimeline::InspectFormattedText: return row.value("inspectText").toBool();
    case MessageTimeline::CanPin: return row.value("canPin").toBool();
    case MessageTimeline::LinkPreviewText: return row.value("preview").toString();
    case MessageTimeline::LinkPreviewAbove: return row.value("previewAbove").toBool();
    case MessageTimeline::HasReplyQuote: return row.value("hasQuote").toBool();
    case MessageTimeline::VisibleLinks: {
        QStringList links;
        for (const auto &link : row.value("links").toArray()) links.append(link.toString());
        return links;
    }
    case MessageTimeline::Outgoing: return row.value("outgoing").toBool();
    case MessageTimeline::ContinuesFromPrevious: return row.value("joinsAbove").toBool();
    case MessageTimeline::ContinuesInNext: return row.value("joinsBelow").toBool();
    case MessageTimeline::Metadata: return row.value("metadata").toString();
    case MessageTimeline::ReceiptStatus: return row.value("receiptStatus").toInt(4);
    case MessageTimeline::ReceiptLabel: return row.value("receiptLabel").toString();
    case MessageTimeline::MediaFooter: return row.value("mediaFooter").toBool();
    case MessageTimeline::DayLabel: return row.value("day").toString();
    case MessageTimeline::UnreadDivider: return row.value("unread").toString();
    case MessageTimeline::SearchHighlight: return row.value("highlighted").toBool();
    case MessageTimeline::UnreadIncoming: return row.value("unreadIncoming").toBool();
    case MessageTimeline::HasAttachment: return row.value("hasAttachment").toBool();
    default: return {};
    }
}

void ChatRows::clear() {
    if (rows_.isEmpty()) return;
    beginResetModel();
    rows_.clear();
    endResetModel();
}

bool ChatRows::replace(const QJsonArray &rows) {
    if (rows.isEmpty()) {
        const bool changed = !rows_.isEmpty();
        clear();
        return changed;
    }
    QSet<QString> retained;
    QList<QJsonObject> desired;
    retained.reserve(rows.size());
    desired.reserve(rows.size());
    for (const auto value : rows) {
        const auto row = value.toObject();
        const auto id = row.value("id").toString();
        if (id.isEmpty() || retained.contains(id)) continue;
        desired.append(row);
        retained.insert(id);
    }
    if (rows_ == desired) return false;
    QSet<QString> current;
    current.reserve(rows_.size());
    for (int last = int(rows_.size()) - 1; last >= 0;) {
        const auto id = rows_[last].value("id").toString();
        if (retained.contains(id)) {
            current.insert(id);
            --last;
            continue;
        }
        int first = last;
        while (first > 0 && !retained.contains(rows_[first - 1].value("id").toString())) --first;
        beginRemoveRows({}, first, last);
        rows_.remove(first, last - first + 1);
        endRemoveRows();
        last = first - 1;
    }
    for (int i = 0; i < desired.size();) {
        const auto &next = desired[i];
        const auto id = next.value("id").toString();
        if (!current.contains(id)) {
            int end = i + 1;
            while (end < desired.size() && !current.contains(desired[end].value("id").toString())) ++end;
            beginInsertRows({}, i, end - 1);
            if (i == rows_.size()) rows_.append(desired.mid(i, end - i));
            else {
                rows_.insert(i, end - i, QJsonObject{});
                for (int row = i; row < end; ++row) rows_[row] = desired[row];
            }
            endInsertRows();
            i = end;
            continue;
        }
        if (rows_[i].value("id") != next.value("id")) {
            int old = i + 1;
            while (rows_[old].value("id") != next.value("id")) ++old;
            beginMoveRows({}, old, old, {}, i);
            rows_.move(old, i);
            endMoveRows();
        }
        if (rows_[i] != next) {
            rows_[i] = next;
            emit dataChanged(index(i), index(i));
        }
        ++i;
    }
    return true;
}

} // namespace MsgAT
