package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.Light;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;
import javax.microedition.m3g.World;

/** Builds a bounded set of shared-texture chunk meshes for JSR-184. */
final class ChunkRenderer {
    static final int CACHE_SIZE = VoxelWorld.CHUNKS_X * VoxelWorld.CHUNKS_Y * VoxelWorld.CHUNKS_Z;
    private static final int TILE_COUNT = 256;

    final World scene = new World();
    final Camera camera = new Camera();
    final Group cameraPivot = new Group();
    private final Group handPivot = new Group();
    private final Group terrain = new Group();
    private final Chunk[] chunks = new Chunk[CACHE_SIZE];
    private final Texture2D atlas;
    private final Appearance blockAppearance;
    private final Appearance handAppearance;
    private final Mesh handMesh;
    private final Mesh[][] heldItemMeshes = new Mesh[Block.COUNT][];
    private final ParticleSystem particles;
    private int visibleHeldItem = -1;
    private int builtRevision = -1;
    private int builtWidth;
    private int builtHeight;

    ChunkRenderer(int width, int height) throws IOException {
        if (width <= 0) width = 1;
        if (height <= 0) height = 1;
        Image source = Image.createImage("/mecraft/textures.png");
        Image2D image = new Image2D(Image2D.RGBA, source);
        atlas = new Texture2D(image);
        atlas.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        atlas.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        blockAppearance = createBlockAppearance();
        Image handSource = Image.createImage("/mecraft/char.png");
        Texture2D handTexture = new Texture2D(new Image2D(Image2D.RGBA, handSource));
        handTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        handTexture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        handAppearance = createHandAppearance(handTexture);
        handMesh = makeHandMesh();
        // Keep the forearm inside the narrow portrait viewport used by most
        // MIDP phones. The previous offset projected it past the right and
        // bottom edges, making the empty hand appear to be missing.
        handMesh.setScale(0.16f, 0.44f, 0.16f);
        handMesh.setTranslation(0.22f, -0.44f, -0.78f);
        handPivot.addChild(handMesh);
        buildHeldItems();
        particles = new ParticleSystem(atlas);

        camera.setPerspective(68.0f, (float)width / (float)height, 0.1f, 38.0f);
        cameraPivot.addChild(camera);
        cameraPivot.addChild(handPivot);
        scene.addChild(terrain);
        scene.addChild(particles.mesh);
        scene.addChild(cameraPivot);
        scene.setActiveCamera(camera);
        Background background = new Background();
        background.setColor(0xFF82B8E8);
        background.setDepthClearEnable(true);
        scene.setBackground(background);
        Light ambient = new Light();
        ambient.setMode(Light.AMBIENT);
        ambient.setIntensity(1.0f);
        scene.addChild(ambient);
        builtWidth = width;
        builtHeight = height;
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0 || (width == builtWidth && height == builtHeight)) return;
        builtWidth = width;
        builtHeight = height;
        camera.setPerspective(68.0f, (float)width / (float)height, 0.1f, 38.0f);
    }

    void updateCamera(VoxelWorld world, float pitch) {
        cameraPivot.setTranslation(world.playerX, world.playerY + 1.62f, world.playerZ);
        // M3G cameras look down local -Z; 180 degrees makes yaw zero face +Z.
        cameraPivot.setOrientation(world.yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        camera.setOrientation(pitch, 1.0f, 0.0f, 0.0f);
        handPivot.setOrientation(pitch, 1.0f, 0.0f, 0.0f);
    }

    void updateHeldItem(int itemId, boolean visible) {
        if (!visible || itemId <= Block.AIR || itemId >= heldItemMeshes.length) itemId = 0;
        if (itemId == visibleHeldItem) return;
        if (visibleHeldItem > 0 && heldItemMeshes[visibleHeldItem] != null) {
            setMeshesVisible(heldItemMeshes[visibleHeldItem], false);
        }
        visibleHeldItem = itemId;
        if (visibleHeldItem > 0 && heldItemMeshes[visibleHeldItem] != null) {
            setMeshesVisible(heldItemMeshes[visibleHeldItem], true);
        }
    }

    private void buildHeldItems() {
        for (int id = 1; id < Block.COUNT; id++) {
            FaceBuilder[] builders = new FaceBuilder[TILE_COUNT];
            for (int face = 0; face < 6; face++) {
                int tile = face == 0 ? Block.topTile((byte)id) : Block.sideTile((byte)id);
                add(builders, tile, 0, 0, 0, face);
            }
            int count = 0;
            for (int i = 0; i < builders.length; i++) if (builders[i] != null) count++;
            Mesh[] meshes = new Mesh[count];
            int at = 0;
            for (int i = 0; i < builders.length; i++) {
                if (builders[i] == null) continue;
                Mesh mesh = makeMesh(builders[i]);
                mesh.setScale(0.17f, 0.17f, 0.17f);
                mesh.setTranslation(0.28f, -0.36f, -0.62f);
                mesh.setRenderingEnable(false);
                handPivot.addChild(mesh);
                meshes[at++] = mesh;
                builders[i] = null;
            }
            heldItemMeshes[id] = meshes;
        }
    }

    private void setMeshesVisible(Mesh[] meshes, boolean visible) {
        for (int i = 0; i < meshes.length; i++) meshes[i].setRenderingEnable(visible);
    }

    private Appearance createHandAppearance(Texture2D texture) {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        return appearance;
    }

    /** Low-poly first-person forearm textured from the classic char.png skin. */
    private Mesh makeHandMesh() {
        short[] p = new short[] {
            0,0,1, 0,1,1, 1,0,1, 1,1,1,
            1,0,0, 1,1,0, 0,0,0, 0,1,0,
            0,0,0, 0,1,0, 0,0,1, 0,1,1,
            1,0,1, 1,1,1, 1,0,0, 1,1,0,
            0,1,1, 0,1,0, 1,1,1, 1,1,0,
            0,0,0, 0,0,1, 1,0,0, 1,0,1
        };
        short[] uv = new short[48];
        // Classic 64x32 skin layout for the right arm: front, back, both
        // sides, top and underside. Avoid stretching the sleeve on every face.
        int[][] armUv = new int[][] {
            { 44, 20, 48, 32 }, // front
            { 52, 20, 56, 32 }, // back
            { 40, 20, 44, 32 }, // right side
            { 48, 20, 52, 32 }, // left side
            { 44, 16, 48, 20 }, // top
            { 48, 16, 52, 20 }  // underside
        };
        int[] strips = new int[] { 4,4,4,4,4,4 };
        for (int face = 0; face < 6; face++) {
            int offset = face * 8;
            int[] region = armUv[face];
            uv[offset] = (short)region[0]; uv[offset + 1] = (short)region[1];
            uv[offset + 2] = (short)region[0]; uv[offset + 3] = (short)region[3];
            uv[offset + 4] = (short)region[2]; uv[offset + 5] = (short)region[1];
            uv[offset + 6] = (short)region[2]; uv[offset + 7] = (short)region[3];
        }
        VertexArray positions = new VertexArray(24, 3, 2);
        positions.set(0, 24, p);
        VertexArray texCoords = new VertexArray(24, 2, 2);
        texCoords.set(0, 24, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 64.0f, null);
        TriangleStripArray triangles = new TriangleStripArray(0, strips);
        return new Mesh(vertices, triangles, handAppearance);
    }

    void spawnBlockDestroy(int x, int y, int z, int tile) {
        particles.spawnBlockDestroy(x, y, z, tile);
    }

    void updateParticles(VoxelWorld world, float yaw, float pitch) {
        particles.update(world, yaw, pitch);
    }

    void sync(VoxelWorld world) {
        if (world.revision != builtRevision) {
            if (builtRevision < 0) rebuildWorld(world);
            else rebuildChanged(world);
            builtRevision = world.revision;
        }
    }

    private void rebuildChanged(VoxelWorld world) {
        boolean[] dirty = new boolean[chunks.length];
        int x = world.changedX, y = world.changedY, z = world.changedZ;
        markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ);
        if (x % VoxelWorld.CHUNK_XZ == 0) markDirty(dirty, x / VoxelWorld.CHUNK_XZ - 1, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ);
        if (x % VoxelWorld.CHUNK_XZ == VoxelWorld.CHUNK_XZ - 1) markDirty(dirty, x / VoxelWorld.CHUNK_XZ + 1, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ);
        if (y % VoxelWorld.CHUNK_Y == 0) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y - 1, z / VoxelWorld.CHUNK_XZ);
        if (y % VoxelWorld.CHUNK_Y == VoxelWorld.CHUNK_Y - 1) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y + 1, z / VoxelWorld.CHUNK_XZ);
        if (z % VoxelWorld.CHUNK_XZ == 0) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ - 1);
        if (z % VoxelWorld.CHUNK_XZ == VoxelWorld.CHUNK_XZ - 1) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ + 1);
        for (int i = 0; i < dirty.length; i++) if (dirty[i]) replaceChunk(world, i);
    }

    private void markDirty(boolean[] dirty, int cx, int cy, int cz) {
        if (cx < 0 || cx >= VoxelWorld.CHUNKS_X || cy < 0 || cy >= VoxelWorld.CHUNKS_Y ||
            cz < 0 || cz >= VoxelWorld.CHUNKS_Z) return;
        dirty[(cy * VoxelWorld.CHUNKS_Z + cz) * VoxelWorld.CHUNKS_X + cx] = true;
    }

    private void replaceChunk(VoxelWorld world, int i) {
        if (chunks[i] != null) {
            for (int m = 0; m < chunks[i].meshes.length; m++) terrain.removeChild(chunks[i].meshes[m]);
        }
        int cx = i % VoxelWorld.CHUNKS_X;
        int cz = (i / VoxelWorld.CHUNKS_X) % VoxelWorld.CHUNKS_Z;
        int cy = i / (VoxelWorld.CHUNKS_X * VoxelWorld.CHUNKS_Z);
        chunks[i] = buildChunk(world, cx, cy, cz);
        if (chunks[i] != null) {
            for (int m = 0; m < chunks[i].meshes.length; m++) terrain.addChild(chunks[i].meshes[m]);
        }
    }

    private void rebuildWorld(VoxelWorld world) {
        for (int i = 0; i < chunks.length; i++) {
            if (chunks[i] != null) {
                for (int m = 0; m < chunks[i].meshes.length; m++) terrain.removeChild(chunks[i].meshes[m]);
                chunks[i] = null;
            }
        }
        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int cz = 0; cz < VoxelWorld.CHUNKS_Z; cz++) {
                for (int cx = 0; cx < VoxelWorld.CHUNKS_X; cx++) {
                    int i = (cy * VoxelWorld.CHUNKS_Z + cz) * VoxelWorld.CHUNKS_X + cx;
                    chunks[i] = buildChunk(world, cx, cy, cz);
                    if (chunks[i] != null) {
                        for (int m = 0; m < chunks[i].meshes.length; m++) terrain.addChild(chunks[i].meshes[m]);
                    }
                }
            }
        }
    }

    private Chunk buildChunk(VoxelWorld world, int cx, int cy, int cz) {
        FaceBuilder[] builders = new FaceBuilder[TILE_COUNT];
        int x0 = cx * VoxelWorld.CHUNK_XZ;
        int y0 = cy * VoxelWorld.CHUNK_Y;
        int z0 = cz * VoxelWorld.CHUNK_XZ;
        int x1 = x0 + VoxelWorld.CHUNK_XZ;
        int y1 = y0 + VoxelWorld.CHUNK_Y;
        int z1 = z0 + VoxelWorld.CHUNK_XZ;

        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    byte id = world.get(x, y, z);
                    if (id == Block.AIR) continue;
                    int tile = Block.sideTile(id);
                    if (Block.faceVisible(id, world.get(x, y + 1, z))) {
                    add(builders, (id == Block.GRASS || id == Block.LOG || id == Block.CRAFTING_TABLE || id == Block.FURNACE) ? Block.topTile(id) : tile,
                            x - x0, y - y0, z - z0, 0);
                    }
                    if (Block.faceVisible(id, world.get(x - 1, y, z)))
                        add(builders, tile, x - x0, y - y0, z - z0, 1);
                    if (Block.faceVisible(id, world.get(x + 1, y, z)))
                        add(builders, tile, x - x0, y - y0, z - z0, 2);
                    if (Block.faceVisible(id, world.get(x, y, z - 1)))
                        add(builders, tile, x - x0, y - y0, z - z0, 3);
                    if (Block.faceVisible(id, world.get(x, y, z + 1)))
                        add(builders, tile, x - x0, y - y0, z - z0, 4);
                    if (y > 0 && Block.faceVisible(id, world.get(x, y - 1, z)))
                        add(builders, tile, x - x0, y - y0, z - z0, 5);
                }
            }
        }

        int count = 0;
        for (int i = 0; i < builders.length; i++) if (builders[i] != null) count++;
        if (count == 0) return null;
        Mesh[] meshes = new Mesh[count];
        int at = 0;
        for (int i = 0; i < builders.length; i++) {
            if (builders[i] == null) continue;
            meshes[at++] = makeMesh(builders[i]);
            builders[i] = null;
        }
        Chunk chunk = new Chunk();
        chunk.meshes = meshes;
        int wx = x0, wy = y0, wz = z0;
        for (int i = 0; i < meshes.length; i++) meshes[i].setTranslation(wx, wy, wz);
        return chunk;
    }

    private void add(FaceBuilder[] builders, int tile, int x, int y, int z, int face) {
        if (builders[tile] == null) builders[tile] = new FaceBuilder(tile);
        builders[tile].add(x, y, z, face);
    }

    private Mesh makeMesh(FaceBuilder data) {
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        VertexArray texCoords = new VertexArray(vertexCount, 2, 2);
        texCoords.set(0, vertexCount, data.uv);
        VertexArray colors = new VertexArray(vertexCount, 4, 1);
        colors.set(0, vertexCount, data.c);

        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 256.0f, null);
        vertices.setColors(colors);
        int[] strips = new int[data.faceCount];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        TriangleStripArray triangles = new TriangleStripArray(0, strips);

        return new Mesh(vertices, triangles, blockAppearance);
    }

    private Appearance createBlockAppearance() {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, atlas);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        material.setVertexColorTrackingEnable(true);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        return appearance;
    }

    private static final class Chunk { Mesh[] meshes; }

    /** Reuses one short position array per material; no per-block scene nodes. */
    private static final class FaceBuilder {
        final int tile;
        short[] p = new short[192];
        short[] uv = new short[128];
        byte[] c = new byte[256];
        int vertexCount;
        int faceCount;

        FaceBuilder(int tile) { this.tile = tile; }

        void add(int x, int y, int z, int face) {
            ensure(vertexCount + 4);
            int u0 = (tile & 15) * 16 + 1;
            int v0 = (tile >> 4) * 16 + 1;
            int u1 = u0 + 14;
            int v1 = v0 + 14;
            int shade = face == 0 ? 255 : (face == 5 ? 135 : (face <= 2 ? 205 : 180));
            if (face == 0) {
                vertex(x,y+1,z, u0,v1,shade); vertex(x,y+1,z+1, u0,v0,shade);
                vertex(x+1,y+1,z, u1,v1,shade); vertex(x+1,y+1,z+1, u1,v0,shade);
            } else if (face == 1) {
                vertex(x,y,z, u1,v1,shade); vertex(x,y,z+1, u0,v1,shade);
                vertex(x,y+1,z, u1,v0,shade); vertex(x,y+1,z+1, u0,v0,shade);
            } else if (face == 2) {
                vertex(x+1,y,z, u0,v1,shade); vertex(x+1,y+1,z, u0,v0,shade);
                vertex(x+1,y,z+1, u1,v1,shade); vertex(x+1,y+1,z+1, u1,v0,shade);
            } else if (face == 3) {
                vertex(x,y,z, u0,v1,shade); vertex(x,y+1,z, u0,v0,shade);
                vertex(x+1,y,z, u1,v1,shade); vertex(x+1,y+1,z, u1,v0,shade);
            } else if (face == 4) {
                vertex(x,y,z+1, u1,v1,shade); vertex(x+1,y,z+1, u0,v1,shade);
                vertex(x,y+1,z+1, u1,v0,shade); vertex(x+1,y+1,z+1, u0,v0,shade);
            } else {
                vertex(x,y,z, u0,v0,shade); vertex(x+1,y,z, u1,v0,shade);
                vertex(x,y,z+1, u0,v1,shade); vertex(x+1,y,z+1, u1,v1,shade);
            }
            faceCount++;
        }

        private void vertex(int x, int y, int z, int u, int v, int shade) {
            int pi = vertexCount * 3;
            p[pi] = (short)x; p[pi + 1] = (short)y; p[pi + 2] = (short)z;
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            int ci = vertexCount * 4;
            c[ci] = (byte)shade; c[ci + 1] = (byte)shade; c[ci + 2] = (byte)shade; c[ci + 3] = (byte)255;
            vertexCount++;
        }

        private void ensure(int needed) {
            if (needed <= p.length / 3 && needed <= uv.length / 2 && needed <= c.length / 4) return;
            int size = p.length / 3;
            if (uv.length / 2 > size) size = uv.length / 2;
            if (c.length / 4 > size) size = c.length / 4;
            while (size < needed) size <<= 1;
            short[] nextP = new short[size * 3];
            short[] nextUv = new short[size * 2];
            byte[] nextC = new byte[size * 4];
            System.arraycopy(p, 0, nextP, 0, vertexCount * 3);
            System.arraycopy(uv, 0, nextUv, 0, vertexCount * 2);
            System.arraycopy(c, 0, nextC, 0, vertexCount * 4);
            p = nextP; uv = nextUv; c = nextC;
        }

    }
}


