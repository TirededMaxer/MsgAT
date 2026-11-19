// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
@preconcurrency import AVFoundation
import UniformTypeIdentifiers

struct RecordingDevice:Identifiable,Sendable {let id:String;let name:String}
enum RecordingPhase:Sendable {case idle,recording,paused,ready,closed}

final class CapturedRecording:@unchecked Sendable {
    let file:EncryptedCaptureFile
    private let lease:EncryptedCaptureLease
    init(_ file:EncryptedCaptureFile)throws{self.file=file;lease=try file.retainFrozen()}
    func close()throws{try lease.close()}
    deinit{try? close()}
}

/// The SDK produces fragmented MP4 in memory; fragments are appended directly
/// to encrypted staging. No AVAssetWriter output URL or plaintext file exists.
private final class RecordingSegmentSink:NSObject,AVAssetWriterDelegate,@unchecked Sendable {
    private let lock=NSLock()
    private let file:EncryptedCaptureFile
    private var position:Int64=0
    private var initialized=false
    private var accepting = true
    private var failed:Error?
    var onFailure:(@Sendable()->Void)?
    var onLimit:(@Sendable()->Void)?
    init(_ file:EncryptedCaptureFile){self.file=file}
    func assetWriter(_ writer:AVAssetWriter,didOutputSegmentData data:Data,segmentType:AVAssetSegmentType){
        var notify=false,limit=false
        lock.withLock{
            guard accepting, failed==nil else{return}
            do {
                guard !data.isEmpty,data.count<=8*1024*1024,position<=file.capacity-Int64(data.count),
                      (segmentType == .initialization && !initialized) || (segmentType == .separable && initialized) else{
                    throw SecurityCore.Failure.operation("Recording output exceeded its limit or changed order")
                }
                try file.write(offset:position,data:data);position+=Int64(data.count);initialized=true
                limit=position>=90*1024*1024
            }catch{failed=error;notify=true}
        }
        if notify{onFailure?()}
        else if limit{onLimit?()}
    }
    func stopAccepting() { lock.withLock { accepting = false } }
    func checkFailure() throws { try lock.withLock { if let failed { throw failed } } }
    func check()throws{try lock.withLock{if let failed{throw failed};guard initialized,position>0 else{throw SecurityCore.Failure.operation("The recording is empty")}}}
}

