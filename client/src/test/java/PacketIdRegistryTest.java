import static org.junit.Assert.assertEquals;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.login.server.S02PacketLoginSuccess;
import net.minecraft.network.login.client.C00PacketLoginStart;
import org.junit.Test;

/**
 * Locks the packet-id registry (EnumConnectionState). Packet IDs are defined by
 * registration ORDER into Guava BiMaps; a Guava upgrade that changed BiMap
 * iteration/registration behavior would silently renumber packets and break
 * every vanilla server connection. This test asserts known 1.8.9 wire IDs both
 * directions (id -> class and class -> id).
 */
public class PacketIdRegistryTest {

    @Test
    public void playClientboundIds() throws Exception {
        assertId(EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 0x00, S00PacketKeepAlive.class);
        assertId(EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 0x01, S01PacketJoinGame.class);
        assertId(EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 0x02, S02PacketChat.class);
    }

    @Test
    public void loginIds() throws Exception {
        assertId(EnumConnectionState.LOGIN, EnumPacketDirection.CLIENTBOUND, 0x02, S02PacketLoginSuccess.class);
        assertId(EnumConnectionState.LOGIN, EnumPacketDirection.SERVERBOUND, 0x00, C00PacketLoginStart.class);
    }

    private void assertId(EnumConnectionState state, EnumPacketDirection dir, int id,
                          Class<? extends Packet> expected) throws Exception {
        // id -> class
        Packet<?> byId = state.getPacket(dir, id);
        assertEquals("id 0x" + Integer.toHexString(id) + " -> class", expected, byId.getClass());
        // class -> id
        assertEquals("class -> id", Integer.valueOf(id), state.getPacketId(dir, byId));
    }
}
