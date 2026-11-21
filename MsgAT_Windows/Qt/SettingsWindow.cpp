// SPDX-License-Identifier: AGPL-3.0-only
#include "SettingsWindow.h"
#include "ChatPalette.h"
#include "WindowPrivacy.h"

#include <QBoxLayout>
#include <QCheckBox>
#include <QCloseEvent>
#include <QComboBox>
#include <QDir>
#include <QFileDialog>
#include <QFormLayout>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QLocale>
#include <QPixmap>
#include <QProgressBar>
#include <QPushButton>
#include <QRegularExpression>
#include <QScopedValueRollback>
#include <QScrollArea>
#include <QScrollBar>
#include <QShortcut>
#include <QStackedWidget>
#include <QTimer>
#include <QToolButton>
#include <algorithm>

namespace MsgAT {
namespace {

enum Page { Overview, Network, Privacy, Notifications, Storage, ExportFolder, Profile, Reset };

QLabel *plainLabel(QWidget *parent) {
    auto *label = new QLabel(parent);
    label->setTextFormat(Qt::PlainText);
    label->setWordWrap(true);
    return label;
}

QScrollArea *scrollFor(QWidget *content, QWidget *parent) {
    auto *scroll = new QScrollArea(parent);
    scroll->setWidget(content);
    scroll->setWidgetResizable(true);
    scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll->setFrameShape(QFrame::NoFrame);
    return scroll;
}

} // namespace

SettingsWindow::SettingsWindow(QWidget *parent) : QDialog(parent) {
    installChatPalette(this, ChatSurface::Timeline);
    setWindowModality(Qt::WindowModal);
    resize(570, 560);
    setMinimumWidth(340);
    setStyleSheet(QStringLiteral(
        "QPushButton#settingsLink { text-align: left; padding: 7px 12px; border: 1px solid transparent;"
        " border-radius: 7px; background: palette(base); color: palette(text); }"
        "QPushButton#settingsLink:hover { background: palette(alternate-base); }"
        "QPushButton#settingsLink:focus { border-color: palette(text); }"
        "QLineEdit { min-height: 26px; padding: 3px 8px; }"));
    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(18, 14, 18, 14);
    layout->setSpacing(10);
    auto *header = new QHBoxLayout;
    back_ = new QPushButton(this);
    back_->setAutoDefault(false);
    connect(back_, &QPushButton::clicked, this, &SettingsWindow::showOverview);
    header->addWidget(back_);
    heading_ = plainLabel(this);
    auto headingFont = heading_->font();
    headingFont.setWeight(QFont::DemiBold);
    if (headingFont.pointSizeF() > 0) headingFont.setPointSizeF(headingFont.pointSizeF() * 1.35);
    heading_->setFont(headingFont);
    header->addWidget(heading_, 1);
    layout->addLayout(header);
    pages_ = new QStackedWidget(this);
    pages_->addWidget(makeOverview());
    pages_->addWidget(makeNetwork());
    pages_->addWidget(makePrivacy());
    pages_->addWidget(makeNotifications());
    pages_->addWidget(makeStorage());
    pages_->addWidget(makeExportFolder());
    pages_->addWidget(makeProfile());
    pages_->addWidget(makeReset());
    layout->addWidget(pages_, 1);
    feedback_ = plainLabel(this);
    layout->addWidget(feedback_);
    auto *footer = new QHBoxLayout;
    footer->addStretch();
    close_ = new QPushButton(this);
    cancel_ = new QPushButton(this);
    save_ = new QPushButton(this);
    retry_ = new QPushButton(this);
    for (auto *button : {close_, retry_, cancel_, save_}) {
        button->setAutoDefault(false);
        footer->addWidget(button);
    }
    layout->addLayout(footer);
    connect(close_, &QPushButton::clicked, this, [this] { if (!busy_) QDialog::reject(); });
    connect(cancel_, &QPushButton::clicked, this, [this] {
        if (pages_->currentIndex() == Network) readNetwork(network_);
        showOverview();
    });
    connect(retry_, &QPushButton::clicked, this, [this] {
        if (pages_->currentIndex() == Storage) send("loadStorage");
        else openPreferences(pages_->currentIndex());
    });
    connect(save_, &QPushButton::clicked, this, [this] {
        switch (pages_->currentIndex()) {
        case Network:
            validationTimer_->stop();
            send("saveNetwork", networkDraft(true));
            break;
        case Privacy:
            send("savePrivacy", {{"read", readReceipts_->isChecked()}, {"online", online_->isChecked()},
                {"typing", typing_->isChecked()}, {"autoDownload", autoDownload_->isChecked()}});
            break;
        case Notifications:
            send("saveNotifications", {{"alerts", alerts_->isChecked()}, {"sound", alertSound_->isChecked()}});
            break;
        case Profile:
            send("saveProfile", {{"nickname", profileName_->text()}, {"removeAvatar", profileRemoveAvatar_}});
            break;
        }
    });
    connect(this, &QDialog::finished, this, [this] { send("close"); reset(); });
    auto *find = new QShortcut(QKeySequence(QKeySequence::Find), this);
    connect(find, &QShortcut::activated, this, [this] {
        if (busy_) return;
        showOverview();
        search_->setFocus();
        search_->selectAll();
    });
}

QWidget *SettingsWindow::makeOverview() {
    auto *root = new QWidget(pages_);
    auto *layout = new QVBoxLayout(root);
    layout->setContentsMargins(0, 0, 0, 0);
    search_ = new QLineEdit(root);
    search_->setClearButtonEnabled(true);
    connect(search_, &QLineEdit::textChanged, this, [this] { if (!applying_) filterTopics(true); });
    layout->addWidget(search_);
    auto *body = new QWidget(root);
    topicsLayout_ = new QVBoxLayout(body);
    topicsLayout_->setContentsMargins(2, 4, 2, 4);
    topicsLayout_->setSpacing(8);
    auto topic = [&](QWidget *row, const char *key, const char *terms, bool searchOnly = false) {
        topics_.append({row, QString::fromLatin1(key), QString::fromLatin1(terms), searchOnly});
        topicsLayout_->addWidget(row);
    };
    auto *profile = new QWidget(body);
    auto *profileLayout = new QVBoxLayout(profile);
    profileLayout->setContentsMargins(0, 5, 0, 5);
    alias_ = plainLabel(profile);
    auto nameFont = alias_->font();
    nameFont.setWeight(QFont::DemiBold);
    alias_->setFont(nameFont);
    profileLayout->addWidget(alias_);
    auto *idRow = new QHBoxLayout;
    identity_ = new QLineEdit(profile);
    identity_->setReadOnly(true);
    identity_->setMinimumWidth(0);
    idRow->addWidget(identity_, 1);
    copy_ = new QPushButton(profile);
    copy_->setAutoDefault(false);
    idRow->addWidget(copy_);
    connect(copy_, &QPushButton::clicked, this, [this] { send("copyIdentity"); });
    profileLayout->addLayout(idRow);
    topic(profile, "copyIdentity", "identity");

    auto link = [&](const char *label, const char *keywords, auto click, bool searchOnly = false) {
        auto *button = new QPushButton(body);
        button->setObjectName(QStringLiteral("settingsLink"));
        button->setAutoDefault(false);
        button->setMinimumHeight(34);
        connect(button, &QPushButton::clicked, this, click);
        topic(button, label, keywords, searchOnly);
    };
    link("profile", "profile", [this] { openPreferences(Profile); });
    link("network", "network", [this] { openNetwork(); });
    link("advanced", "advanced", [this] { openNetwork(advanced_); }, true);
    link("turn", "turn", [this] { openNetwork(turnHeading_); }, true);
    link("fingerprint", "certificate", [this] { openNetwork(fingerprint_); }, true);
    link("notifications", "notifications", [this] { openPreferences(Notifications); });
    link("privacy", "privacy", [this] { openPreferences(Privacy); });

    auto choice = [&](const char *label, const char *keywords) {
        auto *row = new QWidget(body);
        auto *form = new QFormLayout(row);
        form->setContentsMargins(0, 0, 0, 0);
        form->setRowWrapPolicy(QFormLayout::WrapLongRows);
        auto *name = plainLabel(row);
        auto *combo = new QComboBox(row);
        name->setBuddy(combo);
        form->addRow(name, combo);
        formLabels_.append({name, QString::fromLatin1(label)});
        connect(combo, &QComboBox::activated, this, [this, combo, label](int index) {
            send(label, QJsonObject{{"value", combo->itemData(index).toInt()}});
        });
        topic(row, label, keywords);
        return combo;
    };
    language_ = choice("language", "language");
    theme_ = choice("theme", "theme");
    size_ = choice("textSize", "size");
    tray_ = new QCheckBox(body);
    connect(tray_, &QCheckBox::clicked, this, [this](bool value) { send("tray", QJsonObject{{"value", value}}); });
    topic(tray_, "tray", "tray");
    link("storage", "storage", [this] { openPreferences(Storage); });
    link("exportFolder", "exportFolder", [this] { openPreferences(ExportFolder); });
    revocations_ = plainLabel(body);
    topicsLayout_->addWidget(revocations_);
    link("reset", "reset", [this] { openPreferences(Reset); });
    noResults_ = plainLabel(body);
    topicsLayout_->addWidget(noResults_);
    topicsLayout_->addStretch();
    overviewScroll_ = scrollFor(body, root);
    layout->addWidget(overviewScroll_, 1);
    return root;
}

QWidget *SettingsWindow::makeNetwork() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(10);
    auto newForm = [](QWidget *parent) {
        auto *form = new QFormLayout(parent);
        form->setRowWrapPolicy(QFormLayout::WrapLongRows);
        form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
        form->setContentsMargins(0, 0, 0, 0);
        form->setSpacing(8);
        return form;
    };
    auto *basic = new QWidget(body);
    auto *basicForm = newForm(basic);
    validationTimer_ = new QTimer(this);
    validationTimer_->setSingleShot(true);
    validationTimer_->setInterval(75);
    connect(validationTimer_, &QTimer::timeout, this, [this] { if (!busy_) send("validateNetwork", networkDraft(false)); });
    auto field = [&](QFormLayout *form, const char *key, bool validate = false) {
        auto *entry = new QLineEdit(form->parentWidget());
        auto *label = plainLabel(form->parentWidget());
        label->setBuddy(entry);
        form->addRow(label, entry);
        formLabels_.append({label, QString::fromLatin1(key)});
        if (validate) connect(entry, &QLineEdit::textChanged, this, [this] {
            if (applying_) return;
            valid_ = false;
            feedback_->hide();
            save_->setEnabled(false);
            validationTimer_->start();
        });
        return entry;
    };
    server_ = field(basicForm, "server", true);
    port_ = field(basicForm, "port", true);
    layout->addWidget(basic);
    advanced_ = new QToolButton(body);
    advanced_->setCheckable(true);
    advanced_->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    advanced_->setArrowType(Qt::RightArrow);
    layout->addWidget(advanced_, 0, Qt::AlignLeft);
    advancedFields_ = new QWidget(body);
    auto *advancedLayout = newForm(advancedFields_);
    turnHeading_ = plainLabel(advancedFields_);
    auto turnFont = turnHeading_->font();
    turnFont.setWeight(QFont::DemiBold);
    turnHeading_->setFont(turnFont);
    advancedLayout->addRow(turnHeading_);
    turnServer_ = field(advancedLayout, "turnServer", true);
    turnPort_ = field(advancedLayout, "turnPort", true);
    turnUser_ = field(advancedLayout, "turnUser");
    turnPassword_ = field(advancedLayout, "turnPassword");
    turnPassword_->setEchoMode(QLineEdit::Password);
    keepPassword_ = new QCheckBox(advancedFields_);
    connect(keepPassword_, &QCheckBox::toggled, this, [this](bool keep) {
        turnPassword_->setEnabled(!keep);
        if (keep) turnPassword_->setText({});
    });
    advancedLayout->addRow(keepPassword_);
    turnTls_ = new QCheckBox(advancedFields_);
    advancedLayout->addRow(turnTls_);
    fingerprint_ = field(advancedLayout, "fingerprint", true);
    developmentCertificate_ = new QCheckBox(advancedFields_);
    advancedLayout->addRow(developmentCertificate_);
    layout->addWidget(advancedFields_);
    layout->addStretch();
    advancedFields_->hide();
    connect(advanced_, &QToolButton::toggled, this, [this](bool open) {
        advancedFields_->setVisible(open);
        advanced_->setArrowType(open ? Qt::DownArrow : Qt::RightArrow);
    });
    networkScroll_ = scrollFor(body, pages_);
    return networkScroll_;
}

