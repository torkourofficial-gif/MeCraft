package mecraft;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/**
 * Small, fixed particle pool.
 *
 * The system deliberately uses the original simple approach: a circular
 * particle slot, basic velocity/gravity, one ground check and one shared mesh.
 * No per-particle objects or per-frame allocations are made.
 */
final class ParticleSystem {
    private static final int CAPACITY = 24;
    private static final int VERTICES = CAPACITY * 4;
    private static final float SCALE = 256.0f;

    final Mesh mesh;

    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] z = new float[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] vz = new float[CAPACITY];
    private final float[] size = new float[CAPACITY];
    private final int[] age = new int[CAPACITY];
    private final int[] lifetime = new int[CAPACITY];
    private final int[] tile = new int[CAPACITY];
    private final int[] tileX = new int[CAPACITY];
    private final int[] tileY = new int[CAPACITY];
    private final boolean[] dust = new boolean[CAPACITY];

    private final short[] positions = new short[VERTICES * 3];
    private final short[] texCoords = new short[VERTICES * 2];
    private final byte[] colors = new byte[VERTICES * 4];
    private final VertexArray positionArray;
    private final VertexArray texCoordArray;
    private final VertexArray colorArray;

    private int cursor;
    private int random = 0x13579BDF;
    private boolean uvDirty;
    private boolean visible;

    ParticleSystem(Texture2D atlas) {
        positionArray = new VertexArray(VERTICES, 3, 2);

        // Keep the initial bounds centered around the player-relative origin.
        short bound = (short)(32 * SCALE);
        for (int i = 0; i < CAPACITY; i++) {
            int p = i * 12;
            positions[p] = (short)-bound;
            positions[p + 1] = (short)-bound;
            positions[p + 2] = (short)-bound;
            positions[p + 3] = bound;
            positions[p + 4] = (short)-bound;
            positions[p + 5] = (short)-bound;
            positions[p + 6] = (short)-bound;
            positions[p + 7] = bound;
            positions[p + 8] = bound;
            positions[p + 9] = bound;
            positions[p + 10] = bound;
            positions[p + 11] = bound;
        }
        positionArray.set(0, VERTICES, positions);

        texCoordArray = new VertexArray(VERTICES, 2, 2);
        texCoordArray.set(0, VERTICES, texCoords);
        colorArray = new VertexArray(VERTICES, 4, 1);
        colorArray.set(0, VERTICES, colors);

        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positionArray, 1.0f / SCALE, null);
        buffer.setTexCoords(0, texCoordArray, 1.0f / SCALE, null);
        buffer.setColors(colorArray);

        int[] strips = new int[CAPACITY];
        for (int i = 0; i < CAPACITY; i++) strips[i] = 4;

        Appearance appearance = new Appearance();
        appearance.setTexture(0, atlas);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        material.setVertexColorTrackingEnable(true);
        appearance.setMaterial(material);

        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        appearance.setPolygonMode(polygon);

        CompositingMode compositing = new CompositingMode();
        compositing.setBlending(CompositingMode.ALPHA);
        compositing.setDepthWriteEnable(false);
        appearance.setCompositingMode(compositing);

        mesh = new Mesh(buffer, new TriangleStripArray(0, strips), appearance);
    }

    /** Four small block fragments, using the broken block's atlas tile. */
    void spawnBlockDestroy(int bx, int by, int bz, int blockTile) {
        for (int n = 0; n < 4; n++) {
            int i = nextSlot();
            float ox = (n & 1) == 0 ? 0.25f : 0.75f;
            float oz = n < 2 ? 0.25f : 0.75f;
            x[i] = bx + ox;
            y[i] = by + 0.5f;
            z[i] = bz + oz;

            vx[i] = (ox - 0.5f) * 0.12f + randomRange(0.035f);
            vy[i] = 0.045f + nextRandom() * 0.055f;
            vz[i] = (oz - 0.5f) * 0.12f + randomRange(0.035f);
            size[i] = 0.055f + nextRandom() * 0.035f;
            age[i] = 0;
            lifetime[i] = 20;
            tile[i] = blockTile;
            tileX[i] = (int)(nextRandom() * 4.0f);
            tileY[i] = (int)(nextRandom() * 4.0f);
            dust[i] = false;
            writeTileUV(i);
        }
        uvDirty = true;
    }

    /** Four short-lived brown puffs when a mob dies. */
    void spawnMobDeath(float px, float py, float pz) {
        for (int n = 0; n < 4; n++) {
            int i = nextSlot();
            x[i] = px + randomRange(0.22f);
            y[i] = py + 0.25f + nextRandom() * 0.55f;
            z[i] = pz + randomRange(0.22f);
            vx[i] = randomRange(0.018f);
            vy[i] = 0.012f + nextRandom() * 0.018f;
            vz[i] = randomRange(0.018f);
            size[i] = 0.045f + nextRandom() * 0.025f;
            age[i] = 0;
            lifetime[i] = 14 + (int)(nextRandom() * 7.0f);
            tile[i] = Block.sideTile(Block.DIRT);
            tileX[i] = (int)(nextRandom() * 12.0f);
            tileY[i] = (int)(nextRandom() * 12.0f);
            dust[i] = true;
            writeTileUV(i);
        }
        uvDirty = true;
    }

    private int nextSlot() {
        int result = cursor++;
        if (cursor >= CAPACITY) cursor = 0;
        return result;
    }

    private void writeTileUV(int particle) {
        int u = (tile[particle] & 15) * 16 + tileX[particle];
        int v = (tile[particle] >> 4) * 16 + tileY[particle];
        int at = particle * 8;
        texCoords[at] = (short)u;
        texCoords[at + 1] = (short)(v + 4);
        texCoords[at + 2] = (short)(u + 4);
        texCoords[at + 3] = (short)(v + 4);
        texCoords[at + 4] = (short)u;
        texCoords[at + 5] = (short)v;
        texCoords[at + 6] = (short)(u + 4);
        texCoords[at + 7] = (short)v;
    }

    void update(VoxelWorld world, float yawDegrees, float pitchDegrees) {
        boolean anyActive = false;
        for (int i = 0; i < CAPACITY; i++) {
            if (age[i] >= lifetime[i]) {
                setColor(i, 0, 0, 0, 0);
                continue;
            }

            anyActive = true;
            age[i]++;
            if (dust[i]) vy[i] += 0.001f;
            else vy[i] -= 0.04f;

            x[i] += vx[i];
            y[i] += vy[i];
            z[i] += vz[i];

            if (!dust[i]) {
                int below = floorInt(y[i] - size[i]);
                if (world.isSolidAt(floorInt(x[i]), below, floorInt(z[i]))) {
                    y[i] = below + 1.0f + size[i];
                    vy[i] = 0.0f;
                    vx[i] *= 0.7f;
                    vz[i] *= 0.7f;
                }
            }

            vx[i] *= dust[i] ? 0.92f : 0.98f;
            vy[i] *= dust[i] ? 0.96f : 0.98f;
            vz[i] *= dust[i] ? 0.92f : 0.98f;

            if (dust[i]) {
                int alpha = 230 - age[i] * 230 / lifetime[i];
                setColor(i, 165, 132, 91, alpha);
            } else {
                int alpha = 255 - age[i] * 255 / lifetime[i];
                setColor(i, 153, 153, 153, alpha);
            }

            if (age[i] >= lifetime[i]) setColor(i, 0, 0, 0, 0);
        }

        if (!anyActive) {
            if (visible) {
                colorArray.set(0, VERTICES, colors);
                visible = false;
            }
            return;
        }

        float originX = world.playerX;
        float originY = world.playerY;
        float originZ = world.playerZ;
        mesh.setTranslation(originX, originY, originZ);

        double yaw = yawDegrees * Math.PI / 180.0;
        double pitch = pitchDegrees * Math.PI / 180.0;
        float rightX = (float)Math.cos(yaw);
        float rightZ = -(float)Math.sin(yaw);
        float upX = -(float)(Math.sin(yaw) * Math.sin(pitch));
        float upY = (float)Math.cos(pitch);
        float upZ = -(float)(Math.cos(yaw) * Math.sin(pitch));

        for (int i = 0; i < CAPACITY; i++) {
            if (age[i] < lifetime[i]) {
                writeQuad(i, rightX, rightZ, upX, upY, upZ,
                        originX, originY, originZ);
            }
        }

        positionArray.set(0, VERTICES, positions);
        colorArray.set(0, VERTICES, colors);
        if (uvDirty) {
            texCoordArray.set(0, VERTICES, texCoords);
            uvDirty = false;
        }
        visible = true;
    }

    private void writeQuad(int particle, float rx, float rz, float ux, float uy, float uz,
            float originX, float originY, float originZ) {
        float half = size[particle];
        float cx = x[particle] - originX;
        float cy = y[particle] - originY;
        float cz = z[particle] - originZ;

        int p = particle * 12;
        putPosition(p, cx - rx * half - ux * half, cy - uy * half,
                cz - rz * half - uz * half);
        putPosition(p + 3, cx + rx * half - ux * half, cy - uy * half,
                cz + rz * half - uz * half);
        putPosition(p + 6, cx - rx * half + ux * half, cy + uy * half,
                cz - rz * half + uz * half);
        putPosition(p + 9, cx + rx * half + ux * half, cy + uy * half,
                cz + rz * half + uz * half);
    }

    private void putPosition(int at, float px, float py, float pz) {
        positions[at] = (short)(px * SCALE);
        positions[at + 1] = (short)(py * SCALE);
        positions[at + 2] = (short)(pz * SCALE);
    }

    private void setColor(int particle, int red, int green, int blue, int alpha) {
        int at = particle * 16;
        for (int v = 0; v < 4; v++) {
            colors[at++] = (byte)red;
            colors[at++] = (byte)green;
            colors[at++] = (byte)blue;
            colors[at++] = (byte)alpha;
        }
    }

    private int floorInt(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    private float randomRange(float range) {
        return (nextRandom() - 0.5f) * range * 2.0f;
    }

    private float nextRandom() {
        random = random * 1103515245 + 12345;
        return ((random >>> 8) & 0xFFFF) / 65535.0f;
    }
}
