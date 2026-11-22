// SPDX-License-Identifier: AGPL-3.0-only
#include "ChatPalette.h"

#include <QAccessibilityHints>
#include <QAbstractScrollArea>
#include <QApplication>
#include <QStyleHints>
#include <QWidget>

namespace MsgAT {

QPalette chatPalette(ChatSurface surface) {
    const auto *hints = QApplication::styleHints();
    if (hints->accessibility()->contrastPreference() == Qt::ContrastPreference::HighContrast) return {};
    auto palette = QApplication::palette();
    const auto scheme = hints->colorScheme();
    const bool dark = scheme == Qt::ColorScheme::Dark || (scheme == Qt::ColorScheme::Unknown &&
                      palette.color(QPalette::Window).lightness() < 128);
    const bool list = surface == ChatSurface::ConversationList;
    auto color = [dark](QRgb light, QRgb night) { return QColor::fromRgb(dark ? night : light); };
    auto set = [&](QPalette::ColorRole role, QColor value) {
        for (auto group : { QPalette::Active, QPalette::Inactive })
            palette.setColor(group, role, value);
    };
    const auto ink = color(0x192531, 0xf0f3f6);
    const auto subdued = color(0x526070, 0xb7c4cf);
    const auto accent = color(0x006957, 0x86d9c2);
    const auto pane = color(0xf7f9fb, 0x212933);
    const auto paper = color(0xffffff, 0x29333e);
    const auto timeline = color(0xeff3f7, 0x171e26);
    const auto rail = color(0xe8eef4, 0x1b232d);
    const auto background = surface == ChatSurface::Rail ? rail : list ? pane :
        surface == ChatSurface::Chrome ? paper : timeline;
    set(QPalette::Window, background);
    set(QPalette::WindowText, subdued);
    set(QPalette::Base, list ? pane : paper);
    set(QPalette::AlternateBase, list || surface == ChatSurface::Rail
        ? color(0xe5edf3, 0x303d49)
        : color(0xd9eee8, 0x20453c));
    set(QPalette::Text, ink);
    set(QPalette::PlaceholderText, subdued);
    set(QPalette::Button, color(0xe9eff4, 0x33414f));
    set(QPalette::ButtonText, ink);
    set(QPalette::Highlight, color(0xd5e8e7, 0x2e474b));
    set(QPalette::HighlightedText, ink);
    set(QPalette::Accent, accent);
    set(QPalette::Mid, color(0xd4dde5, 0x3b4856));
    return palette;
}

void installChatPalette(QWidget *view, ChatSurface surface) {
    const auto *hints = QApplication::styleHints();
    auto refresh = [view, surface] {
        const auto palette = chatPalette(surface);
        view->setPalette(palette);
        if (auto *scroll = qobject_cast<QAbstractScrollArea *>(view)) scroll->viewport()->setPalette(palette);
    };
    QObject::connect(hints, &QStyleHints::colorSchemeChanged, view, refresh, Qt::QueuedConnection);
    QObject::connect(hints->accessibility(), &QAccessibilityHints::contrastPreferenceChanged,
                     view, refresh, Qt::QueuedConnection);
    refresh();
}

} // namespace MsgAT