/// Session, sample delivery, writer input and finishing are owned by one queue.
final class RecordingController:NSObject,AVCaptureAudioDataOutputSampleBufferDelegate,@unchecked Sendable {
    let session=AVCaptureSession()
    private let queue=DispatchQueue(label:"org.msgat.recording",qos:.userInitiated)
    private let audioOutput=AVCaptureAudioDataOutput()
    private var writer:AVAssetWriter?
    private var audioInput:AVAssetWriterInput?
    private var sink:RecordingSegmentSink?
    private var file:EncryptedCaptureFile?
    private var microphone: MicrophoneOwnership.RecordingLease?
    private var origin:CMTime?
    private var pauseTime:CFTimeInterval?
    private var removedPause=CMTime.zero
    private var lastMeterUpdate = -1.0
    private var phase=RecordingPhase.idle
    private var closing=false,stopping=false,stopped=false
    private var callbacks=[@Sendable()->Void]()
    private var observers=[NSObjectProtocol]()
    private var error:Error?
    var onPhase:(@Sendable(RecordingPhase)->Void)?
    var onError:(@Sendable(String)->Void)?
    var onProgress: (@Sendable (Double, Float) -> Void)?
    var onFinishRequested:(@Sendable()->Void)?
    override init(){super.init()
        for name in [AVCaptureSession.runtimeErrorNotification,AVCaptureSession.wasInterruptedNotification]{
            observers.append(NotificationCenter.default.addObserver(forName:name,object:session,queue:nil){[weak self] _ in
                guard let self else{return};self.queue.async{guard !self.closing else{return};self.error=SecurityCore.Failure.operation("Recording device was interrupted");self.onError?("Recording device was interrupted");self.onFinishRequested?()}
            })
        }
    }
    func prepare(microphone:String?)async throws {
        try await withCheckedThrowingContinuation{continuation in queue.async{
            do {
                guard !self.closing,self.phase == .idle else{throw SecurityCore.Failure.operation("Recording storage is closing")}
                self.session.stopRunning();self.session.beginConfiguration()
                for input in self.session.inputs{self.session.removeInput(input)}
                for output in self.session.outputs{self.session.removeOutput(output)}
                do {
                        let devices=AVCaptureDevice.devices(for:.audio)
                        guard let device=devices.first(where:{$0.uniqueID==microphone}) ?? devices.first else{throw SecurityCore.Failure.operation("No microphone is available")}
                        let input=try AVCaptureDeviceInput(device:device)
                        guard self.session.canAddInput(input),self.session.canAddOutput(self.audioOutput) else{throw SecurityCore.Failure.operation("Microphone capture is unavailable")}
                        self.session.addInput(input);self.session.addOutput(self.audioOutput);self.audioOutput.setSampleBufferDelegate(self,queue:self.queue)
                    self.session.commitConfiguration();continuation.resume()
                }catch{self.session.commitConfiguration();throw error}
            }catch{continuation.resume(throwing:error)}
        }}
    }
    func start()async throws {
        let output=try await EncryptedCaptureStore.shared.open(filename:"Voice-\(Int(Date().timeIntervalSince1970)).m4a",mime:"audio/mp4",stopWriter:{[self] in await stopDevices()})
        do{try await withCheckedThrowingContinuation{continuation in queue.async{
            do {
                guard !self.closing,self.phase == .idle,!self.session.inputs.isEmpty else{throw SecurityCore.Failure.operation("The recording device is not ready")}
                self.microphone = try MicrophoneOwnership.shared.startRecording { [weak self] in
                    guard let self else { return }
                    await self.stopDevices()
                    self.onFinishRequested?()
                }
                self.session.startRunning()
                guard self.session.isRunning else { throw SecurityCore.Failure.operation("The microphone could not start") }
                self.file=output
                let writer=AVAssetWriter(contentType:.mpeg4Movie)
                writer.outputFileTypeProfile = .mpeg4AppleHLS
                writer.preferredOutputSegmentInterval=CMTime(seconds:1,preferredTimescale:600)
                writer.initialSegmentStartTime = .zero
                let sink=RecordingSegmentSink(output);self.sink=sink
                sink.onFailure={[weak self] in self?.onFinishRequested?()}
                sink.onLimit={[weak self] in self?.onFinishRequested?()}
                writer.delegate=sink
                if self.session.outputs.contains(where:{$0===self.audioOutput}){
                    let settings:[String:Any]=[AVFormatIDKey:kAudioFormatMPEG4AAC,AVSampleRateKey:48_000,AVNumberOfChannelsKey:1,AVEncoderBitRateKey:64_000]
                    let input=AVAssetWriterInput(mediaType:.audio,outputSettings:settings);input.expectsMediaDataInRealTime=true
                    guard writer.canAdd(input) else{throw SecurityCore.Failure.operation("Audio encoding is unavailable")};writer.add(input);self.audioInput=input
                }
                guard writer.startWriting() else{throw writer.error ?? SecurityCore.Failure.operation("The encoder could not start")}
                writer.startSession(atSourceTime:.zero);self.writer=writer;self.phase = .recording;self.onPhase?(.recording);continuation.resume()
            }catch{continuation.resume(throwing:error)}
        }}}catch{try await output.close();throw error}
    }
    func pause()async {
        await withCheckedContinuation{continuation in queue.async{
            if self.phase == .recording {self.pauseTime=ProcessInfo.processInfo.systemUptime;self.phase = .paused;self.onPhase?(.paused)};continuation.resume()
        }}
    }
    func resume()async {
        await withCheckedContinuation{continuation in queue.async{
            if self.phase == .paused {
                if let time=self.pauseTime{self.removedPause=CMTimeAdd(self.removedPause,CMTime(seconds:max(0,ProcessInfo.processInfo.systemUptime-time),preferredTimescale:1_000_000))}
                self.pauseTime=nil;self.phase = .recording;self.onPhase?(.recording)
            };continuation.resume()
        }}
    }
    func captureOutput(_ output:AVCaptureOutput,didOutput sampleBuffer:CMSampleBuffer,from connection:AVCaptureConnection){
        guard !closing,phase == .recording,let writer,writer.status == .writing else{return}
        guard output===audioOutput,let input=audioInput,input.isReadyForMoreMediaData else{return}
        let pts=CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if origin==nil{origin=pts}
        guard let origin else{return}
        let offset=CMTimeAdd(origin,removedPause)
        let relative=CMTimeSubtract(pts,offset)
        guard relative.isValid,relative >= .zero else{return}
        do {
            var count=0
            guard CMSampleBufferGetSampleTimingInfoArray(sampleBuffer,entryCount:0,arrayToFill:nil,entriesNeededOut:&count)==noErr,count>0 else{throw SecurityCore.Failure.invalidResponse}
            var timing=Array(repeating:CMSampleTimingInfo(duration:.invalid,presentationTimeStamp:.invalid,decodeTimeStamp:.invalid),count:count)
            let status=timing.withUnsafeMutableBufferPointer{CMSampleBufferGetSampleTimingInfoArray(sampleBuffer,entryCount:count,arrayToFill:$0.baseAddress,entriesNeededOut:&count)}
            guard status==noErr else{throw SecurityCore.Failure.invalidResponse}
            for index in timing.indices {
                if timing[index].presentationTimeStamp.isValid{timing[index].presentationTimeStamp=CMTimeSubtract(timing[index].presentationTimeStamp,offset)}
                if timing[index].decodeTimeStamp.isValid{timing[index].decodeTimeStamp=CMTimeSubtract(timing[index].decodeTimeStamp,offset)}
            }
            var adjusted:CMSampleBuffer?
            let copied=timing.withUnsafeBufferPointer{CMSampleBufferCreateCopyWithNewTiming(allocator:kCFAllocatorDefault,sampleBuffer:sampleBuffer,sampleTimingEntryCount:count,sampleTimingArray:$0.baseAddress!,sampleBufferOut:&adjusted)}
            guard copied==noErr,let adjusted,input.append(adjusted) else{throw writer.error ?? SecurityCore.Failure.operation("The encoder stopped accepting media")}
            let seconds = CMTimeGetSeconds(relative)
            if seconds - lastMeterUpdate >= 0.09 {
                lastMeterUpdate = seconds
                let power = connection.audioChannels.map(\.averagePowerLevel).max() ?? -.infinity
                let level: Float = power.isFinite ? min(1, max(0, (power + 54) / 48)) : 0
                onProgress?(seconds, level)
            }
            if seconds >= 1800{onFinishRequested?()}
        }catch{self.error=error;onError?("The recording could not be encoded");onFinishRequested?()}
    }
    private func stopDevices()async {
        await withCheckedContinuation{continuation in queue.async{
            self.callbacks.append{continuation.resume()}
            if self.stopped{self.completeStop();return}
            guard !self.stopping else{return};self.stopping=true;self.closing=true
            self.session.stopRunning();self.audioOutput.setSampleBufferDelegate(nil,queue:nil)
            MicrophoneOwnership.shared.release(self.microphone)
            self.microphone = nil
            self.audioInput?.markAsFinished()
            if let writer=self.writer,writer.status == .writing {
                writer.finishWriting{[self] in queue.async{if writer.status != .completed{self.error=writer.error ?? SecurityCore.Failure.operation("Recording finalization failed")};self.completeStop()}}
            }else{self.writer?.cancelWriting();self.completeStop()}
        }}
    }
    private func completeStop(){
        guard stopped || stopping else{return};stopped=true
        observers.forEach{NotificationCenter.default.removeObserver($0)};observers=[]
        session.beginConfiguration();for input in session.inputs{session.removeInput(input)};for output in session.outputs{session.removeOutput(output)};session.commitConfiguration()
        writer=nil;audioInput=nil
        let done=callbacks;callbacks=[];done.forEach{$0()}
    }
    func finish()async throws->CapturedRecording {
        let output:EncryptedCaptureFile=try await withCheckedThrowingContinuation{continuation in queue.async{
            if let file=self.file{continuation.resume(returning:file)}else{continuation.resume(throwing:SecurityCore.Failure.operation("No recording is active"))}
        }}
        _=try await output.freeze()
        try await withCheckedThrowingContinuation{(continuation:CheckedContinuation<Void,Error>) in queue.async{
            do{if let error=self.error{throw error};try self.sink?.check();self.phase = .ready;self.onPhase?(.ready);continuation.resume()}catch{continuation.resume(throwing:error)}
        }}
        return try CapturedRecording(output)
    }
    func close()async throws {
        let output:EncryptedCaptureFile?=await withCheckedContinuation{continuation in queue.async{continuation.resume(returning:self.file)}}
        if let output{try await output.close()}else{await stopDevices()}
        await withCheckedContinuation{continuation in queue.async{self.file=nil;self.sink=nil;self.phase = .closed;self.onPhase?(.closed);continuation.resume()}}
    }
}

