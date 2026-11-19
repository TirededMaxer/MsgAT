// SPDX-License-Identifier: AGPL-3.0-only
namespace MsgAT.Windows.Media;

internal static class MicrophoneOwnership
{
    private static readonly object Gate = new();
    private static RecordingLease? _recording;
    private static CallLease? _call;

    public static RecordingLease StartRecording(Func<Task> drain)
    {
        lock (Gate)
        {
            if (_recording is not null || _call is not null) throw new InvalidOperationException("The microphone is already in use.");
            return _recording = new RecordingLease(drain);
        }
    }

    public static CallLease ReserveCall()
    {
        lock (Gate)
        {
            if (_call is not null) throw new InvalidOperationException("A call already owns the microphone.");
            return _call = new CallLease(_recording);
        }
    }

    internal sealed class RecordingLease(Func<Task> drain) : IDisposable
    {
        public Task DrainAsync() => drain();

        public void Dispose()
        {
            lock (Gate) { if (ReferenceEquals(_recording, this)) _recording = null; }
        }
    }

    internal sealed class CallLease(RecordingLease? previous) : IDisposable
    {
        public async Task<bool> DrainAsync()
        {
            if (previous is not null) await previous.DrainAsync();
            lock (Gate) return ReferenceEquals(_call, this) && _recording is null;
        }

        public void Dispose()
        {
            lock (Gate) { if (ReferenceEquals(_call, this)) _call = null; }
        }
    }
}
