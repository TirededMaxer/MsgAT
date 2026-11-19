// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

enum PreparationSendResult:Sendable {case queued,cancelled,failed}
enum PreparationStep: Sendable, Equatable { case importing, encodingPhoto, encodingVideo }

struct PreparationProgress:Sendable {let phase:String;let completed:Int64;let total:Int64;let canCancel:Bool;let version:UInt64;let attachment:Int;let attachmentCount:Int;let step:PreparationStep}
struct PendingPreparation: Sendable, Identifiable {
    let id: UUID
    let filename: String?
    let fileCount: Int
    let isSticker: Bool
    var progress: PreparationProgress
    var savingMessages: Bool = false
}

/// One send preparation, owned until the worker and staged-object cleanup have finished.
final class ObjectPreparation:@unchecked Sendable {
    private let lock=NSRecursiveLock()
    private var work:String?
    private var phase="preparing"
    private var cancelled=false
    private var version:UInt64=0
    private var completed:Int64=0
    private var total:Int64=0
    private var attachment = 1
    private var attachmentCount = 1
    private var step: PreparationStep = .importing
    var wasCancelled:Bool {lock.lock();defer{lock.unlock()};return cancelled}
    func check()throws {
        lock.lock();defer{lock.unlock()}
        if cancelled {throw CancellationError()}
        guard phase != "finished" else {throw SecurityCore.Failure.operation("Preparation has ended")}
    }
    func workID()throws->String {
        lock.lock();defer{lock.unlock()};try check()
        if let work {return work}
        guard let id=try SecurityCore.call(["operation":"create_object_work"])["work_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
        work=id;return id
    }
    func report(total:Int64,completed:Int64)throws {
        lock.lock();defer{lock.unlock()};try check()
        _=try SecurityCore.call(["operation":"report_object_work","work_id":workID(),"total":total,"completed":completed])
    }
    func startAttachment(index: Int, count: Int) throws {
        lock.lock()
        defer { lock.unlock() }
        try check()
        attachment = index
        attachmentCount = count
        try setStep(.importing)
    }
    func setStep(_ value: PreparationStep) throws {
        lock.lock()
        defer { lock.unlock() }
        try report(total: 0, completed: 0)
        step = value
        version &+= 1
    }
    func beginCommit()throws {
        lock.lock();defer{lock.unlock()};try check()
        guard phase=="preparing" else {throw SecurityCore.Failure.operation("Preparation has already entered commit")};phase="committing";version &+= 1
    }
    @discardableResult func cancel()->Bool {
        lock.lock();defer{lock.unlock()}
        guard phase=="preparing" else {return false};cancelled=true;phase="canceling";version &+= 1
        if let work {_ = try? SecurityCore.call(["operation":"cancel_object_work","work_id":work])}
        return true
    }
    func snapshot()->PreparationProgress {
        lock.lock();defer{lock.unlock()}
        if let work,let value=try? SecurityCore.call(["operation":"object_work_status","work_id":work]),let size=value["total"] as? Int64,let done=value["completed"] as? Int64 {total=size;completed=min(max(0,done),size)}
        return PreparationProgress(phase:phase,completed:completed,total:total,canCancel:phase=="preparing",version:version,attachment:attachment,attachmentCount:attachmentCount,step:step)
    }
    func finish(){
        lock.lock();defer{lock.unlock()}
        guard phase != "finished" else{return};phase="finished";version &+= 1
        if let work {_ = try? SecurityCore.call(["operation":"close_object_work","work_id":work])};work=nil
    }
}
