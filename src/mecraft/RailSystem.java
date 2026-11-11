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

        // A normal world is procedural and unbounded. The old renderer scanned
        // only coordinates 0..47, so rails outside the spawn island were never
        // added to the scene even though they remained editable.
        if (!windowMoved && builtRevision != Integer.MIN_VALUE && !world.fullRebuild) {
            int x = world.changedX, y = world.changedY, z = world.changedZ;
            boolean railAffected = world.changedOldId == Block.RAIL ||
                    world.get(x, y, z) == Block.RAIL ||
                    world.get(x - 1, y, z) == Block.RAIL ||
                    world.get(x + 1, y, z) == Block.RAIL ||
                    world.get(x, y - 1, z) == Block.RAIL ||
                    world.get(x, y + 1, z) == Block.RAIL ||
                    world.get(x, y, z - 1) == Block.RAIL ||
                    world.get(x, y, z + 1) == Block.RAIL;
            if (!railAffected) {
                builtRevision = world.revision;
                return;
            }
        }

        for (int i = 0; i < meshes.length; i++) {
            if (meshes[i] != null) group.removeChild(meshes[i]);
            meshes[i] = null;
        }
        int chunkCount = meshes.length;
        int[] counts = new int[chunkCount];
        int firstChunkX = centerX - WINDOW_RADIUS;
        int firstChunkZ = centerZ - WINDOW_RADIUS;
        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int rz = 0; rz < WINDOW_SIZE; rz++) {
                int cz = firstChunkZ + rz;
                for (int rx = 0; rx < WINDOW_SIZE; rx++) {
                    int cx = firstChunkX + rx;
                    int index = chunkIndex(rx, cy, rz);
                    int x0 = cx * VoxelWorld.CHUNK_XZ;
                    int z0 = cz * VoxelWorld.CHUNK_XZ;
                    int y0 = cy * VoxelWorld.CHUNK_Y;
                    for (int y = y0; y < y0 + VoxelWorld.CHUNK_Y; y++)
                        for (int z = z0; z < z0 + VoxelWorld.CHUNK_XZ; z++)
                            for (int x = x0; x < x0 + VoxelWorld.CHUNK_XZ; x++)
                                if (world.get(x, y, z) == Block.RAIL &&
                                        counts[index] < MAX_RAILS_PER_CHUNK) counts[index]++;
                }
            }
        }

        short[][] positions = new short[chunkCount][];
        short[][] uv = new short[chunkCount][];
        int[][] stripCounts = new int[chunkCount][];
        int[] cursors = new int[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            if (counts[i] == 0) continue;
            positions[i] = new short[counts[i] * 12];
            uv[i] = new short[counts[i] * 8];
            stripCounts[i] = new int[counts[i]];
            for (int j = 0; j < counts[i]; j++) stripCounts[i][j] = 4;
        }

        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int rz = 0; rz < WINDOW_SIZE; rz++) {
                int cz = firstChunkZ + rz;
                for (int rx = 0; rx < WINDOW_SIZE; rx++) {
                    int cx = firstChunkX + rx;
                    int index = chunkIndex(rx, cy, rz);
                    if (counts[index] == 0) continue;
                    int chunkX = cx * VoxelWorld.CHUNK_XZ;
                    int chunkY = cy * VoxelWorld.CHUNK_Y;
                    int chunkZ = cz * VoxelWorld.CHUNK_XZ;
                    for (int y = chunkY; y < chunkY + VoxelWorld.CHUNK_Y; y++) {
                        for (int z = chunkZ; z < chunkZ + VoxelWorld.CHUNK_XZ; z++) {
                            for (int x = chunkX; x < chunkX + VoxelWorld.CHUNK_XZ; x++) {
                                if (world.get(x, y, z) != Block.RAIL) continue;
                                int rail = cursors[index]++;
                                if (rail >= counts[index]) continue;
                                float left = x - chunkX, right = left + 1.0f;
                                float front = z - chunkZ, back = front + 1.0f;
                                int localY = y - chunkY;
                                int orientation = world.railMetadata(x, y, z);
                                float lowY = localY + 0.075f, highY = localY + 1.075f;
                                float yLeftFront = lowY, yLeftBack = lowY;
                                float yRightFront = lowY, yRightBack = lowY;
                                if (orientation == 2) { yRightFront = highY; yRightBack = highY; }
                                else if (orientation == 3) { yLeftFront = highY; yLeftBack = highY; }
                                else if (orientation == 4) { yLeftFront = highY; yRightFront = highY; }
                                else if (orientation == 5) { yLeftBack = highY; yRightBack = highY; }
                                int p = rail * 12;
                                short[] v = positions[index];
                                v[p] = (short)(left * 256); v[p + 1] = (short)(yLeftFront * 256); v[p + 2] = (short)(front * 256);
                                v[p + 3] = (short)(left * 256); v[p + 4] = (short)(yLeftBack * 256); v[p + 5] = (short)(back * 256);
                                v[p + 6] = (short)(right * 256); v[p + 7] = (short)(yRightFront * 256); v[p + 8] = (short)(front * 256);
                                v[p + 9] = (short)(right * 256); v[p + 10] = (short)(yRightBack * 256); v[p + 11] = (short)(back * 256);
                                int tile = orientation >= 6 ? 112 : 128;
                                int tileU = (tile & 15) * 16, tileV = (tile >> 4) * 16;
                                int u = rail * 8;
                                short[] tex = uv[index];
                                if (orientation == 0 || orientation == 4 || orientation == 5 ||
                                        orientation == 8 || orientation == 9) {
                                    tex[u] = (short)tileU; tex[u + 1] = (short)(tileV + 16);
                                    tex[u + 2] = (short)(tileU + 16); tex[u + 3] = (short)(tileV + 16);
                                    tex[u + 4] = (short)tileU; tex[u + 5] = (short)tileV;
                                    tex[u + 6] = (short)(tileU + 16); tex[u + 7] = (short)tileV;
                                } else {
                                    tex[u] = (short)(tileU + 16); tex[u + 1] = (short)(tileV + 16);
                                    tex[u + 2] = (short)(tileU + 16); tex[u + 3] = (short)tileV;
                                    tex[u + 4] = (short)tileU; tex[u + 5] = (short)(tileV + 16);
                                    tex[u + 6] = (short)tileU; tex[u + 7] = (short)tileV;
                                }
                            }
                        }
                    }
                }
            }
        }

        for (int i = 0; i < chunkCount; i++) {
            if (counts[i] == 0) continue;
            VertexArray pa = new VertexArray(counts[i] * 4, 3, 2);
            pa.set(0, counts[i] * 4, positions[i]);
            VertexArray ta = new VertexArray(counts[i] * 4, 2, 2);
            ta.set(0, counts[i] * 4, uv[i]);
            VertexBuffer vb = new VertexBuffer();
            vb.setPositions(pa, 1.0f / 256.0f, null);
            vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
            Mesh mesh = new Mesh(vb, new TriangleStripArray(0, stripCounts[i]), appearance);
            int rx = i % WINDOW_SIZE;
            int rz = (i / WINDOW_SIZE) % WINDOW_SIZE;
            int cy = i / (WINDOW_SIZE * WINDOW_SIZE);
            mesh.setTranslation((firstChunkX + rx) * VoxelWorld.CHUNK_XZ,
                    cy * VoxelWorld.CHUNK_Y, (firstChunkZ + rz) * VoxelWorld.CHUNK_XZ);
            meshes[i] = mesh;
            group.addChild(mesh);
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
