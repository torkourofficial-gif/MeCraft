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

/** Alpha 1.2.6-style minecart geometry and rail/fall physics. */
final class MinecartEntity {
    private static final float MAX_SPEED = 0.32f;
    private static final float ACCELERATION = 0.025f;
    private static final float HALF_HEIGHT = 0.10f;
    final Mesh mesh;
    float x, y, z, yaw;
    float motionX, motionZ;
    boolean alive = true;
    private float speed;
    private float motionY;

    MinecartEntity(Texture2D cartTexture, float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        // ModelMinecart from Alpha 1.2.6: shallow base, four raised sides,
        // and the inset floor. Dimensions are in the original 1/16 model units.
        final int boxes = 6;
        final int vertices = boxes * 24;
        short[] p = new short[vertices * 3];
        short[] uv = new short[vertices * 2];
        int[] strips = new int[vertices / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        int[] cursor = new int[] { 0 };
        addBox(p, uv, cursor, -10, -1, -8, 10, 1, 8, 0, 10);
        addBox(p, uv, cursor, -10, 1, -8, -8, 8, 8, 0, 0);
        addBox(p, uv, cursor, 8, 1, -8, 10, 8, 8, 0, 0);
        addBox(p, uv, cursor, -8, 1, -8, 8, 8, -6, 0, 0);
        addBox(p, uv, cursor, -8, 1, 6, 8, 8, 8, 0, 0);
        addBox(p, uv, cursor, -8, 0, -6, 8, 1, 6, 44, 10);

        VertexArray pa = new VertexArray(vertices, 3, 2);
        pa.set(0, vertices, p);
        VertexArray ta = new VertexArray(vertices, 2, 2);
        ta.set(0, vertices, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 16.0f, null);
        // The classic cart.png is 64x32; double V before applying the common
        // 1/64 texture scale because M3G uses the same scale for both axes.
        vb.setTexCoords(0, ta, 1.0f / 64.0f, null);
        Appearance a = new Appearance();
        a.setTexture(0, cartTexture);
        Material m = new Material();
        m.setColor(Material.AMBIENT, 0xFFFFFFFF);
        m.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        a.setMaterial(m);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        a.setPolygonMode(pm);
        CompositingMode cm = new CompositingMode();
        cm.setAlphaThreshold(0.1f);
        cm.setBlending(CompositingMode.ALPHA);
        a.setCompositingMode(cm);
        mesh = new Mesh(vb, new TriangleStripArray(0, strips), a);
        mesh.setTranslation(x, y, z);
    }

    private static void addBox(short[] p, short[] uv, int[] cursor,
                               int x0, int y0, int z0, int x1, int y1, int z1,
                               int texU, int texV) {
        int w = x1 - x0, h = y1 - y0, d = z1 - z0;
        int[][] faces = new int[][] {
            {x0,y0,z0, x0,y1,z0, x1,y0,z0, x1,y1,z0},
            {x1,y0,z1, x1,y1,z1, x0,y0,z1, x0,y1,z1},
            {x0,y0,z1, x0,y1,z1, x0,y0,z0, x0,y1,z0},
            {x1,y0,z0, x1,y1,z0, x1,y0,z1, x1,y1,z1},
            {x0,y1,z0, x0,y1,z1, x1,y1,z0, x1,y1,z1},
            {x0,y0,z1, x0,y0,z0, x1,y0,z1, x1,y0,z0}
        };
        // Same unfolded box layout used by ModelBox: side strips around the
        // box, with top and bottom above them.
        int[][] rects = new int[][] {
            {texU + d, texV + d, texU + d + w, texV + d + h},
            {texU + d + w + d, texV + d, texU + d + w + d + w, texV + d + h},
            {texU, texV + d, texU + d, texV + d + h},
            {texU + d + w, texV + d, texU + d + w + d, texV + d + h},
            {texU + d, texV, texU + d + w, texV + d},
            {texU + d + w, texV, texU + d + w + w, texV + d}
        };
        for (int f = 0; f < faces.length; f++) {
            int[] q = faces[f];
            int[] r = rects[f];
            int v = cursor[0];
            for (int k = 0; k < 4; k++) {
                p[(v+k)*3] = (short)q[k*3];
                p[(v+k)*3+1] = (short)q[k*3+1];
                p[(v+k)*3+2] = (short)q[k*3+2];
            }
            // Texture V is doubled to compensate for the 64x64 normalized UV space.
            uv[v*2] = (short)r[0]; uv[v*2+1] = (short)(r[3] * 2);
            uv[(v+1)*2] = (short)r[0]; uv[(v+1)*2+1] = (short)(r[1] * 2);
            uv[(v+2)*2] = (short)r[2]; uv[(v+2)*2+1] = (short)(r[3] * 2);
            uv[(v+3)*2] = (short)r[2]; uv[(v+3)*2+1] = (short)(r[1] * 2);
            cursor[0] += 4;
        }
    }

    void update(VoxelWorld world, float forward, boolean ridden) {
        if (!alive) return;
        int bx = floor(x), by = floor(y), bz = floor(z);
        if (world.get(bx, by, bz) != Block.RAIL &&
                world.get(bx, by - 1, bz) == Block.RAIL) by--;
        if (world.get(bx, by, bz) == Block.RAIL) {
            int meta = world.railMetadata(bx, by, bz);
            float ax, az, ex, ez, ay, ey;
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
            // Gravity assists downhill motion and opposes uphill motion.
            speed += (ay - ey) * 0.008f;
            if (speed > MAX_SPEED) speed = MAX_SPEED;
            if (speed < -MAX_SPEED) speed = -MAX_SPEED;
            float tx = bx + 0.5f + ax + dx * t;
            float tz = bz + 0.5f + az + dz * t;
            x = tx + tangentX * speed;
            z = tz + tangentZ * speed;
            float nextT = ((x - (bx + 0.5f + ax)) * dx +
                           (z - (bz + 0.5f + az)) * dz) / len2;
            if (nextT < 0.0f) nextT = 0.0f;
            if (nextT > 1.0f) nextT = 1.0f;
            y = by + 0.18f + ay + (ey - ay) * nextT;
            motionX = tangentX * speed;
            motionZ = tangentZ * speed;
            motionY = 0.0f;
            if (Math.abs(speed) > 0.003f) {
                yaw = (float)(Math.atan2(motionX, motionZ) * 180.0 / Math.PI);
            } else if (meta == 1 || meta == 2 || meta == 3) yaw = 90.0f;
            else yaw = 0.0f;
        } else {
            // Off-rail carts fall, collide with the voxel floor, then lose speed
            // instead of tunnelling through terrain forever.
            motionY -= 0.04f;
            if (motionY < -0.45f) motionY = -0.45f;
            x += motionX;
            z += motionZ;
            float nextY = y + motionY;
            int groundX = floor(x), groundZ = floor(z);
            int below = floor(nextY - HALF_HEIGHT);
            if (world.isSolidAt(groundX, below, groundZ)) {
                y = below + 1.0f + HALF_HEIGHT;
                motionY = 0.0f;
                motionX *= 0.60f;
                motionZ *= 0.60f;
                speed *= 0.60f;
                if (Math.abs(motionX) < 0.003f) motionX = 0.0f;
                if (Math.abs(motionZ) < 0.003f) motionZ = 0.0f;
                if (Math.abs(speed) < 0.003f) speed = 0.0f;
            } else {
                y = nextY;
                motionX *= 0.98f;
                motionZ *= 0.98f;
                if (y < -1.0f) alive = false;
            }
            if (Math.abs(motionX) + Math.abs(motionZ) > 0.003f) {
                yaw = (float)(Math.atan2(motionX, motionZ) * 180.0 / Math.PI);
            }
        }
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private static int floor(float v) { int i = (int)v; return v < i ? i - 1 : i; }
}
