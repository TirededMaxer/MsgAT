// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.msgat.console.db.AttachmentStore;
import org.msgat.console.model.GhostPacket;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** Dedicated, bounded transfer executor; never performs file I/O on a network event loop. */
final class AttachmentHandler extends SimpleChannelInboundHandler<GhostPacket> {
    private final AttachmentStore store;
    private long windowStart = System.nanoTime();
    private int requests;

    AttachmentHandler(AttachmentStore store) { this.store=store; }

    @Override protected void channelRead0(ChannelHandlerContext context,GhostPacket packet) {
        try { handlePacket(context,packet); }
        finally { ApplicationQueueGuard.completed(context.channel()); }
    }

    private void handlePacket(ChannelHandlerContext context,GhostPacket packet) {
        String owner=context.channel().attr(AttributeKey.<String>valueOf("AUTHENTICATED_UUID")).get();
        if(owner==null || !Boolean.TRUE.equals(context.channel().attr(AttributeKey.<Boolean>valueOf("TRANSFER_CHANNEL")).get())) {
            context.close(); return;
        }
        if(System.nanoTime()-windowStart>=1_000_000_000L) { windowStart=System.nanoTime(); requests=0; }
        if(++requests>512) { context.close(); return; }
        String id=packet.getTargetMailboxId();
        try(var active=org.msgat.console.db.IdentityRevocations.active(owner)) {
            if(packet.getNonceOrIv()==null || packet.getNonceOrIv().length!=16) throw new IllegalArgumentException();
            AttachmentStore.Status status;
            switch(packet.getType()) {
                case GhostPacket.TYPE_OBJECT_BEGIN -> {
                    byte[] data=packet.getEncryptedData();
                    if(data==null || data.length>1024) throw new IllegalArgumentException();
                    JsonObject request=BoundedJson.parse(data);
                    BoundedJson.fields(request,"cipher_size","cipher_sha256","read_token_hash");
                    status=store.begin(owner,id,BoundedJson.integer(request,"cipher_size",1,3L*1024*1024*1024),
                            BoundedJson.string(request,"cipher_sha256",64),BoundedJson.string(request,"read_token_hash",64));
                }
                case GhostPacket.TYPE_OBJECT_PUT -> status=store.put(owner,id,index(packet),packet.getEncryptedData());
                case GhostPacket.TYPE_OBJECT_FINISH -> {
                    empty(packet); status=store.finish(owner,id);
                }
                case GhostPacket.TYPE_OBJECT_GET -> {
                    byte[] ciphertext=store.get(id,index(packet),packet.getEncryptedData());
                    reply(context,packet,GhostPacket.TYPE_OBJECT_DATA,ciphertext); return;
                }
                case GhostPacket.TYPE_OBJECT_DELETE -> {
                    empty(packet); store.delete(owner,id);
                    reply(context,packet,GhostPacket.TYPE_OBJECT_DELETED,new byte[0]); return;
                }
                default -> { context.close(); return; }
            }
            JsonObject response=new JsonObject();
            response.addProperty("next_chunk",status.nextChunk()); response.addProperty("complete",status.complete());
            response.addProperty("expires_at",status.expiresAt());
            reply(context,packet,GhostPacket.TYPE_OBJECT_STATUS,response.toString().getBytes(StandardCharsets.UTF_8));
        } catch (AttachmentStore.DownloadUnavailable unavailable) {
            // A missing object and a rejected capability use the same response.
            reply(context,packet,GhostPacket.TYPE_OBJECT_ERROR,"{\"error\":\"object_unavailable\"}".getBytes(StandardCharsets.US_ASCII));
        } catch(IllegalArgumentException | IllegalStateException | SQLException | IOException error) {
            reply(context,packet,GhostPacket.TYPE_OBJECT_ERROR,"{\"error\":\"unavailable\"}".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static long index(GhostPacket packet) {
        long index=ByteBuffer.wrap(packet.getNonceOrIv()).getLong();
        if(index<0 || index>=32768) throw new IllegalArgumentException();
        return index;
    }
    private static void empty(GhostPacket packet) { if(packet.getPayloadLen()!=0) throw new IllegalArgumentException(); }
    private static void reply(ChannelHandlerContext context,GhostPacket request,byte type,byte[] data) {
        PacketWriter.write(context.channel(),new GhostPacket(type,request.getTargetMailboxId(),request.getNonceOrIv(),data,new byte[16]),true);
    }
    @Override public void exceptionCaught(ChannelHandlerContext context,Throwable cause) { context.close(); }
}
