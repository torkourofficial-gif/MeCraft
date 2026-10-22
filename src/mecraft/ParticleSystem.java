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

/** Fixed-size, shared-atlas block debris pool; uses no per-tick allocations. */
final class ParticleSystem {
    private static final int CAPACITY = 32;
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
    private final short[] positions = new short[VERTICES * 3];
    private final short[] texCoords = new short[VERTICES * 2];
    private final byte[] colors = new byte[VERTICES * 4];
    private final VertexArray positionArray;
    private final VertexArray texCoordArray;
    private final VertexArray colorArray;
    private int cursor;
    private int random = 0x13579BDF;

    ParticleSystem(Texture2D atlas) {
        positionArray = new VertexArray(VERTICES, 3, 2);
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
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        TriangleStripArray triangles = new TriangleStripArray(0, strips);

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
        mesh = new Mesh(buffer, triangles, appearance);
        for (int i = 0; i < CAPACITY; i++) setParticleColor(i, 0);
    }

    /** Indev emits a 4x4x4 debris grid; a 32 slot ring bounds heap and draw cost. */
    void spawnBlockDestroy(int bx, int by, int bz, int blockTile) {
        for (int gy = 0; gy < 4; gy++) {
            for (int gz = 0; gz < 4; gz++) {
                for (int gx = 0; gx < 4; gx++) {
                    int i = cursor++;
                    if (cursor == CAPACITY) cursor = 0;
                    float rx = (gx + 0.5f) * 0.25f;
                    float ry = (gy + 0.5f) * 0.25f;
                    float rz = (gz + 0.5f) * 0.25f;
                    x[i] = bx + rx;
                    y[i] = by + ry;
                    z[i] = bz + rz;
                    vx[i] = (rx - 0.5f) * 0.12f + (nextRandom() - 0.5f) * 0.035f;
                    vy[i] = (ry - 0.5f) * 0.12f + 0.025f + nextRandom() * 0.045f;
                    vz[i] = (rz - 0.5f) * 0.12f + (nextRandom() - 0.5f) * 0.035f;
                    size[i] = 0.035f + nextRandom() * 0.035f;
                    age[i] = 0;
                    lifetime[i] = 18 + (int)(nextRandom() * 20.0f);
                    tile[i] = blockTile;
                    setParticleColor(i, 255);
                }
            }
        }
    }

    void update(float yawDegrees, float pitchDegrees) {
        double yaw = yawDegrees * Math.PI / 180.0;
        double pitch = pitchDegrees * Math.PI / 180.0;
        float rightX = (float)Math.cos(yaw);
        float rightZ = -(float)Math.sin(yaw);
        float upX = -(float)(Math.sin(yaw) * Math.sin(pitch));
        float upY = (float)Math.cos(pitch);
        float upZ = -(float)(Math.cos(yaw) * Math.sin(pitch));
        for (int i = 0; i < CAPACITY; i++) {
            if (age[i] < lifetime[i]) {
                age[i]++;
                x[i] += vx[i];
                y[i] += vy[i];
                z[i] += vz[i];
                vy[i] -= 0.008f;
                vx[i] *= 0.98f;
                vy[i] *= 0.98f;
                vz[i] *= 0.98f;
                int alpha = (lifetime[i] - age[i]) * 255 / lifetime[i];
                setParticleColor(i, alpha);
            } else {
                setParticleColor(i, 0);
            }
            writeQuad(i, rightX, rightZ, upX, upY, upZ);
        }
        positionArray.set(0, VERTICES, positions);
        texCoordArray.set(0, VERTICES, texCoords);
        colorArray.set(0, VERTICES, colors);
    }

    private void writeQuad(int particle, float rx, float rz, float ux, float uy, float uz) {
        float half = size[particle];
        float x0 = x[particle] - rx * half - ux * half;
        float y0 = y[particle] - uy * half;
        float z0 = z[particle] - rz * half - uz * half;
        float x1 = x[particle] + rx * half - ux * half;
        float y1 = y[particle] - uy * half;
        float z1 = z[particle] + rz * half - uz * half;
        float x2 = x[particle] - rx * half + ux * half;
        float y2 = y[particle] + uy * half;
        float z2 = z[particle] - rz * half + uz * half;
        float x3 = x[particle] + rx * half + ux * half;
        float y3 = y[particle] + uy * half;
        float z3 = z[particle] + rz * half + uz * half;
        int v = particle * 12;
        putPosition(v, x0, y0, z0); putPosition(v + 3, x1, y1, z1);
        putPosition(v + 6, x2, y2, z2); putPosition(v + 9, x3, y3, z3);
        int tileX = (tile[particle] & 15) * 16;
        int tileY = (tile[particle] >> 4) * 16;
        int t = particle * 8;
        texCoords[t] = (short)(tileX + 1); texCoords[t + 1] = (short)(tileY + 15);
        texCoords[t + 2] = (short)(tileX + 15); texCoords[t + 3] = (short)(tileY + 15);
        texCoords[t + 4] = (short)(tileX + 1); texCoords[t + 5] = (short)(tileY + 1);
        texCoords[t + 6] = (short)(tileX + 15); texCoords[t + 7] = (short)(tileY + 1);
    }

    private void putPosition(int at, float px, float py, float pz) {
        positions[at] = toShort(px);
        positions[at + 1] = toShort(py);
        positions[at + 2] = toShort(pz);
    }

    private short toShort(float value) {
        return (short)(value * SCALE);
    }

    private void setParticleColor(int particle, int alpha) {
        int at = particle * 16;
        for (int v = 0; v < 4; v++) {
            colors[at++] = (byte)255;
            colors[at++] = (byte)255;
            colors[at++] = (byte)255;
            colors[at++] = (byte)alpha;
        }
    }

    private float nextRandom() {
        random = random * 1103515245 + 12345;
        return ((random >>> 8) & 0xFFFF) / 65535.0f;
    }
}

