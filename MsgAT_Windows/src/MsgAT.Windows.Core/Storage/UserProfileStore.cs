// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Network;

namespace MsgAT.Windows.Core.Storage;

public sealed record UserProfileSnapshot(string Peer,string Nickname,string? AvatarReference,long Revision,long ChangedAt,string EventId);
internal static class UserProfileStore
{
    internal static UserProfileSnapshot? Read(VaultTransaction db,string peer)=>db.Query(
        "SELECT nickname,avatar_object,revision,changed_at,event_id FROM user_profiles WHERE peer=$p0",r=>new UserProfileSnapshot(peer,r.GetString(0),r.IsDBNull(1)?null:EncryptedObjectStore.Reference(r.GetString(1)),r.GetInt64(2),r.GetInt64(3),r.GetString(4)),peer).FirstOrDefault();
    internal static JsonObject Body(VaultTransaction db,string self,string fallback)
    {
        var local=Read(db,self);if(local is null)return new JsonObject { ["nickname"]=fallback };
        JsonNode? avatar=null;
        if(local.AvatarReference is { } reference)
        {
            var encoded=db.Text("SELECT manifest_json FROM media_objects WHERE object_id=$p0",reference[EncryptedObjectStore.ReferencePrefix.Length..]) ?? throw new InvalidDataException("Profile image is unavailable.");
            avatar=JsonNode.Parse(encoded);
        }
        return new JsonObject { ["nickname"]=local.Nickname,["revision"]=local.Revision,["avatar"]=avatar };
    }
    internal static UserProfileSnapshot SaveLocal(VaultTransaction db,string self,string nickname,JsonObject? avatar,bool replaceAvatar)
    {
        nickname=nickname.Trim();if(nickname.Length==0 || Encoding.UTF8.GetByteCount(nickname)>256)throw new ArgumentException("Nickname is empty or too long.",nameof(nickname));
        var old=Read(db,self);var revision=checked((old?.Revision??0)+1);if(revision>uint.MaxValue)throw new InvalidOperationException("Profile revision limit reached.");
        string? objectId=replaceAvatar?null:old?.AvatarReference?[EncryptedObjectStore.ReferencePrefix.Length..];
        if(avatar is not null){EncryptedObjectStore.Remember(avatar,db,true);objectId=avatar["object_id"]!.GetValue<string>();}
        var timestamp=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();var id=Guid.NewGuid().ToString("D");
        db.Execute("INSERT INTO user_profiles(peer,nickname,revision,avatar_object,changed_at,event_id) VALUES($p0,$p1,$p2,$p3,$p4,$p5) ON CONFLICT(peer) DO UPDATE SET nickname=excluded.nickname,revision=excluded.revision,avatar_object=excluded.avatar_object,changed_at=excluded.changed_at,event_id=excluded.event_id",self,nickname,revision,objectId,timestamp,id);
        return new(self,nickname,objectId is null?null:EncryptedObjectStore.Reference(objectId),revision,timestamp,id);
    }
    internal static UserProfileSnapshot Apply(VaultTransaction db,string peer,JsonObject body,string eventId,long created)
    {
        var revision=body["revision"]?.GetValue<long>()??0;var old=Read(db,peer);
        if(old is not null && (old.Revision>revision || old.Revision==revision && (revision>0 || old.ChangedAt>created || old.ChangedAt==created && string.CompareOrdinal(old.EventId,eventId)>=0)))return old;
        db.Execute("DELETE FROM object_transfers WHERE owner_peer=$p0 AND owner_kind='profile' AND direction=1",peer);
        string? objectId=null;
        if(body["avatar"] is JsonObject avatar)
        {
            EncryptedObjectStore.Remember(avatar,db);objectId=avatar["object_id"]!.GetValue<string>();
            if(!db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 AND local_state=1",objectId))AttachmentQueue.Record(db,eventId,objectId,false,peer,created,"profile");
        }
        var name=body["nickname"]!.GetValue<string>();
        db.Execute("INSERT INTO user_profiles(peer,nickname,revision,avatar_object,changed_at,event_id) VALUES($p0,$p1,$p2,$p3,$p4,$p5) ON CONFLICT(peer) DO UPDATE SET nickname=excluded.nickname,revision=excluded.revision,avatar_object=excluded.avatar_object,changed_at=excluded.changed_at,event_id=excluded.event_id",peer,name,revision,objectId,created,eventId);
        return new(peer,name,objectId is null?null:EncryptedObjectStore.Reference(objectId),revision,created,eventId);
    }
}
