package mecraft;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Small, CLDC 1.1-compatible wire protocol for MeCraft LAN multiplayer.
 * All TCP messages use a two-byte unsigned length followed by a compact payload.
 */
final class LanProtocol {
    static final int DISCOVERY_PORT = 27186;
    static final int GAME_PORT = 27185;
    static final int MAX_PLAYERS = 4;
    static final int MAX_PACKET_SIZE = 4096;
    static final int CODE_LENGTH = 6;
    static final int DISCOVERY_MAGIC = 0x4D434C4E;
    static final int PROTOCOL_VERSION = 1;
    static final int DISCOVERY_QUERY = 1;
    static final int DISCOVERY_RESPONSE = 2;
    static final int HELLO = 3;
    static final int ACCEPT = 2;
    static final int REJECT = 3;
    static final int PLAYER_STATE = 10;
    static final int PLAYER_LEAVE = 11;
    static final int BLOCK_CHANGE = 20;
    static final int WORLD_TIME = 21;
    static final int INVENTORY_CHANGE = 22;
    static final int ENTITY_STATE = 30;
    static final int PING = 40;
    static final int PONG = 41;
    private static final char[] DIGITS = {'0','1','2','3','4','5','6','7','8','9'};

    private LanProtocol() { }

    /** Creates a six-digit decimal room code, including leading zeroes. */
    static String createRoomCode() {
        Random random = new Random(System.currentTimeMillis());
        char[] value = new char[CODE_LENGTH];
        int i;
        for (i = 0; i < CODE_LENGTH; i++) value[i] = DIGITS[random.nextInt(10)];
        return new String(value);
    }

    static boolean isValidRoomCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        int i;
        for (i = 0; i < CODE_LENGTH; i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Writes one length-prefixed packet. The payload begins with its opcode. */
    static void writeFrame(DataOutputStream output, byte[] packet) throws IOException {
        if (packet == null || packet.length < 1 || packet.length > MAX_PACKET_SIZE)
            throw new IOException("Invalid LAN packet size");
        output.writeShort(packet.length);
        output.write(packet);
        output.flush();
    }

    /** Reads one complete frame, returning null only for a clean stream end. */
    static byte[] readFrame(DataInputStream input) throws IOException {
        int length;
        try {
            length = input.readUnsignedShort();
        } catch (java.io.EOFException end) {
            return null;
        }
        if (length < 1 || length > MAX_PACKET_SIZE)
            throw new IOException("Invalid LAN frame length");
        byte[] packet = new byte[length];
        input.readFully(packet);
        return packet;
    }
}
