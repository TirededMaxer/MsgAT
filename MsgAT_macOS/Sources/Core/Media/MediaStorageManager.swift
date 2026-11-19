import Foundation
import AppKit
import AVFoundation
import ImageIO
import SQLCipher
import CryptoKit
import Darwin
import UniformTypeIdentifiers

enum MediaSendSource: Sendable {
    case file(URL)
    case bytes(Data, String, String)
    case recording(CapturedRecording)

    var name: String {
        switch self {
        case .file(let url): return url.lastPathComponent
        case .bytes(_, let name, _): return name
        case .recording(let result): return result.file.filename
        }
    }

    var mime: String {
        switch self {
        case .file(let url): return UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        case .bytes(_, _, let mime): return mime
        case .recording(let result): return result.file.mime
        }
    }

    var fileURL: URL? {
        if case .file(let url) = self { return url }
        return nil
    }

    var canCompress: Bool {
        if case .recording = self { return false }
        return PhotoCompression.supports(mime)
    }
}

enum PhotoCropRatio: String, CaseIterable, Sendable {
    case free, original, square
    case landscapePhoto = "3:2", portraitPhoto = "2:3"
    case landscapeClassic = "4:3", portraitClassic = "3:4"
    case wide = "16:9", tall = "9:16"

    var rotated: Self {
        switch self {
        case .landscapePhoto: return .portraitPhoto
        case .portraitPhoto: return .landscapePhoto
        case .landscapeClassic: return .portraitClassic
        case .portraitClassic: return .landscapeClassic
        case .wide: return .tall
        case .tall: return .wide
        case .free, .original, .square: return self
        }
    }

    func normalized(imageAspect: CGFloat) -> CGFloat? {
        switch self {
        case .free: return nil
        case .original: return 1
        case .square: return 1 / imageAspect
        case .landscapePhoto: return 3 / (2 * imageAspect)
        case .portraitPhoto: return 2 / (3 * imageAspect)
        case .landscapeClassic: return 4 / (3 * imageAspect)
        case .portraitClassic: return 3 / (4 * imageAspect)
        case .wide: return 16 / (9 * imageAspect)
        case .tall: return 9 / (16 * imageAspect)
        }
    }
}

struct PhotoAdjustment: Equatable, Sendable {
    static let wholePhoto = CGRect(x: 0, y: 0, width: 1, height: 1)
    var crop = PhotoAdjustment.wholePhoto
    var turns = 0
    var mirrored = false
    var ratio: PhotoCropRatio = .free

    var changesPixels: Bool { crop != Self.wholePhoto || turns != 0 || mirrored }

    mutating func rotate(clockwise: Bool) {
        crop = Self.rotatedRegion(crop, clockwise: clockwise)
        turns = (turns + (clockwise != mirrored ? 1 : 3)) % 4
        ratio = ratio.rotated
    }

    mutating func flip() {
        crop = Self.mirroredRegion(crop)
        mirrored.toggle()
    }

    mutating func selectRatio(_ selection: PhotoCropRatio, imageAspect: CGFloat) {
        ratio = selection
        guard let aspect = selection.normalized(imageAspect: imageAspect) else { return }
        let width = min(1, aspect)
        let height = min(1, 1 / aspect)
        crop = CGRect(x: min(1 - width, max(0, crop.midX - width / 2)),
                      y: min(1 - height, max(0, crop.midY - height / 2)), width: width, height: height)
    }

    static func rotatedRegion(_ region: CGRect, clockwise: Bool) -> CGRect {
        if clockwise {
            return CGRect(x: 1 - region.maxY, y: region.minX, width: region.height, height: region.width)
        }
        return CGRect(x: region.minY, y: 1 - region.maxX, width: region.height, height: region.width)
    }

    static func mirroredRegion(_ region: CGRect) -> CGRect {
        CGRect(x: 1 - region.maxX, y: region.minY, width: region.width, height: region.height)
    }
}

enum PhotoCompression {
    static let maximumInput = 32 * 1024 * 1024

    static func supports(_ mime: String) -> Bool {
        mime == "image/jpeg" || mime == "image/png"
    }

    static func encode(file: URL, mime: String, preparation: ObjectPreparation?) throws -> Data? {
        guard supports(mime), var bytes = try readPhoto(file, preparation: preparation) else { return nil }
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        return try encode(bytes, mime: mime, preparation: preparation)
    }

    static func encode(_ bytes: Data, mime: String, preparation: ObjectPreparation?) throws -> Data? {
        try preparation?.check()
        guard let image = decode(bytes, mime: mime, edge: 2048) else { return nil }
        try preparation?.check()
        guard var encoded = encodeImage(image, mime: mime, quality: 0.82, limit: bytes.count - 1) else { return nil }
        do {
            try preparation?.check()
            return encoded
        } catch {
            encoded.resetBytes(in: 0..<encoded.count)
            throw error
        }
    }

    static func openForEditing(_ source: MediaSendSource) throws -> CGImage? {
        switch source {
        case .file(let url):
            guard var bytes = try readPhoto(url) else { return nil }
            defer { bytes.resetBytes(in: 0..<bytes.count) }
            return decode(bytes, mime: source.mime, edge: 2560)
        case .bytes(let bytes, _, let mime):
            return decode(bytes, mime: mime, edge: 2560)
        case .recording:
            return nil
        }
    }

