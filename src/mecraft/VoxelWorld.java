package mecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/** A small, single-island Indev world stored as one byte per voxel. */
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
    // Keep Indev's 24,000-tick lighting curve, but run it at 10x so a full cycle
    // is visible in a short Java ME play session rather than taking 20 minutes.
    private static final int DAY_TICKS_PER_UPDATE = 10;
    private static final int MCF6_BLOCK_COUNT = 15;
    private static final int MCF6_ITEM_COUNT = 45;
    private static final int MCF7_ITEM_COUNT = 47;
    private static final int MCF8_ITEM_COUNT = 48;
    private static final int MCF9_ITEM_COUNT = 51;
    static final int DAY_LENGTH = 24000;

    final byte[] blocks = new byte[BLOCK_COUNT];
    private final byte[] waterLevels = new byte[BLOCK_COUNT];
    private final byte[] explosionBits = new byte[(BLOCK_COUNT + 7) / 8];
    private static final int FLUID_QUEUE_SIZE = 2048;
    private final short[] fluidQueue = new short[FLUID_QUEUE_SIZE];
    private final byte[] fluidQueued = new byte[(BLOCK_COUNT + 7) / 8];
    /** Temporary generation-only map; discarded before play to keep the heap small. */
    private byte[] generationSurface;
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
        generate();
    }

    private int index(int x, int y, int z) {
        return (y * DEPTH + z) * WIDTH + x;
    }

    byte get(int x, int y, int z) {
        if (x < 0 || x >= WIDTH || z < 0 || z >= DEPTH || y < 0 || y >= HEIGHT) {
            return Block.AIR;
        }
        return blocks[index(x, y, z)];
    }

    boolean set(int x, int y, int z, byte id) {
        return setBlock(x, y, z, id, 0);
    }

    /** Indev's shell ray blast, scaled to TNT radius 4 and stored in a reusable bitset. */
    void explode(float x, float y, float z, float radius, DroppedItemSystem drops) {
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
                    else if (Block.dropsItem(id) && randomFloat() < 0.3f)
                        drops.spawn(Block.dropId(id), bx, by, bz);
                    set(bx, by, bz, Block.AIR);
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
        if (x < 0 || x >= WIDTH || z < 0 || z >= DEPTH || y <= 0 || y >= HEIGHT) return false;
        if (id < 0 || id >= Block.COUNT) return false;
        int i = index(x, y, z);
        if (blocks[i] == id) return false;
        blocks[i] = id;
        waterLevels[i] = id == Block.WATER ? (byte)fluidLevel : 0;
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
        Random random = new Random(seed);
        int centerX = WIDTH / 2;
        int centerZ = DEPTH / 2;
        generationSurface = new byte[WIDTH * DEPTH];
        for (int z = 0; z < DEPTH; z++) {
            for (int x = 0; x < WIDTH; x++) {
                int dx = x - centerX;
                int dz = z - centerZ;
                int radial = (dx * dx * 100 / (centerX * centerX)) +
                             (dz * dz * 100 / (centerZ * centerZ));
                int noise = noise(x, z, seed);
                int height = 10 + noise / 4 - Math.max(0, radial - 20) / 12;
                if (height < 3) height = 3;
                if (height > 17) height = 17;
                generationSurface[z * WIDTH + x] = (byte)height;
                boolean beach = height <= SEA_LEVEL + 1;

                for (int y = 0; y <= height; y++) {
                    byte id;
                    if (y == 0) id = Block.BEDROCK;
                    else if (y == height) id = beach ? Block.SAND : Block.GRASS;
                    else if (y >= height - 3) id = beach ? Block.SAND : Block.DIRT;
                    else if (y < 5 && random.nextInt(13) == 0) id = Block.COAL;
                    else if (y < 5 && random.nextInt(47) == 0) id = Block.IRON;
                    else id = Block.STONE;
                    blocks[index(x, y, z)] = id;
                }
                for (int y = height + 1; y <= SEA_LEVEL; y++) {
                    blocks[index(x, y, z)] = Block.WATER;
                }
            }
        }

        carveCaves(random);

        // Sparse, deterministic trees keep the small island readable at phone resolution.
        for (int z = 4; z < DEPTH - 4; z += 5) {
            for (int x = 4; x < WIDTH - 4; x += 5) {
                if (Math.abs(x - centerX) <= 2 && Math.abs(z - centerZ) <= 2) continue;
                if (random.nextInt(4) != 0) continue;
                int y = surfaceY(x, z);
                if (get(x, y, z) != Block.GRASS || y + 5 >= HEIGHT) continue;
                for (int t = 1; t <= 3; t++) blocks[index(x, y + t, z)] = Block.LOG;
                for (int oz = -2; oz <= 2; oz++) {
                    for (int ox = -2; ox <= 2; ox++) {
                        for (int oy = 2; oy <= 4; oy++) {
                            if (Math.abs(ox) + Math.abs(oz) + (oy == 4 ? 1 : 0) > 3) continue;
                            int lx = x + ox, ly = y + oy, lz = z + oz;
                            if (get(lx, ly, lz) == Block.AIR) blocks[index(lx, ly, lz)] = Block.LEAVES;
                        }
                    }
                }
            }
        }
        placeFlowers(random, centerX, centerZ);
        playerX = centerX + 0.5f;
        playerZ = centerZ + 0.5f;
        playerY = surfaceY(centerX, centerZ) + 1.0f;
        yaw = 0f;
        generationSurface = null;
    }

    /** Carve a few short, low-cost tunnels below the island's surface. */
    private void carveCaves(Random random) {
        for (int cave = 0; cave < 10; cave++) {
            float x = 3 + random.nextInt(WIDTH - 6);
            float z = 3 + random.nextInt(DEPTH - 6);
            int surface = surfaceY((int)x, (int)z);
            int maxY = surface - 4;
            if (maxY > 12) maxY = 12;
            if (maxY < 3) continue;
            float y = 2 + random.nextInt(maxY - 1);
            float angle = random.nextFloat() * (float)(Math.PI * 2.0);
            float slope = random.nextFloat() * 0.24f - 0.12f;
            float radius = 1.05f + random.nextFloat() * 0.3f;
            float verticalRadius = 1.0f + random.nextFloat() * 0.25f;
            int steps = 3 + random.nextInt(4);

            for (int step = 0; step < steps; step++) {
                x += (float)Math.cos(angle) * 0.8f;
                z += (float)Math.sin(angle) * 0.8f;
                y += slope;
                angle += random.nextFloat() * 0.5f - 0.25f;
                int cx = (int)x, cy = (int)y, cz = (int)z;
                int extent = (int)radius + 1;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -extent; dz <= extent; dz++) {
                        for (int dx = -extent; dx <= extent; dx++) {
                            int bx = cx + dx, by = cy + dy, bz = cz + dz;
                            if (by <= 1 || by >= HEIGHT - 1 || bx <= 1 || bx >= WIDTH - 2 ||
                                bz <= 1 || bz >= DEPTH - 2) continue;
                            float horizontal = (dx * dx + dz * dz) / (radius * radius);
                            float vertical = dy * dy / (verticalRadius * verticalRadius);
                            if (horizontal + vertical > 1.0f || by >= surfaceY(bx, bz) - 1) continue;
                            byte block = get(bx, by, bz);
                            if (block == Block.STONE || block == Block.DIRT ||
                                block == Block.COAL || block == Block.IRON) {
                                blocks[index(bx, by, bz)] = Block.AIR;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Scatter both Indev flower variants over clear grass patches. */
    private void placeFlowers(Random random, int centerX, int centerZ) {
        for (int z = 2; z < DEPTH - 2; z++) {
            for (int x = 2; x < WIDTH - 2; x++) {
                if (Math.abs(x - centerX) <= 3 && Math.abs(z - centerZ) <= 3) continue;
                if (random.nextInt(34) != 0) continue;
                int y = surfaceY(x, z);
                if (get(x, y, z) != Block.GRASS || y + 1 >= HEIGHT ||
                    get(x, y + 1, z) != Block.AIR) continue;
                blocks[index(x, y + 1, z)] = random.nextInt(2) == 0 ?
                    Block.RED_FLOWER : Block.YELLOW_FLOWER;
            }
        }
    }

    int surfaceY(int x, int z) {
        if (generationSurface != null && x >= 0 && x < WIDTH && z >= 0 && z < DEPTH) {
            return generationSurface[z * WIDTH + x] & 255;
        }
        for (int y = HEIGHT - 1; y >= 0; y--) {
            if (get(x, y, z) != Block.AIR && get(x, y, z) != Block.WATER) return y;
        }
        return SEA_LEVEL;
    }

    private static int noise(int x, int z, int seed) {
        int n = x * 73428767 ^ z * 912931 ^ seed * 42317861;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return (n & 7) - 3;
    }

    void save() {
        RecordStore store = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(blocks.length * 2 + 64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4D434642); // MCFB: named worlds
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
                format != 0x4D434641 && format != 0x4D434642) return null;
            int seed = in.readInt();
            int mode = in.readByte();
            boolean namedFormat = format == 0x4D434642;
            VoxelWorld world = new VoxelWorld(seed, mode);
            world.worldName = namedFormat ? in.readUTF() : "Mundo guardado";
            world.saveStoreName = storeName;
            world.selectedSlot = in.readByte();
            boolean currentFormat = format == 0x4D434641 || namedFormat;
            if (format == 0x4D434635 || format == 0x4D434636 || format == 0x4D434637 ||
                format == 0x4D434638 || format == 0x4D434639 || currentFormat) {
                int inventoryLength = currentFormat ? world.inventory.length :
                                      (format == 0x4D434639 ? MCF9_ITEM_COUNT :
                                      (format == 0x4D434638 ? MCF8_ITEM_COUNT :
                                       (format == 0x4D434637 ? MCF7_ITEM_COUNT : MCF6_ITEM_COUNT)));
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

    /** MCF1–MCF5 used block/item ID 13 for the removed furnace. */
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
