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

/** Bounded Indev-style item entities using a block mesh and a small item-sprite mesh. */
final class DroppedItemSystem {
    private static final int CAPACITY = 24;
    private static final int VERTICES_PER_ITEM = 24;
    private static final int VERTEX_COUNT = CAPACITY * VERTICES_PER_ITEM;
    private static final float POSITION_SCALE = 256.0f;
    private static final float HALF_SIZE = 0.125f;
    private static final float PICKUP_RADIUS = 1.5f;
    private static final float PICKUP_RADIUS_SQUARED = PICKUP_RADIUS * PICKUP_RADIUS;
    private static final int[] LOCAL = new int[] {
        -32,32,-32, -32,32,32, 32,32,-32, 32,32,32,
        -32,-32,32, -32,-32,-32, 32,-32,32, 32,-32,-32,
        -32,-32,-32, -32,-32,32, -32,32,-32, -32,32,32,
        32,-32,32, 32,-32,-32, 32,32,32, 32,32,-32,
        -32,-32,-32, -32,32,-32, 32,-32,-32, 32,32,-32,
        32,-32,32, -32,-32,32, 32,32,32, -32,32,32
    };
    private static final int[] FLOWER_LOCAL = new int[] {
        -16,-32,-16, 16,-32,16, 16,32,16, -16,32,-16,
        -16,-32,16, 16,-32,-16, 16,32,-16, -16,32,16
    };

