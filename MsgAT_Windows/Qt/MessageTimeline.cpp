// SPDX-License-Identifier: AGPL-3.0-only
#include "MessageTimeline.h"
#include "ChatPalette.h"

#include <QAbstractTextDocumentLayout>
#include <QCache>
#include <QPainter>
#include <QPainterPath>
#include <QResizeEvent>
#include <QScrollBar>
#include <QStyledItemDelegate>
#include <QTextDocument>
#include <QtMath>

namespace MsgAT {
namespace {

QPainterPath bubbleShape(QRectF rect, bool right, bool joinsAbove, bool joinsBelow) {
    const qreal full = qMin(14.0, qMin(rect.width(), rect.height()) / 2.0);
    const qreal small = qMin(4.0, full);
    const qreal tl = !right && joinsAbove ? small : full;
    const qreal tr = right && joinsAbove ? small : full;
    const qreal br = right && joinsBelow ? small : full;
    const qreal bl = !right && joinsBelow ? small : full;
    const auto l = rect.left(), r = rect.right(), t = rect.top(), b = rect.bottom();
    QPainterPath path;
    path.moveTo(l + tl, t);
    path.lineTo(r - tr, t); path.quadTo(r, t, r, t + tr);
    path.lineTo(r, b - br); path.quadTo(r, b, r - br, b);
    path.lineTo(l + bl, b); path.quadTo(l, b, l, b - bl);
    path.lineTo(l, t + tl); path.quadTo(l, t, l + tl, t);
    path.closeSubpath();
    return path;
}

struct MessageLayout {
    QTextDocument body;
    QFont smallFont;
    QRectF bubble, text, footer;
    QStringList labels;
    int labelHeight = 0, height = 0;
    bool right;

    MessageLayout(int width, const QStyleOptionViewItem &option, const QModelIndex &index)
        : smallFont(option.font),
          right(index.data(MessageTimeline::Outgoing).toBool() != (option.direction == Qt::RightToLeft)) {
        if (smallFont.pointSizeF() > 0) smallFont.setPointSizeF(smallFont.pointSizeF() * 0.86);
        else if (smallFont.pixelSize() > 0) smallFont.setPixelSize(qMax(1, qRound(smallFont.pixelSize() * 0.86)));
        const QFontMetrics small(smallFont);
        for (auto role : {MessageTimeline::DayLabel, MessageTimeline::UnreadDivider}) {
            const auto label = index.data(role).toString();
            if (!label.isEmpty()) labels.append(label);
        }
        labelHeight = small.height() + 12;
        const int top = int(labels.size()) * labelHeight + 2;
        const int margin = width >= 420 ? 20 : 8;
        const int padding = 11;
        const int available = qMax(1, width - 2 * margin);
        const int maxBubble = qMin(available, qMax(112, qMin(540, qRound(available * 0.81))));
        const int maxText = qMax(1, maxBubble - 2 * padding);

        body.setDocumentMargin(0);
        body.setDefaultFont(option.font);
        auto wrapping = body.defaultTextOption();
        wrapping.setWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
        body.setDefaultTextOption(wrapping);
        body.setPlainText(index.data(Qt::DisplayRole).toString());
        body.setTextWidth(maxText);
        const int textWidth = qBound(1, qCeil(body.idealWidth()), maxText);
        body.setTextWidth(textWidth);
        const int textHeight = qCeil(body.size().height());
        const auto meta = index.data(MessageTimeline::Metadata).toString();
        const int metaWidth = qMin(maxText, small.horizontalAdvance(meta));
        const bool inlineFooter = !meta.isEmpty() && body.lineCount() == 1 &&
                                  textWidth + 9 + metaWidth <= maxText;
        const int inner = inlineFooter ? textWidth + 9 + metaWidth : qMax(textWidth, metaWidth);
        const int w = qMin(available, inner + 2 * padding);
        const int h = (inlineFooter ? qMax(textHeight, small.height()) : textHeight) +
                      (!meta.isEmpty() && !inlineFooter ? small.height() + 2 : 0) + 14;
        bubble = QRectF(right ? width - margin - w : margin, top, w, h);
        const int left = int(bubble.left()) + padding;
        const bool rtl = option.direction == Qt::RightToLeft;
        text = QRectF(inlineFooter && rtl ? left + metaWidth + 9 : left, top + 7,
                      inlineFooter ? textWidth : inner, textHeight);
        if (!inlineFooter) body.setTextWidth(inner);
        footer = QRectF(rtl ? left : left + inner - metaWidth, bubble.bottom() - 7 - small.height(),
                        metaWidth, small.height());
        height = qCeil(bubble.bottom()) + (index.data(MessageTimeline::ContinuesInNext).toBool() ? 1 : 6);
    }
};

class MessageDelegate final : public QStyledItemDelegate {
public:
    explicit MessageDelegate(MessageTimeline *view) : QStyledItemDelegate(view), view_(view) {}

