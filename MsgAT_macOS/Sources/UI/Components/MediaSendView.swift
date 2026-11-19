// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit
@preconcurrency import AVFoundation
import AVKit
import Combine
import ImageIO
import UniformTypeIdentifiers

private func readableFileSize(_ url: URL) throws -> Int64 {
    let info = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
    guard info.isRegularFile == true, let length = info.fileSize, length <= 100 * 1024 * 1024 else {
        throw CocoaError(.fileReadTooLarge)
    }
    let reader = try FileHandle(forReadingFrom: url)
    try reader.close()
    return Int64(length)
}

extension MediaSendSource {
    func preview()throws->MediaSendPreview {
        let size:Int64
        let imageSource:CGImageSource?
        switch self {
        case .file(let url):
            size = try readableFileSize(url)
            imageSource=CGImageSourceCreateWithURL(url as CFURL,[kCGImageSourceShouldCache:false] as CFDictionary)
        case .bytes(let bytes,_,_):
            guard bytes.count<=100*1024*1024 else{throw CocoaError(.fileReadTooLarge)}
            size=Int64(bytes.count);imageSource=CGImageSourceCreateWithData(bytes as CFData,[kCGImageSourceShouldCache:false] as CFDictionary)
        case .recording(let result):
            size=try result.file.size;imageSource=nil
        }
        var image:CGImage?
        if let imageSource,let props=CGImageSourceCopyPropertiesAtIndex(imageSource,0,nil) as? [CFString:Any],let width=props[kCGImagePropertyPixelWidth] as? Int,let height=props[kCGImagePropertyPixelHeight] as? Int,width>0,height>0,width<=64_000_000/height {
            image=CGImageSourceCreateThumbnailAtIndex(imageSource,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:512,kCGImageSourceShouldCacheImmediately:true] as CFDictionary)
        }
        if image == nil, let fileURL, mime.hasPrefix("video/") {
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: fileURL))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 512, height: 512)
            image = try? generator.copyCGImage(at: .zero, actualTime: nil)
        }
        return MediaSendPreview(size:size,image:image)
    }
}
struct MediaSendPreview {let size:Int64;let image:CGImage?}
private final class MediaGrantToken:@unchecked Sendable {
    private let lock=NSLock();private var action:(@Sendable()->Void)?
    init(_ action:@escaping @Sendable()->Void){self.action=action}
    func release(){lock.lock();let action=self.action;self.action=nil;lock.unlock();action?()}
    deinit{release()}
}
private struct MediaSendItem: Identifiable {
    let id = UUID()
    let source: MediaSendSource

    var fileURL: URL? { source.fileURL }

    var videoURL: URL? {
        source.mime.hasPrefix("video/") ? fileURL : nil
    }
}

final class MediaSendRequest: Identifiable, @unchecked Sendable {
    let id = UUID()
    let recipient: String
    let initial: SendOptions
    let initialAsFile: Bool
    fileprivate let items: [MediaSendItem]
    private let lock = NSLock()
    private var owners = 1
    private var closed = false
    private var scoped = Set<URL>()
    private let send: @MainActor ([MediaSendSource], MediaSendChoice, @escaping (UUID) -> Void,
                                @escaping @MainActor @Sendable (PreparationSendResult) -> Void) -> Void

    init(sources: [MediaSendSource], recipient: String, initial: SendOptions, initialAsFile: Bool = false,
         send: @escaping @MainActor ([MediaSendSource], MediaSendChoice, @escaping (UUID) -> Void,
                                    @escaping @MainActor @Sendable (PreparationSendResult) -> Void) -> Void) {
        self.items = sources.map { MediaSendItem(source: $0) }
        self.recipient = recipient
        self.initial = initial
        self.initialAsFile = initialAsFile
        self.send = send
        grant(items.compactMap(\.fileURL))
    }

    fileprivate func grant(_ urls: [URL]) {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return }
        for url in urls where !scoped.contains(url) {
            if url.startAccessingSecurityScopedResource() { scoped.insert(url) }
        }
    }

    fileprivate func retain() -> MediaGrantToken? {
        lock.lock()
        guard !closed else { lock.unlock(); return nil }
        owners += 1
        lock.unlock()
        return MediaGrantToken { [self] in release() }
    }

    private func release() {
        lock.lock()
        owners -= 1
        let done = owners == 0
        let urls = done ? scoped : []
        if done { scoped.removeAll() }
        lock.unlock()
        guard done else { return }
        for url in urls { url.stopAccessingSecurityScopedResource() }
        for item in items {
            if case .recording(let result) = item.source { try? result.close() }
        }
    }

    func close() {
        lock.lock()
        let shouldClose = !closed
        closed = true
        lock.unlock()
        if shouldClose { release() }
    }

    @MainActor fileprivate func submit(_ sources: [MediaSendSource], choice: MediaSendChoice,
                                       started: @escaping (UUID) -> Void,
                                       completion: @escaping @MainActor @Sendable (PreparationSendResult) -> Void) {
        guard let grant = retain() else { completion(.failed); return }
        send(sources, choice, started) { result in
            grant.release()
            completion(result)
        }
    }

    deinit { close() }
}