    static func edit(_ image: CGImage, adjustment: PhotoAdjustment, source: MediaSendSource) throws -> PhotoEditResult {
        // Undo the display transforms on the crop before sampling the source pixels.
        let turns = adjustment.turns
        var region = adjustment.mirrored ? PhotoAdjustment.mirroredRegion(adjustment.crop) : adjustment.crop
        for _ in 0..<((4 - turns) % 4) { region = PhotoAdjustment.rotatedRegion(region, clockwise: true) }
        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        let left = min(image.width - 1, max(0, Int((region.minX * width).rounded())))
        let top = min(image.height - 1, max(0, Int((region.minY * height).rounded())))
        let right = max(left + 1, min(image.width, Int((region.maxX * width).rounded())))
        let bottom = max(top + 1, min(image.height, Int((region.maxY * height).rounded())))
        guard let cropped = image.cropping(to: CGRect(x: left, y: top, width: right - left, height: bottom - top))
        else { throw CocoaError(.fileReadCorruptFile) }
        let output: CGImage
        if turns == 0 && !adjustment.mirrored {
            output = cropped
        } else {
            let sideways = turns % 2 != 0
            let outputWidth = sideways ? cropped.height : cropped.width
            let outputHeight = sideways ? cropped.width : cropped.height
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(data: nil, width: outputWidth, height: outputHeight,
                                          bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { throw CocoaError(.fileWriteUnknown) }
            context.translateBy(x: CGFloat(outputWidth) / 2, y: CGFloat(outputHeight) / 2)
            if adjustment.mirrored { context.scaleBy(x: -1, y: 1) }
            context.rotate(by: -CGFloat(turns) * .pi / 2)
            context.interpolationQuality = .none
            context.draw(cropped, in: CGRect(x: -CGFloat(cropped.width) / 2, y: -CGFloat(cropped.height) / 2,
                                              width: CGFloat(cropped.width), height: CGFloat(cropped.height)))
            guard let rotated = context.makeImage() else { throw CocoaError(.fileWriteUnknown) }
            output = rotated
        }
        let mime = source.mime
        guard let bytes = encodeImage(output, mime: mime, quality: 0.95, limit: maximumInput),
              let preview = decode(bytes, mime: mime, edge: 512) else { throw CocoaError(.fileWriteUnknown) }
        let basename = (source.name as NSString).deletingPathExtension
        let suffix = mime == "image/png" ? ".png" : ".jpg"
        let name = String((basename.isEmpty ? "Photo" : basename).prefix(160)) + "-edited" + suffix
        return PhotoEditResult(bytes: bytes, name: name, mime: mime, preview: preview, adjustment: adjustment)
    }

    private static func readPhoto(_ file: URL, preparation: ObjectPreparation? = nil) throws -> Data? {
        let info = try file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard info.isRegularFile == true, let size = info.fileSize, (1...maximumInput).contains(size) else { return nil }
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var bytes = Data(capacity: size)
        do {
            while bytes.count <= maximumInput {
                try preparation?.check()
                guard var block = try input.read(upToCount: min(64 * 1024, maximumInput - bytes.count + 1)),
                      !block.isEmpty else { return bytes.isEmpty ? nil : bytes }
                defer { block.resetBytes(in: 0..<block.count) }
                bytes.append(block)
            }
            bytes.resetBytes(in: 0..<bytes.count)
            return nil
        } catch {
            bytes.resetBytes(in: 0..<bytes.count)
            throw error
        }
    }

    private static func decode(_ bytes: Data, mime: String, edge: Int) -> CGImage? {
        guard supports(mime), (1...maximumInput).contains(bytes.count) else { return nil }
        if mime == "image/png", !isStaticPng(bytes) { return nil }
        guard let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1,
              let sourceType = CGImageSourceGetType(source),
              sourceType as String == (mime == "image/png" ? "public.png" : "public.jpeg"),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 64_000_000 / height else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: edge
        ] as CFDictionary)
    }

    private static func encodeImage(_ image: CGImage, mime: String, quality: Double, limit: Int) -> Data? {
        let output = NSMutableData()
        defer {
            if output.length > 0 { output.resetBytes(in: NSRange(location: 0, length: output.length)) }
        }
        let type = mime == "image/png" ? "public.png" : "public.jpeg"
        guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, type as CFString, 1, nil) else { return nil }
        let options = mime == "image/jpeg" ? [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary : nil
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination), output.length > 0, output.length <= limit else { return nil }
        let storage = UnsafeMutableRawPointer.allocate(byteCount: output.length, alignment: MemoryLayout<UInt64>.alignment)
        storage.copyMemory(from: output.bytes, byteCount: output.length)
        return Data(bytesNoCopy: storage, count: output.length, deallocator: .custom { address, count in
            msgat_zero_bytes(address, count)
            address.deallocate()
        })
    }

    private static func isStaticPng(_ data: Data) -> Bool {
        data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            guard bytes.count >= 8, bytes.prefix(8).elementsEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]) else { return false }
            var offset = 8
            while bytes.count - offset >= 12 {
                let size = (0..<4).reduce(0) { ($0 << 8) | Int(bytes[offset + $1]) }
                guard size <= bytes.count - offset - 12 else { return false }
                let type = bytes[(offset + 4)..<(offset + 8)]
                if type.elementsEqual([0x61, 0x63, 0x54, 0x4c]) { return false } // acTL
                if type.elementsEqual([0x49, 0x44, 0x41, 0x54]) { return true } // IDAT
                offset += 12 + size
            }
            return false
        }
    }
}

struct PhotoEditResult {
    let bytes: Data
    let name: String
    let mime: String
    let preview: CGImage
    let adjustment: PhotoAdjustment
    var source: MediaSendSource { .bytes(bytes, name, mime) }
}

struct AlbumPosition: Equatable, Sendable {
    let id: String
    let index: Int
    let count: Int
}

enum AttachmentDownloadIntent: Int32, Equatable, Sendable {
    case automatic, requested, paused
}

struct AttachmentMetadata: Equatable, Sendable {
    let reference: String
    let filename: String
    let mime: String
    let size: Int64
    let available: Bool
    let caption: String
    var isSticker: Bool=false
    var album: AlbumPosition? = nil
    var remoteUnavailable: Bool = false
    var downloadIntent: AttachmentDownloadIntent = .automatic
    var hasPendingDownload = false
    var isImage: Bool { mime.hasPrefix("image/") }
    var isVideo: Bool { mime.hasPrefix("video/") }
    var isAudio: Bool { mime.hasPrefix("audio/") }
}
enum SharedMediaCategory: String, CaseIterable, Hashable, Identifiable, Sendable {
    case media, photos, videos, files, links, audio
    var id: String { rawValue }
    var visual: Bool { self == .media || self == .photos || self == .videos }
}

