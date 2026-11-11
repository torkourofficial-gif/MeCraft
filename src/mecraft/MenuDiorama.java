package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.Light;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.World;

/** Small static two-chunk M3G title scene with a low-cost orbiting camera. */
final class MenuDiorama {
    private static final int WIDTH = 32, DEPTH = 16, HEIGHT = 16;
    private final World scene = new World();
    private final Group orbit = new Group();
    private final Camera camera = new Camera();
    private int builtWidth, builtHeight;

    MenuDiorama(int width, int height) throws IOException {
        Image terrain = GameAssets.terrain();
        Texture2D atlas = new Texture2D(new Image2D(Image2D.RGBA, terrain));
        atlas.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        atlas.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);

        Appearance appearance = new Appearance();
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

        byte[][][] blocks = makeLandscape();
        int faceCount = countFaces(blocks);
        short[] positions = new short[faceCount * 12];
        short[] texCoords = new short[faceCount * 8];
        int[] strips = new int[faceCount];
        for (int i = 0; i < faceCount; i++) strips[i] = 4;
        buildGeometry(blocks, positions, texCoords);

        VertexArray pa = new VertexArray(faceCount * 4, 3, 2);
        pa.set(0, faceCount * 4, positions);
        VertexArray ta = new VertexArray(faceCount * 4, 2, 2);
        ta.set(0, faceCount * 4, texCoords);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 256.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
        scene.addChild(new Mesh(vb, new TriangleStripArray(0, strips), appearance));

        Background sky = new Background();
        sky.setColor(0xFF83BCEB);
        sky.setDepthClearEnable(true);
        scene.setBackground(sky);
        Light ambient = new Light();
        ambient.setMode(Light.AMBIENT);
        ambient.setIntensity(1.0f);
        scene.addChild(ambient);

