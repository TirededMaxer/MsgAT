// SPDX-License-Identifier: MIT
package org.msgat.console.routing;

import io.netty.channel.Channel;
import java.util.concurrent.ConcurrentHashMap;

/** Online routing only; durable ciphertext belongs to CiphertextMailboxStore. */
public final class BlindRoutingTable {
    private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,java.util.Set<Channel>> authenticatedChannels=new ConcurrentHashMap<>();
    /** Two native transfer workers plus a control socket fit well below this per-root cap. */
    public synchronized boolean track(String mailbox,Channel channel) {
        var channels=authenticatedChannels.computeIfAbsent(mailbox,key->ConcurrentHashMap.newKeySet());
        if(channels.size()>=8) return false;
        channels.add(channel);
        channel.closeFuture().addListener(done -> {
            synchronized(this) { channels.remove(channel); if(channels.isEmpty()) authenticatedChannels.remove(mailbox,channels); }
        });
        return true;
    }
    public synchronized void closeIdentity(String mailbox) {
        var channels=authenticatedChannels.remove(mailbox);
        activeChannels.remove(mailbox);
        if(channels!=null) channels.forEach(Channel::close);
    }
    public void register(String mailbox, Channel channel) {
        Channel previous = activeChannels.put(mailbox, channel);
        if (previous != null && previous != channel) previous.close();
    }
    public Channel getChannel(String mailbox) { return activeChannels.get(mailbox); }
    public void remove(String mailbox, Channel channel) { activeChannels.remove(mailbox, channel); }
    public synchronized void closeConnections() {
        authenticatedChannels.values().forEach(channels->channels.forEach(Channel::close));
        authenticatedChannels.clear(); activeChannels.clear();
    }
}
