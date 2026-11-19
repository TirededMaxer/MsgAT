// SPDX-License-Identifier: AGPL-3.0-only
namespace MsgAT.Windows.Core.Network;

public sealed record NotificationRequest(string SenderUuid, string MessageId, bool Silent, bool Pinned = false);
public sealed record MessageAlertSettings(bool Enabled, bool PlaySound);
