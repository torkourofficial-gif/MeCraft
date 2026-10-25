package mecraft;

import java.io.InputStream;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

/** Small MMAPI music state machine; it keeps only one MIDI player open. */
final class MusicController implements PlayerListener {
    static final int OFF = 0;
    static final int MENU = 1;
    static final int GAMEPLAY = 2;

    private static final String[] TRACKS = {
        "menu.mid", "calm1.mid", "calm2.mid", "calm3.mid", "calm4.mid"
    };
    private static final int CALM_TRACKS = 4;
    private static final long SILENCE_MILLIS = 10000L;

    private volatile Player player;
    private InputStream stream;
    private int mode = OFF;
    private int failedTracks;
    private int lastCalmTrack = -1;
    private int randomState = (int)System.currentTimeMillis() ^ 0x4D494449;
    private long silenceUntil;
    private volatile boolean ended;

    synchronized void update(int requestedMode) {
        if (requestedMode != mode) {
            mode = requestedMode;
            ended = false;
            silenceUntil = 0L;
            closePlayer();
            if (mode == MENU) startTrack(0);
            else if (mode == GAMEPLAY) startRandomCalm();
        }

        if (mode == OFF) return;
        if (ended) {
            ended = false;
            closePlayer();
            if (mode == MENU) startTrack(0);
            else silenceUntil = System.currentTimeMillis() + SILENCE_MILLIS;
        }
        if (mode == GAMEPLAY && player == null &&
                System.currentTimeMillis() >= silenceUntil) startRandomCalm();
        else if (mode == MENU && player == null && (failedTracks & 1) == 0) startTrack(0);
    }

    public void playerUpdate(Player source, String event, Object eventData) {
        if (source == player && PlayerListener.END_OF_MEDIA.equals(event)) ended = true;
    }

    synchronized void close() {
        mode = OFF;
        ended = false;
        closePlayer();
    }

    private void startRandomCalm() {
        int available = 0;
        for (int i = 0; i < CALM_TRACKS; i++) {
            if ((failedTracks & (1 << (i + 1))) == 0) available++;
        }
        if (available == 0) return;

        for (int attempt = 0; attempt < CALM_TRACKS; attempt++) {
            int track = nextRandom(CALM_TRACKS);
            if (track == lastCalmTrack && CALM_TRACKS > 1) track = (track + 1) % CALM_TRACKS;
            int bit = 1 << (track + 1);
            if ((failedTracks & bit) != 0) continue;
            if (startTrack(track + 1)) {
                lastCalmTrack = track;
                return;
            }
        }
    }

    private int nextRandom(int bound) {
        randomState = randomState * 1103515245 + 12345;
        if (randomState == 0) randomState = 0x13579BDF;
        return (randomState >>> 16) % bound;
    }

    private boolean startTrack(int track) {
        int bit = 1 << track;
        if ((failedTracks & bit) != 0) return false;
        InputStream input = null;
        Player next = null;
        try {
            input = GameAssets.stream(TRACKS[track]);
            next = Manager.createPlayer(input, "audio/midi");
            next.addPlayerListener(this);
            next.realize();
            if (track == 0) next.setLoopCount(-1);
            next.prefetch();
            stream = input;
            player = next;
            next.start();
            return true;
        } catch (Exception unavailable) {
            failedTracks |= bit;
        } catch (LinkageError unsupported) {
            failedTracks |= bit;
        }
        if (next != null && player == next) closePlayer();
        else closeLocal(next, input);
        return false;
    }

    private void closePlayer() {
        Player oldPlayer = player;
        InputStream oldStream = stream;
        player = null;
        stream = null;
        if (oldPlayer != null) {
            try { oldPlayer.removePlayerListener(this); } catch (Throwable ignored) { }
            try { oldPlayer.stop(); } catch (Throwable ignored) { }
            try { oldPlayer.close(); } catch (Throwable ignored) { }
        }
        closeStream(oldStream);
    }

    private void closeLocal(Player localPlayer, InputStream localStream) {
        if (localPlayer != null) {
            try { localPlayer.removePlayerListener(this); } catch (Throwable ignored) { }
            try { localPlayer.close(); } catch (Throwable ignored) { }
        }
        closeStream(localStream);
    }

    private void closeStream(InputStream input) {
        if (input != null) try { input.close(); } catch (Throwable ignored) { }
    }
}
