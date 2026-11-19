// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Owns recording admission and waits for hardware callbacks before vault shutdown.
final class EncryptedCaptureStore:@unchecked Sendable {
    static let shared=EncryptedCaptureStore()
    private let gate=NSLock()
    private var closed=false
    private var files=[UUID:EncryptedCaptureFile]()
    private init(){}

    /// stopWriter releases encoder, device and stream. It must not recursively
    /// freeze/close this recording while that recording awaits the callback.
    func open(filename:String,mime:String,capacity:Int64=100*1024*1024,
              stopWriter:@escaping @Sendable () async throws -> Void) async throws -> EncryptedCaptureFile {
        try Task.checkCancellation()
        let file=try await Task.detached { [self] in
            try gate.withLock {
                guard !closed else {throw SecurityCore.Failure.operation("Recording storage is closed")}
                guard (1...100*1024*1024).contains(capacity) else {throw CocoaError(.fileWriteOutOfSpace)}
                let objects=EncryptedObjectStore.shared
                let candidate=try objects.recordingCandidate(size:capacity,filename:filename,mime:mime)
                guard let id=candidate["object_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
                do {
                    let value=try SecurityCore.call(["operation":"open_capture_file","manifest":candidate,"directory":objects.directory.path])
                    guard let handle=value["capture_id"] as? String else {throw SecurityCore.Failure.invalidResponse}
                    let key=UUID()
                    let created=EncryptedCaptureFile(store:self,registration:key,objectID:id,handle:handle,filename:filename,mime:mime,capacity:capacity,stopWriter:stopWriter)
                    files[key]=created;return created
                }catch{try objects.discardRecording(id);throw error}
            }
        }.value
        if Task.isCancelled {try await file.close();throw CancellationError()}
        return file
    }
    fileprivate func removed(_ id:UUID) {gate.withLock{_ = files.removeValue(forKey:id)}}
    func close() async throws {
        let active=gate.withLock{closed=true;return Array(files.values)}
        var firstError:Error?
        for file in active {do{try await file.forceClose()}catch{if firstError==nil{firstError=error}}}
        if let firstError {throw firstError}
    }
}

final class EncryptedCaptureLease:@unchecked Sendable {
    private let lock=NSLock()
    private var file:EncryptedCaptureFile?
    fileprivate init(_ file:EncryptedCaptureFile){self.file=file}
    func close()throws {
        let owner=lock.withLock{let value=file;file=nil;return value}
        try owner?.releaseReader()
    }
    deinit {try? close()}
}

final class EncryptedCaptureFile:@unchecked Sendable {
    private let io=NSRecursiveLock()
    private let store:EncryptedCaptureStore
    private let registration:UUID
    private let objectID:String
    private let handle:String
    private let stopWriter:@Sendable () async throws -> Void
    private var stopped:Task<Void,Error>?
    private var nativeClosed=false,cleaned=false,draining=false,frozen=false,producerReleased=false
    private var owners=1
    private var length:Int64=0
    let filename:String
    let mime:String
    let capacity:Int64
    fileprivate init(store:EncryptedCaptureStore,registration:UUID,objectID:String,handle:String,filename:String,mime:String,capacity:Int64,
                     stopWriter:@escaping @Sendable () async throws -> Void){
        self.store=store;self.registration=registration;self.objectID=objectID;self.handle=handle
        self.filename=filename;self.mime=mime;self.capacity=capacity;self.stopWriter=stopWriter
    }
    var size:Int64 {get throws{try io.withLock{try requireOpen();return length}}}
    private func requireOpen()throws {guard !nativeClosed else{throw SecurityCore.Failure.operation("The recording is closed")}}
    private func call(_ operation:String,_ body:[String:Any]=[:])throws->[String:Any]{
        var request=body;request["operation"]=operation;request["capture_id"]=handle;return try SecurityCore.call(request)
    }
    func write(offset:Int64,data:Data)throws {
        try io.withLock {
            try requireOpen()
            guard !frozen,offset>=0,Int64(data.count)<=capacity,offset<=capacity-Int64(data.count) else{throw CocoaError(.fileWriteOutOfSpace)}
            var done=0
            while done<data.count {
                let count=min(256*1024,data.count-done)
                var part=data.subdata(in:done..<done+count);defer{part.resetBytes(in:0..<part.count)}
                let result=try call("write_capture_file",["offset":offset+Int64(done),"bytes":part.base64EncodedString()])
                guard result["written"] as? Int==count,let size=result["size"] as? Int64 else{throw SecurityCore.Failure.invalidResponse}
                length=size;done+=count
            }
        }
    }
    func read(offset:Int64,count:Int)throws->Data {
        try io.withLock {
            try requireOpen();guard offset>=0,(0...256*1024).contains(count) else{throw CocoaError(.fileReadUnknown)}
            let result=try call("read_capture_file",["offset":offset,"length":count])
            guard let encoded=result["bytes"] as? String,var bytes=Data(base64Encoded:encoded) else{throw SecurityCore.Failure.invalidResponse}
            guard result["length"] as? Int==bytes.count else{bytes.resetBytes(in:0..<bytes.count);throw SecurityCore.Failure.invalidResponse}
            return bytes
        }
    }
    func sync()throws{try io.withLock{try requireOpen();_ = try call("sync_capture_file")}}
    private func stopHardware() async throws {
        let task=io.withLock{if let stopped{return stopped};let task=Task.detached{[stopWriter] in try await stopWriter()};stopped=task;return task}
        do{try await task.value}catch{io.withLock{stopped=nil};throw error}
    }
    func freeze() async throws -> Int64 {
        try io.withLock{try requireOpen();guard !draining else{throw SecurityCore.Failure.operation("Recording storage is closing")}}
        try await stopHardware()
        return try await Task.detached{[self] in
            try io.withLock {
                try requireOpen();guard !draining else{throw SecurityCore.Failure.operation("Recording storage is closing")}
                guard let size=try call("freeze_capture_file")["size"] as? Int64 else{throw SecurityCore.Failure.invalidResponse}
                length=size;frozen=true;return size
            }
        }.value
    }
    func retainFrozen()throws->EncryptedCaptureLease {
        try io.withLock {
            try requireOpen();guard frozen && !draining else{throw SecurityCore.Failure.operation("Finish the recording before reading it")}
            owners+=1;return EncryptedCaptureLease(self)
        }
    }
    // Import worker only. The new immutable object has a separate staging owner.
    func importTo(directory:URL,manifest:[String:Any],preparation:ObjectPreparation?)throws->[String:Any] {
        try io.withLock {
            try requireOpen();guard frozen && !draining else{throw SecurityCore.Failure.operation("Finish the recording before importing it")}
            var request:[String:Any]=["manifest":manifest,"directory":directory.path,"maximum_size":100*1024*1024]
            if let preparation{request["work_id"]=try preparation.workID()}
            guard let result=try call("import_capture_file",request)["manifest"] as? [String:Any] else{throw SecurityCore.Failure.invalidResponse}
            return result
        }
    }
    private func cleanupLocked()throws {
        if cleaned{return}
        if !nativeClosed{_ = try call("close_capture_file");nativeClosed=true}
        try EncryptedObjectStore.shared.discardRecording(objectID);cleaned=true;store.removed(registration)
    }
    fileprivate func releaseReader()throws {
        try io.withLock{guard owners>0 else{throw SecurityCore.Failure.operation("Invalid recording reader ownership")};owners-=1;if owners==0{try cleanupLocked()}}
    }
    func close() async throws {
        try await stopHardware()
        try await Task.detached{[self] in
            try io.withLock{if !producerReleased{producerReleased=true;owners-=1};if owners==0{try cleanupLocked()}}
        }.value
    }
    fileprivate func forceClose() async throws {
        io.withLock{draining=true}
        try await stopHardware()
        try await Task.detached{[self] in try io.withLock{try cleanupLocked()}}.value
    }
}
