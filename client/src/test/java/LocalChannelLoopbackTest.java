import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.SocketAddress;

import io.netty.channel.local.LocalAddress;
import net.minecraft.network.NetworkSystem;
import org.junit.Test;

/**
 * Guards the single-player transport. Launching an integrated world calls
 * {@link NetworkSystem#addLocalEndpoint()} to bind the in-memory server channel;
 * this drives that exact method with no world, no GL and no assets, so CI runs it
 * even on a runner that skips the asset-dependent smoke test.
 *
 * Netty 4.2 routes channel registration through an IoHandler that only accepts
 * matching channel types. Vanilla bound its LocalServerChannel on the NIO event
 * loop group, which 4.0 tolerated and 4.2 rejects with "IoHandle of type
 * LocalServerChannel$LocalServerUnsafe not supported" — so after the 4.1 -> 4.2
 * upgrade every world load died here, with the golden protocol tests still green.
 */
public class LocalChannelLoopbackTest {

    @Test
    public void addLocalEndpointBindsAnInMemoryServerChannel() {
        NetworkSystem system = new NetworkSystem(null);

        try {
            SocketAddress endpoint = system.addLocalEndpoint();

            assertNotNull("addLocalEndpoint must return the bound address", endpoint);
            assertTrue("single-player endpoint must be an in-memory LocalAddress, was "
                + endpoint.getClass().getName(), endpoint instanceof LocalAddress);
        } finally {
            system.terminateEndpoints();
        }
    }
}