QWidget *SettingsWindow::makePrivacy() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(14);
    readReceipts_ = new QCheckBox(body);
    readNote_ = plainLabel(body);
    online_ = new QCheckBox(body);
    typing_ = new QCheckBox(body);
    activityNote_ = plainLabel(body);
    autoDownload_ = new QCheckBox(body);
    for (auto *row : QList<QWidget *>{readReceipts_, readNote_, online_, typing_, activityNote_, autoDownload_})
        layout->addWidget(row);
    layout->addStretch();
    return scrollFor(body, pages_);
}

QWidget *SettingsWindow::makeNotifications() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(14);
    alerts_ = new QCheckBox(body);
    alertSound_ = new QCheckBox(body);
    systemNotifications_ = new QPushButton(body);
    systemNotifications_->setAutoDefault(false);
    layout->addWidget(alerts_);
    layout->addWidget(alertSound_);
    layout->addWidget(systemNotifications_, 0, Qt::AlignLeft);
    layout->addStretch();
    connect(alerts_, &QCheckBox::toggled, this, [this](bool on) { alertSound_->setEnabled(on && preferencesLoaded_); });
    connect(systemNotifications_, &QPushButton::clicked, this, [this] { send("systemNotifications"); });
    return scrollFor(body, pages_);
}

QWidget *SettingsWindow::makeStorage() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(12);
    storageSummary_ = plainLabel(body);
    layout->addWidget(storageSummary_);
    storageCategories_ = new QWidget(body);
    storageCategoriesLayout_ = new QVBoxLayout(storageCategories_);
    storageCategoriesLayout_->setContentsMargins(0, 0, 0, 0);
    layout->addWidget(storageCategories_);
    clearCache_ = new QPushButton(body);
    clearCache_->setAutoDefault(false);
    layout->addWidget(clearCache_, 0, Qt::AlignLeft);
    connect(clearCache_, &QPushButton::clicked, this, [this] { setCacheConfirmation(true); });

    cacheConfirmation_ = new QWidget(body);
    auto *confirmation = new QVBoxLayout(cacheConfirmation_);
    confirmation->setContentsMargins(0, 4, 0, 8);
    cacheConfirmTitle_ = plainLabel(cacheConfirmation_);
    auto titleFont = cacheConfirmTitle_->font();
    titleFont.setWeight(QFont::DemiBold);
    cacheConfirmTitle_->setFont(titleFont);
    cacheConfirmSize_ = plainLabel(cacheConfirmation_);
    cacheConfirmWarning_ = plainLabel(cacheConfirmation_);
    for (auto *label : {cacheConfirmTitle_, cacheConfirmSize_, cacheConfirmWarning_}) confirmation->addWidget(label);
    auto *actions = new QHBoxLayout;
    cancelCache_ = new QPushButton(cacheConfirmation_);
    confirmCache_ = new QPushButton(cacheConfirmation_);
    for (auto *button : {cancelCache_, confirmCache_}) {
        button->setAutoDefault(false);
        actions->addWidget(button);
    }
    actions->addStretch();
    confirmation->addLayout(actions);
    layout->addWidget(cacheConfirmation_);
    connect(cancelCache_, &QPushButton::clicked, this, [this] {
        setCacheConfirmation(false);
        clearCache_->setFocus();
    });
    connect(confirmCache_, &QPushButton::clicked, this, [this] {
        QJsonArray categories;
        for (const auto &category : cacheCategories_)
            if (!category.check->isHidden() && category.check->isChecked()) categories.append(category.value);
        if (!categories.isEmpty()) send("clearCache", {{"categories", categories}});
        setCacheConfirmation(false);
    });

    auto *limitTitle = plainLabel(body);
    storageLimit_ = new QComboBox(body);
    limitTitle->setBuddy(storageLimit_);
    formLabels_.append({limitTitle, QStringLiteral("cacheLimit")});
    layout->addWidget(limitTitle);
    layout->addWidget(storageLimit_, 0, Qt::AlignLeft);
    connect(storageLimit_, &QComboBox::activated, this, [this](int index) {
        const auto limit = storageLimit_->itemData(index).toLongLong();
        if (limit != storedCacheLimit_ && !send("cacheLimit", {{"limit", limit}}))
            storageLimit_->setCurrentIndex(storageLimit_->findData(storedCacheLimit_));
    });
    storageLimitNote_ = plainLabel(body);
    layout->addWidget(storageLimitNote_);
    storageBudgetMeter_ = new QProgressBar(body);
    storageBudgetMeter_->setRange(0, 100);
    storageBudgetMeter_->setTextVisible(false);
    layout->addWidget(storageBudgetMeter_);
    storageBudget_ = plainLabel(body);
    layout->addWidget(storageBudget_);
    storagePending_ = plainLabel(body);
    layout->addWidget(storagePending_);
    layout->addStretch();

    storageRefresh_ = new QTimer(this);
    storageRefresh_->setSingleShot(true);
    storageRefresh_->setInterval(2000);
    connect(storageRefresh_, &QTimer::timeout, this, [this] {
        if (pages_->currentIndex() != Storage) return;
        if (busy_ || cacheConfirming_) { storageRefresh_->start(); return; }
        send("refreshStorage");
    });
    return scrollFor(body, pages_);
}