@MainActor
struct MediaSendView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    let request: MediaSendRequest
    @ObservedObject var model: ChatViewModel
    @State private var sending = false
    @State private var preparationFailed = false
    @State private var mounted = true
    @State private var activePreparationID: UUID?
    @State private var items: [MediaSendItem]
    @State private var captions: [UUID: String] = [:]
    @State private var selectedCaption: UUID?
    @State private var captionFocusRevision = 1
    @State private var asFile = false
    @State private var asAlbum = true
    @State private var compressPhotos = false
    @State private var compressVideos = false
    @State private var options: SendOptions
    @State private var showOptions = false
    @State private var trimming: MediaSendItem?
    @State private var editing: MediaSendItem?
    @State private var photoEdits: [UUID: PhotoEditResult] = [:]
    @State private var trims: [URL: VideoTrim] = [:]
    @State private var previews: [UUID: MediaSendPreview] = [:]
    @State private var unavailable = Set<UUID>()
    @State private var previewRetry = UUID()
    @State private var error: String?
    @State private var dragScope = UUID()
    @State private var dropTarget: UUID?
    @State private var filesTargeted = false
    @State private var loadingDrop = false
    @State private var revalidatingSources = false
    @State private var confirmDiscard = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let reorderType = UTType(exportedAs: "org.msgat.attachment-order", conformingTo: .data)

    init(request: MediaSendRequest, model: ChatViewModel) {
        self.request = request
        self.model = model
        _items = State(initialValue: request.items)
        _options = State(initialValue: request.initial)
        _asFile = State(initialValue: request.initialAsFile)
    }

    private var filesOnly: Bool { request.items.allSatisfy { $0.fileURL != nil } }
    private var ready: Bool {
        !sending && !loadingDrop && !revalidatingSources && !items.isEmpty &&
            items.allSatisfy { previews[$0.id] != nil }
    }
    private var canChangeFiles: Bool {
        mounted && filesOnly && !sending && !loadingDrop && !revalidatingSources &&
            !confirmDiscard && !showOptions && editing == nil && trimming == nil
    }
    private var canAddFiles: Bool { canChangeFiles && items.count < 10 }
    private var hasChanges: Bool {
        !captions.isEmpty || items.map(\.id) != request.items.map(\.id) || !photoEdits.isEmpty ||
            !trims.isEmpty || options != request.initial || asFile != request.initialAsFile ||
            !asAlbum || compressPhotos || compressVideos
    }

    private var captionItem: MediaSendItem? {
        items.first(where: { $0.id == selectedCaption }) ?? items.first
    }

    private func captionBinding(for item: MediaSendItem?) -> Binding<String> {
        Binding(get: { item.flatMap { captions[$0.id] } ?? "" }, set: { text in
            guard let item else { return }
            selectedCaption = item.id
            captions[item.id] = text.isEmpty ? nil : text
        })
    }

    private var batchSizeSummary: String? {
        guard items.count > 1, items.allSatisfy({ previews[$0.id] != nil || unavailable.contains($0.id) }) else { return nil }
        let sizes = items.compactMap { item in
            photoEdits[item.id].map { Int64($0.bytes.count) } ?? previews[item.id]?.size
        }
        guard !sizes.isEmpty else { return interfaceText("attachment_size_unknown") }
        let total = ByteCountFormatter.string(fromByteCount: sizes.reduce(0, +), countStyle: .file)
        return interfaceText.format(sizes.count == items.count ? "attachment_source_size" : "attachment_source_size_partial", total)
    }

    private var canCompressPhotos: Bool { !asFile && items.contains { $0.source.canCompress } }

    private var canCompressVideos: Bool {
        !asFile && items.contains { $0.videoURL != nil }
    }

    private var albumEligible: Bool {
        !asFile && items.count >= 2 && items.allSatisfy { item in
            item.fileURL != nil && !unavailable.contains(item.id) &&
                (item.source.mime.hasPrefix("image/") || item.source.mime.hasPrefix("video/"))
        }
    }

    var body: some View {
        let window = NSApp.keyWindow
        let displayHeight = (window?.screen ?? NSScreen.main)?.visibleFrame.height ?? 760
        let availableHeight = min(displayHeight, window?.sheetParent?.contentLayoutRect.height ?? displayHeight)
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        let optionSummary = sendOptionsLabel(options)
        let optionTitle = optionSummary.isEmpty ? interfaceText("send_options") : optionSummary
        let submitTitle = preparationFailed ? interfaceText("send_retry") :
            items.count > 1 ? interfaceText.format("send_count", Int64(items.count)) : interfaceText("send")
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(items.count <= 1 ? interfaceText("send_attachment") : interfaceText.format("send_attachments", Int64(items.count))).font(.headline)
                Text(interfaceText.format("recipient", request.recipient)).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                if let batchSizeSummary {
                    Text(batchSizeSummary).font(.caption).foregroundStyle(.secondary)
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if albumEligible && asAlbum {
                        let wideFirst = !items.count.isMultiple(of: 2)
                        if wideFirst, let first = items.first {
                            attachmentRow(first, albumTile: true, wideTile: true)
                        }
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)],
                                  alignment: .leading, spacing: 8) {
                            ForEach(items.dropFirst(wideFirst ? 1 : 0)) { item in attachmentRow(item, albumTile: true) }
                        }
                    } else {
                        ForEach(items) { item in attachmentRow(item, albumTile: false) }
                    }
                    if !sending, items.count == 1, let item = items.first,
                       case .recording(let recording) = item.source {
                        AttachmentRecordingPlayback(file: recording.file, optionsOpen: showOptions)
                    }
                    if filesOnly {
                        Button(action: addFiles) { Label(interfaceText("add_files"), systemImage: "plus") }.disabled(!canAddFiles)
                        if loadingDrop {
                            ProgressView(interfaceText("preparing_dropped_files")).controlSize(.small)
                        }
                        if !unavailable.isEmpty {
                            Button(interfaceText("remove_unavailable_attachments")) {
                                for item in items.filter({ unavailable.contains($0.id) }) { remove(item) }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: albumEligible && asAlbum)
            }
            .frame(maxHeight: min(380, max(100, availableHeight - 250)))
            .background(colors.timeline, in: RoundedRectangle(cornerRadius: 9))
            Rectangle().fill(colors.divider).frame(height: 1)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                let item = captionItem
                let captionLabel = interfaceText("caption")
                if items.count > 1 {
                    Picker(captionLabel, selection: Binding(get: { item?.id }, set: { target in
                        guard item?.id != target else { return }
                        selectedCaption = target
                        captionFocusRevision += 1
                    })) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { index, candidate in
                            Text(interfaceText.format("attachment_caption_target", Int64(index + 1), candidate.source.name))
                                .tag(Optional(candidate.id))
                        }
                    }
                    .pickerStyle(.menu)
                } else {
                    Text(captionLabel).font(.caption).foregroundStyle(.secondary)
                }
                ImagePasteComposer(text: captionBinding(for: item), onPasteImage: { false }, onSubmit: submit, onTyping: { _ in },
                                   onHistoryCommand: { command in
                                       guard command == .cancelAccessory else { return false }
                                       cancel()
                                       return true
                                   },
                                   focusRevision: captionFocusRevision, accessibilityTitle: captionLabel,
                                   tabNavigates: true, maximumHeight: 92)
                    .frame(minHeight: 44, maxHeight: 92)
                    .background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 6))
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Color.secondary.opacity(0.25)))
                    .disabled(item == nil)
                if let error { Text(error).foregroundStyle(.red).font(.callout) }
                if revalidatingSources {
                    ProgressView(interfaceText("attachment_rechecking_sources")).controlSize(.small)
                }
            }
            HStack(spacing: 8) {
                Menu {
                    Toggle(interfaceText("send_as_file"), isOn: $asFile)
                    if albumEligible { Toggle(interfaceText("send_as_album"), isOn: $asAlbum) }
                    if canCompressPhotos { Toggle(interfaceText("compress_photos"), isOn: $compressPhotos) }
                    if canCompressVideos { Toggle(interfaceText("compress_videos"), isOn: $compressVideos) }
                } label: {
                    Label(interfaceText("attachment_options"), systemImage: "ellipsis")
                        .labelStyle(.iconOnly)
                }
                .fixedSize()
                .help(interfaceText("attachment_options"))
                Button {
                    showOptions = true
                } label: {
                    Label(optionTitle, systemImage: "slider.horizontal.3")
                        .lineLimit(1)
                }
                .help(optionTitle)
                Spacer()
                HStack(spacing: 8) {
                    Button(interfaceText("cancel")) { cancel() }.keyboardShortcut(.cancelAction)
                    Button(submitTitle, action: submit).buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction).disabled(!ready)
                }
                .fixedSize(horizontal: true, vertical: false)
            }
        }
        .padding(16).frame(width: 500)
        .background(colors.chrome)
        .allowsHitTesting(!sending)
        .disabled(sending)
        .overlay {
            if sending {
                let active = model.pendingPreparations.filter { $0.id == activePreparationID }
                VStack(alignment: .leading, spacing: 5) {
                    if active.isEmpty { ProgressView(interfaceText("preparation_waiting")) }
                    else {
                        if let progress = active.first?.progress, progress.phase == "preparing",
                           items.count > 1, items.indices.contains(progress.attachment - 1) {
                            let item = items[progress.attachment - 1]
                            Text((photoEdits[item.id]?.source ?? item.source).name)
                                .font(.caption.weight(.medium)).lineLimit(1).truncationMode(.middle)
                        }
                        PreparationRows(rows: active, onCancel: model.cancelPreparation)
                    }
                }
                .padding(12).frame(width: 340)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
                .allowsHitTesting(true)
            }
        }
        .interactiveDismissDisabled(sending || hasChanges)
        .onExitCommand(perform: cancel)
        .onDisappear { mounted = false }
        .onDrop(of: [.fileURL], isTargeted: $filesTargeted, perform: receiveFiles)
        .overlay(alignment: .top) {
            if filesTargeted && canAddFiles {
                Label(interfaceText("add_files"), systemImage: "plus")
                    .font(.callout.weight(.semibold))
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(.regularMaterial, in: Capsule())
                    .padding(8).allowsHitTesting(false)
            }
        }
        .confirmationDialog(interfaceText("discard_attachment_caption"), isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button(interfaceText("discard"), role: .destructive) { dismiss() }
            Button(interfaceText("keep_editing"), role: .cancel) { captionFocusRevision += 1 }
        }
        .sheet(isPresented: $showOptions, onDismiss: { captionFocusRevision += 1 }) {
            SendOptionsView(initial: options) { options = $0 }
        }
        .sheet(item: $trimming) { item in
            if let url = item.videoURL {
                VideoTrimView(request: request, url: url, initial: trims[url]) { trim in trims[url] = trim }
            }
        }
        .sheet(item: $editing) { item in
            PhotoEditView(request: request, source: item.source, initial: photoEdits[item.id]?.adjustment ?? PhotoAdjustment()) { edit in
                let used = photoEdits.filter { $0.key != item.id }.values.reduce(0) { $0 + $1.bytes.count }
                guard used + (edit?.bytes.count ?? 0) <= 64 * 1024 * 1024 else { return false }
                photoEdits[item.id] = edit
                return true
            }
        }
        .task(id: Set(items.map(\.id)).union([previewRetry])) { await loadPreviews() }
        .onDisappear {
            dragScope = UUID()
            request.close()
        }
    }

    private func attachmentRow(_ item: MediaSendItem, albumTile: Bool, wideTile: Bool = false) -> some View {
        let edit = photoEdits[item.id]
        let preview = edit?.preview ?? previews[item.id]?.image
        let index = items.firstIndex { $0.id == item.id }
        let canEditPhoto = item.source.canCompress && previews[item.id].map {
            $0.image != nil && $0.size <= Int64(PhotoCompression.maximumInput)
        } == true
        let canEditVideo = filesOnly && item.videoURL != nil && previews[item.id] != nil
        let editorTitle = canEditPhoto ? interfaceText("edit_photo") : canEditVideo ? interfaceText("edit_video") : nil
        let openEditor = {
            if canEditPhoto { editing = item }
            else if canEditVideo { trimming = item }
        }
        let attachmentName = edit?.name ?? item.source.name
        return VStack(alignment: .leading, spacing: 6) {
            if albumTile {
                editablePreview(title: editorTitle, name: attachmentName, action: openEditor) {
                    ZStack(alignment: .topTrailing) {
                        GeometryReader { geometry in
                            ZStack {
                                Color(NSColor.windowBackgroundColor)
                                if let preview {
                                    Image(decorative: preview, scale: 1).resizable().scaledToFill()
                                        .frame(width: geometry.size.width, height: geometry.size.height).clipped()
                                } else {
                                    Image(systemName: item.videoURL == nil ? "photo" : "video")
                                        .font(.title2).foregroundStyle(.secondary)
                                }
                                if item.videoURL != nil && preview != nil {
                                    Image(systemName: "play.fill").foregroundStyle(.white).padding(8)
                                        .background(Color.black.opacity(0.55), in: Circle())
                                }
                            }
                        }
                        if let index {
                            attachmentNumber(index).padding(5)
                        }
                    }
                    .frame(height: wideTile ? 160 : 116).clipShape(RoundedRectangle(cornerRadius: 6))
                }
            } else if items.count == 1, let preview {
                editablePreview(title: editorTitle, name: attachmentName, action: openEditor) {
                    Image(decorative: preview, scale: 1).resizable().scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 210)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
            }
            HStack(spacing: 9) {
                if filesOnly && items.count > 1 {
                    Image(systemName: "line.3.horizontal")
                        .foregroundStyle(.secondary)
                        .frame(width: 24, height: 32)
                        .contentShape(Rectangle())
                        .help(interfaceText("drag_to_reorder"))
                        .accessibilityHidden(true)
                        .onDrag { reorderProvider(for: item.id) }
                }
                if !albumTile && (items.count > 1 || preview == nil) {
                    editablePreview(title: editorTitle, name: attachmentName, action: openEditor) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 6).fill(Color(NSColor.windowBackgroundColor))
                            if let preview {
                                Image(decorative: preview, scale: 1).resizable().scaledToFill()
                            } else {
                                Image(systemName: item.videoURL == nil ? "doc" : "video")
                                    .font(.system(size: 16)).foregroundStyle(.secondary)
                            }
                        }
                        .frame(width: 42, height: 42)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .overlay(alignment: .topTrailing) {
                            if items.count > 1, let index { attachmentNumber(index).padding(2) }
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(attachmentName).lineLimit(1).truncationMode(.middle).help(attachmentName)
                    if let url = item.videoURL, let trim = trims[url] {
                        let audio = trim.muteAudio ? " · " + interfaceText("audio_removed") : ""
                        Text("\(videoTrimTime(trim.startMs)) – \(videoTrimTime(trim.endMs))" + audio)
                            .font(.caption).foregroundStyle(.secondary)
                    } else if let size = edit.map({ Int64($0.bytes.count) }) ?? previews[item.id]?.size {
                        Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                    } else if unavailable.contains(item.id) {
                        Text(interfaceText("attachment_unavailable")).font(.caption).foregroundStyle(.red)
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
                Spacer(minLength: 5)
                if filesOnly, let index {
                    if unavailable.contains(item.id) {
                        attachmentButton(interfaceText("retry"), icon: "arrow.clockwise") {
                            unavailable.remove(item.id)
                            previewRetry = UUID()
                        }
                        attachmentButton(interfaceText("replace_attachment"), icon: "arrow.left.arrow.right") {
                            replaceFile(item)
                        }
                        .disabled(!canChangeFiles)
                    }
                    if items.count > 1 {
                        attachmentButton(interfaceText("move_up"), icon: "chevron.up") { move(item.id, by: -1) }.disabled(index == 0)
                        attachmentButton(interfaceText("move_down"), icon: "chevron.down") { move(item.id, by: 1) }.disabled(index == items.count - 1)
                    }
                    attachmentButton(interfaceText("remove_attachment"), icon: "xmark") {
                        remove(item)
                    }
                }
            }
            if canEditPhoto {
                HStack {
                    Button(interfaceText("edit_photo"), action: openEditor)
                    if edit != nil { Button(interfaceText("undo_edit")) { photoEdits[item.id] = nil } }
                }
                .controlSize(.small)
            }
            if canEditVideo, let url = item.videoURL {
                HStack {
                    Button(interfaceText("edit_video"), action: openEditor)
                    if trims[url] != nil { Button(interfaceText("undo_video_edits")) { trims[url] = nil } }
                }
                .controlSize(.small)
            }
        }
        .padding(8)
        .background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
        .overlay {
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(Color.accentColor, lineWidth: dropTarget == item.id ? 2 :
                    items.count > 1 && selectedCaption == item.id ? 1 : 0)
                .allowsHitTesting(false)
        }
        .onDrop(of: [Self.reorderType], isTargeted: Binding(
            get: { dropTarget == item.id },
            set: { targeted in
                if targeted { dropTarget = item.id }
                else if dropTarget == item.id { dropTarget = nil }
            }
        )) { providers in
            receiveReorder(providers, target: item.id)
        }
    }

    @ViewBuilder
    private func editablePreview<Preview: View>(title: String?, name: String, action: @escaping () -> Void,
                                                 @ViewBuilder preview: () -> Preview) -> some View {
        if let title {
            Button(action: action) { preview().contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .help(title)
                .accessibilityLabel(name)
                .accessibilityHint(title)
        } else {
            preview().accessibilityHidden(true)
        }
    }

    private func attachmentButton(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 22, height: 24) }
            .buttonStyle(.borderless).help(title).accessibilityLabel(title)
    }

    private func move(_ id: UUID, by offset: Int) {
        guard let index = items.firstIndex(where: { $0.id == id }), items.indices.contains(index + offset) else { return }
        moveItem(from: index, to: index + offset)
    }

    private func moveItem(from index: Int, to destination: Int) {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
            var reordered = items
            let item = reordered.remove(at: index)
            reordered.insert(item, at: destination)
            items = reordered
        }
    }

    private func attachmentNumber(_ index: Int) -> some View {
        Text("\(index + 1)")
            .font(.caption.monospacedDigit())
            .foregroundStyle(.white)
            .padding(.horizontal, 4)
            .padding(.vertical, 2)
            .background(Color.black.opacity(0.65), in: Capsule())
    }

    private func reorderProvider(for id: UUID) -> NSItemProvider {
        let payload = Data("\(dragScope.uuidString)/\(id.uuidString)".utf8)
        let provider = NSItemProvider()
        provider.registerDataRepresentation(forTypeIdentifier: Self.reorderType.identifier, visibility: .ownProcess) { complete in
            complete(payload, nil)
            return nil
        }
        return provider
    }

    private func receiveReorder(_ providers: [NSItemProvider], target: UUID) -> Bool {
        guard canChangeFiles, items.count > 1, let provider = providers.first else { return false }
        let scope = dragScope
        let prefix = scope.uuidString + "/"
        provider.loadDataRepresentation(forTypeIdentifier: Self.reorderType.identifier) { data, _ in
            guard let data, data.count == 73, let payload = String(data: data, encoding: .utf8),
                  payload.hasPrefix(prefix), let source = UUID(uuidString: String(payload.dropFirst(prefix.count))),
                  source != target else { return }
            Task { @MainActor in
                guard dragScope == scope, canChangeFiles,
                      let from = items.firstIndex(where: { $0.id == source }),
                      let to = items.firstIndex(where: { $0.id == target }) else { return }
                moveItem(from: from, to: to)
            }
        }
        return true
    }

    private func addFiles() {
        guard canAddFiles else { return }
        let scope = dragScope
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        panel.begin { response in
            guard response == .OK, dragScope == scope, canAddFiles else { return }
            appendFiles(panel.urls)
        }
    }

    private func replaceFile(_ item: MediaSendItem) {
        guard canChangeFiles else { return }
        let scope = dragScope
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.begin { response in
            guard response == .OK, dragScope == scope, canChangeFiles, let url = panel.url,
                  let position = items.firstIndex(where: { $0.id == item.id }) else { return }
            if items.contains(where: { $0.id != item.id && $0.fileURL?.standardizedFileURL == url.standardizedFileURL }) {
                error = interfaceText("attachment_already_selected")
                return
            }
            request.grant([url])
            previews[item.id] = nil
            unavailable.remove(item.id)
            photoEdits[item.id] = nil
            if let previous = item.videoURL { trims[previous] = nil }
            let replacement = MediaSendItem(source: .file(url))
            let caption = captions.removeValue(forKey: item.id)
            captions[replacement.id] = caption
            if selectedCaption == item.id { selectedCaption = replacement.id }
            items[position] = replacement
            error = nil
        }
    }

    private func receiveFiles(_ providers: [NSItemProvider]) -> Bool {
        guard canAddFiles, !providers.isEmpty else { return false }
        guard providers.count <= 10 else {
            error = interfaceText("attachment_limit")
            return true
        }
        loadingDrop = true
        let scope = dragScope
        DroppedFileSelection.load(providers) { urls in
            guard dragScope == scope else { return }
            loadingDrop = false
            guard let urls else { error = interfaceText("dropped_files_open_failed"); return }
            appendFiles(urls)
        }
        return true
    }

    private func appendFiles(_ urls: [URL]) {
        var seen = Set(items.compactMap(\.fileURL).map(\.standardizedFileURL))
        let additions = urls.filter { seen.insert($0.standardizedFileURL).inserted }
        guard items.count + additions.count <= 10 else { error = interfaceText("attachment_limit"); return }
        request.grant(additions)
        items.append(contentsOf: additions.map { MediaSendItem(source: .file($0)) })
        error = nil
    }

    private func cancel() {
        if sending {
            if let id = activePreparationID { model.cancelPreparation(id) }
            return
        }
        if hasChanges { confirmDiscard = true }
        else { dismiss() }
    }

    private func remove(_ item: MediaSendItem) {
        items.removeAll { $0.id == item.id }
        captions[item.id] = nil
        if selectedCaption == item.id { selectedCaption = nil }
        previews[item.id] = nil
        photoEdits[item.id] = nil
        unavailable.remove(item.id)
        if let url = item.videoURL { trims[url] = nil }
        error = nil
    }

    private func loadPreviews() async {
        guard let grant = request.retain() else { return }
        defer { grant.release() }
        for item in items where previews[item.id] == nil && !unavailable.contains(item.id) {
            let source = item.source
            do {
                let preview = try await EncryptedMediaWork.run { try source.preview() }
                guard !Task.isCancelled else { return }
                previews[item.id] = preview
            } catch {
                guard !Task.isCancelled else { return }
                unavailable.insert(item.id)
            }
        }
    }

    private func submit() {
        guard ready else { return }
        if let item = items.first(where: { (captions[$0.id]?.utf8.count ?? 0) > 48 * 1024 }) {
            selectedCaption = item.id
            captionFocusRevision += 1
            error = interfaceText("caption_too_long")
            return
        }
        guard options.sendAt.map({ $0 > Int64(Date().timeIntervalSince1970 * 1000) }) ?? true else {
            error = interfaceText("choose_future")
            return
        }
        let sources = items.map { photoEdits[$0.id]?.source ?? $0.source }
        activePreparationID = nil
        error = nil
        preparationFailed = false
        sending = true
        request.submit(sources, choice: MediaSendChoice(options: options, asFile: asFile,
            asAlbum: asAlbum && albumEligible, compressPhotos: compressPhotos && canCompressPhotos,
            compressVideos: compressVideos && canCompressVideos, videoTrims: trims,
            itemCaptions: items.map { captions[$0.id] ?? "" }),
            started: { activePreparationID = $0 }) { result in
                guard mounted else { return }
                activePreparationID = nil
                sending = false
                switch result {
                case .queued: dismiss()
                case .cancelled: captionFocusRevision += 1
                case .failed:
                    preparationFailed = true
                    error = interfaceText("attachment_send_failed")
                    revalidateFileSources()
                    captionFocusRevision += 1
                }
            }
    }

    private func revalidateFileSources() {
        let sources = items.compactMap { item in
            photoEdits[item.id] == nil ? item.fileURL.map { (item.id, $0) } : nil
        }
        guard !sources.isEmpty, let grant = request.retain() else { return }
        let scope = dragScope
        revalidatingSources = true
        Task { @MainActor in
            defer {
                grant.release()
                if dragScope == scope { revalidatingSources = false }
            }
            for (id, url) in sources {
                let readable = (try? await EncryptedMediaWork.run { try readableFileSize(url) }) != nil
                guard mounted, dragScope == scope else { return }
                if !readable, photoEdits[id] == nil, items.contains(where: { $0.id == id }) {
                    previews[id] = nil
                    unavailable.insert(id)
                }
            }
        }
    }
}