struct SharedMediaCursor: Hashable, Sendable {
    let timestamp: Int64
    let messageId: String
}

struct SharedMediaItem: Identifiable, Equatable, Sendable {
    let id: String
    let timestamp: Int64
    let isMine: Bool
    let attachment: AttachmentMetadata
}

struct VisualMediaNeighbors: Sendable {
    let newer: SharedMediaItem?
    let older: SharedMediaItem?
}

struct SharedLink: Identifiable, Sendable {
    let id: String
    let timestamp: Int64
    let urls: [String]
}

struct SharedMediaPage: Sendable {
    let items: [SharedMediaItem]
    let older: SharedMediaCursor?
    var links: [SharedLink] = []
}

enum InsufficientObjectSpaceError: Error {case insufficientSpace}

public final class MediaStorageManager {
    public static let shared = MediaStorageManager()

    private init() {
        let dir = mediaDirectory
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
    }

    public var mediaDirectory: URL {
        RuntimePaths.applicationSupport.appendingPathComponent("media", isDirectory: true)
    }

    public func getMediaFile(msgId: String, ext: String) -> URL {
        let cleanExt = ext.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return mediaDirectory.appendingPathComponent("\(msgId).\(cleanExt)")
    }

    public func saveMediaBytes(msgId: String, ext: String, bytes: Data) throws -> URL {
        guard UUID(uuidString: msgId) != nil,
              ext.range(of: "^[A-Za-z0-9]{1,10}$", options: .regularExpression) != nil,
              bytes.count <= 100 * 1024 * 1024 else { throw CocoaError(.fileWriteInvalidFileName) }
        let targetFile = getMediaFile(msgId: msgId, ext: ext)
        try bytes.write(to: targetFile, options: .atomic)
        return targetFile
    }

    public func saveMediaFromUrl(msgId: String, ext: String, sourceUrl: URL) throws -> URL {
        let size = try sourceUrl.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 100 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
        return try saveMediaBytes(msgId: msgId, ext: ext, bytes: Data(contentsOf: sourceUrl))
    }

    public func getOrExtractMediaFile(msgId: String, ext: String, content: String) -> URL? {
        if content.isEmpty { return nil }

        // 1. If content is already a local file path
        if content.hasPrefix("/") {
            let url = URL(fileURLWithPath: content)
            if FileManager.default.fileExists(atPath: url.path) {
                return url
            }
            let fallback = getMediaFile(msgId: msgId, ext: ext)
            if FileManager.default.fileExists(atPath: fallback.path) {
                return fallback
            }
            return nil
        }

        // 2. Legacy Base64 string fallback
        let targetFile = getMediaFile(msgId: msgId, ext: ext)
        if FileManager.default.fileExists(atPath: targetFile.path) {
            return targetFile
        }

        var cleanBase64 = content.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
            .replacingOccurrences(of: "=", with: "")
        let padding = cleanBase64.count % 4
        if padding > 0 {
            cleanBase64 += String(repeating: "=", count: 4 - padding)
        }

        if let data = Data(base64Encoded: cleanBase64) {
            return try? saveMediaBytes(msgId: msgId, ext: ext, bytes: data)
        }
        return nil
    }

    public func base64Chunks(bytes: Data) throws -> [String] {
        guard bytes.count <= 100 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
        if bytes.isEmpty { return [""] }
        return stride(from: 0, to: bytes.count, by: 45000).map { offset in
            bytes.subdata(in: offset..<min(offset + 45000, bytes.count)).base64EncodedString()
        }
    }

    // MARK: - Thumbnails

    public func generateImageThumbnail(fileUrl: URL, maxDimension: CGFloat = 400) -> NSImage? {
        guard let imageSource = CGImageSourceCreateWithURL(fileUrl as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary) else {
            return nil
        }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }

    public func generateVideoThumbnail(fileUrl: URL) -> NSImage? {
        let asset = AVAsset(url: fileUrl)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        let time = CMTime(seconds: 0, preferredTimescale: 60)
        do {
            let cgImage = try generator.copyCGImage(at: time, actualTime: nil)
            return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
        } catch {
            return nil
        }
    }

    // MARK: - Deletion & Cleanup

    public func deleteMediaFile(msgId: String, ext: String) {
        let file = getMediaFile(msgId: msgId, ext: ext)
        try? FileManager.default.removeItem(at: file)
    }

    public func deleteAllMedia() {
        let dir = mediaDirectory
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
}

/// V2 ciphertext-only storage. Native decoders receive authenticated memory ranges;
/// an external plaintext file is created only by a separate, explicit export flow.
final class EncryptedObjectStore: @unchecked Sendable {
    static let shared = EncryptedObjectStore()
    static let referencePrefix = "msgat-object://"
    let directory: URL
    private let lifecycle: EncryptedObjectLifecycle
    private init() {
        let root=RuntimePaths.applicationSupport.appendingPathComponent("objects/v2",isDirectory:true)
        directory=root;lifecycle=EncryptedObjectLifecycle(directory:root)
    }
    func collectGarbage() throws -> Int {try lifecycle.collect()}
    private func stage(_ candidate: [String:Any]) throws -> String {
        guard let id=candidate["object_id"] as? String,let bytes=candidate["cipher_size"] as? Int64 else {throw SecurityCore.Failure.invalidResponse}
        try requireFreeSpace(bytes);try lifecycle.stage(id);return id
    }

    func recordingCandidate(size:Int64,filename:String,mime:String)throws->[String:Any] {
        var candidate=try KeychainManager.shared.withRootSeed{seed in
            try SecurityCore.call(["operation":"create_object","seed":seed.base64EncodedString(),"size":size])
        }
        candidate["filename"]=filename;candidate["mime"]=mime
        candidate["sha256"]=String(repeating:"0",count:64);candidate["cipher_sha256"]=String(repeating:"0",count:64)
        _=try stage(candidate);return candidate
    }
    func discardRecording(_ id:String)throws {try lifecycle.discard(id)}
    // Worker only. Retain the source until re-encryption and target cleanup finish.
    func importRecording(_ source:EncryptedCaptureFile,preparation:ObjectPreparation?=nil,asFile:Bool=false)throws->[String:Any] {
        try preparation?.check()
        let lease=try source.retainFrozen();defer{try? lease.close()}
        let candidate=try recordingCandidate(size:source.size,filename:source.filename,mime:asFile ? "application/octet-stream":source.mime)
        var prepared=false
        defer{if !prepared{try? discardCopy(candidate)}}
        let manifest=try source.importTo(directory:directory,manifest:candidate,preparation:preparation)
        try preparation?.check();prepared=true;return manifest
    }

