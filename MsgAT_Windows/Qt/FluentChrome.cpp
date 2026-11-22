// SPDX-License-Identifier: AGPL-3.0-only
#include "FluentChrome.h"
#include "ChatPalette.h"

#include <QAccessibilityHints>
#include <QApplication>
#include <QFontDatabase>
#include <QIconEngine>
#include <QPainter>
#include <QPushButton>
#include <QStyleHints>
#include <QToolButton>

namespace MsgAT {

const QString &fluentSymbolFamily() {
    static const QString family = QFontDatabase::hasFamily(QStringLiteral("Segoe Fluent Icons"))
        ? QStringLiteral("Segoe Fluent Icons") : QStringLiteral("Segoe MDL2 Assets");
    return family;
}

namespace {

class SymbolEngine final : public QIconEngine {
public:
    SymbolEngine(Symbol symbol, bool primary) : symbol_(symbol), primary_(primary) {}
    QIconEngine *clone() const override { return new SymbolEngine(symbol_, primary_); }
    void paint(QPainter *painter, const QRect &rect, QIcon::Mode mode, QIcon::State) override {
        const auto palette = QApplication::palette();
        const auto *hints = QApplication::styleHints();
        const bool contrast = hints->accessibility()->contrastPreference() == Qt::ContrastPreference::HighContrast;
        const auto group = mode == QIcon::Disabled ? QPalette::Disabled : QPalette::Active;
        const auto scheme = hints->colorScheme();
        const bool dark = scheme == Qt::ColorScheme::Dark ||
            (scheme == Qt::ColorScheme::Unknown && palette.color(QPalette::Window).lightness() < 128);
        const auto ink = contrast ? palette.color(group, QPalette::ButtonText)
            : primary_ && mode != QIcon::Disabled ? QColor(Qt::white)
            : dark ? QColor(0xe8ebef) : QColor(0x27313e);
        painter->save();
        painter->setRenderHint(QPainter::TextAntialiasing);
        QFont font(fluentSymbolFamily());
        font.setPixelSize(qMin(rect.width(), rect.height()));
        painter->setFont(font);
        painter->setPen(ink);
        if (!contrast && mode == QIcon::Disabled) painter->setOpacity(0.45);
        painter->drawText(rect, Qt::AlignCenter, QString(QChar(static_cast<ushort>(symbol_))));
        painter->restore();
    }
    QPixmap pixmap(const QSize &size, QIcon::Mode mode, QIcon::State state) override {
        QPixmap image(size);
        image.fill(Qt::transparent);
        QPainter painter(&image);
        paint(&painter, QRect(QPoint(), size), mode, state);
        return image;
    }
private:
    Symbol symbol_;
    bool primary_;
};

} // namespace

QIcon fluentIcon(Symbol symbol, bool primary) { return QIcon(new SymbolEngine(symbol, primary)); }

void setFluentButton(QToolButton *button, Symbol symbol, bool primary) {
    button->setIcon(fluentIcon(symbol, primary));
    button->setIconSize(QSize(20, 20));
    button->setToolButtonStyle(Qt::ToolButtonIconOnly);
    button->setFixedSize(36, 36);
    button->setCursor(Qt::PointingHandCursor);
    if (primary) button->setObjectName(QStringLiteral("primaryAction"));
}

void setFluentAction(QPushButton *button, Symbol symbol, bool lightInk) {
    button->setIcon(fluentIcon(symbol, lightInk));
    button->setIconSize(QSize(16, 16));
    button->setMinimumHeight(36);
}

void installFluentChrome(QApplication &app) {
    if (QFontDatabase::hasFamily(QStringLiteral("Segoe UI Variable Text"))) {
        auto font = app.font();
        font.setFamilies({QStringLiteral("Segoe UI Variable Text"), QStringLiteral("Microsoft YaHei UI"),
                          QStringLiteral("Segoe UI")});
        font.setPointSizeF(10.0);
        app.setFont(font);
    }
    const auto *hints = app.styleHints();
    auto refresh = [&app, hints] {
        if (hints->accessibility()->contrastPreference() == Qt::ContrastPreference::HighContrast) {
            app.setStyleSheet({});
            return;
        }
        auto sheet = QStringLiteral(R"(
            QToolButton { border: 1px solid transparent; border-radius: 8px; padding: 2px; color: palette(button-text); }
            QToolButton:hover { background: palette(alternate-base); }
            QToolButton:pressed, QToolButton:checked { background: palette(highlight); }
            QToolButton:focus { border-color: palette(accent); }
            QToolButton[optionsActive="true"] { background: palette(highlight); }
            QToolButton::menu-indicator { image: none; width: 0; }
            QToolButton#primaryAction { background: #006650; color: white; }
            QToolButton#primaryAction:hover { background: #087862; }
            QToolButton#primaryAction:pressed { background: #005140; }
            QToolButton#primaryAction:disabled { background: palette(alternate-base); }
            QToolButton#searchPill { padding: 5px 10px; text-align: left; background: palette(base); border-color: palette(mid); }
            QToolButton#searchPill:hover { background: palette(alternate-base); }
            QToolButton#searchPill:focus { border-color: palette(accent); }
            QFrame#composerCard { background: palette(base); border: 1px solid palette(mid); border-radius: 12px; }
            QFrame#chatHeader { background: palette(base); border: 0; border-bottom: 1px solid palette(mid); }
            QFrame#workspaceRail { background: @rail; border: 0; border-right: 1px solid palette(mid); }
            QListView#messageTimeline { background: @timeline; }
            QListView#conversationList { background: @conversations; }
            QLabel#chatAvatar { background: palette(button); color: palette(button-text); border-radius: 19px; }
            QLabel#callAvatar { background: palette(button); color: palette(button-text); border-radius: 22px; }
            QPlainTextEdit#chatInput { background: transparent; color: palette(text); border: 0; }
            QLineEdit, QComboBox, QSpinBox { background: palette(base); color: palette(text); border: 1px solid palette(mid);
                border-radius: 7px; min-height: 26px; padding: 2px 8px; selection-background-color: palette(highlight);
                selection-color: palette(highlighted-text); }
            QLineEdit:focus, QComboBox:focus, QSpinBox:focus { border-color: palette(accent); }
            QPushButton { padding: 6px 14px; border: 1px solid palette(mid); border-radius: 7px; background: palette(button); }
            QPushButton:hover { background: palette(alternate-base); }
            QPushButton:focus { border-color: palette(accent); }
            QPushButton:disabled { color: palette(placeholder-text); }
            QPushButton#fluentPrimary { background: #006650; color: white; border-color: #006650; }
            QPushButton#fluentPrimary:hover { background: #067862; }
            QPushButton#fluentPrimary:disabled { background: palette(button); color: palette(placeholder-text); border-color: palette(mid); }
            QPushButton#fluentDestructive { background: #aa2940; color: white; border-color: #aa2940; }
            QPushButton#fluentDestructive:hover { background: #be3250; }
            QPushButton#fluentDestructive:disabled { background: palette(button); color: palette(placeholder-text); border-color: palette(mid); }
            QMenu { background: palette(base); color: palette(text); border: 1px solid palette(mid); border-radius: 9px; padding: 5px; }
            QMenu::item { padding: 7px 28px 7px 25px; border-radius: 5px; }
            QMenu::item:selected { background: palette(highlight); color: palette(highlighted-text); }
            QMenu::item:disabled { color: palette(placeholder-text); }
            QMenu::separator { background: palette(mid); height: 1px; margin: 4px 7px; }
        )");
        const auto colors = chatPalette(ChatSurface::Chrome);
        const auto resolve = [&sheet, &colors](const QString &token, QPalette::ColorRole role) {
            sheet.replace(token, colors.color(role).name());
        };
        resolve(QStringLiteral("palette(button-text)"), QPalette::ButtonText);
        resolve(QStringLiteral("palette(alternate-base)"), QPalette::AlternateBase);
        resolve(QStringLiteral("palette(highlight)"), QPalette::Highlight);
        resolve(QStringLiteral("palette(accent)"), QPalette::Accent);
        resolve(QStringLiteral("palette(base)"), QPalette::Base);
        resolve(QStringLiteral("palette(mid)"), QPalette::Mid);
        resolve(QStringLiteral("palette(text)"), QPalette::Text);
        resolve(QStringLiteral("palette(button)"), QPalette::Button);
        resolve(QStringLiteral("palette(placeholder-text)"), QPalette::PlaceholderText);
        resolve(QStringLiteral("palette(highlighted-text)"), QPalette::HighlightedText);
        sheet.replace(QStringLiteral("@rail"), chatPalette(ChatSurface::Rail).color(QPalette::Window).name());
        sheet.replace(QStringLiteral("@timeline"), chatPalette(ChatSurface::Timeline).color(QPalette::Window).name());
        sheet.replace(QStringLiteral("@conversations"), chatPalette(ChatSurface::ConversationList).color(QPalette::Window).name());
        app.setStyleSheet(sheet);
    };
    QObject::connect(hints, &QStyleHints::colorSchemeChanged, &app, refresh, Qt::QueuedConnection);
    QObject::connect(hints->accessibility(), &QAccessibilityHints::contrastPreferenceChanged, &app, refresh, Qt::QueuedConnection);
    refresh();
}

} // namespace MsgAT
