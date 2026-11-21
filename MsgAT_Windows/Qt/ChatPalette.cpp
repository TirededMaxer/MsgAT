// SPDX-License-Identifier: AGPL-3.0-only
#include "ChatPalette.h"

#include <QAccessibilityHints>
#include <QApplication>
#include <QStyleHints>
#include <QWidget>

namespace MsgAT {

void installChatPalette(QWidget *view, ChatSurface surface) {
    auto hints = QGuiApplication::styleHints();
    auto refresh = [view, surface, hints] {
        QPalette palette = QApplication::palette();
        if (hints->accessibility()->contrastPreference() == Qt::ContrastPreference::HighContrast) {
            view->setPalette(QPalette());
            return;
        }
        const auto scheme = hints->colorScheme();
        const bool dark = scheme == Qt::ColorScheme::Dark || (scheme == Qt::ColorScheme::Unknown &&
                          palette.color(QPalette::Window).lightness() < 128);
        const bool list = surface == ChatSurface::ConversationList;
        auto color = [dark](QRgb light, QRgb night) { return QColor::fromRgb(dark ? night : light); };
        auto set = [&](QPalette::ColorRole role, QColor value) {
            for (auto group : { QPalette::Active, QPalette::Inactive })
                palette.setColor(group, role, value);
        };
        const auto ink = color(0x172620, 0xe9f3ee);
        const auto subdued = color(0x4e6159, 0xa9bdb3);
        const auto rail = color(0xedf2ef, 0x1c2521);
        set(QPalette::Window, list ? rail : color(0xf6f8f7, 0x171e1b));
        set(QPalette::WindowText, subdued);
        set(QPalette::Base, list ? rail : color(0xffffff, 0x28332e));
        set(QPalette::AlternateBase, list ? color(0xe3ebe6, 0x26332c) : color(0xdcefe4, 0x204638));
        set(QPalette::Text, ink);
        set(QPalette::PlaceholderText, subdued);
        set(QPalette::Button, color(0xd8e6dd, 0x304139));
        set(QPalette::ButtonText, ink);
        set(QPalette::Highlight, color(0xd0e6d9, 0x314c3f));
        set(QPalette::HighlightedText, ink);
        set(QPalette::Accent, color(0x126741, 0x8ddab1));
        view->setPalette(palette);
    };
    QObject::connect(hints, &QStyleHints::colorSchemeChanged, view, refresh, Qt::QueuedConnection);
    QObject::connect(hints->accessibility(), &QAccessibilityHints::contrastPreferenceChanged,
                     view, refresh, Qt::QueuedConnection);
    refresh();
}

} // namespace MsgAT
