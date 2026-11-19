-- SPDX-License-Identifier: AGPL-3.0-only
CREATE TABLE IF NOT EXISTS conversation_drafts (peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,token TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,text TEXT NOT NULL DEFAULT '',reply_id TEXT)
-- statement
CREATE TABLE IF NOT EXISTS conversation_preferences(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,pin_order INTEGER NOT NULL DEFAULT 0 CHECK(pin_order>=0),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),mute_until INTEGER NOT NULL DEFAULT 0 CHECK(mute_until>=0),manual_unread INTEGER NOT NULL DEFAULT 0 CHECK(manual_unread IN (0,1)))
-- statement
CREATE INDEX IF NOT EXISTS conversation_preferences_order ON conversation_preferences(archived,pin_order,peer)
-- statement
CREATE TABLE IF NOT EXISTS conversation_folders(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,sort_order INTEGER NOT NULL DEFAULT 0,include_all INTEGER NOT NULL DEFAULT 0 CHECK(include_all IN (0,1)),unread_only INTEGER NOT NULL DEFAULT 0 CHECK(unread_only IN (0,1)),exclude_muted INTEGER NOT NULL DEFAULT 0 CHECK(exclude_muted IN (0,1)),include_archived INTEGER NOT NULL DEFAULT 0 CHECK(include_archived IN (0,1)))
-- statement
CREATE TABLE IF NOT EXISTS conversation_folder_members(folder_id TEXT NOT NULL REFERENCES conversation_folders(id) ON DELETE CASCADE,peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,PRIMARY KEY(folder_id,peer))
-- statement
CREATE INDEX IF NOT EXISTS conversation_folder_members_peer ON conversation_folder_members(peer)
-- statement
CREATE TABLE IF NOT EXISTS conversation_folder_pins(folder_id TEXT NOT NULL REFERENCES conversation_folders(id) ON DELETE CASCADE,peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,pin_order INTEGER NOT NULL CHECK(pin_order>0),PRIMARY KEY(folder_id,peer))
-- statement
CREATE INDEX IF NOT EXISTS conversation_folder_pins_peer ON conversation_folder_pins(peer)
-- statement
CREATE TABLE IF NOT EXISTS conversation_draft_activity(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,last_at INTEGER NOT NULL)
-- statement
CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_timestamp_messageId ON messages(conversationUuid,timestamp,messageId)
-- statement
CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_isMine_isRead_timestamp_messageId ON messages(conversationUuid,isMine,isRead,timestamp,messageId)
-- statement
CREATE TRIGGER IF NOT EXISTS conversation_unarchive_message AFTER INSERT ON messages WHEN NEW.isMine=0 AND NEW.isRead=0 AND NEW.isRevoked=0 BEGIN UPDATE conversation_preferences SET archived=0 WHERE peer=NEW.conversationUuid AND archived=1 AND mute_until<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)); END
-- statement
CREATE TRIGGER IF NOT EXISTS conversation_clear_marker AFTER UPDATE OF isRead ON messages WHEN NEW.isRead=1 AND OLD.isRead=0 BEGIN UPDATE conversation_preferences SET manual_unread=0 WHERE peer=NEW.conversationUuid AND manual_unread=1; END
-- statement
CREATE TRIGGER IF NOT EXISTS conversation_draft_insert AFTER INSERT ON conversation_drafts BEGIN INSERT INTO conversation_draft_activity(peer,last_at) SELECT NEW.peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) WHERE NEW.text!='' OR NEW.reply_id IS NOT NULL ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at; DELETE FROM conversation_draft_activity WHERE peer=NEW.peer AND NEW.text='' AND NEW.reply_id IS NULL; END
-- statement
CREATE TRIGGER IF NOT EXISTS conversation_draft_update AFTER UPDATE ON conversation_drafts BEGIN INSERT INTO conversation_draft_activity(peer,last_at) SELECT NEW.peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) WHERE NEW.text!='' OR NEW.reply_id IS NOT NULL ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at; DELETE FROM conversation_draft_activity WHERE peer=NEW.peer AND NEW.text='' AND NEW.reply_id IS NULL; END
-- statement
CREATE TRIGGER IF NOT EXISTS conversation_draft_delete AFTER DELETE ON conversation_drafts BEGIN DELETE FROM conversation_draft_activity WHERE peer=OLD.peer; END
-- statement
INSERT OR IGNORE INTO conversation_draft_activity(peer,last_at) SELECT peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) FROM conversation_drafts WHERE text!='' OR reply_id IS NOT NULL
-- statement
DROP VIEW IF EXISTS conversation_summaries;
CREATE VIEW IF NOT EXISTS conversation_summaries AS SELECT c.uuid,c.alias,c.avatarPath,c.peerPublicKey,
(SELECT COUNT(*) FROM messages u WHERE u.conversationUuid=c.uuid AND u.isMine=0 AND u.isRead=0 AND u.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=u.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)))) AS unreadCount,
COALESCE(p.pin_order,0) AS pinOrder,COALESCE(p.archived,0) AS archived,COALESCE(p.mute_until,0) AS muteUntil,COALESCE(p.manual_unread,0) AS manualUnread,
COALESCE((SELECT CASE WHEN m.isRevoked=1 THEN '[Deleted message]' WHEN m.ext='txt' THEN substr(m.content,1,240) WHEN m.ext='contact' THEN 'Contact: '||substr(m.content,1,230) WHEN m.ext IN ('location','live_location') THEN substr(m.content,1,240) WHEN m.ext='attachment' THEN COALESCE((SELECT NULLIF(substr(a.caption,1,240),'') FROM message_attachments a WHERE a.message_id=m.messageId),(SELECT json_extract(o.manifest_json,'$.filename') FROM message_attachments a JOIN media_objects o ON o.object_id=a.object_id WHERE a.message_id=m.messageId),'[Attachment]') ELSE '[Call]' END FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),'') AS summary,
COALESCE(substr(d.text,1,240),'') AS draftText,
COALESCE((SELECT m.timestamp FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),0) AS lastAt,
COALESCE((SELECT m.messageId FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),'') AS lastID,
COALESCE((SELECT u.messageId FROM messages u WHERE u.conversationUuid=c.uuid AND u.isMine=0 AND u.isRead=0 AND u.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=u.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY u.timestamp,u.messageId LIMIT 1),'') AS firstUnreadID,
COALESCE((SELECT group_concat(f.folder_id,',') FROM conversation_folder_members f WHERE f.peer=c.uuid),'') AS folderIDs,
CASE WHEN COALESCE(d.text,'')!='' OR d.reply_id IS NOT NULL THEN 1 ELSE 0 END AS hasDraft,
MAX(COALESCE(a.last_at,0),COALESCE((SELECT m.timestamp FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),0)) AS activityAt,
COALESCE((SELECT json_group_object(fp.folder_id,fp.pin_order) FROM conversation_folder_pins fp WHERE fp.peer=c.uuid),'{}') AS folderPinOrders
FROM conversations c LEFT JOIN conversation_preferences p ON p.peer=c.uuid LEFT JOIN conversation_drafts d ON d.peer=c.uuid LEFT JOIN conversation_draft_activity a ON a.peer=c.uuid