    void resetLayouts() { layouts_.clear(); }

    void invalidateRows(const QModelIndex &first, const QModelIndex &last) {
        for (int row = first.row(); row <= last.row(); ++row) {
            const auto index = first.sibling(row, 0);
            layouts_.remove(index.data(MessageTimeline::MessageId).toString());
        }
    }

    QRectF bubbleBounds(const QStyleOptionViewItem &option, const QModelIndex &index, const QRect &row) const {
        return layout(row.width(), option, index).bubble.translated(row.topLeft());
    }

    QSize sizeHint(const QStyleOptionViewItem &option, const QModelIndex &index) const override {
        const int width = view_->viewport()->width();
        return {width, layout(width, option, index).height};
    }

    void paint(QPainter *p, const QStyleOptionViewItem &option, const QModelIndex &index) const override {
        const auto &row = layout(option.rect.width(), option, index);
        const auto &colors = option.palette;
        const bool selected = option.state.testFlag(QStyle::State_Selected) || index.data(MessageTimeline::MessageSelected).toBool();
        const bool outgoing = index.data(MessageTimeline::Outgoing).toBool();
        const auto ink = colors.color(selected ? QPalette::HighlightedText : QPalette::Text);
        p->save();
        p->setClipRect(option.rect, Qt::IntersectClip);
        p->translate(option.rect.topLeft());
        p->setRenderHint(QPainter::Antialiasing);
        p->setFont(row.smallFont);
        p->setPen(colors.color(QPalette::WindowText));
        int top = 0;
        for (const auto &label : row.labels) {
            p->drawText(QRect(0, top, option.rect.width(), row.labelHeight), Qt::AlignCenter, label);
            top += row.labelHeight;
        }
        const auto path = bubbleShape(row.bubble, row.right,
            index.data(MessageTimeline::ContinuesFromPrevious).toBool(),
            index.data(MessageTimeline::ContinuesInNext).toBool());
        p->fillPath(path, colors.brush(selected ? QPalette::Highlight :
                                      outgoing ? QPalette::AlternateBase : QPalette::Base));
        if (index.data(MessageTimeline::SearchHighlight).toBool())
            p->strokePath(path, QPen(colors.color(QPalette::Accent), 2));
        if (option.state.testFlag(QStyle::State_HasFocus) &&
            option.state.testFlag(QStyle::State_KeyboardFocusChange))
            p->strokePath(path, QPen(ink, 1));
        p->save();
        p->translate(row.text.topLeft());
        QAbstractTextDocumentLayout::PaintContext context;
        context.palette = colors;
        context.palette.setColor(QPalette::Text, ink);
        row.body.documentLayout()->draw(p, context);
        p->restore();
        p->setPen(colors.color(selected ? QPalette::HighlightedText : QPalette::PlaceholderText));
        const auto footer = QFontMetrics(row.smallFont).elidedText(index.data(MessageTimeline::Metadata).toString(),
                                                                   Qt::ElideRight, int(row.footer.width()));
        p->drawText(row.footer, Qt::AlignCenter, footer);
        p->restore();
    }

private:
    struct CachedLayout {
        int width;
        QFont font;
        Qt::LayoutDirection direction;
        qreal scale;
        MessageLayout row;

