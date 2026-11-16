package mecraft;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/**
 * Alpha-style rails: thin textured quads just above the supporting block,
 * grouped by chunk so rebuilding rails does not allocate one mesh per tile.
 */
final class RailSystem {
    private static final int MAX_RAILS_PER_CHUNK = VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_Y;
    final Group group = new Group();
    private final Texture2D atlas;
    private final Appearance appearance;
    private static final int WINDOW_RADIUS = 2;
    private static final int WINDOW_SIZE = WINDOW_RADIUS * 2 + 1;
    private final Mesh[] meshes = new Mesh[WINDOW_SIZE * WINDOW_SIZE * VoxelWorld.CHUNKS_Y];
    // Reused Java-side staging arrays. Grow only to the largest rail chunk
    // actually encountered; never reserve the theoretical maximum at startup.
    private short[] railPositionScratch = new short[0];
    private short[] railUvScratch = new short[0];
    private int railScratchCapacity;
    private int builtRevision = Integer.MIN_VALUE;
    private int builtCenterX = Integer.MIN_VALUE, builtCenterZ = Integer.MIN_VALUE;

    RailSystem(Texture2D atlas) {
        this.atlas = atlas;
        appearance = new Appearance();
        appearance.setTexture(0, atlas);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        material.setColor(Material.EMISSIVE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setDepthTestEnable(true);
        compositing.setDepthWriteEnable(false);
        compositing.setAlphaThreshold(0.15f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
    }

    private static final int MAX_CHUNKS_PER_UPDATE = 4;
    private int scanCursor;
    private int scanRadius = WINDOW_RADIUS;
    private int scanRevision = Integer.MIN_VALUE;
    private boolean scanPending;

    /**
     * Incremental rail streamer. The old implementation scanned the entire
     * 5x5xvertical window twice in one paint callback. That could issue over
     * a million world lookups at once and stall the game when crossing a chunk.
     * Work is now spread over frames and reads only stored blocks/edits:
     * rails are player-placed and never part of procedural terrain.
     */
    void update(VoxelWorld world, int requestedRadius) {
        int radius = requestedRadius;
        if (radius < 0) radius = 0;
        if (radius > WINDOW_RADIUS) radius = WINDOW_RADIUS;
        int centerX = floorDiv((int)world.playerX, VoxelWorld.CHUNK_XZ);
        int centerZ = floorDiv((int)world.playerZ, VoxelWorld.CHUNK_XZ);
        boolean windowMoved = centerX != builtCenterX || centerZ != builtCenterZ;
        boolean radiusChanged = radius != scanRadius;

        if (!scanPending && builtRevision == world.revision &&
                !world.fullRebuild && !windowMoved && !radiusChanged) return;

        boolean restart = world.fullRebuild || windowMoved || radiusChanged ||
                builtRevision == Integer.MIN_VALUE;
        if (!restart && !scanPending && builtRevision != Integer.MIN_VALUE) {
            restart = railAffected(world);
            if (!restart) {
                builtRevision = world.revision;
                return;
            }
        } else if (!restart && scanPending && world.revision != scanRevision) {
            // Non-rail edits do not invalidate a rail scan already in progress.
            if (railAffected(world)) restart = true;
            else scanRevision = world.revision;
        }

        if (restart) {
            clearMeshes();
            builtCenterX = centerX;
            builtCenterZ = centerZ;
            scanRadius = radius;
            scanCursor = 0;
            scanRevision = world.revision;
            scanPending = true;
        }

        int size = scanRadius * 2 + 1;
        int total = size * size * VoxelWorld.CHUNKS_Y;
        int processed = 0;
        while (scanPending && scanCursor < total &&
                processed < MAX_CHUNKS_PER_UPDATE) {
            int cursor = scanCursor++;
            int rx = cursor % size;
            int rz = (cursor / size) % size;
            int cy = cursor / (size * size);
            int cx = builtCenterX - scanRadius + rx;
            int cz = builtCenterZ - scanRadius + rz;
            int slot = chunkIndex(rx + WINDOW_RADIUS - scanRadius,
                    cy, rz + WINDOW_RADIUS - scanRadius);
            rebuildChunk(world, slot, cx, cy, cz);
            processed++;
        }

        if (scanPending && scanCursor >= total) {
            scanPending = false;
            builtRevision = scanRevision;
        }
    }

    private boolean railAffected(VoxelWorld world) {
        int x = world.changedX, y = world.changedY, z = world.changedZ;
        return world.changedOldId == Block.RAIL ||
                world.getTorchCandidate(x, y, z) == Block.RAIL ||
                world.getTorchCandidate(x - 1, y, z) == Block.RAIL ||
                world.getTorchCandidate(x + 1, y, z) == Block.RAIL ||
                world.getTorchCandidate(x, y - 1, z) == Block.RAIL ||
                world.getTorchCandidate(x, y + 1, z) == Block.RAIL ||
                world.getTorchCandidate(x, y, z - 1) == Block.RAIL ||
                world.getTorchCandidate(x, y, z + 1) == Block.RAIL;
    }

    private void clearMeshes() {
        for (int i = 0; i < meshes.length; i++) {
            if (meshes[i] != null) group.removeChild(meshes[i]);
            meshes[i] = null;
        }
    }

    /** Build one rail chunk, with no procedural-terrain lookups. */
    private void rebuildChunk(VoxelWorld world, int slot, int cx, int cy, int cz) {
        if (meshes[slot] != null) {
            group.removeChild(meshes[slot]);
            meshes[slot] = null;
        }
        int x0 = cx * VoxelWorld.CHUNK_XZ;
        int y0 = cy * VoxelWorld.CHUNK_Y;
        int z0 = cz * VoxelWorld.CHUNK_XZ;
        int count = 0;
        for (int y = y0; y < y0 + VoxelWorld.CHUNK_Y; y++)
            for (int z = z0; z < z0 + VoxelWorld.CHUNK_XZ; z++)
                for (int x = x0; x < x0 + VoxelWorld.CHUNK_XZ; x++)
                    if (world.getTorchCandidate(x, y, z) == Block.RAIL) count++;
        if (count == 0) return;

        if (count > railScratchCapacity) {
            railPositionScratch = new short[count * 12];
            railUvScratch = new short[count * 8];
            railScratchCapacity = count;
        }
        short[] positions = railPositionScratch;
        short[] uv = railUvScratch;
        int rail = 0;
        for (int y = y0; y < y0 + VoxelWorld.CHUNK_Y; y++) {
            for (int z = z0; z < z0 + VoxelWorld.CHUNK_XZ; z++) {
                for (int x = x0; x < x0 + VoxelWorld.CHUNK_XZ; x++) {
                    if (world.getTorchCandidate(x, y, z) != Block.RAIL) continue;
                    float left = x - x0, right = left + 1.0f;
                    float front = z - z0, back = front + 1.0f;
                    int localY = y - y0;
                    int orientation = world.railMetadata(x, y, z);
                    float lowY = localY + 0.075f, highY = localY + 1.075f;
                    float yLeftFront = lowY, yLeftBack = lowY;
                    float yRightFront = lowY, yRightBack = lowY;
                    if (orientation == 2) { yRightFront = highY; yRightBack = highY; }
                    else if (orientation == 3) { yLeftFront = highY; yLeftBack = highY; }
                    else if (orientation == 4) { yLeftFront = highY; yRightFront = highY; }
                    else if (orientation == 5) { yLeftBack = highY; yRightBack = highY; }
                    int p = rail * 12;
                    positions[p] = (short)(left * 256); positions[p + 1] = (short)(yLeftFront * 256); positions[p + 2] = (short)(front * 256);
                    positions[p + 3] = (short)(left * 256); positions[p + 4] = (short)(yLeftBack * 256); positions[p + 5] = (short)(back * 256);
                    positions[p + 6] = (short)(right * 256); positions[p + 7] = (short)(yRightFront * 256); positions[p + 8] = (short)(front * 256);
                    positions[p + 9] = (short)(right * 256); positions[p + 10] = (short)(yRightBack * 256); positions[p + 11] = (short)(back * 256);
                    int tile = orientation >= 6 ? 112 : 128;
                    int tileU = (tile & 15) * 16, tileV = (tile >> 4) * 16;
                    int u = rail * 8;
                    if (orientation == 0 || orientation == 4 || orientation == 5 ||
                            orientation == 8 || orientation == 9) {
                        uv[u] = (short)tileU; uv[u + 1] = (short)(tileV + 16);
                        uv[u + 2] = (short)(tileU + 16); uv[u + 3] = (short)(tileV + 16);
                        uv[u + 4] = (short)tileU; uv[u + 5] = (short)tileV;
                        uv[u + 6] = (short)(tileU + 16); uv[u + 7] = (short)tileV;
                    } else {
                        uv[u] = (short)(tileU + 16); uv[u + 1] = (short)(tileV + 16);
                        uv[u + 2] = (short)(tileU + 16); uv[u + 3] = (short)tileV;
                        uv[u + 4] = (short)tileU; uv[u + 5] = (short)(tileV + 16);
                        uv[u + 6] = (short)tileU; uv[u + 7] = (short)tileV;
                    }
                    rail++;
                }
            }
        }

        VertexArray pa = new VertexArray(count * 4, 3, 2);
        pa.set(0, count * 4, positions);
        VertexArray ta = new VertexArray(count * 4, 2, 2);
        ta.set(0, count * 4, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 256.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
        int[] stripCounts = new int[count];
        for (int i = 0; i < count; i++) stripCounts[i] = 4;
        Mesh mesh = new Mesh(vb, new TriangleStripArray(0, stripCounts), appearance);
        mesh.setTranslation(x0, y0, z0);
        meshes[slot] = mesh;
        group.addChild(mesh);
    }

    private int floorDiv(int value, int divisor) {
        int result = value / divisor;
        return value < 0 && value % divisor != 0 ? result - 1 : result;
    }

    private int chunkIndex(int rx, int cy, int rz) {
        return (cy * WINDOW_SIZE + rz) * WINDOW_SIZE + rx;
    }
}
