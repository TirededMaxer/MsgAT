namespace MsgAT.Windows.Core.Calls;

public interface ICallSignalingSink
{
    Task OfferReceivedAsync(string senderUuid, string sdp);
    Task AnswerReceivedAsync(string senderUuid, string sdp);
    Task IceCandidateReceivedAsync(string senderUuid, string sdpMid, int sdpMLineIndex, string candidate);
    Task RejectedAsync(string senderUuid);
    Task EndedAsync(string senderUuid, long durationSeconds);
    Task BusyAsync(string senderUuid);
    Task SyncReceivedAsync(string senderUuid, long durationSeconds);
}
