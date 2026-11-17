package mecraft;

/**
 * Dedicated asynchronous terrain generator. Requests are coalesced by world
 * and chunk coordinate; nearest pending requests are generated first.
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

    static void request(VoxelWorld world) {
        if (world != null) worker();
    }

    static void requestChunk(VoxelWorld world, int cx, int cz) {
        if (world != null) worker().enqueue(world, cx, cz);
    }

    private static float distanceSquared(VoxelWorld world, int cx, int cz) {
        float dx = cx * VoxelWorld.CHUNK_XZ + VoxelWorld.CHUNK_XZ * 0.5f - world.playerX;
        float dz = cz * VoxelWorld.CHUNK_XZ + VoxelWorld.CHUNK_XZ * 0.5f - world.playerZ;
        return dx * dx + dz * dz;
    }

    private synchronized void enqueue(VoxelWorld world, int cx, int cz) {
        if (!running) return;
        for (int i = 0; i < count; i++) {
            if (worlds[i] == world && chunkX[i] == cx && chunkZ[i] == cz) return;
        }

        if (count == QUEUE_SIZE) {
            // Replace only the farthest pending request; never evict a nearer
            // chunk merely because it was queued earlier.
            int farthest = 0;
            float farthestDistance = distanceSquared(worlds[0], chunkX[0], chunkZ[0]);
            for (int i = 1; i < count; i++) {
                float distance = distanceSquared(worlds[i], chunkX[i], chunkZ[i]);
                if (distance > farthestDistance) {
                    farthestDistance = distance;
                    farthest = i;
                }
            }
            if (distanceSquared(world, cx, cz) >= farthestDistance) return;
            removeAt(farthest);
        }
        worlds[count] = world;
        chunkX[count] = cx;
        chunkZ[count] = cz;
        count++;
        notifyAll();
    }

    private void removeAt(int index) {
        for (int i = index + 1; i < count; i++) {
            worlds[i - 1] = worlds[i];
            chunkX[i - 1] = chunkX[i];
            chunkZ[i - 1] = chunkZ[i];
        }
        count--;
        worlds[count] = null;
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

                // Re-evaluate against the player's current position at dequeue
                // time so movement does not leave stale FIFO work ahead.
                int nearest = 0;
                float nearestDistance = distanceSquared(worlds[0], chunkX[0], chunkZ[0]);
                for (int i = 1; i < count; i++) {
                    float distance = distanceSquared(worlds[i], chunkX[i], chunkZ[i]);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = i;
                    }
                }
                world = worlds[nearest];
                cx = chunkX[nearest];
                cz = chunkZ[nearest];
                removeAt(nearest);
            }

            try {
                world.generateRequestedChunk(cx, cz);
            } catch (OutOfMemoryError exhausted) {
                // Keep the game alive; a later lookup can enqueue it again.
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
