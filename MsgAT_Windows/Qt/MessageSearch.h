// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QDialog>
#include <QJsonObject>
#include <functional>

class QCheckBox;
class QComboBox;
class QDateEdit;
class QLabel;
class QLineEdit;
class QListWidget;
class QPushButton;
class QTimer;

namespace MsgAT {

class MessageSearch final : public QDialog {
public:
    explicit MessageSearch(QWidget *parent);
    void showFor(const QString &peer, const QJsonObject &labels);
    void present(const QJsonObject &state);
    void reset();
    std::function<bool(int, const QByteArray &)> request;
protected:
    bool eventFilter(QObject *object, QEvent *event) override;
private:
    void changeInput();
    void send(int action);
    void updateButtons();
    QLineEdit *query_;
    QCheckBox *all_, *dateEnabled_;
    QComboBox *kind_;
    QDateEdit *date_;
    QListWidget *results_;
    QLabel *status_;
    QPushButton *more_, *retry_, *jump_, *close_;
    QTimer *debounce_;
    QString peer_;
    qint64 generation_ = 0;
    bool configuring_ = false, opening_ = false, composing_ = false, busy_ = false, next_ = false;
    int retryAction_ = 11;
};

} // namespace MsgAT