struct VideoTrim: Sendable, Equatable {
    static let minimumMs: Int64 = 250
    let startMs: Int64
    let endMs: Int64
    let muteAudio: Bool

    init(startMs: Int64, endMs: Int64, muteAudio: Bool = false) {
        self.startMs = startMs
        self.endMs = endMs
        self.muteAudio = muteAudio
    }

    func fits(durationMs: Int64) -> Bool {
        startMs >= 0 && endMs <= durationMs && endMs > startMs && endMs - startMs >= Self.minimumMs
    }
}

/// Transcodes a selected movie using AVFoundation's in-memory MP4 segments.
/// The import queue retains the source grant and waits for the encoder to drain.
enum VideoCompression {
    private static let maximumInput = 100 * 1024 * 1024
    private enum EncodingFailure: Error { case unsupported, incomplete }

    static func importOnWorker(_ url: URL,
                               preparation: ObjectPreparation?, trim: VideoTrim? = nil,
                               compress: Bool = true, asFile: Bool = false) throws -> [String: Any]? {
        let completion = WorkerResult()
        Task.detached(priority: .utility) {
            do {
                let manifest = try await importVideo(url, preparation: preparation,
                                                     trim: trim, compress: compress, asFile: asFile)
                completion.finish(.success(manifest))
            } catch {
                completion.finish(.failure(error))
            }
        }
        return try completion.wait()
    }

