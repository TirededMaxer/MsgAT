// SPDX-License-Identifier: AGPL-3.0-only
#include "AttachmentPreview.h"
#include "ChatPalette.h"
#include "FluentChrome.h"
#include "WindowPrivacy.h"

#include <QBoxLayout>
#include <QEvent>
#include <QFileDialog>
#include <QImage>
#include <QJsonDocument>
#include <QLabel>
#include <QPainter>
#include <QPushButton>
#include <QTimer>
#include <utility>

namespace MsgAT {

class AttachmentImage final : public QWidget {
public:
    explicit AttachmentImage(QWidget *parent) : QWidget(parent) {}
    void showImage(QImage image) {
        clear();
        image_ = std::move(image);
        setVisible(!image_.isNull());
        update();
    }
    void clear() {
        if (!image_.isNull()) image_.fill(0);
        image_ = {};
        hide();
    }
protected:
    void paintEvent(QPaintEvent *) override {
        if (image_.isNull()) return;
        const auto target = image_.size().scaled(size(), Qt::KeepAspectRatio);
        const QRect bounds((width() - target.width()) / 2, (height() - target.height()) / 2, target.width(), target.height());
        QPainter painter(this);
        painter.setRenderHint(QPainter::SmoothPixmapTransform);
        painter.drawImage(bounds, image_);
    }
private:
    QImage image_;
};

class PlaybackSurface final : public QWidget {
public:
    explicit PlaybackSurface(QWidget *parent) : QWidget(parent) {
        setAttribute(Qt::WA_DontCreateNativeAncestors);
        setFocusPolicy(Qt::StrongFocus);
        setMinimumHeight(96);
        setStyleSheet(QStringLiteral("background: #000"));
    }
    std::function<void()> geometryChanged;
    std::function<void()> keyboardFocused;
protected:
    bool event(QEvent *event) override {
        const bool handled = QWidget::event(event);
        if ((event->type() == QEvent::WinIdChange || event->type() == QEvent::DevicePixelRatioChange) && geometryChanged)
            geometryChanged();
        return handled;
    }
    void resizeEvent(QResizeEvent *event) override {
        QWidget::resizeEvent(event);
        if (geometryChanged) geometryChanged();
    }
    void focusInEvent(QFocusEvent *event) override {
        QWidget::focusInEvent(event);
        if (keyboardFocused) keyboardFocused();
    }
};

AttachmentPreview::AttachmentPreview(QWidget *parent) : QDialog(parent) {
    installChatPalette(this, ChatSurface::Timeline);
    setWindowModality(Qt::WindowModal);
    resize(760, 560);
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(18, 18, 18, 16);
    layout->setSpacing(9);
    auto addLabel = [&](int stretch = 0) {
        auto *label = new QLabel(this);
        label->setTextFormat(Qt::PlainText);
        label->setWordWrap(true);
        layout->addWidget(label, stretch);
        return label;
    };
    name_ = addLabel();
    auto font = name_->font();
    font.setWeight(QFont::DemiBold);
    name_->setFont(font);
    info_ = addLabel();
    image_ = new AttachmentImage(this);
    layout->addWidget(image_, 1);
    playback_ = new PlaybackSurface(this);
    playback_->hide();
    layout->addWidget(playback_, 1);
    playbackLayout_ = new QTimer(this);
    playbackLayout_->setSingleShot(true);
    connect(playbackLayout_, &QTimer::timeout, this, [this] { reportPlaybackSurface(false); });
    playback_->geometryChanged = [this] {
        if (playback_->isVisible() && !playbackLayout_->isActive()) playbackLayout_->start();
    };
    playback_->keyboardFocused = [this] {
        QTimer::singleShot(0, this, [this] {
            if (playback_->hasFocus()) reportPlaybackSurface(true);
        });
    };
    status_ = addLabel();
    layout->addStretch();
    auto *actions = new QHBoxLayout;
    previous_ = new QPushButton(this);
    next_ = new QPushButton(this);
    download_ = new QPushButton(this);
    save_ = new QPushButton(this);
    close_ = new QPushButton(this);
    setFluentAction(previous_, Symbol::Previous);
    setFluentAction(next_, Symbol::Next);
    setFluentAction(download_, Symbol::Download);
    setFluentAction(save_, Symbol::Save);
    setFluentAction(close_, Symbol::Close);
    actions->addWidget(previous_);
    actions->addWidget(next_);
    actions->addWidget(download_);
    actions->addWidget(save_);
    actions->addStretch();
    actions->addWidget(close_);
    layout->addLayout(actions);
    for (auto *button : {previous_, next_, download_, save_, close_}) button->setAutoDefault(false);
    connect(previous_, &QPushButton::clicked, this, [this] { send(26); });
    connect(next_, &QPushButton::clicked, this, [this] { send(27); });
    picker_ = new QFileDialog(this);
    picker_->setAcceptMode(QFileDialog::AcceptSave);
    connect(download_, &QPushButton::clicked, this, [this] { send(23); });
    connect(save_, &QPushButton::clicked, this, [this] {
        picker_->setWindowTitle(save_->text());
        picker_->selectFile(filename_);
        picker_->open();
    });
    connect(picker_, &QFileDialog::accepted, this, [this] {
        if (isVisible() && !picker_->selectedFiles().isEmpty())
            send(25, QJsonObject{{"path", picker_->selectedFiles().first()}});
    });
    connect(close_, &QPushButton::clicked, this, &QDialog::reject);
    connect(this, &QDialog::finished, this, [this] { send(24); reset(); });
}

void AttachmentPreview::showFor(const QString &peer, const QString &message) {
    if (isVisible()) return;
    reset();
    peer_ = peer;
    message_ = message;
    excludeWindowFromCapture(this);
    QDialog::open();
    if (!send(22)) reset();
}

bool AttachmentPreview::send(int action, const QJsonObject &fields) {
    if (!request || peer_.isEmpty() || message_.isEmpty()) return false;
    auto state = fields;
    state.insert("peer", peer_);
    state.insert("id", message_);
    state.insert("generation", generation_);
    auto payload = QJsonDocument(state).toJson(QJsonDocument::Compact);
    const bool accepted = request(action, payload);
    payload.fill(0);
    return accepted;
}

void AttachmentPreview::present(const QJsonObject &state) {
    if (!isVisible() || state.value("generation").toInteger(-1) != generation_) return;
    if (state.value("dismiss").toBool()) { reset(); return; }
    message_ = state.value("id").toString();
    filename_ = state.value("filename").toString();
    setWindowTitle(filename_);
    name_->setText(state.value("name").toString());
    image_->setAccessibleName(name_->text());
    info_->setText(state.value("info").toString());
    status_->setText(state.value("status").toString());
    if (state.value("clearImage").toBool()) image_->clear();
    playback_->setAccessibleName(name_->text());
    const auto playing = state.value("playback").toBool();
    playback_->setVisible(playing);
    if (playing && !playbackLayout_->isActive()) playbackLayout_->start();
    previous_->setText(state.value("previous").toString());
    previous_->setVisible(!previous_->text().isEmpty());
    previous_->setEnabled(!state.value("working").toBool());
    next_->setText(state.value("next").toString());
    next_->setVisible(!next_->text().isEmpty());
    next_->setEnabled(!state.value("working").toBool());
    download_->setText(state.value("download").toString());
    download_->setVisible(!download_->text().isEmpty());
    download_->setEnabled(!state.value("working").toBool());
    save_->setText(state.value("save").toString());
    save_->setVisible(!save_->text().isEmpty());
    save_->setEnabled(!state.value("working").toBool());
    close_->setText(state.value("close").toString());
}

void AttachmentPreview::reportPlaybackSurface(bool focus) {
    if (!isVisible() || !playback_->isVisible() || playback_->width() < 1 || playback_->height() < 1) return;
    send(28, QJsonObject{{"hwnd", QString::number(qulonglong(playback_->winId()), 16)}, {"focus", focus}});
}

void AttachmentPreview::presentImage(qint64 generation, const uchar *pixels, int width, int height, int stride) {
    if (!isVisible() || generation != generation_ || !pixels || width < 1 || height < 1 ||
        width > 4096 || height > 4096 || stride != width * 4) return;
    image_->showImage(QImage(pixels, width, height, stride, QImage::Format_ARGB32_Premultiplied).copy());
}

void AttachmentPreview::reset() {
    hide();
    playbackLayout_->stop();
    playback_->hide();
    picker_->reject();
    ++generation_;
    peer_.clear();
    message_.clear();
    filename_.clear();
    image_->clear();
    name_->clear();
    info_->clear();
    status_->clear();
    download_->hide();
    save_->hide();
    previous_->hide();
    next_->hide();
}

} // namespace MsgAT
