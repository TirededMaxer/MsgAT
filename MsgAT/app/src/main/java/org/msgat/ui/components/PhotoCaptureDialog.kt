// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.R
import org.msgat.ui.theme.uiString

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.msgat.core.media.MemoryMediaSource
import org.msgat.core.media.MemoryMediaStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

private class PhotoBytes:ByteArrayOutputStream(){override fun close(){buf.fill(0);reset()}}
private enum class PhotoFlash(val mode: Int, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Off(ImageCapture.FLASH_MODE_OFF, R.string.ui_capture_flash_off, Icons.Default.FlashOff),
    Auto(ImageCapture.FLASH_MODE_AUTO, R.string.ui_capture_flash_auto, Icons.Default.FlashAuto),
    On(ImageCapture.FLASH_MODE_ON, R.string.ui_capture_flash_on, Icons.Default.FlashOn)
}

private fun normalizedPhoto(image:ImageProxy):ByteArray {
    check(image.format==android.graphics.ImageFormat.JPEG){"Unsupported camera image format"}
    val buffer=image.planes[0].buffer
    check(buffer.remaining() in 1..16*1024*1024){"The captured image is too large"}
    val encoded=ByteArray(buffer.remaining()).also{buffer.get(it)}
    var decoded:Bitmap?=null;var rotated:Bitmap?=null
    try {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(encoded,0,encoded.size,bounds)
        check(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth<=64_000_000/bounds.outHeight){"Unsupported camera dimensions"}
        var sample=1;while(bounds.outWidth/sample>2560 || bounds.outHeight/sample>2560)sample*=2
        decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.size,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("Photo could not be decoded")
        rotated=if(image.imageInfo.rotationDegrees==0)decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply{postRotate(image.imageInfo.rotationDegrees.toFloat())},true)
        return PhotoBytes().use{out->check(rotated.compress(Bitmap.CompressFormat.JPEG,95,out));out.toByteArray().also{check(it.size<=16*1024*1024)}}
    }finally{if(rotated!==decoded)rotated?.recycle();decoded?.recycle();encoded.fill(0)}
}

