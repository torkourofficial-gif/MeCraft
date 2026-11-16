package mecraft;

/**
 * Dedicated asynchronous terrain generator. Requests are coalesced by world
 * and chunk coordinate; neither procedural generation nor RMS I/O runs on the
 * game or chunk-meshing thread.
 */
final class ChunkPrefetchWorker implements Runnable {
    private static final int QUEUE_SIZE = 16;
    private static ChunkPrefetchWorker instance;

    private final VoxelWorld[] worlds = new VoxelWorld[QUEUE_SIZE];
    private final int[] chunkX = new int[QUEUE_SIZE];
    private final int[] chunkZ = new int[QUEUE_SIZE];
    private int count;
    private boolean running = true;
    private final Thread thread;

    private ChunkPrefetchWorker() {
        thread = new Thread(this);
        try { thread.setPriority(Thread.MIN_PRIORITY); } catch (Throwable ignored) { }
        thread.start();
    }

    private static ChunkPrefetchWorker worker() {
        synchronized (ChunkPrefetchWorker.class) {
            if (instance == null) instance = new ChunkPrefetchWorker();
            return instance;
        }
    }

    /** Keep the worker alive for worlds that have not requested an outer chunk yet. */
    static void request(VoxelWorld world) {
        if (world != null) worker();
    }

    static void requestChunk(VoxelWorld world, int cx, int cz) {
        if (world == null) return;
        worker().enqueue(world, cx, cz);
    }

    private synchronized void enqueue(VoxelWorld world, int cx, int cz) {
        if (!running) return;
        for (int i = 0; i < count; i++) {
            if (worlds[i] == world && chunkX[i] == cx && chunkZ[i] == cz) return;
        }

        // Keep the queue bounded. When full, discard its oldest request so
        // chunks around the player's current position take priority.
        if (count == QUEUE_SIZE) {
            for (int i = 1; i < count; i++) {
                worlds[i - 1] = worlds[i];
                chunkX[i - 1] = chunkX[i];
                chunkZ[i - 1] = chunkZ[i];
            }
            count--;
        }
        worlds[count] = world;
        chunkX[count] = cx;
        chunkZ[count] = cz;
        count++;
        notifyAll();
    }

    public void run() {
        while (true) {
            VoxelWorld world;
            int cx, cz;
            synchronized (this) {
                while (running && count == 0) {
                    try { wait(); }
                    catch (InterruptedException ignored) { if (!running) return; }
                }
                if (!running) return;
                world = worlds[0];
                cx = chunkX[0];
                cz = chunkZ[0];
                for (int i = 1; i < count; i++) {
                    worlds[i - 1] = worlds[i];
                    chunkX[i - 1] = chunkX[i];
                    chunkZ[i - 1] = chunkZ[i];
                }
                count--;
                worlds[count] = null;
            }

            try {
                world.generateRequestedChunk(cx, cz);
            } catch (OutOfMemoryError exhausted) {
                // Keep the game alive; a later lookup can enqueue the chunk again.
            } catch (RuntimeException unavailable) {
                // A failed chunk request must not terminate the worker.
            }
        }
    }

    static void shutdown() {
        ChunkPrefetchWorker worker;
        synchronized (ChunkPrefetchWorker.class) {
            worker = instance;
            instance = null;
        }
        if (worker != null) worker.stopWorker();
    }

    private synchronized void stopWorker() {
        running = false;
        count = 0;
        notifyAll();
        thread.interrupt();
    }
}
