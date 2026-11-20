// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit
import Combine
@preconcurrency import AVFoundation

@MainActor
private final class RecordingModel:ObservableObject {
    private var worker = RecordingController()
    @Published var phase=RecordingPhase.idle
    @Published var microphones=[RecordingDevice]()
    @Published var microphone=""
    @Published var ready = false
    @Published var busy = false
    @Published var closing = false
    @Published var seconds=0.0
    @Published private(set) var levels: [Float] = []
    @Published var error:String?
    @Published var result:CapturedRecording?
    @Published private var finishFailed = false
    let playback = MediaPlaybackModel()
    private var prepareGeneration=0
    private var preparedMicrophone: String?
    private var starting = false
    private var finishAfterOperation = false
    var canRecordAgain: Bool { phase == .ready || phase == .closed || finishFailed }
    var canUseRecording: Bool { result != nil && !finishFailed && !busy && !closing }

    init() { observeWorker(worker) }

    private func observeWorker(_ worker: RecordingController) {
        worker.onProgress = { [weak self, weak worker] duration, level in
            Task { @MainActor in
                guard let self, self.worker === worker, !self.closing, self.phase == .recording else { return }
                self.seconds = max(self.seconds, duration)
                if self.levels.count == 28 { self.levels.removeFirst() }
                self.levels.append(level)
            }
        }
        worker.onError = { [weak self, weak worker] message in
            Task { @MainActor in
                guard let self, self.worker === worker, !self.closing else { return }
                self.error = message
            }
        }
        worker.onFinishRequested = { [weak self, weak worker] in
            Task { @MainActor in
                guard let self, self.worker === worker else { return }
                await self.interrupt()
            }
        }
    }
    private func permission(_ type:AVMediaType)async->Bool {
        switch AVCaptureDevice.authorizationStatus(for:type){
        case .authorized:return true
        case .notDetermined:return await AVCaptureDevice.requestAccess(for:type)
        default:return false
        }
    }
    func prepare()async {
        guard !closing,phase == .idle,!busy else{return}
        busy=true;ready=false;prepareGeneration+=1;let generation=prepareGeneration
        defer{if generation==prepareGeneration{busy=false}}
        do {
            guard await permission(.audio) else{throw SecurityCore.Failure.operation("Allow microphone access in System Settings")}
            guard !closing,generation==prepareGeneration else{return}
            microphones=AVCaptureDevice.devices(for:.audio).map{RecordingDevice(id:$0.uniqueID,name:$0.localizedName)}
            if microphone.isEmpty{microphone=microphones.first?.id ?? ""}
            try await worker.prepare(microphone:microphone)
            if !closing{preparedMicrophone=microphone;ready=true;error=nil}
        }catch{if !closing{self.error=error.localizedDescription}}
    }
    func selectMicrophone() { if preparedMicrophone != microphone { ready = false } }

    func start()async {
        guard ready,!busy,!closing,CallManager.shared.callState == .idle || CallManager.shared.callState == .ended else{return}
        busy=true;starting=true
        do{try await worker.start();if !closing {phase = .recording;ready=false}}
        catch{self.error="Recording could not start.";try? await worker.close();phase = .closed}
        starting=false;busy=false
        if finishAfterOperation { await finish() }
    }
    func togglePause()async {
        guard !busy,!closing else{return};busy=true
        if phase == .recording{await worker.pause();phase = .paused}else if phase == .paused{await worker.resume();phase = .recording}
        busy=false
        if finishAfterOperation { await finish() }
    }
    func interrupt() async {
        playback.pause()
        guard !finishFailed, starting || phase == .recording || phase == .paused else { return }
        if busy { finishAfterOperation = true }
        else { await finish() }
    }
    func finish()async {
        finishAfterOperation=false
        guard !busy,!closing,!finishFailed,result==nil,phase == .recording || phase == .paused else{return}
        busy=true;defer{busy=false}
        do{
            let value=try await worker.finish()
            guard !closing else{try? value.close();return}
            result=value;phase = .ready
            playback.openRecording(value.file)
        }catch{
            self.error="Recording could not be finalized."
            finishFailed = true
        }
    }

