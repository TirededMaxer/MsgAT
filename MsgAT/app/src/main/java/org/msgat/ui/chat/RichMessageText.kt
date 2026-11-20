// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.msgat.ui.theme.LocalAppearanceSettings
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.msgat.R
import org.msgat.core.crypto.SecurityCore
import org.msgat.ui.theme.uiString
import kotlin.math.ceil
import kotlin.math.floor

internal val MessageLastLineRight = VerticalAlignmentLine(::maxOf)
internal val MessageLastLineTop = HorizontalAlignmentLine(::maxOf)
internal val MessageLastLineBottom = HorizontalAlignmentLine(::maxOf)

internal fun resolveReplyQuote(text: String, selection: String): String? = runCatching {
    SecurityCore.call(JSONObject().put("operation", "reply_quote_selection").put("text", text)
        .put("selection", selection)).getString("preview")
}.getOrNull()

private data class QuoteRun(val block: Int, val run: Int)

private fun locateReplyQuote(text: String, preview: String): Map<QuoteRun, TextRange> = runCatching {
    val ranges = SecurityCore.call(JSONObject().put("operation", "locate_reply_quote").put("text", text)
        .put("preview", preview)).getJSONArray("ranges")
    buildMap {
        for (index in 0 until ranges.length()) {
            val range = ranges.getJSONObject(index)
            put(QuoteRun(range.getInt("block"), range.getInt("run")), TextRange(range.getInt("start"), range.getInt("end")))
        }
    }
}.getOrDefault(emptyMap())

@Composable
internal fun ReplyQuotePicker(message: MessageUIModel, canQuote: suspend () -> Boolean, onDismiss: () -> Unit, onQuote: (String, String) -> Unit) {
    var source by remember(message.id, message.content) { mutableStateOf<TextFieldValue?>(null) }
    var digest by remember(message.id, message.content) { mutableStateOf<String?>(null) }
    var failed by remember(message.id, message.content) { mutableStateOf(false) }
    var confirming by remember(message.id, message.content) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = uiString(R.string.ui_quote_selected_text)
    LaunchedEffect(message.id, message.content) {
        val document = withContext(Dispatchers.Default) {
            runCatching { SecurityCore.call(JSONObject().put("operation", "reply_quote_text").put("text", message.content)) }.getOrNull()
        }
        if (document == null) failed = true
        else {
            source = TextFieldValue(document.getString("text"))
            digest = document.getString("digest")
        }
    }
    Column(Modifier.widthIn(min = 168.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val value = source
        if (value != null) {
            BasicTextField(value, onValueChange = { source = it; failed = false }, readOnly = true,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = LocalContentColor.current,
                    fontSize = LocalAppearanceSettings.current.apply(16.sp)
                ))
        } else if (!failed) CircularProgressIndicator(Modifier.size(20.dp))
        if (failed) Text(uiString(R.string.ui_quote_selection_unavailable), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
            val range = source?.selection
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f, fill = false)) { Text(uiString(R.string.ui_cancel)) }
            TextButton(modifier = Modifier.weight(1f, fill = false), enabled = !confirming && digest != null && range != null && !range.collapsed, onClick = {
                val selected = source?.selection ?: return@TextButton
                val selection = "${selected.min}:${selected.max}:$digest"
                confirming = true
                scope.launch {
                    try {
                        val preview = if (canQuote()) withContext(Dispatchers.Default) { resolveReplyQuote(message.content, selection) } else null
                        if (preview == null) failed = true else onQuote(selection, preview)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { confirming = false }
                }
            }) { Text(uiString(R.string.ui_quote_selected_text)) }
        }
    }
}

private data class RichRun(val text:String,val style:Int,val spoiler:Int,val url:String?)
private data class QuoteFold(val run: Int, val end: Int)
private data class RichBlock(val kind:String,val language:String,val runs:List<RichRun>, val quoteFold: QuoteFold? = null)
private const val CollapsedCodeLines = 12

internal suspend fun visibleMessageCopy(text: String, placeholder: String, revealed: Set<Int> = emptySet()): String =
    withContext(Dispatchers.Default) {
        SecurityCore.call(JSONObject().put("operation","copy_message_text").put("text",text)
            .put("revealed",JSONArray(revealed)).put("placeholder",placeholder)).getString("text")
    }

