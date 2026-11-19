// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import AppKit
import ImageIO
import Darwin
#if canImport(MsgATPreviewHTTP)
import MsgATPreviewHTTP
#endif

struct WebPreviewDraft: @unchecked Sendable {
    let url: String;let title: String;let summary: String;let site: String;var image: Data?
    var aboveText = false
    var compactImage = false
    mutating func clearImage(){if var bytes=image {image=nil;bytes.resetBytes(in:0..<bytes.count)}}
    func body(image: [String:Any]?) -> [String:Any] {
        var result:[String:Any]=["url":url,"title":title,"summary":summary,"site":site,"image":NSNull()]
        if aboveText { result["above_text"] = true }
        if let image {
            result["image"] = image
            if compactImage { result["compact_image"] = true }
        }
        return result
    }
}
enum WebPreviewFetcher {
    private static let workers: OperationQueue={let q=OperationQueue();q.name="org.msgat.web-preview";q.qualityOfService = .utility;q.maxConcurrentOperationCount=2;return q}()
    private static let gate = NSLock()
    private static var stopping = false
    private static var requests = [UUID: Cancellation]()
    private final class Cancellation: @unchecked Sendable {
        let pointer=msgat_preview_cancel_create()
        private let lock=NSLock()
        private var cancelled=false
        var isCancelled: Bool {lock.lock();defer {lock.unlock()};return cancelled}
        func cancel(){lock.lock();cancelled=true;lock.unlock();msgat_preview_cancel(pointer)}
        deinit {msgat_preview_cancel_free(pointer)}
    }
    private struct Response {let url: URL;let type: String;var body: Data}
    static func candidates(_ text: String) throws -> [String] {
        try SecurityCore.call(["operation": "visible_message_urls", "text": text])["urls"] as? [String] ?? []
    }

    static func fetchURL(_ candidate: String) async -> WebPreviewDraft? {
        let id = UUID()
        let cancellation=Cancellation()
        guard gate.withLock({
            guard !stopping else { return false }
            requests[id] = cancellation
            return true
        }) else { return nil }
        defer { gate.withLock { _ = requests.removeValue(forKey: id) } }
        return await withTaskCancellationHandler(operation:{
            await withCheckedContinuation {continuation in
                workers.addOperation {
                    do {continuation.resume(returning:try fetchURL(candidate,cancellation:cancellation))}
                    catch {continuation.resume(returning:nil)}
                }
            }
        },onCancel:{cancellation.cancel()})
    }

