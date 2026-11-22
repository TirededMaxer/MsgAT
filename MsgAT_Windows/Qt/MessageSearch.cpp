// SPDX-License-Identifier: AGPL-3.0-only
#include "MessageSearch.h"
#include "WindowPrivacy.h"
#include "ChatPalette.h"
#include "FluentChrome.h"

#include <QBoxLayout>
#include <QCheckBox>
#include <QComboBox>
#include <QDateEdit>
#include <QInputMethodEvent>
#include <QJsonArray>
#include <QJsonDocument>
#include <QKeyEvent>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QPushButton>
#include <QTimer>

namespace MsgAT {

MessageSearch::MessageSearch(QWidget *parent) : QDialog(parent) {
    installChatPalette(this, ChatSurface::ConversationList);
    setWindowModality(Qt::WindowModal);
    resize(640, 540);
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(18, 18, 18, 16);
    layout->setSpacing(10);
    query_ = new QLineEdit(this);
    query_->setMaxLength(512);
    query_->addAction(fluentIcon(Symbol::Search), QLineEdit::LeadingPosition);
    query_->setClearButtonEnabled(true);
    query_->installEventFilter(this);
    layout->addWidget(query_);
    auto *filters = new QHBoxLayout;
    all_ = new QCheckBox(this);
    kind_ = new QComboBox(this);
    dateEnabled_ = new QCheckBox(this);
    date_ = new QDateEdit(QDate::currentDate(), this);
    date_->setCalendarPopup(true);
    filters->addWidget(all_);
    filters->addWidget(kind_);
    filters->addStretch();
    filters->addWidget(dateEnabled_);
    filters->addWidget(date_);
    layout->addLayout(filters);
    results_ = new QListWidget(this);
    installChatPalette(results_, ChatSurface::Timeline);
    results_->setWordWrap(true);
    layout->addWidget(results_, 1);
    status_ = new QLabel(this);
    status_->setTextFormat(Qt::PlainText);
    status_->setWordWrap(true);
    layout->addWidget(status_);
    auto *actions = new QHBoxLayout;
    more_ = new QPushButton(this);
    retry_ = new QPushButton(this);
    jump_ = new QPushButton(this);
    close_ = new QPushButton(this);
    jump_->setObjectName(QStringLiteral("fluentPrimary"));
    setFluentAction(more_, Symbol::Down);
    setFluentAction(retry_, Symbol::Reset);
    setFluentAction(jump_, Symbol::Open, true);
    setFluentAction(close_, Symbol::Close);
    for (auto *button : {more_, retry_, jump_, close_}) button->setAutoDefault(false);
    actions->addWidget(more_);
    actions->addWidget(retry_);
    actions->addStretch();
    actions->addWidget(close_);
    actions->addWidget(jump_);
    layout->addLayout(actions);
    debounce_ = new QTimer(this);
    debounce_->setSingleShot(true);
    debounce_->setInterval(250);
    connect(debounce_, &QTimer::timeout, this, [this] { send(11); });
    connect(query_, &QLineEdit::textEdited, this, &MessageSearch::changeInput);
    connect(all_, &QCheckBox::toggled, this, &MessageSearch::changeInput);
    connect(kind_, &QComboBox::currentIndexChanged, this, &MessageSearch::changeInput);
    connect(dateEnabled_, &QCheckBox::toggled, this, &MessageSearch::changeInput);
    connect(date_, &QDateEdit::dateChanged, this, &MessageSearch::changeInput);
    connect(results_, &QListWidget::currentItemChanged, this, &MessageSearch::updateButtons);
    connect(results_, &QListWidget::itemActivated, this, [this] { send(13); });
    connect(more_, &QPushButton::clicked, this, [this] { send(12); });
    connect(retry_, &QPushButton::clicked, this, [this] { send(retryAction_); });
    connect(jump_, &QPushButton::clicked, this, [this] { send(13); });
    connect(close_, &QPushButton::clicked, this, &QDialog::reject);
    connect(this, &QDialog::finished, this, [this] {
        if (request) request(14, QByteArray("{}"));
        reset();
    });
}

void MessageSearch::showFor(const QString &peer, const QJsonObject &labels) {
    if (isVisible()) { query_->setFocus(); return; }
    configuring_ = true;
    peer_ = peer;
    const auto title = labels.value("title").toString();
    setWindowTitle(title);
    query_->setAccessibleName(title);
    query_->setPlaceholderText(title);
    all_->setText(labels.value("all").toString());
    all_->setChecked(peer.isEmpty());
    all_->setVisible(!peer.isEmpty());
    kind_->setAccessibleName(labels.value("type").toString());
    kind_->clear();
    for (const auto &value : labels.value("kinds").toArray()) kind_->addItem(value.toString());
    dateEnabled_->setText(labels.value("date").toString());
    date_->setAccessibleName(dateEnabled_->text());
    dateEnabled_->setChecked(false);
    date_->setDate(QDate::currentDate());
    date_->setEnabled(false);
    more_->setText(labels.value("more").toString());
    retry_->setText(labels.value("retry").toString());
    jump_->setText(labels.value("open").toString());
    close_->setText(labels.value("close").toString());
    retry_->hide();
    configuring_ = false;
    updateButtons();
    excludeWindowFromCapture(this);
    QDialog::open();
    query_->setFocus();
}

void MessageSearch::reset() {
    hide();
    debounce_->stop();
    ++generation_;
    peer_.clear();
    query_->clear();
    results_->clear();
    status_->clear();
    busy_ = next_ = opening_ = composing_ = false;
    retry_->hide();
}

void MessageSearch::changeInput() {
    if (configuring_ || !isVisible()) return;
    ++generation_;
    debounce_->stop();
    const auto text = query_->text();
    const bool dated = dateEnabled_->isChecked();
    date_->setEnabled(dated);
    next_ = busy_ = false;
    status_->clear();
    retry_->hide();
    results_->clear();
    updateButtons();
    if (!text.trimmed().isEmpty()) debounce_->start();
    else send(11);
}

void MessageSearch::send(int action) {
    if (opening_ || !isVisible()) return;
    if ((action == 12 && (busy_ || !next_)) ||
        (action == 13 && (busy_ || !results_->currentItem()))) return;
    QJsonObject state{{"generation", generation_}, {"peer", peer_}};
    if (action == 11) {
        state.insert("query", query_->text());
        state.insert("all", all_->isChecked());
        state.insert("type", kind_->currentIndex());
        if (dateEnabled_->isChecked()) state.insert("day", date_->date().toString(Qt::ISODate));
    } else if (action == 13) {
        state.insert("id", results_->currentItem()->data(Qt::UserRole).toString());
        opening_ = true;
    }
    retry_->hide();
    busy_ = true;
    updateButtons();
    auto payload = QJsonDocument(state).toJson(QJsonDocument::Compact);
    const bool accepted = request && request(action, payload);
    payload.fill(0);
    if (!accepted) reject();
}

void MessageSearch::present(const QJsonObject &state) {
    if (!isVisible() || state.value("generation").toInteger(-1) != generation_) return;
    if (state.value("opened").toBool()) { accept(); return; }
    busy_ = state.value("busy").toBool();
    if (!busy_) opening_ = false;
    next_ = state.value("more").toBool();
    retryAction_ = state.value("retryAction").toInt(11);
    retry_->setVisible(state.value("failed").toBool() && retryAction_ != 13);
    status_->setText(state.value("status").toString());
    const bool replace = state.value("replace").toBool();
    const auto selected = replace && results_->currentItem()
        ? results_->currentItem()->data(Qt::UserRole).toString() : QString{};
    if (replace) results_->clear();
    for (const auto &value : state.value("rows").toArray()) {
        const auto hit = value.toObject();
        auto *row = new QListWidgetItem(hit.value("text").toString(), results_);
        const auto id = hit.value("id").toString();
        row->setData(Qt::UserRole, id);
        if (id == selected) results_->setCurrentItem(row);
    }
    if (results_->currentRow() < 0 && results_->count()) results_->setCurrentRow(0);
    updateButtons();
}

void MessageSearch::updateButtons() {
    jump_->setEnabled(!busy_ && !opening_ && results_->currentItem());
    more_->setVisible(next_);
    more_->setEnabled(!busy_);
    query_->setReadOnly(opening_);
    all_->setEnabled(!opening_);
    kind_->setEnabled(!opening_);
    dateEnabled_->setEnabled(!opening_);
    date_->setEnabled(!opening_ && dateEnabled_->isChecked());
}

bool MessageSearch::eventFilter(QObject *object, QEvent *event) {
    if (object != query_) return QDialog::eventFilter(object, event);
    if (event->type() == QEvent::InputMethod) {
        composing_ = !static_cast<QInputMethodEvent *>(event)->preeditString().isEmpty();
    } else if (event->type() == QEvent::FocusOut) {
        composing_ = false;
    } else if (event->type() == QEvent::KeyPress && !composing_) {
        const auto *key = static_cast<QKeyEvent *>(event);
        if (key->modifiers() != Qt::NoModifier) return QDialog::eventFilter(object, event);
        if (key->key() == Qt::Key_Enter || key->key() == Qt::Key_Return) { send(13); return true; }
        if (key->key() == Qt::Key_Up || key->key() == Qt::Key_Down) {
            const auto target = results_->currentRow() + (key->key() == Qt::Key_Up ? -1 : 1);
            if (target >= 0 && target < results_->count()) results_->setCurrentRow(target);
            else if (target == results_->count() && next_) send(12);
            return true;
        }
    }
    return QDialog::eventFilter(object, event);
}

} // namespace MsgAT
