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
    private static final int VERSION = 7;

    private GameSettings() { }

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
                // Old standalone frameskip preference: use the new default.
                values[0] = 20;
                return;
            }
            if (versionOrFrameSkip < 1 || versionOrFrameSkip > VERSION) return;
            int savedValues = versionOrFrameSkip == 1 ? 3 :
                    (versionOrFrameSkip == 2 ? 4 :
                    (versionOrFrameSkip == 3 ? 5 :
                    (versionOrFrameSkip == 4 ? 6 :
                    (versionOrFrameSkip < 7 ? 7 : values.length))));
            for (int i = 0; i < savedValues; i++) {
                int value = in.readInt();
                if (i < values.length) values[i] = value;
            }
            // Prior settings stored a frameskip mode in slot zero. The new
            // setting stores an FPS limit, so migrate old installs to 20 FPS.
            if (versionOrFrameSkip < 6 && values.length > 0) values[0] = 20;
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