@MainActor
private struct AttachmentRecordingPlayback: View {
    let file: EncryptedCaptureFile
    let optionsOpen: Bool
    @StateObject private var playback = MediaPlaybackModel()
    @ObservedObject private var calls = CallManager.shared

    var body: some View {
        RecordingPlaybackBar(playback: playback) { playback.openRecording(file, resumeAt: playback.position) }
            .onAppear { playback.openRecording(file) }
            .onDisappear { playback.close() }
            .onChange(of: optionsOpen) { open in
                if open { playback.pause() }
            }
            .disabled(optionsOpen || calls.callState != .idle && calls.callState != .ended)
    }
}

@MainActor
private struct PhotoEditView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let request: MediaSendRequest
    let source: MediaSendSource
    let initial: PhotoAdjustment
    let onApply: (PhotoEditResult?) -> Bool

    @State private var image: CGImage?
    @State private var adjustment: PhotoAdjustment
    @State private var drag: CropDrag?
    @State private var saveTask: Task<Void, Never>?
    @State private var error: String?

    init(request: MediaSendRequest, source: MediaSendSource, initial: PhotoAdjustment, onApply: @escaping (PhotoEditResult?) -> Bool) {
        self.request = request
        self.source = source
        self.initial = initial
        self.onApply = onApply
        _adjustment = State(initialValue: initial)
    }

    private var canApply: Bool {
        image != nil && saveTask == nil && adjustment != initial && (adjustment.changesPixels || initial.changesPixels)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(interfaceText("edit_photo")).font(.headline)
            ZStack {
                Color.black
                if let image { photoCanvas(image) }
                else if error == nil { ProgressView().tint(.white) }
            }.frame(height: 340).clipShape(RoundedRectangle(cornerRadius: 7))
            if let error { Text(error).foregroundStyle(.red).font(.callout) }
            HStack {
                HStack(spacing: 8) {
                    editTool("rotate_left", icon: "rotate.left") { rotate(clockwise: false) }
                    editTool("rotate_right", icon: "rotate.right") { rotate(clockwise: true) }
                    editTool("mirror_photo", icon: "arrow.left.and.right", active: adjustment.mirrored) {
                        adjustment.flip()
                        drag = nil
                    }
                    Menu {
                        ForEach(PhotoCropRatio.allCases, id: \.self) { ratio in
                            Button {
                                guard let image, adjustment.ratio != ratio else { return }
                                adjustment.selectRatio(ratio, imageAspect: imageAspect(image))
                                drag = nil
                            } label: {
                                if adjustment.ratio == ratio { Label(ratioLabel(ratio), systemImage: "checkmark") }
                                else { Text(ratioLabel(ratio)) }
                            }
                        }
                    } label: {
                        Image(systemName: "aspectratio").foregroundStyle(adjustment.ratio == .free ? Color.primary : Color.accentColor)
                    }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    .accessibilityLabel(interfaceText("crop_ratio"))
                    .accessibilityValue(ratioLabel(adjustment.ratio))
                    .help(interfaceText("crop_ratio"))
                    editTool("reset_crop", icon: "arrow.counterclockwise") {
                        adjustment = PhotoAdjustment()
                        drag = nil
                        error = nil
                    }.disabled(adjustment == PhotoAdjustment())
                }
                .disabled(image == nil || saveTask != nil)
                Spacer()
                Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(saveTask != nil)
                Button(interfaceText(saveTask == nil ? "apply" : "applying"), action: apply)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canApply)
            }
        }
        .padding(20).frame(width: 590)
        .interactiveDismissDisabled(saveTask != nil)
        .task { await open() }
        .onDisappear { saveTask?.cancel() }
    }

    private func editTool(_ key: String, icon: String, active: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).foregroundStyle(active ? Color.accentColor : Color.primary)
                .frame(width: 22, height: 22)
        }
        .accessibilityLabel(interfaceText(key))
        .accessibilityAddTraits(active ? .isSelected : [])
        .help(interfaceText(key))
    }

    private func ratioLabel(_ ratio: PhotoCropRatio) -> String {
        switch ratio {
        case .free: return interfaceText("crop_free")
        case .original: return interfaceText("crop_original")
        case .square: return interfaceText("crop_square")
        default: return ratio.rawValue
        }
    }

    private func imageAspect(_ image: CGImage) -> CGFloat {
        let sideways = adjustment.turns % 2 != 0
        return CGFloat(sideways ? image.height : image.width) / CGFloat(sideways ? image.width : image.height)
    }

    private func photoCanvas(_ image: CGImage) -> some View {
        GeometryReader { geometry in
            let sideways = adjustment.turns % 2 != 0
            let width = CGFloat(sideways ? image.height : image.width)
            let height = CGFloat(sideways ? image.width : image.height)
            let scale = min(geometry.size.width / width, geometry.size.height / height)
            let fit = CGRect(x: (geometry.size.width - width * scale) / 2,
                             y: (geometry.size.height - height * scale) / 2,
                             width: width * scale, height: height * scale)
            let selection = CropDrag.display(adjustment.crop, in: fit)
            ZStack {
                Image(decorative: image, scale: 1).resizable()
                    .frame(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
                    .rotationEffect(.degrees(Double(adjustment.turns) * 90))
                    .scaleEffect(x: adjustment.mirrored ? -1 : 1, y: 1)
                    .position(x: fit.midX, y: fit.midY)
                Path { path in
                    path.addRect(fit)
                    path.addRect(selection)
                }.fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                Path { path in
                    for part in 1...2 {
                        let x = selection.minX + selection.width * CGFloat(part) / 3
                        let y = selection.minY + selection.height * CGFloat(part) / 3
                        path.move(to: CGPoint(x: x, y: selection.minY))
                        path.addLine(to: CGPoint(x: x, y: selection.maxY))
                        path.move(to: CGPoint(x: selection.minX, y: y))
                        path.addLine(to: CGPoint(x: selection.maxX, y: y))
                    }
                }.stroke(.white.opacity(drag == nil ? 0.30 : 0.80), lineWidth: 1)
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.1), value: drag != nil)
                Path { $0.addRect(selection) }.stroke(.white, lineWidth: 1)
                ForEach(CropDrag.Corner.allCases, id: \.self) { corner in
                    let point = corner.point(in: selection)
                    Circle().fill(.white).frame(width: 9, height: 9).position(point)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 1).onChanged { value in
                guard saveTask == nil, fit.width > 0, fit.height > 0 else { return }
                if drag == nil { drag = CropDrag(crop: adjustment.crop, point: value.startLocation, fit: fit) }
                if let drag {
                    let aspect = width / height
                    adjustment.crop = drag.moved(dx: value.translation.width / fit.width, dy: value.translation.height / fit.height,
                                                 ratio: adjustment.ratio.normalized(imageAspect: aspect), imageAspect: aspect)
                }
            }.onEnded { _ in drag = nil })
            .accessibilityLabel(interfaceText("photo_crop"))
        }
    }

    private func open() async {
        guard let grant = request.retain() else { error = interfaceText("photo_unavailable"); return }
        defer { grant.release() }
        do {
            let original = source
            let loaded = try await EncryptedMediaWork.run { try PhotoCompression.openForEditing(original) }
            guard !Task.isCancelled else { return }
            image = loaded
            if loaded == nil { error = interfaceText("photo_format_limit") }
        } catch {
            if !Task.isCancelled { self.error = interfaceText("photo_open_error") }
        }
    }

    private func rotate(clockwise: Bool) {
        adjustment.rotate(clockwise: clockwise)
        drag = nil
    }

    private func apply() {
        guard let image, canApply else { return }
        let edit = adjustment
        if !edit.changesPixels {
            if onApply(nil) { dismiss() }
            return
        }
        let original = source
        error = nil
        saveTask = Task { @MainActor in
            defer { saveTask = nil }
            do {
                let edited = try await EncryptedMediaWork.run {
                    try PhotoCompression.edit(image, adjustment: edit, source: original)
                }
                guard !Task.isCancelled else { return }
                if onApply(edited) { dismiss() }
                else { error = interfaceText("undo_other_edit") }
            } catch {
                if !Task.isCancelled { self.error = interfaceText("photo_edit_error") }
            }
        }
    }
}

