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
    private final int[] railX = new int[MAX_RAILS_PER_CHUNK];
    private final int[] railY = new int[MAX_RAILS_PER_CHUNK];
    private final int[] railZ = new int[MAX_RAILS_PER_CHUNK];
    private final int[] railMetadata = new int[MAX_RAILS_PER_CHUNK];
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

    void update(VoxelWorld world) {
        int centerX = floorDiv((int)world.playerX, VoxelWorld.CHUNK_XZ);
        int centerZ = floorDiv((int)world.playerZ, VoxelWorld.CHUNK_XZ);
        boolean windowMoved = centerX != builtCenterX || centerZ != builtCenterZ;
        if (builtRevision == world.revision && !world.fullRebuild && !windowMoved) return;

        // Only a rail edit can change rail geometry. Use non-blocking reads here:
        // this visual system must never enqueue procedural terrain generation.
        if (!windowMoved && builtRevision != Integer.MIN_VALUE && !world.fullRebuild) {
            int x = world.changedX, y = world.changedY, z = world.changedZ;
            boolean railAffected = world.changedOldId == Block.RAIL ||
                    world.getLoaded(x, y, z) == Block.RAIL ||
                    world.getLoaded(x - 1, y, z) == Block.RAIL ||
                    world.getLoaded(x + 1, y, z) == Block.RAIL ||
                    world.getLoaded(x, y - 1, z) == Block.RAIL ||
                    world.getLoaded(x, y + 1, z) == Block.RAIL ||
                    world.getLoaded(x, y, z - 1) == Block.RAIL ||
                    world.getLoaded(x, y, z + 1) == Block.RAIL;
            if (!railAffected) {
                builtRevision = world.revision;
                return;
            }
        }

        for (int i = 0; i < meshes.length; i++) {
            if (meshes[i] != null) group.removeChild(meshes[i]);
            meshes[i] = null;
        }

        int firstChunkX = centerX - WINDOW_RADIUS;
        int firstChunkZ = centerZ - WINDOW_RADIUS;
        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int rz = 0; rz < WINDOW_SIZE; rz++) {
                int cz = firstChunkZ + rz;
                for (int rx = 0; rx < WINDOW_SIZE; rx++) {
                    int cx = firstChunkX + rx;
                    int index = chunkIndex(rx, cy, rz);
                    int count = world.collectRailsInChunk(cx, cy, cz,
                            railX, railY, railZ, railMetadata, MAX_RAILS_PER_CHUNK);
                    if (count == 0) continue;

                    short[] positions = new short[count * 12];
                    short[] uv = new short[count * 8];
                    int[] stripCounts = new int[count];
                    for (int rail = 0; rail < count; rail++) {
                        stripCounts[rail] = 4;
                        float left = railX[rail] - cx * VoxelWorld.CHUNK_XZ;
                        float right = left + 1.0f;
                        float front = railZ[rail] - cz * VoxelWorld.CHUNK_XZ;
                        float back = front + 1.0f;
                        int localY = railY[rail] - cy * VoxelWorld.CHUNK_Y;
                        int orientation = railMetadata[rail];
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
                    }

                    VertexArray pa = new VertexArray(count * 4, 3, 2);
                    pa.set(0, count * 4, positions);
                    VertexArray ta = new VertexArray(count * 4, 2, 2);
                    ta.set(0, count * 4, uv);
                    VertexBuffer vb = new VertexBuffer();
                    vb.setPositions(pa, 1.0f / 256.0f, null);
                    vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
                    Mesh mesh = new Mesh(vb, new TriangleStripArray(0, stripCounts), appearance);
                    mesh.setTranslation(cx * VoxelWorld.CHUNK_XZ,
                            cy * VoxelWorld.CHUNK_Y, cz * VoxelWorld.CHUNK_XZ);
                    meshes[index] = mesh;
                    group.addChild(mesh);
                }
            }
        }
        builtCenterX = centerX;
        builtCenterZ = centerZ;
        builtRevision = world.revision;
    }

    private int floorDiv(int value, int divisor) {
        int result = value / divisor;
        return value < 0 && value % divisor != 0 ? result - 1 : result;
    }

    private int chunkIndex(int rx, int cy, int rz) {
        return (cy * WINDOW_SIZE + rz) * WINDOW_SIZE + rx;
    }
}