QWidget *SettingsWindow::makeExportFolder() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(12);
    exportFolder_ = new QLineEdit(body);
    exportFolder_->setReadOnly(true);
    layout->addWidget(exportFolder_);
    chooseFolder_ = new QPushButton(body);
    defaultFolder_ = new QPushButton(body);
    for (auto *button : {chooseFolder_, defaultFolder_}) {
        button->setAutoDefault(false);
        layout->addWidget(button, 0, Qt::AlignLeft);
    }
    connect(chooseFolder_, &QPushButton::clicked, this, [this] { chooseFile(true); });
    connect(defaultFolder_, &QPushButton::clicked, this, [this] {
        send("saveExportFolder", {{"folder", QJsonValue::Null}});
    });
    layout->addStretch();
    return scrollFor(body, pages_);
}

QWidget *SettingsWindow::makeProfile() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(12);
    profileAvatar_ = plainLabel(body);
    profileAvatar_->setFixedSize(92, 92);
    profileAvatar_->setScaledContents(true);
    layout->addWidget(profileAvatar_, 0, Qt::AlignHCenter);
    auto *actions = new QHBoxLayout;
    actions->addStretch();
    chooseAvatar_ = new QPushButton(body);
    removeAvatar_ = new QPushButton(body);
    for (auto *button : {chooseAvatar_, removeAvatar_}) {
        button->setAutoDefault(false);
        actions->addWidget(button);
    }
    actions->addStretch();
    layout->addLayout(actions);
    connect(chooseAvatar_, &QPushButton::clicked, this, [this] { chooseFile(false); });
    connect(removeAvatar_, &QPushButton::clicked, this, [this] {
        if (!send("discardProfileAvatar")) return;
        profileAvatar_->clear();
        profileHasAvatar_ = false;
        profileRemoveAvatar_ = true;
        feedback_->clear();
        feedback_->hide();
        updateState();
        chooseAvatar_->setFocus();
    });
    auto *name = plainLabel(body);
    profileName_ = new QLineEdit(body);
    profileName_->setMaxLength(256);
    name->setBuddy(profileName_);
    formLabels_.append({name, QStringLiteral("nickname")});
    layout->addWidget(name);
    layout->addWidget(profileName_);
    profileNameError_ = plainLabel(body);
    layout->addWidget(profileNameError_);
    layout->addStretch();
    connect(profileName_, &QLineEdit::textChanged, this, [this] {
        if (applying_) return;
        feedback_->clear();
        feedback_->hide();
        updateState();
    });
    return scrollFor(body, pages_);
}

