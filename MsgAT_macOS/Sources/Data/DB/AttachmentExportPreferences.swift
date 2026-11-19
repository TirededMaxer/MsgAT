// SPDX-License-Identifier: AGPL-3.0-only
import SQLCipher

enum AttachmentExportPreferences {
    static func folder() throws -> String? {
        try AppDatabase.shared.query("SELECT folder FROM attachment_export_preferences WHERE id=1") {
            String(cString: sqlite3_column_text($0, 0))
        }.first
    }

    static func setFolder(_ path: String?) throws {
        if let path {
            try AppDatabase.shared.execute("""
                INSERT INTO attachment_export_preferences(id,folder) VALUES(1,?)
                ON CONFLICT(id) DO UPDATE SET folder=excluded.folder
                """, values: [.text(path)])
        } else {
            try AppDatabase.shared.execute("DELETE FROM attachment_export_preferences WHERE id=1")
        }
    }
}
