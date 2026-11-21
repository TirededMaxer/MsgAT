// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

class QWidget;

namespace MsgAT {

enum class ChatSurface { ConversationList, Timeline };
void installChatPalette(QWidget *view, ChatSurface surface);

} // namespace MsgAT
