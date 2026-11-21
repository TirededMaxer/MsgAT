// SPDX-License-Identifier: AGPL-3.0-only
#include "ChatComposer.h"
#include <QContextMenuEvent>
#include <QGuiApplication>
#include <QInputMethod>
#include <QInputMethodEvent>
#include <QKeyEvent>
#include <QMenu>
#include <QMimeData>
#include <QScopedValueRollback>
#include <QTextBlock>
#include <QtMath>

namespace MsgAT {

ChatComposer::ChatComposer(QWidget *parent) : QPlainTextEdit(parent) {
    setFrameShape(QFrame::NoFrame);
    setTabChangesFocus(true);
    setAcceptDrops(false);
    setWordWrapMode(QTextOption::WrapAtWordBoundaryOrAnywhere);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    connect(this, &QPlainTextEdit::textChanged, this, [this] {
        updateHeight();
        if (!updating_ && !isReadOnly()) emit draftChanged(toPlainText());
    });
}

void ChatComposer::reset() {
    QScopedValueRollback<bool> guard(updating_, true);
    if (hasFocus()) QGuiApplication::inputMethod()->reset();
    composing_ = false;
    allowsEmpty = false;
    setReadOnly(true);
    clear();
    document()->clearUndoRedoStacks();
}

void ChatComposer::present(const QJsonObject &state, bool changedPeer) {
    QScopedValueRollback<bool> guard(updating_, true);
    if (changedPeer) reset();
    allowsEmpty = state.value("allowsEmpty").toBool();
    labels_ = state.value("editorLabels").toObject();
    setReadOnly(!state.value("canCompose").toBool());
    const auto placeholder = labels_.value("placeholder").toString();
    setAccessibleName(placeholder);
    setPlaceholderText(placeholder);
    const auto draft = state.value("draft").toString();
    if (toPlainText() != draft) {
        setPlainText(draft);
        moveCursor(QTextCursor::End);
    }
    updateHeight();
}

void ChatComposer::updateHeight() {
    const int lineHeight = fontMetrics().lineSpacing();
    int lines = 0;
    for (auto block = document()->begin(); block.isValid() && lines < 5; block = block.next())
        lines += qMax(1, block.lineCount());
    const int h = qMax(36, qBound(1, lines, 5) * lineHeight + qCeil(2 * document()->documentMargin()) + 4);
    if (minimumHeight() != h || maximumHeight() != h) setFixedHeight(h);
}

void ChatComposer::resizeEvent(QResizeEvent *event) {
    QPlainTextEdit::resizeEvent(event);
    updateHeight();
}

void ChatComposer::inputMethodEvent(QInputMethodEvent *event) {
    composing_ = !event->preeditString().isEmpty();
    QPlainTextEdit::inputMethodEvent(event);
}

void ChatComposer::keyPressEvent(QKeyEvent *event) {
    if (event->matches(QKeySequence::Copy) || event->matches(QKeySequence::Cut)) {
        copyOrCut(event->matches(QKeySequence::Cut));
        event->accept();
        return;
    }
    if (sendOnEnter && !isReadOnly() && !composing_ &&
        (event->key() == Qt::Key_Return || event->key() == Qt::Key_Enter) &&
        (event->modifiers() & ~Qt::KeypadModifier) == Qt::NoModifier) {
        if (!event->isAutoRepeat() && (allowsEmpty || !toPlainText().trimmed().isEmpty())) emit sendRequested();
        event->accept();
        return;
    }
    QPlainTextEdit::keyPressEvent(event);
}

QMimeData *ChatComposer::createMimeDataFromSelection() const {
    // Qt's standard selection drag also calls this outside its Copy action.
    return new QMimeData;
}

void ChatComposer::copyOrCut(bool cut) {
    auto selection = textCursor();
    if (!selection.hasSelection() || (cut && isReadOnly()) || !copySelection) return;
    auto value = selection.selectedText();
    value.replace(QChar::ParagraphSeparator, QChar::LineFeed);
    if (copySelection(value) && cut) selection.removeSelectedText();
}

void ChatComposer::contextMenuEvent(QContextMenuEvent *event) {
    QMenu menu(this);
    auto add = [&](const char *key, bool enabled, auto action) {
        auto *item = menu.addAction(labels_.value(key).toString());
        item->setEnabled(enabled);
        connect(item, &QAction::triggered, this, action);
    };
    const bool writable = !isReadOnly();
    const bool selected = textCursor().hasSelection();
    add("undo", writable && document()->isUndoAvailable(), [this] { undo(); });
    add("redo", writable && document()->isRedoAvailable(), [this] { redo(); });
    menu.addSeparator();
    add("cut", writable && selected, [this] { copyOrCut(true); });
    add("copy", selected, [this] { copyOrCut(false); });
    add("paste", writable && canPaste(), [this] { if (!isReadOnly()) paste(); });
    add("all", !document()->isEmpty(), [this] { selectAll(); });
    menu.exec(event->globalPos());
}
} // namespace MsgAT
