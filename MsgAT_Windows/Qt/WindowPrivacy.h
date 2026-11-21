// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QWidget>
#include <QtCore/qt_windows.h>

namespace MsgAT {

inline void excludeWindowFromCapture(QWidget *window) {
    SetWindowDisplayAffinity(reinterpret_cast<HWND>(window->winId()), WDA_EXCLUDEFROMCAPTURE);
}

} // namespace MsgAT
