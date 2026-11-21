// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QListView>

namespace MsgAT {

class ConversationList final : public QListView {
public:
    // The model supplies a localized name, photo and description using the standard
    // display, decoration and accessibility roles. Custom roles contain display text.
    enum Role {
        Preview = Qt::UserRole + 1,
        ActivityTime,
        AvatarInitials,
        UnreadLabel,
        ReceiptLabel,
        DraftLabel,
        Pinned,
        Archived,
        HasUnread,
        MuteDescription
    };

    explicit ConversationList(QWidget *parent = nullptr);
};

} // namespace MsgAT
