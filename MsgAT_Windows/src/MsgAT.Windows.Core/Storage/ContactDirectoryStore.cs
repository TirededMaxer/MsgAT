// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;
namespace MsgAT.Windows.Core.Storage;

public sealed record ContactEntry(string Id,string Name,string? Avatar,bool Accepted,bool Incoming,bool Outgoing,bool Blocked,string Outcome,bool Revoked=false);
public sealed record ContactSafetyInfo(string Display,string Scannable,bool Verified,bool? Matches=null);
public sealed class ContactVerificationChangedEventArgs(string peer) : EventArgs
{
    public string Peer { get; } = peer;
}

public sealed class ContactDirectoryStore(EncryptedDatabase database,SecureSettingsStore settings):IAsyncDisposable
{
    private readonly object _gate=new();private int _active;private bool _closed;
    private readonly TaskCompletionSource<bool> _drained=new(TaskCreationOptions.RunContinuationsAsynchronously);
    private T Owned<T>(Func<T> action){lock(_gate){ObjectDisposedException.ThrowIf(_closed,this);_active++;}try{return action();}finally{lock(_gate){_active--;if(_closed && _active==0)_drained.TrySetResult(true);}}}
    public ValueTask DisposeAsync(){GC.SuppressFinalize(this);lock(_gate){_closed=true;if(_active==0)_drained.TrySetResult(true);return new ValueTask(_drained.Task);}}
    public IReadOnlyList<ContactEntry> List(string search="",bool blockedOnly=false,int offset=0,string? exactPeer=null)=>Owned(()=>database.ProtocolTransaction(db=>{
        var query=search.Trim();if(query.Length>256)query=query[..256];
        return db.Query("""
            SELECT c.uuid,COALESCE(p.nickname,v.alias,c.uuid),p.avatar_object,c.accepted,c.incoming,c.outgoing,c.blocked,l.state_json,EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)
            FROM contact_states c LEFT JOIN user_profiles p ON p.peer=c.uuid LEFT JOIN conversations v ON v.uuid=c.uuid LEFT JOIN contact_links l ON l.uuid=c.uuid
            WHERE (c.accepted=1 OR c.incoming=1 OR c.outgoing=1 OR c.blocked=1 OR p.peer IS NOT NULL OR v.uuid IS NOT NULL) AND ($p0=0 OR c.blocked=1) AND ($p1='' OR instr(lower(COALESCE(p.nickname,v.alias,'')),lower($p1))>0 OR instr(c.uuid,lower($p1))>0)
            AND ($p3='' OR c.uuid=$p3)
            ORDER BY lower(COALESCE(p.nickname,v.alias,c.uuid)),c.uuid LIMIT 50 OFFSET $p2
            """,r=>new ContactEntry(r.GetString(0),r.GetString(1),r.IsDBNull(2)?null:EncryptedObjectStore.Reference(r.GetString(2)),r.GetInt32(3)!=0,r.GetInt32(4)!=0,r.GetInt32(5)!=0,r.GetInt32(6)!=0,r.IsDBNull(7)?"none":JsonNode.Parse(r.GetString(7))?["last_outcome"]?.GetValue<string>()??"none",r.GetInt32(8)!=0),blockedOnly?1:0,query,Math.Max(0,offset),exactPeer??"");
    }));
    private ContactSafetyInfo Safety(VaultTransaction db,string peer,string? scan=null){
        if(db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0",peer))throw new InvalidOperationException("This identity was revoked.");
        var encoded=db.Text("SELECT bundle_json FROM protocol_local WHERE id=1")??throw new InvalidOperationException("Identity is unavailable.");
        var bundle=JsonNode.Parse(encoded)!.AsObject();
        var local=JsonNode.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(bundle["body"]!.GetValue<string>())))!["certificate"]!.DeepClone();
        var remote=db.Text("SELECT json_extract(state_json,'$.certificate') FROM protocol_peers WHERE uuid=$p0",peer)??throw new InvalidOperationException("Exchange a contact request before checking its safety code.");
        var result=SecurityCore.Call(new JsonObject{["operation"]="contact_safety",["local_id"]=settings.MyUuid,["local"]=local,["peer_id"]=peer,["peer"]=JsonNode.Parse(remote)!.DeepClone(),["scan"]=scan});
        var display=result.GetProperty("display").GetString()!;
        return new(display,result.GetProperty("scannable").GetString()!,db.Exists("SELECT 1 FROM contact_verifications WHERE uuid=$p0 AND display=$p1",peer,display),scan is null?null:result.GetProperty("matches").GetBoolean());
    }
    public ContactSafetyInfo Safety(string peer)=>Owned(()=>database.ProtocolTransaction(db=>Safety(db,peer)));
    public bool IsVerified(string peer) => Owned(() => database.ProtocolTransaction(db =>
        db.Exists("""
            SELECT 1 FROM contact_verifications v JOIN contact_states c ON c.uuid=v.uuid
            WHERE v.uuid=$p0 AND c.accepted=1 AND c.blocked=0
              AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=v.uuid)
            """, peer) && Safety(db, peer).Verified));

    public bool Verify(string peer, string expected) => ChangeVerification(peer, db =>
    {
        var current = Safety(db, peer);
        if (expected.Length != 60 || expected.Any(c => c is < '0' or > '9') || current.Display != expected)
            throw new InvalidOperationException("Safety code changed; compare it again.");
        db.Execute("INSERT INTO contact_verifications(uuid,display,verified_at) VALUES($p0,$p1,$p2) ON CONFLICT(uuid) DO UPDATE SET display=excluded.display,verified_at=excluded.verified_at", peer, expected, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    });

    public bool VerifyScan(string peer, string scan) => ChangeVerification(peer, db =>
    {
        var current = Safety(db, peer, scan);
        if (current.Matches != true)
            throw new InvalidOperationException("Safety QR code does not match this contact.");
        db.Execute("INSERT INTO contact_verifications(uuid,display,verified_at) VALUES($p0,$p1,$p2) ON CONFLICT(uuid) DO UPDATE SET display=excluded.display,verified_at=excluded.verified_at", peer, current.Display, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    });

    public void ClearVerification(string peer) => ChangeVerification(peer, db =>
        db.Execute("DELETE FROM contact_verifications WHERE uuid=$p0", peer));

    private bool ChangeVerification(string peer, Action<VaultTransaction> change) => Owned(() =>
    {
        database.ProtocolTransaction(db =>
        {
            change(db);
            return true;
        });
        database.NotifyBusinessChanges(new ContactVerificationChangedEventArgs(peer));
        return true;
    });
}
