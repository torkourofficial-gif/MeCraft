package mecraft;

/**
 * Small-world port of the 1.2.5 stronghold layout rules.
 *
 * The original MapGenStronghold builds a weighted graph of corridors, turns,
 * crossings, rooms and a portal room. This bounded J2ME world keeps the same
 * recognizable component vocabulary and portal-room frame, but uses a compact
 * deterministic layout so generation stays cheap and cannot cross world edges.
 */
final class StrongholdGenerator {
    private static final byte FLOOR = Block.STONE_SLAB;
    private static final byte WALL = Block.MOSSY_COBBLESTONE;
    private static final int ROOM_HEIGHT = 5;

    private StrongholdGenerator() { }

    static void generate(int seed, byte[] blocks, byte[] metadata,
                         int width, int height, int depth) {
        if (blocks == null || metadata == null || width < 43 || depth < 43 || height < 24)
            return;

        // The 48x48 bounded world cannot use vanilla's 32-chunk ring placement.
        // Place one deterministic stronghold off the center, like a nearby
        // MapGenStronghold candidate, and keep the complete graph inside bounds.
        int minX = (width - 43) / 2;
        int minZ = (depth - 43) / 2;
        int y = 12;
        if (y + ROOM_HEIGHT + 2 >= height) y = height - ROOM_HEIGHT - 3;
        if (y < 4) return;

        // Start stairs/entry, straight corridors, crossing rooms and dead ends.
        room(blocks, width, depth, height, minX + 5, minZ + 5, minX + 11, minZ + 11, y);
        corridorX(blocks, width, depth, height, minX + 11, minX + 18, minZ + 8, y);
        room(blocks, width, depth, height, minX + 18, minZ + 5, minX + 24, minZ + 13, y);
        corridorZ(blocks, width, depth, height, minX + 21, minZ + 13, minZ + 21, y);
        room(blocks, width, depth, height, minX + 18, minZ + 21, minX + 28, minZ + 27, y);
        corridorX(blocks, width, depth, height, minX + 28, minX + 31, minZ + 24, y);

        // Side branch and a short library-like chamber, made from the blocks
        // available in MeCraft rather than introducing unsupported brick IDs.
        corridorZ(blocks, width, depth, height, minX + 8, minZ + 11, minZ + 15, y);
        room(blocks, width, depth, height, minX + 5, minZ + 15, minX + 13, minZ + 21, y);
        corridorZ(blocks, width, depth, height, minX + 21, minZ + 5, minZ + 2, y);
        room(blocks, width, depth, height, minX + 17, minZ + 0, minX + 25, minZ + 5, y);

        portalRoom(blocks, metadata, width, depth, height, minX + 31, minZ + 16, y, seed);

        // Treasure chests in side chambers, away from the portal room/corridors.
        put(blocks, width, depth, height, minX + 7, y + 1, minZ + 18, Block.CHEST);
        put(blocks, width, depth, height, minX + 25, y + 1, minZ + 24, Block.CHEST);
        put(blocks, width, depth, height, minX + 21, y + 1, minZ + 2, Block.CHEST);
    }

    static boolean isLootChest(int seed, int x, int y, int z, int width, int depth) {
        int minX = (width - 43) / 2;
        int minZ = (depth - 43) / 2;
        return y == 13 &&
                ((x == minX + 7 && z == minZ + 18) ||
                 (x == minX + 25 && z == minZ + 24) ||
                 (x == minX + 21 && z == minZ + 2));
    }

    private static int index(int x, int y, int z, int width, int depth) {
        return (y * depth + z) * width + x;
    }

    private static void put(byte[] blocks, int width, int depth, int height,
                            int x, int y, int z, byte id) {
        if (x < 0 || x >= width || z < 0 || z >= depth || y < 0 || y >= height) return;
        blocks[index(x, y, z, width, depth)] = id;
    }

    private static void room(byte[] blocks, int width, int depth, int height,
                             int x0, int z0, int x1, int z1, int y) {
        room(blocks, width, depth, height, x0, z0, x1, z1, y, ROOM_HEIGHT);
    }

