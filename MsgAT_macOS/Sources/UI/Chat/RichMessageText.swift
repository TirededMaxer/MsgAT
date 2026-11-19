// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit

private struct RichTextRun:Sendable {let text:String;let style:Int;let spoiler:Int;let url:String?}
private struct QuoteFold: Sendable { let run: Int; let end: Int }
private struct RichTextBlock:Sendable {
    let kind:String;let language:String;let runs:[RichTextRun]
    let quoteFold: QuoteFold?
}
private struct RichTextDocument:Sendable {
    let blocks:[RichTextBlock]
    static func read(_ text:String)throws->RichTextDocument {
        let value=try SecurityCore.call(["operation":"render_message_text","text":text])
        guard value["version"] as? Int==1,let blocks=value["blocks"] as? [[String:Any]] else{throw SecurityCore.Failure.invalidResponse}
        return RichTextDocument(blocks:try blocks.map{block in
            guard let kind=block["kind"] as? String,let language=block["language"] as? String,let runs=block["runs"] as? [[String:Any]] else{throw SecurityCore.Failure.invalidResponse}
            let fold = (block["quote_fold"] as? [String: Int]).flatMap { value -> QuoteFold? in
                guard let run = value["run"], let end = value["utf16_end"] else { return nil }
                return QuoteFold(run: run, end: end)
            }
            return RichTextBlock(kind:kind,language:language,runs:try runs.map{run in
                guard let text=run["text"] as? String,let style=run["style"] as? Int,let spoiler=run["spoiler"] as? Int else{throw SecurityCore.Failure.invalidResponse}
                return RichTextRun(text:text,style:style,spoiler:spoiler,url:run["url"] as? String)
            }, quoteFold: fold)
        })
    }
}

struct VisibleMessageLinks: PreferenceKey {
    static let defaultValue: [URL] = []
    static func reduce(value: inout [URL], nextValue: () -> [URL]) { value.append(contentsOf: nextValue()) }
}

struct LastPlainMessageLine: LayoutValueKey {
    static let defaultValue: CGSize? = nil
}

@MainActor
enum MessageLinks {
    nonisolated static func target(_ value: String) -> URL? {
        guard let url = URL(string: value), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              url.host?.isEmpty == false, url.user == nil, url.password == nil else { return nil }
        return url
    }

    static func open(_ url: URL, failure: String) -> Bool {
        guard AppLockSession.shared.canShowContent else { return false }
        guard NSWorkspace.shared.open(url) else { NoticeCenter.shared.show(failure); return false }
        return true
    }
}

@MainActor
struct MessageLinkActions: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let url: URL

    var body: some View {
        Button(interfaceText("open_link")) { _ = MessageLinks.open(url, failure: interfaceText("link_open_failed")) }
        Button(interfaceText("copy_link")) {
            guard AppLockSession.shared.canShowContent else { return }
            NSPasteboard.general.clearContents()
            if !NSPasteboard.general.setString(url.absoluteString, forType: .string) {
                NoticeCenter.shared.show(interfaceText("clipboard_unavailable"))
            }
        }
    }
}

@MainActor
enum MessageTextClipboard {
    static func copySelection(peer: String, ids: [String], strings: InterfaceStrings,
                              canPublish: @MainActor () -> Bool) async {
        let placeholder = strings("hidden_message_text")
        do {
            let visible = try await EncryptedMediaWork.run {
                try MsgDao.shared.selectedMessageText(peer: peer, ids: ids).compactMap { text -> String? in
                    let result = try SecurityCore.call([
                        "operation": "copy_message_text", "text": text, "revealed": [Int](), "placeholder": placeholder
                    ])
                    guard let copy = result["text"] as? String else { throw SecurityCore.Failure.invalidResponse }
                    return copy.isEmpty ? nil : copy
                }.joined(separator: "\n\n")
            }
            guard !Task.isCancelled, AppLockSession.shared.canShowContent, canPublish() else { return }
            if visible.isEmpty { NoticeCenter.shared.show(strings("selected_messages_no_text")) }
            else { publish(visible, strings: strings) }
        } catch {
            if !Task.isCancelled, AppLockSession.shared.canShowContent, canPublish() {
                NoticeCenter.shared.show(strings("message_copy_failed"))
            }
        }
    }

