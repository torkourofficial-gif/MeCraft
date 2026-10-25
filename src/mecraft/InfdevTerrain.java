package mecraft;

/**
 * Deterministic terrain sampler for the experimental Infdev world.
 *
 * Coordinates are world coordinates, so neighboring chunks can be generated
 * independently and in any order. The sampler intentionally allocates no
 * per-column objects and uses integer hashing to stay friendly to CLDC.
 */
final class InfdevTerrain {
    static final int CHUNK_SIZE = 16;
    static final int WORLD_HEIGHT = VoxelWorld.HEIGHT;
    static final int SEA_LEVEL = VoxelWorld.SEA_LEVEL;
    static final int CHUNK_VOLUME = CHUNK_SIZE * CHUNK_SIZE * WORLD_HEIGHT;

    private InfdevTerrain() { }

    /** Fills a reusable chunk buffer with Y as the contiguous axis. */
    static void generate(int seed, int chunkX, int chunkZ, byte[] blocks) {
        if (blocks == null || blocks.length < CHUNK_VOLUME) return;
        int originX = chunkX * CHUNK_SIZE;
        int originZ = chunkZ * CHUNK_SIZE;
        for (int z = 0; z < CHUNK_SIZE; z++) {
            int worldZ = originZ + z;
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int worldX = originX + x;
                int height = surfaceHeight(seed, worldX, worldZ);
                int column = (z * CHUNK_SIZE + x) * WORLD_HEIGHT;
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    blocks[column + y] = terrainAtHeight(seed, worldX, y, worldZ, height);
                }
            }
        }
    }

    static byte blockAt(int seed, int x, int y, int z) {
        if (y < 0 || y >= WORLD_HEIGHT) return Block.AIR;
        if (y == 0) return Block.BEDROCK;
        return terrainAtHeight(seed, x, y, z, surfaceHeight(seed, x, z));
    }

    private static byte terrainAtHeight(int seed, int x, int y, int z, int height) {
        if (y == 0) return Block.BEDROCK;
        byte terrain;
        if (y > height) terrain = y <= SEA_LEVEL ? Block.WATER : Block.AIR;
        else if (y == height) terrain = height <= SEA_LEVEL + 1 ? Block.SAND : Block.GRASS;
        else if (y >= height - 3) terrain = height <= SEA_LEVEL + 1 ? Block.SAND : Block.DIRT;
        else if (y < 5 && hash(seed ^ 0x5F356495, x, y, z) % 23 == 0) terrain = Block.COAL;
        else if (y < 5 && hash(seed ^ 0x4B1D2A37, x, y, z) % 61 == 0) terrain = Block.IRON;
        else terrain = Block.STONE;
        if (terrain == Block.AIR || y > height) return treeBlock(seed, x, y, z, terrain);
        return terrain;
    }

    private static byte treeBlock(int seed, int x, int y, int z, byte terrain) {
        int cellX = floorDiv(x - 4, 9);
        int cellZ = floorDiv(z - 4, 9);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int tx = (cellX + dx) * 9 + 4;
                int tz = (cellZ + dz) * 9 + 4;
                if (hash(seed ^ 0x16A5B, cellX + dx, 0, cellZ + dz) % 4 != 0) continue;
                int ty = surfaceHeight(seed, tx, tz);
                if (ty <= SEA_LEVEL + 1) continue;
                if (x == tx && z == tz && y > ty && y <= ty + 3) return Block.LOG;
                int canopy = Math.abs(x - tx) + Math.abs(z - tz);
                if (terrain == Block.AIR && canopy <= 2 && y >= ty + 2 && y <= ty + 4 &&
                    (y != ty + 4 || canopy <= 1)) return Block.LEAVES;
            }
        }
        return terrain;
    }

    static int surfaceHeight(int seed, int x, int z) {
        int broad = sample(seed, x, z, 64);
        int detail = sample(seed ^ 0x2C9277B5, x, z, 16);
        int height = 10 + broad / 3 + detail / 5;
        if (height < 3) return 3;
        if (height > WORLD_HEIGHT - 7) return WORLD_HEIGHT - 7;
        return height;
    }

    private static int sample(int seed, int x, int z, int scale) {
        int x0 = floorDiv(x, scale) * scale;
        int z0 = floorDiv(z, scale) * scale;
        int fx = (x - x0) * 256 / scale;
        int fz = (z - z0) * 256 / scale;
        int a = lattice(seed, x0, z0);
        int b = lattice(seed, x0 + scale, z0);
        int c = lattice(seed, x0, z0 + scale);
        int d = lattice(seed, x0 + scale, z0 + scale);
        int top = a + ((b - a) * fx >> 8);
        int bottom = c + ((d - c) * fx >> 8);
        return top + ((bottom - top) * fz >> 8);
    }

    private static int lattice(int seed, int x, int z) {
        return hash(seed, x, 0, z) % 9 - 4;
    }

    private static int hash(int seed, int x, int y, int z) {
        int n = seed ^ x * 73428767 ^ y * 912931 ^ z * 42317861;
        n = (n ^ (n >>> 13)) * 1274126177;
        return n ^ (n >>> 16);
    }

    private static int floorDiv(int value, int divisor) {
        int result = value / divisor;
        return value < 0 && value % divisor != 0 ? result - 1 : result;
    }
}