    private static func importVideo(_ url: URL,
                                    preparation: ObjectPreparation?, trim: VideoTrim?, compress: Bool,
                                    asFile: Bool) async throws -> [String: Any]? {
        try preparation?.check()
        guard let size = try inputSize(url) else { return nil }

        let asset = AVURLAsset(url: url)
        let capacity = trim == nil ? size - 1 : maximumInput
        let plan: EncodingPlan
        do {
            let source = try await Source.inspect(asset)
            plan = try await EncodingPlan.make(asset: asset, source: source, originalSize: size,
                                               capacity: capacity, trim: trim, compress: compress)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try preparation?.check()
            return nil
        }
        try preparation?.setStep(.encodingVideo)

        let owner = EncoderLifetime()
        let basename = url.deletingPathExtension().lastPathComponent
        let filename = trim.map { String(basename.prefix(160)) + ($0.muteAudio ? "-muted.mp4" : "-trimmed.mp4") }
            ?? basename + ".mp4"
        let staged = try await EncryptedCaptureStore.shared.open(
            filename: filename, mime: "video/mp4", capacity: Int64(capacity), stopWriter: { await owner.stop() }
        )
        do {
            try await owner.run { try await encode(plan: plan, destination: staged, preparation: preparation) }
        } catch {
            try await staged.close()
            try preparation?.check()
            if error is CancellationError { throw error }
            return nil
        }

        var manifest: [String: Any]?
        do {
            _ = try await staged.freeze()
            try preparation?.setStep(.importing)
            manifest = try EncryptedObjectStore.shared.importRecording(staged, preparation: preparation, asFile: asFile)
            try await staged.close()
            return manifest
        } catch {
            if let manifest { try? EncryptedObjectStore.shared.discardCopy(manifest) }
            try await staged.close()
            throw error
        }
    }

    static func trimDuration(_ url: URL, asset: AVURLAsset) async throws -> Int64? {
        guard try inputSize(url) != nil else { return nil }
        let source = try await Source.inspect(asset)
        return source.durationMs
    }

