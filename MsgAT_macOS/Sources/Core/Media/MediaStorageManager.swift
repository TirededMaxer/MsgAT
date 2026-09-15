import Foundation
import AppKit
import AVFoundation
import ImageIO

public final class MediaStorageManager {
    public static let shared = MediaStorageManager()

    private init() {
        let dir = mediaDirectory
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
    }

    public var mediaDirectory: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let mediaDir = appSupport.appendingPathComponent("org.msgat.mac/media", isDirectory: true)
        return mediaDir
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