private struct CropDrag {
    enum Corner: CaseIterable, Hashable {
        case topLeft, topRight, bottomLeft, bottomRight
        var left: Bool { self == .topLeft || self == .bottomLeft }
        var top: Bool { self == .topLeft || self == .topRight }
        func point(in rect: CGRect) -> CGPoint {
            CGPoint(x: left ? rect.minX : rect.maxX, y: top ? rect.minY : rect.maxY)
        }
    }

    let crop: CGRect
    let corner: Corner?

    init?(crop: CGRect, point: CGPoint, fit: CGRect) {
        let selection = Self.display(crop, in: fit)
        let distances = Corner.allCases.map { corner in
            let anchor = corner.point(in: selection)
            return (corner, hypot(anchor.x - point.x, anchor.y - point.y))
        }
        let closest = distances.min { $0.1 < $1.1 }
        corner = closest.flatMap { $0.1 <= 22 ? $0.0 : nil }
        guard corner != nil || selection.contains(point) else { return nil }
        self.crop = crop
    }

    static func display(_ crop: CGRect, in fit: CGRect) -> CGRect {
        CGRect(x: fit.minX + crop.minX * fit.width, y: fit.minY + crop.minY * fit.height,
               width: crop.width * fit.width, height: crop.height * fit.height)
    }

