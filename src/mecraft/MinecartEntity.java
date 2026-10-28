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
 * Small single-player minecart using Alpha 1.2.6's rail-following behavior:
 * it snaps to the track center, accelerates gently from rider input, loses
 * speed when unpowered, and falls if it leaves the rails.
 */
final class MinecartEntity {
    private static final float MAX_SPEED = 0.38f;
    final Mesh mesh;
    float x, y, z, yaw;
    float motionX, motionZ;
    boolean alive = true;

    MinecartEntity(Texture2D atlas, float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        // The cart is built from a shallow iron tub and four small wheels.
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
            uv[(v)*2] = (short)u0; uv[(v)*2+1] = (short)(v0+16);
            uv[(v+1)*2] = (short)u0; uv[(v+1)*2+1] = (short)v0;
            uv[(v+2)*2] = (short)(u0+16); uv[(v+2)*2+1] = (short)(v0+16);
            uv[(v+3)*2] = (short)(u0+16); uv[(v+3)*2+1] = (short)v0;
            cursor[0] += 4;
        }
    }

    void update(VoxelWorld world, float forward, boolean ridden) {
        if (!alive) return;
        int bx = floor(x), by = floor(y), bz = floor(z);
        if (world.get(bx, by - 1, bz) == Block.RAIL) by--;
        boolean onRail = world.get(bx, by, bz) == Block.RAIL;
        if (onRail) {
            y = by + 0.18f;
            boolean northSouth = world.get(bx, by, bz - 1) == Block.RAIL ||
                                 world.get(bx, by, bz + 1) == Block.RAIL;
            boolean eastWest = world.get(bx - 1, by, bz) == Block.RAIL ||
                               world.get(bx + 1, by, bz) == Block.RAIL;
            if (northSouth && !eastWest) {
                motionX *= 0.2f;
                if (ridden) motionZ += forward * 0.035f;
                x += (bx + 0.5f - x) * 0.35f;
                yaw = motionZ < 0 ? 180.0f : 0.0f;
            } else {
                motionZ *= 0.2f;
                if (ridden) motionX += forward * 0.035f;
                z += (bz + 0.5f - z) * 0.35f;
                yaw = motionX < 0 ? 270.0f : 90.0f;
            }
            motionX = clamp(motionX, -MAX_SPEED, MAX_SPEED);
            motionZ = clamp(motionZ, -MAX_SPEED, MAX_SPEED);
            x += motionX;
            z += motionZ;
            if (!ridden) { motionX *= 0.96f; motionZ *= 0.96f; }
            // Like Alpha, a cart that runs off the end of a track leaves the rail
            // and begins falling on the next tick.
        } else {
            y -= 0.04f;
            motionX *= 0.95f; motionZ *= 0.95f;
            x += motionX; z += motionZ;
            if (y < 0.0f) alive = false;
        }
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private static int floor(float v) { int i = (int)v; return v < i ? i - 1 : i; }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