    // Call from a worker queue, never the SwiftUI main actor.
    func importFile(_ source: URL, mime: String,preparation:ObjectPreparation?=nil) throws -> [String: Any] {
        try preparation?.check()
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1
        guard size >= 0, size <= 100 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var excluded = directory; try excluded.setResourceValues(values)
        var metadata = try KeychainManager.shared.withRootSeed { seed in
            try SecurityCore.call(["operation":"create_object", "seed":seed.base64EncodedString(), "size":size])
        }
        metadata["filename"] = source.lastPathComponent; metadata["mime"] = mime
        metadata["sha256"] = String(repeating:"0",count:64)
        metadata["cipher_sha256"] = String(repeating:"0",count:64)
        let id=try stage(metadata);var prepared=false
        defer {if !prepared {try? lifecycle.discard(id)}}
        var request:[String:Any]=["operation":"import_object_file","manifest":metadata,"source":source.path,"directory":directory.path,"maximum_size":100*1024*1024]
        if let preparation {request["work_id"]=try preparation.workID()}
        let result=try SecurityCore.call(request)
        try preparation?.check()
        guard let manifest = result["manifest"] as? [String: Any], manifest["object_id"] as? String==id else {
            throw SecurityCore.Failure.invalidResponse
        }
        prepared=true
        return manifest
    }

    static func remember(_ candidate: [String: Any], in transaction: AppDatabase.Transaction, available: Bool = false) throws {
        let result = try SecurityCore.call(["operation":"validate_object_manifest", "manifest":candidate])
        guard let manifest = result["manifest"] as? [String:Any], let id = manifest["object_id"] as? String else { throw SecurityCore.Failure.invalidResponse }
        let encoded = String(decoding:try JSONSerialization.data(withJSONObject:manifest, options:.sortedKeys),as:UTF8.self)
        let previous = try transaction.query("SELECT manifest_json FROM media_objects WHERE object_id=?",values:[.text(id)]) {
            String(cString:sqlite3_column_text($0,0))
        }.first
        guard previous == nil || previous == encoded else { throw SecurityCore.Failure.operation("Conflicting encrypted object identity.") }
        try transaction.execute("INSERT INTO media_objects(object_id,manifest_json,local_state) VALUES(?,?,?) ON CONFLICT(object_id) DO UPDATE SET local_state=MAX(local_state,excluded.local_state)",
            values:[.text(id),.text(encoded),.integer(available ? 1 : 0)])
        if available {try transaction.execute("DELETE FROM media_staging WHERE object_id=?",values:[.text(id)])}
    }

    // Import worker only. The caller either commits this new object or discards it.
    func copyObject(_ source: [String:Any],preparation:ObjectPreparation?=nil) throws -> [String:Any] {
        try preparation?.check()
        guard let original=try SecurityCore.call(["operation":"validate_object_manifest","manifest":source])["manifest"] as? [String:Any],
              let size=original["size"] as? Int64,let filename=original["filename"] as? String,let mime=original["mime"] as? String else {throw SecurityCore.Failure.invalidResponse}
        var candidate=try KeychainManager.shared.withRootSeed {seed in try SecurityCore.call(["operation":"create_object","seed":seed.base64EncodedString(),"size":size])}
        candidate["filename"]=filename;candidate["mime"]=mime;candidate["sha256"]=String(repeating:"0",count:64);candidate["cipher_sha256"]=String(repeating:"0",count:64)
        let id=try stage(candidate);var prepared=false
        defer {if !prepared {try? lifecycle.discard(id)}}
        var request:[String:Any]=["operation":"rekey_object_file","source":original,"manifest":candidate,"directory":directory.path,"maximum_size":100*1024*1024]
        if let preparation {request["work_id"]=try preparation.workID()}
        guard let manifest=try SecurityCore.call(request)["manifest"] as? [String:Any] else {throw SecurityCore.Failure.invalidResponse}
        try preparation?.check()
        prepared=true;return manifest
    }

