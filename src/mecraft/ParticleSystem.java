package mecraft;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/**
 * Tiny fixed-pool effects for CLDC/M3G devices.
 * No collision tests, object creation, per-particle physics objects or alpha blending.
 */
final class ParticleSystem {
    private static final int CAPACITY = 8;
    private static final int VERTICES = CAPACITY * 4;
    private static final float SCALE = 256.0f;
    private static final int LIFE = 8;
    private static final float[] EXPLOSION_X = { -1.0f, -0.7f, 0.0f, 0.7f, 1.0f, 0.7f, 0.0f, -0.7f };
    private static final float[] EXPLOSION_Z = { 0.0f, -0.7f, -1.0f, -0.7f, 0.0f, 0.7f, 1.0f, 0.7f };

    final Mesh mesh;
    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] z = new float[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] vz = new float[CAPACITY];
    private final float[] size = new float[CAPACITY];
    private final int[] age = new int[CAPACITY];
    private final boolean[] active = new boolean[CAPACITY];
    private final short[] positions = new short[VERTICES * 3];
    private final short[] texCoords = new short[VERTICES * 2];
    private final VertexArray positionArray;
    private final VertexArray texCoordArray;
    private int cursor;
    private int activeCount;

    ParticleSystem(Texture2D atlas) {
        positionArray = new VertexArray(VERTICES, 3, 2);
        texCoordArray = new VertexArray(VERTICES, 2, 2);
        // Give M3G stable bounds; live particles move around the player origin.
        short bound = (short)(16.0f * SCALE);
        for (int i = 0; i < VERTICES; i++) {
            int p = i * 3;
            int corner = (i >> 2) & 7;
            positions[p] = (short)((corner & 1) == 0 ? -bound : bound);
            positions[p + 1] = (short)((corner & 2) == 0 ? -bound : bound);
            positions[p + 2] = (short)((corner & 4) == 0 ? -bound : bound);
        }
        positionArray.set(0, VERTICES, positions);
        texCoordArray.set(0, VERTICES, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positionArray, 1.0f / SCALE, null);
        buffer.setTexCoords(0, texCoordArray, 1.0f / 1024.0f, null);
        int[] strips = new int[CAPACITY];
        for (int i = 0; i < CAPACITY; i++) strips[i] = 4;
        Appearance appearance = new Appearance();
        appearance.setTexture(0, atlas);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        appearance.setPolygonMode(polygon);
        mesh = new Mesh(buffer, new TriangleStripArray(0, strips), appearance);
        mesh.setRenderingEnable(false);
    }

    void spawnBlockDestroy(int bx, int by, int bz, int blockId) {
        spawn(bx + 0.30f, by + 0.38f, bz + 0.50f,
                -0.035f, 0.055f, 0.012f, 0.075f, Block.sideTile(blockId));
        spawn(bx + 0.68f, by + 0.55f, bz + 0.32f,
                0.030f, 0.070f, -0.025f, 0.075f, Block.sideTile(blockId));
        spawn(bx + 0.48f, by + 0.72f, bz + 0.68f,
                0.008f, 0.085f, 0.035f, 0.065f, Block.sideTile(blockId));
        uploadTextureCoordinates();
    }

    void spawnMobDeath(float px, float py, float pz) {
        int tile = Block.sideTile(Block.DIRT);
        spawn(px - 0.12f, py + 0.25f, pz, -0.025f, 0.045f, 0.0f, 0.06f, tile);
        spawn(px + 0.12f, py + 0.35f, pz, 0.025f, 0.055f, 0.015f, 0.06f, tile);
        uploadTextureCoordinates();
    }

    /** Eight tiny fire-colored squares make the TNT blast readable without a heavy emitter. */
    void spawnExplosion(float px, float py, float pz) {
        int tile = Block.sideTile(Block.LAVA);
        for (int i = 0; i < CAPACITY; i++) {
            float lift = (i & 1) == 0 ? 0.055f : 0.095f;
            spawn(px, py, pz, EXPLOSION_X[i] * 0.105f, lift, EXPLOSION_Z[i] * 0.105f,
                    0.16f, tile);
        }
        uploadTextureCoordinates();
    }

    private void spawn(float px, float py, float pz, float sx, float sy, float sz,
                       float particleSize, int tile) {
        int i = cursor++;
        if (cursor == CAPACITY) cursor = 0;
        if (!active[i]) activeCount++;
        active[i] = true;
        x[i] = px; y[i] = py; z[i] = pz;
        vx[i] = sx; vy[i] = sy; vz[i] = sz;
        size[i] = particleSize;
        age[i] = 0;
        int tx = (tile & 15) * 16 + 1;
        int ty = (tile >> 4) * 16 + 1;
        int at = i * 8;
        texCoords[at] = (short)tx; texCoords[at + 1] = (short)(ty + 14);
        texCoords[at + 2] = (short)(tx + 14); texCoords[at + 3] = (short)(ty + 16);
        texCoords[at + 4] = (short)tx; texCoords[at + 5] = (short)ty;
        texCoords[at + 6] = (short)(tx + 16); texCoords[at + 7] = (short)ty;
    }

    private void uploadTextureCoordinates() {
        texCoordArray.set(0, VERTICES, texCoords);
    }

    void update(VoxelWorld world, float yawDegrees, float pitchDegrees) {
        if (activeCount == 0) {
            mesh.setRenderingEnable(false);
            return;
        }
        float yaw = yawDegrees * 0.0174532925f;
        float pitch = pitchDegrees * 0.0174532925f;
        float rightX = (float)Math.cos(yaw);
        float rightZ = -(float)Math.sin(yaw);
        float upX = -(float)(Math.sin(yaw) * Math.sin(pitch));
        float upY = (float)Math.cos(pitch);
        float upZ = -(float)(Math.cos(yaw) * Math.sin(pitch));
        float originX = world.playerX;
        float originY = world.playerY;
        float originZ = world.playerZ;
        mesh.setTranslation(originX, originY, originZ);

        for (int i = 0; i < CAPACITY; i++) {
            int at = i * 12;
            if (!active[i]) {
                for (int v = 0; v < 4; v++) {
                    positions[at++] = 0;
                    positions[at++] = 0;
                    positions[at++] = 0;
                }
                continue;
            }
            if (++age[i] >= LIFE) {
                active[i] = false;
                activeCount--;
                for (int v = 0; v < 4; v++) {
                    positions[at++] = 0;
                    positions[at++] = 0;
                    positions[at++] = 0;
                }
                continue;
            }
            x[i] += vx[i];
            y[i] += vy[i];
            z[i] += vz[i];
            vy[i] -= 0.009f;
            float half = size[i] * (1.0f - (float)age[i] / (float)LIFE);
            float cx = x[i] - originX;
            float cy = y[i] - originY;
            float cz = z[i] - originZ;
            put(at, cx - rightX * half - upX * half,
                    cy - upY * half, cz - rightZ * half - upZ * half); at += 3;
            put(at, cx + rightX * half - upX * half,
                    cy - upY * half, cz + rightZ * half - upZ * half); at += 3;
            put(at, cx - rightX * half + upX * half,
                    cy + upY * half, cz - rightZ * half + upZ * half); at += 3;
            put(at, cx + rightX * half + upX * half,
                    cy + upY * half, cz + rightZ * half + upZ * half);
        }
        positionArray.set(0, VERTICES, positions);
        mesh.setRenderingEnable(activeCount > 0);
    }

    private void put(int at, float px, float py, float pz) {
        positions[at] = (short)(px * SCALE);
        positions[at + 1] = (short)(py * SCALE);
        positions[at + 2] = (short)(pz * SCALE);
    }
}
