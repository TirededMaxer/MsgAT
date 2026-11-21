// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QDialog>
#include <QJsonObject>
#include <QList>
#include <QPointer>
#include <functional>

class QCheckBox;
class QComboBox;
class QFileDialog;
class QFormLayout;
class QLabel;
class QLineEdit;
class QProgressBar;
class QPushButton;
class QScrollArea;
class QStackedWidget;
class QTimer;
class QToolButton;
class QVBoxLayout;

namespace MsgAT {

class SettingsWindow final : public QDialog {
public:
    explicit SettingsWindow(QWidget *parent);
    void present(const QJsonObject &state);
    void reset();
    std::function<bool(const QByteArray &)> request;
protected:
    void reject() override;
    void closeEvent(QCloseEvent *event) override;
private:
    struct Topic { QWidget *row; QString key; QString terms; bool searchOnly; };
    struct CacheCategory { int value; qint64 bytes; QCheckBox *check; };

    QWidget *makeOverview();
    QWidget *makeNetwork();
    QWidget *makePrivacy();
    QWidget *makeNotifications();
    QWidget *makeStorage();
    QWidget *makeExportFolder();
    QWidget *makeProfile();
    QWidget *makeReset();
    void applyLabels();
    void filterTopics(bool resetScroll);
    void readNetwork(const QJsonObject &network);
    void openNetwork(QWidget *target = nullptr);
    void openPreferences(int page);
    void readStorage(const QJsonObject &data);
    void setCacheConfirmation(bool confirming);
    void chooseFile(bool directory);
    void showOverview();
    void updateState();
    bool send(const char *command, const QJsonObject &input = {});
    QJsonObject networkDraft(bool password) const;

    QStackedWidget *pages_;
    QVBoxLayout *topicsLayout_;
    QScrollArea *overviewScroll_, *networkScroll_;
    QLabel *heading_, *alias_, *noResults_, *revocations_, *turnHeading_, *feedback_, *activityNote_, *readNote_;
    QPushButton *copy_, *close_, *back_, *cancel_, *save_, *retry_, *systemNotifications_;
    QLineEdit *search_, *identity_, *server_, *port_, *turnServer_, *turnPort_, *turnUser_, *turnPassword_, *fingerprint_;
    QComboBox *theme_, *size_, *language_;
    QCheckBox *tray_, *turnTls_, *developmentCertificate_, *keepPassword_;
    QCheckBox *readReceipts_, *online_, *typing_, *autoDownload_, *alerts_, *alertSound_;
    QToolButton *advanced_;
    QWidget *advancedFields_;
    QList<Topic> topics_;
    QList<QPair<QLabel *, QString>> formLabels_;
    QJsonObject labels_, terms_, network_;
    QTimer *validationTimer_;
    QTimer *storageRefresh_;
    QLabel *storageSummary_, *storageBudget_, *storagePending_, *storageLimitNote_, *cacheConfirmTitle_, *cacheConfirmSize_, *cacheConfirmWarning_;
    QComboBox *storageLimit_;
    QProgressBar *storageBudgetMeter_;
    QPushButton *clearCache_, *confirmCache_, *cancelCache_, *chooseFolder_, *defaultFolder_;
    QWidget *cacheConfirmation_, *storageCategories_;
    QVBoxLayout *storageCategoriesLayout_;
    QLineEdit *exportFolder_;
    QLabel *profileAvatar_, *profileNameError_, *resetWarning_;
    QLineEdit *profileName_;
    QPushButton *chooseAvatar_, *removeAvatar_, *confirmReset_;
    QList<CacheCategory> cacheCategories_;
    QPointer<QFileDialog> folderPicker_;
    qint64 storedCacheLimit_ = 0;
    QString storedFolder_;
    qint64 id_ = 0;
    bool applying_ = false, busy_ = false, valid_ = false;
    bool preferencesLoaded_ = false;
    bool cacheConfirming_ = false, storageLoadFailed_ = false;
    bool profileHasAvatar_ = false, profileRemoveAvatar_ = false;
};

} // namespace MsgAT