        CachedLayout(int width, const QStyleOptionViewItem &option, qreal scale, const QModelIndex &index)
            : width(width), font(option.font), direction(option.direction), scale(scale), row(width, option, index) {}
    };

    const MessageLayout &layout(int width, const QStyleOptionViewItem &option, const QModelIndex &index) const {
        const auto scale = view_->devicePixelRatioF();
        const auto key = index.data(MessageTimeline::MessageId).toString();
        if (const auto *cached = layouts_.object(key); cached && cached->width == width &&
            cached->font == option.font && cached->direction == option.direction && cached->scale == scale)
            return cached->row;
        auto *entry = new CachedLayout(width, option, scale, index);
        const auto cost = 1 + int(entry->row.body.characterCount() / 1024);
        layouts_.insert(key, entry, qMin<qsizetype>(cost, layouts_.maxCost()));
        return entry->row;
    }

    MessageTimeline *view_;
    mutable QCache<QString, CachedLayout> layouts_{192};
};

} // namespace

MessageTimeline::MessageTimeline(QWidget *parent) : QListView(parent) {
    installChatPalette(this, ChatSurface::Timeline);
    setItemDelegate(new MessageDelegate(this));
    setEditTriggers(QAbstractItemView::NoEditTriggers);
    setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setFrameShape(QFrame::NoFrame);
    viewport()->setBackgroundRole(QPalette::Window);
}

void MessageTimeline::setModel(QAbstractItemModel *next) {
    auto *delegate = static_cast<MessageDelegate *>(itemDelegate());
    if (auto *current = model()) disconnect(current, nullptr, delegate, nullptr);
    delegate->resetLayouts();
    if (next) {
        connect(next, &QAbstractItemModel::modelAboutToBeReset, delegate, &MessageDelegate::resetLayouts);
        connect(next, &QAbstractItemModel::modelReset, delegate, &MessageDelegate::resetLayouts);
        connect(next, &QAbstractItemModel::layoutChanged, delegate, &MessageDelegate::resetLayouts);
        connect(next, &QAbstractItemModel::rowsAboutToBeRemoved, delegate,
                [next, delegate](const QModelIndex &parent, int first, int last) {
                    delegate->invalidateRows(next->index(first, 0, parent), next->index(last, 0, parent));
                });
        connect(next, &QAbstractItemModel::dataChanged, delegate, &MessageDelegate::invalidateRows);
    }
    QListView::setModel(next);
}

QList<QModelIndex> MessageTimeline::readableRows() const {
    QList<QModelIndex> rows;
    const auto first = indexAt(QPoint(1, 1));
    if (!first.isValid()) return rows;
    QStyleOptionViewItem option;
    initViewItemOption(&option);
    const auto *delegate = static_cast<const MessageDelegate *>(itemDelegate());
    const auto bottom = viewport()->height();
    for (int row = first.row(); row < model()->rowCount() && rows.size() < 100; ++row) {
        const auto index = model()->index(row, 0);
        const auto bounds = visualRect(index);
        if (bounds.top() >= bottom) break;
        if (!index.data(UnreadIncoming).toBool()) continue;
        const auto bubble = delegate->bubbleBounds(option, index, bounds);
        const auto visible = qMin<qreal>(bottom, bubble.bottom()) - qMax<qreal>(0, bubble.top());
        if (bubble.height() > 0 && visible >= qMin<qreal>(48, bubble.height())) rows.append(index);
    }
    return rows;
}

void MessageTimeline::resizeEvent(QResizeEvent *event) {
    const bool atLatest = verticalScrollBar()->value() == verticalScrollBar()->maximum();
    const QPersistentModelIndex anchor(indexAt(QPoint(1, 0)));
    const int previousTop = anchor.isValid() ? visualRect(anchor).top() : 0;
    QListView::resizeEvent(event);
    if (event->oldSize().width() == event->size().width() || !model()) return;
    doItemsLayout();
    if (atLatest) scrollToBottom();
    else if (anchor.isValid()) {
        scrollTo(anchor, QAbstractItemView::PositionAtTop);
        verticalScrollBar()->setValue(verticalScrollBar()->value() + visualRect(anchor).top() - previousTop);
    }
}

} // namespace MsgAT
