package mecraft;

/** Deterministic, column-oriented terrain generation for streamed Infdev chunks. */
final class InfdevTerrain {
    static final int END_ISLAND_MIN_Y = 18;
    static final int END_ISLAND_MAX_Y = 35;
    static final int END_RENDER_MAX_Y = VoxelWorld.HEIGHT - 1;
    private static final int[] END_PILLAR_OFFSETS = {
        0, -9, 8, -4, 5, 8, -7, 6, -8, -2
    };
    private static final int[] END_PILLAR_HEIGHTS = { 23, 20, 24, 21, 23 };
    private static final int END_EXIT_SIZE = 3;
    private static final int END_EXIT_FOUNTAIN_SIZE = 7;
    private static final int END_SPAWN_PLATFORM_WIDTH = 5;
    private static final int END_SPAWN_PLATFORM_DEPTH = 2;
    private static final int NETHER_FORTRESS_REGION = 256;
    private static final int NETHER_FORTRESS_SIZE = 51;
    private static final int NETHER_FORTRESS_CELLS = 10;
    private static final int NETHER_FORTRESS_STEP = 5;
    static final int NETHER_FORTRESS_FLOOR_Y = 11;
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
    // Immutable per-seed noise tables. Keeping a small bounded cache avoids
    // serializing whole chunk generations or replacing a table another worker reads.
    private static volatile NoiseTable noiseTable0;
    private static volatile NoiseTable noiseTable1;
    private static volatile NoiseTable noiseTable2;
    private static volatile NoiseTable noiseTable3;
    private static volatile NoiseTable lastNoiseTable;
    private static int nextNoiseTable;

    static synchronized void prepareNoise(int seed, int version) {
        int selectedVersion = version <= 0 ? 0 : 1;
        NoiseTable existing = findNoiseTable(seed, selectedVersion);
        if (existing != null) {
            lastNoiseTable = existing;
            return;
        }
        byte[] permutation = null;
        if (selectedVersion != 0) {
            permutation = new byte[512];
            int state = seed ^ 0x6D2B79F5;
            for (int i = 0; i < 256; i++) permutation[i] = (byte)i;
            for (int i = 255; i > 0; i--) {
                state = state * 1664525 + 1013904223;
                int j = (state >>> 1) % (i + 1);
                byte value = permutation[i];
                permutation[i] = permutation[j];
                permutation[j] = value;
            }
            for (int i = 0; i < 256; i++) permutation[i + 256] = permutation[i];
        }
        NoiseTable created = new NoiseTable(seed, selectedVersion, permutation);
        switch (nextNoiseTable++ & 3) {
        case 0: noiseTable0 = created; break;
        case 1: noiseTable1 = created; break;
        case 2: noiseTable2 = created; break;
        default: noiseTable3 = created; break;
        }
        lastNoiseTable = created;
    }

    private static NoiseTable findNoiseTable(int seed, int version) {
        NoiseTable recent = lastNoiseTable;
        if (recent != null && recent.seed == seed && recent.version == version) return recent;
        NoiseTable table = noiseTable0;
        if (table != null && table.seed == seed && table.version == version) return table;
        table = noiseTable1;
        if (table != null && table.seed == seed && table.version == version) return table;
        table = noiseTable2;
        if (table != null && table.seed == seed && table.version == version) return table;
        table = noiseTable3;
        if (table != null && table.seed == seed && table.version == version) return table;
        return null;
    }

    private static NoiseTable findNoiseTable(int seed) {
        NoiseTable recent = lastNoiseTable;
        if (recent != null && recent.seed == seed) return recent;
        NoiseTable table = noiseTable0;
        if (table != null && table.seed == seed) return table;
        table = noiseTable1;
        if (table != null && table.seed == seed) return table;
        table = noiseTable2;
        if (table != null && table.seed == seed) return table;
        table = noiseTable3;
        if (table != null && table.seed == seed) return table;
        return null;
    }

    private static final class NoiseTable {
        final int seed;
        final int version;
        final byte[] permutation;

