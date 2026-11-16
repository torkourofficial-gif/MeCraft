package mecraft;

/**
 * Single-slot background terrain prefetcher.
 *
 * Only procedural voxel generation and RMS I/O run here. The renderer still
 * creates all M3G objects on the game thread, which is safer for MIDP devices
 * whose M3G implementations are not thread-safe.
 */
final class ChunkPrefetchWorker implements Runnable {
    private static ChunkPrefetchWorker instance;

    private VoxelWorld pendingWorld;
    private boolean running = true;
    private final Thread thread;

    private ChunkPrefetchWorker() {
        thread = new Thread(this);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    static void request(VoxelWorld world) {
        if (world == null || world.saveStoreName == null) return;
        ChunkPrefetchWorker worker;
        synchronized (ChunkPrefetchWorker.class) {
            if (instance == null) instance = new ChunkPrefetchWorker();
            worker = instance;
        }
        worker.enqueue(world);
    }

    private synchronized void enqueue(VoxelWorld world) {
        if (!running) return;
        // A one-slot queue intentionally coalesces rapid updates. The worker
        // always handles the newest active world instead of building a backlog.
        pendingWorld = world;
        notifyAll();
    }

    public void run() {
        while (true) {
            VoxelWorld world;
            synchronized (this) {
                while (running && pendingWorld == null) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                        if (!running) return;
                    }
                }
                if (!running) return;
                world = pendingWorld;
                pendingWorld = null;
            }

            try {
                // VoxelWorld serializes this operation with procedural reads,
                // so the shared 16-slot terrain cache cannot be corrupted.
                world.prefetchAhead();
            } catch (OutOfMemoryError exhausted) {
                // Prefetch is optional; leave normal on-demand generation intact.
            } catch (RuntimeException unavailable) {
                // A failed background request must never stop the game loop.
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
        pendingWorld = null;
        notifyAll();
        thread.interrupt();
    }
}
