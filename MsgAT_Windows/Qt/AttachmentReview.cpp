// SPDX-License-Identifier: AGPL-3.0-only
#include "AttachmentReview.h"
#include "ChatPalette.h"
#include "FluentChrome.h"
#include "ChatComposer.h"
#include "WindowPrivacy.h"

#include <QBoxLayout>
#include <QCheckBox>
#include <QDateTimeEdit>
#include <QFileDialog>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QListWidget>
#include <QMimeDatabase>
#include <QPushButton>
#include <QSignalBlocker>
#include <QSet>
#include <QStyle>
#include <QTimer>

namespace MsgAT {
namespace {
constexpr int pathRole = Qt::UserRole;
constexpr int captionRole = Qt::UserRole + 1;
constexpr int mimeRole = Qt::UserRole + 2;
constexpr int fileLimit = 10;
}

AttachmentReview::AttachmentReview(QWidget *parent) : QDialog(parent) {
    installChatPalette(this, ChatSurface::ConversationList);
    setWindowModality(Qt::WindowModal);
    resize(600, 550);

    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(18, 18, 18, 16);
    layout->setSpacing(10);
    recipient_ = new QLabel(this);
    recipient_->setTextFormat(Qt::PlainText);
    recipient_->setWordWrap(true);
    layout->addWidget(recipient_);

    files_ = new QListWidget(this);
    files_->setAlternatingRowColors(true);
    installChatPalette(files_, ChatSurface::Timeline);
    layout->addWidget(files_, 1);

    auto *order = new QHBoxLayout;
    add_ = new QPushButton(this);
    up_ = new QPushButton(this);
    down_ = new QPushButton(this);
    edit_ = new QPushButton(this);
    remove_ = new QPushButton(this);
    setFluentAction(add_, Symbol::Attach);
    setFluentAction(up_, Symbol::Up);
    setFluentAction(down_, Symbol::Down);
    setFluentAction(edit_, Symbol::Edit);
    setFluentAction(remove_, Symbol::Remove);
    up_->setMinimumWidth(36);
    down_->setMinimumWidth(36);
    order->addWidget(add_);
    order->addStretch();
    order->addWidget(edit_);
    order->addWidget(up_);
    order->addWidget(down_);
    order->addWidget(remove_);
    layout->addLayout(order);

    caption_ = new ChatComposer(this);
    caption_->sendOnEnter = false;
    caption_->copySelection = [this](const QString &value) { return copyCaption && copyCaption(value); };
    layout->addWidget(caption_);

    asFile_ = new QCheckBox(this);
    album_ = new QCheckBox(this);
    photos_ = new QCheckBox(this);
    videos_ = new QCheckBox(this);
    auto *formats = new QGridLayout;
    formats->addWidget(asFile_, 0, 0);
    formats->addWidget(album_, 0, 1);
    formats->addWidget(photos_, 1, 0);
    formats->addWidget(videos_, 1, 1);
    layout->addLayout(formats);

    auto *delivery = new QHBoxLayout;
    silent_ = new QCheckBox(this);
    schedule_ = new QCheckBox(this);
    sendAt_ = new FluentCalendarEdit(this);
    sendAt_->setCalendarPopup(true);
    delivery->addWidget(silent_);
    delivery->addStretch();
    delivery->addWidget(schedule_);
    delivery->addWidget(sendAt_);
    layout->addLayout(delivery);

    status_ = new QLabel(this);
    status_->setTextFormat(Qt::PlainText);
    status_->setWordWrap(true);
    layout->addWidget(status_);
    auto *actions = new QHBoxLayout;
    actions->addStretch();
    cancel_ = new QPushButton(this);
    submit_ = new QPushButton(this);
    submit_->setObjectName(QStringLiteral("fluentPrimary"));
    setFluentAction(submit_, Symbol::Send, true);
    setFluentAction(cancel_, Symbol::Close);
    actions->addWidget(cancel_);
    actions->addWidget(submit_);
    layout->addLayout(actions);
    for (auto *button : {add_, up_, down_, edit_, remove_, cancel_, submit_}) button->setAutoDefault(false);

    picker_ = new QFileDialog(this);
    picker_->setFileMode(QFileDialog::ExistingFiles);
    picker_->setAcceptMode(QFileDialog::AcceptOpen);
    connect(picker_, &QFileDialog::accepted, this, &AttachmentReview::addFiles);
    connect(add_, &QPushButton::clicked, picker_, [this] { picker_->open(); });
    connect(up_, &QPushButton::clicked, this, [this] { moveFile(-1); });
    connect(down_, &QPushButton::clicked, this, [this] { moveFile(1); });
    connect(edit_, &QPushButton::clicked, this, [this] {
        const auto *item = files_->currentItem();
        if (!item || busy_ || editing_) return;
        send(29, QJsonObject{{"path", item->data(pathRole).toString()},
                            {"hwnd", QString::number(qulonglong(winId()), 16)}});
    });
    connect(remove_, &QPushButton::clicked, this, [this] {
        const auto *item = files_->currentItem();
        if (!item || !send(30, QJsonObject{{"path", item->data(pathRole).toString()}})) return;
        delete files_->takeItem(files_->currentRow());
        updateOptions();
    });
    connect(files_, &QListWidget::currentItemChanged, this, [this] {
        updateCaption();
        updateOptions();
    });
    connect(caption_, &QPlainTextEdit::textChanged, this, [this] {
        if (auto *item = files_->currentItem()) item->setData(captionRole, caption_->toPlainText());
    });
    connect(asFile_, &QCheckBox::toggled, this, &AttachmentReview::updateOptions);
    connect(schedule_, &QCheckBox::toggled, this, &AttachmentReview::updateOptions);
    connect(cancel_, &QPushButton::clicked, this, &AttachmentReview::reject);
    connect(submit_, &QPushButton::clicked, this, [this] {
        QJsonArray paths, captions;
        for (int index = 0; index < files_->count(); ++index) {
            const auto *item = files_->item(index);
            const auto caption = item->data(captionRole).toString();
            if (caption.toUtf8().size() > 48 * 1024) {
                files_->setCurrentRow(index);
                caption_->setFocus();
                status_->setText(labels_.value("captionTooLong").toString());
                return;
            }
            paths.append(item->data(pathRole).toString());
            captions.append(caption);
        }
        QJsonObject fields{
            {"paths", paths}, {"captions", captions}, {"asFile", asFile_->isChecked()},
            {"album", album_->isEnabled() && album_->isChecked()},
            {"photos", photos_->isEnabled() && photos_->isChecked()},
            {"videos", videos_->isEnabled() && videos_->isChecked()},
            {"silent", silent_->isChecked()}
        };
        if (schedule_->isChecked()) fields.insert("sendAt", sendAt_->dateTime().toMSecsSinceEpoch());
        busy_ = canCancel_ = true;
        updateOptions();
        if (!send(19, fields)) {
            busy_ = false;
            status_->setText(labels_.value("failed").toString());
            updateOptions();
        }
    });
    connect(this, &QDialog::finished, this, [this] {
        send(21);
        reset();
    });
}

void AttachmentReview::showFor(const QString &peer, const QJsonObject &options, const QStringList &paths) {
    if (isVisible() || peer.isEmpty()) return;
    reset();
    peer_ = peer;
    labels_ = options;
    if (!send(18)) { reset(); return; }
    const auto label = [this](const char *key) { return labels_.value(QLatin1String(key)).toString(); };
    setWindowTitle(label("title"));
    recipient_->setText(label("recipient"));
    files_->setAccessibleName(label("title"));
    picker_->setWindowTitle(label("add"));
    add_->setText(label("add"));
    up_->setAccessibleName(label("up"));
    up_->setToolTip(label("up"));
    down_->setAccessibleName(label("down"));
    down_->setToolTip(label("down"));
    remove_->setText(label("remove"));
    caption_->setPlaceholderText(label("caption"));
    caption_->setAccessibleName(label("caption"));
    caption_->present(QJsonObject{{"editorLabels", labels_.value("editorLabels")}, {"canCompose", true}}, true);
    asFile_->setText(label("asFile"));
    album_->setText(label("album"));
    photos_->setText(label("photos"));
    videos_->setText(label("videos"));
    silent_->setText(label("silent"));
    schedule_->setText(label("schedule"));
    sendAt_->setAccessibleName(label("schedule"));
    submit_->setText(label("submit"));
    asFile_->setChecked(false);
    album_->setChecked(false);
    photos_->setChecked(false);
    videos_->setChecked(false);
    silent_->setChecked(options.value("quiet").toBool());
    const auto scheduled = options.value("scheduled");
    schedule_->setChecked(scheduled.isDouble());
    sendAt_->setDateTime(scheduled.isDouble() ? QDateTime::fromMSecsSinceEpoch(scheduled.toInteger()) :
                                                QDateTime::currentDateTime().addSecs(3600));
    updateOptions();
    excludeWindowFromCapture(this);
    QDialog::open();
    if (paths.isEmpty()) QTimer::singleShot(0, this, [this] { if (isVisible()) picker_->open(); });
    else appendFiles(paths);
}

void AttachmentReview::addFiles() {
    appendFiles(picker_->selectedFiles());
}

void AttachmentReview::appendFiles(const QStringList &paths) {
    if (!isVisible() || busy_ || editing_) return;
    status_->clear();
    QMimeDatabase types;
    for (const auto &path : paths) {
        bool existing = false;
        for (int row = 0; row < files_->count(); ++row)
            if (files_->item(row)->data(pathRole).toString().compare(path, Qt::CaseInsensitive) == 0) {
                existing = true;
                break;
            }
        if (existing) {
            status_->setText(labels_.value("duplicate").toString());
            continue;
        }
        if (files_->count() == fileLimit) {
            status_->setText(labels_.value("limit").toString());
            break;
        }
        auto *item = new QListWidgetItem(QFileInfo(path).fileName(), files_);
        item->setData(pathRole, path);
        item->setData(mimeRole, types.mimeTypeForFile(path, QMimeDatabase::MatchExtension).name());
    }
    if (!files_->currentItem() && files_->count()) files_->setCurrentRow(0);
    updateOptions();
}

void AttachmentReview::moveFile(int delta) {
    const auto row = files_->currentRow();
    const auto next = row + delta;
    if (row < 0 || next < 0 || next >= files_->count()) return;
    auto *item = files_->takeItem(row);
    files_->insertItem(next, item);
    files_->setCurrentItem(item);
}

void AttachmentReview::updateCaption() {
    const QSignalBlocker changed(caption_);
    caption_->setPlainText(files_->currentItem() ? files_->currentItem()->data(captionRole).toString() : QString{});
}

void AttachmentReview::updateOptions() {
    const auto row = files_->currentRow();
    const auto *selected = files_->currentItem();
    const auto mime = selected ? selected->data(mimeRole).toString() : QString{};
    const bool trim = mime.startsWith(QLatin1String("video/"));
    const bool canEdit = trim || mime == QLatin1String("image/jpeg") || mime == QLatin1String("image/png");
    const bool editable = !busy_ && !editing_;
    edit_->setVisible(canEdit);
    edit_->setEnabled(editable && canEdit);
    edit_->setText(labels_.value(trim ? "editVideo" : "editPhoto").toString());
    bool visual = files_->count() > 1, hasPhoto = false, hasVideo = false;
    for (int index = 0; index < files_->count(); ++index) {
        const auto mime = files_->item(index)->data(mimeRole).toString();
        const bool photo = mime.startsWith(QLatin1String("image/")), video = mime.startsWith(QLatin1String("video/"));
        visual = visual && (photo || video);
        hasPhoto = hasPhoto || photo;
        hasVideo = hasVideo || video;
    }
    files_->setEnabled(editable);
    add_->setEnabled(editable && files_->count() < fileLimit);
    up_->setEnabled(editable && row > 0);
    down_->setEnabled(editable && row >= 0 && row < files_->count() - 1);
    remove_->setEnabled(editable && row >= 0);
    caption_->setEnabled(editable && row >= 0);
    asFile_->setEnabled(editable);
    album_->setEnabled(editable && !asFile_->isChecked() && visual);
    photos_->setEnabled(editable && !asFile_->isChecked() && hasPhoto);
    videos_->setEnabled(editable && !asFile_->isChecked() && hasVideo);
    silent_->setEnabled(editable);
    schedule_->setEnabled(editable);
    sendAt_->setEnabled(editable && schedule_->isChecked());
    submit_->setEnabled(editable && files_->count() > 0);
    cancel_->setEnabled(!editing_ && (!busy_ || canCancel_));
    cancel_->setText(labels_.value(busy_ ? "cancelPreparation" : "cancel").toString());
}

bool AttachmentReview::send(int action, const QJsonObject &fields) {
    if (peer_.isEmpty() || !request) return false;
    auto state = fields;
    state.insert("peer", peer_);
    state.insert("generation", generation_);
    auto bytes = QJsonDocument(state).toJson(QJsonDocument::Compact);
    const auto accepted = request(action, bytes);
    bytes.fill(0);
    return accepted;
}

void AttachmentReview::present(const QJsonObject &state) {
    if (!isVisible() || state.value("generation").toInteger(-1) != generation_) return;
    if (state.value("dismiss").toBool()) { reset(); return; }
    busy_ = state.value("busy").toBool();
    editing_ = state.value("editing").toBool();
    canCancel_ = state.value("canCancel").toBool();
    status_->setText(state.value("status").toString());
    QSet<QString> edits;
    for (const auto &path : state.value("edits").toArray()) edits.insert(path.toString());
    const auto editedIcon = style()->standardIcon(QStyle::SP_DialogApplyButton);
    for (int row = 0; row < files_->count(); ++row) {
        auto *item = files_->item(row);
        item->setIcon(edits.contains(item->data(pathRole).toString()) ? editedIcon : QIcon{});
    }
    updateOptions();
    if (state.value("sent").toBool()) accept();
}

void AttachmentReview::reject() {
    if (editing_) return;
    if (!busy_) { QDialog::reject(); return; }
    if (canCancel_) {
        send(20);
        canCancel_ = false;
        updateOptions();
    }
}

void AttachmentReview::reset() {
    hide();
    picker_->reject();
    ++generation_;
    peer_.clear();
    labels_ = QJsonObject{};
    busy_ = canCancel_ = editing_ = false;
    files_->clear();
    caption_->reset();
    recipient_->clear();
    status_->clear();
}

} // namespace MsgAT
