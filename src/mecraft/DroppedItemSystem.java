package mecraft;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/** Bounded Indev-style item entities sharing one textured mesh. */
final class DroppedItemSystem {
    private static final int CAPACITY = 24;
    private static final int VERTICES_PER_ITEM = 24;
    private static final int VERTEX_COUNT = CAPACITY * VERTICES_PER_ITEM;
    private static final float POSITION_SCALE = 256.0f;
    private static final float HALF_SIZE = 0.125f;
    private static final int[] LOCAL = new int[] {
        -32,32,-32, -32,32,32, 32,32,-32, 32,32,32,
        -32,-32,32, -32,-32,-32, 32,-32,32, 32,-32,-32,
        -32,-32,-32, -32,-32,32, -32,32,-32, -32,32,32,
        32,-32,32, 32,-32,-32, 32,32,32, 32,32,-32,
        -32,-32,-32, -32,32,-32, 32,-32,-32, 32,32,-32,
        32,-32,32, -32,-32,32, 32,32,32, -32,32,32
    };

    final Mesh mesh;
    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] z = new float[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] vz = new float[CAPACITY];
    private final int[] itemId = new int[CAPACITY];
    private final int[] age = new int[CAPACITY];
    private final int[] pickupDelay = new int[CAPACITY];
    private final short[] positions = new short[VERTEX_COUNT * 3];
    private final short[] texCoords = new short[VERTEX_COUNT * 2];
    private final VertexArray positionArray;
    private final VertexArray texCoordArray;
    private int cursor;
    private int activeCount;
    private int random = 0x2468ACE1;

    DroppedItemSystem(Texture2D atlas) {
        positionArray = new VertexArray(VERTEX_COUNT, 3, 2);
        texCoordArray = new VertexArray(VERTEX_COUNT, 2, 2);
        positionArray.set(0, VERTEX_COUNT, positions);
        texCoordArray.set(0, VERTEX_COUNT, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positionArray, 1.0f / POSITION_SCALE, null);
        buffer.setTexCoords(0, texCoordArray, 1.0f / POSITION_SCALE, null);
        int[] strips = new int[CAPACITY * 6];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        TriangleStripArray triangles = new TriangleStripArray(0, strips);

        Appearance appearance = new Appearance();
        appearance.setTexture(0, atlas);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        appearance.setPolygonMode(polygon);
        mesh = new Mesh(buffer, triangles, appearance);
        mesh.setRenderingEnable(false);
        for (int i = 0; i < CAPACITY; i++) writeItem(i);
        positionArray.set(0, VERTEX_COUNT, positions);
        texCoordArray.set(0, VERTEX_COUNT, texCoords);
    }

    void spawn(int id, int bx, int by, int bz) {
        if (id <= Block.AIR || id >= Block.COUNT) return;
        int slot = -1;
        for (int n = 0; n < CAPACITY; n++) {
            int candidate = (cursor + n) % CAPACITY;
            if (itemId[candidate] == 0) { slot = candidate; break; }
        }
        // A fixed pool keeps memory bounded; recycle the oldest visible drop if full.
        if (slot < 0) slot = cursor;
        cursor = slot + 1;
        if (cursor == CAPACITY) cursor = 0;
        if (itemId[slot] == 0) activeCount++;

        itemId[slot] = id;
        age[slot] = 0;
        pickupDelay[slot] = 10;
        x[slot] = bx + 0.15f + nextRandom() * 0.70f;
        y[slot] = by + 0.5f;
        z[slot] = bz + 0.15f + nextRandom() * 0.70f;
        vx[slot] = nextRandom() * 0.2f - 0.1f;
        vy[slot] = 0.2f;
        vz[slot] = nextRandom() * 0.2f - 0.1f;
        writeTexture(slot, id);
    }

    /** Returns one collected block ID, or -1 when nothing was collected. */
    int update(VoxelWorld world, int[] inventory) {
        int picked = -1;
        for (int i = 0; i < CAPACITY; i++) {
            if (itemId[i] == 0) {
                writeItem(i);
                continue;
            }
            age[i]++;
            if (pickupDelay[i] > 0) pickupDelay[i]--;
            if (age[i] >= 6000 || y[i] < -2.0f) {
                itemId[i] = 0;
                activeCount--;
                writeItem(i);
                continue;
            }

            vy[i] -= 0.04f;
            move(world, i);
            vx[i] *= 0.98f;
            vy[i] *= 0.98f;
            vz[i] *= 0.98f;
            boolean onGround = Block.solid(world.get(floorBlock(x[i]),
                floorBlock(y[i] - HALF_SIZE - 0.01f), floorBlock(z[i])));
            if (onGround) {
                vx[i] *= 0.7f;
                vz[i] *= 0.7f;
                vy[i] = 0.0f;
            }

            int id = itemId[i];
            float dx = x[i] - world.playerX;
            float dz = z[i] - world.playerZ;
            boolean overlapsPlayer = Math.abs(dx) < 0.425f && Math.abs(dz) < 0.425f &&
                y[i] + HALF_SIZE >= world.playerY && y[i] - HALF_SIZE <= world.playerY + 1.8f;
            if (pickupDelay[i] == 0 && picked < 0 && inventory[id] < 64 && overlapsPlayer) {
                inventory[id]++;
                itemId[i] = 0;
                activeCount--;
                picked = id;
                writeItem(i);
            } else {
                writeItem(i);
            }
        }
        positionArray.set(0, VERTEX_COUNT, positions);
        mesh.setRenderingEnable(activeCount > 0);
        return picked;
    }

    private void move(VoxelWorld world, int i) {
        float next = x[i] + vx[i];
        if (collides(world, next, y[i], z[i])) vx[i] = 0.0f;
        else x[i] = next;

        next = z[i] + vz[i];
        if (collides(world, x[i], y[i], next)) vz[i] = 0.0f;
        else z[i] = next;

        next = y[i] + vy[i];
        if (collides(world, x[i], next, z[i])) {
            if (vy[i] < 0.0f) y[i] = floorBlock(next - HALF_SIZE) + 1.0f + HALF_SIZE;
            else y[i] = floorBlock(next + HALF_SIZE) - HALF_SIZE;
            vy[i] = 0.0f;
        } else {
            y[i] = next;
        }
    }

    private boolean collides(VoxelWorld world, float px, float py, float pz) {
        int minX = floorBlock(px - HALF_SIZE);
        int maxX = floorBlock(px + HALF_SIZE);
        int minY = floorBlock(py - HALF_SIZE);
        int maxY = floorBlock(py + HALF_SIZE);
        int minZ = floorBlock(pz - HALF_SIZE);
        int maxZ = floorBlock(pz + HALF_SIZE);
        for (int by = minY; by <= maxY; by++) {
            if (Block.solid(world.get(minX, by, minZ)) || Block.solid(world.get(maxX, by, minZ)) ||
                Block.solid(world.get(minX, by, maxZ)) || Block.solid(world.get(maxX, by, maxZ))) return true;
        }
        return false;
    }

    private void writeItem(int slot) {
        int baseVertex = slot * VERTICES_PER_ITEM;
        if (itemId[slot] == 0) {
            int at = baseVertex * 3;
            for (int i = 0; i < VERTICES_PER_ITEM * 3; i++) positions[at + i] = 0;
            return;
        }
        float angle = (age[slot] * 4.0f) * (float)Math.PI / 180.0f;
        float sin = (float)Math.sin(angle);
        float cos = (float)Math.cos(angle);
        float bob = (float)Math.sin(age[slot] * 0.12f) * 0.035f;
        float centerY = y[slot] + bob;
        int centerX = toShort(x[slot]);
        int centerYS = toShort(centerY);
        int centerZ = toShort(z[slot]);
        int at = baseVertex * 3;
        for (int v = 0; v < VERTICES_PER_ITEM; v++) {
            int lx = LOCAL[v * 3];
            int ly = LOCAL[v * 3 + 1];
            int lz = LOCAL[v * 3 + 2];
            positions[at++] = (short)(centerX + (int)(lx * cos + lz * sin));
            positions[at++] = (short)(centerYS + ly);
            positions[at++] = (short)(centerZ + (int)(-lx * sin + lz * cos));
        }
    }

    private void writeTexture(int slot, int id) {
        int vertex = slot * VERTICES_PER_ITEM;
        int at = vertex * 2;
        for (int face = 0; face < 6; face++) {
            int blockFace = face == 0 ? 0 : (face == 1 ? 5 : face - 1);
            int tile = Block.faceTile((byte)id, blockFace);
            int u0 = (tile & 15) * 16 + 1;
            int v0 = (tile >> 4) * 16 + 1;
            int u1 = u0 + 14;
            int v1 = v0 + 14;
            texCoords[at++] = (short)u0; texCoords[at++] = (short)v1;
            texCoords[at++] = (short)u0; texCoords[at++] = (short)v0;
            texCoords[at++] = (short)u1; texCoords[at++] = (short)v1;
            texCoords[at++] = (short)u1; texCoords[at++] = (short)v0;
        }
        texCoordArray.set(0, VERTEX_COUNT, texCoords);
    }

    private int toShort(float value) { return (int)(value * POSITION_SCALE); }

    private int floorBlock(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    private float nextRandom() {
        random = random * 1103515245 + 12345;
        return ((random >>> 8) & 0xFFFF) / 65535.0f;
    }
}