internal fun putMessageOnClipboard(context: Context, text: String) {
    if (text.isEmpty()) return
    val clip = ClipData.newPlainText("MsgAT", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
}

private fun richBlocks(text:String):List<RichBlock>{
    val doc=SecurityCore.call(JSONObject().put("operation","render_message_text").put("text",text))
    check(doc.getInt("version")==1)
    val blocks=doc.getJSONArray("blocks")
    return List(blocks.length()){index->val block=blocks.getJSONObject(index);val runs=block.getJSONArray("runs")
        RichBlock(block.getString("kind"),block.getString("language"),List(runs.length()){i->val run=runs.getJSONObject(i)
            RichRun(run.getString("text"),run.getInt("style"),run.getInt("spoiler"),if(run.isNull("url"))null else run.getString("url"))},
            block.optJSONObject("quote_fold")?.let { QuoteFold(it.getInt("run"), it.getInt("utf16_end")) })}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RichMessageText(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,linksEnabled:Boolean=true,
    alignFooter:Boolean=false,quoteHighlight:String?=null,onReveal:((Set<Int>)->Unit)?=null,
    linkColor: Color = MaterialTheme.colorScheme.primary, onVisibleLinks: ((List<String>) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null){
    val textScale = LocalAppearanceSettings.current
    val hiddenText = uiString(R.string.ui_hidden_message_text)
    val formatUnavailable = uiString(R.string.ui_message_formatting_unavailable)
    val linkOpenFailed = uiString(R.string.link_preview_open_failed)
    val codeBlockLabel = uiString(R.string.ui_format_code_block)
    val copied = uiString(R.string.ui_message_copied)
    val copyFailed = uiString(R.string.ui_message_copy_failed)
    key(text){
        val blocks by produceState<List<RichBlock>?>(null, formatUnavailable) {
            value = withContext(Dispatchers.Default) {
                try { richBlocks(text) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { listOf(RichBlock("paragraph", "", listOf(RichRun(formatUnavailable, 0, 0, null)))) }
            }
        }
        val ranges by produceState(emptyMap<QuoteRun, TextRange>(), quoteHighlight) {
            value = emptyMap()
            if (quoteHighlight != null) value = withContext(Dispatchers.Default) { locateReplyQuote(text, quoteHighlight) }
        }
        var revealed by remember{mutableStateOf<Set<Int>>(emptySet())}
        var expandedCodeBlocks by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var expandedQuotes by remember { mutableStateOf<Set<Int>>(emptySet()) }
        val uriHandler=LocalUriHandler.current
        val quoteColor=MaterialTheme.colorScheme.tertiary.copy(alpha = 0.33f)
        val context=LocalContext.current
        val targets = remember(blocks, linksEnabled, revealed, expandedQuotes, ranges) {
            if (!linksEnabled) emptyList() else buildSet {
                blocks?.forEachIndexed { index, block ->
                    if (block.kind == "code") return@forEachIndexed
                    val fold = block.quoteFold?.takeUnless { index in expandedQuotes || ranges.keys.any { it.block == index } }
                    for ((position, run) in block.runs.withIndex()) {
                        if (fold != null && (position > fold.run || position == fold.run && fold.end < run.text.length)) break
                        if (run.spoiler == 0 || run.spoiler in revealed) httpLink(run.url)?.let { add(it) }
                    }
                }
            }.toList()
        }
        if (onVisibleLinks != null) {
            val notify by rememberUpdatedState(onVisibleLinks)
            LaunchedEffect(targets) { notify(targets) }
        }
        var selectedTargets by remember { mutableStateOf<List<String>>(emptyList()) }
        val activeTargets = selectedTargets.filter(targets::contains)
        if (activeTargets.isNotEmpty()) MessageLinksDialog(activeTargets) { selectedTargets = emptyList() }
        val longPress = onLongClick ?: if (targets.isNotEmpty()) ({ selectedTargets = targets }) else null
        Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
            val rendered=blocks
            if(rendered==null)Text("…",color=color)
            rendered?.forEachIndexed{index,block->
                if(block.kind=="code"){
                    val source = remember(block) { block.runs.joinToString("") { it.text } }
                    val expandable = remember(source) { source.count { it == '\n' } >= CollapsedCodeLines }
                    val showingQuote = ranges.keys.any { it.block == index }
                    val expanded = index in expandedCodeBlocks || showingQuote
                    Column(Modifier.fillMaxWidth().background(color.copy(alpha=.08f),RoundedCornerShape(6.dp)).padding(8.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(block.language.ifBlank { codeBlockLabel }, fontSize = textScale.apply(11.sp),
                                color = color.copy(alpha = .75f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(enabled = linksEnabled && source.isNotEmpty(), onClick = {
                                try {
                                    putMessageOnClipboard(context, source)
                                    if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: SecurityException) {
                                    android.widget.Toast.makeText(context, copyFailed, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(uiString(R.string.ui_copy), fontSize = textScale.apply(11.sp)) }
                        }
                        val code = buildAnnotatedString {
                            block.runs.forEachIndexed { runIndex, run ->
                                val offset = length
                                append(run.text)
                                ranges[QuoteRun(index, runIndex)]?.let { addStyle(SpanStyle(background = quoteColor), offset + it.min, offset + it.max) }
                            }
                        }
                            Text(code,fontFamily=FontFamily.Monospace,fontSize=textScale.apply(14.sp),color=color, softWrap = false,
                            maxLines = if (expanded) Int.MAX_VALUE else CollapsedCodeLines, overflow = TextOverflow.Clip,
                            modifier=Modifier.horizontalScroll(rememberScrollState()).combinedClickable(onClick={},onLongClick=longPress))
                        if (expandable && !showingQuote) {
                            TextButton(enabled = linksEnabled, onClick = {
                                expandedCodeBlocks = if (expanded) expandedCodeBlocks - index else expandedCodeBlocks + index
                            }) { Text(uiString(if (expanded) R.string.ui_collapse_code else R.string.ui_expand_code)) }
                        }
                    }
                }else{
                    val fold = block.quoteFold
                    val showingQuote = fold != null && ranges.keys.any { it.block == index }
                    val expanded = index in expandedQuotes || showingQuote
                    val collapsed = fold != null && !expanded
                    val annotated=buildAnnotatedString{
                        var previousHidden=0
                        for((runIndex,run) in block.runs.withIndex()){
                            val piece = if (collapsed && fold != null) {
                                if (runIndex > fold.run) break
                                if (runIndex == fold.run) run.text.take(fold.end) else run.text
                            } else run.text
                            if (piece.isEmpty()) continue
                            val hidden=run.spoiler!=0 && run.spoiler !in revealed
                            if(hidden && previousHidden==run.spoiler)continue
                            previousHidden=if(hidden)run.spoiler else 0
                            val displayed=if(hidden)hiddenText else piece
                            val decorations=buildList{if(run.style and 4!=0)add(TextDecoration.Underline);if(run.style and 8!=0)add(TextDecoration.LineThrough)}
                            val style=SpanStyle(color=if(hidden)color.copy(alpha=.75f)else color,
                                background=if(hidden || run.style and 16!=0)color.copy(alpha=.12f)else Color.Unspecified,
                                fontWeight=if(run.style and 1!=0 && !hidden)FontWeight.Bold else null,
                                fontStyle=if(run.style and 2!=0 && !hidden)FontStyle.Italic else null,
                                fontFamily=if(run.style and 16!=0 && !hidden)FontFamily.Monospace else null,
                                textDecoration=if(decorations.isEmpty() || hidden)null else TextDecoration.combine(decorations))
                            val offset = length
                            val target = if (linksEnabled && !hidden && piece == run.text) httpLink(run.url) else null
                            withStyle(style){
                                if(hidden && linksEnabled){
                                    withLink(LinkAnnotation.Clickable("spoiler:${run.spoiler}",null){
                                        revealed=revealed+run.spoiler
                                        onReveal?.invoke(revealed)
                                    }){append(displayed)}
                                }
                                else if(target != null){
                                    withLink(LinkAnnotation.Url(target,TextLinkStyles(SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline))){
                                        openMessageLink(context, uriHandler, target, linkOpenFailed)
                                    }){append(displayed)}
                                }else append(displayed)
                            }
                            if (run.spoiler == 0) ranges[QuoteRun(index, runIndex)]?.let {
                                addStyle(SpanStyle(background = quoteColor), offset + it.min, offset + it.max)
                            }
                        }
                    }
                    Column {
                        val style=LocalTextStyle.current.copy(fontSize=textScale.apply(16.sp))
                        val openLinkMenu: (String) -> Unit = { selectedTargets = listOf(it) }
                        if (block.kind == "quote") {
                            Column(Modifier.background(color.copy(alpha = .07f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                InteractiveMessageParagraph(annotated, style, color, longPress, openLinkMenu)
                                if (fold != null && !showingQuote) {
                                    TextButton(enabled = linksEnabled, onClick = {
                                        expandedQuotes = if (expanded) expandedQuotes - index else expandedQuotes + index
                                    }) { Text(uiString(if (expanded) R.string.ui_collapse_quote else R.string.ui_expand_quote)) }
                                }
                            }
                        } else if(alignFooter && index==rendered.lastIndex && block.kind=="paragraph") {
                            FooterAlignedParagraph(annotated, style, color, longPress, openLinkMenu)
                        } else {
                            InteractiveMessageParagraph(annotated, style, color, longPress, openLinkMenu)
                        }
                    }
                }
            }
        }
    }
}

internal fun httpLink(value: String?): String? {
    val source = value ?: return null
    val target = android.net.Uri.parse(source)
    return source.takeIf {
        target.scheme?.lowercase() in listOf("https", "http") && target.isHierarchical &&
            !target.host.isNullOrEmpty() && target.userInfo == null
    }
}

internal fun openMessageLink(context: Context, handler: UriHandler, target: String, failure: String) {
    try {
        handler.openUri(target)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: IllegalArgumentException) {
        android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        android.widget.Toast.makeText(context, failure, android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun MessageLinksDialog(targets: List<String>, dismiss: () -> Unit) {
    val context = LocalContext.current
    val handler = LocalUriHandler.current
    val openFailed = uiString(R.string.link_preview_open_failed)
    val copyFailed = uiString(R.string.ui_message_copy_failed)
    AlertDialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text(uiString(R.string.ui_message_links)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(targets, key = { it }) { target ->
                    Text(target, style = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                    Row {
                        TextButton(onClick = {
                            dismiss()
                            openMessageLink(context, handler, target, openFailed)
                        }) { Text(uiString(R.string.link_preview_open)) }
                        TextButton(onClick = {
                            dismiss()
                            try { putMessageOnClipboard(context, target) }
                            catch (_: SecurityException) {
                                android.widget.Toast.makeText(context, copyFailed, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(uiString(R.string.link_preview_copy_url)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(uiString(R.string.ui_close)) } }
    )
}

@Composable
private fun InteractiveMessageParagraph(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    onMessageMenu: (() -> Unit)?,
    onLinkMenu: (String) -> Unit
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val messageMenu by rememberUpdatedState(onMessageMenu)
    val linkMenu by rememberUpdatedState(onLinkMenu)
    val modifier = if (onMessageMenu == null) Modifier else Modifier
        .semantics { onLongClick { messageMenu?.invoke(); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val held = event.changes.firstOrNull { it.id == down.id }
                        if (held == null || !held.pressed || event.changes.count { it.pressed } != 1 ||
                            (held.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) break
                    }
                    true
                }
                if (cancelled == null) {
                    val target = layout.value?.linkAt(down.position)
                    if (target == null) messageMenu?.invoke() else linkMenu(target)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
        }
    Text(text, modifier = modifier, color = color, style = style, onTextLayout = { layout.value = it })
}

private fun TextLayoutResult.linkAt(position: Offset): String? {
    if (position.x < 0 || position.y < 0 || position.x >= size.width || position.y >= size.height) return null
    val text = layoutInput.text
    val offset = getOffsetForPosition(position)
    for (index in listOf(offset, offset - 1)) {
        if (index in 0 until text.length && getBoundingBox(index).contains(position)) {
            return text.getLinkAnnotations(index, index + 1).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Url)?.url }
        }
    }
    return null
}

@Composable
private fun FooterAlignedParagraph(text: AnnotatedString, style: TextStyle, color: Color,
                                   onMessageMenu: (() -> Unit)?, onLinkMenu: (String) -> Unit) {
    val measurer=rememberTextMeasurer()
    Layout(content={InteractiveMessageParagraph(text, style, color, onMessageMenu, onLinkMenu)}) {measurables,constraints ->
        val paragraph=measurables.single().measure(constraints)
        val measured=measurer.measure(text,style=style,constraints=constraints)
        val line=measured.lineCount-1
        val alignment:Map<AlignmentLine,Int> = if(line>=0 && measured.getLineEnd(line,visibleEnd=true)>measured.getLineStart(line) &&
            measured.getParagraphDirection(measured.getLineStart(line))==ResolvedTextDirection.Ltr) {
            mapOf(
                MessageLastLineRight to ceil(measured.getLineRight(line)).toInt(),
                MessageLastLineTop to floor(measured.getLineTop(line)).toInt(),
                MessageLastLineBottom to ceil(measured.getLineBottom(line)).toInt()
            )
        } else emptyMap()
        layout(paragraph.width,paragraph.height,alignmentLines=alignment) {paragraph.placeRelative(0,0)}
    }
}

@Composable
internal fun MessageSummaryText(text:String,modifier:Modifier=Modifier,style:TextStyle=LocalTextStyle.current,color:Color=Color.Unspecified,maxLines:Int=Int.MAX_VALUE,fontSize:androidx.compose.ui.unit.TextUnit=androidx.compose.ui.unit.TextUnit.Unspecified,overflow:androidx.compose.ui.text.style.TextOverflow=androidx.compose.ui.text.style.TextOverflow.Clip){
    val hiddenLabel = uiString(R.string.ui_hidden_message_text)
    var renderedSource by remember{mutableStateOf<String?>(null)}
    var rendered by remember{mutableStateOf("")}
    LaunchedEffect(text,hiddenLabel){
        val value=withContext(Dispatchers.Default){try{SecurityCore.call(JSONObject().put("operation","summarize_message_text").put("text",text).put("limit",240).put("hidden_label",hiddenLabel)).getString("text")}catch(_:Exception){"Message"}}
        rendered=value;renderedSource=text
    }
    Text(if(renderedSource==text)rendered else " ",modifier=modifier,style=style,color=color,maxLines=maxLines,fontSize=fontSize,overflow=overflow)
}


private val textFormats = listOf(
    R.string.ui_format_bold to "bold",
    R.string.ui_format_italic to "italic",
    R.string.ui_format_underline to "underline",
    R.string.ui_format_strike to "strike",
    R.string.ui_format_code to "code",
    R.string.ui_format_spoiler to "spoiler",
    R.string.ui_format_quote to "quote",
    R.string.ui_format_code_block to "code_block"
)
internal fun formatEditorSelection(state:androidx.compose.foundation.text.input.TextFieldState,kind:String):Boolean {
    if(state.composition!=null)return false
    val result=SecurityCore.call(JSONObject().put("operation","format_message_selection").put("text",state.text.toString()).put("start",state.selection.start).put("end",state.selection.end).put("kind",kind))
    state.edit {replace(0,length,result.getString("text"));selection=TextRange(result.getInt("start"),result.getInt("end"))}
    return true
}
internal fun Modifier.composerFormatKeys(state:androidx.compose.foundation.text.input.TextFieldState,enabled:Boolean,onError:()->Unit)=onPreviewKeyEvent{event->
    if(!enabled || state.composition!=null || event.type!=KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed || event.isMetaPressed)return@onPreviewKeyEvent false
    val format=if(event.isShiftPressed)when(event.key){Key.X->"strike";Key.M->"code";Key.P->"spoiler";Key.Nine->"quote";else->null}
        else when(event.key){Key.B->"bold";Key.I->"italic";Key.U->"underline";else->null}
    if(format==null)return@onPreviewKeyEvent false
    try{formatEditorSelection(state,format)}catch(_:Exception){onError()}
    true
}
@Composable
internal fun ComposerFormatButton(state:androidx.compose.foundation.text.input.TextFieldState,enabled:Boolean){
    val context=LocalContext.current
    val formatError = uiString(R.string.ui_format_unavailable)
    var expanded by remember{mutableStateOf(false)}
    val canFormat = enabled && state.composition == null
    LaunchedEffect(canFormat) { if (!canFormat) expanded = false }
    Box {
        val formattingTitle = uiString(R.string.ui_formatting)
        IconButton(enabled = canFormat, onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = formattingTitle }) {
            Text("Aa", Modifier.clearAndSetSemantics { })
        }
        DropdownMenu(expanded && canFormat,onDismissRequest={expanded=false}){
            for((label,kind)in textFormats)DropdownMenuItem(text={Text(uiString(label))},onClick={
                expanded=false
                try{formatEditorSelection(state,kind)}catch(_:Exception){android.widget.Toast.makeText(context,formatError,android.widget.Toast.LENGTH_SHORT).show()}
            })
        }
    }
}
