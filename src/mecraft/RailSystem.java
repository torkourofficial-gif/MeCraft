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
                    float left = x, right = x + 1.0f, front = z, back = z + 1.0f;
                    float yy = y + 0.035f;
                    int p = rail * 12;
                    short[] v = positions[index];
                    v[p] = (short)(left * 16); v[p + 1] = (short)(yy * 16); v[p + 2] = (short)(front * 16);
                    v[p + 3] = (short)(left * 16); v[p + 4] = (short)(yy * 16); v[p + 5] = (short)(back * 16);
                    v[p + 6] = (short)(right * 16); v[p + 7] = (short)(yy * 16); v[p + 8] = (short)(front * 16);
                    v[p + 9] = (short)(right * 16); v[p + 10] = (short)(yy * 16); v[p + 11] = (short)(back * 16);
                    boolean northSouth = world.get(x, y, z - 1) == Block.RAIL ||
                            world.get(x, y, z + 1) == Block.RAIL;
                    int u = rail * 8;
                    short[] t = uv[index];
                    if (northSouth) {
                        t[u] = 0; t[u + 1] = 16; t[u + 2] = 16; t[u + 3] = 16;
                        t[u + 4] = 0; t[u + 5] = 0; t[u + 6] = 16; t[u + 7] = 0;
                    } else {
                        t[u] = 16; t[u + 1] = 16; t[u + 2] = 16; t[u + 3] = 0;
                        t[u + 4] = 0; t[u + 5] = 16; t[u + 6] = 0; t[u + 7] = 0;
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
            vb.setPositions(pa, 1.0f / 16.0f, null);
            vb.setTexCoords(0, ta, 1.0f / 16.0f, null);
            Mesh mesh = new Mesh(vb, new TriangleStripArray(0, stripCounts[i]), appearance);
            meshes[i] = mesh;
            group.addChild(mesh);
        }
        builtRevision = world.revision;
    }

    private int chunkIndex(int cx, int cy, int cz) {
        return (cy * VoxelWorld.CHUNKS_Z + cz) * VoxelWorld.CHUNKS_X + cx;
    }
}