    private static func inputSize(_ url: URL) throws -> Int? {
        guard url.isFileURL else { return nil }
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true, let size = values.fileSize, (2...maximumInput).contains(size) else { return nil }
        return size
    }

    private struct Source {
        let duration: CMTime
        let video: AVAssetTrack
        let audio: AVAssetTrack?
        let frameRate: Int
        let oriented: CGRect
        let transform: CGAffineTransform
        var durationMs: Int64 { CMTimeConvertScale(duration, timescale: 1000, method: .roundTowardZero).value }

        static func inspect(_ asset: AVAsset) async throws -> Source {
            let duration = try await asset.load(.duration)
            let seconds = duration.seconds
            guard seconds.isFinite, seconds > 0, seconds <= 600 else { throw EncodingFailure.unsupported }
            let tracks = try await asset.load(.tracks)
            let videos = tracks.filter { $0.mediaType == .video }
            let audio = tracks.filter { $0.mediaType == .audio }
            guard videos.count == 1, audio.count <= 1,
                  !tracks.contains(where: { $0.mediaType == .subtitle || $0.mediaType == .closedCaption }),
                  let video = videos.first, !video.hasMediaCharacteristic(.containsHDRVideo) else {
                throw EncodingFailure.unsupported
            }

            let size = try await video.load(.naturalSize)
            let transform = try await video.load(.preferredTransform)
            let oriented = CGRect(origin: .zero, size: size).applying(transform).standardized
            guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0,
                  size.width * size.height <= 4096 * 2304,
                  oriented.width.isFinite, oriented.height.isFinite, oriented.width >= 2, oriented.height >= 2,
                  oriented.minX.isFinite, oriented.minY.isFinite else { throw EncodingFailure.unsupported }
            let sourceRate = try await video.load(.nominalFrameRate)
            let rate = sourceRate.isFinite && sourceRate > 0 ? Int(min(30, max(1, sourceRate.rounded()))) : 30
            return Source(duration: duration, video: video, audio: audio.first, frameRate: rate,
                          oriented: oriented, transform: transform)
        }
    }

    private struct EncodingPlan: @unchecked Sendable {
        let asset: AVAsset
        let video: AVAssetTrack
        let audio: AVAssetTrack?
        let duration: CMTime
        let frameRate: Int
        let renderSize: CGSize
        let transform: CGAffineTransform
        let bitrate: Int
        let isTrimmed: Bool

        static func make(asset: AVAsset, source: Source, originalSize: Int, capacity: Int,
                         trim: VideoTrim?, compress: Bool) async throws -> EncodingPlan {
            var input = asset
            var video = source.video
            var audio = trim?.muteAudio == true ? nil : source.audio
            var duration = source.duration
            if let trim {
                guard trim.fits(durationMs: source.durationMs) else { throw EncodingFailure.unsupported }
                let start = CMTime(value: trim.startMs, timescale: 1000)
                duration = CMTime(value: trim.endMs - trim.startMs, timescale: 1000)
                let selected = CMTimeRange(start: start, duration: duration)
                let composition = AVMutableComposition()
                guard let clippedVideo = try await insert(source.video, selected: selected, into: composition) else {
                    throw EncodingFailure.unsupported
                }
                video = clippedVideo
                if let sourceAudio = audio {
                    audio = try await insert(sourceAudio, selected: selected, into: composition)
                }
                input = composition
            }
            let oriented = source.oriented
            let maximumEdge: CGFloat = compress ? 1280 : 2160
            let scale = min(1, maximumEdge / max(oriented.width, oriented.height))
            let width = max(2, Int(oriented.width * scale) & ~1)
            let height = max(2, Int(oriented.height * scale) & ~1)
            let adjusted = source.transform
                .concatenating(CGAffineTransform(translationX: -oriented.minX, y: -oriented.minY))
                .concatenating(CGAffineTransform(scaleX: CGFloat(width) / oriented.width, y: CGFloat(height) / oriented.height))
            let audioRate = audio == nil ? 0.0 : 96_000.0
            let originalRate = Double(originalSize) * 8 / source.duration.seconds
            let requested = compress ? min(2_500_000, originalRate * 0.82 - audioRate)
                : max(400_000, min(10_000_000, originalRate - audioRate))
            let bitrate = Int(min(requested, Double(capacity) * 8 * 0.82 / duration.seconds - audioRate))
            guard bitrate >= 150_000 else { throw EncodingFailure.unsupported }

            return EncodingPlan(asset: input, video: video, audio: audio, duration: duration, frameRate: source.frameRate,
                                renderSize: CGSize(width: width, height: height), transform: adjusted, bitrate: bitrate,
                                isTrimmed: trim != nil)
        }