    final Mesh mesh;
    final Mesh spriteMesh;
    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] z = new float[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] vz = new float[CAPACITY];
    private final int[] itemId = new int[CAPACITY];
    private final int[] age = new int[CAPACITY];
    private final int[] pickupDelay = new int[CAPACITY];
    private final int[] tntFuse = new int[CAPACITY];
    private final int[] tntTextureFrame = new int[CAPACITY];
    private final short[] positions = new short[VERTEX_COUNT * 3];
    private final short[] texCoords = new short[VERTEX_COUNT * 2];
    private final short[] spritePositions = new short[VERTEX_COUNT * 3];
    private final short[] spriteTexCoords = new short[VERTEX_COUNT * 2];
    private final VertexArray positionArray;
    private final VertexArray texCoordArray;
    private final VertexArray spritePositionArray;
    private final VertexArray spriteTexCoordArray;
    private int cursor;
    private int activeCount;
    private int random = 0x2468ACE1;
    private boolean explosionPending;
    private int explosionX, explosionY, explosionZ;

    DroppedItemSystem(Texture2D atlas, Texture2D itemAtlas) {
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
        CompositingMode compositing = new CompositingMode();
        compositing.setAlphaThreshold(0.5f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        mesh = new Mesh(buffer, triangles, appearance);
        mesh.setRenderingEnable(false);
        spritePositionArray = new VertexArray(VERTEX_COUNT, 3, 2);
        spritePositionArray.set(0, VERTEX_COUNT, spritePositions);
        spriteTexCoordArray = new VertexArray(VERTEX_COUNT, 2, 2);
        spriteTexCoordArray.set(0, VERTEX_COUNT, spriteTexCoords);
        VertexBuffer spriteBuffer = new VertexBuffer();
        spriteBuffer.setPositions(spritePositionArray, 1.0f / POSITION_SCALE, null);
        spriteBuffer.setTexCoords(0, spriteTexCoordArray, 1.0f / 256.0f, null);
        Appearance spriteAppearance = new Appearance();
        spriteAppearance.setTexture(0, itemAtlas);
        Material spriteMaterial = new Material();
        spriteMaterial.setColor(Material.AMBIENT, 0xFFFFFFFF);
        spriteMaterial.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        spriteAppearance.setMaterial(spriteMaterial);
        PolygonMode spritePolygon = new PolygonMode();
        spritePolygon.setCulling(PolygonMode.CULL_NONE);
        spriteAppearance.setPolygonMode(spritePolygon);
        CompositingMode spriteCompositing = new CompositingMode();
        spriteCompositing.setAlphaThreshold(0.5f);
        spriteCompositing.setBlending(CompositingMode.ALPHA);
        spriteAppearance.setCompositingMode(spriteCompositing);
        int[] spriteStrips = new int[CAPACITY * 6];
        for (int i = 0; i < spriteStrips.length; i++) spriteStrips[i] = 4;
        spriteMesh = new Mesh(spriteBuffer, new TriangleStripArray(0, spriteStrips), spriteAppearance);
        spriteMesh.setRenderingEnable(false);
        for (int i = 0; i < CAPACITY; i++) writeItem(i);
        positionArray.set(0, VERTEX_COUNT, positions);
        texCoordArray.set(0, VERTEX_COUNT, texCoords);
        spritePositionArray.set(0, VERTEX_COUNT, spritePositions);
        spriteTexCoordArray.set(0, VERTEX_COUNT, spriteTexCoords);
    }

    void spawn(int id, int bx, int by, int bz) {
        if (id <= Block.AIR || id >= ItemCatalog.COUNT) return;
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
        tntFuse[slot] = 0;
        tntTextureFrame[slot] = -1;
        age[slot] = 0;
        pickupDelay[slot] = 10;
        x[slot] = bx + 0.15f + nextRandom() * 0.70f;
        y[slot] = by + 0.5f;
        z[slot] = bz + 0.15f + nextRandom() * 0.70f;
        vx[slot] = nextRandom() * 0.2f - 0.1f;
        vy[slot] = 0.2f;
        vz[slot] = nextRandom() * 0.2f - 0.1f;
        if (id < Block.COUNT) writeTexture(slot, id);
        else writeSpriteTexture(slot, id);
    }

    void spawnPrimedTnt(int bx, int by, int bz, int fuse) {
        spawn(Block.TNT, bx, by, bz);
        int slot = cursor == 0 ? CAPACITY - 1 : cursor - 1;
        tntFuse[slot] = fuse;
        pickupDelay[slot] = fuse + 20;
        float angle = nextRandom() * 6.2831855f;
        vx[slot] = (float)Math.sin(angle) * 0.02f;
        vy[slot] = 0.2f;
        vz[slot] = (float)Math.cos(angle) * 0.02f;
        tntTextureFrame[slot] = tntFuse[slot] / 5 % 2;
        writeTexture(slot, Block.TNT);
    }

    /** Returns one collected block ID, or -1 when nothing was collected. */
    int update(VoxelWorld world, int[] inventory) {
        int picked = -1;
        boolean hasBlocks = false;
        boolean hasSprites = false;
        for (int i = 0; i < CAPACITY; i++) {
            if (itemId[i] == 0) {
                writeItem(i);
                continue;
            }
            age[i]++;
            if (pickupDelay[i] > 0) pickupDelay[i]--;
            if (age[i] >= 6000 || y[i] < -2.0f) {
                itemId[i] = 0;
                tntFuse[i] = 0;
                activeCount--;
                writeItem(i);
                continue;
            }

            boolean primedTnt = tntFuse[i] > 0;
            int textureFrame = tntFuse[i] / 5 % 2;
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
                if (primedTnt) vy[i] *= -0.5f;
                else vy[i] = 0.0f;
            }
            if (primedTnt) {
                int nextTextureFrame = tntFuse[i] / 5 % 2;
                if (nextTextureFrame != textureFrame || nextTextureFrame != tntTextureFrame[i]) {
                    writeTexture(i, Block.TNT);
                    tntTextureFrame[i] = nextTextureFrame;
                }
            }
            if (primedTnt && tntFuse[i]-- <= 0) {
                explosionX = floorBlock(x[i]);
                explosionY = floorBlock(y[i]);
                explosionZ = floorBlock(z[i]);
                explosionPending = true;
                damagePlayerFromExplosion(world, x[i], y[i], z[i]);
                world.explode(x[i], y[i], z[i], 4.0f, this);
                itemId[i] = 0;
                tntFuse[i] = 0;
                tntTextureFrame[i] = -1;
                activeCount--;
                writeItem(i);
                continue;
            }

            int id = itemId[i];
            float dx = x[i] - world.playerX;
            float dy = y[i] - (world.playerY + 0.9f);
            float dz = z[i] - world.playerZ;
            boolean inPickupRange = dx * dx + dy * dy + dz * dz <= PICKUP_RADIUS_SQUARED;
            if (pickupDelay[i] == 0 && picked < 0 && inventory[id] < ItemCatalog.maxStack(id) && inPickupRange) {
                inventory[id]++;
                itemId[i] = 0;
                tntFuse[i] = 0;
                tntTextureFrame[i] = -1;
                activeCount--;
                picked = id;
                writeItem(i);
            } else {
                writeItem(i);
            }
            if (itemId[i] > 0) {
                if (itemId[i] < Block.COUNT) hasBlocks = true;
                else hasSprites = true;
            }
        }
        positionArray.set(0, VERTEX_COUNT, positions);
        spritePositionArray.set(0, VERTEX_COUNT, spritePositions);
        mesh.setRenderingEnable(hasBlocks);
        spriteMesh.setRenderingEnable(hasSprites);
        return picked;
    }

    boolean consumeExplosion() {
        if (!explosionPending) return false;
        explosionPending = false;
        return true;
    }

    int explosionX() { return explosionX; }
    int explosionY() { return explosionY; }
    int explosionZ() { return explosionZ; }

    private void damagePlayerFromExplosion(VoxelWorld world, float ex, float ey, float ez) {
        if (world.mode != 0) return;
        float dx = world.playerX - ex;
        float dy = world.playerY + 0.9f - ey;
        float dz = world.playerZ - ez;
        float distance = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        float impact = 1.0f - distance / 8.0f;
        if (impact <= 0.0f) return;
        int damage = (int)((impact * impact + impact) * 32.0f + 1.0f);
        world.health -= damage;
        if (world.health < 0) world.health = 0;
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
            for (int i = 0; i < VERTICES_PER_ITEM * 3; i++) spritePositions[at + i] = 0;
            return;
        }
        float angle = (age[slot] * 4.0f) * (float)Math.PI / 180.0f;
        float sin = (float)Math.sin(angle);
        float cos = (float)Math.cos(angle);
        float bob = (float)Math.sin(age[slot] * 0.12f) * 0.035f;
        float scale = 1.0f;
        if (itemId[slot] == Block.TNT && tntFuse[slot] > 0 && tntFuse[slot] < 10) {
            float progress = 1.0f - tntFuse[slot] / 10.0f;
            float pulse = progress * progress;
            pulse *= pulse;
            scale += pulse * 0.3f;
        }
        float centerY = y[slot] + bob;
        int centerX = toShort(x[slot]);
        int centerYS = toShort(centerY);
        int centerZ = toShort(z[slot]);
        if (itemId[slot] >= Block.COUNT) {
            int blockAt = baseVertex * 3;
            for (int i = 0; i < VERTICES_PER_ITEM * 3; i++) positions[blockAt + i] = 0;
            int spriteAt = blockAt;
            for (int v = 0; v < VERTICES_PER_ITEM; v++) {
                int lx = v < 8 ? FLOWER_LOCAL[v * 3] : 0;
                int ly = v < 8 ? FLOWER_LOCAL[v * 3 + 1] : 0;
                int lz = v < 8 ? FLOWER_LOCAL[v * 3 + 2] : 0;
                spritePositions[spriteAt++] = (short)(centerX + (int)(lx * cos + lz * sin));
                spritePositions[spriteAt++] = (short)(centerYS + ly);
                spritePositions[spriteAt++] = (short)(centerZ + (int)(-lx * sin + lz * cos));
            }
            return;
        }
        int spriteAt = baseVertex * 3;
        for (int i = 0; i < VERTICES_PER_ITEM * 3; i++) spritePositions[spriteAt + i] = 0;
        int at = baseVertex * 3;
        for (int v = 0; v < VERTICES_PER_ITEM; v++) {
            int lx, ly, lz;
            if (Block.isFlower((byte)itemId[slot])) {
                lx = v < 8 ? FLOWER_LOCAL[v * 3] : 0;
                ly = v < 8 ? FLOWER_LOCAL[v * 3 + 1] : 0;
                lz = v < 8 ? FLOWER_LOCAL[v * 3 + 2] : 0;
            } else {
                lx = LOCAL[v * 3];
                ly = LOCAL[v * 3 + 1];
                lz = LOCAL[v * 3 + 2];
            }
            lx = (int)(lx * scale);
            ly = (int)(ly * scale);
            lz = (int)(lz * scale);
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
            if (id == Block.TNT && tntFuse[slot] > 0 && tntFuse[slot] / 5 % 2 == 0) tile = 5;
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

    private void writeSpriteTexture(int slot, int id) {
        int icon = ItemCatalog.iconIndex(id);
        if (icon < 0) return;
        int at = slot * VERTICES_PER_ITEM * 2;
        int u0 = (icon & 15) * 16, v0 = (icon >> 4) * 16;
        int u1 = u0 + 16, v1 = v0 + 16;
        for (int face = 0; face < 6; face++) {
            spriteTexCoords[at++] = (short)u0; spriteTexCoords[at++] = (short)v1;
            spriteTexCoords[at++] = (short)u0; spriteTexCoords[at++] = (short)v0;
            spriteTexCoords[at++] = (short)u1; spriteTexCoords[at++] = (short)v1;
            spriteTexCoords[at++] = (short)u1; spriteTexCoords[at++] = (short)v0;
        }
        spriteTexCoordArray.set(0, VERTEX_COUNT, spriteTexCoords);
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
