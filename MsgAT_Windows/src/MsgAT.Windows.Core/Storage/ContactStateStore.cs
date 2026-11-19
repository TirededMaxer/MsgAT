// SPDX-License-Identifier: AGPL-3.0-only
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;
namespace MsgAT.Windows.Core.Storage;

internal static class ContactStateStore
{
    internal static JsonObject Read(VaultTransaction db,string peer)
    {
        var encoded=db.Text("SELECT state_json FROM contact_links WHERE uuid=$p0",peer);
        var state=encoded is null?new JsonObject():JsonNode.Parse(encoded)!.AsObject();
        var flags=db.Query("SELECT outgoing,incoming,accepted,blocked FROM contact_states WHERE uuid=$p0",r=>new[]{r.GetInt32(0)!=0,r.GetInt32(1)!=0,r.GetInt32(2)!=0,r.GetInt32(3)!=0},peer).FirstOrDefault();
        if(flags is not null){if(state["accepted"]?.GetValue<bool>()==true && !flags[2])state["accepted_ids"]=new JsonArray();var names=new[]{"outgoing","incoming","accepted","blocked"};for(var i=0;i<4;i++)state[names[i]]=flags[i];}
        return state;
    }
    internal static JsonObject Apply(VaultTransaction db,string peer,JsonObject action)
    {
        if(peer.Length!=64 || peer.Any(c=>!char.IsAsciiHexDigit(c) || char.IsUpper(c)))throw new InvalidDataException("Invalid contact identity.");
        if((action["kind"]!.GetValue<string>() is "request" or "accept" or "unblock") && db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0",peer))throw new InvalidOperationException("This identity was revoked.");
        var result=JsonNode.Parse(SecurityCore.Call(new JsonObject{["operation"]="contact_transition",["state"]=Read(db,peer),["action"]=action}).GetRawText())!.AsObject();
        var state=result["state"]!.AsObject();
        bool Flag(string key)=>state[key]!.GetValue<bool>();
        db.Execute("INSERT INTO contact_links(uuid,state_json) VALUES($p0,$p1) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json",peer,state.ToJsonString());
        db.Execute("INSERT INTO contact_states(uuid,outgoing,incoming,accepted,blocked) VALUES($p0,$p1,$p2,$p3,$p4) ON CONFLICT(uuid) DO UPDATE SET outgoing=excluded.outgoing,incoming=excluded.incoming,accepted=excluded.accepted,blocked=excluded.blocked",peer,Flag("outgoing")?1:0,Flag("incoming")?1:0,Flag("accepted")?1:0,Flag("blocked")?1:0);
        if(!Flag("incoming"))db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",peer);
        if(Flag("blocked") || result["previous"]!["accepted"]!.GetValue<bool>() && !Flag("accepted")) {
            db.Execute("UPDATE messages SET status=-1 WHERE conversationUuid=$p0 AND isMine=1 AND status IN (0,1)",peer);
            db.Execute("DELETE FROM protocol_pending_events WHERE recipient=$p0",peer);
            db.Execute("UPDATE protocol_outbox SET delivered=-2 WHERE delivered>=0 AND recipient=$p0",peer);
            db.Execute("DELETE FROM protocol_key_requests WHERE recipient=$p0",peer);
            db.Execute("UPDATE protocol_receipts SET pending=0 WHERE peer=$p0",peer);
            db.Execute("DELETE FROM activity_watchers WHERE peer=$p0",peer);
            db.Execute("UPDATE activity_peers SET state_json=json_set(state_json,'$.inflight',NULL,'$.published',NULL) WHERE peer=$p0",peer);
            db.Execute("DELETE FROM object_transfers WHERE owner_peer=$p0 AND direction=0",peer);
        }
        return result;
    }
}