    func moved(dx: CGFloat, dy: CGFloat, ratio: CGFloat?, imageAspect: CGFloat) -> CGRect {
        guard let corner else {
            return crop.offsetBy(dx: min(1 - crop.maxX, max(-crop.minX, dx)),
                                 dy: min(1 - crop.maxY, max(-crop.minY, dy)))
        }
        let minimum: CGFloat = 0.04
        if let ratio {
            let anchorX = corner.left ? crop.maxX : crop.minX
            let anchorY = corner.top ? crop.maxY : crop.minY
            let unitWidth = min(1, ratio)
            let unitHeight = min(1, 1 / ratio)
            let limit = max(0, min((corner.left ? anchorX : 1 - anchorX) / unitWidth,
                                  (corner.top ? anchorY : 1 - anchorY) / unitHeight))
            let directionX = unitWidth * imageAspect
            let candidateX = (crop.width + (corner.left ? -dx : dx)) * imageAspect
            let candidateY = crop.height + (corner.top ? -dy : dy)
            let projected = (candidateX * directionX + candidateY * unitHeight) / (directionX * directionX + unitHeight * unitHeight)
            let length = min(limit, max(min(minimum, limit), projected))
            let width = unitWidth * length
            let height = unitHeight * length
            return CGRect(x: corner.left ? anchorX - width : anchorX, y: corner.top ? anchorY - height : anchorY,
                          width: width, height: height)
        }
        let x = corner.left ? max(0, min(crop.maxX - minimum, crop.minX + dx)) : crop.minX
        let y = corner.top ? max(0, min(crop.maxY - minimum, crop.minY + dy)) : crop.minY
        let right = corner.left ? crop.maxX : min(1, max(crop.minX + minimum, crop.maxX + dx))
        let bottom = corner.top ? crop.maxY : min(1, max(crop.minY + minimum, crop.maxY + dy))
        return CGRect(x: x, y: y, width: right - x, height: bottom - y)
    }
}

