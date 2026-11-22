// SPDX-License-Identifier: AGPL-3.0-only
using System.Text.Json.Nodes;
using Microsoft.Data.Sqlite;
using MsgAT.Windows.Core.Network;

namespace MsgAT.Windows.Core.Storage;

public sealed record WebPreviewSnapshot(string Url,string Title,string Summary,string Site,string? ImageReference,
    bool AboveText = false, bool CompactImage = false);
public sealed class WebPreviewStore(EncryptedDatabase database)
{
    public bool Enabled {get=>database.ProtocolTransaction(db=>!db.Exists("SELECT 1 FROM web_preview_preferences WHERE id=1 AND enabled=0"));set=>database.ProtocolTransaction(db=>db.Execute("INSERT INTO web_preview_preferences(id,enabled) VALUES(1,$p0) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled",value?1:0));}
    public WebPreviewSnapshot? Read(string ownerId,bool saved=false)=>database.ProtocolTransaction(db=>Read(db,ownerId,saved));
    internal static WebPreviewSnapshot? Read(VaultTransaction db, string id, bool saved = false) => db.Query("""
        SELECT url,title,summary,site,image_object,above_text,compact_image
        FROM message_previews WHERE owner_kind=$p0 AND owner_id=$p1
        """, row => ReadSnapshot(row, 0),
        saved ? "saved" : "message", id).FirstOrDefault();

    internal static WebPreviewSnapshot ReadSnapshot(SqliteDataReader row, int offset) => new(
        row.GetString(offset), row.GetString(offset + 1), row.GetString(offset + 2), row.GetString(offset + 3),
        row.IsDBNull(offset + 4) ? null : EncryptedObjectStore.Reference(row.GetString(offset + 4)),
        row.GetInt64(offset + 5) != 0, row.GetInt64(offset + 6) != 0);
    internal static JsonObject? Body(VaultTransaction db,string id,bool saved=false)
    {
        var row=Read(db,id,saved);if(row is null)return null;
        JsonNode? image=null;
        if(row.ImageReference is { } reference)image=JsonNode.Parse(db.Text("SELECT manifest_json FROM media_objects WHERE object_id=$p0",reference[EncryptedObjectStore.ReferencePrefix.Length..])??throw new IOException("Preview image is unavailable."));
        var body = new JsonObject {["url"]=row.Url,["title"]=row.Title,["summary"]=row.Summary,["site"]=row.Site,["image"]=image};
        if (row.AboveText) body["above_text"] = true;
        if (row.CompactImage && image is not null) body["compact_image"] = true;
        return body;
    }
    internal static void Record(VaultTransaction db,string id,JsonObject? preview,string text,string peer,bool outgoing,long created,bool saved=false)
    {
        if(preview is null)return;
        if(!saved && !db.Exists("SELECT 1 FROM messages WHERE messageId=$p0 AND ext='txt' AND isRevoked=0 AND content=$p1",id,text))return;
        string? imageId=null;
        if(preview["image"] is JsonObject image)
        {
            EncryptedObjectStore.Remember(image,db,outgoing);imageId=image["object_id"]!.GetValue<string>();
        }
        db.Execute("""
            INSERT INTO message_previews(owner_kind,owner_id,url,title,summary,site,image_object,above_text,compact_image)
            VALUES($p0,$p1,$p2,$p3,$p4,$p5,$p6,$p7,$p8)
            """, saved ? "saved" : "message", id, preview["url"]!.GetValue<string>(), preview["title"]!.GetValue<string>(),
            preview["summary"]!.GetValue<string>(), preview["site"]!.GetValue<string>(), imageId,
            preview["above_text"]?.GetValue<bool>() == true ? 1 : 0,
            imageId is not null && preview["compact_image"]?.GetValue<bool>() == true ? 1 : 0);
        if(!saved && imageId is not null && (outgoing || !db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 AND local_state=1",imageId)))AttachmentQueue.Record(db,id,imageId,outgoing,peer,created);
    }
}
