// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Threading.Channels;

namespace MsgAT.Windows.Core.Storage;

public sealed record ConversationDraft(string Peer, string Token, long Revision, string Text, string? ReplyId, string? ReplyQuote = null);
public sealed class DraftSendCommit
{
    internal DraftSendCommit(ConversationDraft draft, long clearRevision, ConversationDraftStore owner, bool clearsText, bool updatesDraft)
    { Draft = draft; ClearRevision = clearRevision; Owner = owner; ClearsText = clearsText; UpdatesDraft = updatesDraft; }
    public ConversationDraft Draft { get; }
    public long ClearRevision { get; }
    public bool ClearsText { get; }
    public bool UpdatesDraft { get; }
    internal ConversationDraftStore Owner { get; }
    internal TaskCompletionSource<bool> Finished { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
}

/// <summary>Coalesced local drafts in the identity's existing SQLCipher vault.</summary>
public sealed class ConversationDraftStore : IAsyncDisposable
{
    private readonly EncryptedDatabase _database;
    private readonly object _gate = new();
    private readonly Dictionary<(string Peer, string Token), ConversationDraft> _pending = [];
    private readonly Dictionary<(string Peer, string Token), long> _latest = [];
    private readonly Dictionary<string, HashSet<DraftSendCommit>> _sending = [];
    private readonly Channel<bool> _wake = Channel.CreateBounded<bool>(new BoundedChannelOptions(1)
    { SingleReader = true, FullMode = BoundedChannelFullMode.DropWrite });
    private readonly CancellationTokenSource _lifetime = new();
    private readonly SemaphoreSlim _drain = new(1, 1);
    private readonly Task _worker;
    private Task? _disposeTask;
    private bool _closing;
    private long _revision;

    public event EventHandler? SaveFailed;

    public ConversationDraftStore(EncryptedDatabase database)
    {
        _database = database;
        _revision = database.ProtocolTransaction(db => db.Number("SELECT COALESCE(MAX(revision),0) FROM conversation_drafts"));
        _worker = Task.Run(RunAsync);
    }

