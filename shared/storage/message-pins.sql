-- SPDX-License-Identifier: AGPL-3.0-only
-- A bounded observed-remove register per authenticated participant, not a wall-clock LWW register.
CREATE TABLE IF NOT EXISTS message_pin_clocks(peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,message_id TEXT NOT NULL,author TEXT NOT NULL,actor TEXT NOT NULL,sequence INTEGER NOT NULL,addition INTEGER NOT NULL,removed INTEGER NOT NULL,PRIMARY KEY(peer,message_id,author,actor));
CREATE INDEX IF NOT EXISTS message_pin_target ON message_pin_clocks(message_id,peer);
CREATE TRIGGER IF NOT EXISTS message_pin_delete AFTER DELETE ON messages BEGIN DELETE FROM message_pin_clocks WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId; END;
CREATE TRIGGER IF NOT EXISTS message_pin_revoke AFTER UPDATE OF isRevoked ON messages WHEN NEW.isRevoked=1 BEGIN DELETE FROM message_pin_clocks WHERE peer=NEW.conversationUuid AND message_id=NEW.messageId; END;
