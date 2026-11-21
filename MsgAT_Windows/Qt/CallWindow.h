// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QDialog>
#include <QJsonObject>
#include <functional>

class QLabel;
class QPushButton;
class QMenu;
class QTimer;

namespace MsgAT {

class CallWindow final : public QDialog {
public:
    CallWindow();
    void present(const QJsonObject &state);
    void reset();
    std::function<bool(const QByteArray &)> request;
protected:
    void closeEvent(QCloseEvent *event) override;
    void reject() override;
    bool eventFilter(QObject *target, QEvent *event) override;
private:
    bool send(const QString &action, const QJsonObject &fields = {});
    void reportVideoSurface();
    void updatePresentation(bool resizeWindow);
    void setCompact(bool compact);
    void placeBeside(const QString &anchor);
    void updateDevices(const QJsonObject &devices);

    QLabel *initial_, *peer_, *status_, *waiting_, *quality_, *notice_;
    QWidget *video_;
    QPushButton *answer_, *reject_, *end_, *mute_, *camera_, *switchCamera_, *devices_, *compactButton_;
    QMenu *audioMenu_;
    QTimer *videoLayout_;
    QString id_;
    QJsonObject state_, deviceState_;
    bool compact_ = false;
    int layoutMode_ = -1;
};

} // namespace MsgAT