    public async Task<ConversationDraft> OpenAsync(string peer)
    {
        while (true)
        {
            Task[] sends;
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_closing, this);
                sends = _sending.TryGetValue(peer, out var active) ? active.Select(item => (Task)item.Finished.Task).ToArray() : [];
            }
            await Task.WhenAll(sends).ConfigureAwait(false);
            var value = await Task.Run(() => _database.ProtocolTransaction(db =>
            {
                // The token belongs to the conversation's lifetime, not a window.
                // A deleted/recreated conversation gets a different token.
                db.Execute("INSERT OR IGNORE INTO conversation_drafts(peer,token,revision,text,reply_id) SELECT uuid,$p1,0,'',NULL FROM conversations WHERE uuid=$p0", peer, Guid.NewGuid().ToString("D"));
                return db.Query("SELECT token,revision,text,reply_id,reply_quote FROM conversation_drafts WHERE peer=$p0",
                    r => new ConversationDraft(peer, r.GetString(0), r.GetInt64(1), r.GetString(2),
                        r.IsDBNull(3) ? null : r.GetString(3), r.IsDBNull(4) ? null : r.GetString(4)), peer).FirstOrDefault()
                    ?? throw new InvalidOperationException("The conversation is no longer available.");
            })).ConfigureAwait(false);
            lock (_gate)
            {
                ObjectDisposedException.ThrowIf(_closing, this);
                if (_sending.TryGetValue(peer, out var active) && active.Count > 0) continue;
                _revision = Math.Max(_revision, value.Revision);
                var key = (peer, value.Token);
                if (_pending.TryGetValue(key, out var pending) && pending.Revision > value.Revision) return pending;
                // A save may have committed between the SELECT and this lock.
                if (_latest.GetValueOrDefault(key) > value.Revision) continue;
                return value;
            }
        }
    }

    public ConversationDraft Update(ConversationDraft previous, string text, string? replyId, string? replyQuote = null)
    {
        if (Encoding.UTF8.GetByteCount(text) > 1024 * 1024)
            throw new InvalidOperationException("The draft is too large to save.");
        var quote = replyId is not null && replyQuote is { Length: <= 128 } ? replyQuote : null;
        if (previous.Text == text && previous.ReplyId == replyId && previous.ReplyQuote == quote) return previous;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_closing, this);
            var next = previous with { Text = text, ReplyId = replyId, ReplyQuote = quote, Revision = checked(++_revision) };
            var key = (next.Peer, next.Token);
            _pending[key] = next;
            _latest[key] = next.Revision;
            _wake.Writer.TryWrite(true);
            return next;
        }
    }

    public DraftSendCommit PrepareSend(ConversationDraft draft, bool clearsText = true)
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_closing, this);
            var updatesDraft = _latest.GetValueOrDefault((draft.Peer, draft.Token)) <= draft.Revision;
            var commit = new DraftSendCommit(draft, checked(++_revision), this, clearsText, updatesDraft);
            if (!_sending.TryGetValue(draft.Peer, out var active)) _sending[draft.Peer] = active = [];
            active.Add(commit);
            return commit;
        }
    }

    internal static void ClearForCommittedSend(VaultTransaction db, DraftSendCommit commit)
    {
        if (!commit.UpdatesDraft) return;
        db.Execute("UPDATE conversation_drafts SET text=CASE WHEN $p5=1 THEN '' WHEN revision<=$p6 THEN $p4 ELSE text END,reply_id=NULL,reply_quote=NULL,revision=$p0 WHERE peer=$p1 AND token=$p2 AND revision<$p3",
            commit.ClearRevision, commit.Draft.Peer, commit.Draft.Token, commit.ClearRevision, commit.Draft.Text, commit.ClearsText ? 1 : 0, commit.Draft.Revision);
    }

    public void ObserveCommittedSend(DraftSendCommit commit)
    {
        lock (_gate)
        {
            var key = (commit.Draft.Peer, commit.Draft.Token);
            if (commit.UpdatesDraft)
            {
                _latest[key] = Math.Max(_latest.GetValueOrDefault(key), commit.ClearRevision);
                if (_pending.TryGetValue(key, out var pending) && pending.Revision <= commit.Draft.Revision) _pending.Remove(key);
            }
            FinishSend(commit, true);
        }
    }

    internal void ObserveFailedSend(DraftSendCommit commit)
    {
        lock (_gate) FinishSend(commit, false);
    }

    private void FinishSend(DraftSendCommit commit, bool saved)
    {
        if (_sending.TryGetValue(commit.Draft.Peer, out var active))
        {
            active.Remove(commit);
            if (active.Count == 0) _sending.Remove(commit.Draft.Peer);
        }
        commit.Finished.TrySetResult(saved);
    }

    private async Task RunAsync()
    {
        try
        {
            await foreach (var _ in _wake.Reader.ReadAllAsync(_lifetime.Token).ConfigureAwait(false))
            {
                var reported = false;
                while (true)
                {
                    lock (_gate) { if (_pending.Count == 0) break; }
                    try { await DrainAsync().ConfigureAwait(false); reported = false; }
                    catch (Exception) when (!_lifetime.IsCancellationRequested)
                    {
                        if (!reported)
                        {
                            reported = true;
                            foreach (EventHandler observer in SaveFailed?.GetInvocationList() ?? [])
                            {
                                try { observer(this, EventArgs.Empty); }
                                catch (Exception) { /* An observer cannot discard the pending draft. */ }
                            }
                        }
                        await Task.Delay(TimeSpan.FromSeconds(2), _lifetime.Token).ConfigureAwait(false);
                    }
                    _lifetime.Token.ThrowIfCancellationRequested();
                }
            }
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
    }

    private async Task DrainAsync()
    {
        await _drain.WaitAsync().ConfigureAwait(false);
        try
        {
            ConversationDraft[] batch;
            lock (_gate) batch = [.. _pending.Values];
            foreach (var value in batch)
            {
                await Task.Run(() => _database.ProtocolTransaction(db => db.Execute(
                    "UPDATE conversation_drafts SET text=$p0,reply_id=$p1,revision=$p2,reply_quote=$p5 WHERE peer=$p3 AND token=$p4 AND revision<$p2",
                    value.Text, value.ReplyId, value.Revision, value.Peer, value.Token, value.ReplyQuote))).ConfigureAwait(false);
                lock (_gate)
                {
                    var key = (value.Peer, value.Token);
                    if (_pending.TryGetValue(key, out var current) && current.Revision == value.Revision) _pending.Remove(key);
                }
            }
        }
        finally { _drain.Release(); }
    }

    public ValueTask DisposeAsync()
    {
        lock (_gate) return new(_disposeTask ??= CloseAsync());
    }

    private async Task CloseAsync()
    {
        _closing = true;
        foreach (var commit in _sending.Values.SelectMany(items => items)) commit.Finished.TrySetResult(false);
        _sending.Clear();
        await _lifetime.CancelAsync().ConfigureAwait(false);
        _wake.Writer.TryComplete();
        try { await _worker.ConfigureAwait(false); }
        finally
        {
            try { await DrainAsync().ConfigureAwait(false); }
            finally { _lifetime.Dispose(); _drain.Dispose(); }
        }
    }
}