    private static void room(byte[] blocks, int width, int depth, int height,
                             int x0, int z0, int x1, int z1, int y, int roomHeight) {
        if (x0 < 1 || z0 < 1 || x1 >= width - 1 || z1 >= depth - 1) return;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                put(blocks, width, depth, height, x, y, z, FLOOR);
                put(blocks, width, depth, height, x, y + roomHeight, z, FLOOR);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                for (int dy = 1; dy < roomHeight; dy++)
                    put(blocks, width, depth, height, x, y + dy, z, edge ? WALL : Block.AIR);
            }
        }
    }

    private static void corridorX(byte[] blocks, int width, int depth, int height,
                                  int x0, int x1, int centerZ, int y) {
        if (x0 < 1 || x1 >= width - 1 || centerZ < 2 || centerZ >= depth - 2) return;
        for (int x = x0; x <= x1; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                int z = centerZ + dz;
                put(blocks, width, depth, height, x, y, z, FLOOR);
                put(blocks, width, depth, height, x, y + ROOM_HEIGHT, z, FLOOR);
                for (int dy = 1; dy < ROOM_HEIGHT; dy++)
                    put(blocks, width, depth, height, x, y + dy, z,
                            dz == -1 || dz == 1 ? WALL : Block.AIR);
            }
        }
    }

    private static void corridorZ(byte[] blocks, int width, int depth, int height,
                                  int centerX, int z0, int z1, int y) {
        int low = z0 < z1 ? z0 : z1;
        int high = z0 > z1 ? z0 : z1;
        if (centerX < 2 || centerX >= width - 2 || low < 1 || high >= depth - 1) return;
        for (int z = low; z <= high; z++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = centerX + dx;
                put(blocks, width, depth, height, x, y, z, FLOOR);
                put(blocks, width, depth, height, x, y + ROOM_HEIGHT, z, FLOOR);
                for (int dy = 1; dy < ROOM_HEIGHT; dy++)
                    put(blocks, width, depth, height, x, y + dy, z,
                            dx == -1 || dx == 1 ? WALL : Block.AIR);
            }
        }
    }

    private static void portalRoom(byte[] blocks, byte[] metadata,
                                   int width, int depth, int height,
                                   int x0, int z0, int y, int seed) {
        // Leave five clear blocks above the portal ring instead of crowding it.
        room(blocks, width, depth, height, x0, z0, x0 + 10, z0 + 15, y, 8);

        // Classic 1.2.5 portal-room opening on the west wall.
        for (int z = z0 + 8; z <= z0 + 11; z++)
            for (int dy = 1; dy <= 3; dy++)
                put(blocks, width, depth, height, x0, y + dy, z, Block.AIR);

        // Reproduce the stepped approach to the portal dais from the 1.2.5
        // portal-room component.
        for (int z = z0 + 5; z <= z0 + 7; z++)
            for (int x = x0 + 4; x <= x0 + 6; x++)
                put(blocks, width, depth, height, x, y + 1, z, FLOOR);
        for (int z = z0 + 6; z <= z0 + 7; z++)
            for (int x = x0 + 4; x <= x0 + 6; x++)
                put(blocks, width, depth, height, x, y + 2, z, FLOOR);
        for (int x = x0 + 4; x <= x0 + 6; x++)
            put(blocks, width, depth, height, x, y + 3, z0 + 7, FLOOR);

        int frameY = y + 3;
        int eyeCount = (int)((mix(seed ^ 0x5F3759DF) & 0x7FFFFFFFFFFFFFFFL) % 3L);
        int[] frameX = new int[12];
        int[] frameZ = new int[12];
        int[] facing = new int[12];
        int n = 0;

        // Same 3x3 opening and 12 inward-facing frames as the vanilla piece.
        for (int x = 4; x <= 6; x++) {
            frameX[n] = x0 + x; frameZ[n] = z0 + 8; facing[n++] = 0;
        }
        for (int x = 4; x <= 6; x++) {
            frameX[n] = x0 + x; frameZ[n] = z0 + 12; facing[n++] = 2;
        }
        for (int z = 9; z <= 11; z++) {
            frameX[n] = x0 + 3; frameZ[n] = z0 + z; facing[n++] = 3;
        }
        for (int z = 9; z <= 11; z++) {
            frameX[n] = x0 + 7; frameZ[n] = z0 + z; facing[n++] = 1;
        }

        int state = (int)mix(seed ^ (x0 * 73428767) ^ (z0 * 912931));
        for (int i = 0; i < 12; i++) {
            int at = index(frameX[i], frameY, frameZ[i], width, depth);
            blocks[at] = Block.END_PORTAL_FRAME;
            metadata[at] = (byte)facing[i];
        }

        // Choose distinct frame positions; total eyes is deliberately 0, 1 or 2.
        for (int i = 0; i < eyeCount; i++) {
            state = state * 1664525 + 1013904223;
            int selected = (state & 0x7FFFFFFF) % 12;
            while ((metadata[index(frameX[selected], frameY, frameZ[selected], width, depth)] & 4) != 0)
                selected = (selected + 1) % 12;
            int at = index(frameX[selected], frameY, frameZ[selected], width, depth);
            metadata[at] = (byte)(metadata[at] | 4);
        }

        // This compact stronghold only places up to two eyes, so the 3x3 aperture stays inactive.
        for (int z = z0 + 9; z <= z0 + 11; z++)
            for (int x = x0 + 4; x <= x0 + 6; x++)
                put(blocks, width, depth, height, x, frameY, z, Block.AIR);
    }

    private static long mix(int value) {
        long x = value & 0xFFFFFFFFL;
        x ^= x >>> 16;
        x *= 0x7feb352dL;
        x ^= x >>> 15;
        x *= 0x846ca68bL;
        x ^= x >>> 16;
        return x;
    }
}