QWidget *SettingsWindow::makeReset() {
    auto *body = new QWidget(pages_);
    auto *layout = new QVBoxLayout(body);
    layout->setContentsMargins(2, 6, 2, 6);
    layout->setSpacing(14);
    resetWarning_ = plainLabel(body);
    layout->addWidget(resetWarning_);
    confirmReset_ = new QPushButton(body);
    confirmReset_->setAutoDefault(false);
    layout->addWidget(confirmReset_, 0, Qt::AlignLeft);
    connect(confirmReset_, &QPushButton::clicked, this, [this] { send("resetIdentity"); });
    layout->addStretch();
    return scrollFor(body, pages_);
}

void SettingsWindow::readStorage(const QJsonObject &data) {
    storageSummary_->setText(data.value("summary").toString());
    for (auto &category : cacheCategories_) category.bytes = 0;
    for (const auto &row : data.value("categories").toArray()) {
        const auto record = row.toObject();
        const auto value = record.value("value").toInt();
        auto item = std::find_if(cacheCategories_.begin(), cacheCategories_.end(),
            [value](const auto &category) { return category.value == value; });
        if (item == cacheCategories_.end()) {
            auto *check = new QCheckBox(storageCategories_);
            connect(check, &QCheckBox::toggled, this, [this] { if (!applying_) updateState(); });
            QList<CacheCategory>::iterator position = std::find_if(cacheCategories_.begin(), cacheCategories_.end(),
                [value](const auto &category) { return category.value > value; });
            const auto index = position - cacheCategories_.begin();
            item = cacheCategories_.insert(position, CacheCategory{value, 0, check});
            storageCategoriesLayout_->insertWidget(int(index), check);
            check->setChecked(true);
        }
        item->bytes = record.value("bytes").toInteger();
        item->check->setText(record.value("title").toString());
    }
    for (const auto &category : cacheCategories_) category.check->setVisible(category.bytes > 0);
    storedCacheLimit_ = data.value("limit").toInteger();
    if (storageLimit_->count() == 0) {
        for (const auto &row : data.value("limits").toArray()) {
            const auto limit = row.toObject();
            storageLimit_->addItem(limit.value("title").toString(), limit.value("value").toInteger());
        }
    }
    storageLimit_->setCurrentIndex(storageLimit_->findData(storedCacheLimit_));
    storageBudget_->setText(data.value("budget").toString());
    storageBudgetMeter_->setAccessibleName(storageBudget_->text());
    storageBudgetMeter_->setValue(int(data.value("budgetUsed").toDouble() * storageBudgetMeter_->maximum()));
    storagePending_->setText(data.value("pending").toString());
    if (!storagePending_->text().isEmpty() && !storageRefresh_->isActive()) storageRefresh_->start();
}

