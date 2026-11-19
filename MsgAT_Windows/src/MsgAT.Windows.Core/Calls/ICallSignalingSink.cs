// SPDX-License-Identifier: AGPL-3.0-only
using System.Text.Json.Nodes;
namespace MsgAT.Windows.Core.Calls;

public sealed class CallSignal(string sender,string kind,JsonObject body)
{
    public string Sender { get; }=sender;
    public string Kind { get; }=kind;
    public JsonObject Body { get; }=body;
    public override string ToString()=>"Encrypted call signal";
}
public interface ICallSignalingSink
{
    Task HandleCallSignalAsync(CallSignal signal);
}