        private static func insert(_ track: AVAssetTrack, selected: CMTimeRange,
                                   into composition: AVMutableComposition) async throws -> AVMutableCompositionTrack? {
            let available = try await track.load(.timeRange)
            let slice = CMTimeRangeGetIntersection(selected, otherRange: available)
            guard slice.isValid, !slice.isEmpty else { return nil }
            guard let target = composition.addMutableTrack(withMediaType: track.mediaType,
                                                           preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw EncodingFailure.unsupported
            }
            try target.insertTimeRange(slice, of: track, at: CMTimeSubtract(slice.start, selected.start))
            return target
        }
    }

    private struct TrackPipe {
        let output: AVAssetReaderOutput
        let input: AVAssetWriterInput
        var complete = false
    }

    private static func encode(plan: EncodingPlan, destination: EncryptedCaptureFile,
                               preparation: ObjectPreparation?) async throws {
        try Task.checkCancellation()
        let reader = try AVAssetReader(asset: plan.asset)
        let writer = AVAssetWriter(contentType: .mpeg4Movie)
        let sink = RecordingSegmentSink(destination)
        writer.delegate = sink
        writer.outputFileTypeProfile = .mpeg4AppleHLS
        writer.preferredOutputSegmentInterval = CMTime(seconds: 2, preferredTimescale: 600)
        writer.initialSegmentStartTime = .zero
        let range = CMTimeRange(start: .zero, duration: plan.duration)
        reader.timeRange = range

        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: plan.video)
        layer.setTransform(plan.transform, at: .zero)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = range
        instruction.layerInstructions = [layer]
        let composition = AVMutableVideoComposition()
        composition.renderSize = plan.renderSize
        composition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(plan.frameRate))
        composition.instructions = [instruction]

        let videoOutput = AVAssetReaderVideoCompositionOutput(videoTracks: [plan.video],
            videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        videoOutput.videoComposition = composition
        videoOutput.alwaysCopiesSampleData = false
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(plan.renderSize.width),
            AVVideoHeightKey: Int(plan.renderSize.height),
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: plan.bitrate,
                AVVideoExpectedSourceFrameRateKey: plan.frameRate
            ]
        ])
        guard reader.canAdd(videoOutput), writer.canAdd(videoInput) else { throw EncodingFailure.unsupported }
        reader.add(videoOutput)
        writer.add(videoInput)
        var pipes = [TrackPipe(output: videoOutput, input: videoInput)]
        if let track = plan.audio {
            let descriptions = try await track.load(.formatDescriptions)
            let format = descriptions.first.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0) }
            let channels = format?.pointee.mChannelsPerFrame == 1 ? 1 : 2
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsNonInterleaved: false
            ])
            output.alwaysCopiesSampleData = false
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: channels,
                AVSampleRateKey: 48_000,
                AVEncoderBitRateKey: 96_000
            ])
            guard reader.canAdd(output), writer.canAdd(input) else { throw EncodingFailure.unsupported }
            reader.add(output)
            writer.add(input)
            pipes.append(TrackPipe(output: output, input: input))
        }

        defer {
            if reader.status == .reading { reader.cancelReading() }
            if writer.status == .writing { writer.cancelWriting() }
            writer.delegate = nil
            sink.stopAccepting()
        }
        guard reader.startReading(), writer.startWriting() else { throw EncodingFailure.incomplete }
        writer.startSession(atSourceTime: .zero)
        var lastPercent: Int64 = -1
        var wroteVideo = false
        while pipes.contains(where: { !$0.complete }) {
            try Task.checkCancellation()
            try preparation?.check()
            try sink.checkFailure()
            guard reader.status == .reading || reader.status == .completed,
                  writer.status == .writing else { throw EncodingFailure.incomplete }
            var advanced = false
            for index in pipes.indices where !pipes[index].complete && pipes[index].input.isReadyForMoreMediaData {
                let pipe = pipes[index]
                if let raw = pipe.output.copyNextSampleBuffer() {
                    advanced = true
                    let sample: CMSampleBuffer
                    if plan.isTrimmed {
                        if index == 0 {
                            let seconds = CMSampleBufferGetPresentationTimeStamp(raw).seconds
                            guard seconds.isFinite, seconds >= 0, seconds < plan.duration.seconds else { continue }
                            sample = raw
                        } else {
                            guard let clipped = try trimPCM(raw, duration: plan.duration) else { continue }
                            sample = clipped
                        }
                    } else {
                        sample = raw
                    }
                    guard pipe.input.append(sample) else { throw EncodingFailure.incomplete }
                    if index == 0 {
                        wroteVideo = true
                        let timestamp = CMSampleBufferGetPresentationTimeStamp(sample).seconds
                        if timestamp.isFinite {
                            let percent = Int64(max(0, min(100, timestamp / plan.duration.seconds * 100)))
                            if percent > lastPercent {
                                try preparation?.report(total: 100, completed: percent)
                                lastPercent = percent
                            }
                        }
                    }
                } else {
                    pipe.input.markAsFinished()
                    pipes[index].complete = true
                }
                advanced = true
            }
            if !advanced { try await Task.sleep(nanoseconds: 10_000_000) }
        }
        guard reader.status == .completed, wroteVideo else { throw EncodingFailure.incomplete }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            writer.finishWriting { continuation.resume() }
        }
        try Task.checkCancellation()
        try preparation?.check()
        guard writer.status == .completed else { throw EncodingFailure.incomplete }
        try sink.check()
    }

    private static func trimPCM(_ sample: CMSampleBuffer, duration: CMTime) throws -> CMSampleBuffer? {
        let count = CMSampleBufferGetNumSamples(sample)
        let start = CMSampleBufferGetPresentationTimeStamp(sample).seconds
        guard count > 0, start.isFinite,
              let description = CMSampleBufferGetFormatDescription(sample),
              let format = CMAudioFormatDescriptionGetStreamBasicDescription(description),
              format.pointee.mSampleRate.isFinite, format.pointee.mSampleRate > 0 else { throw EncodingFailure.incomplete }
        let rate = format.pointee.mSampleRate
        let first = Int(min(Double(count), max(0, (-start * rate).rounded(.up))))
        let end = Int(min(Double(count), max(0, ((duration.seconds - start) * rate).rounded(.down))))
        guard end > first else { return nil }
        if first == 0, end == count { return sample }
        var clipped: CMSampleBuffer?
        let status = CMSampleBufferCopySampleBufferForRange(allocator: kCFAllocatorDefault, sampleBuffer: sample,
                                                           sampleRange: CFRange(location: first, length: end - first),
                                                           sampleBufferOut: &clipped)
        guard status == noErr, let clipped else { throw EncodingFailure.incomplete }
        return clipped
    }

    private final class WorkerResult: @unchecked Sendable {
        private let ready = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var result: Result<[String: Any]?, Error>?

        func finish(_ result: Result<[String: Any]?, Error>) {
            lock.withLock { self.result = result }
            ready.signal()
        }

        func wait() throws -> [String: Any]? {
            ready.wait()
            return try lock.withLock { try result!.get() }
        }
    }

    private final class EncoderLifetime: @unchecked Sendable {
        private let lock = NSLock()
        private var active: Task<Void, Error>?
        private var closing = false

        func run(_ operation: @escaping @Sendable () async throws -> Void) async throws {
            let task = try lock.withLock {
                guard !closing else { throw CancellationError() }
                let task = Task.detached(priority: .utility, operation: operation)
                active = task
                return task
            }
            try await task.value
        }

        func stop() async {
            let task = lock.withLock { closing = true; return active }
            task?.cancel()
            if let task { _ = await task.result }
        }
    }
}