void SettingsWindow::setCacheConfirmation(bool confirming) {
    cacheConfirming_ = confirming;
    if (confirming) {
        qint64 bytes = 0;
        for (const auto &category : cacheCategories_)
            if (!category.check->isHidden() && category.check->isChecked()) bytes += category.bytes;
        const auto size = bytes < 1000000 ? QLocale().toString(bytes / 1000.0, 'f', 1) + QStringLiteral(" KB") :
            bytes < 1000000000 ? QLocale().toString(bytes / 1000000.0, 'f', 1) + QStringLiteral(" MB") :
            QLocale().toString(bytes / 1000000000.0, 'f', 1) + QStringLiteral(" GB");
        cacheConfirmSize_->setText(labels_.value("clearSelected").toString().replace(QLatin1String("{0}"), size));
    } else {
        cacheConfirmSize_->clear();
    }
    updateState();
    if (confirming) cancelCache_->setFocus();
}

void SettingsWindow::chooseFile(bool directory) {
    if (busy_ || !preferencesLoaded_ || folderPicker_) return;
    const auto page = directory ? ExportFolder : Profile;
    if (pages_->currentIndex() != page) return;
    const auto label = directory ? "exportChoose" : profileHasAvatar_ ? "changeAvatar" : "chooseAvatar";
    auto *picker = new QFileDialog(this, labels_.value(QLatin1String(label)).toString(),
        directory ? QDir::fromNativeSeparators(storedFolder_) : QString());
    folderPicker_ = picker;
    picker->setFileMode(directory ? QFileDialog::Directory : QFileDialog::ExistingFile);
    if (directory) picker->setOption(QFileDialog::ShowDirsOnly);
    else picker->setNameFilter(labels_.value("avatar").toString() + QStringLiteral(" (*.png *.jpg *.jpeg *.bmp *.webp)"));
    const auto id = id_;
    connect(picker, &QFileDialog::fileSelected, this, [this, id, page](const QString &path) {
        if (id_ != id || pages_->currentIndex() != page || !isVisible()) return;
        const auto localPath = QDir::toNativeSeparators(path);
        if (page == ExportFolder) send("saveExportFolder", {{"folder", localPath}});
        else send("cropProfileAvatar", {{"path", localPath}});
    });
    connect(picker, &QDialog::finished, this, [this, picker] {
        if (folderPicker_ == picker) folderPicker_.clear();
        picker->deleteLater();
    });
    picker->open();
}