    func pinTransfer(id: String, direction: Int, eligible: (AppDatabase.Transaction) throws -> Bool) throws -> (() -> Void)? {
        try lifecycle.pinTransfer(id: id, direction: direction, eligible: eligible)
    }
    func discardCopy(_ manifest: [String:Any]) throws {
        guard let id=manifest["object_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
        try lifecycle.discard(id)
    }

    func metadata(_ reference: String, messageID: String) throws -> AttachmentMetadata? {
        let id = try Self.id(reference)
        return try AppDatabase.shared.query("""
            SELECT o.manifest_json,o.local_state,COALESCE(a.caption,s.body,''),
                   EXISTS(SELECT 1 FROM message_stickers WHERE message_id=? UNION ALL SELECT 1 FROM saved_stickers WHERE id=?),
                   g.album_id,g.position,g.item_count,
                   COALESCE(t.download_unavailable,0),
                   CASE WHEN t.download_paused=1 THEN 2 WHEN t.download_requested=1 THEN 1 ELSE 0 END,t.event_id IS NOT NULL
            FROM media_objects o LEFT JOIN message_attachments a ON a.object_id=o.object_id AND a.message_id=?
            LEFT JOIN object_transfers t ON t.object_id=o.object_id AND t.event_id=? AND t.direction=1 AND t.owner_kind='message'
            LEFT JOIN message_album_items g ON g.message_id=a.message_id
            LEFT JOIN saved_messages s ON s.object_id=o.object_id AND s.id=? WHERE o.object_id=?
            """, values: [.text(messageID),.text(messageID),.text(messageID),.text(messageID),.text(messageID),.text(id)]) { row in
            guard let value = try JSONSerialization.jsonObject(with: Data(String(cString: sqlite3_column_text(row, 0)).utf8)) as? [String: Any],
                  let filename = value["filename"] as? String, let mime = value["mime"] as? String, let size = value["size"] as? Int64 else { throw SecurityCore.Failure.invalidResponse }
            let album = sqlite3_column_text(row, 4).map {
                AlbumPosition(id: String(cString: $0), index: Int(sqlite3_column_int(row, 5)), count: Int(sqlite3_column_int(row, 6)))
            }
            return AttachmentMetadata(reference: reference, filename: filename, mime: mime, size: size,
                available: sqlite3_column_int(row, 1) == 1, caption: String(cString: sqlite3_column_text(row, 2)),isSticker:sqlite3_column_int(row,3) != 0,
                album: album, remoteUnavailable: sqlite3_column_int(row,7) != 0,
                downloadIntent: AttachmentDownloadIntent(rawValue: sqlite3_column_int(row,8)) ?? .automatic,
                hasPendingDownload: sqlite3_column_int(row,9) != 0)
        }.first
    }

    private var sharedMediaColumns: String {
        """
        SELECT message_id,created_at,is_mine,object_id,filename,mime,size,available,caption,
               EXISTS(SELECT 1 FROM object_transfers t WHERE t.object_id=media.object_id AND t.event_id=media.message_id
                      AND t.direction=1 AND t.download_unavailable=1),
               COALESCE((SELECT CASE WHEN t.download_paused=1 THEN 2 WHEN t.download_requested=1 THEN 1 ELSE 0 END
                      FROM object_transfers t WHERE t.object_id=media.object_id AND t.event_id=media.message_id AND t.direction=1),0)
        FROM conversation_media media
        """
    }

    private let sharedMediaVisible = """
        NOT EXISTS (SELECT 1 FROM message_expiration e WHERE e.message_id=media.message_id AND e.expires_at<=:at)
        AND NOT EXISTS (SELECT 1 FROM conversation_retention r JOIN messages m ON m.messageId=media.message_id
            WHERE r.peer=m.conversationUuid AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=:at-r.duration_ms)
        """

    func sharedMedia(peer: String, category: SharedMediaCategory, before: SharedMediaCursor?, query: String = "") throws -> SharedMediaPage {
        if category == .links { return try sharedLinks(peer: peer, before: before, query: query) }
        let mediaFilter = category == .media ? "category IN ('photos','videos')" : "category=?"
        var filter = " AND \(sharedMediaVisible)"
        var values: [DatabaseValue] = [.text(peer)]
        if category != .media { values.append(.text(category.rawValue)) }
        values.append(.integer(Int64(Date().timeIntervalSince1970 * 1_000)))
        if let before {
            filter += " AND (created_at<? OR (created_at=? AND message_id<?))"
            values += [.integer(before.timestamp), .integer(before.timestamp), .text(before.messageId)]
        }
        if !query.isEmpty {
            filter += " AND instr(lower(filename),lower(?))>0"
            values.append(.text(query))
        }
        let rows = try AppDatabase.shared.query(
            sharedMediaColumns + " WHERE peer=? AND " + mediaFilter + filter + " ORDER BY created_at DESC,message_id DESC LIMIT 41",
            values: values, map: readSharedMedia
        )
        let items = Array(rows.prefix(40))
        let older = rows.count > items.count ? items.last.map { SharedMediaCursor(timestamp: $0.timestamp, messageId: $0.id) } : nil
        return SharedMediaPage(items: items, older: older)
    }

    private func sharedLinks(peer: String, before: SharedMediaCursor?, query: String) throws -> SharedMediaPage {
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        var links: [SharedLink] = []
        var after = before
        var lastMatch: SharedMediaCursor?
        while true {
            try Task.checkCancellation()
            var filter = ""
            var values: [DatabaseValue] = [.text(peer), .integer(now)]
            if let after {
                filter = " AND (m.timestamp<? OR (m.timestamp=? AND m.messageId<?))"
                values += [.integer(after.timestamp), .integer(after.timestamp), .text(after.messageId)]
            }
            let rows = try AppDatabase.shared.query("""
                SELECT m.messageId,m.timestamp,CASE WHEN m.ext='txt' THEN m.content ELSE a.caption END AS body
                FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId AND m.ext='attachment'
                LEFT JOIN message_expiration e ON e.message_id=m.messageId
                WHERE m.conversationUuid=? AND m.isRevoked=0 AND (e.expires_at IS NULL OR e.expires_at>?2)
                  AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                      AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?2-r.duration_ms)
                  AND (m.ext='txt' OR a.message_id IS NOT NULL)
                  AND (instr(lower(body),'https://')>0 OR instr(lower(body),'http://')>0 OR instr(lower(body),'www.')>0)\(filter)
                ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 80
                """, values: values) { row in
                    (SharedMediaCursor(timestamp: sqlite3_column_int64(row, 1), messageId: String(cString: sqlite3_column_text(row, 0))),
                     String(cString: sqlite3_column_text(row, 2)))
                }
            for (cursor, body) in rows {
                try Task.checkCancellation()
                let response = try SecurityCore.call(["operation": "visible_message_urls", "text": body])
                guard let urls = response["urls"] as? [String] else { throw SecurityCore.Failure.invalidResponse }
                let visible = query.isEmpty ? urls : urls.filter { $0.range(of: query, options: .caseInsensitive) != nil }
                guard !visible.isEmpty else { continue }
                if links.count == 40 { return SharedMediaPage(items: [], older: lastMatch, links: links) }
                links.append(SharedLink(id: cursor.messageId, timestamp: cursor.timestamp, urls: visible))
                lastMatch = cursor
            }
            if rows.count < 80 { return SharedMediaPage(items: [], older: nil, links: links) }
            after = rows.last?.0
        }
    }

    func sharedMediaItem(peer: String, messageId: String) throws -> SharedMediaItem? {
        try AppDatabase.shared.query(sharedMediaColumns + " WHERE peer=? AND message_id=? AND " + sharedMediaVisible,
                                     values: [.text(peer), .text(messageId), .integer(Int64(Date().timeIntervalSince1970 * 1_000))], map: readSharedMedia).first
    }

    func visualMediaNeighbors(peer: String, anchor: SharedMediaCursor, category: SharedMediaCategory = .media,
                              query: String = "", before: SharedMediaCursor? = nil) throws -> VisualMediaNeighbors {
        guard category.visual else { return VisualMediaNeighbors(newer: nil, older: nil) }
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        let categoryFilter = category == .media ? "category IN ('photos','videos')" : "category=?"
        let search = query.isEmpty ? "" : " AND instr(lower(filename),lower(?))>0"
        let boundary = before == nil ? "" : " AND (created_at,message_id)<(?,?)"
        var values: [DatabaseValue] = [.text(peer)]
        if category != .media { values.append(.text(category.rawValue)) }
        values.append(.integer(now))
        if !query.isEmpty { values.append(.text(query)) }
        if let before { values += [.integer(before.timestamp), .text(before.messageId)] }
        values += [.integer(anchor.timestamp), .integer(anchor.timestamp), .text(anchor.messageId)]
        return try AppDatabase.shared.transaction { db in
            func adjacent(newer: Bool) throws -> SharedMediaItem? {
                let comparison = newer ? ">" : "<"
                let order = newer ? "ASC" : "DESC"
                let filter = """
                     WHERE peer=? AND \(categoryFilter) AND available=1
                     AND \(sharedMediaVisible)
                     \(search)\(boundary)
                     AND (created_at\(comparison)? OR (created_at=? AND message_id\(comparison)?))
                     ORDER BY created_at \(order),message_id \(order) LIMIT 1
                    """
                return try db.query(sharedMediaColumns + filter, values: values, map: readSharedMedia).first
            }
            return try VisualMediaNeighbors(newer: adjacent(newer: true), older: adjacent(newer: false))
        }
    }

    private func readSharedMedia(_ row: OpaquePointer) -> SharedMediaItem {
        let media = AttachmentMetadata(
            reference: Self.reference(String(cString: sqlite3_column_text(row, 3))),
            filename: String(cString: sqlite3_column_text(row, 4)),
            mime: String(cString: sqlite3_column_text(row, 5)),
            size: sqlite3_column_int64(row, 6),
            available: sqlite3_column_int(row, 7) == 1,
            caption: String(cString: sqlite3_column_text(row, 8)),
            remoteUnavailable: sqlite3_column_int(row,9) != 0,
            downloadIntent: AttachmentDownloadIntent(rawValue: sqlite3_column_int(row,10)) ?? .automatic
        )
        return SharedMediaItem(id: String(cString: sqlite3_column_text(row, 0)), timestamp: sqlite3_column_int64(row, 1),
                               isMine: sqlite3_column_int(row, 2) != 0, attachment: media)
    }

    struct TimelineAlbumItem: Equatable, Sendable {
        let position: AlbumPosition
        let groupCaption: String
    }

    func timelineAlbumItems(peer: String, messageIds: [String]) throws -> [String: TimelineAlbumItem] {
        var items: [String: TimelineAlbumItem] = [:]
        for offset in stride(from: 0, to: messageIds.count, by: 400) {
            let ids = messageIds[offset..<min(offset + 400, messageIds.count)]
            let parameters = Array(repeating: "?", count: ids.count).joined(separator: ",")
            let rows = try AppDatabase.shared.query(
                """
                SELECT g.message_id,g.album_id,g.position,g.item_count,COALESCE(a.caption,'')
                FROM message_album_items g
                LEFT JOIN message_attachments a ON a.message_id=g.message_id AND g.position=0
                WHERE g.peer=? AND g.message_id IN (\(parameters))
                """,
                values: [.text(peer)] + ids.map { .text($0) }
            ) { row in
                (String(cString: sqlite3_column_text(row, 0)),
                 TimelineAlbumItem(position: AlbumPosition(id: String(cString: sqlite3_column_text(row, 1)),
                    index: Int(sqlite3_column_int(row, 2)), count: Int(sqlite3_column_int(row, 3))),
                    groupCaption: String(cString: sqlite3_column_text(row, 4))))
            }
            for (messageId, item) in rows { items[messageId] = item }
        }
        return items
    }

    func readStickerBytes(_ reference:String) throws -> Data {
        try validateSticker(reference);let id=try Self.id(reference)
        guard let encoded=try AppDatabase.shared.query("SELECT manifest_json FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(id)],map:{String(cString:sqlite3_column_text($0,0))}).first,
              let manifest=try JSONSerialization.jsonObject(with:Data(encoded.utf8)) as? [String:Any],let size=manifest["size"] as? Int,(1...8*1024*1024).contains(size),let expected=manifest["sha256"] as? String else {throw SecurityCore.Failure.invalidResponse}
        var bytes=Data();bytes.reserveCapacity(size)
        do {while bytes.count<size {var part=try read(reference,offset:UInt64(bytes.count),length:min(1024*1024,size-bytes.count));defer {part.resetBytes(in:0..<part.count)};bytes.append(part)}
            guard SHA256.hash(data:bytes).map({String(format:"%02x",$0)}).joined()==expected else {throw SecurityCore.Failure.operation("Sticker content changed.")};return bytes
        }catch{bytes.resetBytes(in:0..<bytes.count);throw error}
    }
    func validateSticker(_ reference:String) throws {
        let id=try Self.id(reference)
        guard let encoded=try AppDatabase.shared.query("SELECT manifest_json FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(id)],map:{String(cString:sqlite3_column_text($0,0))}).first else {throw SecurityCore.Failure.operation("Sticker is unavailable.")}
        _=try SecurityCore.call(["operation":"inspect_sticker_object","manifest":JSONSerialization.jsonObject(with:Data(encoded.utf8)),"directory":directory.path])
    }

    /// Clipboard bytes go directly to authenticated encrypted chunks, never /tmp.
    func importBytes(_ bytes: Data, filename: String, mime: String,preparation:ObjectPreparation?=nil) throws -> [String: Any] {
        try preparation?.check()
        guard bytes.count <= 100 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var excluded = directory; try excluded.setResourceValues(values)
        var manifest = try KeychainManager.shared.withRootSeed { seed in
            try SecurityCore.call(["operation": "create_object", "seed": seed.base64EncodedString(), "size": bytes.count])
        }
        manifest["filename"] = filename; manifest["mime"] = mime
        manifest["sha256"] = String(repeating: "0", count: 64); manifest["cipher_sha256"] = String(repeating: "0", count: 64)
        let checked = try SecurityCore.call(["operation": "validate_object_manifest", "manifest": manifest])
        guard let metadata = checked["manifest"] as? [String: Any], let id = metadata["object_id"] as? String,
              let count = metadata["chunk_count"] as? Int else { throw SecurityCore.Failure.invalidResponse }
        manifest = metadata
        _=try stage(metadata);var prepared=false
        defer {if !prepared {try? lifecycle.discard(id)}}
        let target = try path(id)
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let descriptor = Darwin.open(target.path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard descriptor >= 0 else { throw CocoaError(.fileWriteUnknown) }
        let output = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        do {
            try preparation?.report(total:Int64(bytes.count),completed:0)
            var plainHash = SHA256(), cipherHash = SHA256()
            for index in 0..<count {
                try preparation?.check()
                let start = index * 65536, end = min(bytes.count, start + 65536)
                var plain = bytes.subdata(in: start..<end)
                defer { plain.resetBytes(in: plain.startIndex..<plain.endIndex) }
                plainHash.update(data: plain)
                let result = try SecurityCore.call(["operation": "seal_object_chunk", "chunk": ["key": manifest["key"]!,
                    "object_id": id, "size": bytes.count, "index": index, "data": plain.base64EncodedString()]])
                guard let encoded = result["data"] as? String, let cipher = Data(base64Encoded: encoded) else { throw SecurityCore.Failure.invalidResponse }
                try output.write(contentsOf: cipher); cipherHash.update(data: cipher)
                try preparation?.report(total:Int64(bytes.count),completed:Int64(end))
            }
            try preparation?.check()
            try output.synchronize(); try output.close()
            _ = try SecurityCore.call(["operation": "sync_object_publication", "directory": directory.path, "object_id": id])
            try preparation?.check()
            manifest["sha256"] = plainHash.finalize().map { String(format: "%02x", $0) }.joined()
            manifest["cipher_sha256"] = cipherHash.finalize().map { String(format: "%02x", $0) }.joined()
            prepared=true
            return manifest
        } catch {try? output.close();throw error}
    }

    func readImageBytes(_ media: AttachmentMetadata, maximumBytes: Int64) throws -> Data {
        guard media.available, media.isImage, media.size > 0, media.size <= maximumBytes else {
            throw CocoaError(.fileReadCorruptFile)
        }
        var bytes = Data(); bytes.reserveCapacity(Int(media.size))
        do {
            while bytes.count < media.size {
                let count = min(1048576, Int(media.size) - bytes.count)
                var chunk = try read(media.reference, offset: UInt64(bytes.count), length: count)
                defer { chunk.resetBytes(in: chunk.startIndex..<chunk.endIndex) }
                guard chunk.count == count else { throw CocoaError(.fileReadCorruptFile) }
                bytes.append(chunk)
            }
            return bytes
        } catch {
            bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex)
            throw error
        }
    }

    func image(_ media: AttachmentMetadata, maximumDimension: Int) throws -> NSImage {
        NSImage(cgImage: try imageRaster(media, maximumDimension: maximumDimension), size: .zero)
    }

    func imageRaster(_ media: AttachmentMetadata, maximumDimension: Int) throws -> CGImage {
        var bytes = try readImageBytes(media, maximumBytes: 100 * 1024 * 1024)
        defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
        guard let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int, let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 64_000_000 / height,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: maximumDimension] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        return image
    }

    func thumbnailBytes(_ reference: String, edge: Int) throws -> Data? {
        let thumbnail = try AppDatabase.shared.query("""
            SELECT t.thumbnail_object,json_extract(preview.manifest_json,'$.size')
            FROM media_thumbnail_cache t JOIN media_objects o ON o.object_id=t.object_id
            JOIN media_objects preview ON preview.object_id=t.thumbnail_object
            WHERE t.object_id=? AND t.edge=? AND o.local_state=1 AND preview.local_state=1
            AND json_extract(preview.manifest_json,'$.size') BETWEEN 1 AND 131072
            AND EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id)
            """, values: [.text(try Self.id(reference)), .integer(Int64(edge))]) { row in
            (String(cString: sqlite3_column_text(row, 0)), Int(sqlite3_column_int64(row, 1)))
        }.first
        guard let (id, size) = thumbnail else { return nil }
        return try read(Self.referencePrefix + id, offset: 0, length: size)
    }

    func cacheThumbnail(_ reference: String, edge: Int, encoded: Data, mime: String) throws {
        guard (1...640).contains(edge), (1...131072).contains(encoded.count),
              mime == "image/jpeg" || mime == "image/png" else { return }
        let id = try Self.id(reference)
        let staged = try importBytes(encoded, filename: mime == "image/jpeg" ? "thumbnail.jpg" : "thumbnail.png", mime: mime)
        var committed = false
        defer { if !committed { try? discardCopy(staged) } }
        guard let thumbnail = staged["object_id"] as? String else { throw SecurityCore.Failure.invalidResponse }
        committed = try AppDatabase.shared.transaction { db in
            let stillOwned = try db.query("""
                SELECT 1 FROM media_objects o WHERE o.object_id=? AND o.local_state=1
                AND EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id)
                """, values: [.text(id)]) { _ in true }.first ?? false
            guard stillOwned else { return false }
            try Self.remember(staged, in: db, available: true)
            try db.execute("""
                INSERT INTO media_thumbnail_cache(object_id,edge,thumbnail_object) VALUES(?,?,?)
                ON CONFLICT(object_id,edge) DO UPDATE SET thumbnail_object=excluded.thumbnail_object
                """, values: [.text(id), .integer(Int64(edge)), .text(thumbnail)])
            return true
        }
    }

    func export(_ reference: String, to destination: URL, progress: (Int64) -> Void = { _ in },
                allowed: () throws -> Bool = { true }) throws {
        try Task.checkCancellation()
        guard try allowed() else { throw CocoaError(.fileReadNoPermission) }
        let manifest = try manifest(reference)
        guard let size = manifest["size"] as? Int64 else { throw SecurityCore.Failure.invalidResponse }
        let temporary = destination.deletingLastPathComponent().appendingPathComponent(".msgat-export-\(UUID().uuidString)")
        let fd = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard fd >= 0 else { throw CocoaError(.fileWriteUnknown) }
        let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? output.close(); try? FileManager.default.removeItem(at: temporary) }
        var offset: Int64 = 0
        while offset < size {
            try Task.checkCancellation()
            var bytes = try read(reference, offset: UInt64(offset), length: Int(min(1048576, size - offset)))
            defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
            guard !bytes.isEmpty else { throw CocoaError(.fileReadCorruptFile) }
            try output.write(contentsOf: bytes)
            offset += Int64(bytes.count)
            progress(offset)
        }
        try Task.checkCancellation()
        try output.synchronize(); try output.close()
        guard try allowed() else { throw CocoaError(.fileReadNoPermission) }
        try Task.checkCancellation()
        if FileManager.default.fileExists(atPath: destination.path) { _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary) }
        else { try FileManager.default.moveItem(at: temporary, to: destination) }
    }