        NoiseTable(int seed, int version, byte[] permutation) {
            this.seed = seed;
            this.version = version;
            this.permutation = permutation;
        }
    }

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
        generate(seed, chunkX, chunkZ, blocks, heights, biomes, false);
    }

    static void generateEconomic(int seed, int chunkX, int chunkZ, byte[] blocks,
                                 int[] heights, byte[] biomes) {
        generate(seed, chunkX, chunkZ, blocks, heights, biomes, true);
    }

    private static void generate(int seed, int chunkX, int chunkZ, byte[] blocks,
                                 int[] heights, byte[] biomes, boolean economic) {
        if (blocks == null || blocks.length < CHUNK_VOLUME || heights == null ||
            heights.length < CHUNK_SIZE * CHUNK_SIZE || biomes == null ||
            biomes.length < CHUNK_SIZE * CHUNK_SIZE) return;
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: calculando origen del chunk " + chunkX + "," + chunkZ);
        int originX = chunkX * CHUNK_SIZE;
        int originZ = chunkZ * CHUNK_SIZE;

        for (int z = 0; z < CHUNK_SIZE; z++) {
            int worldZ = originZ + z;
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int worldX = originX + x;
                int column = z * CHUNK_SIZE + x;
                int biome = biomeAt(seed, worldX, worldZ);
                int height = economic ? economicSurfaceHeight(seed, worldX, worldZ, biome) :
                    surfaceHeight(seed, worldX, worldZ, biome);
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

        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: generando vetas minerales");
        if (economic) generateEconomicOreVeins(seed, originX, originZ, blocks);
        else generateOreVeins(seed, originX, originZ, blocks);

        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: tallando cuevas");
        if (economic) carveEconomicCaves(seed, originX, originZ, heights, blocks);
        else carveCaves(seed, originX, originZ, heights, blocks);
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: formando ravines");
        if (economic) carveEconomicRavines(seed, originX, originZ, heights, blocks);
        else carveRavines(seed, originX, originZ, heights, blocks);
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: decorando terreno");
        decorate(seed, originX, originZ, heights, biomes, blocks, economic);
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: chunk completado " + chunkX + "," + chunkZ);
    }

    static int surfaceHeight(int seed, int x, int z) {
        return surfaceHeight(seed, x, z, biomeAt(seed, x, z));
    }

    static int economicSurfaceHeight(int seed, int x, int z) {
        return economicSurfaceHeight(seed, x, z, biomeAt(seed, x, z));
    }

    /** Low-amplitude two-octave terrain for devices that need cheaper worlds. */
    private static int economicSurfaceHeight(int seed, int x, int z, int biome) {
        int broad = valueNoise(seed ^ 0x2A4B6C, x, z, 128) - 128;
        int detail = valueNoise(seed ^ 0x5D7E9F, x, z, 48) - 128;
        int variation = (broad * 5 + detail * 2) >> 8;
        int height;
        if (biome == OCEAN) height = 27 + (variation >> 1);
        else if (biome == SNOWY) height = 35 + variation;
        else if (biome == DESERT) height = 33 + variation;
        else if (biome == DARK_FOREST || biome == JUNGLE) height = 35 + variation;
        else height = 34 + variation;
        if (height < 24) return 24;
        if (height > 40) return 40;
        return height;
    }

    /** Compact Alpha-style Nether: netherrack floor, cave volume, lava sea and roof. */
    static void generateNether(int seed, int chunkX, int chunkZ, byte[] blocks,
                               int[] floorHeights) {
        generateNether(seed, chunkX, chunkZ, blocks, floorHeights, false);
    }

    /**
     * End island generator. The End is a bounded 48x48 area of void; only one
     * irregular, tapered island is generated inside it. Keep a void margin on
     * every edge so the island never gets clipped into the square world bounds.
     */
    static void generateEnd(int seed, int chunkX, int chunkZ, byte[] blocks,
                            int[] surfaceHeights, byte[] biomes) {
        if (blocks == null || blocks.length < CHUNK_VOLUME || surfaceHeights == null ||
                surfaceHeights.length < CHUNK_SIZE * CHUNK_SIZE || biomes == null ||
                biomes.length < CHUNK_SIZE * CHUNK_SIZE) return;
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generateEnd: generando chunk " +
                chunkX + "," + chunkZ);
        final int centerX = VoxelWorld.WIDTH / 2;
        final int centerZ = VoxelWorld.DEPTH / 2;
        int originX = chunkX * CHUNK_SIZE;
        int originZ = chunkZ * CHUNK_SIZE;
        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int wx = originX + x;
                int wz = originZ + z;
                int dx = wx - centerX;
                int dz = wz - centerZ;
                int outlineNoise = valueNoise(seed ^ 0x45D9F3B, wx, wz, 11);
                int detailNoise = valueNoise(seed ^ 0x27D4EB2D, wx, wz, 5);
                int radius = 18 + (outlineNoise - 128) / 40 +
                        (detailNoise - 128) / 112;
                if (radius < 14) radius = 14;
                if (radius > 22) radius = 22;
                int distanceSquared = dx * dx + dz * dz;
                boolean island = distanceSquared <= radius * radius;
                int column = z * CHUNK_SIZE + x;
                int base = column * WORLD_HEIGHT;
                biomes[column] = 0;
                int top = 0;
                int bottom = 0;
                if (island) {
                    top = endSurfaceHeight(seed, wx, wz);

                    int distance = (int)Math.sqrt(distanceSquared);
                    int undersideNoise = valueNoise(seed ^ 0x9E3779B9, wx, wz, 7);
                    int thickness = 3 + (radius - distance) / 3 + (undersideNoise - 128) / 96;
                    if (thickness < 3) thickness = 3;
                    if (thickness > 11) thickness = 11;
                    bottom = top - thickness;
                    surfaceHeights[column] = top;
                } else {
                    surfaceHeights[column] = 0;
                }
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    blocks[base + y] = island && y >= bottom && y <= top ?
                            Block.END_STONE : Block.AIR;
                }
                for (int pillar = 0; pillar < END_PILLAR_HEIGHTS.length; pillar++) {
                    if (!isEndPillarColumn(pillar, wx, wz)) continue;
                    int pillarTop = endPillarTopY(seed, pillar);
                    for (int y = top + 1; y <= pillarTop; y++) {
                        blocks[base + y] = Block.OBSIDIAN;
                    }
                    break;
                }
                int fountainX = endExitFountainX(), fountainZ = endExitFountainZ();
                if (wx >= fountainX && wx < fountainX + END_EXIT_FOUNTAIN_SIZE &&
                        wz >= fountainZ && wz < fountainZ + END_EXIT_FOUNTAIN_SIZE) {
                    int foundationY = endExitFoundationY(seed);
                    int portalY = foundationY + 1;
                    blocks[base + foundationY] = Block.BEDROCK;
                    int innerX = endExitX(), innerZ = endExitZ();
                    if (wx >= innerX && wx < innerX + END_EXIT_SIZE &&
                            wz >= innerZ && wz < innerZ + END_EXIT_SIZE)
                        blocks[base + portalY] = Block.BEDROCK;
                    if (isEndExitFountainRim(wx, wz))
                        blocks[base + portalY + 1] = Block.BEDROCK;
                    if (isEndExitPostColumn(wx, wz)) {
                        for (int y = portalY + 1; y <= portalY + 3; y++)
                            blocks[base + y] = Block.BEDROCK;
                    }
                }
                if (isEndSpawnPlatformColumn(wx, wz))
                    blocks[base + endSpawnPlatformY(seed)] = Block.OBSIDIAN;
            }
        }
    }


    /** Infinite sky realm: deterministic island fields are generated per chunk. */
    static void generateAether(int seed, int chunkX, int chunkZ, byte[] blocks,
                               int[] surfaceHeights, byte[] biomes) {
        if (blocks == null || blocks.length < CHUNK_VOLUME || surfaceHeights == null ||
                surfaceHeights.length < CHUNK_SIZE * CHUNK_SIZE || biomes == null ||
                biomes.length < CHUNK_SIZE * CHUNK_SIZE) return;
        int ox = chunkX * CHUNK_SIZE, oz = chunkZ * CHUNK_SIZE;
        for (int z = 0; z < CHUNK_SIZE; z++) for (int x = 0; x < CHUNK_SIZE; x++) {
            int wx = ox + x, wz = oz + z, col = z * CHUNK_SIZE + x, base = col * WORLD_HEIGHT;
            int chosenX = 0, chosenZ = 0, chosenRadius = 0, top = 0;
            int chosenDx = 0, chosenDz = 0, best = 0x7FFFFFFF;
            int cellX = floorDiv(wx, 32), cellZ = floorDiv(wz, 32);
            // A 3x3 neighborhood covers the dense 32-block island field; broad
            // island edges overlap to form clustered skyland masses.
            for (int cz = cellZ - 1; cz <= cellZ + 1; cz++) {
                for (int cx = cellX - 1; cx <= cellX + 1; cx++) {
                    int hx = lattice(seed ^ 0x3C6EF372, cx, cz);
                    int hz = lattice(seed ^ 0x1BF5A9D5, cx, cz);
                    int islandX = cx * 32 + 8 + (hx * 16 >> 8);
                    int islandZ = cz * 32 + 8 + (hz * 16 >> 8);
                    int radius = 12 + (lattice(seed ^ 0x5A827999, cx, cz) * 8 >> 8);
                    // Preserve the classic central landing island so the
                    // fixed Aether return portal always rests on real terrain.
                    if (cx == 0 && cz == 0) {
                        islandX = 24; islandZ = 24; radius = 8;
                    }
                    int dx = wx - islandX, dz = wz - islandZ;
                    int distance = dx * dx + dz * dz;
                    int rough = valueNoise(seed ^ 0x51ED270B, wx, wz, 11);
                    int edgeRadius = radius + (rough - 128) / 110;
                    if (edgeRadius < radius - 1) edgeRadius = radius - 1;
                    if (edgeRadius > radius + 1) edgeRadius = radius + 1;
                    if (distance <= edgeRadius * edgeRadius && distance < best) {
                        best = distance;
                        chosenX = islandX; chosenZ = islandZ;
                        chosenRadius = edgeRadius; chosenDx = dx; chosenDz = dz;
                        top = (cx == 0 && cz == 0) ? 35 :
                                29 + (lattice(seed ^ 0x2C1B3C6D, cx, cz) * 15 >> 8) +
                                (valueNoise(seed ^ 0x6C8E9CF5, wx, wz, 19) - 128) / 110;
                    }
                }
            }
            biomes[col] = 0;
            for (int y = 0; y < WORLD_HEIGHT; y++) blocks[base + y] = Block.AIR;
            if (best == 0x7FFFFFFF) {
                surfaceHeights[col] = 0;
                continue;
            }
            int distance = (int)Math.sqrt(chosenDx * chosenDx + chosenDz * chosenDz);
            int thick = 4 + (chosenRadius - distance) / 2 +
                    (valueNoise(seed ^ 0x4CF5AD43, wx, wz, 8) - 128) / 100;
            if (thick < 3) thick = 3;
            if (thick > 10) thick = 10;
            int bottom = top - thick;
            surfaceHeights[col] = top;
            for (int y = bottom; y <= top; y++) {
                if (y == top) blocks[base + y] = Block.GRASS;
                else if (y >= top - 2) blocks[base + y] = Block.DIRT;
                else blocks[base + y] = Block.STONE;
            }

            // Tall Aether trees grow near the center of selected islands.
            // Their canopy is broad, layered and deterministic across chunk edges.
            for (int cz = cellZ - 1; cz <= cellZ + 1; cz++) {
                for (int cx = cellX - 1; cx <= cellX + 1; cx++) {
                    int treeChance = lattice(seed ^ 0x7F4A7C15, cx, cz);
                    if ((cx == 0 && cz == 0) || treeChance < 118) continue;
                    int treeX = cx * 32 + 8 + (lattice(seed ^ 0x3C6EF372, cx, cz) * 16 >> 8);
                    int treeZ = cz * 32 + 8 + (lattice(seed ^ 0x1BF5A9D5, cx, cz) * 16 >> 8);
                    if (cx == 0 && cz == 0) { treeX = 24; treeZ = 24; }
                    int tx = wx - treeX, tz = wz - treeZ;
                    int td = tx * tx + tz * tz;
                    if (td > 18) continue;
                    int treeTop = 29 + (lattice(seed ^ 0x2C1B3C6D, cx, cz) * 15 >> 8);
                    int treeHeight = 7 + (lattice(seed ^ 0x6A09E667, cx, cz) * 3 >> 8);
                    for (int ty = treeTop + 1; ty <= treeTop + treeHeight + 2 &&
                            ty < WORLD_HEIGHT; ty++) {
                        boolean trunk = tx == 0 && tz == 0 && ty <= treeTop + treeHeight;
                        int layer = ty - (treeTop + treeHeight - 2);
                        int radius = layer == 0 || layer == 4 ? 1 :
                                (layer == 1 || layer == 3 ? 2 : 3);
                        boolean foliage = layer >= 0 && layer <= 4 &&
                                td <= radius * radius + 1;
                        int at = base + ty;
                        if (trunk) blocks[at] = Block.LOG;
                        else if (foliage && blocks[at] == Block.AIR)
                            blocks[at] = Block.LEAVES;
                    }
                }
            }
        }

        // Snowy cloud clusters are generated in a separate pass so empty sky
        // columns do not erase them while their island terrain is initialized.
        int minCellX = floorDiv(ox, 32) - 1, maxCellX = floorDiv(ox + CHUNK_SIZE - 1, 32) + 1;
        int minCellZ = floorDiv(oz, 32) - 1, maxCellZ = floorDiv(oz + CHUNK_SIZE - 1, 32) + 1;
        for (int cz = minCellZ; cz <= maxCellZ; cz++) for (int cx = minCellX; cx <= maxCellX; cx++) {
            int cloudX = cx * 32 + 4 + (lattice(seed ^ 0x243F6A88, cx, cz) * 24 >> 8);
            int cloudZ = cz * 32 + 4 + (lattice(seed ^ 0xB7E15162, cx, cz) * 24 >> 8);
            for (int z = 0; z < CHUNK_SIZE; z++) for (int x = 0; x < CHUNK_SIZE; x++) {
                int wx = ox + x, wz = oz + z, dx = wx - cloudX, dz = wz - cloudZ;
                int distance = dx * dx + dz * dz;
                if (distance > 25) continue;
                int col = z * CHUNK_SIZE + x, base = col * WORLD_HEIGHT;
                int edge = 25 - distance;
                if (edge >= 5 && blocks[base + 48] == Block.AIR) blocks[base + 48] = Block.SNOW;
                if (edge >= 12 && blocks[base + 49] == Block.AIR) blocks[base + 49] = Block.SNOW;
                if (edge >= 19 && blocks[base + 47] == Block.AIR) blocks[base + 47] = Block.SNOW;
            }
        }
    }

    static int endPillarCount() { return END_PILLAR_HEIGHTS.length; }

    static int endPillarX(int index) {
        return VoxelWorld.WIDTH / 2 + END_PILLAR_OFFSETS[index * 2];
    }

    static int endPillarZ(int index) {
        return VoxelWorld.DEPTH / 2 + END_PILLAR_OFFSETS[index * 2 + 1];
    }

    private static boolean isEndPillarColumn(int index, int x, int z) {
        return x >= endPillarX(index) - 1 && x <= endPillarX(index) + 2 &&
                z >= endPillarZ(index) - 1 && z <= endPillarZ(index) + 2;
    }

    static int endExitX() { return VoxelWorld.WIDTH / 2 - 1; }
    static int endExitZ() { return VoxelWorld.DEPTH / 2 - 1; }

    private static int endExitFountainX() { return VoxelWorld.WIDTH / 2 - END_EXIT_FOUNTAIN_SIZE / 2; }
    private static int endExitFountainZ() { return VoxelWorld.DEPTH / 2 - END_EXIT_FOUNTAIN_SIZE / 2; }

    private static boolean isEndExitFountainRim(int x, int z) {
        int x0 = endExitFountainX(), z0 = endExitFountainZ();
        return x == x0 || x == x0 + END_EXIT_FOUNTAIN_SIZE - 1 ||
                z == z0 || z == z0 + END_EXIT_FOUNTAIN_SIZE - 1;
    }

    static int endSpawnPlatformX() { return VoxelWorld.WIDTH / 2 - END_SPAWN_PLATFORM_WIDTH / 2; }
    static int endSpawnPlatformZ() { return 0; }

    private static boolean isEndSpawnPlatformColumn(int x, int z) {
        return x >= endSpawnPlatformX() && x < endSpawnPlatformX() + END_SPAWN_PLATFORM_WIDTH &&
                z >= endSpawnPlatformZ() && z < endSpawnPlatformZ() + END_SPAWN_PLATFORM_DEPTH;
    }

    static int endSpawnPlatformY(int seed) {
        return endExitBaseY(seed);
    }

    private static boolean isEndExitPostColumn(int x, int z) {
        return x == VoxelWorld.WIDTH / 2 && z == VoxelWorld.DEPTH / 2;
    }

    static int endExitBaseY(int seed) {
        return endExitFoundationY(seed) + 1;
    }

    private static int endExitFoundationY(int seed) {
        int max = 0;
        int x0 = endExitFountainX(), z0 = endExitFountainZ();
        for (int z = z0; z < z0 + END_EXIT_FOUNTAIN_SIZE; z++)
            for (int x = x0; x < x0 + END_EXIT_FOUNTAIN_SIZE; x++) {
                int height = endSurfaceHeight(seed, x, z);
                if (height > max) max = height;
            }
        return max;
    }

    static int endExitBaseY(VoxelWorld world) {
        // The center column contains the bedrock post, so derive the portal
        // level from the full fountain footprint instead of scanning blocks.
        return endExitBaseY(world.seed);
    }

    static void ensureEndExitStructure(VoxelWorld world) {
        // Keep an activated exit while rebuilding both the legacy and current
        // fountain layouts into the complete bedrock basin.
        int legacyX = VoxelWorld.WIDTH / 2 - 2;
        int legacyZ = VoxelWorld.DEPTH / 2 - 2;
        int legacyBaseY = legacyEndExitBaseY(world.seed);
        boolean wasActive = false;
        int portalScanMaxY = Math.min(VoxelWorld.HEIGHT - 1, END_ISLAND_MAX_Y + 8);
        for (int y = END_ISLAND_MIN_Y; y <= portalScanMaxY; y++)
            for (int z = legacyZ + 1; z <= legacyZ + 3; z++)
                for (int x = legacyX + 1; x <= legacyX + 3; x++) {
                    int at = (y * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x;
                    if (world.blocks[at] == Block.END_PORTAL) {
                        wasActive = true;
                        world.blocks[at] = Block.AIR;
                    }
                }
        for (int z = legacyZ; z < legacyZ + 5; z++) {
            for (int x = legacyX; x < legacyX + 5; x++) {
                int at = (legacyBaseY * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x;
                if (world.blocks[at] == Block.BEDROCK) world.blocks[at] = Block.END_STONE;
            }
        }
        for (int y = legacyBaseY + 1; y <= legacyBaseY + 4; y++)
            for (int z = legacyZ; z < legacyZ + 5; z++)
                for (int x = legacyX; x < legacyX + 5; x++) {
                    int at = (y * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x;
                    if (world.blocks[at] == Block.BEDROCK) world.blocks[at] = Block.AIR;
                }

        int x0 = endExitFountainX(), z0 = endExitFountainZ();
        int foundationY = endExitFoundationY(world.seed);
        int portalY = foundationY + 1;
        int innerX = endExitX(), innerZ = endExitZ();
        for (int z = z0; z < z0 + END_EXIT_FOUNTAIN_SIZE; z++) {
            for (int x = x0; x < x0 + END_EXIT_FOUNTAIN_SIZE; x++) {
                world.blocks[(foundationY * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] = Block.BEDROCK;
                if (x >= innerX && x < innerX + END_EXIT_SIZE &&
                        z >= innerZ && z < innerZ + END_EXIT_SIZE)
                    world.blocks[(portalY * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] = Block.BEDROCK;
                if (isEndExitFountainRim(x, z))
                    world.blocks[((portalY + 1) * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] = Block.BEDROCK;
            }
        }
        int postX = VoxelWorld.WIDTH / 2, postZ = VoxelWorld.DEPTH / 2;
        for (int y = portalY + 1; y <= portalY + 3; y++)
            world.blocks[(y * VoxelWorld.DEPTH + postZ) * VoxelWorld.WIDTH + postX] = Block.BEDROCK;
        if (wasActive) {
            for (int z = z0; z < z0 + END_EXIT_SIZE; z++)
                for (int x = x0; x < x0 + END_EXIT_SIZE; x++)
                    world.blocks[(portalY * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] =
                            x == VoxelWorld.WIDTH / 2 && z == VoxelWorld.DEPTH / 2 ?
                            Block.BEDROCK : Block.END_PORTAL;
        }
    }

    private static int legacyEndExitBaseY(int seed) {
        int max = 0, x0 = VoxelWorld.WIDTH / 2 - 2, z0 = VoxelWorld.DEPTH / 2 - 2;
        for (int z = z0; z < z0 + 5; z++)
            for (int x = x0; x < x0 + 5; x++) {
                int height = endSurfaceHeight(seed, x, z);
                if (height > max) max = height;
            }
        return max;
    }

    static void ensureEndSpawnPlatform(VoxelWorld world) {
        int x0 = endSpawnPlatformX(), z0 = endSpawnPlatformZ();
        int y = endSpawnPlatformY(world.seed);
        for (int z = z0; z < z0 + END_SPAWN_PLATFORM_DEPTH; z++) {
            for (int x = x0; x < x0 + END_SPAWN_PLATFORM_WIDTH; x++) {
                for (int clearY = END_ISLAND_MIN_Y; clearY <= END_ISLAND_MAX_Y; clearY++) {
                    int at = (clearY * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x;
                    if (world.blocks[at] == Block.END_STONE) world.blocks[at] = Block.AIR;
                }
                world.blocks[(y * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] = Block.OBSIDIAN;
            }
        }
    }

    static int endPillarTopY(int seed, int index) {
        int x = endPillarX(index), z = endPillarZ(index);
        return endSurfaceHeight(seed, x, z) + END_PILLAR_HEIGHTS[index];
    }

    static int endPillarHeight(int index) { return END_PILLAR_HEIGHTS[index]; }

    static int endPillarTopY(VoxelWorld world, int index) {
        int x = endPillarX(index), z = endPillarZ(index);
        for (int y = VoxelWorld.HEIGHT - 1; y >= 1; y--)
            if (world.get(x, y, z) == Block.OBSIDIAN) return y;
        return endPillarTopY(world.seed, index);
    }

    static void ensureEndPillars(VoxelWorld world) {
        for (int i = 0; i < endPillarCount(); i++) {
            int centerX = endPillarX(i), centerZ = endPillarZ(i);
            int centerSurface = findEndSurface(world, centerX, centerZ);
            if (centerSurface == 0) continue;
            int pillarTop = centerSurface + endPillarHeight(i);
            if (pillarTop >= VoxelWorld.HEIGHT) pillarTop = VoxelWorld.HEIGHT - 1;
            for (int z = centerZ - 1; z <= centerZ + 2; z++) {
                for (int x = centerX - 1; x <= centerX + 2; x++) {
                    if (world.get(x, pillarTop, z) == Block.OBSIDIAN) continue;
                    int surface = findEndSurface(world, x, z);
                    for (int y = surface + 1; surface > 0 && y <= pillarTop; y++)
                        world.blocks[(y * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] = Block.OBSIDIAN;
                }
            }
        }
    }

    /** Sparse, seed-stable nether-brick labyrinths, aligned across chunk edges. */
    private static void generateNetherFortresses(int seed, int chunkX, int chunkZ,
                                                  byte[] blocks) {
        int originX = chunkX * CHUNK_SIZE, originZ = chunkZ * CHUNK_SIZE;
        int regionX = floorDivide(originX, NETHER_FORTRESS_REGION);
        int regionZ = floorDivide(originZ, NETHER_FORTRESS_REGION);
        for (int rz = regionZ - 1; rz <= regionZ + 1; rz++) {
            for (int rx = regionX - 1; rx <= regionX + 1; rx++) {
                int regionHash = hash(seed ^ 0x6D2B79F5, rx, 0, rz);
                if ((regionHash & 7) != 0) continue;
                int centerX = rx * NETHER_FORTRESS_REGION + 32 + ((regionHash >>> 8) & 191);
                int centerZ = rz * NETHER_FORTRESS_REGION + 32 + ((regionHash >>> 17) & 191);
                int minX = centerX - NETHER_FORTRESS_SIZE / 2;
                int minZ = centerZ - NETHER_FORTRESS_SIZE / 2;
                if (minX >= originX + CHUNK_SIZE || minX + NETHER_FORTRESS_SIZE <= originX ||
                        minZ >= originZ + CHUNK_SIZE || minZ + NETHER_FORTRESS_SIZE <= originZ)
                    continue;
                boolean[][] east = new boolean[NETHER_FORTRESS_CELLS][NETHER_FORTRESS_CELLS];
                boolean[][] south = new boolean[NETHER_FORTRESS_CELLS][NETHER_FORTRESS_CELLS];
                buildFortressMaze(seed ^ regionHash, east, south);
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    int wz = originZ + z;
                    int localZ = wz - minZ;
                    if (localZ < 0 || localZ >= NETHER_FORTRESS_SIZE) continue;
                    for (int x = 0; x < CHUNK_SIZE; x++) {
                        int wx = originX + x;
                        int localX = wx - minX;
                        if (localX < 0 || localX >= NETHER_FORTRESS_SIZE) continue;
                        int base = (z * CHUNK_SIZE + x) * WORLD_HEIGHT;
                        blocks[base + NETHER_FORTRESS_FLOOR_Y] = Block.NETHER_BRICK;
                        blocks[base + NETHER_FORTRESS_FLOOR_Y + 5] = Block.NETHER_BRICK;
                        for (int y = NETHER_FORTRESS_FLOOR_Y + 1;
                                y < NETHER_FORTRESS_FLOOR_Y + 5; y++)
                            blocks[base + y] = Block.AIR;
                        if (fortressWall(localX, localZ, east, south)) {
                            for (int y = NETHER_FORTRESS_FLOOR_Y + 1;
                                    y < NETHER_FORTRESS_FLOOR_Y + 5; y++)
                                blocks[base + y] = Block.NETHER_BRICK;
                        }
                    }
                }
            }
        }
    }

    private static boolean fortressWall(int x, int z, boolean[][] east, boolean[][] south) {
        if (x == 0 || z == 0 || x == NETHER_FORTRESS_SIZE - 1 ||
                z == NETHER_FORTRESS_SIZE - 1) {
            // A three-block-wide southern entry connects the maze to the cavern.
            return !(z == 0 && x >= NETHER_FORTRESS_SIZE / 2 - 1 &&
                    x <= NETHER_FORTRESS_SIZE / 2 + 1);
        }
        int cellX = x / NETHER_FORTRESS_STEP;
        int cellZ = z / NETHER_FORTRESS_STEP;
        if (x % NETHER_FORTRESS_STEP == 0) {
            int west = cellX - 1;
            if (west >= 0 && west < NETHER_FORTRESS_CELLS &&
                    cellZ >= 0 && cellZ < NETHER_FORTRESS_CELLS &&
                    z % NETHER_FORTRESS_STEP >= 1 && z % NETHER_FORTRESS_STEP <= 3 &&
                    east[west][cellZ]) return false;
            return true;
        }
        if (z % NETHER_FORTRESS_STEP == 0) {
            int north = cellZ - 1;
            if (north >= 0 && north < NETHER_FORTRESS_CELLS &&
                    cellX >= 0 && cellX < NETHER_FORTRESS_CELLS &&
                    x % NETHER_FORTRESS_STEP >= 1 && x % NETHER_FORTRESS_STEP <= 3 &&
                    south[cellX][north]) return false;
            return true;
        }
        return false;
    }

    private static void buildFortressMaze(int seed, boolean[][] east, boolean[][] south) {
        boolean[][] visited = new boolean[NETHER_FORTRESS_CELLS][NETHER_FORTRESS_CELLS];
        int[] stack = new int[NETHER_FORTRESS_CELLS * NETHER_FORTRESS_CELLS];
        int[] choices = new int[4];
        int top = 0, step = 0;
        stack[0] = 0;
        visited[0][0] = true;
        while (top >= 0) {
            int cell = stack[top];
            int cx = cell % NETHER_FORTRESS_CELLS;
            int cz = cell / NETHER_FORTRESS_CELLS;
            int count = 0;
            if (cx > 0 && !visited[cz][cx - 1]) choices[count++] = 0;
            if (cx + 1 < NETHER_FORTRESS_CELLS && !visited[cz][cx + 1]) choices[count++] = 1;
            if (cz > 0 && !visited[cz - 1][cx]) choices[count++] = 2;
            if (cz + 1 < NETHER_FORTRESS_CELLS && !visited[cz + 1][cx]) choices[count++] = 3;
            if (count == 0) { top--; continue; }
            int pick = (hash(seed, step++, cell, count) & 0x7fffffff) % count;
            int direction = choices[pick];
            int nx = cx, nz = cz;
            if (direction == 0) nx--;
            else if (direction == 1) { east[cz][cx] = true; nx++; }
            else if (direction == 2) nz--;
            else { south[cz][cx] = true; nz++; }
            if (direction == 0) east[nz][nx] = true;
            else if (direction == 2) south[nz][nx] = true;
            visited[nz][nx] = true;
            stack[++top] = nz * NETHER_FORTRESS_CELLS + nx;
        }
    }

    private static int floorDivide(int value, int divisor) {
        int quotient = value / divisor;
        if (value < 0 && value % divisor != 0) quotient--;
        return quotient;
    }

    static void ensureEndIslandExpansion(VoxelWorld world) {
        int centerX = VoxelWorld.WIDTH / 2, centerZ = VoxelWorld.DEPTH / 2;
        for (int z = 0; z < VoxelWorld.DEPTH; z++) {
            for (int x = 0; x < VoxelWorld.WIDTH; x++) {
                int dx = x - centerX, dz = z - centerZ;
                int distanceSquared = dx * dx + dz * dz;
                int outline = valueNoise(world.seed ^ 0x45D9F3B, x, z, 11);
                int detail = valueNoise(world.seed ^ 0x27D4EB2D, x, z, 5);
                int oldRadius = 17 + (outline - 128) / 40 + (detail - 128) / 112;
                if (oldRadius < 13) oldRadius = 13;
                if (oldRadius > 21) oldRadius = 21;
                int newRadius = oldRadius + 1;
                if (newRadius < 14) newRadius = 14;
                if (newRadius > 22) newRadius = 22;
                if (distanceSquared <= oldRadius * oldRadius ||
                        distanceSquared > newRadius * newRadius) continue;
                boolean occupied = false;
                for (int y = END_ISLAND_MIN_Y; y <= END_ISLAND_MAX_Y; y++) {
                    if (world.get(x, y, z) != Block.AIR) { occupied = true; break; }
                }
                if (occupied) continue;
                int top = endSurfaceHeight(world.seed, x, z);
                int distance = (int)Math.sqrt(distanceSquared);
                int underside = valueNoise(world.seed ^ 0x9E3779B9, x, z, 7);
                int thickness = 3 + (newRadius - distance) / 3 + (underside - 128) / 96;
                if (thickness < 3) thickness = 3;
                if (thickness > 11) thickness = 11;
                int bottom = top - thickness;
                for (int y = bottom; y <= top; y++)
                    world.blocks[(y * VoxelWorld.DEPTH + z) * VoxelWorld.WIDTH + x] = Block.END_STONE;
            }
        }
    }

    private static int findEndSurface(VoxelWorld world, int x, int z) {
        for (int y = VoxelWorld.HEIGHT - 1; y >= 18; y--)
            if (world.get(x, y, z) == Block.END_STONE) return y;
        return 0;
    }

    private static int endSurfaceHeight(int seed, int x, int z) {
        int heightNoise = valueNoise(seed ^ 0x165667B1, x, z, 13);
        int heightDetail = valueNoise(seed ^ 0xD3A2646C, x, z, 6);
        int top = 32 + (heightNoise - 128) / 48 + (heightDetail - 128) / 128;
        if (top < 29) return 29;
        if (top > 35) return 35;
        return top;
    }

    /** Economic Nether uses fewer height-noise samples while retaining lava rivers. */
    static void generateEconomicNether(int seed, int chunkX, int chunkZ, byte[] blocks,
                                       int[] floorHeights) {
        generateNether(seed, chunkX, chunkZ, blocks, floorHeights, true);
    }

    private static void generateNether(int seed, int chunkX, int chunkZ, byte[] blocks,
                                       int[] floorHeights, boolean economic) {
        if (blocks == null || blocks.length < CHUNK_VOLUME || floorHeights == null ||
            floorHeights.length < CHUNK_SIZE * CHUNK_SIZE) return;
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generateNether: calculando origen del chunk " + chunkX + "," + chunkZ);
        int originX = chunkX * CHUNK_SIZE;
        int originZ = chunkZ * CHUNK_SIZE;
        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int wx = originX + x, wz = originZ + z;
                int column = z * CHUNK_SIZE + x;
                int floor = economic ? economicNetherFloorHeight(seed, wx, wz) : netherFloorHeight(seed, wx, wz);
                int roof = economic ? 18 + (valueNoise(seed ^ 0x3B91, wx, wz, 64) >> 5) :
                    16 + (valueNoise(seed ^ 0x3B91, wx, wz, 40) >> 5);
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
                // A low-frequency contour creates continuous, winding lava rivers
                // across chunk borders. World-coordinate sampling prevents seams.
                int riverField = valueNoise(seed ^ 0x4C415641, wx, wz, economic ? 72 : 56);
                if (floor > 2 && Math.abs(riverField - 128) <= (economic ? 5 : 7)) {
                    blocks[base + floor] = Block.LAVA;
                    blocks[base + floor - 1] = Block.LAVA;
                }

                int roofHash = hash(seed ^ 0x1057, wx, roof, wz);
                if ((roofHash & 31) == 0 && roof > floor + 2) {
                    blocks[base + (roof - 1)] = Block.COAL;
                    if (x > 0) blocks[(column - 1) * WORLD_HEIGHT + roof - 1] = Block.COAL;
                    if (z > 0) blocks[(column - CHUNK_SIZE) * WORLD_HEIGHT + roof - 1] = Block.COAL;
                }
            }
        }
        generateNetherFortresses(seed, chunkX, chunkZ, blocks);
    }

    static int economicNetherFloorHeight(int seed, int x, int z) {
        int broad = valueNoise(seed ^ 0x6A09, x, z, 64);
        int floor = 4 + (broad * 4 >> 8);
        if (floor > 7) floor = 7;
        return floor;
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
        // Alpha-inspired continental shape: broad low-frequency terrain, a
        // medium ridge field and a small detail octave. All work is 2D and
        // performed once per column (never a full 3D noise volume).
        int broad = valueNoise(seed ^ 0x1A2B3C, x, z, 96) - 128;
        int medium = valueNoise(seed ^ 0x4D5E6F, x, z, 32) - 128;
        int detail = valueNoise(seed ^ 0x778899, x, z, 10) - 128;
        int ridgeNoise = valueNoise(seed ^ 0x13579B, x, z, 72);
        int ridge = 128 - Math.abs(ridgeNoise - 128);
        int variation = (broad * 30 + medium * 12 + detail * 4) >> 8;
        variation += (ridge - 64) >> 2;
        int height;
        if (biome == OCEAN) height = 12 + (variation >> 2);
        else if (biome == SNOWY) height = 20 + variation;
        else if (biome == DESERT) height = 17 + (variation * 3 >> 2);
        else if (biome == DARK_FOREST || biome == JUNGLE) height = 19 + variation;
        else height = 18 + variation;
        // Raise the surface by 16 blocks while keeping the bedrock at y=0.
        // This adds genuine underground depth instead of merely adding empty sky.
        height += 16;
        if (height < 25) return 25;
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

    /**
     * Lock-free noise lookup. The old synchronized lattice cache serialized
     * every single noise sample from terrain generation. Version 0 uses a
     * direct coordinate hash; version 1 reads the permutation prepared when
     * the world is created.
     */
    private static int lattice(int seed, int x, int z) {
        NoiseTable table = findNoiseTable(seed);
        if (table == null) {
            prepareNoise(seed, 1);
            table = findNoiseTable(seed);
        }
        if (table != null && table.version != 0) {
            byte[] permutation = table.permutation;
            int px = x & 255;
            int pz = z & 255;
            return permutation[px + (permutation[pz] & 255)] & 255;
        }
        int base = seed ^ x * 73428767 ^ z * 42317861;
        int mixed = (base ^ (base >>> 13)) * 1274126177;
        mixed ^= mixed >>> 16;
        return mixed >>> 24;
    }

    /** Sparse, short, mostly unbranched tunnels for Economic worlds. */
    private static void carveEconomicCaves(int seed, int originX, int originZ,
                                           int[] heights, byte[] blocks) {
        final int region = 40;
        int firstX = floorDiv(originX - 18, region);
        int lastX = floorDiv(originX + CHUNK_SIZE + 18, region);
        int firstZ = floorDiv(originZ - 18, region);
        int lastZ = floorDiv(originZ + CHUNK_SIZE + 18, region);
        for (int rz = firstZ; rz <= lastZ; rz++) {
            for (int rx = firstX; rx <= lastX; rx++) {
                int h = hash(seed ^ 0x29A4C731, rx, 0, rz);
                if ((h & 3) == 0) continue;
                int x = rx * region + 8 + ((h >>> 4) & 23);
                int z = rz * region + 8 + ((h >>> 10) & 23);
                int surface = economicSurfaceHeight(seed, x, z);
                int y = 7 + ((h >>> 18) & 15);
                if (y > surface - 5) y = surface - 5;
                int dx = ((h >>> 2) & 1) == 0 ? 1 : -1;
                int dz = ((h >>> 7) & 1) == 0 ? 0 : 1;
                int length = 10 + ((h >>> 25) & 15);
                for (int step = 0; step < length; step++) {
                    int wobble = hash(seed ^ h, step, rx, rz);
                    if ((step & 3) == 0) {
                        if ((wobble & 3) == 0) dx = -dx;
                        if ((wobble & 12) == 4) dz = dz == 0 ? 1 : -dz;
                    }
                    x += dx;
                    z += dz;
                    if ((wobble & 7) == 0) y += (wobble & 8) == 0 ? 1 : -1;
                    if (y < 4) y = 4;
                    if (y > surface - 4) y = surface - 4;
                    carveTunnelPocket(originX, originZ, heights, blocks,
                                      x, y, z, 1, 1);
                }
            }
        }
    }

    private static void carveCaves(int seed, int originX, int originZ, int[] heights, byte[] blocks) {
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.carveCaves: túneles Alpha ligeros");
        // Alpha's cave generator walks a small number of curved 3D paths and
        // occasionally forks them. This version uses integer deterministic
        // steps and clips every carve to the active 16x16 chunk.
        // Smaller regions and more paths create a denser cave network without
        // allocating a 3D noise volume. Paths remain deterministic per seed.
        final int region = 24;
        int firstX = floorDiv(originX - 34, region);
        int lastX = floorDiv(originX + CHUNK_SIZE + 34, region);
        int firstZ = floorDiv(originZ - 34, region);
        int lastZ = floorDiv(originZ + CHUNK_SIZE + 34, region);
        for (int rz = firstZ; rz <= lastZ; rz++) {
            for (int rx = firstX; rx <= lastX; rx++) {
                int regionHash = hash(seed ^ 0x35A76D21, rx, 0, rz);
                // Every region gets at least one tunnel; most get a second.
                int tunnelCount = (regionHash & 3) == 0 ? 1 : 2;
                for (int t = 0; t < tunnelCount; t++) {
                    int h = hash(seed ^ (0x6C8E9CF5 + t * 0x1F123BB5), rx, t + 1, rz);
                    int x = rx * region + 8 + ((h >>> 4) & 15);
                    int z = rz * region + 8 + ((h >>> 12) & 15);
                    int surface = surfaceHeight(seed, x, z);
                    int y = 8 + ((h >>> 20) & 31);
                    if (y > surface - 5) y = Math.max(4, surface - 5);
                    int dirX = ((h >>> 2) & 3) - 1;
                    int dirZ = ((h >>> 9) & 3) - 1;
                    if (dirX == 0 && dirZ == 0) dirX = 1;
                    int pitch = ((h >>> 25) & 3) - 1;
                    int length = 16 + ((h >>> 27) & 15);
                    int branchAt = 5 + ((h >>> 16) & 7);
                    int branchDir = (h >>> 6) & 1;
                    int branchX = 0, branchY = 0, branchZ = 0;
                    int branchDX = 0, branchDZ = 0;
                    for (int step = 0; step < length; step++) {
                        int wobble = hash(seed ^ h, step, t, rx + rz);
                        if ((step & 3) == 0) {
                            dirX += ((wobble >>> 3) & 1) - ((wobble >>> 5) & 1);
                            dirZ += ((wobble >>> 7) & 1) - ((wobble >>> 9) & 1);
                            if (dirX > 1) dirX = 1;
                            if (dirX < -1) dirX = -1;
                            if (dirZ > 1) dirZ = 1;
                            if (dirZ < -1) dirZ = -1;
                            pitch += ((wobble >>> 11) & 1) - ((wobble >>> 13) & 1);
                            if (pitch > 1) pitch = 1;
                            if (pitch < -1) pitch = -1;
                        }
                        x += dirX;
                        z += dirZ;
                        y += pitch;
                        if (y < 3) { y = 3; pitch = 1; }
                        if (y > WORLD_HEIGHT - 12) { y = WORLD_HEIGHT - 12; pitch = -1; }
                        int radius = 2 + ((wobble >>> 17) & 1);
                        if ((wobble & 31) == 0) radius++;
                        carveTunnelPocket(originX, originZ, heights, blocks,
                                          x, y, z, radius, Math.max(1, radius - 1));
                        if (step == branchAt && t == 0 && (h & 7) < 5) {
                            branchX = x; branchY = y; branchZ = z;
                            branchDX = branchDir == 0 ? -dirZ : dirZ;
                            branchDZ = branchDir == 0 ? dirX : -dirX;
                        }
                    }
                    if (branchDX != 0 || branchDZ != 0) {
                        int bx = branchX, by = branchY, bz = branchZ;
                        for (int step = 0; step < 10; step++) {
                            bx += branchDX;
                            bz += branchDZ;
                            if ((step & 2) == 0) by += ((h >>> (step + 3)) & 1);
                            carveTunnelPocket(originX, originZ, heights, blocks,
                                              bx, by, bz, 2, 1);
                        }
                    }

                    // Rare sloped entrances: a short descending tunnel, not a
                    // vertical shaft. The low frequency keeps surface carving cheap.
                    if (t == 0 && (h & 15) == 7) {
                        int ex = rx * region + 8 + ((h >>> 14) & 15);
                        int ez = rz * region + 8 + ((h >>> 18) & 15);
                        int ey = surfaceHeight(seed, ex, ez) - 1;
                        int sx = ((h >>> 8) & 1) == 0 ? 1 : -1;
                        int sz = ((h >>> 9) & 1) == 0 ? 0 : 1;
                        for (int step = 0; step < 7; step++) {
                            carveTunnelPocket(originX, originZ, heights, blocks,
                                              ex, ey, ez, 2, 2);
                            ex += sx;
                            ez += sz;
                            ey--;
                            if (ey < 4) break;
                        }
                    }
                }
            }
        }
    }

    /** Very occasional narrow fissures; avoids the wide ravine pass in Economic. */
    private static void carveEconomicRavines(int seed, int originX, int originZ,
                                             int[] heights, byte[] blocks) {
        final int region = 96;
        int firstX = floorDiv(originX - 24, region);
        int lastX = floorDiv(originX + CHUNK_SIZE + 24, region);
        int firstZ = floorDiv(originZ - 24, region);
        int lastZ = floorDiv(originZ + CHUNK_SIZE + 24, region);
        for (int rz = firstZ; rz <= lastZ; rz++) {
            for (int rx = firstX; rx <= lastX; rx++) {
                int h = hash(seed ^ 0x61C88647, rx, 0, rz);
                if ((h & 15) != 0) continue;
                int x = rx * region + 12 + ((h >>> 4) & 63);
                int z = rz * region + 12 + ((h >>> 11) & 63);
                int y = economicSurfaceHeight(seed, x, z) - 2;
                int dx = ((h >>> 2) & 1) == 0 ? 1 : -1;
                int dz = ((h >>> 8) & 1) == 0 ? 0 : 1;
                int length = 18 + ((h >>> 20) & 15);
                for (int step = 0; step < length; step++) {
                    int wobble = hash(seed ^ h, step, rx, rz);
                    if ((step & 3) == 0) {
                        if ((wobble & 3) == 0) dx = -dx;
                        if ((wobble & 12) == 4) dz = dz == 0 ? 1 : -dz;
                    }
                    x += dx;
                    z += dz;
                    if ((wobble & 7) == 0) y += (wobble & 8) == 0 ? 1 : -1;
                    carveRavineSlice(originX, originZ, heights, blocks,
                                     x, y, z, 1, 4);
                }
            }
        }
    }

    /**
     * Alpha 1.8-style ravines: a deterministic, winding surface fissure with
     * steep walls and a narrow floor. Each region owns its seed, so chunks
     * independently reproduce the same ravine segments at their borders.
     */
    private static void carveRavines(int seed, int originX, int originZ,
                                     int[] heights, byte[] blocks) {
        final int region = 48;
        int firstX = floorDiv(originX - 76, region);
        int lastX = floorDiv(originX + CHUNK_SIZE + 76, region);
        int firstZ = floorDiv(originZ - 76, region);
        int lastZ = floorDiv(originZ + CHUNK_SIZE + 76, region);
        for (int rz = firstZ; rz <= lastZ; rz++) {
            for (int rx = firstX; rx <= lastX; rx++) {
                int h = hash(seed ^ 0x6A09E667, rx, 0, rz);
                // A little more common than vanilla's 1/50 chunk roll because
                // MeCraft's playable world is streamed in a much smaller area.
                if ((h & 7) != 0) continue;
                int x = rx * region + 4 + ((h >>> 4) & 39);
                int z = rz * region + 4 + ((h >>> 11) & 39);
                int surface = surfaceHeight(seed, x, z);
                int depth = 13 + ((h >>> 19) & 15);
                int y = surface - (depth >> 1);
                int dirX = ((h >>> 2) & 3) - 1;
                int dirZ = ((h >>> 8) & 3) - 1;
                if (dirX == 0 && dirZ == 0) dirX = 1;
                int length = 40 + ((h >>> 25) & 31);
                int pitch = ((h >>> 15) & 3) - 1;
                for (int step = 0; step < length; step++) {
                    int wobble = hash(seed ^ h, step, rx, rz);
                    if ((step & 3) == 0) {
                        dirX += ((wobble >>> 3) & 1) - ((wobble >>> 5) & 1);
                        dirZ += ((wobble >>> 7) & 1) - ((wobble >>> 9) & 1);
                        if (dirX > 1) dirX = 1;
                        if (dirX < -1) dirX = -1;
                        if (dirZ > 1) dirZ = 1;
                        if (dirZ < -1) dirZ = -1;
                        pitch += ((wobble >>> 12) & 1) - ((wobble >>> 14) & 1);
                        if (pitch > 1) pitch = 1;
                        if (pitch < -1) pitch = -1;
                    }
                    x += dirX;
                    z += dirZ;
                    y += pitch;
                    if (y < 9) { y = 9; pitch = 1; }
                    if (y > WORLD_HEIGHT - 18) { y = WORLD_HEIGHT - 18; pitch = -1; }

                    int profile = step < length / 2 ? step : length - step;
                    int width = 2 + profile * 2 / (length / 2);
                    if (width > 4) width = 4;
                    int verticalRadius = depth;
                    // Keep the ravine's vertical path. The previous code
                    // recalculated centerY from the surface on every step,
                    // discarding the accumulated pitch stored in y.
                    carveRavineSlice(originX, originZ, heights, blocks,
                                     x, y, z, width, verticalRadius);
                }
            }
        }
    }

    private static void carveRavineSlice(int originX, int originZ, int[] heights,
                                         byte[] blocks, int cx, int cy, int cz,
                                         int radius, int verticalRadius) {
        int minX = Math.max(originX, cx - radius);
        int maxX = Math.min(originX + CHUNK_SIZE - 1, cx + radius);
        int minZ = Math.max(originZ, cz - radius);
        int maxZ = Math.min(originZ + CHUNK_SIZE - 1, cz + radius);
        int low = Math.max(2, cy - verticalRadius);
        int high = Math.min(WORLD_HEIGHT - 2, cy + verticalRadius);
        int radiusSquared = radius * radius;
        int verticalSquared = verticalRadius * verticalRadius;

        // This carver is clipped below each column's surface (height - 1),
        // while generated ocean water exists only above the surface. A per-slice
        // water scan here could never find water and only repeated voxel reads.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int dx = x - cx, dz = z - cz;
                int horizontal = dx * dx + dz * dz;
                if (horizontal > radiusSquared) continue;
                int column = (z - originZ) * CHUNK_SIZE + x - originX;
                int ceiling = heights[column] - 1;
                int top = Math.min(high, ceiling);
                for (int y = low; y <= top; y++) {
                    int dy = y - cy;
                    if (horizontal * verticalSquared + dy * dy * radiusSquared >
                        radiusSquared * verticalSquared) continue;
                    int at = column * WORLD_HEIGHT + y;
                    byte block = blocks[at];
                    if (block == Block.STONE || block == Block.DIRT ||
                        block == Block.GRASS || block == Block.COAL ||
                        block == Block.IRON || block == Block.GOLD_ORE ||
                        block == Block.REDSTONE_ORE || block == Block.DIAMOND_ORE) {
                        // Ores inside the ravine volume are excavated too;
                        // ores just outside it remain embedded in the walls.
                        blocks[at] = y < 5 ? Block.LAVA : Block.AIR;
                    }
                }
            }
        }
    }

    private static void carveTunnelPocket(int originX, int originZ, int[] heights,
                                          byte[] blocks, int cx, int cy, int cz,
                                          int radius, int verticalRadius) {
        int minX = Math.max(originX, cx - radius);
        int maxX = Math.min(originX + CHUNK_SIZE - 1, cx + radius);
        int minZ = Math.max(originZ, cz - radius);
        int maxZ = Math.min(originZ + CHUNK_SIZE - 1, cz + radius);
        int low = Math.max(2, cy - verticalRadius);
        int high = Math.min(WORLD_HEIGHT - 2, cy + verticalRadius);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int dx = x - cx, dz = z - cz;
                int horizontal = dx * dx + dz * dz;
                if (horizontal > radius * radius) continue;
                int column = (z - originZ) * CHUNK_SIZE + x - originX;
                int ceiling = heights[column] - 3;
                int top = Math.min(high, ceiling);
                for (int y = low; y <= top; y++) {
                    int dy = y - cy;
                    if (horizontal * verticalRadius * verticalRadius +
                        dy * dy * radius * radius >
                        radius * radius * verticalRadius * verticalRadius) continue;
                    int at = column * WORLD_HEIGHT + y;
                    byte block = blocks[at];
                    if (block == Block.STONE || block == Block.DIRT ||
                        block == Block.GRASS) {
                        if (y < 5) blocks[at] = Block.LAVA;
                        else blocks[at] = Block.AIR;
                    }
                }
            }
        }
    }

    /** One short, sparse vein per selected 8x8x8 cell for Economic worlds. */
    private static void generateEconomicOreVeins(int seed, int originX, int originZ,
                                                  byte[] blocks) {
        int firstCellX = floorDiv(originX, 8) - 1;
        int lastCellX = floorDiv(originX + CHUNK_SIZE - 1, 8) + 1;
        int firstCellZ = floorDiv(originZ, 8) - 1;
        int lastCellZ = floorDiv(originZ + CHUNK_SIZE - 1, 8) + 1;
        int lastCellY = floorDiv(WORLD_HEIGHT - 2, 8);
        for (int cy = 0; cy <= lastCellY; cy++) {
            for (int cz = firstCellZ; cz <= lastCellZ; cz++) {
                for (int cx = firstCellX; cx <= lastCellX; cx++) {
                    int h = hash(seed ^ 0x2C9277B5, cx, cy, cz);
                    int kind = h & 31;
                    if (kind < 12)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.COAL, 3, Math.min(46, WORLD_HEIGHT - 3), 2, 4, h);
                    else if (kind < 21)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.IRON, 3, Math.min(36, WORLD_HEIGHT - 3), 2, 3, h);
                    else if (kind < 26)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.GOLD_ORE, 3, Math.min(26, WORLD_HEIGHT - 3), 2, 3, h);
                    else if (kind < 30)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.REDSTONE_ORE, 2, Math.min(20, WORLD_HEIGHT - 3), 2, 3, h);
                    else
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.DIAMOND_ORE, 2, Math.min(14, WORLD_HEIGHT - 3), 2, 2, h);
                }
            }
        }
    }

    /**
     * Deterministic mineral veins. Neighboring 8-block cells are visited too,
     * so veins crossing chunk borders are generated consistently.
     */
    private static void generateOreVeins(int seed, int originX, int originZ, byte[] blocks) {
        int firstCellX = floorDiv(originX, 8) - 1;
        int lastCellX = floorDiv(originX + CHUNK_SIZE - 1, 8) + 1;
        int firstCellZ = floorDiv(originZ, 8) - 1;
        int lastCellZ = floorDiv(originZ + CHUNK_SIZE - 1, 8) + 1;
        int lastCellY = floorDiv(WORLD_HEIGHT - 2, 8);
        for (int cy = 0; cy <= lastCellY; cy++) {
            for (int cz = firstCellZ; cz <= lastCellZ; cz++) {
                for (int cx = firstCellX; cx <= lastCellX; cx++) {
                    int h = hash(seed ^ 0x2C9277B5, cx, cy, cz);
                    if ((h & 1) == 0)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.COAL, 3, Math.min(46, WORLD_HEIGHT - 3), 5, 9, h);
                    int ironHash = hash(seed ^ 0x165667B1, cx, cy, cz);
                    if ((ironHash & 3) != 0)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.IRON, 3, Math.min(38, WORLD_HEIGHT - 3), 4, 7, ironHash);
                    int goldHash = hash(seed ^ 0x7F4A7C15, cx, cy, cz);
                    if ((goldHash & 7) == 0)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.GOLD_ORE, 3, Math.min(30, WORLD_HEIGHT - 3), 3, 5, goldHash);
                    int redHash = hash(seed ^ 0x94D049BB, cx, cy, cz);
                    if ((redHash & 3) == 0)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.REDSTONE_ORE, 2, Math.min(24, WORLD_HEIGHT - 3), 4, 7, redHash);
                    int diamondHash = hash(seed ^ 0x369DEA0F, cx, cy, cz);
                    if ((diamondHash & 15) == 0)
                        placeOreVein(seed, originX, originZ, blocks, cx, cy, cz,
                                     Block.DIAMOND_ORE, 2, Math.min(20, WORLD_HEIGHT - 3), 2, 4, diamondHash);
                }
            }
        }
    }

    private static void placeOreVein(int seed, int originX, int originZ, byte[] blocks,
                                    int cellX, int cellY, int cellZ, byte ore,
                                    int minY, int maxY, int minLength, int maxLength, int h) {
        if (maxY < minY) return;
        int x = cellX * 8 + ((h >>> 4) & 7);
        int z = cellZ * 8 + ((h >>> 12) & 7);
        int y = minY + ((h >>> 20) & 0x7FFFFFFF) % (maxY - minY + 1);
        int length = minLength + ((h >>> 27) & 0x7FFFFFFF) % (maxLength - minLength + 1);
        for (int step = 0; step < length; step++) {
            if (x >= originX && x < originX + CHUNK_SIZE &&
                z >= originZ && z < originZ + CHUNK_SIZE &&
                y > 1 && y < WORLD_HEIGHT - 1) {
                int column = (z - originZ) * CHUNK_SIZE + x - originX;
                int at = column * WORLD_HEIGHT + y;
                if (blocks[at] == Block.STONE) blocks[at] = ore;
            }
            int wobble = hash(seed ^ h, step, cellY, cellX + cellZ);
            x += ((wobble >>> 2) & 3) - 1;
            z += ((wobble >>> 7) & 3) - 1;
            y += ((wobble >>> 12) & 3) - 1;
            int cellMinY = Math.max(minY, cellY * 8 - 2);
            int cellMaxY = Math.min(maxY, cellY * 8 + 9);
            if (y < cellMinY) y = cellMinY;
            if (y > cellMaxY) y = cellMaxY;
        }
    }

    private static void decorate(int seed, int originX, int originZ, int[] heights,
                                 byte[] biomes, byte[] blocks, boolean economic) {
        VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.decorate: calculando regiones de decoración");
        int firstCellX = floorDiv(originX - 2 - 4, 9);
        int lastCellX = floorDiv(originX + CHUNK_SIZE + 1 - 4, 9);
        int firstCellZ = floorDiv(originZ - 2 - 4, 9);
        int lastCellZ = floorDiv(originZ + CHUNK_SIZE + 1 - 4, 9);
        for (int rz = firstCellZ; rz <= lastCellZ; rz++) {
            for (int rx = firstCellX; rx <= lastCellX; rx++) {
                int tx = rx * 9 + 4, tz = rz * 9 + 4;
                int biome = biomeAt(seed, tx, tz);
                if (biome == OCEAN) continue;
                int treeHash = hash(seed ^ 0x16A5B, rx, 0, rz);
                int chance = economic ?
                    (biome == JUNGLE ? 1 : biome == DARK_FOREST ? 1 : biome == PLAINS ? 2 : 0) :
                    (biome == JUNGLE ? 3 : biome == DARK_FOREST ? 2 : biome == PLAINS ? 7 : 0);
                if (chance == 0 || (treeHash & 15) >= chance) continue;
                int ty = economic ? economicSurfaceHeight(seed, tx, tz, biome) :
                    surfaceHeight(seed, tx, tz, biome);
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
                int flowerMask = economic ? (biome == PLAINS ? 31 : 127) :
                    (biome == PLAINS ? 7 : 63);
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
