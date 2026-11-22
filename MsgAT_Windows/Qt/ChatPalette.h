// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

class QWidget;
class QPalette;

namespace MsgAT {

enum class ChatSurface { ConversationList, Timeline, Chrome, Rail };
QPalette chatPalette(ChatSurface surface);
void installChatPalette(QWidget *view, ChatSurface surface);

} // namespace MsgAT
