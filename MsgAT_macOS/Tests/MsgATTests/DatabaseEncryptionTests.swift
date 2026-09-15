import Foundation
import SQLCipher
import XCTest
@testable import MsgAT

final class DatabaseEncryptionTests: XCTestCase {
    func testPlaintextDatabaseIsMigratedWithoutLosingRows() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("msgat-db-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let url = directory.appendingPathComponent("vault.sqlite")
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &database), SQLITE_OK)
        guard let database else { return XCTFail("Unable to create plaintext fixture") }
        XCTAssertEqual(sqlite3_exec(database, """
            CREATE TABLE conversations (
                uuid TEXT PRIMARY KEY NOT NULL,
                alias TEXT NOT NULL,
                avatarPath TEXT NOT NULL DEFAULT '',
                peerPublicKey TEXT NOT NULL DEFAULT ''
            );
            INSERT INTO conversations VALUES ('peer-id', 'Peer', '', 'public-key');
            """, nil, nil, nil), SQLITE_OK)
        sqlite3_close_v2(database)

        let plaintextHeader = try Data(contentsOf: url).prefix(16)
        XCTAssertEqual(plaintextHeader, Data("SQLite format 3\0".utf8))

        let encrypted = try AppDatabase(testDatabaseURL: url, key: Data(repeating: 0xA5, count: 32))
        let aliases = try encrypted.query(
            "SELECT alias FROM conversations WHERE uuid = ?",
            values: [.text("peer-id")]
        ) { databaseText($0, 0) }

        XCTAssertEqual(aliases, ["Peer"])
        XCTAssertNotEqual(try Data(contentsOf: url).prefix(16), Data("SQLite format 3\0".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path + ".plaintext-backup"))
    }
}
