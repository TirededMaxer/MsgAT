// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Crypto;

/// <summary>SQLCipher owns durable Signal snapshots; native code never owns a database.</summary>
[SupportedOSPlatform("windows")]
internal sealed class SignalProtocolStore
{
    internal sealed record Pending(string EventId, string Recipient, string Kind, JsonObject Body, long CreatedAt);
    internal sealed record Outgoing(string EventId, string Recipient, byte[] Wire);
    internal sealed record Incoming(string Sender, string EventId, string Kind, JsonObject Body, long CreatedAt);
    private readonly EncryptedDatabase _database;
    private readonly SecureSettingsStore _settings;
    private static readonly JsonWriterOptions JsonOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    private static readonly (string Field, string Kind)[] KeyKinds =
        [("prekeys", "ec"), ("signed_prekeys", "signed"), ("pq_prekeys", "pq")];

    public SignalProtocolStore(EncryptedDatabase database, SecureSettingsStore settings)
    {
        _database = database;
        _settings = settings;
        Transaction(db =>
        {
            foreach (var sql in new[]
            {
                "CREATE TABLE IF NOT EXISTS protocol_local(id INTEGER PRIMARY KEY CHECK(id=1),identity_json TEXT NOT NULL,bundle_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_keys(kind TEXT NOT NULL,id INTEGER NOT NULL,record TEXT NOT NULL,last_resort INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))",
                "CREATE TABLE IF NOT EXISTS protocol_key_counters(kind TEXT PRIMARY KEY NOT NULL,next_id INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_key_ages(kind TEXT NOT NULL,id INTEGER NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(kind,id))",
                "CREATE TABLE IF NOT EXISTS protocol_peers(uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_pq_uses(fingerprint TEXT PRIMARY KEY NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_outbox(event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,payload_hash TEXT NOT NULL,wire TEXT NOT NULL,delivered INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS protocol_pending_events(event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,kind TEXT NOT NULL,body_json TEXT NOT NULL,created_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_key_requests(recipient TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_inbox(sender TEXT NOT NULL,event_id TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(sender,event_id))",
                "CREATE TABLE IF NOT EXISTS protocol_received_wires(wire_hash TEXT PRIMARY KEY NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS contact_states(uuid TEXT PRIMARY KEY NOT NULL,outgoing INTEGER NOT NULL DEFAULT 0,incoming INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,blocked INTEGER NOT NULL DEFAULT 0)"
            }) db.Execute(sql);
            return true;
        });
    }

    public T Transaction<T>(Func<VaultTransaction, T> operation, bool notify = false) =>
        _database.ProtocolTransaction(operation, notify);

