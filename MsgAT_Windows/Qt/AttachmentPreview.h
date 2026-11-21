// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QDialog>
#include <QJsonObject>
#include <functional>

class QFileDialog;
class QLabel;
class QPushButton;
class QTimer;

namespace MsgAT {
class AttachmentImage;
class PlaybackSurface;

class AttachmentPreview final : public QDialog {
public:
    explicit AttachmentPreview(QWidget *parent);
    void showFor(const QString &peer, const QString &message);
    void present(const QJsonObject &state);
    void presentImage(qint64 generation, const uchar *pixels, int width, int height, int stride);
    void reset();
    std::function<bool(int, const QByteArray &)> request;
private:
    bool send(int action, const QJsonObject &fields = {});
    void reportPlaybackSurface(bool focus);
    AttachmentImage *image_;
    PlaybackSurface *playback_;
    QTimer *playbackLayout_;
    QLabel *name_, *info_, *status_;
    QPushButton *previous_, *next_, *download_, *save_, *close_;
    QFileDialog *picker_;
    QString peer_, message_, filename_;
    qint64 generation_ = 0;
};

} // namespace MsgAT