void SettingsWindow::applyLabels() {
    const auto label = [this](const char *key) { return labels_.value(QLatin1String(key)).toString(); };
    setWindowTitle(label("title"));
    search_->setAccessibleName(label("search"));
    search_->setPlaceholderText(label("search"));
    identity_->setAccessibleName(label("identity"));
    copy_->setText(label("copyIdentity"));
    close_->setText(label("close"));
    back_->setText(label("back"));
    cancel_->setText(label("cancel"));
    save_->setText(label("save"));
    retry_->setText(label("retry"));
    tray_->setText(label("tray"));
    noResults_->setText(label("empty"));
    advanced_->setText(label("advanced"));
    turnHeading_->setText(label("turn"));
    turnTls_->setText(label("turnTls"));
    developmentCertificate_->setText(label("developmentCertificate"));
    keepPassword_->setText(label("keepPassword"));
    readReceipts_->setText(label("readReceipts"));
    readNote_->setText(label("readNote"));
    online_->setText(label("online"));
    typing_->setText(label("typing"));
    activityNote_->setText(label("activityNote"));
    autoDownload_->setText(label("autoDownload"));
    alerts_->setText(label("alerts"));
    alertSound_->setText(label("alertSound"));
    systemNotifications_->setText(label("systemNotifications"));
    storageLimitNote_->setText(label("cacheLimitNote"));
    clearCache_->setText(label("clearCache"));
    cacheConfirmTitle_->setText(label("clearTitle"));
    cacheConfirmWarning_->setText(label("clearWarning"));
    confirmCache_->setText(label("confirm"));
    cancelCache_->setText(label("cancel"));
    exportFolder_->setAccessibleName(label("exportFolder"));
    chooseFolder_->setText(label("exportChoose"));
    defaultFolder_->setText(label("exportDefault"));
    profileAvatar_->setAccessibleName(label("avatar"));
    profileNameError_->setText(label("nicknameTooLong"));
    removeAvatar_->setText(label("removeAvatar"));
    resetWarning_->setText(label("resetWarning"));
    confirmReset_->setText(label("reset"));
    for (const auto &entry : formLabels_) entry.first->setText(labels_.value(entry.second).toString());
    for (const auto &topic : topics_)
        if (auto *button = qobject_cast<QPushButton *>(topic.row)) button->setText(labels_.value(topic.key).toString());
    updateState();
}

void SettingsWindow::filterTopics(bool resetScroll) {
    static const QRegularExpression whitespace(QStringLiteral("\\s+"));
    const auto words = search_->text().split(whitespace, Qt::SkipEmptyParts);
    bool any = false;
    for (const auto &topic : topics_) {
        const auto index = labels_.value(topic.key).toString() + QLatin1Char(' ') + terms_.value(topic.terms).toString();
        const bool visible = (!topic.searchOnly || !words.isEmpty()) && std::all_of(words.begin(), words.end(),
            [&](const QString &word) { return index.contains(word, Qt::CaseInsensitive); });
        topic.row->setVisible(visible);
        any |= visible;
    }
    noResults_->setVisible(!any);
    revocations_->setVisible(words.isEmpty() && !revocations_->text().isEmpty());
    if (resetScroll) overviewScroll_->verticalScrollBar()->setValue(0);
}

void SettingsWindow::present(const QJsonObject &state) {
    if (state.value("dismiss").toBool()) { reset(); return; }
    const auto id = state.value("id").toInteger();
    if (id <= 0) return;
    const bool first = state.value("open").toBool();
    if (first) { reset(); id_ = id; }
    if (id_ != id) return;
    const QScopedValueRollback<bool> guard(applying_, true);
    busy_ = state.value("busy").toBool();
    valid_ = state.value("valid").toBool();
    if (state.value("overview").isObject()) {
        const auto overview = state.value("overview").toObject();
        labels_ = overview.value("labels").toObject();
        terms_ = overview.value("terms").toObject();
        alias_->setText(overview.value("alias").toString());
        identity_->setText(overview.value("identity").toString());
        revocations_->setText(overview.value("revocations").toString());
        tray_->setChecked(overview.value("tray").toBool());
        auto choices = [](QComboBox *box, const QJsonValue &rows, int selected) {
            box->clear();
            for (const auto &row : rows.toArray()) {
                const auto entry = row.toObject();
                box->addItem(entry.value("title").toString(), entry.value("value").toInt());
            }
            box->setCurrentIndex(box->findData(selected));
        };
        choices(theme_, overview.value("themes"), overview.value("theme").toInt());
        choices(language_, overview.value("languages"), overview.value("language").toInt());
        choices(size_, overview.value("sizes"), overview.value("size").toInt());
        applyLabels();
        filterTopics(false);
    }
    if (state.value("network").isObject()) {
        network_ = state.value("network").toObject();
        readNetwork(network_);
    }
    const auto page = pages_->currentIndex();
    if (state.value("preferences").isObject()) {
        const auto data = state.value("preferences").toObject();
        const auto type = data.value("page").toString();
        if (page == Privacy && type == QLatin1String("privacy")) {
            preferencesLoaded_ = true;
            readReceipts_->setChecked(data.value("read").toBool());
            online_->setChecked(data.value("online").toBool());
            typing_->setChecked(data.value("typing").toBool());
            autoDownload_->setChecked(data.value("autoDownload").toBool());
        } else if (page == Notifications && type == QLatin1String("notifications")) {
            preferencesLoaded_ = true;
            alerts_->setChecked(data.value("alerts").toBool());
            alertSound_->setChecked(data.value("sound").toBool());
        } else if (page == Storage && type == QLatin1String("storage")) {
            preferencesLoaded_ = true;
            storageLoadFailed_ = false;
            readStorage(data);
        } else if (page == ExportFolder && type == QLatin1String("exportFolder")) {
            preferencesLoaded_ = true;
            storedFolder_ = data.value("folder").toString();
            exportFolder_->setText(storedFolder_.isEmpty() ? labels_.value("exportDefault").toString() : storedFolder_);
            exportFolder_->setCursorPosition(0);
        } else if (page == Profile && type == QLatin1String("profile")) {
            preferencesLoaded_ = true;
            profileName_->setText(data.value("nickname").toString());
            profileHasAvatar_ = data.value("hasAvatar").toBool();
            profileRemoveAvatar_ = false;
            profileName_->setFocus();
        }
    }
    if (page == Profile && state.value("profileImage").isString()) {
        auto bytes = QByteArray::fromBase64(state.value("profileImage").toString().toLatin1());
        QPixmap photo;
        const bool loaded = photo.loadFromData(bytes);
        bytes.fill(0);
        if (loaded) profileAvatar_->setPixmap(photo);
        if (state.value("completed").toString() == QLatin1String("cropProfileAvatar")) {
            profileHasAvatar_ = true;
            profileRemoveAvatar_ = false;
        }
    }
    feedback_->setText(state.value("message").toString());
    feedback_->setVisible(!feedback_->text().isEmpty());
    if (page == Storage) {
        const auto completed = state.value("completed").toString();
        if (!feedback_->text().isEmpty()) {
            if (completed == QLatin1String("cacheLimit"))
                storageLimit_->setCurrentIndex(storageLimit_->findData(storedCacheLimit_));
            if (completed == QLatin1String("loadStorage") || completed == QLatin1String("refreshStorage") ||
                completed == QLatin1String("cacheLimit") || completed == QLatin1String("clearCache"))
                storageLoadFailed_ = true;
        }
        if (state.value("storageChanged").toBool() && !storageRefresh_->isActive()) storageRefresh_->start();
    }
    if (state.value("saved").toBool()) showOverview();
    updateState();
    if (first) {
        excludeWindowFromCapture(this);
        open();
        search_->setFocus();
    }
}

