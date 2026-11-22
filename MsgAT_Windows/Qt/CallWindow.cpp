// SPDX-License-Identifier: AGPL-3.0-only
#include "CallWindow.h"
#include "ChatPalette.h"
#include "FluentChrome.h"
#include "WindowPrivacy.h"

#include <QBoxLayout>
#include <QCloseEvent>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QMenu>
#include <QPushButton>
#include <QTimer>

namespace MsgAT {

CallWindow::CallWindow() : QDialog(nullptr, Qt::Tool) {
    setWindowModality(Qt::NonModal);
    setAttribute(Qt::WA_ShowWithoutActivating);
    setWindowTitle(QStringLiteral("MsgAT"));
    installChatPalette(this, ChatSurface::Timeline);
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(16, 14, 16, 14);
    layout->setSpacing(10);
    auto *identity = new QHBoxLayout;
    identity->setSpacing(12);
    initial_ = new QLabel(this);
    initial_->setTextFormat(Qt::PlainText);
    initial_->setFixedSize(44, 44);
    initial_->setAlignment(Qt::AlignCenter);
    initial_->setObjectName(QStringLiteral("callAvatar"));
    identity->addWidget(initial_);
    auto *details = new QVBoxLayout;
    details->setSpacing(3);
    peer_ = new QLabel(this);
    peer_->setTextFormat(Qt::PlainText);
    peer_->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    auto title = peer_->font();
    title.setWeight(QFont::DemiBold);
    if (title.pointSizeF() > 0) title.setPointSizeF(title.pointSizeF() * 1.25);
    peer_->setFont(title);
    status_ = new QLabel(this);
    status_->setTextFormat(Qt::PlainText);
    status_->setWordWrap(true);
    details->addWidget(peer_);
    details->addWidget(status_);
    identity->addLayout(details, 1);
    quality_ = new QLabel(this);
    quality_->setTextFormat(Qt::PlainText);
    identity->addWidget(quality_);
    layout->addLayout(identity);

    waiting_ = new QLabel(this);
    waiting_->setTextFormat(Qt::PlainText);
    waiting_->setAlignment(Qt::AlignCenter);
    layout->addWidget(waiting_);
    video_ = new QWidget(this);
    video_->setAttribute(Qt::WA_DontCreateNativeAncestors);
    video_->setMinimumHeight(120);
    video_->setStyleSheet(QStringLiteral("background: #000"));
    videoLayout_ = new QTimer(this);
    videoLayout_->setSingleShot(true);
    connect(videoLayout_, &QTimer::timeout, this, &CallWindow::reportVideoSurface);
    video_->installEventFilter(this);
    layout->addWidget(video_, 1);

    notice_ = new QLabel(this);
    notice_->setTextFormat(Qt::PlainText);
    notice_->setWordWrap(true);
    layout->addWidget(notice_);
    auto *actions = new QHBoxLayout;
    actions->setSpacing(7);
    actions->addStretch();
    auto button = [this, actions](const char *key, bool main = false) {
        auto *control = new QPushButton(this);
        control->setAutoDefault(false);
        actions->addWidget(control);
        if (main) {
            auto font = control->font();
            font.setWeight(QFont::DemiBold);
            control->setFont(font);
        }
        if (key) connect(control, &QPushButton::clicked, this, [this, key] { send(QString::fromLatin1(key)); });
        return control;
    };
    reject_ = button("reject");
    answer_ = button("answer", true);
    devices_ = button(nullptr);
    audioMenu_ = new QMenu(devices_);
    devices_->setMenu(audioMenu_);
    mute_ = button("mute");
    camera_ = button("camera");
    switchCamera_ = button("switchCamera");
    compactButton_ = button(nullptr);
    connect(compactButton_, &QPushButton::clicked, this, [this] { setCompact(!compact_); });
    end_ = button("end", true);
    answer_->setObjectName(QStringLiteral("fluentPrimary"));
    reject_->setObjectName(QStringLiteral("fluentDestructive"));
    end_->setObjectName(QStringLiteral("fluentDestructive"));
    setFluentAction(answer_, Symbol::Call, true);
    setFluentAction(reject_, Symbol::HangUp, true);
    setFluentAction(end_, Symbol::HangUp, true);
    setFluentAction(camera_, Symbol::Video);
    setFluentAction(switchCamera_, Symbol::Reset);
    setFluentAction(mute_, Symbol::Record);
    setFluentAction(devices_, Symbol::Settings);
    setFluentAction(compactButton_, Symbol::Options);
    layout->addLayout(actions);
    reset();
}

void CallWindow::present(const QJsonObject &state) {
    const auto id = state.value("id").toString();
    if (id.isEmpty()) { reset(); return; }
    const bool first = id_ != id;
    if (first) {
        reset();
        id_ = id;
        compact_ = state.value("compact").toBool();
    }
    state_ = state;
    setWindowTitle(QStringLiteral("MsgAT · ") + state.value("title").toString());
    peer_->setText(state.value("peer").toString());
    peer_->setAccessibleName(peer_->text());
    initial_->setText(state.value("initial").toString());
    status_->setText(state.value("status").toString());
    quality_->setText(state.value("latency").toString());
    const auto notice = state.value("notice").toString();
    notice_->setText(notice);
    notice_->setVisible(!notice.isEmpty());
    updateDevices(state.value("devices").toObject());
    const int previousMode = layoutMode_;
    updatePresentation(true);
    const bool opening = !isVisible();
    if (opening) {
        excludeWindowFromCapture(this);
        placeBeside(state.value("anchor").toString());
        show();
    } else if (previousMode != layoutMode_) placeBeside(state.value("anchor").toString());
    // WPF's modal dispatcher disables visible thread windows. The standalone call
    // controls must remain usable while its sibling settings or review dialog is open.
    const auto handle = reinterpret_cast<HWND>(winId());
    if (!IsWindowEnabled(handle)) EnableWindow(handle, TRUE);
    if (state.value("activate").toBool()) {
        showNormal();
        raise();
        activateWindow();
        if (answer_->isVisible()) answer_->setFocus();
        else if (mute_->isEnabled()) mute_->setFocus();
    }
    if ((opening || previousMode != layoutMode_) && video_->isVisible() && !videoLayout_->isActive()) videoLayout_->start();
}

void CallWindow::updatePresentation(bool resizeWindow) {
    const auto labels = state_.value("labels").toObject();
    const bool incoming = state_.value("incoming").toBool();
    const bool ended = state_.value("ended").toBool();
    const bool connected = state_.value("connected").toBool();
    const bool pending = state_.value("busy").toBool();
    const bool videoCall = state_.value("video").toBool();
    const bool fullVideo = videoCall && !incoming && !ended && !compact_;
    const bool remote = state_.value("remoteVideo").toBool();
    const bool local = state_.value("localVideo").toBool();
    const bool videoFault = state_.value("videoFault").toBool();
    const auto set = [&](QPushButton *control, const char *key, bool visible, bool enabled) {
        control->setText(labels.value(QLatin1String(key)).toString());
        control->setVisible(visible);
        control->setEnabled(enabled);
    };
    set(answer_, "answer", incoming, !pending);
    set(reject_, "reject", incoming, !pending);
    set(end_, "end", !incoming && !ended, !state_.value("ending").toBool());
    set(mute_, "mute", !incoming && !ended, connected && !pending);
    set(camera_, "camera", fullVideo, !pending && state_.value("canCamera").toBool());
    set(switchCamera_, "switchCamera", fullVideo && local, !pending && state_.value("canSwitchCamera").toBool());
    set(devices_, "devices", connected && !compact_, !pending && !deviceState_.isEmpty());
    set(compactButton_, compact_ ? "expand" : "compact", !incoming && !ended, !pending);
    compactButton_->setIcon(fluentIcon(compact_ ? Symbol::Options : Symbol::ChevronUp));
    waiting_->setText(labels.value(videoFault ? "videoUnavailable" : "waiting").toString());
    waiting_->setVisible(fullVideo && (!remote || videoFault));
    video_->setVisible(fullVideo && !videoFault);
    quality_->setVisible(connected && !compact_);
    const int mode = fullVideo ? 2 : compact_ ? 0 : 1;
    if (resizeWindow && layoutMode_ != mode) {
        layoutMode_ = mode;
        if (fullVideo) resize(720, 510);
        else {
            layout()->activate();
            resize(qMax(compact_ ? 320 : 380, minimumSizeHint().width()), minimumSizeHint().height());
        }
    }
}

void CallWindow::updateDevices(const QJsonObject &devices) {
    if (deviceState_ == devices) return;
    deviceState_ = devices;
    audioMenu_->clear();
    auto addDevices = [&](const char *key, const char *label, const char *action) {
        auto *group = audioMenu_->addMenu(devices.value(QLatin1String(label)).toString());
        for (const auto &value : devices.value(QLatin1String(key)).toArray()) {
            const auto entry = value.toObject();
            const auto id = entry.value("id").toString();
            auto name = entry.value("name").toString();
            auto *option = group->addAction(name.replace(QLatin1Char('&'), QStringLiteral("&&")));
            option->setCheckable(true);
            option->setChecked(entry.value("selected").toBool());
            connect(option, &QAction::triggered, this, [this, action, id] {
                send(QString::fromLatin1(action), QJsonObject{{"device", id}});
            });
        }
        group->setEnabled(!group->isEmpty());
    };
    if (devices.isEmpty()) return;
    addDevices("inputs", "inputLabel", "input");
    addDevices("outputs", "outputLabel", "output");
}

void CallWindow::placeBeside(const QString &anchor) {
    bool valid = false;
    const auto owner = reinterpret_cast<HWND>(quintptr(anchor.toULongLong(&valid, 16)));
    const auto handle = reinterpret_cast<HWND>(winId());
    RECT base{}, bounds{};
    MONITORINFO monitor{sizeof(MONITORINFO)};
    if (!valid || !GetWindowRect(owner, &base) || !GetWindowRect(handle, &bounds) ||
        !GetMonitorInfoW(MonitorFromWindow(owner, MONITOR_DEFAULTTONEAREST), &monitor)) return;
    const auto gap = MulDiv(12, GetDpiForWindow(owner), 96);
    const auto width = bounds.right - bounds.left;
    const auto height = bounds.bottom - bounds.top;
    const auto &work = monitor.rcWork;
    const auto rightLimit = qMax(work.left, work.right - width);
    const auto right = base.right + gap;
    const auto left = base.left - width - gap;
    const auto x = qBound(work.left, right <= rightLimit ? right : left >= work.left ? left : rightLimit, rightLimit);
    const auto y = qBound(work.top, base.top, qMax(work.top, work.bottom - height));
    SetWindowPos(handle, nullptr, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
}

void CallWindow::setCompact(bool compact) {
    if (compact_ == compact) return;
    compact_ = compact;
    updatePresentation(true);
    placeBeside(state_.value("anchor").toString());
    if (video_->isVisible()) videoLayout_->start();
}

bool CallWindow::send(const QString &action, const QJsonObject &fields) {
    if (id_.isEmpty() || !request) return false;
    auto data = fields;
    data.insert("id", id_);
    data.insert("action", action);
    auto bytes = QJsonDocument(data).toJson(QJsonDocument::Compact);
    const bool accepted = request(bytes);
    bytes.fill(0);
    return accepted;
}

bool CallWindow::eventFilter(QObject *target, QEvent *event) {
    if (target == video_ && (event->type() == QEvent::Resize || event->type() == QEvent::Show || event->type() == QEvent::WinIdChange ||
        event->type() == QEvent::DevicePixelRatioChange) && video_->isVisible() && !videoLayout_->isActive())
        videoLayout_->start();
    return QDialog::eventFilter(target, event);
}

void CallWindow::reportVideoSurface() {
    if (video_->isVisible() && video_->width() > 0 && video_->height() > 0)
        send(QStringLiteral("surface"), QJsonObject{{"hwnd", QString::number(qulonglong(video_->winId()), 16)}});
}

void CallWindow::closeEvent(QCloseEvent *event) {
    event->ignore();
    reject();
}

void CallWindow::reject() {
    if (state_.value("incoming").toBool()) send(QStringLiteral("reject"));
    else if (state_.value("ended").toBool()) reset();
    else setCompact(true);
}

void CallWindow::reset() {
    hide();
    videoLayout_->stop();
    video_->hide();
    id_.clear();
    state_ = {};
    deviceState_ = {};
    audioMenu_->hide();
    audioMenu_->clear();
    for (auto *label : {peer_, initial_, status_, waiting_, quality_, notice_}) label->clear();
    layoutMode_ = -1;
    compact_ = false;
}

} // namespace MsgAT
