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
 * Lightweight single-player cart. Rail motion follows Alpha 1.2.6 metadata:
 * straight tracks, curves, and the four raised-track directions.
 */
final class MinecartEntity {
    private static final float MAX_SPEED = 0.32f;
    private static final float ACCELERATION = 0.025f;
    final Mesh mesh;
    float x, y, z, yaw;
    float motionX, motionZ;
    boolean alive = true;
    private float speed;

    MinecartEntity(Texture2D atlas, float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        final int boxes = 5;
        final int vertices = boxes * 24;
        short[] p = new short[vertices * 3];
        short[] uv = new short[vertices * 2];
        int[] strips = new int[vertices / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        int[] cursor = new int[] { 0 };
        addBox(p, uv, cursor, -9, -1, -11, 9, 4, 11, 38);
        addBox(p, uv, cursor, -9, 0, -11, -6, 6, -8, 38);
        addBox(p, uv, cursor, 6, 0, -11, 9, 6, -8, 38);
        addBox(p, uv, cursor, -9, 0, 8, -6, 6, 11, 38);
        addBox(p, uv, cursor, 6, 0, 8, 9, 6, 11, 38);
        VertexArray pa = new VertexArray(vertices, 3, 2);
        pa.set(0, vertices, p);
        VertexArray ta = new VertexArray(vertices, 2, 2);
        ta.set(0, vertices, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 256.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
        Appearance a = new Appearance();
        a.setTexture(0, atlas);
        Material m = new Material();
        m.setColor(Material.AMBIENT, 0xFFFFFFFF);
        m.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        a.setMaterial(m);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        a.setPolygonMode(pm);
        CompositingMode cm = new CompositingMode();
        cm.setAlphaThreshold(0.1f);
        a.setCompositingMode(cm);
        mesh = new Mesh(vb, new TriangleStripArray(0, strips), a);
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private static void addBox(short[] p, short[] uv, int[] cursor,
                               int x0, int y0, int z0, int x1, int y1, int z1, int tile) {
        int[][] faces = new int[][] {
            {x0,y0,z0, x0,y1,z0, x1,y0,z0, x1,y1,z0},
            {x1,y0,z1, x1,y1,z1, x0,y0,z1, x0,y1,z1},
            {x0,y0,z1, x0,y1,z1, x0,y0,z0, x0,y1,z0},
            {x1,y0,z0, x1,y1,z0, x1,y0,z1, x1,y1,z1},
            {x0,y1,z0, x0,y1,z1, x1,y1,z0, x1,y1,z1},
            {x0,y0,z1, x0,y0,z0, x1,y0,z1, x1,y0,z0}
        };
        int u0 = (tile & 15) * 16, v0 = (tile >> 4) * 16;
        for (int f = 0; f < faces.length; f++) {
            int[] q = faces[f];
            int v = cursor[0];
            for (int k = 0; k < 4; k++) {
                p[(v+k)*3] = (short)(q[k*3] * 8);
                p[(v+k)*3+1] = (short)(-q[k*3+1] * 8);
                p[(v+k)*3+2] = (short)(q[k*3+2] * 8);
            }
            uv[v*2] = (short)u0; uv[v*2+1] = (short)(v0+16);
            uv[(v+1)*2] = (short)u0; uv[(v+1)*2+1] = (short)v0;
            uv[(v+2)*2] = (short)(u0+16); uv[(v+2)*2+1] = (short)(v0+16);
            uv[(v+3)*2] = (short)(u0+16); uv[(v+3)*2+1] = (short)v0;
            cursor[0] += 4;
        }
    }

    void update(VoxelWorld world, float forward, boolean ridden) {
        if (!alive) return;
        int bx = floor(x), by = floor(y), bz = floor(z);
        if (world.get(bx, by, bz) != Block.RAIL && world.get(bx, by - 1, bz) == Block.RAIL) by--;
        if (world.get(bx, by, bz) == Block.RAIL) {
            int meta = world.railMetadata(bx, by, bz);
            float ax, az, ex, ez, ay, ey;
            // Endpoint table mirrors Alpha's metadata 0-9 rail path table.
            if (meta == 0) { ax = 0.0f; az = -0.5f; ex = 0.0f; ez = 0.5f; ay = 0.0f; ey = 0.0f; }
            else if (meta == 1) { ax = -0.5f; az = 0.0f; ex = 0.5f; ez = 0.0f; ay = 0.0f; ey = 0.0f; }
            else if (meta == 2) { ax = -0.5f; az = 0.0f; ex = 0.5f; ez = 0.0f; ay = 0.0f; ey = 1.0f; }
            else if (meta == 3) { ax = -0.5f; az = 0.0f; ex = 0.5f; ez = 0.0f; ay = 1.0f; ey = 0.0f; }
            else if (meta == 4) { ax = 0.0f; az = -0.5f; ex = 0.0f; ez = 0.5f; ay = 1.0f; ey = 0.0f; }
            else if (meta == 5) { ax = 0.0f; az = -0.5f; ex = 0.0f; ez = 0.5f; ay = 0.0f; ey = 1.0f; }
            else if (meta == 6) { ax = 0.0f; az = 0.5f; ex = 0.5f; ez = 0.0f; ay = 0.0f; ey = 0.0f; }
            else if (meta == 7) { ax = 0.0f; az = 0.5f; ex = -0.5f; ez = 0.0f; ay = 0.0f; ey = 0.0f; }
            else if (meta == 8) { ax = 0.0f; az = -0.5f; ex = -0.5f; ez = 0.0f; ay = 0.0f; ey = 0.0f; }
            else { ax = 0.0f; az = -0.5f; ex = 0.5f; ez = 0.0f; ay = 0.0f; ey = 0.0f; }

            float dx = ex - ax, dz = ez - az;
            float len2 = dx * dx + dz * dz;
            float t = ((x - (bx + 0.5f + ax)) * dx +
                       (z - (bz + 0.5f + az)) * dz) / len2;
            if (t < 0.0f) t = 0.0f;
            if (t > 1.0f) t = 1.0f;
            float tangentX = dx / (float)Math.sqrt(len2);
            float tangentZ = dz / (float)Math.sqrt(len2);
            float along = motionX * tangentX + motionZ * tangentZ;
            if (along < 0.0f) { tangentX = -tangentX; tangentZ = -tangentZ; }
            if (ridden) speed += forward * ACCELERATION;
            else speed *= 0.96f;
            if (speed > MAX_SPEED) speed = MAX_SPEED;
            if (speed < -MAX_SPEED) speed = -MAX_SPEED;
            float tx = bx + 0.5f + ax + dx * t;
            float tz = bz + 0.5f + az + dz * t;
            // Project onto the rail centerline, then move along its tangent.
            x = tx + tangentX * speed;
            z = tz + tangentZ * speed;
            float nextT = ((x - (bx + 0.5f + ax)) * dx +
                           (z - (bz + 0.5f + az)) * dz) / len2;
            if (nextT < 0.0f) nextT = 0.0f;
            if (nextT > 1.0f) nextT = 1.0f;
            y = by + 0.18f + ay + (ey - ay) * nextT;
            motionX = tangentX * speed;
            motionZ = tangentZ * speed;
            yaw = (float)(Math.atan2(motionX, motionZ) * 180.0 / Math.PI);
            if (Math.abs(speed) < 0.003f) yaw = meta == 1 || meta == 2 || meta == 3 ? 90.0f : 0.0f;
        } else {
            // Vanilla carts leave the track at its end and then obey gravity.
            speed *= 0.95f;
            motionX *= 0.95f;
            motionZ *= 0.95f;
            y -= 0.04f;
            x += motionX;
            z += motionZ;
            if (y < 0.0f) alive = false;
        }
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private static int floor(float v) { int i = (int)v; return v < i ? i - 1 : i; }
}