void SettingsWindow::readNetwork(const QJsonObject &data) {
    const QScopedValueRollback<bool> guard(applying_, true);
    server_->setText(data.value("server").toString());
    port_->setText(data.value("port").toString());
    turnServer_->setText(data.value("turnServer").toString());
    turnPort_->setText(data.value("turnPort").toString());
    turnUser_->setText(data.value("turnUser").toString());
    fingerprint_->setText(data.value("fingerprint").toString());
    turnPassword_->setText({});
    const bool hasPassword = data.value("hasPassword").toBool();
    keepPassword_->setVisible(hasPassword);
    keepPassword_->setChecked(hasPassword);
    turnPassword_->setEnabled(!hasPassword);
    turnTls_->setChecked(data.value("turnTls").toBool());
    developmentCertificate_->setChecked(data.value("developmentCertificate").toBool());
}

void SettingsWindow::openNetwork(QWidget *target) {
    if (busy_) return;
    pages_->setCurrentIndex(Network);
    updateState();
    send("validateNetwork", networkDraft(false));
    if (!target) { server_->setFocus(); return; }
    advanced_->setChecked(true);
    QTimer::singleShot(0, this, [this, target] {
        if (pages_->currentIndex() != Network) return;
        networkScroll_->ensureWidgetVisible(target);
        target->setFocus();
    });
}

void SettingsWindow::openPreferences(int page) {
    if (busy_) return;
    preferencesLoaded_ = false;
    feedback_->clear();
    feedback_->hide();
    for (auto *box : {readReceipts_, online_, typing_, autoDownload_, alerts_, alertSound_}) box->setChecked(false);
    if (page == Storage) {
        storageLoadFailed_ = false;
        storageLimit_->clear();
        storageSummary_->clear();
        storageBudget_->clear();
        storagePending_->clear();
        for (auto &category : cacheCategories_) {
            category.bytes = 0;
            category.check->hide();
            category.check->setChecked(true);
        }
    }
    pages_->setCurrentIndex(page);
    updateState();
    switch (page) {
    case Privacy: send("loadPrivacy"); break;
    case Notifications: send("loadNotifications"); break;
    case Storage: send("loadStorage"); break;
    case ExportFolder: send("loadExportFolder"); break;
    case Profile: send("loadProfile"); break;
    case Reset: back_->setFocus(); break;
    }
}

void SettingsWindow::showOverview() {
    if (!applying_) {
        if (pages_->currentIndex() == Profile) send("discardProfileAvatar");
        else if (pages_->currentIndex() == Storage) send("leaveStorage");
    }
    pages_->setCurrentIndex(Overview);
    validationTimer_->stop();
    storageRefresh_->stop();
    cacheConfirming_ = false;
    cacheConfirmSize_->clear();
    storedFolder_.clear();
    exportFolder_->clear();
    profileHasAvatar_ = profileRemoveAvatar_ = false;
    profileName_->clear();
    profileAvatar_->clear();
    feedback_->clear();
    feedback_->hide();
    updateState();
}

