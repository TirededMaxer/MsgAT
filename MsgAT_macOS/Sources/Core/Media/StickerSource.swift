// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if canImport(MsgATNativeImports)
import MsgATNativeImports
#endif

struct StickerSourceEntry: Equatable,Sendable {let id:Int;let name:String;let size:Int;let compressedSize:Int;let method:Int}
struct StickerImageInfo: Sendable {
    let width:Int;let height:Int;let frames:Int
    func validate() throws {guard (1...2048).contains(width),(1...2048).contains(height),(1...300).contains(frames) else {throw SecurityCore.Failure.operation("Sticker dimensions or frame count exceed their limits.")}}
}
struct StickerSourceImage: Sendable {
    var bytes:Data;let mime:String;let ext:String;let sha256:String;let info:StickerImageInfo
    mutating func clear(){bytes.resetBytes(in:0..<bytes.count)}
}
final class StickerSource: @unchecked Sendable {
    let format:String;let entries:[StickerSourceEntry]
    private let session:String;private let url:URL;private let scoped:Bool
    private let gate=NSLock();private var closed=false;private let readers=DispatchGroup()
    private let queue:OperationQueue={let q=OperationQueue();q.name="org.msgat.sticker-read";q.maxConcurrentOperationCount=2;q.qualityOfService = .utility;return q}()
    private init(session:String,format:String,entries:[StickerSourceEntry],url:URL,scoped:Bool){self.session=session;self.format=format;self.entries=entries;self.url=url;self.scoped=scoped}
    static func open(_ url:URL) async throws -> StickerSource {
        try await EncryptedMediaWork.run {
            let scoped=url.startAccessingSecurityScopedResource();var session:String?
            do {
                let result=try SecurityCore.call(["operation":"open_sticker_source","source":url.path]);session=result["session"] as? String
                guard let session,let format=result["format"] as? String,let values=result["entries"] as? [[String:Any]] else {throw SecurityCore.Failure.invalidResponse}
                let entries=try values.map {value -> StickerSourceEntry in
                    guard let id=value["id"] as? Int,let name=value["name"] as? String,let size=value["size"] as? Int,let packed=value["compressed_size"] as? Int,let method=value["method"] as? Int else {throw SecurityCore.Failure.invalidResponse}
                    return StickerSourceEntry(id:id,name:name,size:size,compressedSize:packed,method:method)
                }
                return StickerSource(session:session,format:format,entries:entries,url:url,scoped:scoped)
            } catch {if let session {_=try? SecurityCore.call(["operation":"close_sticker_source","session":session])};if scoped {url.stopAccessingSecurityScopedResource()};throw error}
        }
    }
    private func requireOpen() throws {gate.lock();defer {gate.unlock()};if closed {throw CancellationError()}}
    func read(_ entry:StickerSourceEntry) async throws -> StickerSourceImage {
        try await withCheckedThrowingContinuation {continuation in
            gate.lock();defer {gate.unlock()}
            guard !closed,entries.contains(entry) else {continuation.resume(throwing:CancellationError());return}
            readers.enter()
            queue.addOperation {defer {self.readers.leave()};do {continuation.resume(returning:try self.readCore(entry))}catch{continuation.resume(throwing:error)}}
        }
    }
    func readOnImportWorker(_ entry:StickerSourceEntry) throws -> StickerSourceImage {
        gate.lock();guard !closed,entries.contains(entry) else {gate.unlock();throw CancellationError()};readers.enter();gate.unlock()
        defer {readers.leave()};return try readCore(entry)
    }
    private func readCore(_ entry:StickerSourceEntry) throws -> StickerSourceImage {
        var packed=Data();packed.reserveCapacity(entry.compressedSize);var image=Data();var returned=false;var inspected=false
        defer {
            if !inspected {_=try? SecurityCore.call(["operation":"discard_sticker_inspection","session":session,"id":entry.id])}
            packed.resetBytes(in:0..<packed.count);if !returned {image.resetBytes(in:0..<image.count)}
        }
        var offset=0
        while offset<entry.compressedSize {
            try requireOpen();let length=min(1024*1024,entry.compressedSize-offset)
            let result=try SecurityCore.call(["operation":"read_sticker_source","session":session,"id":entry.id,"offset":offset,"length":length])
            guard let encoded=result["data"] as? String,var block=Data(base64Encoded:encoded),block.count==length else {throw SecurityCore.Failure.invalidResponse}
            packed.append(block);block.resetBytes(in:0..<block.count);offset+=length
        }
        if entry.method==0 {guard packed.count==entry.size else {throw SecurityCore.Failure.invalidResponse};image=packed;packed=Data()}
        else if entry.method==8 {
            image=Data(count:entry.size)
            let result=packed.withUnsafeBytes {input in image.withUnsafeMutableBytes {output in msgat_sticker_inflate(input.bindMemory(to:UInt8.self).baseAddress,packed.count,output.bindMemory(to:UInt8.self).baseAddress,entry.size)}}
            guard result==0 else {throw SecurityCore.Failure.operation("Sticker compression is damaged or has an invalid length.")}
        } else {throw SecurityCore.Failure.operation("Unsupported sticker compression.")}
        var identity:[String:Any]?;offset=0
        while offset<image.count {
            try requireOpen();let length=min(1024*1024,image.count-offset);var block=image.subdata(in:offset..<offset+length)
            defer {block.resetBytes(in:0..<block.count)}
            identity=try SecurityCore.call(["operation":"inspect_sticker_chunk","session":session,"id":entry.id,"offset":offset,"bytes":block.base64EncodedString(),"finish":offset+length==image.count]);offset+=length
        }
        guard let value=identity,let mime=value["mime"] as? String,let ext=value["extension"] as? String,let hash=value["sha256"] as? String,let xor=value["xor"] as? UInt8,
              let width=value["width"] as? Int,let height=value["height"] as? Int,let frames=value["frames"] as? Int else {throw SecurityCore.Failure.invalidResponse}
        inspected=true
        if xor != 0 {image.withUnsafeMutableBytes {raw in let bytes=raw.bindMemory(to:UInt8.self);for i in bytes.indices {bytes[i]^=xor}}}
        try requireOpen();let info=StickerImageInfo(width:width,height:height,frames:frames);try info.validate()
        returned=true;return StickerSourceImage(bytes:image,mime:mime,ext:ext,sha256:hash,info:info)
    }
    private func beginClose() -> Bool {gate.lock();defer {gate.unlock()};if closed{return false};closed=true;return true}
    func close() async {
        guard beginClose() else {return}
        await withCheckedContinuation {continuation in
            queue.addBarrierBlock {
                self.readers.wait()
                _=try? SecurityCore.call(["operation":"close_sticker_source","session":self.session]);if self.scoped {self.url.stopAccessingSecurityScopedResource()};continuation.resume()
            }
        }
    }
}
