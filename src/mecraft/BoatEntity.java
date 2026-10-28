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
 * Bounded single-player port of the Alpha 1.2.6 boat entity.
 * The five hull pieces follow ModelBoat's 24x20x6 pixel proportions.
 */
final class BoatEntity {
    private static final float MAX_SPEED = 0.4f;
    final Mesh mesh;
    float x, y, z, yaw;
    float motionX, motionY, motionZ;
    int damage;
    boolean alive = true;

    BoatEntity(Texture2D texture, float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
        final int boxes = 5;
        final int vertices = boxes * 24;
        short[] p = new short[vertices * 3];
        short[] uv = new short[vertices * 2];
        int[] strips = new int[vertices / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        int[] cursor = new int[] { 0 };
        // The original model has one rotated bottom box and four thin side panels.
        addBox(p, uv, cursor, -12, 3, -8, 12, 7, 8, 0, 8, 24, 16, 4);
        addBox(p, uv, cursor, -12, -3, -8, -10, 3, 8, 0, 0, 20, 6, 2);
        addBox(p, uv, cursor, 10, -3, -8, 12, 3, 8, 0, 0, 20, 6, 2);
        addBox(p, uv, cursor, -10, -3, -10, 10, 3, -8, 0, 0, 20, 6, 2);
        addBox(p, uv, cursor, -10, -3, 8, 10, 3, 10, 0, 0, 20, 6, 2);
        VertexArray pa = new VertexArray(vertices, 3, 2);
        pa.set(0, vertices, p);
        VertexArray ta = new VertexArray(vertices, 2, 2);
        ta.set(0, vertices, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 256.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 16.0f, null);
        Appearance a = new Appearance();
        a.setTexture(0, texture);
        Material m = new Material();
        m.setColor(Material.AMBIENT, 0xFFFFFFFF);
        m.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        a.setMaterial(m);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        a.setPolygonMode(pm);
        CompositingMode cm = new CompositingMode();
        cm.setBlending(CompositingMode.ALPHA);
        cm.setAlphaThreshold(0.5f);
        a.setCompositingMode(cm);
        mesh = new Mesh(vb, new TriangleStripArray(0, strips), a);
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private static void addBox(short[] p, short[] uv, int[] cursor,
                               int x0, int y0, int z0, int x1, int y1, int z1,
                               int textureU, int textureV, int textureWidth,
                               int textureHeight, int textureDepth) {
        // Each face gets a stable atlas rectangle; coordinates are scaled for a 64x32 boat texture.
        int[][] faces = new int[][] {
            {x0,y0,z0, x0,y1,z0, x1,y0,z0, x1,y1,z0},
            {x1,y0,z1, x1,y1,z1, x0,y0,z1, x0,y1,z1},
            {x0,y0,z1, x0,y1,z1, x0,y0,z0, x0,y1,z0},
            {x1,y0,z0, x1,y1,z0, x1,y0,z1, x1,y1,z1},
            {x0,y1,z0, x0,y1,z1, x1,y1,z0, x1,y1,z1},
            {x0,y0,z1, x0,y0,z0, x1,y0,z1, x1,y0,z0}
        };
        for (int f = 0; f < faces.length; f++) {
            int[] q = faces[f];
            int v = cursor[0];
            for (int k = 0; k < 4; k++) {
                p[(v+k)*3] = (short)(q[k*3] * 16);
                p[(v+k)*3+1] = (short)(q[k*3+1] * 16);
                p[(v+k)*3+2] = (short)(q[k*3+2] * 16);
            }
            // ModelRenderer's unfolded box UV layout for the original 64x32 boat atlas.
            int u0, u1, v0, v1;
            if (f == 0) { u0 = textureU + textureDepth; u1 = u0 + textureWidth; v0 = textureV + textureDepth; v1 = v0 + textureHeight; }
            else if (f == 1) { u0 = textureU + textureDepth + textureWidth + textureDepth; u1 = u0 + textureWidth; v0 = textureV + textureDepth; v1 = v0 + textureHeight; }
            else if (f == 2) { u0 = textureU; u1 = textureU + textureDepth; v0 = textureV + textureDepth; v1 = v0 + textureHeight; }
            else if (f == 3) { u0 = textureU + textureDepth + textureWidth; u1 = u0 + textureDepth; v0 = textureV + textureDepth; v1 = v0 + textureHeight; }
            else if (f == 4) { u0 = textureU + textureDepth; u1 = u0 + textureWidth; v0 = textureV; v1 = textureV + textureDepth; }
            else { u0 = textureU + textureDepth + textureWidth; u1 = u0 + textureWidth; v0 = textureV; v1 = textureV + textureDepth; }
            int[] tex = new int[] {u0/4,v0/2, u0/4,v1/2, u1/4,v0/2, u1/4,v1/2};
            for (int k = 0; k < 8; k++) uv[v*2+k] = (short)tex[k];
            cursor[0] += 4;
        }
    }

    void update(VoxelWorld world, float forward, float strafe, boolean ridden) {
        if (!alive) return;
        float water = 0.0f;
        // Alpha tests five slices through the hull to estimate submerged volume.
        for (int i = 0; i < 5; i++) {
            float sampleY = y - 0.3f + (i + 0.5f) * 0.12f;
            if (world.get(floor(x - 0.65f), floor(sampleY), floor(z)) == Block.WATER ||
                world.get(floor(x + 0.65f), floor(sampleY), floor(z)) == Block.WATER ||
                world.get(floor(x), floor(sampleY), floor(z - 0.35f)) == Block.WATER ||
                world.get(floor(x), floor(sampleY), floor(z + 0.35f)) == Block.WATER) water += 0.2f;
        }
        motionY += 0.04f * (water * 2.0f - 1.0f);
        if (ridden) {
            float radians = yaw * (float)Math.PI / 180.0f;
            float sin = (float)Math.sin(radians), cos = (float)Math.cos(radians);
            motionX += (forward * sin - strafe * cos) * 0.04f;
            motionZ += (forward * cos + strafe * sin) * 0.04f;
        }
        if (motionX > MAX_SPEED) motionX = MAX_SPEED;
        if (motionX < -MAX_SPEED) motionX = -MAX_SPEED;
        if (motionZ > MAX_SPEED) motionZ = MAX_SPEED;
        if (motionZ < -MAX_SPEED) motionZ = -MAX_SPEED;
        float nx = x + motionX, ny = y + motionY, nz = z + motionZ;
        if (collides(world, nx, y, nz)) {
            if (motionX * motionX + motionZ * motionZ > 0.0225f) alive = false;
            motionX *= 0.5f; motionZ *= 0.5f;
        } else { x = nx; z = nz; }
        if (collides(world, x, ny, z)) motionY = 0.0f;
        else y = ny;
        motionX *= 0.99f; motionY *= 0.95f; motionZ *= 0.99f;
        float speed2 = motionX * motionX + motionZ * motionZ;
        if (speed2 > 0.001f) {
            float target = (float)(Math.atan2(-motionX, motionZ) * 180.0 / Math.PI);
            float delta = target - yaw;
            while (delta >= 180.0f) delta -= 360.0f;
            while (delta < -180.0f) delta += 360.0f;
            if (delta > 20.0f) delta = 20.0f;
            if (delta < -20.0f) delta = -20.0f;
            yaw += delta;
        }
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private static boolean collides(VoxelWorld world, float px, float py, float pz) {
        int minX = floor(px - 0.72f), maxX = floor(px + 0.72f);
        int minY = floor(py - 0.3f), maxY = floor(py + 0.3f);
        int minZ = floor(pz - 0.5f), maxZ = floor(pz + 0.5f);
        for (int by = minY; by <= maxY; by++)
            for (int bx = minX; bx <= maxX; bx++)
                for (int bz = minZ; bz <= maxZ; bz++) {
                    byte block = world.get(bx, by, bz);
                    if (block != Block.AIR && block != Block.WATER && block != Block.LAVA &&
                            !Block.isFluid(block)) return true;
                }
        return false;
    }

    private static int floor(float value) {
        int i = (int)value;
        return value < i ? i - 1 : i;
    }

    void damage(int amount) {
        // EntityBoat scales each successful hit by ten and breaks above forty.
        damage += (amount > 0 ? amount : 1) * 10;
        if (damage > 40) alive = false;
    }
}
