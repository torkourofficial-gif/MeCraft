package mecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStoreException;

/** Infinite, seed-generated terrain with an editable saved starting area. */
final class VoxelWorld {
    private static String generationDiagnosticStage = "generación sin iniciar";

    static void setGenerationDiagnosticStage(String stage) {
        generationDiagnosticStage = stage;
    }

    static String getGenerationDiagnosticStage() {
        return generationDiagnosticStage;
    }

    static final int WIDTH = 48;
    static final int HEIGHT = 64;
    static final int DEPTH = 48;
    static final int SEA_LEVEL = 29;
    static final int CHUNK_XZ = 16;
    static final int CHUNK_Y = 8;
    static final int CHUNKS_X = WIDTH / CHUNK_XZ;
    static final int CHUNKS_Y = HEIGHT / CHUNK_Y;
    static final int CHUNKS_Z = DEPTH / CHUNK_XZ;
    private static final int BLOCK_COUNT = WIDTH * HEIGHT * DEPTH;
    int worldSize = SIZE_INFINITE;
    int worldType = TYPE_STANDARD;
    int difficulty = DIFFICULTY_NORMAL;
    static boolean economicModeEnabled;
    boolean economicMode;
    // Minecraft advances one world-time tick per game tick: 24,000 ticks at
    // 20 ticks/second gives the original 20-minute day/night cycle.
    private static final int DAY_TICKS_PER_UPDATE = 1;
    private static final int MCF6_BLOCK_COUNT = 15;
    private static final int MCF6_ITEM_COUNT = 45;
    private static final int MCF7_ITEM_COUNT = 47;
    private static final int MCF8_ITEM_COUNT = 48;
    private static final int MCF9_ITEM_COUNT = 51;
    private static final int PRE_ALPHA_BLOCK_COUNT = 81;
    private static final int ALPHA_ITEM_SAVE_MAGIC = 0x4D434645; // MCFE
    private static final int DEEP_WORLD_SAVE_MAGIC = 0x4D434646; // MCFF
    private static final int CONFIG_WORLD_SAVE_MAGIC = 0x4D434647; // MCFG
    private static final int DIFFICULTY_WORLD_SAVE_MAGIC = 0x4D434648; // MCFH
    static final int SIZE_INFINITE = 0;
    static final int SIZE_OLD = 1;
    static final int TYPE_STANDARD = 0;
    static final int TYPE_FLAT = 1;
    static final int TYPE_ECONOMIC = 2;
    static final int DIFFICULTY_PEACEFUL = 0;
    static final int DIFFICULTY_EASY = 1;
    static final int DIFFICULTY_NORMAL = 2;
    static final int DIFFICULTY_HARD = 3;
    private static final int INFDEV_EDIT_MAGIC = 0x49464431;
    private static final int INFDEV_EDIT_POINTER_MAGIC = 0x49465031;
    private static final int INFDEV_EDIT_PAGE_MAGIC = 0x49465047;
    private static final int INFDEV_EDIT_PAGE_MAGIC_V2 = 0x49464432;
    private static final int DIMENSION_STORE_MAGIC = 0x4D43444D; // MCDM
    private static final int PROCEDURAL_CACHE_SAVE_MAGIC = 0x4D434350; // MCCP
    private static final int DIMENSION_PAGE_MAGIC = 0x4D434450; // MCDP
    private static final int DIMENSION_PAGE_BYTES = 16000;
    private static final int INFDEV_EDITS_PER_PAGE = 128;
    static final int DAY_LENGTH = 24000;

    byte[] blocks = new byte[BLOCK_COUNT];
    private byte[] waterLevels = new byte[BLOCK_COUNT];
    private byte[] otherDimensionBlocks;
    private byte[] otherDimensionWaterLevels;
    private boolean otherDimensionIsNether;
    // Local reusable mask: TNT blast bounds are at most 19x19x19 blocks (radius 4).
    private static final int EXPLOSION_MASK_SIZE = 20;
    private static final int EXPLOSION_MASK_AREA = EXPLOSION_MASK_SIZE * EXPLOSION_MASK_SIZE;
    private final byte[] explosionBits = new byte[(EXPLOSION_MASK_SIZE * EXPLOSION_MASK_AREA + 7) / 8];
    private static final int FLUID_QUEUE_SIZE = 2048;
    private final int[] fluidQueue = new int[FLUID_QUEUE_SIZE];
    private final byte[] fluidQueued = new byte[(BLOCK_COUNT + 7) / 8];
    // Power-of-two ring cache; eight chunks reduce regeneration while meshing neighbors.
    private static final int PROCEDURAL_CACHE_SIZE = 8;
    private final byte[][] proceduralChunks =
            new byte[PROCEDURAL_CACHE_SIZE][InfdevTerrain.CHUNK_VOLUME];
    private final int[] proceduralChunkX = new int[PROCEDURAL_CACHE_SIZE];
    private final int[] proceduralChunkZ = new int[PROCEDURAL_CACHE_SIZE];
    private final int[] proceduralHeights = new int[InfdevTerrain.CHUNK_SIZE * InfdevTerrain.CHUNK_SIZE];
    private final byte[] proceduralBiomes = new byte[InfdevTerrain.CHUNK_SIZE * InfdevTerrain.CHUNK_SIZE];
    // Background terrain generation uses private scratch buffers, never scene objects.
    private byte[] proceduralWorkerBuffer = new byte[InfdevTerrain.CHUNK_VOLUME];
    private final int[] proceduralWorkerHeights = new int[InfdevTerrain.CHUNK_SIZE * InfdevTerrain.CHUNK_SIZE];
    private final byte[] proceduralWorkerBiomes = new byte[InfdevTerrain.CHUNK_SIZE * InfdevTerrain.CHUNK_SIZE];
    private final int[] proceduralQueueX = new int[PROCEDURAL_CACHE_SIZE];
    private final int[] proceduralQueueZ = new int[PROCEDURAL_CACHE_SIZE];
    private final boolean[] proceduralQueueNether = new boolean[PROCEDURAL_CACHE_SIZE];
    private int proceduralQueueCount;
    private int proceduralQueueFocusX = Integer.MIN_VALUE;
    private int proceduralQueueFocusZ = Integer.MIN_VALUE;
    private boolean proceduralQueueFocusNether;
    private int proceduralGenerationEpoch;
    private boolean proceduralWorkerRunning;
    private boolean proceduralWorkerBusy;
    private int proceduralWorkerX, proceduralWorkerZ;
    private boolean proceduralWorkerNether;
    private final int[] proceduralChunkAge = new int[PROCEDURAL_CACHE_SIZE];
    private int proceduralCacheClock;
    private int proceduralChunksGenerated;
    private long lastProceduralGenerationMillis;
    private int[] editX = new int[128];
    private int[] editY = new int[128];
    private int[] editZ = new int[128];
    private byte[] editId = new byte[128];
    private byte[] editMeta = new byte[128];
    private boolean[] editNether = new boolean[128];
    private int[] editLookup = new int[256];
    private int editCount;
    private int editGeneration;
    final int[] inventory = new int[ItemCatalog.COUNT];
    final int[] itemDamage = new int[ItemCatalog.COUNT];
    final int[] hotbar = new int[] {
        Block.DIRT, Block.STONE, Block.LOG, Block.SAND, Block.PLANKS,
        Block.LEAVES, Block.COAL, Block.IRON, Block.TORCH
    };
    int seed;
    int mode;
    String worldName = "Mundo";
    boolean nether;
    String saveStoreName;
    int selectedSlot;
    int health = 20;
    int dayTime;
    float playerX;
    float playerY;
    float playerZ;
    float yaw;
    int revision;
    byte changedOldId;
    int changedX;
    int changedY;
    int changedZ;
    boolean fullRebuild;
    private int explosionRandom = 0x51F15E;
    private int fluidQueueHead;
    private int fluidQueueTail;
    private int fluidQueueCount;
    private int fluidTickDelay;
    private int lavaTickCounter;
    private int fluidDirection;

    void tickTime() {
        dayTime += DAY_TICKS_PER_UPDATE;
        if (dayTime >= DAY_LENGTH) dayTime -= DAY_LENGTH;
    }

    /** Indev's celestial curve reaches darkness after sunset and dawn near tick 23000. */
    boolean isNight() {
        return dayTime >= 13000 && dayTime < 23000;
    }

    VoxelWorld(int seed, int mode) { this(seed, mode, SIZE_INFINITE, TYPE_STANDARD); }

    VoxelWorld(int seed, int mode, int worldSize, int worldType) {
        this(seed, mode, worldSize, worldType, true);
    }

