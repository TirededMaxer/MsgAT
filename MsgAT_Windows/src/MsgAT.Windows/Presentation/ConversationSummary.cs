// SPDX-License-Identifier: AGPL-3.0-only
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

internal static class ConversationSummary
{
    internal static string Render(string source, string hiddenLabel)
    {
        try
        {
            var result = SecurityCore.Call(new
            {
                operation = "summarize_message_text", text = source, limit = 240, hidden_label = hiddenLabel
            });
            return result.GetProperty("text").GetString() ?? "";
        }
        catch (InvalidOperationException) { return AppStrings.Get("UiNewMessage"); }
    }

    internal static string ReceiptMark(MessageReceiptPresentation receipt) => receipt.ReceiptStatus switch
    {
        -1 => "!", 0 => "◷", 1 => "✓", 2 or 3 => "✓✓", _ => ""
    };
}
