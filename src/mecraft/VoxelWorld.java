package mecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/** Infinite, seed-generated terrain with an editable saved starting area. */
final class VoxelWorld {
    static final int WIDTH = 48;
    static final int HEIGHT = 24;
    static final int DEPTH = 48;
    static final int SEA_LEVEL = 7;
    static final int CHUNK_XZ = 16;
    static final int CHUNK_Y = 8;
    static final int CHUNKS_X = WIDTH / CHUNK_XZ;
    static final int CHUNKS_Y = HEIGHT / CHUNK_Y;
    static final int CHUNKS_Z = DEPTH / CHUNK_XZ;
    private static final int BLOCK_COUNT = WIDTH * HEIGHT * DEPTH;
    // Minecraft advances one world-time tick per game tick: 24,000 ticks at
    // 20 ticks/second gives the original 20-minute day/night cycle.
    private static final int DAY_TICKS_PER_UPDATE = 1;
    private static final int MCF6_BLOCK_COUNT = 15;
    private static final int MCF6_ITEM_COUNT = 45;
    private static final int MCF7_ITEM_COUNT = 47;
    private static final int MCF8_ITEM_COUNT = 48;
    private static final int MCF9_ITEM_COUNT = 51;
    private static final int INFDEV_EDIT_LIMIT = 512;
    private static final int INFDEV_EDIT_MAGIC = 0x49464431;
    static final int DAY_LENGTH = 24000;

    final byte[] blocks = new byte[BLOCK_COUNT];
    private final byte[] waterLevels = new byte[BLOCK_COUNT];
    private final byte[] explosionBits = new byte[(BLOCK_COUNT + 7) / 8];
    private static final int FLUID_QUEUE_SIZE = 2048;
    private final short[] fluidQueue = new short[FLUID_QUEUE_SIZE];
    private final byte[] fluidQueued = new byte[(BLOCK_COUNT + 7) / 8];
    // Power-of-two ring cache; eight chunks reduce regeneration while meshing neighbors.
    private static final int PROCEDURAL_CACHE_SIZE = 8;
    private final byte[][] proceduralChunks =
            new byte[PROCEDURAL_CACHE_SIZE][InfdevTerrain.CHUNK_VOLUME];
    private final int[] proceduralChunkX = new int[PROCEDURAL_CACHE_SIZE];
    private final int[] proceduralChunkZ = new int[PROCEDURAL_CACHE_SIZE];
    private final int[] proceduralHeights = new int[InfdevTerrain.CHUNK_SIZE * InfdevTerrain.CHUNK_SIZE];
    private final byte[] proceduralBiomes = new byte[InfdevTerrain.CHUNK_SIZE * InfdevTerrain.CHUNK_SIZE];
    private int proceduralCacheCursor;
    private final int[] editX = new int[INFDEV_EDIT_LIMIT];
    private final int[] editY = new int[INFDEV_EDIT_LIMIT];
    private final int[] editZ = new int[INFDEV_EDIT_LIMIT];
    private final byte[] editId = new byte[INFDEV_EDIT_LIMIT];
    private final byte[] editMeta = new byte[INFDEV_EDIT_LIMIT];
    private final short[] editLookup = new short[1024];
    private int editCount;
    final int[] inventory = new int[ItemCatalog.COUNT];
    final int[] itemDamage = new int[ItemCatalog.COUNT];
    final int[] hotbar = new int[] {
        Block.DIRT, Block.STONE, Block.LOG, Block.SAND, Block.PLANKS,
        Block.LEAVES, Block.COAL, Block.IRON, Block.TORCH
    };
    int seed;
    int mode;
    String worldName = "Mundo";
    String saveStoreName;
    int selectedSlot;
    int health = 20;
    int dayTime;
    float playerX;
    float playerY;
    float playerZ;
    float yaw;
    int revision;
    int changedX;
    int changedY;
    int changedZ;
    boolean fullRebuild;
    private int explosionRandom = 0x51F15E;
    private int fluidQueueHead;
    private int fluidQueueTail;
    private int fluidQueueCount;
    private int fluidTickDelay;
    private int fluidDirection;

