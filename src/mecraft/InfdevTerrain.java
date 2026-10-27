package mecraft;

/** Deterministic, column-oriented terrain generation for streamed Infdev chunks. */
final class InfdevTerrain {
    static final int CHUNK_SIZE = 16;
    static final int WORLD_HEIGHT = VoxelWorld.HEIGHT;
    static final int SEA_LEVEL = VoxelWorld.SEA_LEVEL;
    static final int CHUNK_VOLUME = CHUNK_SIZE * CHUNK_SIZE * WORLD_HEIGHT;
    private static final int LATTICE_CACHE_SIZE = 512;
    private static final int LATTICE_CACHE_MASK = LATTICE_CACHE_SIZE - 1;
    private static final int[] latticeSeedCache = new int[LATTICE_CACHE_SIZE];
    private static final int[] latticeXCache = new int[LATTICE_CACHE_SIZE];
    private static final int[] latticeZCache = new int[LATTICE_CACHE_SIZE];
    private static final byte[] latticeValueCache = new byte[LATTICE_CACHE_SIZE];
    private static final boolean[] latticeCacheValid = new boolean[LATTICE_CACHE_SIZE];

    private static final int OCEAN = 0;
    private static final int PLAINS = 1;
    private static final int DESERT = 2;
    private static final int DARK_FOREST = 3;
    private static final int JUNGLE = 4;
    private static final int SNOWY = 5;
    private static final int REGION_SIZE = 24;

    private InfdevTerrain() { }