    func recordAgain() async {
        guard canRecordAgain, !busy, !closing else { return }
        busy = true
        finishFailed = true
        playback.close()
        do {
            try await worker.close()
            guard !closing else { busy = false; return }
            try result?.close()
            result = nil
            worker = RecordingController()
            observeWorker(worker)
            phase = .idle
            seconds = 0
            levels.removeAll()
            finishFailed = false
            finishAfterOperation = false
            preparedMicrophone = nil
            ready = false
            error = nil
        } catch {
            self.error = "Recording resources have not finished closing"
        }
        busy = false
        if phase == .idle { await prepare() }
    }
    func close()async {
        guard !closing else{return};closing=true;prepareGeneration+=1
        playback.close()
        do{try await worker.close();phase = .closed}catch{self.error="Recording resources have not finished closing";closing=false}
    }
    func takeResult()async->CapturedRecording? {
        guard canUseRecording,let value=result else{return nil}
        await close();guard closing else{return nil};result=nil;return value
    }
}

struct RecordingView:View {
    let onRecorded:(CapturedRecording)->Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.layoutDirection) private var layoutDirection
    @StateObject private var model:RecordingModel
    @ObservedObject private var calls=CallManager.shared
    @State private var yieldingToCall = false
    init(onRecorded:@escaping(CapturedRecording)->Void){self.onRecorded=onRecorded;_model=StateObject(wrappedValue:RecordingModel())}
    var body:some View {
        VStack(alignment:.leading,spacing:12){
            HStack {
                if !model.canRecordAgain && (model.phase == .recording || model.phase == .paused) {
                    Circle().fill(model.phase == .recording ? Color.red : Color.secondary)
                        .frame(width: 8, height: 8).accessibilityHidden(true)
                }
                Text(interfaceText("voice_message")).font(.headline)
                Spacer()
                Text(model.playback.timeText(model.seconds)).monospacedDigit().font(.title2)
            }
            if model.phase == .idle {
                Picker(interfaceText("recording_microphone"),selection:$model.microphone){ForEach(model.microphones){Text($0.name).tag($0.id)}}.disabled(model.busy)
                if !model.ready {
                    Button(interfaceText("recording_prepare")) { Task { await model.prepare() } }.disabled(model.busy)
                }
            }
            if !model.canRecordAgain && (model.phase == .recording || model.phase == .paused) {
                recordingMeter
            }
            if model.phase == .ready {
                RecordingPlaybackBar(playback: model.playback) {
                    if let file = model.result?.file { model.playback.openRecording(file, resumeAt: model.playback.position) }
                }
            }
            if model.busy{ProgressView().controlSize(.small)}
            if let error=model.error{Text(error).foregroundStyle(.red).font(.callout)}
            HStack{
                Button(interfaceText("cancel")){Task{await model.close();if model.closing{dismiss()}}}.keyboardShortcut(.cancelAction).disabled(model.busy)
                Spacer()
                if model.canRecordAgain {
                    control("arrow.counterclockwise", "recording_again") { await model.recordAgain() }
                        .disabled(model.busy || model.closing)
                }
                switch model.phase {
                case .idle:
                    control("mic.fill", "recording_start") { await model.start() }
                        .disabled(!model.ready || model.busy)
                case .recording,.paused:
                    let paused = model.phase == .paused
                    control(paused ? "play.fill" : "pause.fill", paused ? "recording_resume" : "pause") { await model.togglePause() }
                        .disabled(model.busy || model.canRecordAgain)
                    control("stop.fill", "recording_finish") { await model.finish() }.disabled(model.busy || model.canRecordAgain)
                case .ready:
                    Button(interfaceText("recording_use")){Task{if let result=await model.takeResult(){onRecorded(result);dismiss()}}}
                        .buttonStyle(.borderedProminent).disabled(!model.canUseRecording)
                case .closed:EmptyView()
                }
            }
        }.padding(20).frame(width:400)
        .disabled(yieldingToCall)
        .task{await model.prepare()}
        .onChange(of:model.microphone){_ in
            model.selectMicrophone()
            if !model.ready { Task { await model.prepare() } }
        }
        .onChange(of: calls.callState) { state in
            guard state != .idle && state != .ended else { return }
            yieldingToCall = true
            Task { await yieldToCall() }
        }
        .onChange(of: model.busy) { busy in
            if yieldingToCall && !busy { Task { await yieldToCall() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
            Task { await model.interrupt() }
        }
        .onDisappear{Task{await model.close()}}
    }

    @MainActor
    private func yieldToCall() async {
        guard !model.closing else { return }
        await model.interrupt()
        guard !model.busy, !model.closing else { return }
        if model.canUseRecording {
            guard let result = await model.takeResult() else { yieldingToCall = false; return }
            onRecorded(result)
        } else {
            await model.close()
            guard model.closing else { yieldingToCall = false; return }
        }
        dismiss()
    }

    private var recordingMeter: some View {
        let levels = model.levels
        let color = model.phase == .recording ? Color.red : Color.secondary
        let rtl = layoutDirection == .rightToLeft
        return Canvas { context, size in
            let step = min(6, size.width / 28)
            let start = (size.width - 28 * step) / 2
            var bars = Path()
            for index in 0..<28 {
                let sample = levels.count - 28 + index
                let level = sample >= 0 ? CGFloat(levels[sample]) : 0
                let slot = rtl ? 27 - index : index
                let x = start + (CGFloat(slot) + 0.5) * step
                let halfHeight = 1 + level * (size.height / 2 - 2)
                bars.move(to: CGPoint(x: x, y: size.height / 2 - halfHeight))
                bars.addLine(to: CGPoint(x: x, y: size.height / 2 + halfHeight))
            }
            context.stroke(bars, with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
        .frame(height: 24)
        .accessibilityHidden(true)
    }

    private func control(_ symbol: String, _ title: String, action: @escaping @MainActor () async -> Void) -> some View {
        Button { Task { await action() } } label: {
            Image(systemName: symbol).frame(width: 24, height: 22)
        }
        .help(interfaceText(title)).accessibilityLabel(interfaceText(title))
    }
}

struct RecordingPlaybackBar: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @ObservedObject var playback: MediaPlaybackModel
    let onRetry: () -> Void
    @State private var scrub: Double?

    var body: some View {
        if playback.failed {
            HStack {
                Text(interfaceText("media_playback_unavailable")).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(interfaceText("retry")) {
                    scrub = nil
                    onRetry()
                }
            }
        } else {
            HStack(spacing: 10) {
                let title = interfaceText(playback.isPlaying ? "pause" : "recording_play")
                Button { playback.toggle() } label: {
                    Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                        .frame(width: 24, height: 22)
                }
                .help(title).accessibilityLabel(title).disabled(playback.preparing || playback.duration <= 0)
                Slider(value: Binding(get: { scrub ?? playback.position }, set: { value in
                    if scrub != nil { scrub = value } else { playback.seek(to: value) }
                }), in: 0...max(0.001, playback.duration), onEditingChanged: { editing in
                    if editing { scrub = playback.position }
                    else if let target = scrub { scrub = nil; playback.seek(to: target) }
                })
                .accessibilityLabel(interfaceText("media_seek"))
                .disabled(playback.preparing || playback.duration <= 0)
                if playback.preparing { ProgressView().controlSize(.small) }
                else {
                    Text("\(playback.timeText(scrub ?? playback.position)) / \(playback.timeText(playback.duration, duration: true))")
                        .monospacedDigit().font(.caption)
                }
            }
        }
    }
}
