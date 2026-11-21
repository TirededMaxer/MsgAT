// SPDX-License-Identifier: AGPL-3.0-only
#pragma once

#include <QAbstractListModel>
#include <QJsonArray>
#include <QJsonObject>

namespace MsgAT {

class ChatRows final : public QAbstractListModel {
public:
    enum class Kind { Conversations, Messages };
    ChatRows(Kind kind, QObject *parent);
    int rowCount(const QModelIndex &parent = {}) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QString idAt(const QModelIndex &index) const;
    QModelIndex byId(const QString &id) const;
    bool replace(const QJsonArray &rows);
    void clear();
private:
    Kind kind_;
    QList<QJsonObject> rows_;
};

} // namespace MsgAT