    void tickTime() {
        dayTime += DAY_TICKS_PER_UPDATE;
        if (dayTime >= DAY_LENGTH) dayTime -= DAY_LENGTH;
    }

    /** Indev's celestial curve reaches darkness after sunset and dawn near tick 23000. */
    boolean isNight() {
        return dayTime >= 13000 && dayTime < 23000;
    }

    VoxelWorld(int seed, int mode) {
        this.seed = seed;
        this.mode = mode;
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) {
            proceduralChunkX[i] = Integer.MIN_VALUE;
            proceduralChunkZ[i] = Integer.MIN_VALUE;
        }
        generate();
    }

    private int index(int x, int y, int z) {
        return (y * DEPTH + z) * WIDTH + x;
    }

    private boolean inside(int x, int z) {
        return x >= 0 && x < WIDTH && z >= 0 && z < DEPTH;
    }

    private int floorChunk(int value) {
        int result = value / CHUNK_XZ;
        return value < 0 && value % CHUNK_XZ != 0 ? result - 1 : result;
    }

    private byte proceduralBlock(int x, int y, int z) {
        int cx = floorChunk(x), cz = floorChunk(z);
        int cache = -1;
        for (int i = 0; i < PROCEDURAL_CACHE_SIZE; i++) {
            if (proceduralChunkX[i] == cx && proceduralChunkZ[i] == cz) { cache = i; break; }
        }
        if (cache < 0) {
            cache = proceduralCacheCursor++ & (PROCEDURAL_CACHE_SIZE - 1);
            InfdevTerrain.generate(seed, cx, cz, proceduralChunks[cache], proceduralHeights, proceduralBiomes);
            proceduralChunkX[cache] = cx; proceduralChunkZ[cache] = cz;
        }
        int lx = x - cx * CHUNK_XZ, lz = z - cz * CHUNK_XZ;
        return proceduralChunks[cache][(lz * CHUNK_XZ + lx) * HEIGHT + y];
    }

    private int findEdit(int x, int y, int z) {
        int hash = x * 73428767 ^ y * 912931 ^ z * 42317861;
        int slot = hash & (editLookup.length - 1);
        for (int probe = 0; probe < editLookup.length; probe++) {
            int value = editLookup[slot] & 65535;
            if (value == 0) return -1;
            int i = value - 1;
            if (editX[i] == x && editY[i] == y && editZ[i] == z) return i;
            slot = (slot + 1) & (editLookup.length - 1);
        }
        return -1;
    }

    private int ensureEdit(int x, int y, int z) {
        int i = findEdit(x, y, z);
        if (i >= 0) return i;
        if (editCount == editX.length) return -1;
        i = editCount++;
        editX[i] = x; editY[i] = y; editZ[i] = z;
        int slot = (x * 73428767 ^ y * 912931 ^ z * 42317861) & (editLookup.length - 1);
        while (editLookup[slot] != 0) slot = (slot + 1) & (editLookup.length - 1);
        editLookup[slot] = (short)(i + 1);
        return i;
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

    byte get(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT) return Block.AIR;
        if (inside(x, z)) return blocks[index(x, y, z)];
        int edit = findEdit(x, y, z);
        return edit >= 0 ? editId[edit] : proceduralBlock(x, y, z);
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
        scheduleWaterNeighbors(x, y, z);
        return true;
    }

    int doorMetadata(int x, int y, int z) {
        if (get(x, y, z) != Block.DOOR) return 0;
        return metadataAt(x, y, z) & 15;
    }

    int doorState(int x, int y, int z) {
        int metadata = doorMetadata(x, y, z);
        return (metadata & 4) != 0 ? metadata & 3 : (metadata - 1) & 3;
    }

    boolean placeDoor(int x, int y, int z, int orientation) {
        if (y <= 0 || y + 1 >= HEIGHT || !Block.solid(get(x, y - 1, z)) ||
            !Block.replaceable(get(x, y, z)) || !Block.replaceable(get(x, y + 1, z))) return false;
        if (!setBlock(x, y, z, Block.DOOR, 0)) return false;
        if (!setBlock(x, y + 1, z, Block.DOOR, 0)) {
            setBlock(x, y, z, Block.AIR, 0);
            return false;
        }
        int metadata = orientation & 7;
        setMetadata(x, y, z, metadata);
        setMetadata(x, y + 1, z, metadata | 8);
        fullRebuild = true;
        revision++;
        changedX = x; changedY = y; changedZ = z;
        return true;
    }

    boolean toggleDoor(int x, int y, int z) {
        if (get(x, y, z) != Block.DOOR) return false;
        int metadata = doorMetadata(x, y, z);
        if ((metadata & 8) != 0) { y--; metadata = doorMetadata(x, y, z); }
        if (get(x, y, z) != Block.DOOR || get(x, y + 1, z) != Block.DOOR) return false;
        metadata ^= 4;
        setMetadata(x, y, z, metadata);
        setMetadata(x, y + 1, z, metadata | 8);
        fullRebuild = true;
        revision++;
        changedX = x; changedY = y; changedZ = z;
        return true;
    }

    boolean removeDoor(int x, int y, int z) {
        if (get(x, y, z) != Block.DOOR) return false;
        if ((doorMetadata(x, y, z) & 8) != 0) y--;
        if (get(x, y, z) != Block.DOOR) return false;
        setBlock(x, y, z, Block.AIR, 0);
        if (y + 1 < HEIGHT && get(x, y + 1, z) == Block.DOOR)
            setBlock(x, y + 1, z, Block.AIR, 0);
        fullRebuild = true;
        return true;
    }

    boolean isSolidAt(int x, int y, int z) {
        byte id = get(x, y, z);
        return id == Block.DOOR ? (doorMetadata(x, y, z) & 4) == 0 : Block.solid(id);
    }

    /** Indev's shell ray blast, scaled to TNT radius 4 and stored in a reusable bitset. */
    void explode(float x, float y, float z, float radius, DroppedItemSystem drops) {
        if (!inside(floorBlock(x), floorBlock(z))) {
            int reach = (int)(radius * 2.0f) + 1;
            int minX = floorBlock(x) - reach, maxX = floorBlock(x) + reach;
            int minY = Math.max(1, floorBlock(y) - reach), maxY = Math.min(HEIGHT - 1, floorBlock(y) + reach);
            int minZ = floorBlock(z) - reach, maxZ = floorBlock(z) + reach;
            float radiusSquared = radius * radius * 4.0f;
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    for (int bx = minX; bx <= maxX; bx++) {
                        float dx = bx + 0.5f - x, dy = by + 0.5f - y, dz = bz + 0.5f - z;
                        if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
                        byte id = get(bx, by, bz);
                        if (id == Block.AIR || id == Block.BEDROCK || id == Block.WATER) continue;
                        if (id == Block.TNT) drops.spawnPrimedTnt(bx, by, bz, 10 + randomInt(20));
                        else if (Block.dropsItem(id) && randomFloat() < 0.3f)
                            drops.spawn(dropItemId(bx, by, bz), bx, by, bz);
                        set(bx, by, bz, Block.AIR);
                    }
                }
            }
            fullRebuild = true;
            return;
        }
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
                        if (id != Block.AIR) strength -= (explosionResistance(id) + 0.3f) * 0.3f;
                        if (strength > 0.0f && bx >= 0 && bx < WIDTH && by > 0 && by < HEIGHT && bz >= 0 && bz < DEPTH) {
                            int index = index(bx, by, bz);
                            explosionBits[index >> 3] |= (byte)(1 << (index & 7));
                        }
                        px += dx * 0.3f; py += dy * 0.3f; pz += dz * 0.3f;
                        strength -= 0.22500001f;
                    }
                }
            }
        }
        int minX = Math.max(0, floorBlock(x - radius * 2.0f - 1.0f));
        int maxX = Math.min(WIDTH - 1, floorBlock(x + radius * 2.0f + 1.0f));
        int minY = Math.max(1, floorBlock(y - radius * 2.0f - 1.0f));
        int maxY = Math.min(HEIGHT - 1, floorBlock(y + radius * 2.0f + 1.0f));
        int minZ = Math.max(0, floorBlock(z - radius * 2.0f - 1.0f));
        int maxZ = Math.min(DEPTH - 1, floorBlock(z + radius * 2.0f + 1.0f));
        for (int by = minY; by <= maxY; by++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int bx = minX; bx <= maxX; bx++) {
                    int index = index(bx, by, bz);
                    if ((explosionBits[index >> 3] & (1 << (index & 7))) == 0) continue;
                    byte id = get(bx, by, bz);
                    if (id == Block.AIR) continue;
                    if (id == Block.TNT) drops.spawnPrimedTnt(bx, by, bz, 10 + randomInt(20));
                    else if (id == Block.DOOR) {
                        if (randomFloat() < 0.3f) drops.spawn(Block.DOOR, bx, by, bz);
                        removeDoor(bx, by, bz);
                        continue;
                    }
                    else if (Block.dropsItem(id) && randomFloat() < 0.3f)
                        drops.spawn(dropItemId(bx, by, bz), bx, by, bz);
                    set(bx, by, bz, Block.AIR);
                    if (by + 1 < HEIGHT && get(bx, by + 1, bz) == Block.DOOR &&
                        !Block.solid(get(bx, by, bz))) {
                        if (randomFloat() < 0.3f) drops.spawn(Block.DOOR, bx, by + 1, bz);
                        removeDoor(bx, by + 1, bz);
                    }
                }
            }
        }
        fullRebuild = true;
    }

    private float explosionResistance(byte id) {
        if (id == Block.BEDROCK) return 10000.0f;
        if (id == Block.STONE || id == Block.COBBLESTONE || id == Block.IRON) return 2.0f;
        if (id == Block.WATER) return 100.0f;
        return 0.0f;
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
        if (y <= 0 || y >= HEIGHT) return false;
        if (id < 0 || id >= Block.COUNT) return false;
        if (get(x, y, z) == id && (id != Block.WATER || metadataAt(x, y, z) == fluidLevel)) return false;
        if (inside(x, z)) {
            int i = index(x, y, z);
            blocks[i] = id;
            waterLevels[i] = id == Block.WATER ? (byte)fluidLevel : 0;
        } else {
            int edit = ensureEdit(x, y, z);
            if (edit < 0) return false;
            editId[edit] = id;
            editMeta[edit] = id == Block.WATER ? (byte)fluidLevel : 0;
        }
        changedX = x;
        changedY = y;
        changedZ = z;
        revision++;
        scheduleWaterNeighbors(x, y, z);
        return true;
    }

    private boolean setFlowingWater(int x, int y, int z, int level) {
        if (!Block.replaceable(get(x, y, z)) || level > 7) return false;
        return setBlock(x, y, z, Block.WATER, level);
    }

    /** Bounded Indev water updates; each scheduled cell spreads no more than one block per interval. */
    void updateFluids() {
        if (++fluidTickDelay < 5) return;
        fluidTickDelay = 0;
        while (fluidQueueCount > 0) {
            int i = fluidQueue[fluidQueueHead] & 0xFFFF;
            fluidQueueHead++;
            if (fluidQueueHead == FLUID_QUEUE_SIZE) fluidQueueHead = 0;
            fluidQueueCount--;
            setFluidQueued(i, false);
            if (blocks[i] != Block.WATER) continue;
            int x = i % WIDTH;
            int z = (i / WIDTH) % DEPTH;
            int y = i / (WIDTH * DEPTH);
            int level = waterLevels[i] & 0xFF;

            if (y > 1 && get(x, y - 1, z) == Block.AIR) {
                if (setFlowingWater(x, y - 1, z, level)) scheduleWater(i);
                return;
            }
            if (level >= 7) continue;
            for (int direction = 0; direction < 4; direction++) {
                int side = (fluidDirection + direction) & 3;
                int nx = x + (side == 0 ? -1 : (side == 1 ? 1 : 0));
                int nz = z + (side == 2 ? -1 : (side == 3 ? 1 : 0));
                if (nx <= 0 || nx >= WIDTH - 1 || nz <= 0 || nz >= DEPTH - 1) continue;
                if (get(nx, y, nz) != Block.AIR || get(nx, y - 1, nz) == Block.AIR) continue;
                fluidDirection = (side + 1) & 3;
                if (setFlowingWater(nx, y, nz, level + 1)) scheduleWater(i);
                return;
            }
        }
    }

    private void scheduleWaterNeighbors(int x, int y, int z) {
        scheduleWater(x, y, z);
        scheduleWater(x, y - 1, z);
        scheduleWater(x, y + 1, z);
        scheduleWater(x - 1, y, z);
        scheduleWater(x + 1, y, z);
        scheduleWater(x, y, z - 1);
        scheduleWater(x, y, z + 1);
    }

    private void scheduleWater(int x, int y, int z) {
        if (x < 0 || x >= WIDTH || z < 0 || z >= DEPTH || y < 0 || y >= HEIGHT) return;
        scheduleWater(index(x, y, z));
    }

    private void scheduleWater(int i) {
        if (blocks[i] != Block.WATER || isFluidQueued(i) || fluidQueueCount == FLUID_QUEUE_SIZE) return;
        fluidQueue[fluidQueueTail] = (short)i;
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

    private void queueSavedFlowingWater() {
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == Block.WATER && waterLevels[i] != 0) scheduleWater(i);
        }
    }

    private void generate() {
        for (int cz = 0; cz < CHUNKS_Z; cz++) {
            for (int cx = 0; cx < CHUNKS_X; cx++) {
                int cache = proceduralCacheCursor++ & (PROCEDURAL_CACHE_SIZE - 1);
                InfdevTerrain.generate(seed, cx, cz, proceduralChunks[cache], proceduralHeights, proceduralBiomes);
                proceduralChunkX[cache] = cx; proceduralChunkZ[cache] = cz;
                for (int z = 0; z < CHUNK_XZ; z++) {
                    for (int x = 0; x < CHUNK_XZ; x++) {
                        int wx = cx * CHUNK_XZ + x, wz = cz * CHUNK_XZ + z;
                        for (int y = 0; y < HEIGHT; y++)
                            blocks[index(wx, y, wz)] = proceduralChunks[cache][(z * CHUNK_XZ + x) * HEIGHT + y];
                    }
                }
            }
        }
        playerX = WIDTH / 2 + 0.5f;
        playerZ = DEPTH / 2 + 0.5f;
        playerY = InfdevTerrain.surfaceHeight(seed, WIDTH / 2, DEPTH / 2) + 1.0f;
        yaw = 0f;
    }

    int surfaceY(int x, int z) {
        for (int y = HEIGHT - 1; y >= 0; y--) {
            if (get(x, y, z) != Block.AIR && get(x, y, z) != Block.WATER) return y;
        }
        return SEA_LEVEL;
    }

    void save() {
        RecordStore store = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(blocks.length * 2 + 64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4D434643); // MCFC: named worlds with colored wool
            out.writeInt(seed);
            out.writeByte(mode);
            out.writeUTF(worldName == null ? "Mundo" : worldName);
            out.writeByte(selectedSlot);
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
            out.writeShort(editCount);
            for (int i = 0; i < editCount; i++) {
                out.writeInt(editX[i]); out.writeByte(editY[i]); out.writeInt(editZ[i]);
                out.writeByte(editId[i]); out.writeByte(editMeta[i]);
            }
            out.flush();
            byte[] data = bytes.toByteArray();

            String storeName = saveStoreName == null ? "mecraft-save" : saveStoreName;
            store = RecordStore.openRecordStore(storeName, true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length);
            else store.setRecord(1, data, 0, data.length);
            ByteArrayOutputStream infoBytes = new ByteArrayOutputStream(40);
            DataOutputStream info = new DataOutputStream(infoBytes);
            info.writeInt(0x4D434249); // MCBI: compact world-list metadata
            info.writeByte(mode);
            info.writeUTF(worldName == null ? "Mundo" : worldName);
            info.flush();
            byte[] infoData = infoBytes.toByteArray();
            if (store.getNumRecords() < 2) store.addRecord(infoData, 0, infoData.length);
            else store.setRecord(2, infoData, 0, infoData.length);
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

    static VoxelWorld load() { return load("mecraft-save"); }

    static VoxelWorld load(String storeName) {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(storeName, false);
            if (store.getNumRecords() == 0) return null;
            byte[] data = store.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int format = in.readInt();
            if (format != 0x4D434631 && format != 0x4D434632 && format != 0x4D434633 &&
                format != 0x4D434634 && format != 0x4D434635 && format != 0x4D434636 &&
                format != 0x4D434637 && format != 0x4D434638 && format != 0x4D434639 &&
                format != 0x4D434641 && format != 0x4D434642 && format != 0x4D434643) return null;
            int seed = in.readInt();
            int mode = in.readByte();
            boolean namedFormat = format == 0x4D434642 || format == 0x4D434643;
            VoxelWorld world = new VoxelWorld(seed, mode);
            world.worldName = namedFormat ? in.readUTF() : "Mundo guardado";
            world.saveStoreName = storeName;
            world.selectedSlot = in.readByte();
            boolean currentFormat = format == 0x4D434641 || format == 0x4D434642 || format == 0x4D434643;
            boolean latestFormat = format == 0x4D434643;
            if (format == 0x4D434635 || format == 0x4D434636 || format == 0x4D434637 ||
                format == 0x4D434638 || format == 0x4D434639 || currentFormat) {
                int inventoryLength = latestFormat ? world.inventory.length :
                                      (currentFormat ? world.inventory.length - ItemCatalog.WOOL_COLOR_COUNT :
                                      (format == 0x4D434639 ? MCF9_ITEM_COUNT :
                                      (format == 0x4D434638 ? MCF8_ITEM_COUNT :
                                       (format == 0x4D434637 ? MCF7_ITEM_COUNT : MCF6_ITEM_COUNT))));
                for (int i = 0; i < inventoryLength; i++) {
                    int newId = currentFormat ? i : (format == 0x4D434639 ? i :
                                (format == 0x4D434638 ? (i < 18 ? i : i + 1) :
                                 (format == 0x4D434637 ? (i < 17 ? i : i + 2) :
                                  (i < MCF6_BLOCK_COUNT ? i : i + 4))));
                    if (!currentFormat && newId >= 19) newId += Block.COUNT - 19;
                    world.inventory[newId] = in.readUnsignedByte();
                }
                for (int i = 0; i < world.hotbar.length; i++) {
                    int id = in.readUnsignedByte();
                    if (currentFormat) { }
                    else if (format == 0x4D434638 && id >= 18) id++;
                    else if (format == 0x4D434637 && id >= 17) id += 2;
                    else if (format != 0x4D434637 && format != 0x4D434638 &&
                             format != 0x4D434639 && id >= MCF6_BLOCK_COUNT) id += 4;
                    if (!currentFormat && id >= 19) id += Block.COUNT - 19;
                    world.hotbar[i] = id > Block.AIR && id < ItemCatalog.COUNT ? id : Block.DIRT;
                }
                for (int i = 0; i < inventoryLength; i++) {
                    int newId = currentFormat ? i : (format == 0x4D434639 ? i :
                                (format == 0x4D434638 ? (i < 18 ? i : i + 1) :
                                 (format == 0x4D434637 ? (i < 17 ? i : i + 2) :
                                  (i < MCF6_BLOCK_COUNT ? i : i + 4))));
                    if (!currentFormat && newId >= 19) newId += Block.COUNT - 19;
                    world.itemDamage[newId] = in.readUnsignedShort();
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
            in.readFully(world.blocks);
            if (format == 0x4D434634 || format == 0x4D434635 || format == 0x4D434636 ||
                format == 0x4D434637 || format == 0x4D434638 || format == 0x4D434639 ||
                currentFormat)
                in.readFully(world.waterLevels);
            if (in.available() >= 6 && in.readInt() == INFDEV_EDIT_MAGIC) {
                int count = in.readUnsignedShort();
                for (int i = 0; i < count; i++) {
                    int x = in.readInt(), y = in.readUnsignedByte(), z = in.readInt();
                    byte id = in.readByte(), meta = in.readByte();
                    int edit = world.ensureEdit(x, y, z);
                    if (edit >= 0) { world.editId[edit] = id; world.editMeta[edit] = meta; }
                }
            }
            if (format != 0x4D434636 && format != 0x4D434637 && format != 0x4D434638 &&
                format != 0x4D434639 && !currentFormat)
                world.migrateLegacyFurnaces();
            world.queueSavedFlowingWater();
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
