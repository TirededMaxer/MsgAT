// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.R
import org.msgat.ui.theme.uiString

import android.Manifest
import android.content.pm.PackageManager
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.msgat.core.media.*
import org.msgat.core.call.CallManager
import org.msgat.core.call.CallState

@Composable
internal fun VideoRecordingDialog(onDismiss:()->Unit,onRecorded:(RecordingMediaSource)->Unit){
    val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    val calls = remember { CallManager.getInstance() }
    val callState by calls.callState.collectAsState()
    fun canCapture(state: CallState) = state == CallState.IDLE || state == CallState.ENDED
    val callActive = !canCapture(callState)
    val owner=remember{CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)}
    val active=remember{java.util.concurrent.atomic.AtomicBoolean(true)}
    val previewView=remember{PreviewView(context).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER}}
    val preview=remember{Preview.Builder().build().also{it.setSurfaceProvider(previewView.surfaceProvider)}}
    val recorder=remember{CameraPipeMuxer.useWith(Recorder.Builder()).setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.HD,Quality.SD),FallbackStrategy.lowerQualityThan(Quality.SD))).build()}
    val video=remember{VideoCapture.withOutput(recorder)}
    var provider by remember{mutableStateOf<ProcessCameraProvider?>(null)}
    val controller=remember{VideoRecordingController(context,org.msgat.MsgATApp.instance.nettyClient.objects.captures){provider?.unbind(preview,video)}}
    val phase by controller.phase.collectAsState()
    val elapsed by controller.elapsedMillis.collectAsState()
    val autoFinished by controller.autoFinished.collectAsState()
    var cameraPermission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var microphonePermission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)}
    var sound by remember{mutableStateOf(true)}
    var camera by remember{mutableStateOf<Camera?>(null)}
    var front by remember{mutableStateOf(false)}
    var canSwitch by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var started by remember { mutableStateOf(false) }
    var finishFailed by remember { mutableStateOf(false) }
    var error by remember{mutableStateOf<Int?>(null)}
    var source by remember{mutableStateOf<RecordingMediaSource?>(null)}
    var player by remember{mutableStateOf<VideoView?>(null)}
    var playbackPosition by remember { mutableIntStateOf(0) }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->
        cameraPermission=grants[Manifest.permission.CAMERA]?:cameraPermission
        microphonePermission=grants[Manifest.permission.RECORD_AUDIO]?:microphonePermission
    }
    LaunchedEffect(Unit){if(!cameraPermission || !microphonePermission)request.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))}
    val shouldBindCamera = cameraPermission && !callActive && !started && source == null && phase == RecordingPhase.IDLE
    DisposableEffect(shouldBindCamera,front,lifecycle){
        var attached=true
        if(shouldBindCamera){
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({if(attached && active.get() && canCapture(calls.callState.value))try{
                val manager=future.get();provider=manager
                val back=manager.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);val selfie=manager.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                canSwitch=back && selfie
                val selector=if(front && selfie || !back)CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                manager.unbind(preview,video)
                camera=manager.bindToLifecycle(lifecycle,selector,preview,video)
                error=null
            }catch(_:Exception){error=R.string.ui_capture_camera_unavailable}},ContextCompat.getMainExecutor(context))
        }
        onDispose{attached=false}
    }
    val finish by rememberUpdatedState<suspend ()->Unit>({
        if(!busy && source==null){busy=true
            try{
                val output=controller.finish()
                val registered=withContext(Dispatchers.IO){RecordingMediaStore.register(context,output)}
                if(active.get())source=registered else registered.close()
            }catch(_:Exception){if(active.get()) {
                finishFailed = true
                error = R.string.ui_capture_video_finish_failed
            }}
            finally{busy=false}
        }
    })
    LaunchedEffect(autoFinished, callActive, busy, started, source, finishFailed) {
        if (callActive && !started && !busy) {
            provider?.unbind(preview, video)
            camera = null
        }
        if (!busy && started && source == null && !finishFailed && (callActive || autoFinished)) owner.launch { finish() }
    }
    LaunchedEffect(callActive) {
        if (!callActive) return@LaunchedEffect
        player?.let {
            playbackPosition = it.currentPosition
            it.stopPlayback()
        }
        player = null
    }
    DisposableEffect(lifecycle){
        val observer=object:androidx.lifecycle.DefaultLifecycleObserver{
            override fun onStop(ownerLifecycle:androidx.lifecycle.LifecycleOwner){
                player?.pause()
                if(active.get() && controller.phase.value in listOf(RecordingPhase.RECORDING,RecordingPhase.PAUSED))owner.launch{finish()}
            }
        }
        lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(Unit){onDispose{
        active.set(false);player?.stopPlayback();player=null;source?.close();source=null
        owner.launch{try{controller.close()}finally{provider?.unbind(preview,video);owner.cancel()}}
    }}
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)){
        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max=680.dp).fillMaxWidth(.96f).fillMaxHeight(.92f),shape=MaterialTheme.shapes.large){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                val showingCamera = (!callActive || started || busy) && cameraPermission && source == null &&
                    phase != RecordingPhase.READY && phase != RecordingPhase.CLOSED
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(uiString(R.string.ui_attachment_record_video),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    Text("%02d:%02d".format(elapsed/60_000,(elapsed/1000)%60))
                    camera?.takeIf { showingCamera && it.cameraInfo.hasFlashUnit() }?.let { bound ->
                        VideoTorchButton(bound, enabled = !busy) { error = R.string.ui_capture_torch_unavailable }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                    val recorded=source
                    if(recorded==null) {
                        if(showingCamera) CameraPreview(previewView,camera,!busy,Modifier.fillMaxSize())
                    }
                    else if (!callActive) AndroidView(factory={VideoView(it).also{view->
                        player=view;view.setVideoURI(recorded.uri)
                        view.setMediaController(android.widget.MediaController(it).also{controls->controls.setAnchorView(view)})
                        view.setOnPreparedListener {
                            it.isLooping = false
                            view.seekTo(playbackPosition.coerceAtLeast(1))
                        }
                        view.setOnErrorListener{_,_,_->error=R.string.ui_capture_video_preview_failed;true}
                    }},modifier=Modifier.fillMaxSize())
                }
                if(!cameraPermission)Button(onClick={request.launch(arrayOf(Manifest.permission.CAMERA))}){Text(uiString(R.string.ui_capture_allow_camera))}
                error?.let{Text(uiString(it),color=MaterialTheme.colorScheme.error)}
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(phase==RecordingPhase.IDLE)Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(sound,{sound=it},enabled=!busy);Text(uiString(R.string.ui_capture_record_sound))
                    if(sound && !microphonePermission)TextButton(onClick={request.launch(arrayOf(Manifest.permission.RECORD_AUDIO))}){Text(uiString(R.string.ui_capture_allow_microphone))}
                    Spacer(Modifier.weight(1f))
                    IconButton(enabled=camera!=null && canSwitch && !busy,onClick={front=!front}){
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription=uiString(R.string.ui_call_switch_camera))
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End)){
                    TextButton(enabled=!busy,onClick=onDismiss){Text(uiString(R.string.ui_cancel))}
                    when(phase){
                        RecordingPhase.IDLE->Button(enabled=!callActive && camera!=null && (!sound || microphonePermission) && !busy && !started,onClick={busy=true;owner.launch{
                            try{
                                if (!canCapture(calls.callState.value)) return@launch
                                video.targetRotation=previewView.display?.rotation?:android.view.Surface.ROTATION_0
                                controller.start(recorder,sound)
                                started = true
                            }catch(_:Exception){error=R.string.ui_capture_video_start_failed}finally{busy=false}
                        }}){Text(uiString(R.string.ui_attachment_record_video))}
                        RecordingPhase.RECORDING,RecordingPhase.PAUSED->{
                            OutlinedButton(enabled=!busy,onClick={busy=true;owner.launch{
                                try{if(phase==RecordingPhase.RECORDING)controller.pause()else controller.resume()}catch(_:Exception){error=R.string.ui_capture_video_state_failed}finally{busy=false}
                            }}){Text(uiString(if(phase==RecordingPhase.RECORDING) R.string.ui_pause else R.string.ui_record_resume))}
                            Button(enabled=!busy,onClick={owner.launch{finish()}}){Text(uiString(R.string.ui_record_finish))}
                        }
                        RecordingPhase.READY->source?.let{recorded->Button(enabled=!busy,onClick={
                            player?.stopPlayback();player=null;source=null;onRecorded(recorded)
                        }){Text(uiString(R.string.ui_capture_use_video))}}
                        else->Unit
                    }
                }
            }
        }
    }
}
