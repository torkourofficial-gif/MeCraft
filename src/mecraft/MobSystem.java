package mecraft;

import java.io.IOException;
import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/** Small, fixed-pool Indev mob models and movement for the 4 MiB target. */
final class MobSystem {
    private static final int PIG = 0;
    private static final int SHEEP = 1;
    private static final int ZOMBIE = 2;
    private static final int MOB_COUNT = 4;

    private final Mob[] mobs = new Mob[MOB_COUNT];
    private final Random random = new Random(1L);
    private int worldSeed = Integer.MIN_VALUE;
    private int tick;
    private int attackCooldown;

    MobSystem(Group scene) throws IOException {
        Appearance[] skins = new Appearance[] {
            skin("/mecraft/pig.png"), skin("/mecraft/sheep.png"), skin("/mecraft/zombie.png")
        };
        for (int i = 0; i < mobs.length; i++) {
            int type = i == 0 ? PIG : (i == 1 ? SHEEP : ZOMBIE);
            mobs[i] = new Mob(type, skins[type]);
            mobs[i].root.setRenderingEnable(false);
            scene.addChild(mobs[i].root);
        }
    }

    /** Returns damage dealt to the player on this tick. */
    int update(VoxelWorld world) {
        if (worldSeed != world.seed) reset(world);
        tick++;
        if (attackCooldown > 0) attackCooldown--;
        int playerDamage = 0;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive || (mob.type == ZOMBIE && world.mode != 0)) continue;
            mob.age++;
            mob.chasing = false;
            if (mob.type == ZOMBIE) {
                float dx = world.playerX - mob.x;
                float dz = world.playerZ - mob.z;
                float distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < 100.0f) {
                    mob.chasing = true;
                    mob.targetYaw = yawTo(dx, dz);
                    if (distanceSquared < 0.85f * 0.85f &&
                        world.playerY < mob.y + 1.8f && world.playerY + 1.8f > mob.y) {
                        if (mob.attackDelay <= 0) {
                            mob.attackDelay = 20;
                            playerDamage = 2;
                        }
                    }
                } else wander(mob, 90);
            } else {
                wander(mob, 58);
            }
            if (mob.attackDelay > 0) mob.attackDelay--;
            animate(mob);
            turn(mob);
            move(mob, world);
            mob.root.setTranslation(mob.x, mob.y, mob.z);
            mob.root.setOrientation(mob.yaw, 0.0f, 1.0f, 0.0f);
        }
        return playerDamage;
    }

    /** Hit a mob on the camera ray before continuing to mine a block. */
    boolean hit(VoxelWorld world, float pitch, int damage) {
        if (worldSeed != world.seed) reset(world);
        float yaw = world.yaw * (float)Math.PI / 180.0f;
        float pitchRadians = pitch * (float)Math.PI / 180.0f;
        float dirX = (float)Math.sin(yaw) * (float)Math.cos(pitchRadians);
        float dirY = (float)Math.sin(pitchRadians);
        float dirZ = (float)Math.cos(yaw) * (float)Math.cos(pitchRadians);
        float originX = world.playerX;
        float originY = world.playerY + 1.62f;
        float originZ = world.playerZ;
        int closest = -1;
        float closestDistance = 4.0f;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive || (mob.type == ZOMBIE && world.mode != 0)) continue;
            float centerY = mob.y + (mob.type == ZOMBIE ? 0.9f : 0.7f);
            float dx = mob.x - originX;
            float dy = centerY - originY;
            float dz = mob.z - originZ;
            float along = dx * dirX + dy * dirY + dz * dirZ;
            if (along < 0.0f || along > closestDistance) continue;
            float ex = dx - dirX * along, ey = dy - dirY * along, ez = dz - dirZ * along;
            float radius = mob.type == ZOMBIE ? 0.48f : 0.58f;
            if (ex * ex + ey * ey + ez * ez <= radius * radius) {
                closest = i;
                closestDistance = along;
            }
        }
        if (closest < 0) return false;
        if (blocked(world, originX, originY, originZ, dirX, dirY, dirZ, closestDistance)) return false;
        if (attackCooldown > 0) return true;
        attackCooldown = 8;
        Mob target = mobs[closest];
        target.health -= damage;
        if (target.health <= 0) {
            target.alive = false;
            target.root.setRenderingEnable(false);
        } else {
            target.fleeTicks = 35;
            target.targetYaw = yawTo(target.x - world.playerX, target.z - world.playerZ);
        }
        return true;
    }

    private void reset(VoxelWorld world) {
        worldSeed = world.seed;
        tick = 0;
        attackCooldown = 0;
        random.setSeed(world.seed ^ 0x51ED270B);
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            mob.health = mob.type == ZOMBIE ? 20 : (mob.type == PIG ? 10 : 8);
            mob.alive = mob.type != ZOMBIE || world.mode == 0;
            mob.root.setRenderingEnable(mob.alive);
            int attempts = 0;
            int x = VoxelWorld.WIDTH / 2, z = VoxelWorld.DEPTH / 2;
            do {
                int radius = mob.type == ZOMBIE ? 9 + random.nextInt(10) : 4 + random.nextInt(12);
                int angle = random.nextInt(360);
                x = VoxelWorld.WIDTH / 2 + (int)(Math.cos(angle * Math.PI / 180.0) * radius);
                z = VoxelWorld.DEPTH / 2 + (int)(Math.sin(angle * Math.PI / 180.0) * radius);
                x = clamp(x, 2, VoxelWorld.WIDTH - 3);
                z = clamp(z, 2, VoxelWorld.DEPTH - 3);
                attempts++;
            } while ((!validSpawn(world, x, z) || tooClose(mob, x + 0.5f, z + 0.5f)) && attempts < 48);
            int ground = world.surfaceY(x, z);
            mob.x = x + 0.5f;
            mob.z = z + 0.5f;
            mob.y = ground + 1.0f;
            mob.yaw = random.nextInt(360);
            mob.targetYaw = mob.yaw;
            mob.age = random.nextInt(100);
            mob.nextWander = 20 + random.nextInt(100);
            mob.attackDelay = 0;
            mob.fleeTicks = 0;
            mob.walkTicks = 0;
            mob.chasing = false;
            mob.root.setTranslation(mob.x, mob.y, mob.z);
            mob.root.setOrientation(mob.yaw, 0.0f, 1.0f, 0.0f);
        }
    }

    private boolean validSpawn(VoxelWorld world, int x, int z) {
        int y = world.surfaceY(x, z);
        byte ground = world.get(x, y, z);
        return Block.solid(ground) && ground != Block.LEAVES &&
               world.get(x, y + 1, z) == Block.AIR && world.get(x, y + 2, z) == Block.AIR;
    }

    private boolean tooClose(Mob mob, float x, float z) {
        for (int i = 0; i < mobs.length; i++) {
            Mob other = mobs[i];
            if (other == mob || other.y <= 0.0f) continue;
            float dx = other.x - x, dz = other.z - z;
            if (dx * dx + dz * dz < 9.0f) return true;
        }
        return false;
    }

    private void wander(Mob mob, int interval) {
        if (mob.fleeTicks > 0) {
            mob.fleeTicks--;
            mob.walkTicks = 30;
            if (mob.age % 30 == 0) mob.targetYaw += 100.0f + random.nextInt(160);
        } else if (tick >= mob.nextWander) {
            mob.targetYaw = random.nextInt(360);
            mob.walkTicks = 15 + random.nextInt(36);
            mob.nextWander = tick + mob.walkTicks + 20 + random.nextInt(interval);
        }
    }

    private void turn(Mob mob) {
        float delta = mob.targetYaw - mob.yaw;
        while (delta < -180.0f) delta += 360.0f;
        while (delta > 180.0f) delta -= 360.0f;
        if (delta > 4.0f) delta = 4.0f;
        if (delta < -4.0f) delta = -4.0f;
        mob.yaw += delta;
        if (mob.yaw < 0.0f) mob.yaw += 360.0f;
        if (mob.yaw >= 360.0f) mob.yaw -= 360.0f;
    }

    private void move(Mob mob, VoxelWorld world) {
        boolean walking = mob.chasing || mob.walkTicks > 0 || mob.fleeTicks > 0;
        float speed = mob.type == ZOMBIE ? 0.026f : 0.014f;
        if (mob.type != ZOMBIE && mob.fleeTicks > 0) speed *= 1.6f;
        if (!walking) speed = 0.0f;
        float radians = mob.yaw * (float)Math.PI / 180.0f;
        float nx = mob.x + (float)Math.sin(radians) * speed;
        float nz = mob.z + (float)Math.cos(radians) * speed;
        if (canMove(world, mob, nx, mob.z)) mob.x = nx;
        else mob.targetYaw += 75.0f;
        if (canMove(world, mob, mob.x, nz)) mob.z = nz;
        else mob.targetYaw -= 140.0f;
        int groundY = (int)mob.y - 1;
        if (!Block.solid(world.get((int)mob.x, groundY, (int)mob.z))) {
            float fall = mob.y - 0.08f;
            if (Block.solid(world.get((int)mob.x, (int)fall - 1, (int)mob.z)))
                mob.y = (int)fall;
            else mob.y = fall;
        } else {
            mob.y = groundY + 1.0f;
        }
    }

    private boolean canMove(VoxelWorld world, Mob mob, float x, float z) {
        int bx = (int)x, bz = (int)z;
        if (bx < 1 || bz < 1 || bx >= VoxelWorld.WIDTH - 1 || bz >= VoxelWorld.DEPTH - 1) return false;
        int feet = (int)mob.y;
        if (Block.solid(world.get(bx, feet, bz)) || Block.solid(world.get(bx, feet + 1, bz))) return false;
        return Block.solid(world.get(bx, feet - 1, bz));
    }

    private void animate(Mob mob) {
        float phase = mob.age * 0.22f;
        float walk = 0.0f;
        if (mob.type == ZOMBIE && mob.chasing) walk = 0.9f;
        else if (mob.fleeTicks > 0 || mob.walkTicks > 0) walk = 0.5f;
        if (mob.walkTicks > 0) mob.walkTicks--;
        if (mob.type == ZOMBIE) {
            mob.legs[0].setOrientation((float)Math.cos(phase) * 35.0f * walk, 1, 0, 0);
            mob.legs[1].setOrientation((float)Math.cos(phase + Math.PI) * 35.0f * walk, 1, 0, 0);
            mob.arms[0].setOrientation(-88.0f + (float)Math.sin(phase) * 5.0f, 1, 0, 0);
            mob.arms[1].setOrientation(-88.0f + (float)Math.sin(phase + Math.PI) * 5.0f, 1, 0, 0);
        } else {
            float stride = (float)Math.cos(phase) * 28.0f * walk;
            mob.legs[0].setOrientation(stride, 1, 0, 0);
            mob.legs[1].setOrientation(-stride, 1, 0, 0);
            mob.legs[2].setOrientation(-stride, 1, 0, 0);
            mob.legs[3].setOrientation(stride, 1, 0, 0);
            mob.head.setOrientation((float)Math.sin(phase * 0.22f) * 6.0f, 0, 1, 0);
        }
    }

    private Appearance skin(String resource) throws IOException {
        Image image = Image.createImage(resource);
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setAlphaThreshold(0.1f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private boolean blocked(VoxelWorld world, float ox, float oy, float oz,
            float dx, float dy, float dz, float distance) {
        int steps = (int)(distance * 8.0f);
        for (int i = 1; i < steps; i++) {
            float t = i / 8.0f;
            if (Block.solid(world.get(floor(ox + dx * t), floor(oy + dy * t),
                                      floor(oz + dz * t)))) return true;
        }
        return false;
    }

    private int floor(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    private int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** CLDC-compatible octant interpolation for atan2(dx, dz), in degrees. */
    private float yawTo(float dx, float dz) {
        float ax = dx < 0.0f ? -dx : dx;
        float az = dz < 0.0f ? -dz : dz;
        float base;
        if (az >= ax) base = az == 0.0f ? 0.0f : 45.0f * ax / az;
        else base = 90.0f - 45.0f * az / ax;
        if (dx >= 0.0f && dz >= 0.0f) return base;
        if (dx >= 0.0f) return 180.0f - base;
        if (dz < 0.0f) return base - 180.0f;
        return -base;
    }

    private final class Mob {
        final int type;
        final Group root = new Group();
        final Group head = new Group();
        final Group[] legs = new Group[4];
        final Group[] arms = new Group[2];
        private final Appearance skin;
        int health;
        int age;
        int attackDelay;
        int fleeTicks;
        int nextWander;
        boolean alive;
        boolean chasing;
        int walkTicks;
        float x, y, z, yaw, targetYaw;

        Mob(int type, Appearance appearance) {
            this.type = type;
            this.skin = appearance;
            if (type == ZOMBIE) buildZombie(appearance);
            else buildQuadruped(appearance, type == PIG);
        }

        private void buildQuadruped(Appearance appearance, boolean pig) {
            if (pig) {
                addPart(root, 28, 8, 10, 16, 8, 10, 16, 8,
                        0.0f, 0.625f, 0.0f, 90.0f, 1, 0, 0);
                addPart(head, 0, 0, 8, 8, 8, 8, 8, 8,
                        0.0f, 0.0f, 0.0f, 0, 0, 1, 0);
                head.setTranslation(0.0f, 0.75f, -0.625f);
                root.addChild(head);
                addLegs(6, 0.375f, 0.1875f);
            } else {
                addPart(root, 28, 8, 8, 16, 6, 8, 16, 6,
                        0.0f, 0.9375f, 0.0f, 90.0f, 1, 0, 0);
                addPart(head, 0, 0, 6, 6, 8, 6, 6, 8,
                        0.0f, 0.0f, 0.0f, 0, 0, 1, 0);
                head.setTranslation(0.0f, 1.1875f, -0.625f);
                root.addChild(head);
                addLegs(12, 0.75f, 0.1875f);
            }
        }

        private void addLegs(int length, float pivotY, float halfWidth) {
            for (int i = 0; i < 4; i++) {
                Group leg = new Group();
                float x = (i & 1) == 0 ? -halfWidth : halfWidth;
                float z = i < 2 ? 0.4375f : -0.3125f;
                leg.setTranslation(x, pivotY, z);
                root.addChild(leg);
                addPart(leg, 0, 16, 4, length, 4, 4, length, 4,
                        0.0f, -length / 32.0f, 0.0f, 0, 0, 1, 0);
                legs[i] = leg;
            }
        }

        private void buildZombie(Appearance appearance) {
            addPart(head, 0, 0, 8, 8, 8, 8, 8, 8,
                    0.0f, 1.75f, 0.0f, 0, 0, 1, 0);
            addPart(head, 32, 0, 9, 9, 9, 8, 8, 8,
                    0.0f, 1.75f, 0.0f, 0, 0, 1, 0);
            root.addChild(head);
            addPart(root, 16, 16, 8, 12, 4, 8, 12, 4,
                    0.0f, 1.125f, 0.0f, 0, 0, 1, 0);
            for (int i = 0; i < 2; i++) {
                float side = i == 0 ? -1.0f : 1.0f;
                Group arm = new Group();
                arm.setTranslation(side * 0.3125f, 1.375f, 0.0f);
                root.addChild(arm);
                addPart(arm, 40, 16, 4, 12, 4, 4, 12, 4,
                        side * 0.0625f, -0.25f, 0.0f, 0, 0, 1, 0);
                arms[i] = arm;

                Group leg = new Group();
                leg.setTranslation(side * 0.125f, 0.75f, 0.0f);
                root.addChild(leg);
                addPart(leg, 0, 16, 4, 12, 4, 4, 12, 4,
                        0.0f, -0.375f, 0.0f, 0, 0, 1, 0);
                legs[i] = leg;
            }
        }

        private void addPart(Group parent, int u, int v, int width, int height, int depth,
                int layoutWidth, int layoutHeight, int layoutDepth,
                float x, float y, float z, float angle, float ax, float ay, float az) {
            Group part = new Group();
            part.setTranslation(x, y, z);
            if (angle != 0.0f) part.setOrientation(angle, ax, ay, az);
            part.addChild(makeBox(u, v, width, height, depth,
                    layoutWidth, layoutHeight, layoutDepth, skin));
            parent.addChild(part);
        }
    }

    private Mesh makeBox(int u, int v, int width, int height, int depth,
            int layoutWidth, int layoutHeight, int layoutDepth, Appearance appearance) {
        short[] positions = new short[24 * 3];
        short[] texCoords = new short[24 * 2];
        float x0 = -width * 0.5f, x1 = width * 0.5f;
        float y0 = -height * 0.5f, y1 = height * 0.5f;
        float z0 = -depth * 0.5f, z1 = depth * 0.5f;
        int[][] faces = new int[][] {
            {3,7,5,1}, {6,2,0,4}, {0,1,5,4},
            {6,7,3,2}, {3,2,0,1}, {6,7,5,4}
        };
        float[][] points = new float[][] {
            {x0,y0,z0},{x1,y0,z0},{x0,y1,z0},{x1,y1,z0},
            {x0,y0,z1},{x1,y0,z1},{x0,y1,z1},{x1,y1,z1}
        };
        int[][] rects = new int[][] {
            {u + layoutDepth + layoutWidth, v + layoutDepth,
                u + layoutDepth + layoutWidth + layoutDepth, v + layoutDepth + layoutHeight},
            {u, v + layoutDepth, u + layoutDepth, v + layoutDepth + layoutHeight},
            {u + layoutDepth, v, u + layoutDepth + layoutWidth, v + layoutDepth},
            {u + layoutDepth + layoutWidth, v, u + layoutDepth + layoutWidth + layoutWidth, v + layoutDepth},
            {u + layoutDepth, v + layoutDepth, u + layoutDepth + layoutWidth, v + layoutDepth + layoutHeight},
            {u + layoutDepth + layoutWidth + layoutDepth, v + layoutDepth,
                u + layoutDepth + layoutWidth + layoutDepth + layoutWidth, v + layoutDepth + layoutHeight}
        };
        int at = 0;
        for (int face = 0; face < 6; face++) {
            int[] r = rects[face];
            int[] order = faces[face];
            int[] us = {r[0], r[2], r[2], r[0]};
            int[] vs = {r[1], r[1], r[3], r[3]};
            for (int q = 0; q < 4; q++) {
                float[] p = points[order[q]];
                positions[at * 3] = (short)(p[0] * 64.0f);
                positions[at * 3 + 1] = (short)(p[1] * 64.0f);
                positions[at * 3 + 2] = (short)(p[2] * 64.0f);
                texCoords[at * 2] = (short)(us[q] * 16);
                texCoords[at * 2 + 1] = (short)(vs[q] * 16);
                at++;
            }
        }
        VertexArray pa = new VertexArray(24, 3, 2);
        pa.set(0, 24, positions);
        VertexArray ta = new VertexArray(24, 2, 2);
        ta.set(0, 24, texCoords);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 1024.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        int[] strips = {4,4,4,4,4,4};
        return new Mesh(vb, new TriangleStripArray(0, strips), appearance);
    }

}
