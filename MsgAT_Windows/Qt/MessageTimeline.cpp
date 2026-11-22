// SPDX-License-Identifier: AGPL-3.0-only
#include "MessageTimeline.h"
#include "ChatPalette.h"
#include "FluentChrome.h"

#include <QAbstractTextDocumentLayout>
#include <QCache>
#include <QFontDatabase>
#include <QHelpEvent>
#include <QJsonArray>
#include <QJsonObject>
#include <QSet>
#include <QTextCursor>
#include <QPainter>
#include <QPainterPath>
#include <QResizeEvent>
#include <QScrollBar>
#include <QStyledItemDelegate>
#include <QTextDocument>
#include <QTimer>
#include <QToolTip>
#include <QtMath>
#include <memory>

namespace MsgAT {
namespace {

QPainterPath bubbleShape(QRectF rect, bool right, bool joinsAbove, bool joinsBelow) {
    const qreal full = qMin(16.0, qMin(rect.width(), rect.height()) / 2.0);
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

void setRichText(QTextDocument &body, const QModelIndex &index, const QPalette &palette) {
    const auto runs = index.data(MessageTimeline::RichRuns).value<QJsonArray>();
    const auto reply = index.data(MessageTimeline::ReplyText).toString();
    if (runs.isEmpty() && reply.isEmpty()) {
        body.setPlainText(index.data(Qt::DisplayRole).toString());
        return;
    }
    QTextCursor cursor(&body);
    if (!reply.isEmpty()) {
        QTextCharFormat quote;
        quote.setForeground(palette.color(QPalette::Accent));
        quote.setFontWeight(QFont::DemiBold);
        cursor.insertText(QStringLiteral("│ ") + reply + QStringLiteral("\n\n"), quote);
    }
    if (runs.isEmpty()) {
        const auto text = index.data(Qt::DisplayRole).toString();
        cursor.insertText(text.mid(reply.size() + 2), QTextCharFormat());
        return;
    }
    static const auto monospace = QFontDatabase::systemFont(QFontDatabase::FixedFont).family();
    for (const auto &value : runs) {
        const auto run = value.toObject();
        const auto style = run.value("style").toInt();
        const auto action = run.value("action").toString();
        QTextCharFormat format;
        if ((style & 1) != 0) format.setFontWeight(QFont::Bold);
        if ((style & 2) != 0) format.setFontItalic(true);
        if ((style & 4) != 0) format.setFontUnderline(true);
        if ((style & 8) != 0) format.setFontStrikeOut(true);
        if ((style & 16) != 0) {
            format.setFontFamilies({monospace});
            format.setBackground(QColor(110, 125, 140, 35));
        }
        if (!action.isEmpty()) {
            format.setAnchor(true);
            format.setAnchorHref(action);
            format.setForeground(palette.color(QPalette::Accent));
            format.setFontUnderline((style & 32) == 0);
        }
        if ((style & 32) != 0) format.setBackground(palette.color(QPalette::Highlight));
        const auto disclosure = run.value("disclosure").toString();
        if (disclosure == QStringLiteral("collapse") || disclosure == QStringLiteral("expand")) {
            QTextCharFormat icon(format);
            icon.setFontFamilies({fluentSymbolFamily()});
            icon.setFontUnderline(false);
            const auto symbol = disclosure == QStringLiteral("collapse") ? Symbol::ChevronUp : Symbol::Options;
            cursor.insertText(QString(QChar(static_cast<ushort>(symbol))), icon);
            cursor.insertText(QStringLiteral("\u00a0 "), format);
        }
        cursor.insertText(run.value("text").toString(), format);
    }
}

struct MediaTile {
    QRectF bounds;
    QString id;
    QString label;
    bool available, sticker, video;
};

QSize inlineMediaSize(const QSize &source, bool video, bool sticker) {
    if (sticker) {
        if (source.isEmpty()) return {132, 132};
        const auto scale = qMin(132.0 / qMax(source.width(), source.height()),
                                qMax(1.0, 30.0 / qMin(source.width(), source.height())));
        return {qMax(1, qRound(source.width() * scale)), qMax(1, qRound(source.height() * scale))};
    }
    if (source.isEmpty()) return {224, video ? 126 : 168};
    const auto aspect = qBound(0.5, double(source.width()) / source.height(), 2.5);
    const auto longEdge = qBound(96, qMax(source.width(), source.height()), 224);
    return aspect >= 1 ? QSize(longEdge, qRound(longEdge / aspect))
                       : QSize(qRound(longEdge * aspect), longEdge);
}

void paintReceipt(QPainter &painter, const QRectF &rect, int status, bool overMedia, const QPalette &palette) {
    const auto center = rect.center();
    const auto neutral = overMedia ? QColor(205, 212, 216) : palette.color(QPalette::PlaceholderText);
    const auto delivered = overMedia ? QColor(121, 223, 188) : palette.color(QPalette::Accent);
    const auto failed = overMedia || palette.color(QPalette::Window).lightness() < 128 ? QColor(255, 158, 163) : QColor(174, 39, 56);
    painter.setPen(QPen(status == -1 ? failed : status >= 2 ? delivered : neutral,
                        1.35, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    painter.setBrush(Qt::NoBrush);
    if (status <= 0) {
        painter.drawEllipse(center, 4.9, 4.9);
        if (status == -1) {
            painter.drawLine(center + QPointF(0, -2.5), center + QPointF(0, 0.6));
            painter.drawPoint(center + QPointF(0, 2.4));
        } else {
            QPainterPath hand(center + QPointF(0, -2.6));
            hand.lineTo(center);
            hand.lineTo(center + QPointF(2.2, 1.1));
            painter.drawPath(hand);
        }
        return;
    }
    auto check = [&](int offset) {
        QPainterPath stroke(center + QPointF(-3.6 + offset, 0));
        stroke.lineTo(center + QPointF(-1.0 + offset, 2.6));
        stroke.lineTo(center + QPointF(4.2 + offset, -2.8));
        painter.drawPath(stroke);
    };
    check(status == 3 ? -2 : 0);
    if (status == 3) check(2);
}

struct MessageLayout {
    QTextDocument body;
    std::unique_ptr<QTextDocument> preview;
    QFont smallFont;
    QRectF bubble, bounds, text, previewRect, footer, footerPill, receipt;
    QList<MediaTile> media;
    int moreMedia = 0;
    QStringList labels;
    int labelHeight = 0, height = 0;
    bool right, revoked, attachmentCard = false;

    MessageLayout(int width, const QStyleOptionViewItem &option, const QModelIndex &index, const MessageTimeline &view)
        : smallFont(option.font),
          right(index.data(MessageTimeline::Outgoing).toBool() != (option.direction == Qt::RightToLeft)),
          revoked(index.data(MessageTimeline::Revoked).toBool()) {
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
        const int available = qMax(1, width - 2 * margin);

        body.setDocumentMargin(0);
        body.setDefaultFont(revoked ? smallFont : option.font);
        auto wrapping = body.defaultTextOption();
        wrapping.setWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
        if (revoked) wrapping.setAlignment(Qt::AlignHCenter);
        body.setDefaultTextOption(wrapping);
        setRichText(body, index, option.palette);
        if (revoked) {
            body.setTextWidth(available);
            text = QRectF(margin, top + 2, available, qCeil(body.size().height()));
            height = qCeil(text.bottom()) + 8;
            return;
        }

        const int padding = 11;
        const int maxBubble = qMin(available, qMax(112, qMin(540, qRound(available * 0.81))));
        const int maxText = qMax(1, maxBubble - 2 * padding);
        body.setTextWidth(maxText);
        const int textWidth = qBound(1, qCeil(body.idealWidth()), maxText);
        body.setTextWidth(textWidth);
        const int textHeight = qCeil(body.size().height());
        const auto previewText = index.data(MessageTimeline::LinkPreviewText).toString();
        int previewWidth = 0;
        if (!previewText.isEmpty()) {
            preview = std::make_unique<QTextDocument>();
            preview->setDocumentMargin(0);
            preview->setDefaultFont(smallFont);
            preview->setDefaultTextOption(wrapping);
            preview->setPlainText(previewText);
            preview->setTextWidth(maxText);
            previewWidth = qBound(1, qCeil(preview->idealWidth()), maxText);
        }
        const auto images = index.data(MessageTimeline::InlineMedia).value<QJsonArray>();
        const auto single = images.size() == 1 ? images[0].toObject() : QJsonObject{};
        const bool singleSticker = single.value("sticker").toBool();
        const int columns = images.size() > 1 ? 2 : 1;
        int mediaWidth = 0;
        QSize tile;
        if (columns == 2) {
            mediaWidth = qMin(maxText, 348);
            const auto tileWidth = qMax(1, (mediaWidth - 4) / columns);
            tile = {tileWidth, qRound(tileWidth * 0.77)};
        } else if (!images.isEmpty()) {
            const auto preferred = inlineMediaSize(view.mediaSize(single.value("id").toString()),
                                                   single.value("video").toBool(), singleSticker);
            tile = preferred.scaled(QSize(maxText, preferred.height()), Qt::KeepAspectRatio).expandedTo({1, 1});
            mediaWidth = tile.width();
        }
        const int tileWidth = tile.width();
        const int tileHeight = tile.height();
        const int mediaRows = (int(images.size()) + columns - 1) / columns;
        const int mediaHeight = mediaRows ? mediaRows * tileHeight + (mediaRows - 1) * 4 : 0;
        const bool hasText = !body.isEmpty();
        const int bodyHeight = hasText ? textHeight : 0;
        const auto meta = index.data(MessageTimeline::Metadata).toString();
        const auto status = index.data(MessageTimeline::ReceiptStatus);
        const int markWidth = status.isValid() && status.toInt() >= -1 && status.toInt() <= 3 ? 20 : 0;
        const int metaWidth = qMin(maxText, (meta.isEmpty() ? 0 :
            qCeil(QFontMetricsF(smallFont).horizontalAdvance(meta)) + 2) + markWidth);
        attachmentCard = images.isEmpty() && index.data(MessageTimeline::HasAttachment).toBool();
        const bool footerInside = images.isEmpty() && !attachmentCard;
        const bool footerOverMedia = !hasText && !preview && !singleSticker && !single.isEmpty() &&
            index.data(MessageTimeline::MediaFooter).toBool() && mediaWidth >= metaWidth + 18 && tileHeight >= small.height() + 24;
        const bool inlineFooter = footerInside && !preview && metaWidth > 0 && body.lineCount() == 1 &&
                                  textWidth + 9 + metaWidth <= maxText;
        const int inner = inlineFooter ? textWidth + 9 + metaWidth :
            qMax(hasText ? textWidth : 0, qMax(footerInside ? metaWidth : 0, previewWidth));
        if (preview) preview->setTextWidth(inner);
        const int previewHeight = preview ? qCeil(preview->size().height()) : 0;
        const int previewMargin = preview && hasText ? 7 : 0;
        const bool previewAbove = index.data(MessageTimeline::LinkPreviewAbove).toBool();
        const int mediaMargin = mediaHeight && (hasText || preview) ? 5 : 0;
        const int footerHeight = metaWidth > 0 && !inlineFooter && footerInside ? small.height() + 3 : 0;
        const int w = qMin(available, inner + 2 * padding);
        const int bubbleTop = top + mediaHeight + mediaMargin;
        const int bubbleHeight = (inlineFooter ? qMax(textHeight, small.height()) : bodyHeight) +
            previewHeight + previewMargin + footerHeight + 14;
        if (hasText || preview || images.isEmpty()) bubble = QRectF(right ? width - margin - w : margin, bubbleTop, w, bubbleHeight);
        const int left = int(bubble.left()) + padding;
        const int contentY = bubbleTop + 7;
        const bool rtl = option.direction == Qt::RightToLeft;
        text = QRectF(inlineFooter && rtl ? left + metaWidth + 9 : left,
                      contentY + (previewAbove ? previewHeight + previewMargin : 0),
                      inlineFooter ? textWidth : inner, bodyHeight);
        if (preview) previewRect = QRectF(left, contentY + (previewAbove ? 0 : bodyHeight + previewMargin), inner, previewHeight);
        if (!inlineFooter) body.setTextWidth(inner);
        const int mediaLeft = right ? width - margin - mediaWidth : margin;
        for (int i = 0; i < images.size(); ++i) {
            const auto image = images[i].toObject();
            const int column = rtl ? columns - 1 - i % columns : i % columns;
            media.append({QRectF(mediaLeft + column * (tileWidth + 4),
                                 top + (i / columns) * (tileHeight + 4), tileWidth, tileHeight),
                          image.value("id").toString(), image.value("label").toString(),
                          image.value("available").toBool(), image.value("sticker").toBool(), image.value("video").toBool()});
        }
        bounds = bubble;
        if (mediaHeight > 0) bounds = bounds.united(QRectF(mediaLeft, top, mediaWidth, mediaHeight));
        if (metaWidth > 0) {
            const int inset = 10;
            const int x = footerInside ? (rtl ? left : left + inner - metaWidth) : footerOverMedia ?
                (rtl ? mediaLeft + inset : mediaLeft + mediaWidth - metaWidth - inset) :
                (right ? width - margin - metaWidth : margin);
            const int y = footerInside ? int(bubble.bottom()) - 7 - small.height() : footerOverMedia ?
                top + tileHeight - small.height() - 8 : qCeil(bounds.bottom()) + 3;
            const QRectF area(x, y, metaWidth, small.height());
            const int reserved = qMin(markWidth, metaWidth);
            footer = area.adjusted(rtl ? reserved : 0, 0, rtl ? 0 : -reserved, 0);
            if (reserved > 0) receipt = QRectF(rtl ? x : area.right() - reserved, y, reserved, small.height());
            if (footerOverMedia) footerPill = area.adjusted(-5, -2, 5, 2);
            bounds = bounds.united(area);
        }
        moreMedia = qMax(0, index.data(MessageTimeline::InlineMediaCount).toInt() - int(media.size()));
        height = qCeil(bounds.bottom()) + (index.data(MessageTimeline::ContinuesInNext).toBool() ? 1 : 6);
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

    QRectF messageBounds(const QStyleOptionViewItem &option, const QModelIndex &index, const QRect &row) const {
        return layout(row.width(), option, index).bounds.translated(row.topLeft());
    }

    bool receiptContains(const QStyleOptionViewItem &option, const QModelIndex &index, const QRect &row, QPoint point) const {
        return layout(row.width(), option, index).receipt.contains(point - row.topLeft());
    }

    bool helpEvent(QHelpEvent *event, QAbstractItemView *view, const QStyleOptionViewItem &option,
                   const QModelIndex &index) override {
        const auto label = index.data(MessageTimeline::ReceiptLabel).toString();
        if (event && view && !label.isEmpty() && event->type() == QEvent::ToolTip &&
            receiptContains(option, index, option.rect, event->pos())) {
            QToolTip::showText(event->globalPos(), label, view);
            return true;
        }
        return QStyledItemDelegate::helpEvent(event, view, option, index);
    }

    bool previewContains(const QStyleOptionViewItem &option, const QModelIndex &index, const QRect &row, QPoint point) const {
        return layout(row.width(), option, index).previewRect.contains(point - row.topLeft());
    }

    QString actionAt(const QStyleOptionViewItem &option, const QModelIndex &index, const QRect &row, QPoint point) const {
        const auto &layout = this->layout(row.width(), option, index);
        const auto local = point - row.topLeft();
        if (!layout.text.contains(local)) return {};
        return layout.body.documentLayout()->anchorAt(local - layout.text.topLeft());
    }

    QString mediaAt(const QStyleOptionViewItem &option, const QModelIndex &index, const QRect &row, QPoint point) const {
        const auto local = point - row.topLeft();
        for (const auto &tile : layout(row.width(), option, index).media)
            if (tile.bounds.contains(local)) return tile.id;
        return {};
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
        const auto ink = row.revoked ? colors.color(QPalette::WindowText) :
                         colors.color(selected ? QPalette::HighlightedText : QPalette::Text);
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
        const bool outlined = index.data(MessageTimeline::SearchHighlight).toBool() ||
            (option.state.testFlag(QStyle::State_HasFocus) && option.state.testFlag(QStyle::State_KeyboardFocusChange));
        if (!row.revoked && !row.bubble.isEmpty()) {
            const auto path = bubbleShape(row.bubble, row.right,
                index.data(MessageTimeline::ContinuesFromPrevious).toBool(),
                index.data(MessageTimeline::ContinuesInNext).toBool());
            p->fillPath(path, colors.brush(selected ? QPalette::Highlight : row.attachmentCard ? QPalette::Button :
                                          outgoing ? QPalette::AlternateBase : QPalette::Base));
            if (index.data(MessageTimeline::SearchHighlight).toBool())
                p->strokePath(path, QPen(colors.color(QPalette::Accent), 2));
            if (option.state.testFlag(QStyle::State_HasFocus) &&
                option.state.testFlag(QStyle::State_KeyboardFocusChange))
                p->strokePath(path, QPen(ink, 1));
        }
        p->setRenderHint(QPainter::SmoothPixmapTransform);
        for (int i = 0; i < row.media.size(); ++i) {
            const auto &tile = row.media[i];
            const auto photo = view_->imageFor(tile.id);
            QPainterPath clip;
            clip.addRoundedRect(tile.bounds, 9, 9);
            p->save();
            p->setClipPath(clip, Qt::IntersectClip);
            if (!tile.sticker || photo.isNull()) p->fillPath(clip, colors.brush(QPalette::Button));
            if (!photo.isNull()) {
                const auto aspect = double(photo.width()) / photo.height();
                const bool crop = row.media.size() > 1 || (!tile.sticker && (aspect < 0.5 || aspect > 2.5));
                const auto sizing = crop ? Qt::KeepAspectRatioByExpanding : Qt::KeepAspectRatio;
                const auto base = photo.size().scaled(tile.bounds.size().toSize(), sizing);
                QRectF target(QPointF(), base);
                target.moveCenter(tile.bounds.center());
                p->drawImage(target, photo);
            } else {
                const auto status = index.data(MessageTimeline::InlineMediaStatus).toString();
                const auto label = status.isEmpty() || tile.available ? tile.label : status;
                QRect icon(0, 0, 24, 24);
                icon.moveCenter((tile.bounds.center() - QPointF(0, 9)).toPoint());
                fluentIcon(tile.sticker ? Symbol::Sticker : tile.video ? Symbol::Video : Symbol::Image).paint(p, icon);
                p->setFont(row.smallFont);
                p->setPen(colors.color(QPalette::ButtonText));
                p->drawText(tile.bounds.adjusted(8, tile.bounds.height() / 2 + 8, -8, -6), Qt::AlignHCenter | Qt::AlignTop,
                    QFontMetrics(row.smallFont).elidedText(label, Qt::ElideMiddle, int(tile.bounds.width()) - 16));
            }
            if (tile.video && !photo.isNull()) {
                p->setPen(Qt::NoPen);
                p->setBrush(QColor(15, 20, 25, 180));
                p->drawEllipse(tile.bounds.center(), 17, 17);
                QPainterPath triangle;
                const auto center = tile.bounds.center();
                triangle.moveTo(center + QPointF(-4, -7));
                triangle.lineTo(center + QPointF(8, 0));
                triangle.lineTo(center + QPointF(-4, 7));
                triangle.closeSubpath();
                p->fillPath(triangle, Qt::white);
            }
            if (row.moreMedia && i + 1 == row.media.size()) {
                p->fillRect(tile.bounds, QColor(12, 18, 24, 160));
                p->setFont(option.font);
                p->setPen(Qt::white);
                p->drawText(tile.bounds, Qt::AlignCenter, QStringLiteral("+%1").arg(row.moreMedia));
            }
            p->restore();
            if (outlined || selected) p->strokePath(clip, QPen(colors.color(QPalette::Accent), 2));
        }
        p->save();
        p->translate(row.text.topLeft());
        QAbstractTextDocumentLayout::PaintContext context;
        context.palette = colors;
        context.palette.setColor(QPalette::Text, ink);
        row.body.documentLayout()->draw(p, context);
        p->restore();
        if (row.preview) {
            p->save();
            p->translate(row.previewRect.topLeft());
            context.palette.setColor(QPalette::Text, selected ? ink : colors.color(QPalette::Accent));
            row.preview->documentLayout()->draw(p, context);
            p->restore();
        }
        if (!row.revoked) {
            const bool overMedia = !row.footerPill.isEmpty();
            if (overMedia) {
                p->setPen(Qt::NoPen);
                p->setBrush(QColor(0, 0, 0, 184));
                p->drawRoundedRect(row.footerPill, 8, 8);
            }
            p->setFont(row.smallFont);
            p->setPen(overMedia ? QColor(Qt::white) : colors.color(QPalette::PlaceholderText));
            if (!row.footer.isEmpty()) {
                const auto footer = QFontMetricsF(row.smallFont).elidedText(index.data(MessageTimeline::Metadata).toString(),
                                                                            Qt::ElideRight, row.footer.width());
                p->drawText(row.footer, Qt::AlignCenter, footer);
            }
            if (!row.receipt.isEmpty()) paintReceipt(*p, row.receipt, index.data(MessageTimeline::ReceiptStatus).toInt(), overMedia, colors);
        }
        p->restore();
    }

private:
    struct CachedLayout {
        int width;
        QFont font;
        Qt::LayoutDirection direction;
        qreal scale;
        qint64 paletteKey;
        MessageLayout row;

        CachedLayout(int width, const QStyleOptionViewItem &option, qreal scale, const QModelIndex &index, const MessageTimeline &view)
            : width(width), font(option.font), direction(option.direction), scale(scale), paletteKey(option.palette.cacheKey()), row(width, option, index, view) {}
    };

    const MessageLayout &layout(int width, const QStyleOptionViewItem &option, const QModelIndex &index) const {
        const auto scale = view_->devicePixelRatioF();
        const auto key = index.data(MessageTimeline::MessageId).toString();
        if (const auto *cached = layouts_.object(key); cached && cached->width == width &&
            cached->font == option.font && cached->direction == option.direction && cached->scale == scale && cached->paletteKey == option.palette.cacheKey())
            return cached->row;
        auto *entry = new CachedLayout(width, option, scale, index, *view_);
        const auto previewLength = entry->row.preview ? entry->row.preview->characterCount() : 0;
        const auto cost = 1 + int((entry->row.body.characterCount() + previewLength) / 1024);
        layouts_.insert(key, entry, qMin<qsizetype>(cost, layouts_.maxCost()));
        return entry->row;
    }

    MessageTimeline *view_;
    mutable QCache<QString, CachedLayout> layouts_{192};
};

} // namespace

MessageTimeline::MessageTimeline(QWidget *parent) : QListView(parent) {
    setObjectName(QStringLiteral("messageTimeline"));
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

bool MessageTimeline::previewContains(const QModelIndex &index, QPoint point) const {
    if (!index.isValid()) return false;
    QStyleOptionViewItem option;
    initViewItemOption(&option);
    const auto *delegate = static_cast<const MessageDelegate *>(itemDelegate());
    return delegate->previewContains(option, index, visualRect(index), point);
}

QString MessageTimeline::richAction(const QModelIndex &index, QPoint point) const {
    if (!index.isValid()) return {};
    QStyleOptionViewItem option;
    initViewItemOption(&option);
    return static_cast<const MessageDelegate *>(itemDelegate())->actionAt(option, index, visualRect(index), point);
}

QString MessageTimeline::mediaTarget(const QModelIndex &index, QPoint point) const {
    if (!index.isValid()) return {};
    QStyleOptionViewItem option;
    initViewItemOption(&option);
    return static_cast<const MessageDelegate *>(itemDelegate())->mediaAt(option, index, visualRect(index), point);
}

bool MessageTimeline::retryReceiptContains(const QModelIndex &index, QPoint point) const {
    if (!index.isValid() || !index.data(CanRetry).toBool()) return false;
    QStyleOptionViewItem option;
    initViewItemOption(&option);
    const auto *delegate = static_cast<const MessageDelegate *>(itemDelegate());
    return delegate->receiptContains(option, index, visualRect(index), point);
}

QStringList MessageTimeline::visibleMedia() const {
    QStringList ids;
    const auto first = indexAt(QPoint(1, 1));
    if (!first.isValid()) return ids;
    for (int row = first.row(); row < model()->rowCount() && ids.size() < 16; ++row) {
        const auto index = model()->index(row, 0);
        if (visualRect(index).top() >= viewport()->height()) break;
        for (const auto &value : index.data(InlineMedia).value<QJsonArray>()) {
            const auto item = value.toObject();
            const auto id = item.value("id").toString();
            if (item.value("available").toBool() && !id.isEmpty()) ids.append(id);
            if (ids.size() >= 16) break;
        }
    }
    return ids;
}

void MessageTimeline::retainMedia(const QStringList &ids) {
    const QSet<QString> visible(ids.begin(), ids.end());
    for (auto it = inlineImages_.begin(); it != inlineImages_.end();) {
        if (visible.contains(it.key())) ++it;
        else {
            it.value().fill(0);
            it = inlineImages_.erase(it);
        }
    }
}

void MessageTimeline::showMedia(const QString &id, const QImage &image) {
    auto existing = inlineImages_.find(id);
    if (existing != inlineImages_.end()) existing.value().fill(0);
    inlineImages_.insert(id, image);
    viewport()->update();

    const auto previous = mediaSize(id);
    if (!model() || image.isNull() || previous == image.size()) return;
    inlineImageSizes_.insert(id, new QSize(image.size()));
    const auto first = indexAt(QPoint(1, 1));
    if (!first.isValid()) return;
    for (int row = first.row(); row < model()->rowCount(); ++row) {
        const auto index = model()->index(row, 0);
        if (visualRect(index).top() >= viewport()->height()) break;
        const auto items = index.data(InlineMedia).value<QJsonArray>();
        if (items.size() != 1) continue;
        const auto item = items[0].toObject();
        if (item.value("id").toString() != id) continue;
        const auto video = item.value("video").toBool();
        const auto sticker = item.value("sticker").toBool();
        if (inlineMediaSize(previous, video, sticker) == inlineMediaSize(image.size(), video, sticker)) break;
        static_cast<MessageDelegate *>(itemDelegate())->invalidateRows(index, index);
        if (!mediaRelayoutPending_) {
            mediaRelayoutPending_ = true;
            QTimer::singleShot(0, this, [this] {
                mediaRelayoutPending_ = false;
                const bool atLatest = verticalScrollBar()->value() == verticalScrollBar()->maximum();
                const QPersistentModelIndex anchor(indexAt(QPoint(1, 1)));
                const int top = anchor.isValid() ? visualRect(anchor).top() : 0;
                doItemsLayout();
                if (atLatest) scrollToBottom();
                else if (anchor.isValid()) {
                    scrollTo(anchor, QAbstractItemView::PositionAtTop);
                    verticalScrollBar()->setValue(verticalScrollBar()->value() + visualRect(anchor).top() - top);
                }
            });
        }
        break;
    }
}

QImage MessageTimeline::imageFor(const QString &id) const { return inlineImages_.value(id); }
QSize MessageTimeline::mediaSize(const QString &id) const {
    const auto *size = inlineImageSizes_.object(id);
    return size ? *size : QSize{};
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
        const auto bubble = delegate->messageBounds(option, index, bounds);
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
