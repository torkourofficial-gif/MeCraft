package mecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.rms.RecordStore;

/** Small RMS-backed store for user preferences and control remapping. */
final class GameSettings {
    private static final String STORE_NAME = "mecraft-settings";
    private static final int MAGIC = 0x4D435354;
    private static final int VERSION = 1;

    private GameSettings() { }

    static void load(int[] values, int[] controlCodes, boolean[] customControls) {
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore(STORE_NAME, true);
            if (store.getNumRecords() == 0) return;
            byte[] data = store.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) return;
            for (int i = 0; i < values.length; i++) values[i] = in.readInt();
            int count = in.readUnsignedByte();
            int limit = Math.min(count, customControls.length);
            for (int i = 0; i < count; i++) {
                boolean custom = in.readBoolean();
                int keyCode = in.readInt();
                if (i < limit) {
                    customControls[i] = custom;
                    controlCodes[i] = keyCode;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (Exception ignored) { }
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
            store = RecordStore.openRecordStore(STORE_NAME, true);
            if (store.getNumRecords() == 0) store.addRecord(data, 0, data.length);
            else store.setRecord(1, data, 0, data.length);
        } catch (Exception ignored) {
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (Exception ignored) { }
        }
    }
}
