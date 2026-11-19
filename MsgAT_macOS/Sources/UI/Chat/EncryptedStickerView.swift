// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit
import ImageIO

/// The image source owns authenticated bytes in memory; there is no decrypted URL or disk cache.
enum StickerPixels {
    static func inspect(_ bytes:Data,expected:StickerImageInfo?=nil) throws {
        guard let source=CGImageSourceCreateWithData(bytes as CFData,[kCGImageSourceShouldCache:false] as CFDictionary),
              (1...300).contains(CGImageSourceGetCount(source)),
              let image=CGImageSourceCreateImageAtIndex(source,0,[kCGImageSourceShouldCacheImmediately:true] as CFDictionary),
              (1...2048).contains(image.width),(1...2048).contains(image.height) else {throw CocoaError(.fileReadCorruptFile)}
        if let expected,CGImageSourceGetCount(source) != expected.frames {throw SecurityCore.Failure.operation("This system cannot decode every frame of this sticker. Export it as GIF.")}
    }
    static func thumbnail(_ bytes:Data) throws -> NSImage {
        try inspect(bytes)
        guard let source=CGImageSourceCreateWithData(bytes as CFData,nil),let image=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceThumbnailMaxPixelSize:128,kCGImageSourceShouldCacheImmediately:true] as CFDictionary) else {throw CocoaError(.fileReadCorruptFile)}
        return NSImage(cgImage:image,size:NSSize(width:image.width,height:image.height))
    }
}

private final class StickerFrames:@unchecked Sendable {
    private let gate=NSLock();private var bytes:Data;private var source:CGImageSource?;private var index=0
    private let frameEdge: Int
    private let isGIFAttachment: Bool
    init(reference:String, gif: AttachmentMetadata?) throws {
        let store = EncryptedObjectStore.shared
        isGIFAttachment = gif != nil
        if let gif {
            frameEdge = 2048
            bytes = try store.readImageBytes(gif, maximumBytes: 32 * 1024 * 1024)
        } else {
            frameEdge = 512
            bytes = try store.readStickerBytes(reference)
        }
        do {
            if gif == nil { try StickerPixels.inspect(bytes) }
            guard let decoded = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                  (1...300).contains(CGImageSourceGetCount(decoded)) else { throw CocoaError(.fileReadCorruptFile) }
            source = decoded
        } catch {
            bytes.resetBytes(in: 0..<bytes.count)
            throw error
        }
    }
    func next() throws -> (CGImage,Double,Bool) {
        gate.lock();defer {gate.unlock()}
        guard let source else {throw CancellationError()};let count=CGImageSourceGetCount(source)
        let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] ?? [:]
        if isGIFAttachment {
            guard let width = properties[kCGImagePropertyPixelWidth] as? Int,
                  let height = properties[kCGImagePropertyPixelHeight] as? Int,
                  (1...2048).contains(width), (1...2048).contains(height)
            else { throw CocoaError(.fileReadCorruptFile) }
        }
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceThumbnailMaxPixelSize: frameEdge,
                  kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw CocoaError(.fileReadCorruptFile) }
        var delay=0.1
        // ImageIO's format dictionaries expose unclamped delay for both GIF and WebP.
        for value in properties.values {if let dictionary=value as? [String:Any] {
            let duration=(dictionary["UnclampedDelayTime"] as? NSNumber)?.doubleValue ?? (dictionary["DelayTime"] as? NSNumber)?.doubleValue
            if let duration,duration>0 {delay=duration;break}
        }}
        CGImageSourceRemoveCacheAtIndex(source,index)
        index=(index+1)%count;return(image,max(0.02,min(10,delay)),count>1)
    }
    func close(){gate.lock();defer {gate.unlock()};source=nil;bytes.resetBytes(in:0..<bytes.count);bytes=Data()}
    deinit {close()}
}

private struct StickerPlaybackPausedKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var stickerPlaybackPaused: Bool {
        get { self[StickerPlaybackPausedKey.self] }
        set { self[StickerPlaybackPausedKey.self] = newValue }
    }
}

struct EncryptedStickerView:NSViewRepresentable {
    @Environment(\.stickerPlaybackPaused) private var playbackPaused
    let reference:String
    var gif: AttachmentMetadata? = nil
    var onFailure: (() -> Void)? = nil
    func makeNSView(context:Context)->StickerCanvas {
        let view = StickerCanvas()
        updateNSView(view, context: context)
        return view
    }
    func updateNSView(_ view:StickerCanvas,context:Context){
        view.onFailure = onFailure
        view.setPlaybackPaused(playbackPaused)
        view.setReference(reference, gif: gif)
    }
    static func dismantleNSView(_ view:StickerCanvas,coordinator:()){view.shutdown()}
}

