import XCTest
import CryptoKit
@testable import MsgAT

final class CryptoTests: XCTestCase {

    func testBlake2bMailboxIdCalculation() {
        let testUuid = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        let mailboxId = CryptoManager.calculateMailboxId(uuid: testUuid)
        XCTAssertEqual(mailboxId.count, 64)
        XCTAssertFalse(mailboxId.isEmpty)
    }

    func testEd25519SigningAndVerification() throws {
        let seed = [UInt8](repeating: 1, count: 32)
        let privKey = try Curve25519.Signing.PrivateKey(rawRepresentation: Data(seed))
        let nonce = "1234567890abcdef".data(using: .utf8)!

        let (pubKey, signature) = try CryptoManager.signChallenge(privateKey: privKey, nonce: nonce)
        XCTAssertEqual(pubKey.count, 32)
        XCTAssertEqual(signature.count, 64)

        let pubObj = try Curve25519.Signing.PublicKey(rawRepresentation: pubKey)
        XCTAssertTrue(pubObj.isValidSignature(signature, for: nonce))
    }

    func testX25519ECDHAndAESGCM() throws {
        let seedA = [UInt8](repeating: 2, count: 32)
        let seedB = [UInt8](repeating: 3, count: 32)

        let privA = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(seedA))
        let privB = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(seedB))

        let pubABase64 = privA.publicKey.rawRepresentation.base64EncodedString()
        let pubBBase64 = privB.publicKey.rawRepresentation.base64EncodedString()

        let plaintext = "Hello MsgAT E2EE World!".data(using: .utf8)!
        let ivBytes = [UInt8](repeating: 9, count: 16)
        let iv = Data(ivBytes)

        // A encrypts for B
        let (cipherText, authTag) = try CryptoManager.encryptPayloadECDH(
            myPrivateKey: privA,
            peerPublicKeyBase64: pubBBase64,
            iv: iv,
            plaintext: plaintext
        )

        // B decrypts from A
        let decrypted = try CryptoManager.decryptPayloadECDH(
            myPrivateKey: privB,
            peerPublicKeyBase64: pubABase64,
            iv: iv,
            cipherText: cipherText,
            authTag: authTag
        )

        XCTAssertEqual(decrypted, plaintext)
    }

    func testFallbackEncryption() throws {
        let uuidA = "alice_uuid_12345"
        let uuidB = "bob_uuid_67890"
        let plaintext = "Friend Request Fallback Message".data(using: .utf8)!
        let ivBytes = [UInt8](repeating: 7, count: 16)
        let iv = Data(ivBytes)

        let (cipher, tag) = try CryptoManager.encryptPayloadFallback(
            myUuid: uuidA,
            targetUuid: uuidB,
            iv: iv,
            plaintext: plaintext
        )

        let decrypted = try CryptoManager.decryptPayloadFallback(
            myUuid: uuidB,
            targetUuid: uuidA,
            iv: iv,
            cipherText: cipher,
            authTag: tag
        )

        XCTAssertEqual(decrypted, plaintext)
    }

    func testGhostPacketCodecRoundtrip() throws {
        let packet = GhostPacket(
            type: GhostPacket.typeMessage,
            targetMailboxId: "abcdef1234567890",
            nonceOrIv: Data(repeating: 1, count: 16),
            encryptedData: "secret_data".data(using: .utf8)!,
            authTag: Data(repeating: 2, count: 16)
        )

        var encoded = try XCTUnwrap(GhostPacketCodec.encode(packet))
        XCTAssertEqual(encoded.count, 4096) // Padded to boundary

        guard let decoded = GhostPacketCodec.decode(from: &encoded) else {
            XCTFail("Failed to decode packet")
            return
        }

        XCTAssertEqual(decoded.type, GhostPacket.typeMessage)
        XCTAssertEqual(decoded.targetMailboxId, "abcdef1234567890")
        XCTAssertEqual(decoded.encryptedData, "secret_data".data(using: .utf8)!)
        XCTAssertEqual(decoded.authTag, Data(repeating: 2, count: 16))
    }

    func testMultiPacketSequentialDecoding() throws {
        let p1 = GhostPacket(type: 0x01, targetMailboxId: "mb1", nonceOrIv: Data(repeating: 1, count: 16), encryptedData: Data([1, 2, 3]), authTag: Data(repeating: 9, count: 16))
        let p2 = GhostPacket(type: 0x03, targetMailboxId: "mb2", nonceOrIv: Data(repeating: 2, count: 16), encryptedData: Data([4, 5, 6]), authTag: Data(repeating: 8, count: 16))

        var streamBuffer = Data()
        streamBuffer.append(try XCTUnwrap(GhostPacketCodec.encode(p1)))
        streamBuffer.append(try XCTUnwrap(GhostPacketCodec.encode(p2)))

        XCTAssertEqual(streamBuffer.count, 8192)

        let d1 = GhostPacketCodec.decode(from: &streamBuffer)
        XCTAssertNotNil(d1)
        XCTAssertEqual(d1?.type, 0x01)
        XCTAssertEqual(d1?.targetMailboxId, "mb1")
        XCTAssertEqual(streamBuffer.count, 4096)

        let d2 = GhostPacketCodec.decode(from: &streamBuffer)
        XCTAssertNotNil(d2)
        XCTAssertEqual(d2?.type, 0x03)
        XCTAssertEqual(d2?.targetMailboxId, "mb2")
        XCTAssertEqual(streamBuffer.count, 0)

        let d3 = GhostPacketCodec.decode(from: &streamBuffer)
        XCTAssertNil(d3)
    }

    func testFragmentedPacketDecoding() throws {
        let p = GhostPacket(type: 0x07, targetMailboxId: "mb_frag", nonceOrIv: Data(repeating: 3, count: 16), encryptedData: Data(repeating: 0xAA, count: 100), authTag: Data(repeating: 0xBB, count: 16))
        let encoded = try XCTUnwrap(GhostPacketCodec.encode(p))

        var receiveBuffer = Data()
        // Feed first 100 bytes
        receiveBuffer.append(encoded.prefix(100))
        XCTAssertNil(GhostPacketCodec.decode(from: &receiveBuffer))
        XCTAssertEqual(receiveBuffer.count, 100)

        // Feed remaining bytes
        receiveBuffer.append(encoded.dropFirst(100))
        let decoded = GhostPacketCodec.decode(from: &receiveBuffer)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.type, 0x07)
        XCTAssertEqual(decoded?.targetMailboxId, "mb_frag")
        XCTAssertEqual(decoded?.encryptedData?.count, 100)
        XCTAssertEqual(receiveBuffer.count, 0)
    }

    func testOversizedOutboundPacketIsRejected() {
        let packet = GhostPacket(
            type: GhostPacket.typeMessage,
            targetMailboxId: String(repeating: "a", count: 64),
            nonceOrIv: Data(repeating: 0, count: 16),
            encryptedData: Data(repeating: 0, count: 262_040),
            authTag: Data(repeating: 0, count: 16)
        )
        XCTAssertNil(GhostPacketCodec.encode(packet))
    }
}
