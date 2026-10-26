package mecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/** Small device-wide settings kept separately from world saves. */
final class GameSettings {
    private static final String STORE = "mecraft-settings";
    private static final int MAGIC = 0x4D435354; // MCST
    private static final int VERSION = 3;

    private GameSettings() { }

    static int loadFrameSkip() {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(STORE, false);
            if (store.getNumRecords() == 0) return 0;
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(store.getRecord(1)));
            if (in.readInt() != MAGIC) return 0;
            int value = in.readUnsignedByte();
            return value > 2 ? 0 : value;
        } catch (Exception unavailable) {
            return 0;
        } finally {
            if (store != null) try { store.closeRecordStore(); }
            catch (RecordStoreException ignored) { }
        }
    }

    static void saveFrameSkip(int value) {
        if (value < 0) value = 0;
        if (value > 2) value = 2;
        RecordStore store = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(5);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(value);
            out.flush();
            byte[] data = bytes.toByteArray();
            store = RecordStore.openRecordStore(STORE, true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length);
            else store.setRecord(1, data, 0, data.length);
        } catch (Exception ignored) {
            // Keep the current session usable when the device's RMS is full.
        } finally {
            if (store != null) try { store.closeRecordStore(); }
            catch (RecordStoreException ignored) { }
        }
    }

    static void load(int[] values, int[] controlCodes, boolean[] customControls) {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(STORE, false);
            if (store.getNumRecords() == 0) return;
            byte[] data = store.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC) return;
            int versionOrFrameSkip = in.readUnsignedByte();
            if (data.length == 5) {
                values[0] = versionOrFrameSkip > 2 ? 0 : versionOrFrameSkip;
                return;
            }
            if (versionOrFrameSkip < 1 || versionOrFrameSkip > VERSION) return;
            int savedValues = versionOrFrameSkip == 1 ? 3 :
                    (versionOrFrameSkip == 2 ? 4 : values.length);
            for (int i = 0; i < savedValues; i++) {
                int value = in.readInt();
                if (i < values.length) values[i] = value;
            }
            if (versionOrFrameSkip < 2 || in.available() < 1) return;
            int count = in.readUnsignedByte();
            for (int i = 0; i < count; i++) {
                boolean custom = in.readBoolean();
                int keyCode = in.readInt();
                if (i < customControls.length) {
                    customControls[i] = custom;
                    controlCodes[i] = keyCode;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }

    static void save(int[] values, int[] controlCodes, boolean[] customControls) {
        RecordStore store = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            for (int i = 0; i < values.length; i++) out.writeInt(values[i]);
            out.writeByte(customControls.length);
            for (int i = 0; i < customControls.length; i++) {
                out.writeBoolean(customControls[i]);
                out.writeInt(controlCodes[i]);
            }
            out.flush();
            byte[] data = bytes.toByteArray();
            store = RecordStore.openRecordStore(STORE, true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length);
            else store.setRecord(1, data, 0, data.length);
        } catch (Exception ignored) {
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (RecordStoreException ignored) { }
        }
    }
}
