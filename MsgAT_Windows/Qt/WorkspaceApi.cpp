// SPDX-License-Identifier: AGPL-3.0-only
#include "ChatWorkspace.h"
#include "FluentChrome.h"
#include "WindowPrivacy.h"
#include <QApplication>
#include <QCloseEvent>
#include <QDir>
#include <QFileInfo>
#include <QFileIconProvider>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMainWindow>
#include <QTimer>
#include <cstring>

// The managed owner enters on the main STA. Render and selection run on this
// same thread. A view handle is valid only between the ready and closed events.
using Notify = int (*)(void *owner, void *view, int event, const char *bytes, int length);

namespace {
class WorkspaceWindow final : public QMainWindow {
public:
    std::function<void()> requestClose;
protected:
    void closeEvent(QCloseEvent *event) override {
        event->ignore();
        if (requestClose) requestClose();
    }
};
}

extern "C" Q_DECL_EXPORT int msgat_workspace_run(void *owner, Notify notify, const wchar_t *runtime,
                                                 const wchar_t *geometry, int sidebarWidth) {
    if (!owner || !notify || !runtime || QCoreApplication::instance()) return -1;
    const auto root = QString::fromWCharArray(runtime);
    if (!QFileInfo(QDir(root).filePath("platforms/qwindows.dll")).isFile()) return -1;
    QCoreApplication::setLibraryPaths({root});
    qunsetenv("QT_QPA_PLATFORM_PLUGIN_PATH");
    int argc = 3;
    char name[] = "MsgAT";
    char platformFlag[] = "-platform";
    char platform[] = "windows";
    char *argv[] = {name, platformFlag, platform, nullptr};
    QApplication app(argc, argv);
    QApplication::setApplicationName("MsgAT");
    QApplication::setQuitOnLastWindowClosed(false);
    const auto icon = QFileIconProvider().icon(QFileInfo(QCoreApplication::applicationFilePath()));
    if (!icon.isNull()) app.setWindowIcon(icon);
    MsgAT::installFluentChrome(app);
    WorkspaceWindow window;
    window.setWindowTitle("MsgAT");
    window.resize(1040, 680);
    auto *workspace = new MsgAT::ChatWorkspace(&window);
    window.setCentralWidget(workspace);
    if (geometry && *geometry) {
        const auto saved = QByteArray::fromBase64(QString::fromWCharArray(geometry).toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
        if (!saved.isEmpty()) window.restoreGeometry(saved);
    }
    workspace->restoreSidebarWidth(sidebarWidth);
    window.requestClose = [=] { notify(owner, workspace, 15, nullptr, 0); };
    auto publishFocus = [&] {
        bool active = false;
        if (window.isVisible() && !window.isMinimized()) {
            for (auto *candidate = QApplication::activeWindow(); candidate; candidate = candidate->parentWidget()) {
                if (candidate == &window) { active = true; break; }
            }
        }
        notify(owner, workspace, 16, nullptr, (active ? 1 : 0) | (workspace->isCallWindowActive() ? 2 : 0));
        workspace->reportHistoryViewport();
    };
    QObject::connect(&app, &QGuiApplication::focusWindowChanged, &window,
        [&] { QTimer::singleShot(0, &window, publishFocus); });
    QObject::connect(workspace, &MsgAT::ChatWorkspace::conversationRequested, &window,
        [=](const QString &id) {
            const auto data = id.toUtf8();
            notify(owner, workspace, 1, data.constData(), int(data.size()));
        });
    QObject::connect(workspace, &MsgAT::ChatWorkspace::folderRequested, &window,
        [=](const QString &key) {
            const auto data = key.toUtf8();
            notify(owner, workspace, 17, data.constData(), int(data.size()));
        });
    QObject::connect(workspace, &MsgAT::ChatWorkspace::historyRequested, &window,
        [=](int action, const QString &peer) {
            const auto data = peer.toUtf8();
            notify(owner, workspace, action, data.constData(), int(data.size()));
        });
    QObject::connect(workspace, &MsgAT::ChatWorkspace::historyViewportChanged, &window,
        [=](const QByteArray &data) {
            notify(owner, workspace, 10, data.constData(), int(data.size()));
        });
    workspace->dialogRequest = [=](int action, const QByteArray &data) {
        return notify(owner, workspace, action, data.constData(), int(data.size())) != 0;
    };
    auto editorEvent = [=](int kind, const QString &peer, const QString &target, const QString &text) {
        const QJsonObject payload{{"peer", peer}, {"target", target}, {"text", text}};
        auto data = QJsonDocument(payload).toJson(QJsonDocument::Compact);
        const auto result = notify(owner, workspace, kind, data.constData(), int(data.size()));
        data.fill(0);
        return result != 0;
    };
    QObject::connect(workspace, &MsgAT::ChatWorkspace::draftChanged, &window,
        [=](const QString &peer, const QString &target, const QString &text) { editorEvent(3, peer, target, text); });
    QObject::connect(workspace, &MsgAT::ChatWorkspace::sendRequested, &window,
        [=](const QString &peer, const QString &target, const QString &text) { editorEvent(4, peer, target, text); });
    workspace->copyText = [=](const QString &peer, const QString &text) {
        return editorEvent(5, peer, {}, text);
    };
    QTimer::singleShot(0, &window, [&] {
        MsgAT::excludeWindowFromCapture(&window);
        window.show();
        notify(owner, workspace, 0, nullptr, 0);
        publishFocus();
    });
    const int exitCode = app.exec();
    notify(owner, workspace, 2, nullptr, 0);
    workspace->clear();
    return exitCode;
}

extern "C" Q_DECL_EXPORT void msgat_workspace_inline_image(void *handle, qint64 generation, const char *id,
        const uchar *pixels, int width, int height, int stride) {
    auto *view = static_cast<MsgAT::ChatWorkspace *>(handle);
    if (!view || !id || !pixels || width < 1 || height < 1 || width > 800 || height > 800 || stride != width * 4) return;
    const QImage image(pixels, width, height, stride, QImage::Format_ARGB32_Premultiplied);
    view->presentInlineImage(generation, QString::fromUtf8(id), image.copy());
}

extern "C" Q_DECL_EXPORT void msgat_workspace_present(void *handle, const char *bytes, int length, int surface) {
    auto *view = static_cast<MsgAT::ChatWorkspace *>(handle);
    if (!view) return;
    if (!bytes || length <= 0) { view->clear(); return; }
    const auto data = QByteArray::fromRawData(bytes, length);
    if (surface == 2) view->presentSearch(QJsonDocument::fromJson(data).object());
    else if (surface == 3) view->presentAttachment(QJsonDocument::fromJson(data).object());
    else if (surface == 4) view->presentPreview(QJsonDocument::fromJson(data).object());
    else if (surface == 5) view->presentNotice(QJsonDocument::fromJson(data).object().value("message").toString());
    else if (surface == 6) view->presentArchiveUndo(QJsonDocument::fromJson(data).object());
    else if (surface == 7) view->presentCall(QJsonDocument::fromJson(data).object());
    else if (surface == 8) view->presentSettings(QJsonDocument::fromJson(data).object());
    else if (surface == 1) view->presentComposer(QJsonDocument::fromJson(data).object(), false);
    else view->present(data);
}

extern "C" Q_DECL_EXPORT void msgat_workspace_close(void *handle) {
    if (auto *view = static_cast<MsgAT::ChatWorkspace *>(handle)) {
        view->clear();
        view->window()->hide();
        QCoreApplication::quit();
    }
}

extern "C" Q_DECL_EXPORT void msgat_workspace_show(void *handle, int visible) {
    if (auto *view = static_cast<MsgAT::ChatWorkspace *>(handle)) {
        auto *window = view->window();
        if (visible != 1) {
            view->dismissSearch();
            view->dismissAttachment();
            view->presentNotice({});
            if (visible == 2) window->showMinimized();
            else window->hide();
        }
        else {
            if (window->isMinimized()) window->setWindowState(window->windowState() & ~Qt::WindowMinimized);
            window->show();
            window->raise();
            window->activateWindow();
        }
    }
}

extern "C" Q_DECL_EXPORT void msgat_workspace_sample_viewport(void *handle) {
    if (auto *view = static_cast<MsgAT::ChatWorkspace *>(handle)) view->reportHistoryViewport();
}

extern "C" Q_DECL_EXPORT WId msgat_workspace_window(void *handle) {
    auto *view = static_cast<MsgAT::ChatWorkspace *>(handle);
    return view ? view->window()->winId() : WId{};
}

extern "C" Q_DECL_EXPORT int msgat_workspace_geometry(void *handle, char *output, int capacity, int *sidebarWidth) {
    auto *view = static_cast<MsgAT::ChatWorkspace *>(handle);
    if (!view || !output || !sidebarWidth || capacity <= 0) return 0;
    const auto geometry = view->window()->saveGeometry().toBase64();
    if (geometry.size() > capacity) return 0;
    std::memcpy(output, geometry.constData(), size_t(geometry.size()));
    *sidebarWidth = view->sidebarWidth();
    return int(geometry.size());
}

extern "C" Q_DECL_EXPORT void msgat_workspace_image(void *handle, qint64 generation, const uchar *pixels,
                                                   int width, int height, int stride) {
    if (auto *view = static_cast<MsgAT::ChatWorkspace *>(handle))
        view->presentImage(generation, pixels, width, height, stride);
}