    static func copy(_ text: String, revealed: Set<Int> = [], strings: InterfaceStrings) async {
        let placeholder = strings("hidden_message_text")
        do {
            let visible = try await EncryptedMediaWork.run {
                let result = try SecurityCore.call([
                    "operation": "copy_message_text", "text": text, "revealed": revealed.sorted(), "placeholder": placeholder
                ])
                guard let copy = result["text"] as? String else { throw SecurityCore.Failure.invalidResponse }
                return copy
            }
            guard !Task.isCancelled, AppLockSession.shared.canShowContent, !visible.isEmpty else { return }
            publish(visible, strings: strings)
        } catch {
            if !Task.isCancelled, AppLockSession.shared.canShowContent {
                NoticeCenter.shared.show(strings("message_copy_failed"))
            }
        }
    }

    fileprivate static func publish(_ text: String, strings: InterfaceStrings) {
        guard AppLockSession.shared.canShowContent, !text.isEmpty else { return }
        NSPasteboard.general.clearContents()
        let success = NSPasteboard.general.setString(text, forType: .string)
        NoticeCenter.shared.show(strings(success ? "message_copied" : "clipboard_unavailable"))
    }
}

struct ReplyQuotePicker: View {
    let message: ChatMessage
    let onDismiss: () -> Void
    let onQuote: (String, String) -> Void
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var source: String?
    @State private var digest: String?
    @State private var range = NSRange(location: 0, length: 0)
    @State private var failed = false

    var body: some View {
        let title = interfaceText("quote_selected_text")
        VStack(alignment: .leading, spacing: 6) {
            if let source {
                SelectableQuoteText(text: source, range: $range)
                    .frame(height: min(180, max(44, CGFloat(source.count / 28 + 1) * 20)))
                    .accessibilityLabel(title)
            } else if !failed {
                ProgressView().frame(maxWidth: .infinity, minHeight: 100)
            }
            if failed { Text(interfaceText("quote_selection_unavailable")).foregroundStyle(.secondary) }
            HStack {
                Spacer()
                Button(interfaceText("cancel"), action: onDismiss)
                Button(title, action: confirm)
                    .disabled(range.length == 0 || digest == nil)
            }
        }
        .frame(maxWidth: 320)
        .task(id: message.content) {
            let content = message.content
            let result = try? await EncryptedMediaWork.run {
                let value = try SecurityCore.call(["operation": "reply_quote_text", "text": content])
                guard let text = value["text"] as? String, let digest = value["digest"] as? String else {
                    throw SecurityCore.Failure.invalidResponse
                }
                return (text, digest)
            }
            guard !Task.isCancelled else { return }
            source = result?.0
            digest = result?.1
            failed = result == nil
        }
    }

    private func confirm() {
        guard let digest, range.length > 0 else { return }
        let selection = "\(range.location):\(NSMaxRange(range)):\(digest)"
        guard let value = try? SecurityCore.call(["operation": "reply_quote_selection", "text": message.content, "selection": selection]),
              let excerpt = value["preview"] as? String else { failed = true; return }
        onQuote(selection, excerpt)
    }
}

private struct SelectableQuoteText: NSViewRepresentable {
    let text: String
    @Binding var range: NSRange

    func makeCoordinator() -> Coordinator { Coordinator(range: $range) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSTextView.scrollableTextView()
        guard let editor = scroll.documentView as? NSTextView else { return scroll }
        editor.isEditable = false
        editor.isSelectable = true
        editor.drawsBackground = false
        scroll.drawsBackground = false
        editor.font = .systemFont(ofSize: NSFont.systemFontSize)
        editor.textColor = .labelColor
        editor.textContainerInset = NSSize(width: 8, height: 8)
        editor.string = text
        editor.delegate = context.coordinator
        DispatchQueue.main.async { [weak editor] in
            if let editor { editor.window?.makeFirstResponder(editor) }
        }
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.range = $range
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var range: Binding<NSRange>
        init(range: Binding<NSRange>) { self.range = range }

        func textViewDidChangeSelection(_ notification: Notification) {
            guard let editor = notification.object as? NSTextView else { return }
            range.wrappedValue = editor.selectedRange()
        }
    }
}

@MainActor
struct RichMessageText:View {
    let text:String
    var quoteHighlight: String? = nil
    var onReveal: ((Set<Int>) -> Void)? = nil
    var measuresFinalLine = false
    @Environment(\.chatTextScale) private var chatTextScale
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var document:RichTextDocument?
    @State private var revealed=Set<Int>()
    @State private var failed=false
    @State private var expandedCodeBlocks = Set<Int>()
    @State private var expandedQuotes = Set<Int>()
    @State private var quoteRanges: [Int: [Int: NSRange]] = [:]
    private let collapsedCodeLines = 12