@Composable
internal fun PhotoCaptureDialog(onDismiss:()->Unit,onCaptured:(MemoryMediaSource)->Unit){
    val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    val active=remember{AtomicBoolean(true)}
    val view=remember(context){PreviewView(context).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER}}
    val capture=remember{ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(95)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(2560,1920),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()}
    val preview=remember{Preview.Builder().build().also{it.setSurfaceProvider(view.surfaceProvider)}}
    var provider by remember{mutableStateOf<ProcessCameraProvider?>(null)}
    var permission by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var front by remember{mutableStateOf(false)}
    var canSwitch by remember{mutableStateOf(false)}
    var camera by remember{mutableStateOf<Camera?>(null)}
    var flash by remember { mutableStateOf(PhotoFlash.Off) }
    var showFlashModes by remember { mutableStateOf(false) }
    var busy by remember{mutableStateOf(false)}
    var photo by remember{mutableStateOf<MemoryMediaSource?>(null)}
    var error by remember{mutableStateOf<Int?>(null)}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it;error=if(it)null else R.string.ui_capture_camera_permission}
    LaunchedEffect(Unit){if(!permission)request.launch(Manifest.permission.CAMERA)}
    DisposableEffect(lifecycle){
        val observer=object:androidx.lifecycle.DefaultLifecycleObserver{override fun onResume(owner:androidx.lifecycle.LifecycleOwner){permission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}}
        lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(Unit){onDispose{active.set(false)}}
    DisposableEffect(photo){val selected=photo;onDispose{selected?.close()}}
    DisposableEffect(permission,front,photo==null,lifecycle){
        var attached=true
        camera=null
        showFlashModes=false
        if(permission && photo==null){
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({if(attached && active.get())try{
                val manager=future.get();provider=manager
                val backAvailable=manager.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);val frontAvailable=manager.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                canSwitch=backAvailable && frontAvailable
                val selector=if(front && frontAvailable || !backAvailable)CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                camera=manager.bindToLifecycle(lifecycle,selector,preview,capture);error=null
            }catch(_:Exception){error=R.string.ui_capture_camera_unavailable}},ContextCompat.getMainExecutor(context))
        }
        onDispose{attached=false;provider?.unbind(preview,capture)}
    }
    val thumbnail by key(photo) {
        produceState<Bitmap?>(null) {
            val source = photo ?: return@produceState
            val owner = source.retain()
            val previewJob = coroutineContext[Job]
            try {
                withContext(NonCancellable) {
                    val bitmap = withContext(Dispatchers.IO) {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(context.contentResolver, source.uri)
                        ) { decoder, info, _ ->
                            val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize(
                                maxOf(1, (info.size.width * scale).toInt()),
                                maxOf(1, (info.size.height * scale).toInt())
                            )
                            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }
                    if (previewJob?.isActive == true) value = bitmap else bitmap.recycle()
                }
            } catch (_: Exception) {
                if (previewJob?.isActive == true) error = R.string.ui_capture_photo_prepare_failed
            } finally {
                owner.close()
            }
        }
    }
    DisposableEffect(thumbnail){val bitmap=thumbnail;onDispose{bitmap?.recycle()}}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)){
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiString(if(photo==null) R.string.ui_capture_take_photo else R.string.ui_capture_review_photo), Modifier.weight(1f), style=MaterialTheme.typography.titleLarge)
                    if (photo == null && camera?.cameraInfo?.hasFlashUnit() == true) {
                        Box {
                            IconButton(enabled = !busy, onClick = { showFlashModes = true }) {
                                Icon(flash.icon, contentDescription = uiString(R.string.ui_capture_flash_mode, uiString(flash.labelRes)))
                            }
                            DropdownMenu(expanded = showFlashModes, onDismissRequest = { showFlashModes = false }) {
                                for (option in PhotoFlash.entries) {
                                    DropdownMenuItem(text = { Text(uiString(option.labelRes)) }, leadingIcon = { Icon(option.icon, null) },
                                        onClick = { flash = option; showFlashModes = false })
                                }
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black),contentAlignment=Alignment.Center){
                    if(permission && photo==null) CameraPreview(view, camera, !busy, Modifier.fillMaxSize())
                    if(photo!=null)Box(Modifier.matchParentSize().background(Color.Black),contentAlignment=Alignment.Center){thumbnail?.let{Image(it.asImageBitmap(),contentDescription=uiString(R.string.ui_capture_photo),modifier=Modifier.fillMaxSize())}}
                    if(busy)CircularProgressIndicator()
                }
                error?.let{Text(uiString(it),color=MaterialTheme.colorScheme.error)}
                if(!permission){Text(uiString(R.string.ui_capture_camera_settings));TextButton(onClick={org.msgat.core.network.BackgroundReception.appSettings(context)}){Text(uiString(R.string.ui_capture_open_settings))}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick=onDismiss){Text(uiString(R.string.ui_cancel))}
                    if(photo==null){
                        IconButton(enabled=canSwitch && camera!=null && !busy,onClick={front=!front}){
                            Icon(Icons.Default.FlipCameraAndroid, contentDescription=uiString(R.string.ui_call_switch_camera))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(enabled=permission && camera!=null && !busy,onClick={
                            busy=true;error=null
                            try{
                                capture.targetRotation=view.display?.rotation?:android.view.Surface.ROTATION_0
                                capture.flashMode=if(camera?.cameraInfo?.hasFlashUnit()==true) flash.mode else PhotoFlash.Off.mode
                                capture.takePicture(ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageCapturedCallback(){
                                override fun onCaptureSuccess(image:ImageProxy){
                                    if(!active.get()){image.close();return}
                                    scope.launch(start=CoroutineStart.UNDISPATCHED){try{withContext(NonCancellable+Dispatchers.Default){
                                        val bytes=normalizedPhoto(image)
                                        val source=try{MemoryMediaStore.photo(context,bytes)}catch(problem:Exception){bytes.fill(0);throw problem}
                                        withContext(NonCancellable+Dispatchers.Main){if(active.get()){photo=source;busy=false}else source.close()}
                                    }}catch(_:Exception){withContext(NonCancellable+Dispatchers.Main){if(active.get()){busy=false;error=R.string.ui_capture_photo_prepare_failed}}}finally{image.close()}}
                                }
                                override fun onError(exception:ImageCaptureException){if(active.get()){busy=false;error=R.string.ui_capture_photo_failed}}
                            })}catch(_:Exception){busy=false;error=R.string.ui_capture_camera_unavailable}
                        }){Text(uiString(R.string.ui_capture_shutter))}
                    }else{
                        TextButton(onClick={photo=null;error=null}){Text(uiString(R.string.ui_capture_retake))};Spacer(Modifier.weight(1f))
                        Button(onClick={val next=photo?.retain()?:return@Button;try{onCaptured(next)}catch(_:Exception){next.close();error=R.string.ui_capture_photo_select_failed}}){Text(uiString(R.string.ui_record_continue))}
                    }
                }
            }
        }
    }
}
