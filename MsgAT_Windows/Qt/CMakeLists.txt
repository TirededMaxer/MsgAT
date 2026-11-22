# SPDX-License-Identifier: AGPL-3.0-only
cmake_minimum_required(VERSION 3.24)
project(MsgATDesktopUi LANGUAGES CXX)
find_package(Qt6 6.10 REQUIRED COMPONENTS Widgets)
add_library(msgat_desktop_ui SHARED
    ChatPalette.cpp ChatPalette.h
    FluentChrome.cpp FluentChrome.h
    ChatComposer.cpp ChatComposer.h
    ChatRows.cpp ChatRows.h
    ChatWorkspace.cpp ChatWorkspace.h
    WorkspaceApi.cpp WindowPrivacy.h
    ConversationList.cpp ConversationList.h
    MessageSearch.cpp MessageSearch.h
    AttachmentReview.cpp AttachmentReview.h
    AttachmentPreview.cpp AttachmentPreview.h
    CallWindow.cpp CallWindow.h
    SettingsWindow.cpp SettingsWindow.h
    MessageTimeline.cpp MessageTimeline.h)
set_target_properties(msgat_desktop_ui PROPERTIES AUTOMOC ON)
target_compile_features(msgat_desktop_ui PUBLIC cxx_std_17)
target_link_libraries(msgat_desktop_ui PUBLIC Qt6::Widgets PRIVATE user32)
target_include_directories(msgat_desktop_ui PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