private func videoTrimTime(_ ms: Int64) -> String {
    String(format: "%lld:%02lld.%lld", ms / 60_000, ms / 1000 % 60, ms / 100 % 10)
}

@MainActor
private struct VideoTrimView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    let request: MediaSendRequest
    let url: URL
    let initial: VideoTrim?
    let onApply: (VideoTrim?) -> Void
    @State private var duration: Int64 = 0
    @State private var startMs: Double = 0
    @State private var endMs: Double = 0
    @State private var muteAudio = false
    @State private var player: AVPlayer?
    @State private var grant: MediaGrantToken?
    @State private var images: [CGImage?] = []
    @State private var playback: Task<Void, Never>?
    @State private var previewing = false
    @State private var error: String?
    @State private var timelineDrag: TimelineDrag?

    private struct TimelineDrag {
        let atStart: Bool
        let position: Double
    }

    init(request: MediaSendRequest, url: URL, initial: VideoTrim?, onApply: @escaping (VideoTrim?) -> Void) {
        self.request = request
        self.url = url
        self.initial = initial
        self.onApply = onApply
    }

    private var start: Int64 { Int64(startMs.rounded()) }
    private var end: Int64 { Int64(endMs.rounded()) }
    private var trim: VideoTrim { VideoTrim(startMs: start, endMs: end, muteAudio: muteAudio) }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(interfaceText("edit_video")).font(.headline)
            ZStack {
                Color.black
                if let player { TrimPlayerSurface(player: player) }
                else if error == nil { ProgressView().tint(.white) }
            }.frame(height: 252).clipShape(RoundedRectangle(cornerRadius: 7))
            if duration > VideoTrim.minimumMs {
                filmstrip.frame(height: 44)
                LabeledContent("\(interfaceText("selection_start"))  \(videoTrimTime(start))") {
                    Slider(value: Binding(get: { startMs }, set: { update($0, atStart: true) }), in: 0...Double(duration))
                        .accessibilityLabel(interfaceText("selection_start"))
                }
                LabeledContent("\(interfaceText("selection_end"))  \(videoTrimTime(end))") {
                    Slider(value: Binding(get: { endMs }, set: { update($0, atStart: false) }), in: 0...Double(duration))
                        .accessibilityLabel(interfaceText("selection_end"))
                }
                HStack {
                    Button(interfaceText(previewing ? "pause" : "play_selection"), action: togglePlayback)
                    Text(interfaceText.format("duration_selected", videoTrimTime(end - start))).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Toggle(interfaceText("remove_audio"), isOn: $muteAudio)
                }
            }
            if let error { Text(error).font(.callout).foregroundStyle(.red) }
            HStack {
                Spacer()
                Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction)
                Button(interfaceText("apply")) {
                    onApply(start == 0 && end == duration && !muteAudio ? nil : trim)
                    dismiss()
                }.keyboardShortcut(.defaultAction).disabled(!trim.fits(durationMs: duration))
            }
        }
        .padding(20).frame(width: 580)
        .task { await open() }
        .onChange(of: muteAudio) { player?.isMuted = $0 }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { notification in
            guard let ended = notification.object as? AVPlayerItem, ended === player?.currentItem else { return }
            stopPlayback()
        }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime)) { notification in
            guard let failed = notification.object as? AVPlayerItem, failed === player?.currentItem else { return }
            stopPlayback()
            error = interfaceText("video_preview_error")
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in stopPlayback() }
        .onDisappear {
            stopPlayback()
            player?.replaceCurrentItem(with: nil)
            player = nil
            grant = nil
        }
    }

    private var filmstrip: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let left = width * CGFloat(startMs / Double(duration))
            let right = width * CGFloat(endMs / Double(duration))
            ZStack(alignment: .leading) {
                Color.black
                HStack(spacing: 1) {
                    ForEach(images.indices, id: \.self) { index in
                        GeometryReader { tile in
                            if let image = images[index] {
                                Image(decorative: image, scale: 1).resizable().scaledToFill()
                                    .frame(width: tile.size.width, height: tile.size.height).clipped()
                            } else { Color.black }
                        }
                    }
                }
                Color.black.opacity(0.62).frame(width: left)
                Color.black.opacity(0.62).frame(width: width - right).offset(x: right)
                Rectangle().strokeBorder(Color.accentColor, lineWidth: 2).frame(width: right - left).offset(x: left)
                ForEach(0..<2, id: \.self) { index in
                    let edge = index == 0 ? left : right
                    RoundedRectangle(cornerRadius: 2).fill(Color.accentColor).frame(width: 5)
                        .offset(x: max(0, min(width - 5, edge - 2.5)))
                }
            }
            .clipped()
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 1).onChanged { value in
                guard width > 0 else { return }
                if timelineDrag == nil {
                    let first = abs(left - value.startLocation.x)
                    let last = abs(right - value.startLocation.x)
                    guard min(first, last) <= 20 else { return }
                    let atStart = first <= last
                    timelineDrag = TimelineDrag(atStart: atStart, position: atStart ? startMs : endMs)
                }
                guard let timelineDrag else { return }
                let position = timelineDrag.position + Double(value.translation.width / width) * Double(duration)
                update(position, atStart: timelineDrag.atStart, seekPreview: false)
            }.onEnded { _ in
                if let timelineDrag { seek(to: timelineDrag.atStart ? start : end - 1) }
                timelineDrag = nil
            })
        }.accessibilityHidden(true)
    }

    private func open() async {
        guard let token = request.retain() else { error = interfaceText("video_unavailable"); return }
        defer { withExtendedLifetime(token) {} }
        let asset = AVURLAsset(url: url)
        do {
            guard let length = try await VideoCompression.trimDuration(url, asset: asset),
                  length > VideoTrim.minimumMs else {
                if !Task.isCancelled { error = interfaceText("cannot_trim") }
                return
            }
            try Task.checkCancellation()
            let saved = initial.flatMap { $0.fits(durationMs: length) ? $0 : nil }
            startMs = Double(saved?.startMs ?? 0)
            endMs = Double(saved?.endMs ?? length)
            muteAudio = saved?.muteAudio ?? false
            duration = length
            let preview = AVPlayer(playerItem: AVPlayerItem(asset: asset))
            preview.actionAtItemEnd = .pause
            preview.isMuted = muteAudio
            player = preview
            grant = token
            seek(to: start)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 160, height: 160)
            var strip: [CGImage?] = []
            for index in 0..<6 {
                try Task.checkCancellation()
                let frame = try? await generator.image(at: CMTime(value: length * Int64(index) / 6, timescale: 1000))
                strip.append(frame?.image)
            }
            if !Task.isCancelled { images = strip }
        } catch {
            if !Task.isCancelled { self.error = interfaceText("video_open_error") }
        }
    }

    private func update(_ value: Double, atStart: Bool, seekPreview: Bool = true) {
        stopPlayback()
        let minimum = Double(VideoTrim.minimumMs)
        if atStart { startMs = max(0, min(value.rounded(), endMs - minimum)) }
        else { endMs = min(Double(duration), max(value.rounded(), startMs + minimum)) }
        player?.currentItem?.forwardPlaybackEndTime = .invalid
        if seekPreview { seek(to: atStart ? start : end - 1) }
    }

    private func seek(to position: Int64) {
        player?.seek(to: CMTime(value: position, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func stopPlayback() {
        playback?.cancel()
        playback = nil
        player?.pause()
        previewing = false
    }

    private func togglePlayback() {
        if previewing { stopPlayback(); return }
        guard let player, let item = player.currentItem, trim.fits(durationMs: duration) else { return }
        error = nil
        previewing = true
        item.forwardPlaybackEndTime = CMTime(value: end, timescale: 1000)
        let position = CMTime(value: start, timescale: 1000)
        playback = Task { @MainActor in
            let ready: Bool = await player.seek(to: position, toleranceBefore: .zero, toleranceAfter: .zero)
            guard !Task.isCancelled else { return }
            if ready { player.play() }
            else {
                previewing = false
                error = interfaceText("video_preview_error")
            }
        }
    }
}

private struct TrimPlayerSurface: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.controlsStyle = .none
        view.videoGravity = .resizeAspect
        view.player = player
        return view
    }
    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
    static func dismantleNSView(_ view: AVPlayerView, coordinator: ()) { view.player = nil }
}
