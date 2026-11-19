// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.ReadTimeoutHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.media.ProfileImages
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

/** Own only the selected composer's RAM bytes. Persistence belongs to the message transaction. */
data class WebPreviewDraft(val url: String,val title: String,val summary: String,val site: String,val image: ByteArray?,
                           val aboveText: Boolean = false, val compactImage: Boolean = false) {
    fun clearImage() {image?.fill(0)}
    internal fun body(image: JSONObject?)=JSONObject().put("url",url).put("title",title).put("summary",summary).put("site",site).put("image",image?:JSONObject.NULL)
        .apply {
            if (aboveText) put("above_text", true)
            if (compactImage && image != null) put("compact_image", true)
        }
}
internal class WebPreviewFetcher: AutoCloseable {
    private val workers=lazy {NioEventLoopGroup(1)}
    private val group by workers
    private val slots=Semaphore(2)
    @Volatile private var closed=false
    private data class Response(val url: URI,val status: Int,val type: String,val location: String?,val body: ByteArray)
    fun candidates(text: String): List<String> {
        val urls = SecurityCore.call(JSONObject().put("operation","visible_message_urls").put("text",text)).getJSONArray("urls")
        return List(urls.length()) { urls.getString(it) }
    }

    suspend fun fetchUrl(candidate: String): WebPreviewDraft?=withContext(Dispatchers.IO) {
        try {withTimeout(12000) {slots.withPermit {
            check(!closed)
            val page=get(URI(candidate),1024*1024)
            val metadata=try {
                if(page.type !in setOf("text/html","application/xhtml+xml"))return@withPermit null
                SecurityCore.call(JSONObject().put("operation","inspect_web_page").put("url",candidate).put("html",page.body.toString(Charsets.UTF_8)))
            } finally {page.body.fill(0)}
            var image: ByteArray?=null
            try {
                val source=metadata.optString("image_url")
                if(source.isNotBlank()) {
                    val cover=get(page.url.resolve(source),2*1024*1024)
                    try {if(cover.type in setOf("image/jpeg","image/png","image/webp"))image=ProfileImages.jpeg(cover.body)}
                    finally {cover.body.fill(0)}
                }
            } catch(cancelled: CancellationException){throw cancelled}
            catch(_: Exception){/* A missing cover does not discard valid page metadata. */}
            try {currentCoroutineContext().ensureActive()}
            catch(cancelled: CancellationException){image?.fill(0);throw cancelled}
            WebPreviewDraft(candidate,metadata.getString("title"),metadata.getString("summary"),metadata.getString("site"),image)
        }}}
        catch(cancelled: TimeoutCancellationException){null}
        catch(cancelled: CancellationException){throw cancelled}
        catch(_: Exception){null}
    }
    private suspend fun get(initial: URI,maximum: Int): Response {
        var current=initial
        for(hop in 0..3) {
            currentCoroutineContext().ensureActive();check(!closed)
            val target=SecurityCore.call(JSONObject().put("operation","web_preview_target").put("url",current.toASCIIString()))
            val host=target.getString("host");val port=target.getInt("port")
            val addresses=InetAddress.getAllByName(host).distinctBy {it.hostAddress}.sortedBy {if(it is Inet4Address)0 else 1}
            SecurityCore.call(JSONObject().put("operation","web_preview_policy").put("url",current.toASCIIString()).put("addresses",JSONArray(addresses.map {it.hostAddress})))
            var response: Response?=null
            for(address in addresses) {
                try {response=request(current,host,port,address,target.getBoolean("secure"),maximum);break}
                catch(cancelled: CancellationException){throw cancelled}
                catch(_: Exception){currentCoroutineContext().ensureActive()}
            }
            val result=checkNotNull(response){"Preview destination is unreachable"}
            if(result.status in setOf(301,302,303,307,308)) {
                result.body.fill(0)
                check(hop<3 && result.location!=null){"Preview redirect limit reached"}
                val next=current.resolve(result.location)
                check(!current.scheme.equals("https",ignoreCase=true) || next.scheme.equals("https",ignoreCase=true)){"Preview TLS downgrade is forbidden"}
                current=next;continue
            }
            if(result.status !in 200..299) {result.body.fill(0);error("Preview page is unavailable")}
            return result
        }
        error("Preview redirect limit reached")
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun request(url: URI,host: String,port: Int,address: InetAddress,secure: Boolean,maximum: Int): Response=suspendCancellableCoroutine {continuation ->
        val bootstrap=Bootstrap().group(group).channel(NioSocketChannel::class.java).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,3000)
            .handler(object: ChannelInitializer<SocketChannel>() {
                override fun initChannel(channel: SocketChannel) {
                    if(secure) {
                        val engine=SSLContext.getDefault().createSSLEngine(host,port).apply {
                            useClientMode=true
                            sslParameters=sslParameters.apply {endpointIdentificationAlgorithm="HTTPS";if(!host.contains(':') && host.any {it.isLetter()})serverNames=listOf(SNIHostName(host))}
                        }
                        channel.pipeline().addLast(SslHandler(engine).apply {handshakeTimeoutMillis=5000})
                    }
                    channel.pipeline().addLast(HttpClientCodec(4096,32768,8192),HttpObjectAggregator(maximum),ReadTimeoutHandler(5),object: SimpleChannelInboundHandler<FullHttpResponse>() {
                        override fun channelRead0(ctx: ChannelHandlerContext,message: FullHttpResponse) {
                            if(!continuation.isActive){ctx.close();return}
                            val encoded=message.headers().get(HttpHeaderNames.CONTENT_ENCODING)
                            if(encoded!=null && !encoded.equals("identity",true)){exceptionCaught(ctx,IllegalStateException("Unsupported preview encoding"));return}
                            val bytes=ByteArray(message.content().readableBytes());message.content().readBytes(bytes)
                            val response=Response(url,message.status().code(),message.headers().get(HttpHeaderNames.CONTENT_TYPE,"").substringBefore(';').trim().lowercase(),message.headers().get(HttpHeaderNames.LOCATION),bytes)
                            continuation.resume(response,onCancellation={bytes.fill(0)})
                            ctx.close()
                        }
                        override fun exceptionCaught(ctx: ChannelHandlerContext,cause: Throwable) {
                            if(continuation.isActive)continuation.resumeWith(Result.failure(java.io.IOException("Preview request failed")))
                            ctx.close()
                        }
                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            if(continuation.isActive)continuation.resumeWith(Result.failure(java.io.IOException("Preview request closed")))
                        }
                    })
                }
            })
        val connect=bootstrap.connect(InetSocketAddress(address,port))
        continuation.invokeOnCancellation {connect.cancel(false);connect.channel().close()}
        connect.addListener {future ->
            if(!future.isSuccess) {
                if(continuation.isActive)continuation.resumeWith(Result.failure(java.io.IOException("Preview connection failed")))
            } else {
                val path=url.rawPath?.takeIf {it.isNotEmpty()}?:"/"
                val request=DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,path+(url.rawQuery?.let {"?$it"}?:""))
                request.headers().set(HttpHeaderNames.HOST,url.rawAuthority).set(HttpHeaderNames.USER_AGENT,"MsgAT-LinkPreview/2.0").set(HttpHeaderNames.ACCEPT_ENCODING,"identity").set(HttpHeaderNames.CONNECTION,"close")
                connect.channel().writeAndFlush(request).addListener {written ->if(!written.isSuccess && continuation.isActive)continuation.resumeWith(Result.failure(java.io.IOException("Preview request failed")))}
            }
        }
    }
    override fun close() {closed=true;if(workers.isInitialized())group.shutdownGracefully(0,1,java.util.concurrent.TimeUnit.SECONDS)}
}
