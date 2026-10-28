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
    private static final float HALF_HEIGHT = 0.125f;
    final Mesh mesh;
    float x, y, z, yaw;
    float motionX, motionZ;
    boolean alive = true;
    private float speed;
    private float motionY;
    private boolean forcedOffRail;

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
        // Build the exact ModelMinecart local boxes, then apply each original pivot/rotation.
        addBox(p, uv, cursor, -10, -8, -1, 10, 8, 1, 0, 10, 20, 16, 2, 0, 4, 0, 90, 0);
        addBox(p, uv, cursor, -8, -9, -1, 8, -1, 1, 0, 0, 16, 8, 2, -9, 4, 0, 0, 270);
        addBox(p, uv, cursor, -8, -9, -1, 8, -1, 1, 0, 0, 16, 8, 2, 9, 4, 0, 0, 90);
        addBox(p, uv, cursor, -8, -9, -1, 8, -1, 1, 0, 0, 16, 8, 2, 0, 4, -7, 0, 180);
        addBox(p, uv, cursor, -8, -9, -1, 8, -1, 1, 0, 0, 16, 8, 2, 0, 4, 7, 0, 0);
        addBox(p, uv, cursor, -9, -7, -1, 9, 7, 0, 44, 10, 18, 14, 1, 0, 4, 0, -90, 0);

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
                               int texU, int texV, int uvW, int uvH, int uvD,
                               int pivotX, int pivotY, int pivotZ, int rotateX, int rotateY) {
        int w = x1 - x0, h = y1 - y0, d = z1 - z0;
        // The original ModelMinecart rotates several boxes after UV assignment.
        // Preserve their pre-rotation dimensions for the texture atlas layout.
        int tw = uvW, th = uvH, td = uvD;
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
            {texU + td, texV + td, texU + td + tw, texV + td + th},
            {texU + td + tw + td, texV + td, texU + td + tw + td + tw, texV + td + th},
            {texU, texV + td, texU + td, texV + td + th},
            {texU + td + tw, texV + td, texU + td + tw + td, texV + td + th},
            {texU + td, texV, texU + td + tw, texV + td},
            {texU + td + tw, texV, texU + td + tw + tw, texV + td}
        };
        for (int f = 0; f < faces.length; f++) {
            int[] q = faces[f];
            int[] r = rects[f];
            int v = cursor[0];
            for (int k = 0; k < 4; k++) {
                float px = q[k*3], py = q[k*3+1], pz = q[k*3+2];
                // ModelRenderer rotates around its pivot after defining UVs on the unrotated box.
                px += pivotX; py += pivotY; pz += pivotZ;
                double rx = rotateX * Math.PI / 180.0;
                double ry = rotateY * Math.PI / 180.0;
                float localY = py - pivotY, localZ = pz - pivotZ;
                float rotatedY = (float)(localY * Math.cos(rx) - localZ * Math.sin(rx));
                float rotatedZ = (float)(localY * Math.sin(rx) + localZ * Math.cos(rx));
                float localX = px - pivotX;
                float rotatedX = (float)(localX * Math.cos(ry) + rotatedZ * Math.sin(ry));
                rotatedZ = (float)(-localX * Math.sin(ry) + rotatedZ * Math.cos(ry));
                // RenderMinecart applies glScalef(-1, -1, 1) before rendering ModelMinecart.
                p[(v+k)*3] = (short)-(rotatedX + pivotX);
                p[(v+k)*3+1] = (short)-(rotatedY + pivotY);
                p[(v+k)*3+2] = (short)(rotatedZ + pivotZ);
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
        if (world.get(bx, by, bz) == Block.RAIL && !forcedOffRail) {
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
            float proposedX = tx + tangentX * speed;
            float proposedZ = tz + tangentZ * speed;
            float nextT = ((proposedX - (bx + 0.5f + ax)) * dx +
                           (proposedZ - (bz + 0.5f + az)) * dz) / len2;
            boolean atEnd = (nextT > 1.0f && speed > 0.0f) ||
                            (nextT < 0.0f && speed < 0.0f);
            int aheadX = floor(proposedX + tangentX * 0.30f);
            int aheadZ = floor(proposedZ + tangentZ * 0.30f);
            boolean railAhead = world.get(aheadX, by, aheadZ) == Block.RAIL ||
                                world.get(aheadX, by + 1, aheadZ) == Block.RAIL ||
                                world.get(aheadX, by - 1, aheadZ) == Block.RAIL;
            if (atEnd && !railAhead) {
                // Let the cart roll off the end instead of snapping it to the
                // endpoint forever; the following ticks use normal fall physics.
                x = proposedX;
                z = proposedZ;
                y = by + 0.18f + (nextT > 1.0f ? ey : ay);
                motionX = tangentX * speed;
                motionZ = tangentZ * speed;
                motionY = 0.0f;
                forcedOffRail = true;
            } else {
                if (nextT < 0.0f) nextT = 0.0f;
                if (nextT > 1.0f) nextT = 1.0f;
                x = proposedX;
                z = proposedZ;
                y = by + 0.18f + ay + (ey - ay) * nextT;
                motionX = tangentX * speed;
                motionZ = tangentZ * speed;
                motionY = 0.0f;
            }
            if (Math.abs(speed) > 0.003f) {
                yaw = angleDegrees(motionX, motionZ);
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
                yaw = angleDegrees(motionX, motionZ);
            }
        }
        mesh.setTranslation(x, y, z);
        // RenderMinecart rotates by 180 - entity yaw; keep travel yaw separate for the rider.
        mesh.setOrientation(180.0f - yaw, 0.0f, 1.0f, 0.0f);
    }


    // Approximate atan2 using arithmetic only for restricted Java ME runtimes.
    private static float angleDegrees(float vx, float vz) {
        float ax = vx < 0.0f ? -vx : vx;
        float az = vz < 0.0f ? -vz : vz;
        float angle;
        if (ax == 0.0f && az == 0.0f) return 0.0f;
        if (az >= ax) {
            float r = az == 0.0f ? 0.0f : ax / az;
            angle = r * (45.0f + 15.642f * (1.0f - r));
        } else {
            float r = ax == 0.0f ? 0.0f : az / ax;
            angle = 90.0f - r * (45.0f + 15.642f * (1.0f - r));
        }
        if (vz < 0.0f) {
            if (vx >= 0.0f) return 180.0f - angle;
            return -180.0f + angle;
        }
        return vx < 0.0f ? -angle : angle;
    }

    private static int floor(float v) { int i = (int)v; return v < i ? i - 1 : i; }
}
