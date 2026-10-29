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
    private final Mesh[] meshes = new Mesh[VoxelWorld.CHUNKS_X * VoxelWorld.CHUNKS_Y * VoxelWorld.CHUNKS_Z];
    private int builtRevision = Integer.MIN_VALUE;

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
        if (builtRevision == world.revision && !world.fullRebuild) return;
        // Ordinary block edits should not trigger a full rail scan. Only rebuild
        // when a rail or one of its six adjacent/support cells changed.
        if (builtRevision != Integer.MIN_VALUE && !world.fullRebuild) {
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
        for (int y = 1; y < VoxelWorld.HEIGHT; y++) {
            for (int z = 0; z < VoxelWorld.DEPTH; z++) {
                for (int x = 0; x < VoxelWorld.WIDTH; x++) {
                    if (world.get(x, y, z) != Block.RAIL) continue;
                    int index = chunkIndex(x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y,
                            z / VoxelWorld.CHUNK_XZ);
                    if (counts[index] < MAX_RAILS_PER_CHUNK) counts[index]++;
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
        for (int y = 1; y < VoxelWorld.HEIGHT; y++) {
            for (int z = 0; z < VoxelWorld.DEPTH; z++) {
                for (int x = 0; x < VoxelWorld.WIDTH; x++) {
                    if (world.get(x, y, z) != Block.RAIL) continue;
                    int index = chunkIndex(x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y,
                            z / VoxelWorld.CHUNK_XZ);
                    int rail = cursors[index]++;
                    if (rail >= counts[index]) continue;
                    // M3G short vertex positions are signed 16-bit values. Store
                    // coordinates relative to this chunk before scaling by 256;
                    // absolute world X/Z exceed the short range in 240x240 worlds.
                    int chunkX = (x / VoxelWorld.CHUNK_XZ) * VoxelWorld.CHUNK_XZ;
                    int chunkY = (y / VoxelWorld.CHUNK_Y) * VoxelWorld.CHUNK_Y;
                    int chunkZ = (z / VoxelWorld.CHUNK_XZ) * VoxelWorld.CHUNK_XZ;
                    float left = x - chunkX, right = x - chunkX + 1.0f;
                    float front = z - chunkZ, back = z - chunkZ + 1.0f;
                    int localY = y - chunkY;
                    int orientation = world.railMetadata(x, y, z);
                    float lowY = localY + 0.075f;
                    float highY = localY + 1.075f;
                    float yLeftFront = lowY, yLeftBack = lowY;
                    float yRightFront = lowY, yRightBack = lowY;
                    // Alpha metadata 2-5 tilts the track up by one block toward
                    // its elevated endpoint, rather than rendering a flat tile.
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
                    // Alpha BlockMinecartTrack uses tile 128 for straight/slope
                    // rails and tile 112 for curves (metadata 6-9).
                    int tile = orientation >= 6 ? 112 : 128;
                    int tileU = (tile & 15) * 16;
                    int tileV = (tile >> 4) * 16;
                    int u = rail * 8;
                    short[] t = uv[index];
                    if (orientation == 0 || orientation == 4 || orientation == 5 ||
                            orientation == 8 || orientation == 9) {
                        t[u] = (short)tileU; t[u + 1] = (short)(tileV + 16);
                        t[u + 2] = (short)(tileU + 16); t[u + 3] = (short)(tileV + 16);
                        t[u + 4] = (short)tileU; t[u + 5] = (short)tileV;
                        t[u + 6] = (short)(tileU + 16); t[u + 7] = (short)tileV;
                    } else {
                        t[u] = (short)(tileU + 16); t[u + 1] = (short)(tileV + 16);
                        t[u + 2] = (short)(tileU + 16); t[u + 3] = (short)tileV;
                        t[u + 4] = (short)tileU; t[u + 5] = (short)(tileV + 16);
                        t[u + 6] = (short)tileU; t[u + 7] = (short)tileV;
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
            int cx = i % VoxelWorld.CHUNKS_X;
            int cz = (i / VoxelWorld.CHUNKS_X) % VoxelWorld.CHUNKS_Z;
            int cy = i / (VoxelWorld.CHUNKS_X * VoxelWorld.CHUNKS_Z);
            mesh.setTranslation(cx * VoxelWorld.CHUNK_XZ,
                    cy * VoxelWorld.CHUNK_Y, cz * VoxelWorld.CHUNK_XZ);
            meshes[i] = mesh;
            group.addChild(mesh);
        }
        builtRevision = world.revision;
    }

    private int chunkIndex(int cx, int cy, int cz) {
        return (cy * VoxelWorld.CHUNKS_Z + cz) * VoxelWorld.CHUNKS_X + cx;
    }
}