    private var finalPlainLine: CGSize? {
        guard measuresFinalLine, let document, document.blocks.count == 1,
              let block = document.blocks.first, block.kind == "paragraph", block.runs.count == 1,
              let run = block.runs.first, run.style == 0, run.spoiler == 0, run.url == nil,
              run.text.utf16.count <= 4_096, !run.text.contains("\t"),
              let separator = run.text.lastIndex(of: "\n") else { return nil }
        let tail = run.text[run.text.index(after: separator)...]
        guard !tail.isEmpty else { return nil }
        let font = NSFont.systemFont(ofSize: 14 * chatTextScale)
        let size = (String(tail) as NSString).size(withAttributes: [.font: font])
        return CGSize(width: ceil(size.width), height: ceil(size.height))
    }

    var body:some View {
        VStack(alignment:.leading,spacing:6){
            if let document {
                ForEach(document.blocks.indices,id:\.self){index in
                    let block=document.blocks[index]
                    if block.kind=="code" {
                        let code = block.runs.map(\.text).joined()
                        let expandable = code.lazy.filter { $0 == "\n" }.prefix(collapsedCodeLines).count == collapsedCodeLines
                        let showingQuote = quoteRanges[index]?.isEmpty == false
                        let expanded = showingQuote || expandedCodeBlocks.contains(index)
                        VStack(alignment:.leading,spacing:6){
                            HStack {
                                Text(block.language.isEmpty ? interfaceText("format_code_block") : block.language).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                Spacer()
                                Button(interfaceText("copy")) { MessageTextClipboard.publish(code, strings: interfaceText) }
                                    .buttonStyle(.borderless).font(.caption).disabled(code.isEmpty)
                            }
                            ScrollView(.horizontal) {
                                Text(attributed(block, index: index)).lineLimit(expanded ? nil : collapsedCodeLines)
                                    .textSelection(.enabled).fixedSize(horizontal: true, vertical: false)
                            }
                            if expandable && !showingQuote {
                                Button(interfaceText(expanded ? "collapse_code" : "expand_code")) {
                                    if expanded { expandedCodeBlocks.remove(index) }
                                    else { expandedCodeBlocks.insert(index) }
                                }.buttonStyle(.borderless).font(.caption)
                            }
                        }.padding(8).background(.primary.opacity(0.06),in:RoundedRectangle(cornerRadius:6))
                    } else if block.kind == "quote" {
                        let showingQuote = quoteRanges[index]?.isEmpty == false
                        let expanded = showingQuote || expandedQuotes.contains(index)
                        let visible = expanded ? block.runs : foldedRuns(in: block)
                        let destinations = visibleLinks(in: visible)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(attributed(block, index: index, visible: visible)).textSelection(.enabled)
                                .help(destinations.map(\.absoluteString).joined(separator: "\n"))
                                .preference(key: VisibleMessageLinks.self, value: destinations)
                            if block.quoteFold != nil && !showingQuote {
                                Button(interfaceText(expanded ? "collapse_quote" : "expand_quote")) {
                                    if expanded { expandedQuotes.remove(index) }
                                    else { expandedQuotes.insert(index) }
                                }.buttonStyle(.borderless).font(.caption)
                            }
                        }
                        .padding(.leading, 10)
                        .overlay(alignment: .leading) { Rectangle().fill(Color.accentColor.opacity(0.6)).frame(width: 3) }
                    } else {
                        let destinations = visibleLinks(in: block.runs)
                        Text(attributed(block, index: index)).textSelection(.enabled)
                            .help(destinations.map(\.absoluteString).joined(separator: "\n"))
                            .preference(key: VisibleMessageLinks.self, value: destinations)
                    }
                }
            }else{Text(failed ? interfaceText("message_formatting_unavailable") : "…")}
        }
        .font(.system(size: 14 * chatTextScale))
        .layoutValue(key: LastPlainMessageLine.self, value: finalPlainLine)
        .task(id:text){
            document=nil;revealed=[];expandedCodeBlocks=[];expandedQuotes=[];failed=false
            let source=text
            do {
                let value=try await Task.detached(priority:.userInitiated){try RichTextDocument.read(source)}.value
                guard !Task.isCancelled else{return};document=value
            }catch{if !Task.isCancelled{failed=true}}
        }
        .task(id: quoteHighlight) {
            quoteRanges = [:]
            guard let preview = quoteHighlight else { return }
            let source = text
            let ranges = try? await EncryptedMediaWork.run {
                let result = try SecurityCore.call(["operation": "locate_reply_quote", "text": source, "preview": preview])
                var ranges: [Int: [Int: NSRange]] = [:]
                for item in result["ranges"] as? [[String: Int]] ?? [] {
                    guard let block = item["block"], let run = item["run"],
                          let start = item["start"], let end = item["end"], start >= 0, end > start else { continue }
                    ranges[block, default: [:]][run] = NSRange(location: start, length: end - start)
                }
                return ranges
            }
            if !Task.isCancelled, let ranges { quoteRanges = ranges }
        }
        .environment(\.openURL,OpenURLAction{url in
            if url.scheme=="msgat-spoiler",let id=Int(url.host ?? ""),let document,document.blocks.contains(where:{$0.runs.contains(where:{$0.spoiler==id})}){
                revealed.insert(id)
                onReveal?(revealed)
                return .handled
            }
            guard let target = MessageLinks.target(url.absoluteString) else { return .discarded }
            return MessageLinks.open(target, failure: interfaceText("link_open_failed")) ? .handled : .discarded
        })
    }

    private func foldedRuns(in block: RichTextBlock) -> [RichTextRun] {
        guard let fold = block.quoteFold, block.runs.indices.contains(fold.run) else { return block.runs }
        var visible = Array(block.runs.prefix(fold.run))
        if fold.end > 0 {
            let run = block.runs[fold.run]
            let prefix = String(decoding: run.text.utf16.prefix(fold.end), as: UTF16.self)
            visible.append(RichTextRun(text: prefix, style: run.style, spoiler: run.spoiler,
                                       url: fold.end == run.text.utf16.count ? run.url : nil))
        }
        return visible
    }

    private func visibleLinks(in runs: [RichTextRun]) -> [URL] {
        var seen = Set<URL>()
        return runs.compactMap { run -> URL? in
            guard run.spoiler == 0 || revealed.contains(run.spoiler),
                  let raw = run.url, let url = MessageLinks.target(raw), seen.insert(url).inserted else { return nil }
            return url
        }
    }
    private func attributed(_ block:RichTextBlock, index: Int, visible: [RichTextRun]? = nil)->AttributedString {
        var result=AttributedString();var previousHidden=0
        for (runIndex, run) in (visible ?? block.runs).enumerated() {
            let hidden=run.spoiler != 0 && !revealed.contains(run.spoiler)
            if hidden && previousHidden==run.spoiler{continue}
            previousHidden=hidden ? run.spoiler : 0
            var fragment=AttributedString(hidden ? interfaceText("hidden_message_text") : run.text)
            if hidden {
                fragment.foregroundColor=Color.secondary
                fragment.backgroundColor=Color.secondary.opacity(0.15)
                fragment.link=URL(string:"msgat-spoiler://\(run.spoiler)")
            }else {
                var font=Font.system(size:(block.kind == "code" ? 13 : 14) * chatTextScale,design:run.style & 16 != 0 ? .monospaced : .default)
                if run.style & 1 != 0{font=font.bold()}
                if run.style & 2 != 0{font=font.italic()}
                fragment.font=font
                if run.style & 4 != 0{fragment.underlineStyle = .single}
                if run.style & 8 != 0{fragment.strikethroughStyle = .single}
                if run.style & 16 != 0 && block.kind != "code" { fragment.backgroundColor = Color.secondary.opacity(0.1) }
                if let target = run.url, let url = MessageLinks.target(target) {
                    fragment.link = url
                    fragment.foregroundColor = Color.accentColor
                    fragment.underlineStyle = .single
                }
            }
            if run.spoiler == 0, let match = quoteRanges[index]?[runIndex],
               let range = Range(match, in: run.text),
               let start = AttributedString.Index(range.lowerBound, within: fragment),
               let end = AttributedString.Index(range.upperBound, within: fragment) {
                fragment[start..<end].backgroundColor = Color.accentColor.opacity(0.35)
            }
            result += fragment
        }
        return result
    }
}

@MainActor
struct MessageSummaryText:View {
    let text:String
    @State private var renderedSource:String?
    @State private var summary=""
    var body:some View {
        Text(renderedSource==text ? summary : " ")
            .task(id:text){
                let source=text
                let result=await Task.detached(priority:.utility){
                    (try? SecurityCore.call(["operation":"summarize_message_text","text":source,"limit":240])["text"] as? String) ?? "Message"
                }.value
                guard !Task.isCancelled else{return};summary=result;renderedSource=source
            }
    }
}
