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
            VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generate: procesando fila z=" + z + " del chunk " + chunkX + "," + chunkZ);
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
            yieldGenerationSlice();
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

    /**
     * Resumable streamed-chunk generator. The expensive column fill advances
     * one Z row per step; later deterministic passes are separate steps.
     * The worker can yield between steps without exposing a partial chunk.
     */
    static final class GenerationTask {
        private final int seed, chunkX, chunkZ, originX, originZ;
        private final byte[] blocks, biomes;
        private final int[] heights;
        private final boolean economic, nether;
        private int row;
        private int phase;

        GenerationTask(int seed, int chunkX, int chunkZ, byte[] blocks,
                       int[] heights, byte[] biomes, boolean economic, boolean nether) {
            this.seed = seed;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.originX = chunkX * CHUNK_SIZE;
            this.originZ = chunkZ * CHUNK_SIZE;
            this.blocks = blocks;
            this.heights = heights;
            this.biomes = biomes;
            this.economic = economic;
            this.nether = nether;
        }

        /** Returns true only after the complete chunk is ready for publication. */
        boolean step() {
            if (phase == 0 && nether && row < CHUNK_SIZE) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.GenerationTask: Nether fila " + row);
                generateNetherRow(seed, originX, originZ, row, blocks, heights, economic);
                row++;
                if (row == CHUNK_SIZE) phase = 5;
                return false;
            }
            if (phase == 0 && row < CHUNK_SIZE) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.GenerationTask: fila " + row +
                        " del chunk " + chunkX + "," + chunkZ);
                int worldZ = originZ + row;
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    int worldX = originX + x;
                    int column = row * CHUNK_SIZE + x;
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
                row++;
                if (row == CHUNK_SIZE) phase = 1;
                return false;
            }
            if (phase == 1) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.GenerationTask: vetas minerales");
                if (economic) generateEconomicOreVeins(seed, originX, originZ, blocks);
                else generateOreVeins(seed, originX, originZ, blocks);
                phase = 2;
                return false;
            }
            if (phase == 2) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.GenerationTask: cuevas");
                if (economic) carveEconomicCaves(seed, originX, originZ, heights, blocks);
                else carveCaves(seed, originX, originZ, heights, blocks);
                phase = 3;
                return false;
            }
            if (phase == 3) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.GenerationTask: ravines");
                if (economic) carveEconomicRavines(seed, originX, originZ, heights, blocks);
                else carveRavines(seed, originX, originZ, heights, blocks);
                phase = 4;
                return false;
            }
            if (phase == 4) {
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.GenerationTask: decoración");
                decorate(seed, originX, originZ, heights, biomes, blocks, economic);
                phase = 5;
                return false;
            }
            return phase == 5;
        }
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
            VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.generateNether: fila z=" + z);
            generateNetherRow(seed, originX, originZ, z, blocks, floorHeights, economic);
            yieldGenerationSlice();
        }
    }

    /** Generates one Nether row so streamed tasks can resume between rows. */
    private static void generateNetherRow(int seed, int originX, int originZ, int z,
                                          byte[] blocks, int[] floorHeights, boolean economic) {
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

    /**
     * Cooperative pause between terrain rows. The worker stays low-priority,
     * and this gives the game/render threads regular CPU time on slow devices.
     * The chunk is still published atomically only after every terrain pass.
     */
    private static void yieldGenerationSlice() {
        // Only throttle the low-priority streaming worker. Initial world creation
        // runs synchronously and must not gain artificial loading delays.
        if (Thread.currentThread().getPriority() != Thread.MIN_PRIORITY) return;
        try {
            Thread.sleep(1);
        } catch (InterruptedException ignored) {
            // A short pause is advisory; keep deterministic generation intact.
        }
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

    private static synchronized int lattice(int seed, int x, int z) {
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
        
            yieldGenerationSlice();}
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
        
            yieldGenerationSlice();}
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
        
            yieldGenerationSlice();}
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
        
            yieldGenerationSlice();}
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

        // Like the original generator, don't open a ravine through an ocean
        // column; otherwise the fissure would immediately fill with water.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                // Vanilla checks the boundary of the carving box for water;
                // scanning only its rim avoids a full 3D pre-pass on every step.
                if (x != minX && x != maxX && z != minZ && z != maxZ) continue;
                int column = (z - originZ) * CHUNK_SIZE + x - originX;
                int top = Math.min(high, heights[column] - 1);
                int dx = x - cx, dz = z - cz;
                if (dx * dx + dz * dz > radiusSquared) continue;
                for (int y = top; y >= low; y--) {
                    if (blocks[column * WORLD_HEIGHT + y] == Block.WATER) return;
                }
            }
        }

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
        
            yieldGenerationSlice();
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
            yieldGenerationSlice();
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
                VoxelWorld.setGenerationDiagnosticStage("InfdevTerrain.decorate: revisando árbol rx=" + rx + ", rz=" + rz);
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
        
            yieldGenerationSlice();}

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
