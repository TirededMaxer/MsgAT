// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QJsonObject>
#include <QPlainTextEdit>
#include <functional>

namespace MsgAT {
class ChatComposer final : public QPlainTextEdit {
    Q_OBJECT
public:
    explicit ChatComposer(QWidget *parent);
    void present(const QJsonObject &state, bool changedPeer);
    void reset();
    bool acceptsSubmit() const { return !isReadOnly() && !composing_; }
    bool sendOnEnter = true;
    bool allowsEmpty = false;
    std::function<bool(const QString &)> copySelection;
    std::function<bool(const QMimeData &)> pasteMedia;
signals:
    void sendRequested();
    void draftChanged(const QString &text);
protected:
    void keyPressEvent(QKeyEvent *event) override;
    void inputMethodEvent(QInputMethodEvent *event) override;
    void contextMenuEvent(QContextMenuEvent *event) override;
    void resizeEvent(QResizeEvent *event) override;
    QMimeData *createMimeDataFromSelection() const override;
    bool canInsertFromMimeData(const QMimeData *source) const override;
    void insertFromMimeData(const QMimeData *source) override;
private:
    void copyOrCut(bool cut);
    void updateHeight();
    QJsonObject labels_;
    bool composing_ = false, updating_ = false;
};
} // namespace MsgAT