    func manifest(_ reference: String) throws -> [String:Any] {
        let id = try Self.id(reference)
        let values = try AppDatabase.shared.query("SELECT manifest_json FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(id)]) {
            String(cString:sqlite3_column_text($0,0))
        }
        guard let encoded = values.first, let value = try JSONSerialization.jsonObject(with:Data(encoded.utf8)) as? [String:Any] else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        return value
    }

    func hasCompleteCiphertext(_ reference: String, check: () throws -> Void = {}) throws -> Bool {
        do {
            let manifest = try manifest(reference)
            guard let expected = manifest["cipher_size"] as? Int64,
                  let hash = manifest["cipher_sha256"] as? String else { throw SecurityCore.Failure.invalidResponse }
            let file = try path(Self.id(reference))
            let values = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, let size = values.fileSize else {
                throw CocoaError(.fileReadCorruptFile)
            }
            guard Int64(size) == expected else { return false }
            let input = try FileHandle(forReadingFrom: file)
            defer { try? input.close() }
            var remaining = expected
            var digest = SHA256()
            while remaining > 0 {
                try Task.checkCancellation()
                try check()
                guard let bytes = try input.read(upToCount: Int(min(remaining, 64 * 1024))), !bytes.isEmpty else {
                    return false
                }
                digest.update(data: bytes)
                remaining -= Int64(bytes.count)
            }
            return (try input.read(upToCount: 1)?.isEmpty ?? true) &&
                digest.finalize().map { String(format: "%02x", $0) }.joined() == hash
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {
            return false
        }
    }

    func checkCiphertext(_ reference: String) async throws -> Bool {
        let work = Task.detached(priority: .userInitiated) { try self.hasCompleteCiphertext(reference) }
        return try await withTaskCancellationHandler {
            try await work.value
        } onCancel: {
            work.cancel()
        }
    }

    func read(_ reference: String, offset: UInt64, length: Int) throws -> Data {
        let result = try SecurityCore.call(["operation":"read_object_range", "manifest":try manifest(reference),
            "directory":directory.path, "offset":offset, "length":length])
        guard let encoded = result["data"] as? String, let data = Data(base64Encoded:encoded) else { throw SecurityCore.Failure.invalidResponse }
        return data
    }

    func requireFreeSpace(_ cipherBytes: Int64) throws {
        if cipherBytes==0 {return}
        let reserve: Int64=256*1024*1024
        guard cipherBytes>0,cipherBytes<=Int64.max-reserve else {throw CocoaError(.fileWriteOutOfSpace)}
        var location=directory
        while !FileManager.default.fileExists(atPath:location.path) {
            let parent=location.deletingLastPathComponent();guard parent != location else {throw CocoaError(.fileReadUnknown)};location=parent
        }
        guard let free=try FileManager.default.attributesOfFileSystem(forPath:location.path)[.systemFreeSize] as? NSNumber else {throw CocoaError(.fileReadUnknown)}
        if free.int64Value<cipherBytes+reserve {throw InsufficientObjectSpaceError.insufficientSpace}
    }
    func requireDownloadSpace(_ manifest: [String:Any]) throws {
        try requireFreeSpace(remainingDownloadBytes(manifest))
    }

    func remainingDownloadBytes(_ manifest: [String:Any]) throws -> Int64 {
        guard let expected=manifest["cipher_size"] as? Int64,let id=manifest["object_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
        let target=try path(id)
        func length(_ url: URL) throws -> Int64 {
            do {
                return (try FileManager.default.attributesOfItem(atPath:url.path)[.size] as? NSNumber)?.int64Value ?? 0
            } catch let error as CocoaError where error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile {
                return 0
            }
        }
        if try length(target)==expected {return 0}
        let partial = try length(target.appendingPathExtension("part"))
        let retained = partial > expected ? 0 : partial - partial % 65_552
        return expected - retained
    }
    func path(_ id: String) throws -> URL {
        guard id.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil else { throw CocoaError(.fileReadInvalidFileName) }
        return directory.appendingPathComponent(String(id.prefix(2)),isDirectory:true).appendingPathComponent(String(id.dropFirst(2))+".obj")
    }
    static func reference(_ id: String) -> String { referencePrefix+id }
    private static func id(_ reference: String) throws -> String {
        guard reference.hasPrefix(referencePrefix) else { throw CocoaError(.fileReadInvalidFileName) }
        let id = String(reference.dropFirst(referencePrefix.count))
        guard id.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil else { throw CocoaError(.fileReadInvalidFileName) }
        return id
    }
}