    public JsonObject PublicBundle(bool forceRefresh = false) => Transaction(db =>
    {
        var saved = Local(db);
        var seed = _settings.IdentitySeed;
        try
        {
            var request = new JsonObject
            {
                ["operation"] = "provision_identity", ["seed"] = Convert.ToBase64String(seed), ["prekey_count"] = 100
            };
            if (saved is not null)
            {
                Require(Text(saved.Value.Identity, "uuid") == _settings.MyUuid, "Vault identity mismatch");
                var body = Object(Convert.FromBase64String(Text(saved.Value.Bundle, "body")));
                var ids = body["prekeys"]?.AsArray().Select(key => Number(key!.AsObject(), "id")).ToArray()
                    ?? throw new InvalidDataException("Stored bundle has no prekeys");
                Require(ids.Length is >= 1 and <= 100, "Invalid stored prekey range");
                var available = db.Number("SELECT COUNT(*) FROM protocol_keys WHERE kind='ec' AND id>=$p0 AND id<=$p1", ids.Min(), ids.Max());
                if (!forceRefresh && Number(body, "expires_at") > Seconds + 2 * 86400 && available >= 20)
                    return saved.Value.Bundle;
                request["operation"] = "refresh_identity";
                request["local"] = saved.Value.Identity.DeepClone();
                foreach (var (kind, field) in new[] { ("ec", "first_prekey_id"), ("signed", "signed_prekey_id"), ("pq", "pq_prekey_id") })
                {
                    var next = db.Number("SELECT next_id FROM protocol_key_counters WHERE kind=$p0", kind);
                    Require(next is >= 1 and <= uint.MaxValue, "Missing or exhausted prekey counter");
                    request[field] = next;
                }
            }
            else Require(!db.Exists("SELECT 1 FROM protocol_peers UNION ALL SELECT 1 FROM protocol_keys LIMIT 1"), "Protocol identity is missing from an existing vault");
            var result = Call(request);
            Require(Text(result, "uuid") == _settings.MyUuid, "Invalid provisioned identity");
            var bundle = result["public_bundle"]!.AsObject();
            var state = result["private_state"]!.AsObject();
            var identity = state["local_identity"]!.AsObject();
            Require(saved is null || Canonical(saved.Value.Identity) == Canonical(identity), "Prekey refresh cannot replace identity");
            db.Execute("INSERT INTO protocol_local(id,identity_json,bundle_json) VALUES(1,$p0,$p1) ON CONFLICT(id) DO UPDATE SET identity_json=excluded.identity_json,bundle_json=excluded.bundle_json", Canonical(identity), Canonical(bundle));
            foreach (var (field, kind) in KeyKinds)
            {
                var records = state[field]!.AsArray();
                Require(records.Count > 0, "Missing provisioned keys");
                long next = 1;
                foreach (var value in records)
                {
                    var key = value!.AsObject();
                    var id = Number(key, "id");
                    Require(id is >= 1 and <= uint.MaxValue, "Invalid provisioned key identifier");
                    db.Execute("INSERT INTO protocol_keys(kind,id,record,last_resort) VALUES($p0,$p1,$p2,$p3)", kind,id,Text(key,"record"),key["last_resort"]?.GetValue<bool>() == true ? 1 : 0);
                    db.Execute("INSERT INTO protocol_key_ages(kind,id,created_at) VALUES($p0,$p1,$p2)",kind,id,Seconds);
                    next = Math.Max(next,id+1);
                }
                db.Execute("INSERT INTO protocol_key_counters(kind,next_id) VALUES($p0,$p1) ON CONFLICT(kind) DO UPDATE SET next_id=MAX(next_id,excluded.next_id)",kind,next);
            }
            db.Execute("DELETE FROM protocol_keys WHERE (kind,id) IN (SELECT kind,id FROM protocol_key_ages WHERE created_at<$p0)",Seconds-15*86400);
            db.Execute("DELETE FROM protocol_key_ages WHERE NOT EXISTS (SELECT 1 FROM protocol_keys k WHERE k.kind=protocol_key_ages.kind AND k.id=protocol_key_ages.id)");
            return (JsonObject)bundle.DeepClone();
        }
        finally { CryptographicOperations.ZeroMemory(seed); }
    });

    public bool HasSession(string recipient) => Transaction(db => Peer(db,recipient)?["session_record"] is JsonValue);

    public bool Enqueue(Pending message, VaultTransaction db)
    {
        Validate(message.EventId,message.Kind,message.CreatedAt);
        Require(IsAccountId(message.Recipient), "Invalid recipient identity");
        var body = Canonical(message.Body);
        Require(Encoding.UTF8.GetByteCount(body) <= 60*1024, "Event body exceeds its size limit");
        var pending = db.Query("SELECT recipient,kind,body_json,created_at FROM protocol_pending_events WHERE event_id=$p0",
            reader => (reader.GetString(0),reader.GetString(1),reader.GetString(2),reader.GetInt64(3)), message.EventId);
        if (pending.Count > 0)
        {
            Require(pending[0] == (message.Recipient,message.Kind,body,message.CreatedAt), "Conflicting pending event");
            return false;
        }
        var outgoing = db.Query("SELECT recipient,payload_hash FROM protocol_outbox WHERE event_id=$p0",
            reader => (reader.GetString(0),reader.GetString(1)),message.EventId);
        if (outgoing.Count > 0)
        {
            var bytes = Payload(message);
            try { Require(outgoing[0] == (message.Recipient,Digest(bytes)), "Conflicting committed event"); }
            finally { CryptographicOperations.ZeroMemory(bytes); }
            return false;
        }
        db.Execute("INSERT INTO protocol_pending_events(event_id,recipient,kind,body_json,created_at) VALUES($p0,$p1,$p2,$p3,$p4)",message.EventId,message.Recipient,message.Kind,body,message.CreatedAt);
        return true;
    }

