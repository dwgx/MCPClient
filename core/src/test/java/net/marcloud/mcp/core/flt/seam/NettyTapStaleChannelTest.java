package net.marcloud.mcp.core.flt.seam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.marcloud.mcp.core.ke.event.EventBus;
import org.junit.Test;

/**
 * S2 teeth: {@code isHandlerInstalled} must report against the CURRENTLY LIVE channel, not a
 * stale one. The bug: {@code NettyTap} read {@code trackedChannel}, refreshed only inside
 * {@code acquireChannel()} (install path), never on a status check. After a server reconnect
 * without a re-install, {@code trackedChannel} still pointed at the old, now-closed channel whose
 * pipeline could still carry the handler, so {@code isNettyTapInstalled()} lied {@code true} while
 * the live channel had no tap.
 *
 * <p>The full no-arg path (GameAccess -&gt; NetworkManager -&gt; private {@code channel} reflection)
 * only resolves against a running game and cannot be exercised headlessly. These teeth instead
 * pin the comparison core {@link NettyTap#isHandlerInstalled(Channel, String)} — which the fixed
 * no-arg method feeds the LIVE channel. The pre-fix logic ({@code channel.pipeline().get(name) !=
 * null}, no liveness check, keyed on the stored stale field) fails the closed-channel case below.
 */
public class NettyTapStaleChannelTest {

    private static final String TAP = "mcp_packet_tap";

    private static NettyTap tap() {
        // GameAccess is only used by the live no-arg path; the comparison-core overload
        // takes an explicit channel, so a null game is fine here.
        return new NettyTap(null, new EventBus());
    }

    /**
     * A Channel whose {@code isOpen()} we control while {@code pipeline()} delegates to a real
     * channel that DOES carry the handler — models the real stale-reconnect state (a closed
     * channel whose pipeline still holds the tap), which a plain {@link EmbeddedChannel} cannot
     * reproduce because {@code close()} also tears down the pipeline.
     */
    private static Channel fakeChannel(boolean open, Channel pipelineSource) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isOpen":
                    return open;
                case "pipeline":
                    return pipelineSource.pipeline();
                case "toString":
                    return "FakeChannel(open=" + open + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Channel) Proxy.newProxyInstance(
                NettyTapStaleChannelTest.class.getClassLoader(),
                new Class<?>[]{Channel.class}, h);
    }

    @Test
    public void closedChannelWithHandlerStillPresentReportsFalse() {
        // A real channel that carries the tap, wrapped as CLOSED (isOpen()==false). This is the
        // stale-reconnect state. Pre-fix code (pipeline().get(name) != null, no open check) would
        // return TRUE; the fix's isOpen() guard makes it honestly FALSE.
        EmbeddedChannel backing = new EmbeddedChannel();
        backing.pipeline().addLast(TAP, new ChannelInboundHandlerAdapter());
        assertTrue("sanity: the backing pipeline really holds the tap",
                backing.pipeline().get(TAP) != null);

        Channel stale = fakeChannel(false, backing); // closed, but pipeline still has the tap
        assertFalse("a closed (stale) channel must report the tap as NOT installed",
                tap().isHandlerInstalled(stale, TAP));

        backing.finishAndReleaseAll();
    }

    @Test
    public void channelSwapAfterReconnectReportsAgainstLiveChannel() {
        // Reconnect: the stale channel (old) still carries the tap; the NEW live channel does not.
        // Keyed on the LIVE channel (what the fixed no-arg method acquires), the answer is FALSE —
        // the honest state. Keyed on the stale channel it is (still) present, which is exactly the
        // stored-field the pre-fix code trusted.
        EmbeddedChannel oldChannel = new EmbeddedChannel();
        oldChannel.pipeline().addLast(TAP, new ChannelInboundHandlerAdapter());
        EmbeddedChannel liveChannel = new EmbeddedChannel(); // fresh post-reconnect, no tap

        NettyTap t = tap();
        assertTrue("the stale channel does still carry the tap (the trap)",
                t.isHandlerInstalled(oldChannel, TAP));
        assertFalse("keyed on the LIVE channel, the tap is honestly absent after reconnect",
                t.isHandlerInstalled(liveChannel, TAP));

        oldChannel.finishAndReleaseAll();
        liveChannel.finishAndReleaseAll();
    }

    @Test
    public void openLiveChannelWithHandlerReportsTrue() {
        // Positive control: an OPEN live channel that carries the tap reports true.
        EmbeddedChannel live = new EmbeddedChannel();
        live.pipeline().addLast(TAP, new ChannelInboundHandlerAdapter());
        assertTrue("an open live channel carrying the tap reports installed",
                tap().isHandlerInstalled(live, TAP));
        live.finishAndReleaseAll();
    }

    @Test
    public void nullChannelReportsFalseAndNeverThrows() {
        assertFalse("no live channel (headless / not connected) -> not installed",
                tap().isHandlerInstalled((Channel) null, TAP));
    }
}
