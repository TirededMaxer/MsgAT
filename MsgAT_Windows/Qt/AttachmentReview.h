// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QDialog>
#include <QJsonObject>
#include <functional>

class QCheckBox;
class QDateTimeEdit;
class QFileDialog;
class QLabel;
class QListWidget;
class QPushButton;

namespace MsgAT {
class ChatComposer;

class AttachmentReview final : public QDialog {
public:
    explicit AttachmentReview(QWidget *parent);
    void showFor(const QString &peer, const QJsonObject &options);
    void present(const QJsonObject &state);
    void reset();
    std::function<bool(int, const QByteArray &)> request;
    std::function<bool(const QString &)> copyCaption;
protected:
    void reject() override;
private:
    void addFiles();
    void moveFile(int delta);
    void updateOptions();
    void updateCaption();
    bool send(int action, const QJsonObject &fields = {});

    QListWidget *files_;
    ChatComposer *caption_;
    QFileDialog *picker_;
    QLabel *recipient_, *status_;
    QPushButton *add_, *up_, *down_, *edit_, *remove_, *submit_, *cancel_;
    QCheckBox *asFile_, *album_, *photos_, *videos_, *silent_, *schedule_;
    QDateTimeEdit *sendAt_;
    QJsonObject labels_;
    QString peer_;
    qint64 generation_ = 0;
    bool busy_ = false, canCancel_ = false, editing_ = false;
};

} // namespace MsgAT