    public Outgoing Prepare(Pending message, JsonObject? bundle = null, long? selectedPrekey = null) => Transaction(db =>
    {
        Validate(message.EventId,message.Kind,message.CreatedAt);
        var plaintext = Payload(message);
        try
        {
            Require(plaintext.Length <= 64*1024, "Event exceeds encrypted message limit");
            var hash = Digest(plaintext);
            var existing = db.Query("SELECT recipient,payload_hash,wire FROM protocol_outbox WHERE event_id=$p0",
                reader => (Recipient:reader.GetString(0),Hash:reader.GetString(1),Wire:reader.GetString(2)),message.EventId);
            if (existing.Count > 0)
            {
                Require(existing[0].Recipient==message.Recipient && existing[0].Hash==hash,"Conflicting event identifier");
                return new Outgoing(message.EventId,message.Recipient,Convert.FromBase64String(existing[0].Wire));
            }
            var remote = Peer(db,message.Recipient);
            if (remote is null)
            {
                Require(bundle is not null,"A contact key bundle is required");
                var verified = Call(new JsonObject { ["operation"]="verify_bundle",["bundle"]=bundle!.DeepClone(),["expected_uuid"]=message.Recipient });
                remote = new JsonObject { ["uuid"]=message.Recipient,["certificate"]=verified["bundle"]!["certificate"]!.DeepClone(),["session_record"]=null,["trusted_identity_key"]=null };
            }
            var request = new JsonObject { ["operation"]="encrypt",["local"]=RequiredLocal(db),["peer"]=remote,["plaintext"]=Convert.ToBase64String(plaintext) };
            if (bundle is not null) request["bundle"]=bundle.DeepClone();
            if (selectedPrekey is not null) request["selected_prekey"]=selectedPrekey.Value;
            var result = Call(request);
            SavePeer(db,result["peer_state"]!.AsObject(),message.Recipient);
            var wire = Text(result,"wire_bytes");
            db.Execute("INSERT INTO protocol_outbox(event_id,recipient,payload_hash,wire) VALUES($p0,$p1,$p2,$p3)",message.EventId,message.Recipient,hash,wire);
            db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0",message.EventId);
            return new Outgoing(message.EventId,message.Recipient,Convert.FromBase64String(wire));
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    });

    public bool Receive(byte[] wire, string sender, string eventId, Action<Incoming,VaultTransaction> apply) => Transaction(db =>
    {
        Require(wire.Length is > 0 and <= 262000,"Invalid transport envelope size");
        var hash = Digest(wire);
        var previous = db.Query("SELECT sender,event_id FROM protocol_received_wires WHERE wire_hash=$p0",reader => (reader.GetString(0),reader.GetString(1)),hash);
        if (previous.Count > 0)
        {
            Require(previous[0]==(sender,eventId),"Relay sender/event mismatch");
            return false;
        }
        var decoded = Call(new JsonObject { ["operation"]="decode_wire",["bytes"]=Convert.ToBase64String(wire),["expected_recipient"]=_settings.MyUuid });
        Require(Text(decoded,"sender_uuid")==sender,"Relay sender mismatch");
        var message = decoded["wire_message"]!.AsObject();
        var description = decoded["prekeys"]!.AsObject();
        var remote = Peer(db,sender) ?? new JsonObject { ["uuid"]=sender,["certificate"]=message["sender_certificate"]!.DeepClone(),["session_record"]=null,["trusted_identity_key"]=null };
        var keys = new JsonObject();
        foreach (var (field,kind,output) in new[] { ("prekey_id","ec","prekey"),("signed_prekey_id","signed","signed_prekey"),("pq_prekey_id","pq","pq_prekey") })
        {
            if (description[field] is null) continue;
            var found = db.Query("SELECT record,last_resort FROM protocol_keys WHERE kind=$p0 AND id=$p1",reader => (Record:reader.GetString(0),Last:reader.GetInt64(1)!=0),kind,Number(description,field));
            if (found.Count==0) continue;
            keys[output]=found[0].Record;
            if (kind=="pq") keys["pq_last_resort"]=found[0].Last;
        }
        if (description["pq_key_use"] is JsonObject use)
            keys["pq_already_used"]=db.Exists("SELECT 1 FROM protocol_pq_uses WHERE fingerprint=$p0",Text(use,"fingerprint"));
        var result = Call(new JsonObject { ["operation"]="decrypt",["local"]=RequiredLocal(db),["peer"]=remote,["wire"]=message.DeepClone(),["keys"]=keys });
        var plaintext = Convert.FromBase64String(Text(result,"plaintext"));
        try
        {
            Require(plaintext.Length <= 64*1024,"Invalid decrypted event size");
            var incoming = Object(plaintext);
            Require(Number(incoming,"version")==2,"Unsupported event version");
            var id = Text(incoming,"event_id"); var kind = Text(incoming,"kind"); var created = Number(incoming,"created_at");
            Validate(id,kind,created);
            Require(id==eventId,"Relay event identifier mismatch");
            var payloadHash = Digest(plaintext);
            var existing = db.Text("SELECT payload_hash FROM protocol_inbox WHERE sender=$p0 AND event_id=$p1",sender,id);
            Require(existing is null || existing==payloadHash,"Conflicting received event");
            if (existing is null)
            {
                apply(new Incoming(sender,id,kind,incoming["body"]!.AsObject(),created),db);
                db.Execute("INSERT INTO protocol_inbox(sender,event_id,payload_hash) VALUES($p0,$p1,$p2)",sender,id,payloadHash);
            }
            SavePeer(db,result["peer_state"]!.AsObject(),sender);
            if (result["consumed_prekey_id"] is not null) db.Execute("DELETE FROM protocol_keys WHERE kind='ec' AND id=$p0",Number(result,"consumed_prekey_id"));
            if (result["consumed_pq_prekey"]?.GetValue<bool>()==true) db.Execute("DELETE FROM protocol_keys WHERE kind='pq' AND id=$p0",Number(description,"pq_prekey_id"));
            if (result["pq_key_use"] is JsonObject pq) db.Execute("INSERT INTO protocol_pq_uses(fingerprint) VALUES($p0)",Text(pq,"fingerprint"));
            db.Execute("INSERT INTO protocol_received_wires(wire_hash,sender,event_id) VALUES($p0,$p1,$p2)",hash,sender,id);
            return existing is null;
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    },notify:true);

    public List<Pending> PendingEvents(int limit=64) => Transaction(db => db.Query(
        "SELECT event_id,recipient,kind,body_json,created_at FROM protocol_pending_events ORDER BY rowid LIMIT $p0",
        r => new Pending(r.GetString(0),r.GetString(1),r.GetString(2),Object(r.GetString(3)),r.GetInt64(4)),Math.Clamp(limit,1,256)));

    public List<Outgoing> PendingOutgoing(int limit=8) => Transaction(db => db.Query(
        "SELECT event_id,recipient,wire FROM protocol_outbox WHERE delivered=0 ORDER BY rowid LIMIT $p0",
        r => new Outgoing(r.GetString(0),r.GetString(1),Convert.FromBase64String(r.GetString(2))),Math.Clamp(limit,1,256)));

    public string KeyRequestId(string recipient) => Transaction(db =>
    {
        var existing = db.Text("SELECT request_id FROM protocol_key_requests WHERE recipient=$p0",recipient);
        if (existing is not null) return existing;
        var id = Guid.NewGuid().ToString("D");
        db.Execute("INSERT INTO protocol_key_requests(recipient,request_id) VALUES($p0,$p1)",recipient,id);
        return id;
    });

    public void FinishKeyRequest(string recipient,string id) => Transaction(db =>
        db.Execute("DELETE FROM protocol_key_requests WHERE recipient=$p0 AND request_id=$p1",recipient,id));

    private static JsonObject? Peer(VaultTransaction db,string uuid) => db.Text("SELECT state_json FROM protocol_peers WHERE uuid=$p0",uuid) is string json ? Object(json) : null;
    private static (JsonObject Identity,JsonObject Bundle)? Local(VaultTransaction db) => db.Query("SELECT identity_json,bundle_json FROM protocol_local WHERE id=1",
        r => (Identity:Object(r.GetString(0)),Bundle:Object(r.GetString(1)))).Select(row => ((JsonObject Identity,JsonObject Bundle)?)row).FirstOrDefault();
    private JsonObject RequiredLocal(VaultTransaction db)
    {
        var identity = Local(db)?.Identity ?? throw new InvalidDataException("Protocol identity is not initialized");
        Require(Text(identity,"uuid")==_settings.MyUuid,"Vault identity mismatch");
        return identity;
    }
    private static void SavePeer(VaultTransaction db,JsonObject state,string expected)
    {
        Require(Text(state,"uuid")==expected,"Unexpected peer state");
        db.Execute("INSERT INTO protocol_peers(uuid,state_json) VALUES($p0,$p1) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json",expected,Canonical(state));
    }
    private static byte[] Payload(Pending message) => Encoding.UTF8.GetBytes(Canonical(new JsonObject
    {
        ["version"]=2,["event_id"]=message.EventId,["kind"]=message.Kind,["body"]=message.Body.DeepClone(),["created_at"]=message.CreatedAt
    }));
    internal static bool IsAccountId(string value) => value.Length==64 && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    internal static void Validate(string id,string kind,long created)
    {
        Require(Guid.TryParseExact(id,"D",out var uuid) && uuid.ToString("D")==id && id[14]=='4' && "89ab".Contains(id[19])
            && kind.Length>0 && Encoding.UTF8.GetByteCount(kind)<=64 && created>=0,"Invalid encrypted event fields");
    }
    internal static JsonObject Call(JsonObject request) => Object(SecurityCore.Call(request).GetRawText());
    internal static string Text(JsonObject value,string name) => value[name]?.GetValue<string>() ?? throw new InvalidDataException("Missing string field");
    internal static long Number(JsonObject value,string name) => value[name]?.GetValue<long>() ?? throw new InvalidDataException("Missing integer field");
    private static string Digest(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    private static long Seconds => DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    private static void Require(bool condition,string message) { if (!condition) throw new InvalidDataException(message); }
    private static JsonObject Object(string json) => Object(Encoding.UTF8.GetBytes(json));
    private static JsonObject Object(byte[] json)
    {
        using var document = JsonDocument.Parse(json,new JsonDocumentOptions { MaxDepth=32 });
        CheckUnique(document.RootElement);
        return JsonNode.Parse(document.RootElement.GetRawText())?.AsObject() ?? throw new InvalidDataException("Expected a JSON object");
    }
    private static void CheckUnique(JsonElement element)
    {
        if (element.ValueKind==JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                Require(names.Add(property.Name),"Duplicate JSON field");
                CheckUnique(property.Value);
            }
        }
        else if (element.ValueKind==JsonValueKind.Array) foreach (var item in element.EnumerateArray()) CheckUnique(item);
    }
    private static string Canonical(JsonObject value)
    {
        using var memory = new MemoryStream();
        using (var writer = new Utf8JsonWriter(memory,JsonOptions)) WriteCanonical(writer,JsonSerializer.SerializeToElement(value));
        return Encoding.UTF8.GetString(memory.GetBuffer(),0,checked((int)memory.Length));
    }
    private static void WriteCanonical(Utf8JsonWriter writer,JsonElement element)
    {
        if (element.ValueKind==JsonValueKind.Object)
        {
            writer.WriteStartObject();
            foreach (var property in element.EnumerateObject().OrderBy(p => p.Name,StringComparer.Ordinal))
            { writer.WritePropertyName(property.Name); WriteCanonical(writer,property.Value); }
            writer.WriteEndObject();
        }
        else if (element.ValueKind==JsonValueKind.Array)
        {
            writer.WriteStartArray();
            foreach (var item in element.EnumerateArray()) WriteCanonical(writer,item);
            writer.WriteEndArray();
        }
        else element.WriteTo(writer);
    }
}
