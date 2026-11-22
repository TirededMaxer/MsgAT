// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QComboBox>
#include <QIcon>

class QApplication;
class QString;
class QToolButton;
class QPushButton;

namespace MsgAT {

enum class Symbol : ushort {
    Menu = 0xe700, Search = 0xe721, Chat = 0xe8bd, Contacts = 0xe716, Settings = 0xe713,
    Bookmark = 0xe734, Back = 0xe72b, Call = 0xe717, Video = 0xe714, More = 0xe712,
    Up = 0xe74a, Down = 0xe74b, Latest = 0xe72a, Close = 0xe711, Attach = 0xe723,
    Sticker = 0xe76e, Record = 0xe720, Send = 0xe724, Check = 0xe73e, Options = 0xe70d,
    Image = 0xeb9f, File = 0xe8a5, AddContact = 0xe8fa, Pin = 0xe718, History = 0xe81c, Shield = 0xea18, Network = 0xec27, Bell = 0xe7ed, Folder = 0xe8b7,
    Drive = 0xeda2, Copy = 0xe8c8, Reset = 0xe72c, Download = 0xe896, HangUp = 0xe77f,
    Previous = 0xe76b, Next = 0xe76c, ChevronUp = 0xe70e,
    Edit = 0xe70f, Remove = 0xe74d, Save = 0xe74e, Open = 0xe8a7
};

const QString &fluentSymbolFamily();
QIcon fluentIcon(Symbol symbol, bool primary = false);
void setFluentButton(QToolButton *button, Symbol symbol, bool primary = false);
void setFluentAction(QPushButton *button, Symbol symbol, bool lightInk = false);
void installFluentChrome(QApplication &app);

class FluentComboBox final : public QComboBox {
public:
    using QComboBox::QComboBox;

    void showPopup() override;
    void hidePopup() override;

protected:
    void paintEvent(QPaintEvent *event) override;
};

} // namespace MsgAT
