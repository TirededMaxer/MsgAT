-- SPDX-License-Identifier: AGPL-3.0-only
-- Each block is one SQLite statement; the delimiter also preserves trigger bodies.
CREATE TABLE IF NOT EXISTS local_search_meta(version INTEGER PRIMARY KEY NOT NULL)
-- statement
CREATE INDEX IF NOT EXISTS local_history_position ON messages(conversationUuid,timestamp DESC,messageId DESC)
-- statement
CREATE VIRTUAL TABLE IF NOT EXISTS local_message_search USING fts5(body, tokenize='trigram')
-- statement
DROP VIEW IF EXISTS local_search_source
-- statement
CREATE VIEW IF NOT EXISTS local_search_source AS
SELECT m.rowid AS source_rowid, m.messageId,
 CASE WHEN m.ext IN ('txt','contact','location') THEN m.content ELSE
  COALESCE(a.caption,'') || ' ' || CASE WHEN json_valid(o.manifest_json)
   THEN COALESCE(json_extract(o.manifest_json,'$.filename'),'') ELSE '' END END AS body
FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId
 LEFT JOIN media_objects o ON o.object_id=a.object_id
WHERE m.isRevoked=0 AND (m.ext IN ('txt','contact','location') OR (m.ext='attachment' AND a.message_id IS NOT NULL))
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_insert AFTER INSERT ON messages BEGIN
 INSERT OR REPLACE INTO local_message_search(rowid,body)
 SELECT source_rowid,body FROM local_search_source WHERE messageId=NEW.messageId;
END
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_update AFTER UPDATE OF content,ext,isRevoked ON messages BEGIN
 DELETE FROM local_message_search WHERE rowid=OLD.rowid;
 INSERT OR REPLACE INTO local_message_search(rowid,body)
 SELECT source_rowid,body FROM local_search_source WHERE messageId=NEW.messageId;
END
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_delete AFTER DELETE ON messages BEGIN
 DELETE FROM local_message_search WHERE rowid=OLD.rowid;
END
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_attachment_insert AFTER INSERT ON message_attachments BEGIN
 INSERT OR REPLACE INTO local_message_search(rowid,body)
 SELECT source_rowid,body FROM local_search_source WHERE messageId=NEW.message_id;
END
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_attachment_update AFTER UPDATE OF caption,object_id ON message_attachments BEGIN
 DELETE FROM local_message_search WHERE rowid=(SELECT rowid FROM messages WHERE messageId=OLD.message_id);
 INSERT OR REPLACE INTO local_message_search(rowid,body)
 SELECT source_rowid,body FROM local_search_source WHERE messageId=NEW.message_id;
END
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_attachment_delete BEFORE DELETE ON message_attachments BEGIN
 DELETE FROM local_message_search WHERE rowid=(SELECT rowid FROM messages WHERE messageId=OLD.message_id);
END
-- statement
CREATE TRIGGER IF NOT EXISTS local_search_manifest_update AFTER UPDATE OF manifest_json ON media_objects BEGIN
 INSERT OR REPLACE INTO local_message_search(rowid,body)
 SELECT s.source_rowid,s.body FROM local_search_source s JOIN message_attachments a ON a.message_id=s.messageId WHERE a.object_id=NEW.object_id;
END
-- statement
INSERT OR REPLACE INTO local_message_search(rowid,body)
SELECT source_rowid,body FROM local_search_source WHERE NOT EXISTS(SELECT 1 FROM local_search_meta WHERE version=1)
-- statement
INSERT OR IGNORE INTO local_search_meta(version) VALUES(1)
-- statement
INSERT OR REPLACE INTO local_message_search(rowid,body)
SELECT s.source_rowid,s.body FROM local_search_source s JOIN messages m ON m.rowid=s.source_rowid
 WHERE m.ext='contact' AND NOT EXISTS(SELECT 1 FROM local_search_meta WHERE version=2)
-- statement
INSERT OR IGNORE INTO local_search_meta(version) VALUES(2)
-- statement
INSERT OR REPLACE INTO local_message_search(rowid,body)
SELECT s.source_rowid,s.body FROM local_search_source s JOIN messages m ON m.rowid=s.source_rowid
 WHERE m.ext='location' AND NOT EXISTS(SELECT 1 FROM local_search_meta WHERE version=3)
-- statement
INSERT OR IGNORE INTO local_search_meta(version) VALUES(3)