void SettingsWindow::updateState() {
    const auto page = pages_->currentIndex();
    const bool overview = page == Overview;
    const bool network = page == Network;
    const bool preferences = page == Privacy || page == Notifications;
    const char *title;
    switch (page) {
    case Network: title = "network"; break;
    case Privacy: title = "privacy"; break;
    case Notifications: title = "notifications"; break;
    case Storage: title = "storage"; break;
    case ExportFolder: title = "exportFolder"; break;
    case Profile: title = "profile"; break;
    case Reset: title = "reset"; break;
    default: title = "title"; break;
    }
    heading_->setText(labels_.value(QLatin1String(title)).toString());
    const bool profile = page == Profile;
    const bool hasSave = network || preferences || profile;
    const auto nickname = profile ? profileName_->text().trimmed() : QString();
    const bool nicknameTooLong = profile && nickname.toUtf8().size() > 256;
    back_->setVisible(!overview);
    close_->setVisible(overview);
    save_->setVisible(hasSave);
    cancel_->setVisible(hasSave);
    retry_->setVisible(!busy_ && (((preferences || profile) && !preferencesLoaded_) || (page == Storage && storageLoadFailed_)));
    back_->setEnabled(!busy_);
    close_->setEnabled(!busy_);
    cancel_->setEnabled(!busy_);
    const bool canSave = network ? valid_ : preferencesLoaded_ && (preferences || (profile && !nickname.isEmpty() && !nicknameTooLong));
    save_->setEnabled(!busy_ && canSave);
    save_->setDefault(save_->isEnabled());
    pages_->setEnabled(!busy_);
    for (auto *box : {readReceipts_, online_, typing_, autoDownload_, alerts_}) box->setEnabled(preferencesLoaded_);
    alertSound_->setEnabled(preferencesLoaded_ && alerts_->isChecked());
    const bool storageReady = page == Storage && preferencesLoaded_ && !storageLoadFailed_;
    const bool selected = std::any_of(cacheCategories_.cbegin(), cacheCategories_.cend(),
        [](const auto &category) { return category.bytes > 0 && category.check->isChecked(); });
    cacheConfirmation_->setVisible(cacheConfirming_);
    storageCategories_->setEnabled(storageReady && !cacheConfirming_);
    clearCache_->setVisible(!cacheConfirming_);
    clearCache_->setEnabled(storageReady && selected);
    storageLimit_->setEnabled(storageReady && !cacheConfirming_);
    storageBudget_->setVisible(!storageBudget_->text().isEmpty());
    storageBudgetMeter_->setVisible(!storageBudget_->text().isEmpty());
    storagePending_->setVisible(!storagePending_->text().isEmpty());
    const bool exportReady = page == ExportFolder && preferencesLoaded_;
    chooseFolder_->setEnabled(exportReady);
    defaultFolder_->setEnabled(exportReady && !storedFolder_.isEmpty());
    const bool profileReady = profile && preferencesLoaded_;
    profileName_->setEnabled(profileReady);
    profileNameError_->setVisible(profileReady && nicknameTooLong);
    chooseAvatar_->setText(labels_.value(profileHasAvatar_ ? "changeAvatar" : "chooseAvatar").toString());
    chooseAvatar_->setEnabled(profileReady);
    removeAvatar_->setVisible(profileHasAvatar_);
    removeAvatar_->setEnabled(profileReady);
    profileAvatar_->setVisible(profileHasAvatar_ && !profileAvatar_->pixmap().isNull());
}

QJsonObject SettingsWindow::networkDraft(bool includePassword) const {
    auto draft = QJsonObject{{"server", server_->text()}, {"port", port_->text()},
        {"turnServer", turnServer_->text()}, {"turnPort", turnPort_->text()}, {"fingerprint", fingerprint_->text()}};
    if (includePassword) {
        draft.insert("turnUser", turnUser_->text());
        draft.insert("turnPassword", turnPassword_->text());
        draft.insert("keepPassword", keepPassword_->isChecked());
        draft.insert("turnTls", turnTls_->isChecked());
        draft.insert("developmentCertificate", developmentCertificate_->isChecked());
    }
    return draft;
}

bool SettingsWindow::send(const char *command, const QJsonObject &input) {
    if (id_ == 0 || !request) return false;
    auto state = input;
    state.insert("id", id_);
    state.insert("command", QString::fromLatin1(command));
    if (QLatin1String(command) != QLatin1String("close")) state.insert("hwnd", QString::number(qulonglong(winId()), 16));
    auto bytes = QJsonDocument(state).toJson(QJsonDocument::Compact);
    const bool accepted = request(bytes);
    bytes.fill(0);
    return accepted;
}

void SettingsWindow::reject() {
    if (busy_) return;
    if (cacheConfirming_) setCacheConfirmation(false);
    else if (pages_->currentIndex() != Overview) showOverview();
    else if (!search_->text().isEmpty()) { search_->clear(); search_->setFocus(); }
    else QDialog::reject();
}

void SettingsWindow::closeEvent(QCloseEvent *event) {
    if (busy_) { event->ignore(); return; }
    event->accept();
    QDialog::reject();
}

void SettingsWindow::reset() {
    id_ = 0;
    if (folderPicker_) static_cast<QDialog *>(folderPicker_.data())->reject();
    hide();
    const QScopedValueRollback<bool> guard(applying_, true);
    validationTimer_->stop();
    busy_ = valid_ = false;
    preferencesLoaded_ = false;
    storageLoadFailed_ = false;
    storageSummary_->clear();
    storageBudget_->clear();
    storagePending_->clear();
    storageLimit_->clear();
    for (auto &category : cacheCategories_) {
        category.bytes = 0;
        category.check->hide();
        category.check->setText({});
    }
    for (auto *box : {readReceipts_, online_, typing_, autoDownload_, alerts_, alertSound_}) box->setChecked(false);
    network_ = {};
    labels_ = {};
    terms_ = {};
    identity_->setText({});
    alias_->clear();
    revocations_->clear();
    search_->setText({});
    advanced_->setChecked(false);
    readNetwork({});
    showOverview();
}

} // namespace MsgAT
