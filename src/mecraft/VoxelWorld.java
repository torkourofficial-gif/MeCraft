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

    final byte[] blocks = new byte[WIDTH * HEIGHT * DEPTH];
    final int[] inventory = new int[9];
    int seed;
    int mode;
    int selectedSlot;
    float playerX;
    float playerY;
    float playerZ;
    float yaw;
    int revision;
    int changedX;
    int changedY;
    int changedZ;

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
        if (x < 0 || x >= WIDTH || z < 0 || z >= DEPTH || y <= 0 || y >= HEIGHT) return false;
        if (id < 0 || id >= Block.COUNT) return false;
        int i = index(x, y, z);
        if (blocks[i] == id) return false;
        blocks[i] = id;
        changedX = x;
        changedY = y;
        changedZ = z;
        revision++;
        return true;
    }

    private void generate() {
        Random random = new Random(seed);
        int centerX = WIDTH / 2;
        int centerZ = DEPTH / 2;
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
        playerX = centerX + 0.5f;
        playerZ = centerZ + 0.5f;
        playerY = surfaceY(centerX, centerZ) + 1.0f;
        yaw = 0f;
    }

    int surfaceY(int x, int z) {
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
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(blocks.length + 40);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4D434631); // MCF1
            out.writeInt(seed);
            out.writeByte(mode);
            out.writeByte(selectedSlot);
            for (int i = 0; i < inventory.length; i++) out.writeByte(inventory[i]);
            out.writeFloat(playerX);
            out.writeFloat(playerY);
            out.writeFloat(playerZ);
            out.writeFloat(yaw);
            out.write(blocks);
            out.flush();
            byte[] data = bytes.toByteArray();

            store = RecordStore.openRecordStore("mecraft-save", true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length);
            else store.setRecord(1, data, 0, data.length);
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

    static VoxelWorld load() {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore("mecraft-save", false);
            if (store.getNumRecords() == 0) return null;
            byte[] data = store.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 0x4D434631) return null;
            int seed = in.readInt();
            VoxelWorld world = new VoxelWorld(seed, in.readByte());
            world.selectedSlot = in.readByte();
            for (int i = 0; i < world.inventory.length; i++) world.inventory[i] = in.readUnsignedByte();
            world.playerX = in.readFloat();
            world.playerY = in.readFloat();
            world.playerZ = in.readFloat();
            world.yaw = in.readFloat();
            in.readFully(world.blocks);
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

    static boolean hasSave() {
        String[] names = RecordStore.listRecordStores();
        if (names == null) return false;
        for (int i = 0; i < names.length; i++) if ("mecraft-save".equals(names[i])) return true;
        return false;
    }
}

