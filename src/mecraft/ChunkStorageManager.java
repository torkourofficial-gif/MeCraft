package mecraft;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Small persistent second-level cache for deterministic procedural terrain.
 *
 * Stores the nine most recently generated chunks in RMS. Each chunk is split
 * into two records so every RMS record stays comfortably below 16 KB. This
 * cache contains base terrain only; player edits remain in VoxelWorld's
 * existing edit journal and are applied on lookup.
 */
final class ChunkStorageManager {
    private static final int MAGIC = 0x4D43434B; // MCCK
    private static final int FORMAT_VERSION = 1;
    private static final int SLOT_COUNT = 9;
    private static final int PAGE_COUNT = 2;
    private static final int PAGE_BYTES = 8192;
    private static final int HEADER_BYTES = 23;
    private static final int RECORD_BYTES = HEADER_BYTES + PAGE_BYTES;

    private final String storeName;
    private final int seed;
    private final int worldType;
    private final boolean economicMode;
    private int nextSlot;

    ChunkStorageManager(String worldStoreName, int seed, int worldType,
                        boolean economicMode) {
        this.storeName = worldStoreName + "-chunks";
        this.seed = seed;
        this.worldType = worldType;
        this.economicMode = economicMode;
    }

    synchronized boolean load(int chunkX, int chunkZ, boolean nether,
                              byte[] target) {
        if (target == null || target.length < InfdevTerrain.CHUNK_VOLUME) return false;
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(storeName, false);
            if (store.getNumRecords() < SLOT_COUNT * PAGE_COUNT) return false;
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                int firstId = slot * PAGE_COUNT + 1;
                byte[] first = store.getRecord(firstId);
                if (!matches(first, chunkX, chunkZ, nether, 0)) continue;
                byte[] second = store.getRecord(firstId + 1);
                if (!matches(second, chunkX, chunkZ, nether, 1)) continue;
                System.arraycopy(first, HEADER_BYTES, target, 0, PAGE_BYTES);
                System.arraycopy(second, HEADER_BYTES, target, PAGE_BYTES, PAGE_BYTES);
                nextSlot = (slot + 1) % SLOT_COUNT;
                return true;
            }
        } catch (Exception ignored) {
            // A missing, full, or incompatible RMS cache is just a cache miss.
        } finally {
            if (store != null) {
                try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
            }
        }
        return false;
    }

    synchronized void store(int chunkX, int chunkZ, boolean nether,
                            byte[] source) {
        if (source == null || source.length < InfdevTerrain.CHUNK_VOLUME) return;
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(storeName, true);
            while (store.getNumRecords() < SLOT_COUNT * PAGE_COUNT)
                store.addRecord(new byte[0], 0, 0);

            int slot = -1;
            for (int candidate = 0; candidate < SLOT_COUNT; candidate++) {
                int firstId = candidate * PAGE_COUNT + 1;
                byte[] first = store.getRecord(firstId);
                if (matches(first, chunkX, chunkZ, nether, 0)) {
                    slot = candidate;
                    break;
                }
            }
            if (slot < 0) {
                slot = nextSlot;
                nextSlot = (nextSlot + 1) % SLOT_COUNT;
            }

            byte[] record = new byte[RECORD_BYTES];
            for (int page = 0; page < PAGE_COUNT; page++) {
                writeInt(record, 0, MAGIC);
                record[4] = (byte)FORMAT_VERSION;
                writeInt(record, 5, seed);
                record[9] = (byte)worldType;
                record[10] = (byte)(economicMode ? 1 : 0);
                record[11] = (byte)(nether ? 1 : 0);
                writeInt(record, 12, chunkX);
                writeInt(record, 16, chunkZ);
                record[20] = (byte)page;
                record[21] = (byte)(PAGE_BYTES >>> 8);
                record[22] = (byte)PAGE_BYTES;
                System.arraycopy(source, page * PAGE_BYTES, record,
                        HEADER_BYTES, PAGE_BYTES);
                store.setRecord(slot * PAGE_COUNT + page + 1,
                        record, 0, record.length);
            }
        } catch (Exception ignored) {
            // The world remains playable if persistent caching is unavailable.
        } catch (OutOfMemoryError ignored) {
            // Never let an optional cache allocation terminate gameplay.
        } finally {
            if (store != null) {
                try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
            }
        }
    }

    private boolean matches(byte[] record, int chunkX, int chunkZ,
                            boolean nether, int page) {
        if (record == null || record.length != RECORD_BYTES ||
                readInt(record, 0) != MAGIC ||
                (record[4] & 255) != FORMAT_VERSION ||
                readInt(record, 5) != seed ||
                (record[9] & 255) != worldType ||
                (record[10] != 0) != economicMode ||
                (record[11] != 0) != nether ||
                readInt(record, 12) != chunkX ||
                readInt(record, 16) != chunkZ ||
                (record[20] & 255) != page) return false;
        int length = ((record[21] & 255) << 8) | (record[22] & 255);
        return length == PAGE_BYTES;
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte)(value >>> 24);
        data[offset + 1] = (byte)(value >>> 16);
        data[offset + 2] = (byte)(value >>> 8);
        data[offset + 3] = (byte)value;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 255) << 24) |
                ((data[offset + 1] & 255) << 16) |
                ((data[offset + 2] & 255) << 8) |
                (data[offset + 3] & 255);
    }
}
