// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Text.RegularExpressions;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Infrastructure;

internal sealed record NetworkSettingsInput(string Server, string Port, string TurnServer, string TurnPort,
    string TurnUsername, string TurnPassword, bool TurnTls, string Fingerprint, bool AllowDevelopmentCertificate,
    bool KeepTurnPassword = false)
{
    private string NormalizedPin => Fingerprint.Replace(":", "", StringComparison.Ordinal)
        .Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant();

    internal string? ValidationIssue()
    {
        if (string.IsNullOrWhiteSpace(Server)) return "UiNetworkAddressRequired";
        if (!ValidPort(Port, out _)) return "UiNetworkServerPortRange";
        if (!string.IsNullOrWhiteSpace(TurnServer) && !ValidPort(TurnPort, out _)) return "UiNetworkTurnPortRange";
        var pin = NormalizedPin;
        if (pin.Length != 0 && !Regex.IsMatch(pin, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant))
            return "UiNetworkFingerprintLength";
        return null;
    }

    internal SecureSettings ApplyTo(SecureSettings original)
    {
        _ = ValidPort(Port, out var serverPort);
        var turnPort = ValidPort(TurnPort, out var port) ? port : 3478;
        return original with
        {
            ServerAddress = Server.Trim(), ServerPort = serverPort,
            TurnServer = EmptyToNull(TurnServer), TurnPort = turnPort, TurnUsername = EmptyToNull(TurnUsername),
            TurnPassword = KeepTurnPassword ? original.TurnPassword : TurnPassword.Length == 0 ? null : TurnPassword,
            TurnUseTls = TurnTls, ServerCertificateSha256 = EmptyToNull(NormalizedPin),
            AllowDevelopmentCertificate = AllowDevelopmentCertificate
        };
    }

    public override string ToString() => "Network settings input";

    private static bool ValidPort(string value, out int port) =>
        int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out port) && port is > 0 and <= 65535;

    private static string? EmptyToNull(string value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