    /**
     * Fills a reusable chunk buffer. Heights and biome ids are calculated once
     * per column, then caves and decorations are applied in regional passes.
     */
    static void generate(int seed, int chunkX, int chunkZ, byte[] blocks,
                         int[] heights, byte[] biomes) {
        if (blocks == null || blocks.length < CHUNK_VOLUME || heights == null ||
            heights.length < CHUNK_SIZE * CHUNK_SIZE || biomes == null ||
            biomes.length < CHUNK_SIZE * CHUNK_SIZE) return;
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: calculando origen del chunk " + chunkX + "," + chunkZ);
        int originX = chunkX * CHUNK_SIZE;
        int originZ = chunkZ * CHUNK_SIZE;

        for (int z = 0; z < CHUNK_SIZE; z++) {
            VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: procesando fila z=" + z + " del chunk " + chunkX + "," + chunkZ);
            int worldZ = originZ + z;
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int worldX = originX + x;
                int column = z * CHUNK_SIZE + x;
                int biome = biomeAt(seed, worldX, worldZ);
                int height = surfaceHeight(seed, worldX, worldZ, biome);
                heights[column] = height;
                biomes[column] = (byte)biome;
                int top = biome == DESERT || biome == OCEAN ? Block.SAND :
                    biome == SNOWY ? Block.SNOW : Block.GRASS;
                int fill = biome == DESERT || biome == OCEAN ? Block.SAND : Block.DIRT;
                int at = column * WORLD_HEIGHT;
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    byte id;
                    if (y == 0) id = Block.BEDROCK;
                    else if (y > height) id = biome == OCEAN && y <= SEA_LEVEL ?
                        Block.WATER : Block.AIR;
                    else if (y == height) id = (byte)top;
                    else if (y >= height - 3) id = (byte)fill;
                    else if (y < 8 && (hash(seed ^ 0x5F356495, worldX, y, worldZ) & 31) == 0) id = Block.COAL;
                    else if (y < 5 && (hash(seed ^ 0x4B1D2A37, worldX, y, worldZ) & 95) == 0) id = Block.IRON;
                    else id = Block.STONE;
                    blocks[at + y] = id;
                }
            }
        }

        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: tallando cuevas");
        carveCaves(seed, originX, originZ, heights, blocks);
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: decorando terreno");
        decorate(seed, originX, originZ, heights, biomes, blocks);
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: chunk completado " + chunkX + "," + chunkZ);
    }

    static int surfaceHeight(int seed, int x, int z) {
        return surfaceHeight(seed, x, z, biomeAt(seed, x, z));
    }

    /** Compact Alpha-style Nether: netherrack floor, cave volume, lava sea and roof. */
    static void generateNether(int seed, int chunkX, int chunkZ, byte[] blocks,
                               int[] floorHeights) {
        if (blocks == null || blocks.length < CHUNK_VOLUME || floorHeights == null ||
            floorHeights.length < CHUNK_SIZE * CHUNK_SIZE) return;
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generateNether: calculando origen del chunk " + chunkX + "," + chunkZ);
        int originX = chunkX * CHUNK_SIZE;
        int originZ = chunkZ * CHUNK_SIZE;
        for (int z = 0; z < CHUNK_SIZE; z++) {
            VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generateNether: fila z=" + z);
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int wx = originX + x, wz = originZ + z;
                int column = z * CHUNK_SIZE + x;
                int floor = netherFloorHeight(seed, wx, wz);
                int roof = 16 + (valueNoise(seed ^ 0x3B91, wx, wz, 40) >> 5);
                if (roof > WORLD_HEIGHT - 2) roof = WORLD_HEIGHT - 2;
                floorHeights[column] = floor;
                int base = column * WORLD_HEIGHT;
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    byte block;
                    if (y == 0 || y == WORLD_HEIGHT - 1) block = Block.BEDROCK;
                    else if (y <= floor || y >= roof) block = Block.STONE;
                    else if (y <= 5) block = Block.LAVA;
                    else block = Block.AIR;
                    blocks[base + y] = block;
                }

                int surface = base + floor;
                int surfaceHash = hash(seed ^ 0x5A17, wx, floor, wz);
                if ((surfaceHash & 15) == 0) blocks[surface] = Block.SAND;
                if ((surfaceHash & 63) == 1 && floor + 2 < roof) {
                    blocks[surface + 1] = Block.STONE;
                    if ((surfaceHash & 128) != 0 && floor + 3 < roof)
                        blocks[surface + 2] = Block.STONE;
                }
                int roofHash = hash(seed ^ 0x1057, wx, roof, wz);
                if ((roofHash & 31) == 0 && roof > floor + 2) {
                    blocks[base + (roof - 1)] = Block.COAL;
                    if (x > 0) blocks[(column - 1) * WORLD_HEIGHT + roof - 1] = Block.COAL;
                    if (z > 0) blocks[(column - CHUNK_SIZE) * WORLD_HEIGHT + roof - 1] = Block.COAL;
                }
            }
        }
    }

    static int netherFloorHeight(int seed, int x, int z) {
        int broad = valueNoise(seed ^ 0x6A09, x, z, 36);
        int detail = valueNoise(seed ^ 0xBB67, x, z, 12);
        int floor = 3 + (broad * 6 >> 8) + (detail >> 6);
        if (floor > 10) floor = 10;
        return floor;
    }

    private static int biomeAt(int seed, int x, int z) {
        int temperature = valueNoise(seed ^ 211, x, z, 256);
        if (temperature < 24) return SNOWY;
        if (temperature < 64) return OCEAN;
        if (temperature < 164) return PLAINS;
        if (temperature < 215) return DESERT;
        return valueNoise(seed ^ 97, x, z, 192) < 128 ? DARK_FOREST : JUNGLE;
    }

    private static int surfaceHeight(int seed, int x, int z, int biome) {
        int n1 = valueNoise(seed ^ 11, x, z, 32) - 128;
        int n2 = valueNoise(seed ^ 37, x, z, 12) - 128;
        int n3 = valueNoise(seed ^ 73, x, z, 4) - 128;
        int variation = (n1 * 9 + n2 * 5 + n3 * 2) >> 7;
        int height;
        if (biome == OCEAN) height = 13 + (variation >> 1);
        else if (biome == SNOWY) height = 20 + variation;
        else if (biome == DESERT) height = 18 + variation;
        else if (biome == DARK_FOREST || biome == JUNGLE) height = 20 + variation;
        else height = 18 + variation;
        if (height < 11) return 11;
        if (height > WORLD_HEIGHT - 5) return WORLD_HEIGHT - 5;
        return height;
    }

    /** Smooth bilinear value noise, returning 0..255 without floating point. */
    private static int valueNoise(int seed, int x, int z, int scale) {
        int gx = floorDiv(x, scale), gz = floorDiv(z, scale);
        int fx = smooth((x - gx * scale) * 256 / scale);
        int fz = smooth((z - gz * scale) * 256 / scale);
        int a = lattice(seed, gx, gz);
        int b = lattice(seed, gx + 1, gz);
        int c = lattice(seed, gx, gz + 1);
        int d = lattice(seed, gx + 1, gz + 1);
        int top = a + ((b - a) * fx >> 8);
        int bottom = c + ((d - c) * fx >> 8);
        return top + ((bottom - top) * fz >> 8);
    }

    private static int smooth(int value) {
        return value * value * (768 - 2 * value) >> 16;
    }

    private static int lattice(int seed, int x, int z) {
        int base = seed ^ x * 73428767 ^ z * 42317861;
        int slot = base & LATTICE_CACHE_MASK;
        if (latticeCacheValid[slot] && latticeSeedCache[slot] == seed &&
            latticeXCache[slot] == x && latticeZCache[slot] == z)
            return latticeValueCache[slot] & 255;
        int mixed = (base ^ (base >>> 13)) * 1274126177;
        mixed ^= mixed >>> 16;
        int value = mixed >>> 24;
        latticeSeedCache[slot] = seed;
        latticeXCache[slot] = x;
        latticeZCache[slot] = z;
        latticeValueCache[slot] = (byte)value;
        latticeCacheValid[slot] = true;
        return value;
    }

    private static void carveCaves(int seed, int originX, int originZ, int[] heights, byte[] blocks) {
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.carveCaves: calculando regiones");
        // Each deterministic region contributes several small chambers and a
        // less frequent medium chamber. Region-based placement keeps cave shapes
        // continuous across chunk boundaries and independent of generation order.
        final int caveRegion = 24;
        int firstCellX = floorDiv(originX - 18, caveRegion);
        int lastCellX = floorDiv(originX + CHUNK_SIZE + 18, caveRegion);
        int firstCellZ = floorDiv(originZ - 18, caveRegion);
        int lastCellZ = floorDiv(originZ + CHUNK_SIZE + 18, caveRegion);
        for (int rz = firstCellZ; rz <= lastCellZ; rz++) {
            for (int rx = firstCellX; rx <= lastCellX; rx++) {
                int regionHash = hash(seed ^ 0x35A76D21, rx, 0, rz);
                int baseX = rx * caveRegion + 3 + ((regionHash >>> 4) & 15);
                int baseZ = rz * caveRegion + 3 + ((regionHash >>> 9) & 15);

                // Frequent tiny pockets: uneven, rounded, mostly underground.
                int smallCount = 3 + ((regionHash >>> 18) & 3);
                for (int c = 0; c < smallCount; c++) {
                    int h = hash(seed ^ (0x51ED270B + c * 0x1F123BB5), rx, c + 1, rz);
                    int cx = baseX + ((h >>> 3) & 15) - 7;
                    int cz = baseZ + ((h >>> 11) & 15) - 7;
                    int radius = 2 + ((h >>> 20) & 1);
                    int surface = surfaceHeight(seed, cx, cz);
                    int cy = 4 + ((h >>> 24) & 15);
                    if (cy > surface - 3) cy = Math.max(3, surface - 3 - ((h >>> 7) & 3));
                    carveCaveRoom(originX, originZ, heights, blocks, cx, cy, cz,
                                  radius, 1 + ((h >>> 27) & 1), false);
                }

                // A medium cave in most regions, occasionally joined to another
                // chamber to create a winding passage rather than isolated holes.
                if ((regionHash & 3) != 0) {
                    int h = hash(seed ^ 0x6C8E9CF5, rx, 7, rz);
                    int cx = baseX + ((h >>> 5) & 15) - 7;
                    int cz = baseZ + ((h >>> 13) & 15) - 7;
                    int surface = surfaceHeight(seed, cx, cz);
                    int cy = 5 + ((h >>> 21) & 9);
                    if (cy > surface - 4) cy = Math.max(4, surface - 4);
                    int radius = 4 + ((h >>> 25) & 2);
                    int vertical = 2 + ((h >>> 28) & 1);
                    carveCaveRoom(originX, originZ, heights, blocks, cx, cy, cz,
                                  radius, vertical, false);
                    if ((h & 7) < 5) {
                        int direction = (h >>> 8) & 3;
                        int length = 4 + ((h >>> 16) & 7);
                        for (int step = 2; step <= length; step += 2) {
                            int tx = cx + (direction == 0 ? step : direction == 1 ? -step : 0);
                            int tz = cz + (direction == 2 ? step : direction == 3 ? -step : 0);
                            int ty = cy + (((h >>> (step & 15)) & 1) == 0 ? 0 : 1);
                            carveCaveRoom(originX, originZ, heights, blocks, tx, ty, tz,
                                          2 + ((h >>> 22) & 1), 2, false);
                        }
                    }
                }

                // Rare surface entrances: a small opening and a short shaft
                // connect the surface to a chamber, but most caves stay buried.
                if ((regionHash & 7) == 2) {
                    int h = hash(seed ^ 0x2C9277B5, rx, 11, rz);
                    int cx = baseX + ((h >>> 6) & 15) - 7;
                    int cz = baseZ + ((h >>> 14) & 15) - 7;
                    int surface = surfaceHeight(seed, cx, cz);
                    int cy = Math.max(4, surface - (5 + ((h >>> 22) & 3)));
                    int radius = 3 + ((h >>> 27) & 1);
                    carveCaveRoom(originX, originZ, heights, blocks, cx, cy, cz,
                                  radius, 2, true);
                    int shaftRadius = (h & 0x100) == 0 ? 0 : 1;
                    for (int sy = cy + 1; sy < surface; sy++) {
                        for (int dz = -shaftRadius; dz <= shaftRadius; dz++) {
                            for (int dx = -shaftRadius; dx <= shaftRadius; dx++) {
                                int wx = cx + dx, wz = cz + dz;
                                if (wx < originX || wx >= originX + CHUNK_SIZE ||
                                    wz < originZ || wz >= originZ + CHUNK_SIZE) continue;
                                int col = (wz - originZ) * CHUNK_SIZE + wx - originX;
                                if (sy < heights[col] && sy > 1)
                                    blocks[col * WORLD_HEIGHT + sy] = Block.AIR;
                            }
                        }
                    }
                }
            }
        }
    }

    private static void carveCaveRoom(int originX, int originZ, int[] heights,
                                      byte[] blocks, int centerX, int centerY, int centerZ,
                                      int radius, int verticalRadius, boolean allowNearSurface) {
        int minX = Math.max(originX, centerX - radius);
        int maxX = Math.min(originX + CHUNK_SIZE - 1, centerX + radius);
        int minZ = Math.max(originZ, centerZ - radius);
        int maxZ = Math.min(originZ + CHUNK_SIZE - 1, centerZ + radius);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int dx = x - centerX, dz = z - centerZ;
                int horizontal = dx * dx + dz * dz;
                if (horizontal > radius * radius) continue;
                int column = (z - originZ) * CHUNK_SIZE + x - originX;
                int ceiling = allowNearSurface ? heights[column] - 1 : heights[column] - 3;
                int low = Math.max(2, centerY - verticalRadius);
                int high = Math.min(Math.min(centerY + verticalRadius, ceiling), WORLD_HEIGHT - 2);
                for (int y = low; y <= high; y++) {
                    int dy = y - centerY;
                    if (horizontal * verticalRadius * verticalRadius +
                        dy * dy * radius * radius >
                        radius * radius * verticalRadius * verticalRadius) continue;
                    blocks[column * WORLD_HEIGHT + y] = Block.AIR;
                }
            }
        }
    }

    private static void decorate(int seed, int originX, int originZ, int[] heights,
                                 byte[] biomes, byte[] blocks) {
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.decorate: calculando regiones de decoración");
        int firstCellX = floorDiv(originX - 2 - 4, 9);
        int lastCellX = floorDiv(originX + CHUNK_SIZE + 1 - 4, 9);
        int firstCellZ = floorDiv(originZ - 2 - 4, 9);
        int lastCellZ = floorDiv(originZ + CHUNK_SIZE + 1 - 4, 9);
        for (int rz = firstCellZ; rz <= lastCellZ; rz++) {
            for (int rx = firstCellX; rx <= lastCellX; rx++) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.decorate: revisando árbol rx=" + rx + ", rz=" + rz);
                int tx = rx * 9 + 4, tz = rz * 9 + 4;
                int biome = biomeAt(seed, tx, tz);
                if (biome == OCEAN) continue;
                int treeHash = hash(seed ^ 0x16A5B, rx, 0, rz);
                int chance = biome == JUNGLE ? 3 : biome == DARK_FOREST ? 2 : biome == PLAINS ? 7 : 0;
                if (chance == 0 || (treeHash & 15) >= chance) continue;
                int ty = surfaceHeight(seed, tx, tz, biome);
                if (ty <= SEA_LEVEL + 1 || ty + 4 >= WORLD_HEIGHT || biome == SNOWY) continue;
                for (int oy = 1; oy <= 4; oy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int wx = tx + dx, wz = tz + dz;
                            if (wx < originX || wx >= originX + CHUNK_SIZE ||
                                wz < originZ || wz >= originZ + CHUNK_SIZE) continue;
                            boolean trunk = dx == 0 && dz == 0 && oy <= 3;
                            boolean leaf = Math.abs(dx) + Math.abs(dz) <= 2 &&
                                (oy != 4 || Math.abs(dx) + Math.abs(dz) <= 1);
                            if (!trunk && !leaf) continue;
                            int column = (wz - originZ) * CHUNK_SIZE + wx - originX;
                            int at = column * WORLD_HEIGHT + ty + oy;
                            if (trunk || blocks[at] == Block.AIR) blocks[at] = trunk ? Block.LOG : Block.LEAVES;
                        }
                    }
                }
            }
        }

        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.decorate: colocando cactus y flores");
        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.decorate: columna de flora x=" + x + ", z=" + z);
                int column = z * CHUNK_SIZE + x;
                int height = heights[column];
                int biome = biomes[column] & 255;
                if (biome == OCEAN) continue;
                if (biome == DESERT) {
                    if ((hash(seed ^ 0xCA275, originX + x, height, originZ + z) & 31) == 0 &&
                        height + 2 < WORLD_HEIGHT) {
                        int cactusHeight = 1 + ((hash(seed, originX + x, 2, originZ + z) >>> 8) & 1);
                        for (int i = 1; i <= cactusHeight; i++)
                            blocks[column * WORLD_HEIGHT + height + i] = Block.CACTUS;
                    }
                    continue;
                }
                if (biome == SNOWY) continue;
                int flowerMask = biome == PLAINS ? 7 : 63;
                if ((hash(seed ^ 0x73B91, originX + x, height, originZ + z) & flowerMask) != 0 ||
                    height + 1 >= WORLD_HEIGHT) continue;
                int at = column * WORLD_HEIGHT + height + 1;
                if (blocks[at] == Block.AIR)
                    blocks[at] = (hash(seed, originX + x, 1, originZ + z) & 1) == 0 ?
                        Block.RED_FLOWER : Block.YELLOW_FLOWER;
            }
        }
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
