// SPDX-License-Identifier: AGPL-3.0-only
#include "ConversationList.h"
#include "ChatPalette.h"

#include <QFontMetrics>
#include <QIcon>
#include <QPainter>
#include <QPainterPath>
#include <QStyledItemDelegate>
#include <QStyleOptionViewItem>

namespace MsgAT {
namespace {

class ConversationDelegate final : public QStyledItemDelegate {
public:
    using QStyledItemDelegate::QStyledItemDelegate;

    QSize sizeHint(const QStyleOptionViewItem &option, const QModelIndex &) const override {
        const QFontMetrics metrics(option.font);
        return {1, qMax(64, 18 + metrics.height() * 2)};
    }

    void paint(QPainter *p, const QStyleOptionViewItem &option,
               const QModelIndex &index) const override {
        const auto &palette = option.palette;
        const bool selected = option.state.testFlag(QStyle::State_Selected);
        const bool hover = option.state.testFlag(QStyle::State_MouseOver);
        const auto group = option.state.testFlag(QStyle::State_Enabled)
            ? QPalette::Active : QPalette::Disabled;
        auto color = [&](QPalette::ColorRole role) { return palette.color(group, role); };
        auto field = [&](int role) { return index.data(role).toString(); };

        p->save();
        p->setClipRect(option.rect);
        p->setRenderHint(QPainter::Antialiasing);
        const QRect row = option.rect.adjusted(4, 1, -4, -1);
        auto visual = [&](const QRect &logical) {
            return QStyle::visualRect(option.direction, row, logical);
        };
        if (selected || hover) {
            p->setPen(Qt::NoPen);
            p->setBrush(color(selected ? QPalette::Highlight : QPalette::AlternateBase));
            p->drawRoundedRect(row, 8, 8);
            if (selected) {
                p->setBrush(color(QPalette::Accent));
                p->drawRoundedRect(visual(QRect(row.left(), row.center().y() - 10, 3, 20)), 1.5, 1.5);
            }
        }
        if (option.state.testFlag(QStyle::State_HasFocus) &&
            option.state.testFlag(QStyle::State_KeyboardFocusChange)) {
            p->setPen(QPen(color(selected ? QPalette::HighlightedText : QPalette::Accent), 1));
            p->setBrush(Qt::NoBrush);
            p->drawRoundedRect(row.adjusted(1, 1, -1, -1), 7, 7);
        }

        const QRect avatar = visual(QRect(row.left() + 8, row.center().y() - 21, 42, 42));
        const QIcon photo = qvariant_cast<QIcon>(index.data(Qt::DecorationRole));
        p->save();
        QPainterPath circle;
        circle.addEllipse(avatar);
        p->setClipPath(circle, Qt::IntersectClip);
        p->fillRect(avatar, color(QPalette::Button));
        if (photo.isNull()) {
            QFont initials = option.font;
            initials.setWeight(QFont::DemiBold);
            p->setFont(initials);
            p->setPen(color(QPalette::ButtonText));
            p->drawText(avatar, Qt::AlignCenter, field(ConversationList::AvatarInitials));
        } else {
            photo.paint(p, avatar, Qt::AlignCenter);
        }
        p->restore();

        const QRect content = row.adjusted(60, 5, -10, -5);
        if (content.width() <= 0) { p->restore(); return; }
        QFont title = option.font;
        title.setWeight(QFont::DemiBold);
        QFont metadata = option.font;
        if (metadata.pointSizeF() > 0) metadata.setPointSizeF(metadata.pointSizeF() * 0.85);
        else if (metadata.pixelSize() > 0) metadata.setPixelSize(qMax(1, qRound(metadata.pixelSize() * 0.85)));
        const QColor primary = color(selected ? QPalette::HighlightedText : QPalette::Text);
        const QColor secondary = color(selected ? QPalette::HighlightedText : QPalette::PlaceholderText);
        const int line = qMin(content.height() / 2, qMax(QFontMetrics(title).height(),
                                                        QFontMetrics(option.font).height()) + 2);
        const int top = content.center().y() - line;
        auto drawLine = [&](const QRect &logical, const QString &text, const QFont &font,
                            QColor pen, bool trailing = false) {
            if (logical.width() <= 0 || text.isEmpty()) return;
            p->setFont(font);
            p->setPen(pen);
            const auto align = QStyle::visualAlignment(option.direction,
                trailing ? Qt::AlignRight : Qt::AlignLeft);
            p->drawText(visual(logical), int(align | Qt::AlignVCenter) | Qt::TextSingleLine,
                        QFontMetrics(font).elidedText(text, Qt::ElideRight, logical.width()));
        };
        const QString time = field(ConversationList::ActivityTime);
        const int timeWidth = qMin(content.width() / 2, QFontMetrics(metadata).horizontalAdvance(time));
        int remaining = content.width() - timeWidth - (timeWidth ? 6 : 0);
        auto marker = [&](const QPainterPath &path) {
            if (remaining < 28) return;
            remaining -= 16;
            const auto box = visual(QRect(content.left() + remaining, top + (line - 12) / 2, 12, 12));
            p->save();
            p->translate(box.topLeft());
            p->strokePath(path, QPen(secondary, 1.1, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
            p->restore();
        };
        if (index.data(ConversationList::Pinned).toBool()) {
            QPainterPath pin;
            pin.moveTo(3, 2); pin.lineTo(9, 2); pin.moveTo(4, 2); pin.lineTo(4, 5);
            pin.lineTo(2.5, 7.5); pin.lineTo(9.5, 7.5); pin.lineTo(8, 5); pin.lineTo(8, 2);
            pin.moveTo(6, 7.5); pin.lineTo(6, 11);
            marker(pin);
        }
        if (!field(ConversationList::MuteDescription).isEmpty()) {
            QPainterPath muted;
            muted.moveTo(1, 4); muted.lineTo(3.5, 4); muted.lineTo(7, 1.5); muted.lineTo(7, 10.5);
            muted.lineTo(3.5, 8); muted.lineTo(1, 8); muted.closeSubpath();
            muted.moveTo(9, 4.5); muted.lineTo(11.5, 7.5); muted.moveTo(11.5, 4.5); muted.lineTo(9, 7.5);
            marker(muted);
        }
        drawLine(QRect(content.left(), top, remaining, line), field(Qt::DisplayRole), title, primary);
        drawLine(QRect(content.right() + 1 - timeWidth, top, timeWidth, line), time, metadata, secondary, true);

        const QString unread = field(ConversationList::UnreadLabel);
        const QString receipt = field(ConversationList::ReceiptLabel);
        const int badgeHeight = QFontMetrics(metadata).height() + 3;
        const int badgeWidth = unread.isEmpty() ? 0 : qMin(content.width() / 2,
            qMax(badgeHeight, QFontMetrics(metadata).horizontalAdvance(unread) + 12));
        const int receiptWidth = badgeWidth ? 0 : qMin(content.width() / 2, QFontMetrics(metadata).horizontalAdvance(receipt));
        const int tail = badgeWidth + receiptWidth;
        const QRect preview(content.left(), top + line, content.width() - tail - (tail ? 6 : 0), line);
        const QString draft = field(ConversationList::DraftLabel);
        const int prefix = qMin(preview.width(), QFontMetrics(option.font).horizontalAdvance(draft));
        drawLine(QRect(preview.left(), preview.top(), prefix, line), draft, option.font, primary);
        drawLine(preview.adjusted(prefix + (prefix ? 3 : 0), 0, 0, 0),
                 field(ConversationList::Preview), option.font, secondary);
        if (badgeWidth) {
            const QRect badge = visual(QRect(content.right() + 1 - badgeWidth,
                top + line + (line - badgeHeight) / 2, badgeWidth, badgeHeight));
            p->setPen(Qt::NoPen);
            p->setBrush(color(QPalette::ButtonText));
            p->drawRoundedRect(badge, badgeHeight / 2.0, badgeHeight / 2.0);
            p->setPen(color(QPalette::Button));
            p->setFont(metadata);
            p->drawText(badge, Qt::AlignCenter, unread);
        } else {
            drawLine(QRect(content.right() + 1 - receiptWidth, top + line, receiptWidth, line),
                     receipt, metadata, secondary, true);
        }
        p->restore();
    }
};

} // namespace

ConversationList::ConversationList(QWidget *parent) : QListView(parent) {
    setObjectName(QStringLiteral("conversationList"));
    installChatPalette(this, ChatSurface::ConversationList);
    setItemDelegate(new ConversationDelegate(this));
    setSelectionMode(QAbstractItemView::SingleSelection);
    setEditTriggers(QAbstractItemView::NoEditTriggers);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    setUniformItemSizes(true);
    setMouseTracking(true);
    setFrameShape(QFrame::NoFrame);
}

} // namespace MsgAT
