// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

@MainActor
struct ConversationFoldersView:View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @ObservedObject var model:SessionListViewModel
    @State private var selected:String?
    @State private var name=""
    @State private var includeAll=false
    @State private var unreadOnly=false
    @State private var excludeMuted=false
    @State private var includeArchived=false
    @State private var members=Set<String>()
    @State private var memberSearch = ""
    @State private var busy=false
    @State private var error:String?
    @State private var deleting: String?
    @State private var pendingExit: (() -> Void)?
    @State private var baseline = Draft(name: "", all: false, unread: false, muted: false, archived: false, members: [])
    private struct Draft: Equatable {
        let name: String
        let all, unread, muted, archived: Bool
        let members: Set<String>
    }
    private var draft: Draft {
        Draft(name: name.trimmingCharacters(in: .whitespacesAndNewlines), all: includeAll, unread: unreadOnly,
              muted: excludeMuted, archived: includeArchived, members: members)
    }
    private var selection: Binding<String?> {
        Binding(get: { selected }, set: { id in
            guard let id, id != selected, let folder = model.folders.first(where: { $0.id == id }) else { return }
            leave { choose(folder) }
        })
    }
    var body:some View {
        let query = memberSearch.trimmingCharacters(in: .whitespacesAndNewlines)
        let visibleMembers = query.isEmpty ? model.conversations : model.conversations.filter {
            $0.conversation.alias.localizedCaseInsensitiveContains(query)
        }
        VStack(alignment:.leading,spacing:12){
            Text(interfaceText("chat_folders")).font(.headline)
            HStack(alignment:.top,spacing:16){
                VStack {
                    List(selection: selection) {
                        ForEach(model.folders) { folder in
                            Text(folder.name).lineLimit(1).tag(folder.id).moveDisabled(busy)
                        }
                        .onMove(perform: reorder)
                    }
                    .frame(width: 170)
                    HStack {Button{leave { choose(nil) }}label:{Image(systemName:"plus")}.help(interfaceText("folder_new"));Button{move(true)}label:{Image(systemName:"arrow.up")}.help(interfaceText("folder_move_up"));Button{move(false)}label:{Image(systemName:"arrow.down")}.help(interfaceText("folder_move_down"));Button{deleting=selected}label:{Image(systemName:"trash")}.help(interfaceText("folder_delete")).disabled(selected==nil)}
                }
                VStack(alignment:.leading,spacing:10){
                    TextField(interfaceText("folder_name"),text:$name).textFieldStyle(.roundedBorder)
                    Toggle(interfaceText("folder_include_all"),isOn:$includeAll)
                    Toggle(interfaceText("folder_unread_only"),isOn:$unreadOnly)
                    Toggle(interfaceText("folder_exclude_muted"),isOn:$excludeMuted)
                    Toggle(interfaceText("folder_include_archived"),isOn:$includeArchived)
                    TextField(interfaceText("folder_search_members"), text: $memberSearch)
                        .textFieldStyle(.roundedBorder)
                        .disabled(includeAll)
                    List(visibleMembers,id:\.conversation.uuid){row in
                        Toggle(row.conversation.alias,isOn:Binding(get:{members.contains(row.conversation.uuid)},set:{if $0{members.insert(row.conversation.uuid)}else{members.remove(row.conversation.uuid)}})).toggleStyle(.checkbox).disabled(includeAll)
                    }
                }
            }.disabled(busy)
            if busy {ProgressView().controlSize(.small)}
            if let error {Text(interfaceText(error)).foregroundStyle(.red).font(.callout)}
            HStack {Spacer();Button(interfaceText("close")) { leave { dismiss() } }.keyboardShortcut(.cancelAction).disabled(busy);Button(interfaceText("save"),action:save).keyboardShortcut(.defaultAction).disabled(busy || draft.name.isEmpty || draft.name.utf8.count > 128 || draft.name.utf8.contains(0))}
        }.padding(20).frame(minWidth:640,idealWidth:720,minHeight:460,idealHeight:560)
        .interactiveDismissDisabled(busy || draft != baseline)
        .alert(interfaceText("folder_delete_confirm"), isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } }
        ), presenting: deleting) { id in
            Button(interfaceText("folder_delete"), role: .destructive) {
                run { let ok = await model.deleteFolder(id); if ok { choose(nil) }; return ok }
            }
            Button(interfaceText("cancel"), role: .cancel) {}
        }
        .confirmationDialog(interfaceText("discard_message_edit"), isPresented: Binding(
            get: { pendingExit != nil }, set: { if !$0 { pendingExit = nil } }
        ), titleVisibility: .visible, presenting: pendingExit) { action in
            Button(interfaceText("discard"), role: .destructive, action: action)
            Button(interfaceText("keep_editing"), role: .cancel) {}
        }
    }

    private func leave(_ action: @escaping () -> Void) {
        guard !busy, pendingExit == nil else { return }
        if draft != baseline { pendingExit = action }
        else { action() }
    }

    private func choose(_ folder: ConversationFolder?) {
        selected = folder?.id
        name = folder?.name ?? ""
        includeAll = folder?.includeAll ?? false
        unreadOnly = folder?.unreadOnly ?? false
        excludeMuted = folder?.excludeMuted ?? false
        includeArchived = folder?.includeArchived ?? false
        memberSearch = ""
        members = Set(model.conversations.filter { row in
            folder.map { row.folderIDs.split(separator: ",").contains(Substring($0.id)) } ?? false
        }.map { $0.conversation.uuid })
        baseline = draft
        error = nil
    }

    private func run(_ action: @escaping @MainActor () async -> Bool) {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            let ok = await action()
            busy = false
            if !ok { error = "folder_update_failed" }
        }
    }

    private func save() {
        let id = selected ?? UUID().uuidString.lowercased()
        let snapshot = draft
        let folder = ConversationFolder(id: id, name: snapshot.name, includeAll: snapshot.all, unreadOnly: snapshot.unread,
                                        excludeMuted: snapshot.muted, includeArchived: snapshot.archived)
        run {
            let ok = await model.saveFolder(folder, peers: Array(snapshot.members))
            if ok { selected = id; baseline = snapshot }
            return ok
        }
    }
    private func move(_ up: Bool) {
        let folders = model.folders
        guard let id = selected, let current = folders.firstIndex(where: { $0.id == id }) else { return }
        let neighbor = current + (up ? -1 : 1)
        guard folders.indices.contains(neighbor) else { return }
        let target = folders[neighbor].id
        run { await model.moveFolder(id, targetID: target, before: up) }
    }

    private func reorder(_ offsets: IndexSet, to destination: Int) {
        let folders = model.folders
        guard offsets.count == 1, let current = offsets.first, folders.indices.contains(current) else { return }
        let target = destination > current ? destination - 1 : destination
        guard folders.indices.contains(target), target != current else { return }
        let id = folders[current].id
        let targetID = folders[target].id
        run { await model.moveFolder(id, targetID: targetID, before: target < current) }
    }
}