        camera.setPerspective(58.0f, (float)width / (float)height, 0.1f, 80.0f);
        camera.setOrientation(-17.0f, 1.0f, 0.0f, 0.0f);
        camera.setTranslation(0.0f, 12.0f, 35.0f);
        orbit.setTranslation(16.0f, 4.0f, 8.0f);
        orbit.addChild(camera);
        scene.addChild(orbit);
        scene.setActiveCamera(camera);
        builtWidth = width;
        builtHeight = height;
    }

    void render(Graphics3D graphics, int width, int height, int tick) {
        if (width <= 0 || height <= 0) return;
        if (width != builtWidth || height != builtHeight) {
            camera.setPerspective(58.0f, (float)width / (float)height, 0.1f, 80.0f);
            builtWidth = width;
            builtHeight = height;
        }
        orbit.setOrientation((tick * 0.75f) % 360.0f, 0.0f, 1.0f, 0.0f);
        graphics.render(scene);
    }

    private byte[][][] makeLandscape() {
        byte[][][] blocks = new byte[WIDTH][HEIGHT][DEPTH];
        int[][] heights = new int[WIDTH][DEPTH];
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                int hillA = 4 + Math.max(0, 4 - (Math.abs(x - 8) + Math.abs(z - 9)) / 3);
                int hillB = 4 + Math.max(0, 3 - (Math.abs(x - 25) + Math.abs(z - 5)) / 3);
                int top = hillA > hillB ? hillA : hillB;
                if (x >= 13 && x <= 20 && z >= 4 && z <= 11) top = 4;
                heights[x][z] = top;
                for (int y = 0; y <= top; y++) {
                    blocks[x][y][z] = y == top ? Block.GRASS :
                            (y == 0 ? Block.STONE : (y < top - 2 ? Block.STONE : Block.DIRT));
                }
            }
        }
        // Wooden cabin at the saddle between the two hills.
        for (int x = 14; x <= 19; x++) for (int z = 5; z <= 10; z++)
            put(blocks, x, 5, z, Block.PLANKS);
        for (int y = 6; y <= 8; y++) {
            for (int x = 14; x <= 19; x++) {
                put(blocks, x, y, 5, Block.PLANKS);
                put(blocks, x, y, 10, Block.PLANKS);
            }
            for (int z = 6; z <= 9; z++) {
                put(blocks, 14, y, z, Block.PLANKS);
                put(blocks, 19, y, z, Block.PLANKS);
            }
            put(blocks, 16, y, 5, Block.AIR);
            put(blocks, 17, y, 5, Block.AIR);
        }
        for (int x = 14; x <= 19; x++) for (int z = 5; z <= 10; z++)
            put(blocks, x, 9, z, Block.PLANKS);
        put(blocks, 15, 7, 5, Block.GLASS);
        put(blocks, 18, 7, 5, Block.GLASS);
        makeTree(blocks, heights, 7, 10);
        makeTree(blocks, heights, 26, 4);
        return blocks;
    }

    private void makeTree(byte[][][] blocks, int[][] heights, int x, int z) {
        int base = heights[x][z];
        for (int y = base + 1; y <= base + 4; y++) put(blocks, x, y, z, Block.LOG);
        for (int y = base + 2; y <= base + 5; y++) {
            int radius = y == base + 5 ? 1 : 2;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1) continue;
                    if (dx == 0 && dz == 0 && y <= base + 4) continue;
                    put(blocks, x + dx, y, z + dz, Block.LEAVES);
                }
            }
        }
    }

    private void put(byte[][][] blocks, int x, int y, int z, byte id) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && z >= 0 && z < DEPTH)
            blocks[x][y][z] = id;
    }

    private int countFaces(byte[][][] blocks) {
        int count = 0;
        for (int x = 0; x < WIDTH; x++) for (int y = 0; y < HEIGHT; y++)
            for (int z = 0; z < DEPTH; z++) {
                byte id = blocks[x][y][z];
                if (id == Block.AIR) continue;
                if (empty(blocks, x, y + 1, z)) count++;
                if (empty(blocks, x - 1, y, z)) count++;
                if (empty(blocks, x + 1, y, z)) count++;
                if (empty(blocks, x, y, z - 1)) count++;
                if (empty(blocks, x, y, z + 1)) count++;
            }
        return count;
    }

    private boolean empty(byte[][][] blocks, int x, int y, int z) {
        return x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT ||
                z < 0 || z >= DEPTH || blocks[x][y][z] == Block.AIR;
    }

    private void buildGeometry(byte[][][] blocks, short[] positions, short[] texCoords) {
        int faceIndex = 0;
        for (int x = 0; x < WIDTH; x++) for (int y = 0; y < HEIGHT; y++)
            for (int z = 0; z < DEPTH; z++) {
                byte id = blocks[x][y][z];
                if (id == Block.AIR) continue;
                if (empty(blocks, x, y + 1, z)) faceIndex = writeFace(faceIndex, positions, texCoords, id, 0, x, y, z);
                if (empty(blocks, x - 1, y, z)) faceIndex = writeFace(faceIndex, positions, texCoords, id, 1, x, y, z);
                if (empty(blocks, x + 1, y, z)) faceIndex = writeFace(faceIndex, positions, texCoords, id, 2, x, y, z);
                if (empty(blocks, x, y, z - 1)) faceIndex = writeFace(faceIndex, positions, texCoords, id, 3, x, y, z);
                if (empty(blocks, x, y, z + 1)) faceIndex = writeFace(faceIndex, positions, texCoords, id, 4, x, y, z);
            }
    }

    private int writeFace(int faceIndex, short[] p, short[] uv, byte id,
                          int face, int x, int y, int z) {
        int vertex = faceIndex * 12;
        if (face == 0) {
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)((z + 1) * 256);
        } else if (face == 1) {
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)((z + 1) * 256);
        } else if (face == 2) {
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)(z * 256);
        } else if (face == 3) {
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)(z * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)(z * 256);
        } else {
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)(x * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)(y * 256); p[vertex++] = (short)((z + 1) * 256);
            p[vertex++] = (short)((x + 1) * 256); p[vertex++] = (short)((y + 1) * 256); p[vertex++] = (short)((z + 1) * 256);
        }
        int tile = Block.faceTile(id, face);
        int tileU = (tile & 15) * 16, tileV = (tile >> 4) * 16;
        int tex = faceIndex * 8;
        uv[tex] = (short)tileU; uv[tex + 1] = (short)(tileV + 16);
        uv[tex + 2] = (short)tileU; uv[tex + 3] = (short)tileV;
        uv[tex + 4] = (short)(tileU + 16); uv[tex + 5] = (short)(tileV + 16);
        uv[tex + 6] = (short)(tileU + 16); uv[tex + 7] = (short)tileV;
        return faceIndex + 1;
    }
}