    static func stop() {
        let pending = gate.withLock {
            stopping = true
            return Array(requests.values)
        }
        for request in pending { request.cancel() }
    }
    private static func fetchURL(_ candidate: String,cancellation: Cancellation) throws -> WebPreviewDraft? {
        guard !cancellation.isCancelled,let url=URL(string:candidate) else {return nil}
        let deadline=ProcessInfo.processInfo.systemUptime+12
        var page=try get(url,maximum:1024*1024,deadline:deadline,cancellation:cancellation)
        defer {page.body.resetBytes(in:page.body.startIndex..<page.body.endIndex)}
        guard ["text/html","application/xhtml+xml"].contains(page.type) else {return nil}
        let metadata=try SecurityCore.call(["operation":"inspect_web_page","url":candidate,"html":String(decoding:page.body,as:UTF8.self)])
        var image: Data?
        if let source=metadata["image_url"] as? String,!source.isEmpty,let coverURL=URL(string:source,relativeTo:page.url)?.absoluteURL {
            do {
                var cover=try get(coverURL,maximum:2*1024*1024,deadline:deadline,cancellation:cancellation)
                defer {cover.body.resetBytes(in:cover.body.startIndex..<cover.body.endIndex)}
                if ["image/jpeg","image/png","image/webp"].contains(cover.type) {image=try normalize(cover.body)}
            } catch { /* Keep text metadata when the cover cannot be fetched. */ }
        }
        if cancellation.isCancelled {if var bytes=image {image=nil;bytes.resetBytes(in:0..<bytes.count)};return nil}
        return WebPreviewDraft(url:candidate,title:metadata["title"] as? String ?? "",summary:metadata["summary"] as? String ?? "",site:metadata["site"] as? String ?? "",image:image)
    }
    private static func get(_ initial: URL,maximum: Int,deadline: TimeInterval,cancellation: Cancellation) throws -> Response {
        var url=initial
        for hop in 0...3 {
            guard !cancellation.isCancelled else {throw CancellationError()}
            let description=try SecurityCore.call(["operation":"web_preview_target","url":url.absoluteString])
            guard let host=description["host"] as? String,let port=description["port"] as? Int,cancellation.pointer != nil else {throw URLError(.badURL)}
            let addresses=try resolve(host)
            _=try SecurityCore.call(["operation":"web_preview_policy","url":url.absoluteString,"addresses":addresses])
            let pinned=host.contains(":") ? "" : "\(host):\(port):"+addresses.map {$0.contains(":") ? "[\($0)]" : $0}.joined(separator:",")
            let remaining=Int((deadline-ProcessInfo.processInfo.systemUptime)*1000)
            guard remaining>0 else {throw URLError(.timedOut)}
            var raw=MsgatPreviewHTTPResult()
            let status=url.absoluteString.withCString {address in pinned.withCString {resolution in msgat_preview_http_fetch(address,resolution,maximum,min(12000,remaining),cancellation.pointer,&raw)}}
            defer {msgat_preview_http_free(&raw)}
            guard status==0 else {throw URLError(.cannotLoadFromNetwork)}
            let type=withUnsafePointer(to:&raw.content_type){$0.withMemoryRebound(to:CChar.self,capacity:128){String(cString:$0)}}.split(separator:";").first.map(String.init)?.trimmingCharacters(in:.whitespacesAndNewlines).lowercased() ?? ""
            let location = withUnsafePointer(to: &raw) { result in
                guard let value = msgat_preview_http_location(result) else { return "" }
                return String(cString: value)
            }
            if [301,302,303,307,308].contains(Int(raw.status)) {
                guard hop<3,!location.isEmpty,let next=URL(string:location,relativeTo:url)?.absoluteURL,url.scheme?.lowercased() != "https" || next.scheme?.lowercased()=="https" else {throw URLError(.redirectToNonExistentLocation)}
                url=next;continue
            }
            guard (200...299).contains(Int(raw.status)),let body=raw.body else {throw URLError(.badServerResponse)}
            return Response(url:url,type:type,body:Data(bytes:body,count:raw.length))
        }
        throw URLError(.httpTooManyRedirects)
    }
    private static func resolve(_ host: String) throws -> [String] {
        var hints=addrinfo();hints.ai_family=AF_UNSPEC;hints.ai_socktype=SOCK_STREAM;hints.ai_protocol=IPPROTO_TCP
        var first: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host,nil,&hints,&first)==0 else {throw URLError(.cannotFindHost)}
        defer {if let first {freeaddrinfo(first)}}
        var result=[String]();var cursor=first
        while let current=cursor,result.count<65 {
            let value=current.pointee;var buffer=[CChar](repeating:0,count:Int(NI_MAXHOST))
            if getnameinfo(value.ai_addr,value.ai_addrlen,&buffer,socklen_t(buffer.count),nil,0,NI_NUMERICHOST)==0 {let address=String(cString:buffer);if !result.contains(address){result.append(address)}}
            cursor=value.ai_next
        }
        return result
    }
    private static func normalize(_ data: Data) throws -> Data {
        guard let source=CGImageSourceCreateWithData(data as CFData,[kCGImageSourceShouldCache:false] as CFDictionary),
              let properties=CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],let w=properties[kCGImagePropertyPixelWidth] as? Int,let h=properties[kCGImagePropertyPixelHeight] as? Int,
              w>0,h>0,w<=64_000_000/h,let cg=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:512] as CFDictionary),
              let image=NSBitmapImageRep(cgImage:cg).representation(using:.jpeg,properties:[.compressionFactor:0.82]),!image.isEmpty,image.count<=512*1024 else {throw URLError(.cannotDecodeContentData)}
        return image
    }
}