    /** Loading a save restores its voxel arrays; do not generate terrain first. */
    private VoxelWorld(int seed, int mode, int worldSize, int worldType,
                       boolean generateInitialTerrain) {
        setGenerationDiagnosticStage("VoxelWorld: asignando semilla");
        this.seed = seed;
        setGenerationDiagnosticStage("VoxelWorld: asignando modo");
        this.mode = mode;
        this.worldSize = worldSize == SIZE_OLD ? SIZE_OLD : SIZE_INFINITE;
        this.worldType = worldType == TYPE_FLAT ? TYPE_FLAT :
                (worldType == TYPE_ECONOMIC ? TYPE_ECONOMIC : TYPE_STANDARD);
        this.economicMode = economicModeEnabled || this.worldType == TYPE_ECONOMIC;
        setGenerationDiagnosticStage("VoxelWorld: inicializando caché procedural");
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) {
            setGenerationDiagnosticStage("VoxelWorld: inicializando caché [" + i + "]");
            proceduralChunkX[i] = Integer.MIN_VALUE;
            proceduralChunkZ[i] = Integer.MIN_VALUE;
        }
        if (generateInitialTerrain) {
            setGenerationDiagnosticStage("VoxelWorld: entrando en generate()");
            generate();
            setGenerationDiagnosticStage("VoxelWorld: generate() terminó; posicionando jugador");
        }
    }

    private int index(int x, int y, int z) {
        return (y * DEPTH + z) * WIDTH + x;
    }

    private boolean inside(int x, int z) {
        return x >= 0 && x < WIDTH && z >= 0 && z < DEPTH;
    }

    private boolean outsideWorld(int x, int z) {
        return worldSize == SIZE_OLD && (x < 0 || x >= 240 || z < 0 || z >= 240);
    }

    private int floorChunk(int value) {
        int result = value / CHUNK_XZ;
        return value < 0 && value % CHUNK_XZ != 0 ? result - 1 : result;
    }

    /** Locate a resident procedural chunk in the eight-slot cache. */
    private int findProceduralChunk(int cx, int cz) {
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++)
            if (proceduralChunkX[i] == cx && proceduralChunkZ[i] == cz) return i;
        return -1;
    }

    /** Choose an empty slot or the least recently used resident chunk. */
    private int chooseProceduralSlot() {
        int oldest = 0;
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) {
            if (proceduralChunkX[i] == Integer.MIN_VALUE) return i;
            if (proceduralChunkAge[i] < proceduralChunkAge[oldest]) oldest = i;
        }
        return oldest;
    }

    private void touchProceduralSlot(int slot) {
        proceduralCacheClock++;
        if (proceduralCacheClock == Integer.MAX_VALUE) {
            for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) proceduralChunkAge[i] >>= 1;
            proceduralCacheClock = 1;
        }
        proceduralChunkAge[slot] = proceduralCacheClock;
    }

    private byte proceduralBlock(int x, int y, int z) {
        int cx = floorChunk(x), cz = floorChunk(z);
        synchronized (this) {
            int cache = findProceduralChunk(cx, cz);
            if (cache >= 0) {
                touchProceduralSlot(cache);
                int lx = x - cx * CHUNK_XZ, lz = z - cz * CHUNK_XZ;
                return proceduralChunks[cache][(lz * CHUNK_XZ + lx) * HEIGHT + y];
            }
        }
        // Never generate terrain on the caller thread. A missing sector is
        // requested from the low-priority worker and remains temporarily empty.
        enqueueProceduralChunk(cx, cz, true);
        return Block.AIR;
    }

    /**
     * Queue the current chunk and its four face-neighbors. Retain nearby work
     * when the focus moves by one chunk so already-useful requests can finish.
     */
    void requestProceduralChunk(int cx, int cz) {
        synchronized (this) {
            if (proceduralQueueFocusX != cx || proceduralQueueFocusZ != cz ||
                proceduralQueueFocusNether != nether) {
                int kept = 0;
                for (int i = 0; i < proceduralQueueCount; i++) {
                    if (proceduralQueueNether[i] == nether &&
                        Math.abs(proceduralQueueX[i] - cx) <= 1 &&
                        Math.abs(proceduralQueueZ[i] - cz) <= 1) {
                        proceduralQueueX[kept] = proceduralQueueX[i];
                        proceduralQueueZ[kept] = proceduralQueueZ[i];
                        proceduralQueueNether[kept] = proceduralQueueNether[i];
                        kept++;
                    }
                }
                proceduralQueueCount = kept;
                proceduralQueueFocusX = cx;
                proceduralQueueFocusZ = cz;
                proceduralQueueFocusNether = nether;
            }
        }
        // Generate the visible chunk first, then its four neighbors.
        enqueueProceduralChunk(cx, cz, true);
        enqueueProceduralChunk(cx - 1, cz, false);
        enqueueProceduralChunk(cx + 1, cz, false);
        enqueueProceduralChunk(cx, cz - 1, false);
        enqueueProceduralChunk(cx, cz + 1, false);
    }

    boolean isProceduralChunkReady(int cx, int cz) {
        int x0 = cx * CHUNK_XZ, z0 = cz * CHUNK_XZ;
        if (worldSize == SIZE_OLD &&
            (outsideWorld(x0, z0) || outsideWorld(x0 + CHUNK_XZ - 1, z0 + CHUNK_XZ - 1)))
            return true;
        if (x0 >= 0 && x0 + CHUNK_XZ <= WIDTH && z0 >= 0 && z0 + CHUNK_XZ <= DEPTH)
            return true;
        synchronized (this) { return findProceduralChunk(cx, cz) >= 0; }
    }

    /** A mesh is safe to finalize only after every horizontal face-neighbor exists. */
    boolean areProceduralChunkNeighborsReady(int cx, int cz) {
        return isProceduralChunkReady(cx, cz) &&
                isProceduralChunkReady(cx - 1, cz) &&
                isProceduralChunkReady(cx + 1, cz) &&
                isProceduralChunkReady(cx, cz - 1) &&
                isProceduralChunkReady(cx, cz + 1);
    }

    private void enqueueProceduralChunk(final int cx, final int cz, boolean priority) {
        int x0 = cx * CHUNK_XZ, z0 = cz * CHUNK_XZ;
        if (worldSize == SIZE_OLD &&
            (outsideWorld(x0, z0) || outsideWorld(x0 + CHUNK_XZ - 1, z0 + CHUNK_XZ - 1))) return;
        if (x0 >= 0 && x0 + CHUNK_XZ <= WIDTH && z0 >= 0 && z0 + CHUNK_XZ <= DEPTH) return;
        synchronized (this) {
            if (findProceduralChunk(cx, cz) >= 0) return;
            if (proceduralWorkerBusy && proceduralWorkerX == cx &&
                proceduralWorkerZ == cz && proceduralWorkerNether == nether) return;
            for (int i = 0; i < proceduralQueueCount; i++) {
                if (proceduralQueueX[i] == cx && proceduralQueueZ[i] == cz &&
                    proceduralQueueNether[i] == nether) {
                    // A queued neighbor may become the new focus. Promote it
                    // instead of leaving urgent work behind older prefetches.
                    if (priority && i > 0) {
                        for (int j = i; j > 0; j--) {
                            proceduralQueueX[j] = proceduralQueueX[j - 1];
                            proceduralQueueZ[j] = proceduralQueueZ[j - 1];
                            proceduralQueueNether[j] = proceduralQueueNether[j - 1];
                        }
                        proceduralQueueX[0] = cx;
                        proceduralQueueZ[0] = cz;
                        proceduralQueueNether[0] = nether;
                    }
                    return;
                }
            }
            if (priority) {
                if (proceduralQueueCount == proceduralQueueX.length) proceduralQueueCount--;
                for (int i = proceduralQueueCount; i > 0; i--) {
                    proceduralQueueX[i] = proceduralQueueX[i - 1];
                    proceduralQueueZ[i] = proceduralQueueZ[i - 1];
                    proceduralQueueNether[i] = proceduralQueueNether[i - 1];
                }
                proceduralQueueX[0] = cx;
                proceduralQueueZ[0] = cz;
                proceduralQueueNether[0] = nether;
                proceduralQueueCount++;
            } else {
                if (proceduralQueueCount == proceduralQueueX.length) return;
                proceduralQueueX[proceduralQueueCount] = cx;
                proceduralQueueZ[proceduralQueueCount] = cz;
                proceduralQueueNether[proceduralQueueCount] = nether;
                proceduralQueueCount++;
            }
            if (!proceduralWorkerRunning) {
                proceduralWorkerRunning = true;
                Thread worker = new Thread(new Runnable() {
                    public void run() { runProceduralWorker(); }
                });
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.start();
            } else {
                // Wake the existing low-priority worker instead of creating
                // another thread for every short burst of streamed chunks.
                notifyAll();
            }
        }
    }

    private synchronized boolean isProceduralGenerationCurrent(int epoch, boolean dimension) {
        return epoch == proceduralGenerationEpoch && dimension == nether;
    }

    private void runProceduralWorker() {
        while (true) {
            int cx, cz;
            boolean dimension;
            int epoch;
            synchronized (this) {
                // Keep one worker warm briefly between adjacent chunk requests.
                // A bounded idle wait avoids both per-chunk thread churn and a
                // permanently sleeping thread when a world is no longer streamed.
                while (proceduralQueueCount == 0) {
                    try {
                        wait(500L);
                    } catch (InterruptedException ignored) {
                    }
                    if (proceduralQueueCount == 0) {
                        proceduralWorkerRunning = false;
                        return;
                    }
                }
                cx = proceduralQueueX[0]; cz = proceduralQueueZ[0];
                dimension = proceduralQueueNether[0];
                proceduralWorkerBusy = true;
                proceduralWorkerX = cx; proceduralWorkerZ = cz;
                proceduralWorkerNether = dimension;
                for (int i = 1; i < proceduralQueueCount; i++) {
                    proceduralQueueX[i - 1] = proceduralQueueX[i];
                    proceduralQueueZ[i - 1] = proceduralQueueZ[i];
                    proceduralQueueNether[i - 1] = proceduralQueueNether[i];
                }
                proceduralQueueCount--;
                epoch = proceduralGenerationEpoch;
            }
            if (dimension != nether) {
                synchronized (this) { proceduralWorkerBusy = false; }
                continue;
            }
            long generationStarted = System.currentTimeMillis();
            try {
                boolean completed;
                if (worldType == TYPE_FLAT && !dimension) {
                    generateFlatChunkInSlices(proceduralWorkerBuffer, proceduralWorkerHeights,
                                              proceduralWorkerBiomes);
                    completed = true;
                } else {
                    InfdevTerrain.GenerationTask task = new InfdevTerrain.GenerationTask(
                            seed, cx, cz, proceduralWorkerBuffer, proceduralWorkerHeights,
                            proceduralWorkerBiomes, economicMode, dimension);
                    completed = false;
                    while (isProceduralGenerationCurrent(epoch, dimension)) {
                        if (task.step()) {
                            completed = true;
                            break;
                        }
                        Thread.yield();
                    }
                }
                if (!completed) {
                    synchronized (this) { proceduralWorkerBusy = false; }
                    continue;
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    proceduralWorkerBusy = false;
                    proceduralWorkerRunning = false;
                    proceduralQueueCount = 0;
                }
                setGenerationDiagnosticStage("VoxelWorld: falló la generación procedural en segundo plano");
                return;
            }
            synchronized (this) {
                proceduralWorkerBusy = false;
                if (epoch != proceduralGenerationEpoch || dimension != nether) continue;
                int slot = findProceduralChunk(cx, cz);
                if (slot < 0) slot = chooseProceduralSlot();
                // Hand the completed array to the cache and recycle the
                // evicted slot's array as the next worker buffer. No 16 KiB
                // copy is needed for every generated chunk.
                byte[] recycled = proceduralChunks[slot];
                proceduralChunks[slot] = proceduralWorkerBuffer;
                proceduralWorkerBuffer = recycled;
                proceduralChunkX[slot] = cx;
                proceduralChunkZ[slot] = cz;
                touchProceduralSlot(slot);

                proceduralChunksGenerated++;
                lastProceduralGenerationMillis =
                        System.currentTimeMillis() - generationStarted;
            }
        }
    }

    /** Debug-only snapshot; never called from the gameplay or rendering hot path. */
    synchronized String getStreamingDiagnostics() {
        int resident = 0;
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++)
            if (proceduralChunkX[i] != Integer.MIN_VALUE) resident++;
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long used = total - runtime.freeMemory();
        return "Heap " + (used / 1024L) + "/" + (total / 1024L) +
                "K C" + resident + "/" + PROCEDURAL_CACHE_SIZE +
                " Q" + proceduralQueueCount +
                " G" + proceduralChunksGenerated +
                " " + lastProceduralGenerationMillis + "ms";
    }

    private int findEdit(int x, int y, int z) {
        return findEdit(x, y, z, nether);
    }

    private int findEdit(int x, int y, int z, boolean dimension) {
        int hash = editHash(x, y, z, dimension);
        int slot = hash & (editLookup.length - 1);
        for (int probe = 0; probe < editLookup.length; probe++) {
            int value = editLookup[slot];
            if (value == 0) return -1;
            int i = value - 1;
            if (editX[i] == x && editY[i] == y && editZ[i] == z &&
                editNether[i] == dimension) return i;
            slot = (slot + 1) & (editLookup.length - 1);
        }
        return -1;
    }

    private int ensureEdit(int x, int y, int z) {
        return ensureEdit(x, y, z, nether);
    }

    private int ensureEdit(int x, int y, int z, boolean dimension) {
        int i = findEdit(x, y, z, dimension);
        if (i >= 0) return i;
        if (editCount == editX.length) growEditArrays();
        if ((editCount + 1) * 2 > editLookup.length) rehashEdits(editLookup.length * 2);
        i = editCount++;
        editX[i] = x; editY[i] = y; editZ[i] = z;
        editNether[i] = dimension;
        int slot = editHash(x, y, z, dimension) & (editLookup.length - 1);
        while (editLookup[slot] != 0) slot = (slot + 1) & (editLookup.length - 1);
        editLookup[slot] = i + 1;
        return i;
    }

    private void growEditArrays() {
        int size = editX.length * 2;
        int[] nx = new int[size], ny = new int[size], nz = new int[size];
        byte[] ni = new byte[size], nm = new byte[size];
        boolean[] nd = new boolean[size];
        System.arraycopy(editX, 0, nx, 0, editCount);
        System.arraycopy(editY, 0, ny, 0, editCount);
        System.arraycopy(editZ, 0, nz, 0, editCount);
        System.arraycopy(editId, 0, ni, 0, editCount);
        System.arraycopy(editMeta, 0, nm, 0, editCount);
        System.arraycopy(editNether, 0, nd, 0, editCount);
        editX = nx; editY = ny; editZ = nz; editId = ni; editMeta = nm; editNether = nd;
    }

    private void rehashEdits(int size) {
        int[] lookup = new int[size];
        for (int i = 0; i < editCount; i++) {
            int slot = editHash(editX[i], editY[i], editZ[i], editNether[i]) & (size - 1);
            while (lookup[slot] != 0) slot = (slot + 1) & (size - 1);
            lookup[slot] = i + 1;
        }
        editLookup = lookup;
    }

    private int editHash(int x, int y, int z, boolean inNether) {
        int hash = x * 73428767 ^ y * 912931 ^ z * 42317861;
        return inNether ? hash ^ 0x6D2B79F5 : hash;
    }

    private int metadataAt(int x, int y, int z) {
        if (inside(x, z)) return waterLevels[index(x, y, z)] & 255;
        int i = findEdit(x, y, z);
        return i < 0 ? 0 : editMeta[i] & 255;
    }

    private void setMetadata(int x, int y, int z, int metadata) {
        if (inside(x, z)) waterLevels[index(x, y, z)] = (byte)metadata;
        else {
            int i = ensureEdit(x, y, z);
            if (i >= 0) editMeta[i] = (byte)metadata;
        }
    }

    /** Four horizontal chest facings: 0=-Z, 1=+X, 2=+Z, 3=-X. */
    int chestFacing(int x, int y, int z) {
        if (get(x, y, z) != Block.CHEST) return 0;
        return metadataAt(x, y, z) & 3;
    }

    void setChestFacing(int x, int y, int z, int facing) {
        if (get(x, y, z) != Block.CHEST) return;
        int next = facing & 3;
        if (chestFacing(x, y, z) == next) return;
        setMetadata(x, y, z, next);
        changedOldId = Block.CHEST;
        changedX = x;
        changedY = y;
        changedZ = z;
        revision++;
    }

    /** Alpha 1.2.6 rail orientation metadata (0-9); shares the saved metadata byte. */
    int railMetadata(int x, int y, int z) {
        if (get(x, y, z) != Block.RAIL) return 0;
        return metadataAt(x, y, z) & 15;
    }

    void setRailMetadata(int x, int y, int z, int metadata) {
        if (get(x, y, z) != Block.RAIL) return;
        int next = metadata & 15;
        if (metadataAt(x, y, z) == next) return;
        setMetadata(x, y, z, next);
        changedOldId = Block.RAIL;
        changedX = x;
        changedY = y;
        changedZ = z;
        revision++;
    }

    byte get(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT || outsideWorld(x, z)) return Block.AIR;
        if (inside(x, z)) return blocks[index(x, y, z)];
        int edit = findEdit(x, y, z);
        return edit >= 0 ? editId[edit] : proceduralBlock(x, y, z);
    }

    /**
     * Collision lookup that never generates terrain on the physics thread.
     * An unloaded procedural sector is treated as empty for this tick: movement
     * continues while the background worker prepares the real terrain.
     */
    byte getCollision(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT || outsideWorld(x, z)) return Block.AIR;
        if (inside(x, z)) return blocks[index(x, y, z)];
        int edit = findEdit(x, y, z);
        if (edit >= 0) return editId[edit];
        int cx = floorChunk(x), cz = floorChunk(z);
        synchronized (this) {
            int cache = findProceduralChunk(cx, cz);
            if (cache >= 0) {
                touchProceduralSlot(cache);
                int lx = x - cx * CHUNK_XZ, lz = z - cz * CHUNK_XZ;
                return proceduralChunks[cache][(lz * CHUNK_XZ + lx) * HEIGHT + y];
            }
        }
        enqueueProceduralChunk(cx, cz, true);
        return Block.AIR;
    }

    /**
     * Non-blocking terrain lookup for rendering. Unlike get(), this never
     * generates a missing procedural chunk on the caller thread.
     */
    byte getLoaded(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT || outsideWorld(x, z)) return Block.AIR;
        if (inside(x, z)) return blocks[index(x, y, z)];
        int edit = findEdit(x, y, z);
        if (edit >= 0) return editId[edit];
        int cx = floorChunk(x), cz = floorChunk(z);
        synchronized (this) {
            int cache = findProceduralChunk(cx, cz);
            if (cache < 0) return Block.AIR;
            touchProceduralSlot(cache);
            int lx = x - cx * CHUNK_XZ, lz = z - cz * CHUNK_XZ;
            return proceduralChunks[cache][(lz * CHUNK_XZ + lx) * HEIGHT + y];
        }
    }

    /**
     * Copy one 16x16 horizontal layer for the chunk mesher. Resolve the
     * procedural cache slot once per layer instead of performing a synchronized
     * LRU lookup for every voxel. The destination uses z*CHUNK_XZ+x ordering.
     */
    void copyLoadedChunkLayer(int x0, int y, int z0, byte[] target) {
        if (target == null || target.length < CHUNK_XZ * CHUNK_XZ) return;
        if (y < 0 || y >= HEIGHT) {
            for (int i = 0; i < CHUNK_XZ * CHUNK_XZ; i++) target[i] = Block.AIR;
            return;
        }
        // The saved starting area is already one contiguous voxel array.
        // A complete 16x16 layer inside it needs no cache lookup, edit lookup,
        // bounds checks per voxel, or monitor: copy each row directly.
        if (x0 >= 0 && x0 + CHUNK_XZ <= WIDTH &&
            z0 >= 0 && z0 + CHUNK_XZ <= DEPTH) {
            int source = index(x0, y, z0);
            for (int z = 0; z < CHUNK_XZ; z++) {
                System.arraycopy(blocks, source, target, z * CHUNK_XZ, CHUNK_XZ);
                source += DEPTH * WIDTH;
            }
            return;
        }

        int cx = floorChunk(x0), cz = floorChunk(z0);
        synchronized (this) {
            int cache = findProceduralChunk(cx, cz);
            for (int z = 0; z < CHUNK_XZ; z++) {
                for (int x = 0; x < CHUNK_XZ; x++) {
                    int wx = x0 + x, wz = z0 + z;
                    int at = z * CHUNK_XZ + x;
                    if (outsideWorld(wx, wz)) {
                        target[at] = Block.AIR;
                    } else if (inside(wx, wz)) {
                        target[at] = blocks[index(wx, y, wz)];
                    } else {
                        int edit = findEdit(wx, y, wz);
                        if (edit >= 0) target[at] = editId[edit];
                        else if (cache >= 0) {
                            int lx = wx - cx * CHUNK_XZ;
                            int lz = wz - cz * CHUNK_XZ;
                            target[at] = proceduralChunks[cache][(lz * CHUNK_XZ + lx) * HEIGHT + y];
                        } else target[at] = Block.AIR;
                    }
                }
            }
            if (cache >= 0) touchProceduralSlot(cache);
        }
    }

    /**
     * Fast lookup for light-source discovery. Torches can only be placed as
     * world edits; outside the saved terrain, avoid generating procedural
     * chunks merely to prove that a coordinate contains no torch.
     */
    byte getTorchCandidate(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT || outsideWorld(x, z)) return Block.AIR;
        if (inside(x, z)) return blocks[index(x, y, z)];
        int edit = findEdit(x, y, z);
        return edit >= 0 ? editId[edit] : Block.AIR;
    }

    /** Collect rails in one chunk without querying/generating procedural terrain. */
    int collectRailsInChunk(int chunkX, int chunkY, int chunkZ,
                            int[] outX, int[] outY, int[] outZ,
                            int[] outMetadata, int capacity) {
        if (capacity <= 0) return 0;
        int x0 = chunkX * CHUNK_XZ, y0 = chunkY * CHUNK_Y, z0 = chunkZ * CHUNK_XZ;
        int x1 = x0 + CHUNK_XZ - 1, y1 = Math.min(HEIGHT - 1, y0 + CHUNK_Y - 1);
        int z1 = z0 + CHUNK_XZ - 1;
        if (y0 < 0 || y0 >= HEIGHT) return 0;
        int count = 0;
        int minX = Math.max(0, x0), maxX = Math.min(WIDTH - 1, x1);
        int minZ = Math.max(0, z0), maxZ = Math.min(DEPTH - 1, z1);
        if (minX <= maxX && minZ <= maxZ) {
            for (int y = y0; y <= y1 && count < capacity; y++) {
                for (int z = minZ; z <= maxZ && count < capacity; z++) {
                    int at = index(minX, y, z);
                    for (int x = minX; x <= maxX && count < capacity; x++, at++) {
                        if (blocks[at] != Block.RAIL) continue;
                        outX[count] = x;
                        outY[count] = y;
                        outZ[count] = z;
                        outMetadata[count] = metadataAt(x, y, z) & 15;
                        count++;
                    }
                }
            }
        }
        for (int i = 0; i < editCount && count < capacity; i++) {
            if (editNether[i] != nether || editId[i] != Block.RAIL ||
                editX[i] < x0 || editX[i] > x1 ||
                editY[i] < y0 || editY[i] > y1 ||
                editZ[i] < z0 || editZ[i] > z1) continue;
            outX[count] = editX[i];
            outY[count] = editY[i];
            outZ[count] = editZ[i];
            outMetadata[count] = editMeta[i] & 15;
            count++;
        }
        return count;
    }

    /**
     * Enumerates nearby light sources without probing every voxel in the
     * surrounding volume. Procedural terrain cannot contain player torches;
     * those are sparse edits. Only the finite starter area needs a voxel scan
     * because it can contain naturally generated lava.
     */
    int collectTorchSources(int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ,
                            int[] outX, int[] outY, int[] outZ,
                            boolean[] outLava, int capacity) {
        int count = 0;
        int lowY = Math.max(0, minY);
        int highY = Math.min(HEIGHT - 1, maxY);
        if (lowY > highY || capacity <= 0) return 0;

        int localMinX = Math.max(0, minX);
        int localMaxX = Math.min(WIDTH - 1, maxX);
        int localMinZ = Math.max(0, minZ);
        int localMaxZ = Math.min(DEPTH - 1, maxZ);
        if (localMinX <= localMaxX && localMinZ <= localMaxZ) {
            for (int y = lowY; y <= highY && count < capacity; y++) {
                for (int z = localMinZ; z <= localMaxZ && count < capacity; z++) {
                    int at = index(localMinX, y, z);
                    for (int x = localMinX; x <= localMaxX && count < capacity; x++, at++) {
                        byte id = blocks[at];
                        if (id != Block.TORCH && id != Block.LAVA) continue;
                        outX[count] = x;
                        outY[count] = y;
                        outZ[count] = z;
                        outLava[count] = id == Block.LAVA;
                        count++;
                    }
                }
            }
        }

        // The edit table is sparse and already contains every placed torch
        // in the infinite world. Ignore edits belonging to the other dimension.
        for (int i = 0; i < editCount && count < capacity; i++) {
            if (editNether[i] != nether || editY[i] < lowY || editY[i] > highY ||
                editX[i] < minX || editX[i] > maxX ||
                editZ[i] < minZ || editZ[i] > maxZ) continue;
            byte id = editId[i];
            if (id != Block.TORCH && id != Block.LAVA) continue;
            outX[count] = editX[i];
            outY[count] = editY[i];
            outZ[count] = editZ[i];
            outLava[count] = id == Block.LAVA;
            count++;
        }
        return count;
    }

    boolean set(int x, int y, int z, byte id) {
        return setBlock(x, y, z, id, 0);
    }

    int woolColor(int x, int y, int z) {
        if (get(x, y, z) != Block.WOOL) return 0;
        return metadataAt(x, y, z) & 15;
    }

    int dropItemId(int x, int y, int z) {
        byte id = get(x, y, z);
        return id == Block.WOOL ? ItemCatalog.woolItem(woolColor(x, y, z)) : Block.dropId(id);
    }

    boolean setWool(int x, int y, int z, int color) {
        if (y <= 0 || y >= HEIGHT || color < 0 || color >= WoolColors.COUNT) return false;
        if (!inside(x, z)) {
            byte old = get(x, y, z);
            if (old != Block.WOOL && !Block.replaceable(old)) return false;
            if (old == Block.WOOL && woolColor(x, y, z) == color) return false;
            int edit = ensureEdit(x, y, z);
            if (edit < 0) return false;
            editId[edit] = Block.WOOL;
            editMeta[edit] = (byte)color;
            changedX = x; changedY = y; changedZ = z; revision++;
            return true;
        }
        int i = index(x, y, z);
        byte old = blocks[i];
        if (old != Block.WOOL && !Block.replaceable(old)) return false;
        if (old == Block.WOOL && (waterLevels[i] & 15) == color) return false;
        blocks[i] = Block.WOOL;
        waterLevels[i] = (byte)color;
        changedX = x; changedY = y; changedZ = z;
        revision++;
        scheduleFluidNeighbors(x, y, z);
        // Alpha rails are supported by the block below. Removing a support
        // block also removes any rail directly above it; this can cascade.
        if (y + 1 < HEIGHT && !Block.solid(get(x, y, z)) &&
                get(x, y + 1, z) == Block.RAIL) {
            setBlock(x, y + 1, z, Block.AIR, 0);
        }
        return true;
    }

    int doorMetadata(int x, int y, int z) {
        if (!Block.isDoor(get(x, y, z))) return 0;
        return metadataAt(x, y, z) & 15;
    }

    int doorState(int x, int y, int z) {
        int metadata = doorMetadata(x, y, z);
        return (metadata & 4) != 0 ? metadata & 3 : (metadata - 1) & 3;
    }

    boolean placeDoor(int x, int y, int z, int orientation) {
        return placeDoor(x, y, z, orientation, Block.DOOR);
    }

    boolean placeDoor(int x, int y, int z, int orientation, byte doorBlock) {
        if (!Block.isDoor(doorBlock) || y <= 0 || y + 1 >= HEIGHT ||
            !Block.solid(get(x, y - 1, z)) || !Block.replaceable(get(x, y, z)) ||
            !Block.replaceable(get(x, y + 1, z))) return false;
        if (!setBlock(x, y, z, doorBlock, 0)) return false;
        if (!setBlock(x, y + 1, z, doorBlock, 0)) {
            setBlock(x, y, z, Block.AIR, 0);
            return false;
        }
        int metadata = orientation & 7;
        setMetadata(x, y, z, metadata);
        setMetadata(x, y + 1, z, metadata | 8);
        revision++;
        changedX = x; changedY = y; changedZ = z;
        return true;
    }

    boolean toggleDoor(int x, int y, int z) {
        if (!Block.isDoor(get(x, y, z))) return false;
        int metadata = doorMetadata(x, y, z);
        if ((metadata & 8) != 0) { y--; metadata = doorMetadata(x, y, z); }
        if (!Block.isDoor(get(x, y, z)) || get(x, y + 1, z) != get(x, y, z)) return false;
        metadata ^= 4;
        setMetadata(x, y, z, metadata);
        setMetadata(x, y + 1, z, metadata | 8);
        revision++;
        changedX = x; changedY = y; changedZ = z;
        return true;
    }

    boolean removeDoor(int x, int y, int z) {
        if (!Block.isDoor(get(x, y, z))) return false;
        if ((doorMetadata(x, y, z) & 8) != 0) y--;
        byte doorBlock = get(x, y, z);
        if (!Block.isDoor(doorBlock)) return false;
        boolean hasUpper = y + 1 < HEIGHT && get(x, y + 1, z) == doorBlock;
        if (!setBlock(x, y, z, Block.AIR, 0)) return false;
        if (hasUpper && !setBlock(x, y + 1, z, Block.AIR, 0)) return false;
        return true;
    }

    boolean isSolidAt(int x, int y, int z) {
        // Old worlds have an invisible, non-editable collision wall at all four edges.
        // Keep get() returning AIR beyond the map so the barrier is never rendered.
        if (worldSize == SIZE_OLD && y >= 0 && y < HEIGHT && outsideWorld(x, z))
            return true;
        byte id = get(x, y, z);
        return Block.isDoor(id) ? (doorMetadata(x, y, z) & 4) == 0 : Block.solid(id);
    }

    /** Alpha 1.2.6 resistance-aware ray marching, using a local mask for finite and infinite worlds. */
    void explode(float x, float y, float z, float radius, DroppedItemSystem drops) {
        int minX = floorBlock(x - radius * 2.0f - 1.0f);
        int maxX = floorBlock(x + radius * 2.0f + 1.0f);
        int minY = Math.max(1, floorBlock(y - radius * 2.0f - 1.0f));
        int maxY = Math.min(HEIGHT - 1, floorBlock(y + radius * 2.0f + 1.0f));
        int minZ = floorBlock(z - radius * 2.0f - 1.0f);
        int maxZ = floorBlock(z + radius * 2.0f + 1.0f);
        int spanX = maxX - minX + 1;
        int spanY = maxY - minY + 1;
        int spanZ = maxZ - minZ + 1;
        if (spanX > EXPLOSION_MASK_SIZE || spanY > EXPLOSION_MASK_SIZE ||
            spanZ > EXPLOSION_MASK_SIZE) return;
        for (int i = 0; i < explosionBits.length; i++) explosionBits[i] = 0;

        for (int rx = 0; rx < 16; rx++) {
            for (int ry = 0; ry < 16; ry++) {
                for (int rz = 0; rz < 16; rz++) {
                    if (rx != 0 && rx != 15 && ry != 0 && ry != 15 && rz != 0 && rz != 15) continue;
                    float dx = rx / 15.0f * 2.0f - 1.0f;
                    float dy = ry / 15.0f * 2.0f - 1.0f;
                    float dz = rz / 15.0f * 2.0f - 1.0f;
                    float length = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (length == 0.0f) continue;
                    dx /= length; dy /= length; dz /= length;
                    float strength = radius * (0.7f + randomFloat() * 0.6f);
                    float px = x, py = y, pz = z;
                    while (strength > 0.0f) {
                        int bx = floorBlock(px), by = floorBlock(py), bz = floorBlock(pz);
                        byte id = get(bx, by, bz);
                        if (id != Block.AIR)
                            strength -= (explosionResistance(id) + 0.3f) * 0.3f;
                        if (strength > 0.0f && bx >= minX && bx <= maxX &&
                            by >= minY && by <= maxY && bz >= minZ && bz <= maxZ) {
                            int local = ((by - minY) * EXPLOSION_MASK_SIZE + (bz - minZ)) *
                                EXPLOSION_MASK_SIZE + (bx - minX);
                            explosionBits[local >> 3] |= (byte)(1 << (local & 7));
                        }
                        px += dx * 0.3f; py += dy * 0.3f; pz += dz * 0.3f;
                        strength -= 0.22500001f;
                    }
                }
            }
        }

        for (int by = minY; by <= maxY; by++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int bx = minX; bx <= maxX; bx++) {
                    int local = ((by - minY) * EXPLOSION_MASK_SIZE + (bz - minZ)) *
                        EXPLOSION_MASK_SIZE + (bx - minX);
                    if ((explosionBits[local >> 3] & (1 << (local & 7))) == 0) continue;
                    byte id = get(bx, by, bz);
                    if (id == Block.AIR) continue;
                    if (id == Block.TNT) {
                        // Keep the block intact if the bounded entity pool is full;
                        // never erase chain TNT unless its primed entity was created.
                        if (!drops.spawnPrimedTnt(bx, by, bz, 10 + randomInt(20))) continue;
                    } else if (Block.isDoor(id)) {
                        if (randomFloat() < 0.3f) drops.spawn(Block.dropId(id), bx, by, bz);
                        removeDoor(bx, by, bz);
                        continue;
                    } else if (Block.dropsItem(id) && randomFloat() < 0.3f) {
                        drops.spawn(dropItemId(bx, by, bz), bx, by, bz);
                    }
                    set(bx, by, bz, Block.AIR);
                    if (by + 1 < HEIGHT && Block.isDoor(get(bx, by + 1, bz)) &&
                        !Block.solid(get(bx, by, bz))) {
                        if (randomFloat() < 0.3f) drops.spawn(Block.dropId(get(bx, by + 1, bz)), bx, by + 1, bz);
                        removeDoor(bx, by + 1, bz);
                    }
                }
            }
        }
        fullRebuild = true;
    }

    private float explosionResistance(byte id) {
        // Alpha 1.2.6 blockResistance values, divided by five as in Block.func_227_a.
        if (id == Block.BEDROCK) return 3600000.0f;
        if (id == Block.OBSIDIAN) return 1200.0f;
        if (id == Block.STONE || id == Block.COBBLESTONE || id == Block.IRON)
            return 6.0f;
        if (id == Block.PLANKS || id == Block.LOG) return 3.0f;
        if (id == Block.GOLD_ORE || id == Block.DIAMOND_ORE ||
            id == Block.REDSTONE_ORE || id == Block.COAL) return 3.0f;
        if (id == Block.GOLD_BLOCK) return 3.0f;
        if (id == Block.IRON_BLOCK || id == Block.DIAMOND_BLOCK) return 5.0f;
        if (id == Block.CRAFTING_TABLE || id == Block.FURNACE) return 3.0f;
        if (id == Block.CHEST) return 2.5f;
        if (id == Block.WOOL) return 0.8f;
        if (id == Block.LEAVES || id == Block.NETHERRACK) return 0.2f;
        if (id == Block.GLASS || id == Block.GLOWSTONE) return 0.3f;
        if (id == Block.CACTUS) return 0.4f;
        if (id == Block.SNOW) return 0.1f;
        if (id == Block.GRASS || id == Block.DIRT || id == Block.SAND ||
            id == Block.GRAVEL || id == Block.FARMLAND || id == Block.CLAY_BLOCK ||
            id == Block.SOUL_SAND) return 0.5f;
        if (Block.isFluid(id)) return 100.0f;
        // Non-solid decoration (flowers, torches, rails, fire and TNT) has no blast resistance.
        if (id == Block.AIR || id == Block.TNT || id == Block.TORCH ||
            id == Block.FIRE || id == Block.RAIL || Block.isFlower(id)) return 0.0f;
        return 0.5f;
    }

    private int randomInt(int bound) {
        explosionRandom = explosionRandom * 1664525 + 1013904223;
        return (explosionRandom >>> 1) % bound;
    }

    private float randomFloat() {
        explosionRandom = explosionRandom * 1664525 + 1013904223;
        return (explosionRandom >>> 8 & 0xFFFF) / 65535.0f;
    }

    private int floorBlock(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    private boolean setBlock(int x, int y, int z, byte id, int fluidLevel) {
        if (y <= 0 || y >= HEIGHT || outsideWorld(x, z)) return false;
        if (id < 0 || id >= Block.COUNT) return false;
        byte oldId = get(x, y, z);
        if (oldId == id && (!Block.isFluid(id) || metadataAt(x, y, z) == fluidLevel)) return false;
        if (inside(x, z)) {
            int i = index(x, y, z);
            blocks[i] = id;
            waterLevels[i] = Block.isFluid(id) ? (byte)fluidLevel : 0;
        } else {
            int edit = ensureEdit(x, y, z);
            if (edit < 0) return false;
            editId[edit] = id;
            editMeta[edit] = Block.isFluid(id) ? (byte)fluidLevel : 0;
        }
        changedOldId = oldId;
        changedX = x;
        changedY = y;
        changedZ = z;
        revision++;
        scheduleFluidNeighbors(x, y, z);
        return true;
    }

    int fluidLevel(int x, int y, int z) {
        return Block.isFluid(get(x, y, z)) ? metadataAt(x, y, z) & 15 : -1;
    }

    private boolean setFlowingFluid(int x, int y, int z, byte fluid, int level) {
        if (level > 7) return false;
        byte old = get(x, y, z);
        if (old == fluid) {
            int oldLevel = metadataAt(x, y, z) & 15;
            return oldLevel > level && setBlock(x, y, z, fluid, level);
        }
        if (!Block.replaceable(old)) return false;
        return setBlock(x, y, z, fluid, level);
    }

    /** Alpha-style scheduled liquid spread: water updates quickly, lava slowly. */
    void updateFluids() {
        if (++fluidTickDelay < 5) return;
        fluidTickDelay = 0;
        lavaTickCounter += 5;
        boolean lavaDue = lavaTickCounter >= 30;
        if (lavaDue) lavaTickCounter = 0;

        // Process only the queue snapshot so newly scheduled neighbors are
        // handled on a later tick, keeping the work bounded on small devices.
        // Bound each fluid update to avoid a large saved queue monopolizing
        // a game tick on low-clock Java ME devices.
        int pending = fluidQueueCount;
        int budget = pending > 96 ? 96 : pending;
        while (budget-- > 0 && fluidQueueCount > 0) {
            int i = fluidQueue[fluidQueueHead];
            fluidQueueHead++;
            if (fluidQueueHead == FLUID_QUEUE_SIZE) fluidQueueHead = 0;
            fluidQueueCount--;
            setFluidQueued(i, false);

            byte fluid = blocks[i];
            if (!Block.isFluid(fluid)) continue;
            if (fluid == Block.LAVA && !lavaDue) {
                scheduleFluid(i);
                continue;
            }

            int x = i % WIDTH;
            int z = (i / WIDTH) % DEPTH;
            int y = i / (WIDTH * DEPTH);
            int level = waterLevels[i] & 15;

            if (fluid == Block.LAVA && touchesWater(x, y, z)) {
                setBlock(x, y, z, level == 0 ? Block.OBSIDIAN : Block.COBBLESTONE, 0);
                continue;
            }

            // Try to fall first. Crucially, only stop here if the lower block
            // actually changed: if it already contains an equal-level fluid,
            // this block must still be allowed to spread sideways.
            if (y > 1 && canFluidEnter(get(x, y - 1, z), fluid)) {
                if (setFlowingFluid(x, y - 1, z, fluid, level)) {
                    scheduleFluid(i);
                    continue;
                }
            }

            int maxSideLevel = fluid == Block.LAVA ? 4 : 7;
            if (level >= maxSideLevel) continue;

            // Rotate the first direction between updates to avoid a fixed
            // directional bias, while allowing liquid to flow over ledges.
            for (int direction = 0; direction < 4; direction++) {
                int side = (fluidDirection + direction) & 3;
                int nx = x + (side == 0 ? -1 : (side == 1 ? 1 : 0));
                int nz = z + (side == 2 ? -1 : (side == 3 ? 1 : 0));
                if (nx <= 0 || nx >= WIDTH - 1 || nz <= 0 || nz >= DEPTH - 1) continue;
                if (!canFluidEnter(get(nx, y, nz), fluid)) continue;

                // Sideways flow is allowed when the destination is supported
                // or can immediately fall. This produces proper edges and
                // waterfalls rather than requiring a solid block below.
                byte below = get(nx, y - 1, nz);
                if (below == Block.AIR && y <= 1) continue;
                if (setFlowingFluid(nx, y, nz, fluid, level + 1)) {
                    scheduleFluid(i);
                    fluidDirection = (side + 1) & 3;
                }
            }
        }
    }

    private boolean canFluidEnter(byte id, byte fluid) {
        return id == Block.AIR || Block.isFlower(id) || id == fluid;
    }

    private boolean touchesWater(int x, int y, int z) {
        return get(x - 1, y, z) == Block.WATER || get(x + 1, y, z) == Block.WATER ||
               get(x, y - 1, z) == Block.WATER || get(x, y + 1, z) == Block.WATER ||
               get(x, y, z - 1) == Block.WATER || get(x, y, z + 1) == Block.WATER;
    }

    private void scheduleFluidNeighbors(int x, int y, int z) {
        scheduleFluid(x, y, z);
        scheduleFluid(x, y - 1, z);
        scheduleFluid(x, y + 1, z);
        scheduleFluid(x - 1, y, z);
        scheduleFluid(x + 1, y, z);
        scheduleFluid(x, y, z - 1);
        scheduleFluid(x, y, z + 1);
    }

    private void scheduleFluid(int x, int y, int z) {
        if (x < 0 || x >= WIDTH || z < 0 || z >= DEPTH || y < 0 || y >= HEIGHT) return;
        scheduleFluid(index(x, y, z));
    }

    private void scheduleFluid(int i) {
        if (!Block.isFluid(blocks[i]) || isFluidQueued(i) || fluidQueueCount == FLUID_QUEUE_SIZE) return;
        fluidQueue[fluidQueueTail] = i;
        fluidQueueTail++;
        if (fluidQueueTail == FLUID_QUEUE_SIZE) fluidQueueTail = 0;
        fluidQueueCount++;
        setFluidQueued(i, true);
    }

    private boolean isFluidQueued(int i) {
        return (fluidQueued[i >> 3] & (1 << (i & 7))) != 0;
    }

    private void setFluidQueued(int i, boolean queued) {
        int byteIndex = i >> 3;
        int bit = 1 << (i & 7);
        if (queued) fluidQueued[byteIndex] = (byte)(fluidQueued[byteIndex] | bit);
        else fluidQueued[byteIndex] = (byte)(fluidQueued[byteIndex] & ~bit);
    }

    private void queueSavedFlowingFluids() {
        for (int i = 0; i < blocks.length; i++) {
            if (Block.isFluid(blocks[i]) && waterLevels[i] != 0) scheduleFluid(i);
        }
    }

    /** Flat chunks use the same bounded row scheduling as procedural terrain. */
    private void generateFlatChunkInSlices(byte[] target, int[] heights, byte[] biomes) {
        for (int z = 0; z < CHUNK_XZ; z++) {
            for (int x = 0; x < CHUNK_XZ; x++) {
                int base = (z * CHUNK_XZ + x) * HEIGHT;
                for (int y = 0; y < HEIGHT; y++)
                    target[base + y] = y == 0 ? Block.BEDROCK :
                        (y <= 3 ? Block.DIRT : (y == 4 ? Block.GRASS : Block.AIR));
                heights[z * CHUNK_XZ + x] = 4;
                biomes[z * CHUNK_XZ + x] = 0;
            }
            yieldInitialGenerationSlice();
        }
    }

    /** Cooperative scheduling point; unlike sleep, it does not impose a timer delay. */
    private void yieldInitialGenerationSlice() {
        Thread.yield();
    }

    private void generate() {
        setGenerationDiagnosticStage("VoxelWorld.generate: preparando bucle de chunks");
        for (int cz = 0; cz < CHUNKS_Z; cz++) {
            setGenerationDiagnosticStage("VoxelWorld.generate: comenzando fila de chunks cz=" + cz);
            for (int cx = 0; cx < CHUNKS_X; cx++) {
                setGenerationDiagnosticStage("VoxelWorld.generate: reservando caché para chunk cx=" + cx + ", cz=" + cz);
                int cache = chooseProceduralSlot();
                setGenerationDiagnosticStage("VoxelWorld.generate: generando chunk por tareas cx=" + cx + ", cz=" + cz);
                if (worldType == TYPE_FLAT && !nether) {
                    // Flat terrain is also filled in bounded Z rows.
                    for (int z = 0; z < CHUNK_XZ; z++) {
                        for (int x = 0; x < CHUNK_XZ; x++) {
                            int base = (z * CHUNK_XZ + x) * HEIGHT;
                            for (int y = 0; y < HEIGHT; y++)
                                proceduralChunks[cache][base + y] = y == 0 ? Block.BEDROCK :
                                    (y <= 3 ? Block.DIRT : (y == 4 ? Block.GRASS : Block.AIR));
                            proceduralHeights[z * CHUNK_XZ + x] = 4;
                            proceduralBiomes[z * CHUNK_XZ + x] = 0;
                        }
                        yieldInitialGenerationSlice();
                    }
                } else {
                    InfdevTerrain.GenerationTask initialTask = new InfdevTerrain.GenerationTask(
                            seed, cx, cz, proceduralChunks[cache], proceduralHeights,
                            proceduralBiomes, economicMode, nether);
                    while (!initialTask.step()) yieldInitialGenerationSlice();
                }
                setGenerationDiagnosticStage("VoxelWorld.generate: guardando coordenadas del chunk cx=" + cx + ", cz=" + cz);
                proceduralChunkX[cache] = cx; proceduralChunkZ[cache] = cz;
                touchProceduralSlot(cache);

                for (int z = 0; z < CHUNK_XZ; z++) {
                    setGenerationDiagnosticStage("VoxelWorld.generate: copiando chunk cx=" + cx + ", cz=" + cz + ", z=" + z);
                    for (int x = 0; x < CHUNK_XZ; x++) {
                        int wx = cx * CHUNK_XZ + x, wz = cz * CHUNK_XZ + z;
                        for (int y = 0; y < HEIGHT; y++) {
                            blocks[index(wx, y, wz)] = proceduralChunks[cache][(z * CHUNK_XZ + x) * HEIGHT + y];
                        }
                    }
                    yieldInitialGenerationSlice();
                }
            }
        }
        setGenerationDiagnosticStage("VoxelWorld.generate: asignando posición X del jugador");
        int spawnCenter = worldSize == SIZE_OLD ? 120 : WIDTH / 2;
        playerX = spawnCenter + 0.5f;
        setGenerationDiagnosticStage("VoxelWorld.generate: asignando posición Z del jugador");
        playerZ = spawnCenter + 0.5f;
        setGenerationDiagnosticStage("VoxelWorld.generate: calculando altura inicial del jugador");
        int spawnHeight = nether ? (economicMode ?
                InfdevTerrain.economicNetherFloorHeight(seed, spawnCenter, spawnCenter) :
                InfdevTerrain.netherFloorHeight(seed, spawnCenter, spawnCenter)) :
                (worldType == TYPE_FLAT ? 4 :
                 (economicMode ?
                  InfdevTerrain.economicSurfaceHeight(seed, spawnCenter, spawnCenter) :
                  InfdevTerrain.surfaceHeight(seed, spawnCenter, spawnCenter)));
        playerY = spawnHeight + 1.0f;
        setGenerationDiagnosticStage("VoxelWorld.generate: asignando yaw inicial");
        yaw = 0f;
    }

    private void generateChunk(int cx, int cz, byte[] target) {
        generateChunk(cx, cz, target, proceduralHeights, proceduralBiomes, nether);
    }

    private void generateChunk(int cx, int cz, byte[] target, int[] heights,
                               byte[] biomes, boolean dimensionNether) {
        setGenerationDiagnosticStage("VoxelWorld.generateChunk: validando dimensión; chunk cx=" + cx + ", cz=" + cz);
        if (!dimensionNether && worldType == TYPE_FLAT) {
            for (int z = 0; z < CHUNK_XZ; z++) for (int x = 0; x < CHUNK_XZ; x++) {
                int base = (z * CHUNK_XZ + x) * HEIGHT;
                for (int y = 0; y < HEIGHT; y++)
                    target[base + y] = y == 0 ? Block.BEDROCK : (y <= 3 ? Block.DIRT : (y == 4 ? Block.GRASS : Block.AIR));
                heights[z * CHUNK_XZ + x] = 4;
                biomes[z * CHUNK_XZ + x] = 0;
            }
        } else if (dimensionNether) {
            setGenerationDiagnosticStage("VoxelWorld.generateChunk: InfdevTerrain.generateNether; cx=" + cx + ", cz=" + cz);
            if (economicMode) InfdevTerrain.generateEconomicNether(seed, cx, cz, target, heights);
            else InfdevTerrain.generateNether(seed, cx, cz, target, heights);
        } else if (economicMode) {
            setGenerationDiagnosticStage("VoxelWorld.generateChunk: InfdevTerrain.generateEconomic; cx=" + cx + ", cz=" + cz);
            InfdevTerrain.generateEconomic(seed, cx, cz, target, heights, biomes);
        } else {
            setGenerationDiagnosticStage("VoxelWorld.generateChunk: InfdevTerrain.generate; cx=" + cx + ", cz=" + cz);
            InfdevTerrain.generate(seed, cx, cz, target, heights, biomes);
        }
    }

    void setNetherDimension(boolean enabled) {
        if (nether == enabled) return;
        synchronized (this) { proceduralGenerationEpoch++; proceduralQueueCount = 0; }
        if (otherDimensionBlocks != null && otherDimensionIsNether == enabled) {
            byte[] oldBlocks = blocks;
            byte[] oldWater = waterLevels;
            blocks = otherDimensionBlocks;
            waterLevels = otherDimensionWaterLevels;
            otherDimensionBlocks = oldBlocks;
            otherDimensionWaterLevels = oldWater;
            otherDimensionIsNether = !enabled;
            nether = enabled;
        } else if (otherDimensionBlocks == null) {
            otherDimensionBlocks = blocks;
            otherDimensionWaterLevels = waterLevels;
            otherDimensionIsNether = !enabled;
            nether = enabled;
            blocks = new byte[BLOCK_COUNT];
            waterLevels = new byte[BLOCK_COUNT];
            generate();
        } else {
            return;
        }
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) {
            proceduralChunkX[i] = Integer.MIN_VALUE;
            proceduralChunkZ[i] = Integer.MIN_VALUE;
        }

        fluidQueueHead = fluidQueueTail = fluidQueueCount = fluidTickDelay = fluidDirection = 0;
        lavaTickCounter = 0;
        for (int i = 0; i < fluidQueued.length; i++) fluidQueued[i] = 0;
        if (nether) convertLegacyNetherWater();
        queueSavedFlowingFluids();
        fullRebuild = true;
        revision++;
    }

    void switchDimension() { setNetherDimension(!nether); }

    private void convertLegacyNetherWater() {
        for (int i = 0; i < blocks.length; i++)
            if (blocks[i] == Block.WATER) blocks[i] = Block.LAVA;
        for (int i = 0; i < editCount; i++)
            if (editNether[i] && editId[i] == Block.WATER) editId[i] = Block.LAVA;
    }

    /** Activates a black-wool 4x5 frame when the player uses a diamond sword. */
    boolean activatePortalAtWool(int x, int y, int z) {
        if (get(x, y, z) != Block.WOOL) return false;
        int[] frame = findPortalFrameForWool(x, y, z);
        if (frame == null) return false;
        for (int v = 1; v <= 3; v++) {
            for (int u = 1; u <= 2; u++) {
                int px = frame[3] == 0 ? frame[0] + u : frame[0];
                int pz = frame[3] == 0 ? frame[2] : frame[2] + u;
                if (!set(px, frame[1] + v, pz, Block.PORTAL) &&
                        get(px, frame[1] + v, pz) != Block.PORTAL) return false;
            }
        }
        // All six portal cells can straddle chunk boundaries; invalidate the
        // full visible mesh set instead of rebuilding only the last cell's chunk.
        fullRebuild = true;
        return true;
    }

    int portalAxisAt(int x, int y, int z) {
        if (get(x - 1, y, z) == Block.PORTAL || get(x + 1, y, z) == Block.PORTAL) return 0;
        if (get(x, y, z - 1) == Block.PORTAL || get(x, y, z + 1) == Block.PORTAL) return 1;
        return 0;
    }

    boolean touchesPortal(float px, float py, float pz) {
        int minX = floorBlock(px - 0.31f), maxX = floorBlock(px + 0.31f);
        int minY = floorBlock(py), maxY = floorBlock(py + 1.8f);
        int minZ = floorBlock(pz - 0.31f), maxZ = floorBlock(pz + 0.31f);
        for (int y = minY; y <= maxY; y++)
            for (int z = minZ; z <= maxZ; z++)
                for (int x = minX; x <= maxX; x++)
                    if (get(x, y, z) == Block.PORTAL) return true;
        return false;
    }

    int[] portalFrameAt(int x, int y, int z) {
        if (get(x, y, z) != Block.PORTAL) return null;
        for (int axis = 0; axis < 2; axis++) {
            for (int u = 1; u <= 2; u++) {
                int bx = axis == 0 ? x - u : x;
                int bz = axis == 0 ? z : z - u;
                for (int v = 1; v <= 3; v++) {
                    int by = y - v;
                    if (matchesPortalFrame(bx, by, bz, axis))
                        return new int[] { bx, by, bz, axis };
                }
            }
        }
        return null;
    }

    int[] findPortalNear(int x, int z) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int y = 1; y < HEIGHT - 1; y++) {
                        int[] frame = portalFrameAt(x + dx, y, z + dz);
                        if (frame != null) return frame;
                    }
                }
            }
        }
        return null;
    }

    int[] createPortalAt(int baseX, int baseY, int baseZ, int axis) {
        if (axis != 0 && axis != 1) return null;
        // The terrain height can differ between the four columns of a portal.
        // A portal placed one block above only its center column can intersect
        // a neighboring hill/wall and fail halfway through construction. Try
        // the requested site first, then nearby sites using the highest ground
        // under the whole frame.
        for (int radius = 0; radius <= 4; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int bx = baseX + dx, bz = baseZ + dz;
                    int candidateY = radius == 0 ? baseY : -1;
                    if (candidateY <= 0) {
                        int highest = 0;
                        for (int u = 0; u < 4; u++) {
                            int x = axis == 0 ? bx + u : bx;
                            int z = axis == 0 ? bz : bz + u;
                            int ground = surfaceY(x, z);
                            if (ground > highest) highest = ground;
                        }
                        candidateY = highest + 1;
                    }
                    if (candidateY <= 0 || candidateY + 4 >= HEIGHT) continue;
                    if (!canPlacePortalFrame(bx, candidateY, bz, axis)) continue;
                    for (int v = 0; v <= 4; v++) {
                        for (int u = 0; u <= 3; u++) {
                            boolean frameBlock = v == 0 || v == 4 || u == 0 || u == 3;
                            int x = axis == 0 ? bx + u : bx;
                            int z = axis == 0 ? bz : bz + u;
                            if (frameBlock) {
                                if (get(x, candidateY + v, z) != Block.WOOL ||
                                        woolColor(x, candidateY + v, z) != WoolColors.BLACK) {
                                    if (!setWool(x, candidateY + v, z, WoolColors.BLACK)) return null;
                                }
                            } else if (!set(x, candidateY + v, z, Block.PORTAL) &&
                                    get(x, candidateY + v, z) != Block.PORTAL) return null;
                        }
                    }
                    fullRebuild = true;
                    return new int[] { bx, candidateY, bz, axis };
                }
            }
        }
        return null;
    }

    private boolean canPlacePortalFrame(int bx, int by, int bz, int axis) {
        for (int v = 0; v <= 4; v++) {
            for (int u = 0; u <= 3; u++) {
                int x = axis == 0 ? bx + u : bx;
                int z = axis == 0 ? bz : bz + u;
                byte id = get(x, by + v, z);
                boolean frameBlock = v == 0 || v == 4 || u == 0 || u == 3;
                if (frameBlock) {
                    if (id == Block.WOOL && woolColor(x, by + v, z) == WoolColors.BLACK) continue;
                    if (!Block.replaceable(id)) return false;
                } else if (id != Block.AIR && id != Block.PORTAL) return false;
            }
        }
        return true;
    }

    private int[] findPortalFrameForWool(int x, int y, int z) {
        for (int axis = 0; axis < 2; axis++) {
            int firstU = axis == 0 ? x - 3 : z - 3;
            int lastU = axis == 0 ? x : z;
            for (int baseU = firstU; baseU <= lastU; baseU++) {
                for (int baseY = y - 4; baseY <= y; baseY++) {
                    int bx = axis == 0 ? baseU : x;
                    int bz = axis == 0 ? z : baseU;
                    if (baseY <= 0 || baseY + 4 >= HEIGHT) continue;
                    if (matchesPortalFrame(bx, baseY, bz, axis))
                        return new int[] { bx, baseY, bz, axis };
                }
            }
        }
        return null;
    }

    private boolean matchesPortalFrame(int bx, int by, int bz, int axis) {
        if (by <= 0 || by + 4 >= HEIGHT) return false;
        for (int v = 0; v <= 4; v++) {
            for (int u = 0; u <= 3; u++) {
                int x = axis == 0 ? bx + u : bx;
                int z = axis == 0 ? bz : bz + u;
                byte id = get(x, by + v, z);
                if (v == 0 || v == 4 || u == 0 || u == 3) {
                    boolean corner = (u == 0 || u == 3) && (v == 0 || v == 4);
                    if (id == Block.WOOL) {
                        if (woolColor(x, by + v, z) != WoolColors.BLACK) return false;
                    } else if (!corner || !Block.replaceable(id)) return false;
                } else if (id != Block.AIR && id != Block.PORTAL) return false;
            }
        }
        return true;
    }

    int surfaceY(int x, int z) {
        if (nether) return economicMode ? InfdevTerrain.economicNetherFloorHeight(seed, x, z) :
            InfdevTerrain.netherFloorHeight(seed, x, z);
        for (int y = HEIGHT - 1; y >= 0; y--) {
            if (get(x, y, z) != Block.AIR && !Block.isFluid(get(x, y, z))) return y;
        }
        return SEA_LEVEL;
    }

    void save() {
        RecordStore store = null;
        try {
            String storeName = saveStoreName == null ? "mecraft-save" : saveStoreName;
            store = RecordStore.openRecordStore(storeName, true);
            if (store.getNumRecords() == 0) store.addRecord(new byte[0], 0, 0);
            ByteArrayOutputStream infoBytes = new ByteArrayOutputStream(40);
            DataOutputStream info = new DataOutputStream(infoBytes);
            info.writeInt(0x4D434249); // MCBI: compact world-list metadata
            info.writeByte(mode);
            info.writeUTF(worldName == null ? "Mundo" : worldName);
            info.flush();
            byte[] infoData = infoBytes.toByteArray();
            if (store.getNumRecords() < 2) store.addRecord(infoData, 0, infoData.length);
            else store.setRecord(2, infoData, 0, infoData.length);

            int oldGeneration = editGeneration;
            int generation = oldGeneration + 1;
            int pageCount = (editCount + INFDEV_EDITS_PER_PAGE - 1) / INFDEV_EDITS_PER_PAGE;
            for (int page = 0; page < pageCount; page++) {
                int start = page * INFDEV_EDITS_PER_PAGE;
                int count = editCount - start;
                if (count > INFDEV_EDITS_PER_PAGE) count = INFDEV_EDITS_PER_PAGE;
                ByteArrayOutputStream pageBytes = new ByteArrayOutputStream(16 + count * 12);
                DataOutputStream pageOut = new DataOutputStream(pageBytes);
                pageOut.writeInt(INFDEV_EDIT_PAGE_MAGIC_V2);
                pageOut.writeInt(generation);
                pageOut.writeInt(page);
                pageOut.writeInt(pageCount);
                pageOut.writeShort(count);
                for (int i = start; i < start + count; i++) writeEdit(pageOut, i);
                pageOut.flush();
                byte[] pageData = pageBytes.toByteArray();
                store.addRecord(pageData, 0, pageData.length);
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(blocks.length * 2 + 64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(DIFFICULTY_WORLD_SAVE_MAGIC);
            out.writeByte(HEIGHT);
            out.writeByte(worldSize);
            out.writeByte(worldType);
            out.writeByte(difficulty);
            out.writeInt(seed);
            out.writeByte(mode);
            out.writeUTF(worldName == null ? "Mundo" : worldName);
            out.writeBoolean(nether);
            out.writeByte(selectedSlot);
            out.writeShort(inventory.length);
            for (int i = 0; i < inventory.length; i++) out.writeByte(inventory[i]);
            for (int i = 0; i < hotbar.length; i++) out.writeByte(hotbar[i]);
            for (int i = 0; i < itemDamage.length; i++) out.writeShort(itemDamage[i]);
            out.writeFloat(playerX);
            out.writeFloat(playerY);
            out.writeFloat(playerZ);
            out.writeFloat(yaw);
            out.writeByte(health);
            out.writeShort(dayTime);
            out.write(blocks);
            out.write(waterLevels);
            out.writeInt(INFDEV_EDIT_MAGIC);
            out.writeShort(0); // Edits live in bounded RMS pages below.
            out.writeInt(INFDEV_EDIT_POINTER_MAGIC);
            out.writeInt(generation);
            out.writeInt(pageCount);
            synchronized (this) {
                int cachedCount = 0;
                for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++)
                    if (proceduralChunkX[i] != Integer.MIN_VALUE) cachedCount++;
                out.writeInt(PROCEDURAL_CACHE_SAVE_MAGIC);
                out.writeByte(cachedCount);
                for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) {
                    if (proceduralChunkX[i] == Integer.MIN_VALUE) continue;
                    out.writeInt(proceduralChunkX[i]);
                    out.writeInt(proceduralChunkZ[i]);
                    out.write(proceduralChunks[i]);
                }
            }
            out.flush();
            byte[] data = bytes.toByteArray();
            store.setRecord(1, data, 0, data.length);
            editGeneration = generation;
            deleteOldEditPages(store, generation);
            saveOtherDimension(storeName);
        } catch (Exception ignored) {
            // A failed save must not terminate play on devices with tiny RMS limits.
        } catch (OutOfMemoryError ignored) {
            // Keep the game alive if RMS serialization cannot allocate its temporary buffer.
        } finally {
            if (store != null) {
                try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
            }
        }
    }

    private void saveOtherDimension(String storeName) {
        if (otherDimensionBlocks == null || otherDimensionWaterLevels == null) return;
        RecordStore dimStore = null;
        try {
            dimStore = RecordStore.openRecordStore(storeName + "-dim", true);
            RecordEnumeration oldRecords = dimStore.enumerateRecords(null, null, false);
            int[] oldIds = new int[dimStore.getNumRecords()];
            int oldCount = 0;
            while (oldRecords.hasNextElement()) oldIds[oldCount++] = oldRecords.nextRecordId();
            oldRecords.destroy();
            for (int i = 0; i < oldCount; i++) dimStore.deleteRecord(oldIds[i]);
            int totalBytes = BLOCK_COUNT * 2;
            int pageCount = (totalBytes + DIMENSION_PAGE_BYTES - 1) / DIMENSION_PAGE_BYTES;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(10);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(DIMENSION_STORE_MAGIC);
            out.writeBoolean(otherDimensionIsNether);
            out.writeInt(totalBytes);
            out.writeShort(pageCount);
            out.flush();
            byte[] data = bytes.toByteArray();
            dimStore.addRecord(data, 0, data.length);
            for (int page = 0; page < pageCount; page++) {
                int offset = page * DIMENSION_PAGE_BYTES;
                int length = totalBytes - offset;
                if (length > DIMENSION_PAGE_BYTES) length = DIMENSION_PAGE_BYTES;
                ByteArrayOutputStream pageBytes = new ByteArrayOutputStream(length + 10);
                DataOutputStream pageOut = new DataOutputStream(pageBytes);
                pageOut.writeInt(DIMENSION_PAGE_MAGIC);
                pageOut.writeShort(page);
                pageOut.writeShort(length);
                for (int i = 0; i < length; i++) {
                    int source = offset + i;
                    pageOut.writeByte(source < BLOCK_COUNT ? otherDimensionBlocks[source] :
                                      otherDimensionWaterLevels[source - BLOCK_COUNT]);
                }
                pageOut.flush();
                byte[] pageData = pageBytes.toByteArray();
                dimStore.addRecord(pageData, 0, pageData.length);
            }
        } catch (Exception ignored) {
        } catch (OutOfMemoryError ignored) {
        } finally {
            if (dimStore != null) try { dimStore.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }

    private static void loadOtherDimension(String storeName, VoxelWorld world) {
        RecordStore dimStore = null;
        try {
            dimStore = RecordStore.openRecordStore(storeName + "-dim", false);
            if (dimStore.getNumRecords() == 0) return;
            byte[] data = null;
            RecordEnumeration headers = dimStore.enumerateRecords(null, null, false);
            try {
                while (headers.hasNextElement()) {
                    byte[] candidate = dimStore.getRecord(headers.nextRecordId());
                    if (candidate.length >= 11 &&
                        new DataInputStream(new ByteArrayInputStream(candidate)).readInt() == DIMENSION_STORE_MAGIC) {
                        data = candidate;
                        break;
                    }
                }
            } finally {
                headers.destroy();
            }
            if (data == null) return;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != DIMENSION_STORE_MAGIC) return;
            boolean otherNether = in.readBoolean();
            int totalBytes = in.readInt();
            int pageCount = in.readUnsignedShort();
            int savedHeight = totalBytes / (WIDTH * DEPTH * 2);
            int oldBlockCount = WIDTH * DEPTH * savedHeight;
            if (otherNether == world.nether || savedHeight < 8 || savedHeight > HEIGHT ||
                totalBytes != oldBlockCount * 2 ||
                pageCount != (totalBytes + DIMENSION_PAGE_BYTES - 1) / DIMENSION_PAGE_BYTES ||
                dimStore.getNumRecords() != pageCount + 1) return;
            byte[] savedBlocks = new byte[oldBlockCount];
            byte[] savedWater = new byte[oldBlockCount];
            boolean[] loaded = new boolean[pageCount];
            RecordEnumeration pages = dimStore.enumerateRecords(null, null, false);
            try {
                while (pages.hasNextElement()) {
                    int id = pages.nextRecordId();
                    byte[] pageData = dimStore.getRecord(id);
                    DataInputStream pageIn = new DataInputStream(new ByteArrayInputStream(pageData));
                    int pageMagic = pageIn.readInt();
                    if (pageMagic != DIMENSION_PAGE_MAGIC) continue;
                    int page = pageIn.readUnsignedShort();
                    int length = pageIn.readUnsignedShort();
                    int offset = page * DIMENSION_PAGE_BYTES;
                    if (page < 0 || page >= pageCount || loaded[page] ||
                        length != Math.min(DIMENSION_PAGE_BYTES, totalBytes - offset) ||
                        pageIn.available() != length) return;
                    for (int i = 0; i < length; i++) {
                        int target = offset + i;
                        byte value = pageIn.readByte();
                        if (target < oldBlockCount) savedBlocks[target] = value;
                        else savedWater[target - oldBlockCount] = value;
                    }
                    loaded[page] = true;
                }
            } finally {
                pages.destroy();
            }
            for (int i = 0; i < loaded.length; i++) if (!loaded[i]) return;
            byte[] otherBlocks = new byte[BLOCK_COUNT];
            byte[] otherWater = new byte[BLOCK_COUNT];
            int shift = otherNether ? 0 : HEIGHT - savedHeight;
            expandSavedHeight(savedBlocks, savedWater, savedHeight, shift, otherBlocks, otherWater);
            world.otherDimensionIsNether = otherNether;
            world.otherDimensionBlocks = otherBlocks;
            world.otherDimensionWaterLevels = otherWater;
            if (otherNether) {
                for (int i = 0; i < otherBlocks.length; i++)
                    if (otherBlocks[i] == Block.WATER) otherBlocks[i] = Block.LAVA;
            }
        } catch (Exception ignored) {
        } finally {
            if (dimStore != null) try { dimStore.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }

    private void writeEdit(DataOutputStream out, int i) throws IOException {
        out.writeInt(editX[i]); out.writeByte(editY[i]); out.writeInt(editZ[i]);
        out.writeByte(editId[i]); out.writeByte(editMeta[i]); out.writeBoolean(editNether[i]);
    }

    private static void deleteOldEditPages(RecordStore store, int keepGeneration) {
        RecordEnumeration records = null;
        try {
            records = store.enumerateRecords(null, null, false);
            int[] remove = new int[store.getNumRecords()];
            int count = 0;
            while (records.hasNextElement()) {
                int recordId = records.nextRecordId();
                if (recordId <= 2) continue;
                byte[] data = store.getRecord(recordId);
                if (data.length < 8) continue;
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                int magic = in.readInt();
                if ((magic == INFDEV_EDIT_PAGE_MAGIC || magic == INFDEV_EDIT_PAGE_MAGIC_V2) &&
                    in.readInt() != keepGeneration)
                    remove[count++] = recordId;
            }
            records.destroy(); records = null;
            for (int i = 0; i < count; i++) store.deleteRecord(remove[i]);
        } catch (Exception ignored) {
        } finally {
            if (records != null) try { records.destroy(); } catch (Exception ignored) { }
        }
    }

    static VoxelWorld load() { return load("mecraft-save"); }

    static VoxelWorld load(String storeName) {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(storeName, false);
            if (store.getNumRecords() == 0) return null;
            byte[] data = store.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int format = in.readInt();
            boolean difficultyWorldFormat = format == DIFFICULTY_WORLD_SAVE_MAGIC;
            boolean configWorldFormat = format == CONFIG_WORLD_SAVE_MAGIC || difficultyWorldFormat;
            boolean deepWorldFormat = format == DEEP_WORLD_SAVE_MAGIC || configWorldFormat;
            if (format != 0x4D434631 && format != 0x4D434632 && format != 0x4D434633 &&
                format != 0x4D434634 && format != 0x4D434635 && format != 0x4D434636 &&
                format != 0x4D434637 && format != 0x4D434638 && format != 0x4D434639 &&
                format != 0x4D434641 && format != 0x4D434642 && format != 0x4D434643 &&
                format != 0x4D434644 && format != ALPHA_ITEM_SAVE_MAGIC &&
                format != DIFFICULTY_WORLD_SAVE_MAGIC && !deepWorldFormat) return null;
            int savedHeight = deepWorldFormat ? in.readUnsignedByte() : 24;
            if (savedHeight < 8 || savedHeight > HEIGHT) return null;
            int savedWorldSize = configWorldFormat ? in.readUnsignedByte() : SIZE_INFINITE;
            int savedWorldType = configWorldFormat ? in.readUnsignedByte() : TYPE_STANDARD;
            int savedDifficulty = difficultyWorldFormat ? in.readUnsignedByte() : DIFFICULTY_NORMAL;
            int seed = in.readInt();
            int mode = in.readByte();
            boolean namedFormat = format == 0x4D434642 || format == 0x4D434643 ||
                                  format == 0x4D434644 || format == ALPHA_ITEM_SAVE_MAGIC || deepWorldFormat;
            // Saved voxel data is read below. Generating a fresh 3x3 area here
            // would waste nine complete chunk generations (and another nine
            // when the save belongs to the Nether).
            VoxelWorld world = new VoxelWorld(seed, mode, savedWorldSize, savedWorldType, false);
            world.worldName = namedFormat ? in.readUTF() : "Mundo guardado";
            world.difficulty = savedDifficulty < DIFFICULTY_PEACEFUL || savedDifficulty > DIFFICULTY_HARD ? DIFFICULTY_NORMAL : savedDifficulty;
            boolean alphaItemFormat = format == ALPHA_ITEM_SAVE_MAGIC || deepWorldFormat;
            boolean explicitDimensionFormat = format == 0x4D434644 || alphaItemFormat;
            boolean savedNether = explicitDimensionFormat ? in.readBoolean() :
                                  "NETHER".equalsIgnoreCase(world.worldName);
            // The primary block arrays will be restored from this save record.
            // Set the dimension flag directly instead of generating a throwaway
            // dimension before loading the saved blocks.
            world.nether = savedNether;
            world.saveStoreName = storeName;
            world.selectedSlot = in.readByte();
            boolean currentFormat = format == 0x4D434641 || format == 0x4D434642 ||
                                    format == 0x4D434643 || explicitDimensionFormat;
            boolean latestFormat = format == 0x4D434643 || format == 0x4D434644 || alphaItemFormat;
            if (format == 0x4D434635 || format == 0x4D434636 || format == 0x4D434637 ||
                format == 0x4D434638 || format == 0x4D434639 || currentFormat) {
                int inventoryLength;
                if (alphaItemFormat) inventoryLength = in.readUnsignedShort();
                else if (format == 0x4D434644 || format == 0x4D434643)
                    inventoryLength = ItemCatalog.ALPHA_ITEM_START - (Block.COUNT - PRE_ALPHA_BLOCK_COUNT);
                else if (currentFormat)
                    inventoryLength = ItemCatalog.ALPHA_ITEM_START -
                            (Block.COUNT - PRE_ALPHA_BLOCK_COUNT) - ItemCatalog.WOOL_COLOR_COUNT;
                else if (latestFormat) inventoryLength = world.inventory.length;
                else if (format == 0x4D434639) inventoryLength = MCF9_ITEM_COUNT;
                else if (format == 0x4D434638) inventoryLength = MCF8_ITEM_COUNT;
                else if (format == 0x4D434637) inventoryLength = MCF7_ITEM_COUNT;
                else inventoryLength = MCF6_ITEM_COUNT;
                for (int i = 0; i < inventoryLength; i++) {
                    int newId = currentFormat ? i : (format == 0x4D434639 ? i :
                                (format == 0x4D434638 ? (i < 18 ? i : i + 1) :
                                 (format == 0x4D434637 ? (i < 17 ? i : i + 2) :
                                  (i < MCF6_BLOCK_COUNT ? i : i + 4))));
                    if (!currentFormat && newId >= 19) newId += Block.COUNT - 19;
                    else if (currentFormat && !alphaItemFormat && newId >= PRE_ALPHA_BLOCK_COUNT)
                        newId += Block.COUNT - PRE_ALPHA_BLOCK_COUNT;
                    int count = in.readUnsignedByte();
                    if (newId < world.inventory.length) world.inventory[newId] = count;
                }
                for (int i = 0; i < world.hotbar.length; i++) {
                    int id = in.readUnsignedByte();
                    if (currentFormat) { }
                    else if (format == 0x4D434638 && id >= 18) id++;
                    else if (format == 0x4D434637 && id >= 17) id += 2;
                    else if (format != 0x4D434637 && format != 0x4D434638 &&
                             format != 0x4D434639 && id >= MCF6_BLOCK_COUNT) id += 4;
                    if (!currentFormat && id >= 19) id += Block.COUNT - 19;
                    else if (currentFormat && !alphaItemFormat && id >= PRE_ALPHA_BLOCK_COUNT)
                        id += Block.COUNT - PRE_ALPHA_BLOCK_COUNT;
                    world.hotbar[i] = id > Block.AIR && id < ItemCatalog.COUNT ? id : Block.DIRT;
                }
                for (int i = 0; i < inventoryLength; i++) {
                    int newId = currentFormat ? i : (format == 0x4D434639 ? i :
                                (format == 0x4D434638 ? (i < 18 ? i : i + 1) :
                                 (format == 0x4D434637 ? (i < 17 ? i : i + 2) :
                                  (i < MCF6_BLOCK_COUNT ? i : i + 4))));
                    if (!currentFormat && newId >= 19) newId += Block.COUNT - 19;
                    else if (currentFormat && !alphaItemFormat && newId >= PRE_ALPHA_BLOCK_COUNT)
                        newId += Block.COUNT - PRE_ALPHA_BLOCK_COUNT;
                    int damage = in.readUnsignedShort();
                    if (newId < world.itemDamage.length) world.itemDamage[newId] = damage;
                }
            } else if (format == 0x4D434633 || format == 0x4D434634) {
                for (int i = 0; i < Block.LEGACY_COUNT; i++) world.inventory[i] = in.readUnsignedByte();
                for (int i = 0; i < world.hotbar.length; i++) {
                    int id = in.readUnsignedByte();
                    world.hotbar[i] = id > Block.AIR && id < Block.LEGACY_COUNT ? id : Block.DIRT;
                }
            } else {
                int[] oldHotbar = new int[] { Block.DIRT, Block.STONE, Block.LOG, Block.SAND,
                    Block.PLANKS, Block.LEAVES, Block.COAL, Block.IRON, Block.GRASS };
                for (int i = 0; i < oldHotbar.length; i++) world.inventory[oldHotbar[i]] = in.readUnsignedByte();
            }
            world.playerX = in.readFloat();
            world.playerY = in.readFloat();
            world.playerZ = in.readFloat();
            world.yaw = in.readFloat();
            if (format == 0x4D434632 || format == 0x4D434633 || format == 0x4D434634 ||
                format == 0x4D434635 || format == 0x4D434636 || format == 0x4D434637 ||
                format == 0x4D434638 || format == 0x4D434639 || currentFormat)
                world.health = in.readUnsignedByte();
            if (format == 0x4D434638 || format == 0x4D434639 || currentFormat) {
                world.dayTime = in.readUnsignedShort() % DAY_LENGTH;
            }
            int oldBlockCount = WIDTH * savedHeight * DEPTH;
            byte[] savedBlocks = new byte[oldBlockCount];
            byte[] savedWater = new byte[oldBlockCount];
            in.readFully(savedBlocks);
            if (format == 0x4D434634 || format == 0x4D434635 || format == 0x4D434636 ||
                format == 0x4D434637 || format == 0x4D434638 || format == 0x4D434639 ||
                currentFormat)
                in.readFully(savedWater);
            int heightShift = world.nether ? 0 : HEIGHT - savedHeight;
            expandSavedHeight(savedBlocks, savedWater, savedHeight, heightShift,
                    world.blocks, world.waterLevels);
            if (heightShift > 0) world.playerY += heightShift;
            int editGeneration = 0, editPageCount = 0;
            if (in.available() >= 6 && in.readInt() == INFDEV_EDIT_MAGIC) {
                int count = in.readUnsignedShort();
                for (int i = 0; i < count; i++) {
                    int x = in.readInt(), y = in.readUnsignedByte(), z = in.readInt();
                    y += heightShift;
                    byte id = in.readByte(), meta = in.readByte();
                    int edit = world.ensureEdit(x, y, z);
                    if (edit >= 0) { world.editId[edit] = id; world.editMeta[edit] = meta; }
                }
                if (in.available() >= 12 && in.readInt() == INFDEV_EDIT_POINTER_MAGIC) {
                    editGeneration = in.readInt();
                    editPageCount = in.readInt();
                }
            }
            // Persist the small outer-terrain cache so the first nearby streamed
            // sectors can be restored directly instead of regenerated on load.
            if (in.available() >= 5 && in.readInt() == PROCEDURAL_CACHE_SAVE_MAGIC) {
                int cachedCount = in.readUnsignedByte();
                if (cachedCount > PROCEDURAL_CACHE_SIZE) throw new IOException("Invalid chunk cache");
                for (int i = 0; i < cachedCount; i++) {
                    int cx = in.readInt(), cz = in.readInt();
                    int slot = i;
                    in.readFully(world.proceduralChunks[slot]);
                    world.proceduralChunkX[slot] = cx;
                    world.proceduralChunkZ[slot] = cz;
                    world.touchProceduralSlot(slot);
                }
            }
            if (editPageCount > 0 && editPageCount <= store.getNumRecords())
                loadEditPages(store, world, editGeneration, editPageCount, heightShift);
            if (world.nether) world.convertLegacyNetherWater();
            if (explicitDimensionFormat) loadOtherDimension(storeName, world);
            world.editGeneration = editGeneration;
            if (format != 0x4D434636 && format != 0x4D434637 && format != 0x4D434638 &&
                format != 0x4D434639 && !currentFormat)
                world.migrateLegacyFurnaces();
            world.queueSavedFlowingFluids();
            world.revision++;
            return world;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (store != null) {
                try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
            }
        }
    }

    private static void expandSavedHeight(byte[] oldBlocks, byte[] oldWater, int oldHeight,
                                          int shift, byte[] newBlocks, byte[] newWater) {
        if (shift == 0) {
            for (int y = 0; y < oldHeight; y++) {
                int oldLayer = y * WIDTH * DEPTH;
                int newLayer = oldLayer;
                System.arraycopy(oldBlocks, oldLayer, newBlocks, newLayer, WIDTH * DEPTH);
                System.arraycopy(oldWater, oldLayer, newWater, newLayer, WIDTH * DEPTH);
            }
            return;
        }
        for (int i = 0; i < newBlocks.length; i++) {
            newBlocks[i] = Block.AIR;
            newWater[i] = 0;
        }
        for (int z = 0; z < DEPTH; z++) {
            for (int x = 0; x < WIDTH; x++) {
                newBlocks[(z * WIDTH) + x] = Block.BEDROCK;
                for (int y = 1; y < shift; y++)
                    newBlocks[(y * DEPTH + z) * WIDTH + x] = Block.STONE;
                for (int y = 1; y < oldHeight; y++) {
                    int oldIndex = (y * DEPTH + z) * WIDTH + x;
                    int newY = y + shift;
                    if (newY >= HEIGHT) continue;
                    int newIndex = (newY * DEPTH + z) * WIDTH + x;
                    newBlocks[newIndex] = oldBlocks[oldIndex];
                    newWater[newIndex] = oldWater[oldIndex];
                }
            }
        }
    }

    private static void loadEditPages(RecordStore store, VoxelWorld world,
                                      int generation, int pageCount, int heightShift) {
        RecordEnumeration records = null;
        boolean[] loaded = new boolean[pageCount];
        try {
            records = store.enumerateRecords(null, null, false);
            while (records.hasNextElement()) {
                int recordId = records.nextRecordId();
                if (recordId <= 2) continue;
                byte[] data = store.getRecord(recordId);
                if (data.length < 18) continue;
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                int pageMagic = in.readInt();
                if ((pageMagic != INFDEV_EDIT_PAGE_MAGIC && pageMagic != INFDEV_EDIT_PAGE_MAGIC_V2) ||
                    in.readInt() != generation) continue;
                int page = in.readInt();
                int storedPageCount = in.readInt();
                int count = in.readUnsignedShort();
                if (page < 0 || page >= pageCount || storedPageCount != pageCount ||
                    count > INFDEV_EDITS_PER_PAGE || loaded[page] ||
                    in.available() < count * (pageMagic == INFDEV_EDIT_PAGE_MAGIC_V2 ? 12 : 11))
                    continue;
                loaded[page] = true;
                for (int i = 0; i < count; i++) {
                    int x = in.readInt(), y = in.readUnsignedByte() + heightShift, z = in.readInt();
                    byte id = in.readByte(), meta = in.readByte();
                    boolean dimension = pageMagic == INFDEV_EDIT_PAGE_MAGIC_V2 ? in.readBoolean() : world.nether;
                    int edit = world.ensureEdit(x, y, z, dimension);
                    if (edit >= 0) { world.editId[edit] = id; world.editMeta[edit] = meta; }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (records != null) try { records.destroy(); } catch (Exception ignored) { }
        }
    }

    /** MCF1?MCF5 used block/item ID 13 for the removed furnace. */
    private void migrateLegacyFurnaces() {
        inventory[13] = 0;
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] == 13) hotbar[i] = Block.STONE;
        }
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == 13) {
                blocks[i] = Block.STONE;
                revision++;
            }
        }
    }

    static boolean hasSave() {
        return listWorlds().length > 0;
    }

    /** Deletes only a store owned by this game's named-world system. */
    static boolean deleteWorld(String storeName) {
        if (storeName == null ||
            (!"mecraft-save".equals(storeName) && !storeName.startsWith("mecraft-world-")))
            return false;
        try {
            RecordStore.deleteRecordStore(storeName);
            try { RecordStore.deleteRecordStore(storeName + "-dim"); } catch (RecordStoreException ignored) { }
            return true;
        } catch (RecordStoreException ignored) {
            return false;
        }
    }

    static final class SavedWorld {
        final String storeName;
        final String name;
        final int mode;
        SavedWorld(String storeName, String name, int mode) {
            this.storeName = storeName; this.name = name; this.mode = mode;
        }
    }

    static String nextWorldStoreName() {
        String[] stores = RecordStore.listRecordStores();
        for (int id = 1; id < 10000; id++) {
            String candidate = "mecraft-world-" + id;
            boolean used = false;
            if (stores != null) for (int i = 0; i < stores.length; i++) {
                if (candidate.equals(stores[i])) { used = true; break; }
            }
            if (!used) return candidate;
        }
        return "mecraft-world-9999";
    }

    static SavedWorld[] listWorlds() {
        String[] stores = RecordStore.listRecordStores();
        if (stores == null) return new SavedWorld[0];
        SavedWorld[] worlds = new SavedWorld[stores.length];
        int count = 0;
        for (int i = 0; i < stores.length; i++) {
            String storeName = stores[i];
            if (!"mecraft-save".equals(storeName) && !storeName.startsWith("mecraft-world-")) continue;
            RecordStore store = null;
            try {
                store = RecordStore.openRecordStore(storeName, false);
                if (store.getNumRecords() == 0) continue;
                String name = "Mundo guardado";
                int mode = 0;
                if (store.getNumRecords() >= 2) {
                    DataInputStream info = new DataInputStream(new ByteArrayInputStream(store.getRecord(2)));
                    if (info.readInt() == 0x4D434249) {
                        mode = info.readUnsignedByte();
                        name = info.readUTF();
                    }
                }
                if ("Mundo guardado".equals(name)) {
                    DataInputStream header = new DataInputStream(new ByteArrayInputStream(store.getRecord(1)));
                    int format = header.readInt();
                    header.readInt();
                    mode = header.readUnsignedByte();
                    if (format == 0x4D434642) name = header.readUTF();
                }
                worlds[count++] = new SavedWorld(storeName, name, mode);
            } catch (Exception ignored) {
                // Ignore incomplete RMS entries rather than blocking the world selector.
            } finally {
                if (store != null) try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
            }
        }
        SavedWorld[] result = new SavedWorld[count];
        System.arraycopy(worlds, 0, result, 0, count);
        for (int i = 1; i < result.length; i++) {
            SavedWorld value = result[i];
            int j = i - 1;
            while (j >= 0 && result[j].storeName.compareTo(value.storeName) > 0) {
                result[j + 1] = result[j]; j--;
            }
            result[j + 1] = value;
        }
        return result;
    }
}