/// Three active animations at most. Offscreen/inactive views release their decoder, not just the timer.
final class StickerCanvas:NSView {
    private static var active=0
    private var reference="";private var frames:StickerFrames?;private var timer:Timer?;private var retry:Timer?
    private var gif: AttachmentMetadata?
    private var observers=[NSObjectProtocol]();private var generation=0;private var loading=false;private var settled=false;private var failed=false;private var ownsSlot=false;private var ended=false
    var onFailure: (() -> Void)?
    private var playbackPaused = false
    override init(frame:NSRect){super.init(frame:frame);wantsLayer=true;layer?.contentsGravity = .resizeAspect}
    required init?(coder:NSCoder){fatalError("init(coder:) has not been implemented")}
    func setPlaybackPaused(_ paused: Bool) {
        guard playbackPaused != paused else { return }
        playbackPaused = paused
        updatePlayback()
    }
    func setReference(_ value: String, gif: AttachmentMetadata?) {
        guard reference != value || self.gif?.mime != gif?.mime else { return }
        stop()
        reference = value
        self.gif = gif
        settled = false
        failed = false
        ended = false
        layer?.contents = nil
        updatePlayback()
    }
    override func viewDidMoveToWindow(){super.viewDidMoveToWindow();observe();updatePlayback()}
    override func viewDidMoveToSuperview(){super.viewDidMoveToSuperview();observe();updatePlayback()}
    override func layout(){super.layout();updatePlayback()}
    private func observe(){
        observers.forEach(NotificationCenter.default.removeObserver);observers=[]
        for name in [NSWindow.didBecomeKeyNotification,NSWindow.didResignKeyNotification,NSApplication.didBecomeActiveNotification,NSApplication.didResignActiveNotification] {observers.append(NotificationCenter.default.addObserver(forName:name,object:nil,queue:.main){[weak self]_ in self?.updatePlayback()})}
        if let clip=enclosingScrollView?.contentView {clip.postsBoundsChangedNotifications=true;observers.append(NotificationCenter.default.addObserver(forName:NSView.boundsDidChangeNotification,object:clip,queue:.main){[weak self]_ in self?.updatePlayback()})}
    }
    private var visible:Bool {!ended && !playbackPaused && window != nil && NSApp.isActive && window?.isKeyWindow==true && !isHiddenOrHasHiddenAncestor && !visibleRect.isEmpty}
    private func updatePlayback(){
        guard visible else {stop();return}
        guard !settled,!failed,!loading,frames==nil,!reference.isEmpty else {return}
        if Self.active>=3 {if retry==nil {retry=Timer.scheduledTimer(withTimeInterval:0.5,repeats:true){[weak self]_ in self?.updatePlayback()}};return}
        retry?.invalidate();retry=nil;Self.active+=1;ownsSlot=true;loading=true
        let version=generation;let ref=reference;let gif=gif
        Task { @MainActor in
            var created:StickerFrames?
            do {
                created=try await EncryptedMediaWork.run {try StickerFrames(reference:ref, gif:gif)}
                guard self.generation==version,self.visible else {if let pending=created {_=try? await EncryptedMediaWork.run {pending.close()}};self.loading=false;self.releaseSlot();self.updatePlayback();return}
                self.frames=created;self.loading=false;self.next()
            }catch {
                self.loading = false
                if self.generation == version {
                    self.failed = true
                    self.onFailure?()
                }
                self.releaseSlot()
                if self.generation != version { self.updatePlayback() }
            }
        }
    }
    private func next(){
        timer?.invalidate();timer=nil;guard let frames,visible else {stop();return};let version=generation
        Task { @MainActor in
            do {let (image,delay,animated)=try await EncryptedMediaWork.run {try frames.next()}
                guard self.generation==version else {return};self.layer?.contents=image
                if animated {self.timer=Timer.scheduledTimer(withTimeInterval:delay,repeats:false){[weak self]_ in self?.next()}}
                else {self.settled=true;self.stop()}
            }catch {
                guard self.generation == version else { return }
                self.failed = true
                self.stop()
                self.onFailure?()
            }
        }
    }
    private func releaseSlot(){if ownsSlot {ownsSlot=false;Self.active-=1}}
    private func stop(){
        generation+=1;timer?.invalidate();timer=nil;retry?.invalidate();retry=nil
        let old=frames;frames=nil
        // Keep the slot until the old source has actually released its encoded bytes.
        if let old {
            let release=ownsSlot;ownsSlot=false
            Task { @MainActor in
                _=try? await EncryptedMediaWork.run {old.close()}
                if release {Self.active-=1}
            }
        } else if !loading {releaseSlot()}
    }
    func shutdown(){ended=true;onFailure=nil;observers.forEach(NotificationCenter.default.removeObserver);observers=[];stop();layer?.contents=nil}
    deinit {timer?.invalidate();retry?.invalidate();observers.forEach(NotificationCenter.default.removeObserver)}
}
