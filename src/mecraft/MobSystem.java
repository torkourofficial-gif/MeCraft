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

/** Bounded, lazy-loaded Alpha mob models and movement for the 4 MiB target. */
final class MobSystem {
    private static final int PIG = 0;
    private static final int SHEEP = 1;
    private static final int ZOMBIE = 2;
    private static final int CREEPER = 3;
    private static final int SKELETON = 4;
    private static final int COW = 5;
    private static final int CHICKEN = 6;
    private static final int SPIDER = 7;
    private static final int SLIME = 8;
    private static final int GHAST = 9;
    private static final int PIG_ZOMBIE = 10;
    private static final int MOB_COUNT = 14;
    private static final float DESPAWN_DISTANCE_SQUARED = 32.0f * 32.0f;
    private static final int PASSIVE_RESPAWN_INTERVAL = 80;
    private static final int HOSTILE_RESPAWN_INTERVAL = 100;
    private static final int FIRE_FRAME_COUNT = 8;
    private static final int FIRE_FRAME_UV_HEIGHT = 32;
    // Small overlaps at moving joints prevent visible seams between closed cuboids.
    private static final float JOINT_OVERLAP = 0.5f;

    private final Mob[] mobs = new Mob[MOB_COUNT];
    private final Arrow[] arrows = new Arrow[6];
    private final Group scene;
    private final DroppedItemSystem drops;
    private final ParticleSystem particles;
    private final Appearance fireAppearance;
    private final Appearance spiderEyesAppearance;
    private final Texture2D[] mobTextures = new Texture2D[11];
    private final Texture2D[] fleeceTextures = new Texture2D[WoolColors.COUNT];
    private final Random random = new Random(1L);
    private int worldSeed = Integer.MIN_VALUE;
    private int difficulty = VoxelWorld.DIFFICULTY_NORMAL;
    private int tick;
    private int attackCooldown;
    private boolean zombiesNightActive;
    private int nextArrow;

    MobSystem(Group scene, DroppedItemSystem drops, ParticleSystem particles) throws IOException {
        this.scene = scene;
        this.drops = drops;
        this.particles = particles;
        fireAppearance = skin(texture(GameAssets.image("fire.png")));
        spiderEyesAppearance = skin(texture("alpha_spider_eyes.png"));
        spiderEyesAppearance.getMaterial().setColor(Material.EMISSIVE, 0xFFAA2222);
        Texture2D[] skinTextures = new Texture2D[] {
            texture("pig.png"), texture("sheep.png"), texture("zombie.png"),
            texture("creeper.png"), texture("skeleton.png"), texture("cow.png"),
            texture("alpha_chicken.png"), texture("alpha_spider.png"),
            texture("alpha_slime.png"), texture("alpha_ghast.png"),
            texture("alpha_pigzombie.png")
        };
        System.arraycopy(skinTextures, 0, mobTextures, 0, skinTextures.length);
        Image fleeceImage = GameAssets.image("sheep_fur.png");
        fleeceTextures[0] = texture(fleeceImage);
        fleeceTextures[WoolColors.LIGHT_GRAY] =
                texture(recolor(fleeceImage, WoolColors.LIGHT_GRAY));
        fleeceTextures[WoolColors.BLACK] = texture(recolor(fleeceImage, WoolColors.BLACK));
        Appearance arrowAppearance = skin(skinTextures[SKELETON]);
        for (int i = 0; i < arrows.length; i++) {
            arrows[i] = new Arrow(arrowAppearance);
            scene.addChild(arrows[i].root);
        }
    }

    /** Returns damage dealt to the player on this tick. */
    int update(VoxelWorld world) {
        if (worldSeed != world.seed) reset(world);
        tick++;
        unloadDistantMobs(world);
        if (tick % PASSIVE_RESPAWN_INTERVAL == 0) respawnPassiveMob(world);
        updateHostileCycle(world);
        if (attackCooldown > 0) attackCooldown--;
        int playerDamage = 0;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (mob == null) continue;
            if (mob.dying) {
                updateDeath(i, mob);
                continue;
            }
            if (!mob.alive) continue;
            mob.age++;
            if (mob.type == CHICKEN && mob.age > 0 && mob.age % 500 == 0)
                drops.spawn(ItemCatalog.EGG, floor(mob.x), floor(mob.y), floor(mob.z));
            if (updateBurning(mob, world)) continue;
            if (mob.hurtTicks > 0 && --mob.hurtTicks == 0) mob.setHurt(false);
            mob.chasing = false;
            if (mob.attackDelay > 0) mob.attackDelay--;
            if (isHostile(mob.type)) {
                float dx = world.playerX - mob.x;
                float dz = world.playerZ - mob.z;
                float distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < 256.0f) {
                    mob.chasing = true;
                    mob.targetYaw = yawTo(dx, dz);
                    float fuseRange = mob.fuseTicks > 0 ? 7.0f : 3.0f;
                    if (mob.type == CREEPER && distanceSquared < fuseRange * fuseRange) {
                        if (mob.fuseTicks < 30) mob.fuseTicks++;
                        if (mob.fuseTicks >= 30) {
                            world.explode(mob.x, mob.y + 0.7f, mob.z, 3.0f, drops);
                            if (distanceSquared < 9.0f && Math.abs(world.playerY - mob.y) < 3.0f)
                                playerDamage = 10;
                            beginDeath(mob);
                            continue;
                        }
                    } else if (mob.type == CREEPER && mob.fuseTicks > 0) {
                        mob.fuseTicks--;
                    }
                    if (mob.type == SKELETON && distanceSquared > 16.0f && distanceSquared < 100.0f &&
                        mob.attackDelay <= 0) {
                        float distance = (float)Math.sqrt(distanceSquared);
                        if (!blocked(world, mob.x, mob.y + 1.2f, mob.z,
                                dx / distance, 0.0f, dz / distance, distance)) {
                            mob.attackDelay = 30;
                            shootArrow(mob, world);
                        }
                    } else if (distanceSquared < 2.5f * 2.5f &&
                        world.playerY < mob.y + mobHeight(mob) &&
                        world.playerY + 1.8f > mob.y) {
                        if (mob.attackDelay <= 0) {
                            mob.attackDelay = 20;
                            if (mob.type != SLIME || mob.slimeSize > 1)
                                playerDamage = mob.type == SLIME ? mob.slimeSize : 5;
                        }
                    }
                } else {
                    if (mob.type == CREEPER && mob.fuseTicks > 0) mob.fuseTicks--;
                    wander(mob, 90);
                }
            } else {
                wander(mob, 58);
            }
            if (mob.type == CREEPER)
                mob.setFlashWhite(mob.fuseTicks > 0 && (mob.fuseTicks / 2) % 2 == 0);
            animate(mob);
            turn(mob);
            move(mob, world);
            mob.root.setTranslation(mob.x, mob.y, mob.z);
            if (mob.type == CREEPER && mob.fuseTicks > 0) {
                float fuse = mob.fuseTicks / 28.0f;
                float pulse = 1.0f + (float)Math.sin(fuse * 100.0f) * fuse * 0.01f;
                if (fuse < 0.0f) fuse = 0.0f;
                if (fuse > 1.0f) fuse = 1.0f;
                float swell = fuse * fuse;
                swell *= swell;
                mob.root.setScale((1.0f + swell * 0.4f) * pulse,
                        (1.0f + swell * 0.1f) / pulse,
                        (1.0f + swell * 0.4f) * pulse);
            } else if (mob.type == SLIME) {
                mob.root.setScale((float)mob.slimeSize, (float)mob.slimeSize, (float)mob.slimeSize);
            } else mob.root.setScale(1.0f, 1.0f, 1.0f);
            mob.root.setOrientation(modelYaw(mob.yaw), 0.0f, 1.0f, 0.0f);
        }
        playerDamage = updateArrows(world, playerDamage);
        return playerDamage;
    }

    /** Returns 1 for a hit, 0 when the target is still on attack cooldown, or -1 for no target. */
    int hit(VoxelWorld world, float pitch, int damage) {
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
            if (mob == null || !mob.alive) continue;
            float height = mobHeight(mob);
            float width = mobWidth(mob);
            float along = rayBox(originX, originY, originZ, dirX, dirY, dirZ,
                    mob.x - width * 0.5f, mob.y, mob.z - width * 0.5f,
                    mob.x + width * 0.5f, mob.y + height, mob.z + width * 0.5f);
            if (along >= 0.0f && along <= closestDistance) {
                closest = i;
                closestDistance = along;
            }
        }
        if (closest < 0) return -1;
        if (blocked(world, originX, originY, originZ, dirX, dirY, dirZ, closestDistance)) return -1;
        if (attackCooldown > 0) return 0;
        attackCooldown = 8;
        Mob target = mobs[closest];
        if (target.type == SHEEP && !target.sheared) {
            target.setSheared(true);
            int woolCount = 1 + random.nextInt(3);
            for (int i = 0; i < woolCount; i++) {
                drops.spawn(ItemCatalog.woolItem(target.woolColor),
                        floor(target.x), floor(target.y), floor(target.z));
            }
        }
        target.health -= damage;
        if (target.health <= 0) {
            dropForDeath(target);
            beginDeath(target);
        } else {
            target.hurtTicks = 10;
            target.setHurt(true);
            float knockX = world.playerX - target.x;
            float knockZ = world.playerZ - target.z;
            float knockLength = (float)Math.sqrt(knockX * knockX + knockZ * knockZ);
            if (knockLength > 0.0001f) {
                target.motionX -= knockX / knockLength * 0.4f;
                target.motionZ -= knockZ / knockLength * 0.4f;
            }
            target.verticalVelocity = 0.4f;
            target.fleeTicks = 35;
            target.targetYaw = yawTo(target.x - world.playerX, target.z - world.playerZ);
        }
        return 1;
    }

    void damageFromExplosion(VoxelWorld world, float x, float y, float z, float radius) {
        float reach = radius * 2.0f;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (mob == null || !mob.alive) continue;
            float dx = mob.x - x, dy = mob.y + 0.5f - y, dz = mob.z - z;
            float distance = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
            float impact = 1.0f - distance / reach;
            if (impact <= 0.0f) continue;
            int damage = (int)((impact * impact + impact) * 32.0f + 1.0f);
            mob.health -= damage;
            if (mob.health <= 0) {
                dropForDeath(mob);
                beginDeath(mob);
            } else {
                mob.hurtTicks = 10;
                mob.setHurt(true);
                if (distance > 0.0001f) {
                    mob.motionX += dx / distance * impact;
                    mob.motionZ += dz / distance * impact;
                    mob.verticalVelocity += dy / distance * impact;
                }
                mob.fleeTicks = 35;
            }
        }
    }

    private boolean updateBurning(Mob mob, VoxelWorld world) {
        if (mob.type != ZOMBIE && mob.type != SKELETON) return false;
        if (world.isNight()) {
            mob.fireTicks = 0;
            mob.fireMesh.setRenderingEnable(false);
            return false;
        }
        if (mob.fireTicks <= 0 && hasSkyExposure(world, mob)) {
            mob.fireTicks = 300;
            mob.burnDamageTicks = 20;
        }
        if (mob.fireTicks <= 0) {
            mob.fireMesh.setRenderingEnable(false);
            return false;
        }
        mob.fireTicks--;
        mob.fireMesh.setRenderingEnable(true);
        mob.updateFireFrame((tick >> 2) % FIRE_FRAME_COUNT);
        if (--mob.burnDamageTicks <= 0) {
            mob.burnDamageTicks = 20;
            mob.health--;
            mob.hurtTicks = 10;
            mob.setHurt(true);
            if (mob.health <= 0) {
                dropForDeath(mob);
                beginDeath(mob);
                return true;
            }
        }
        if (mob.fireTicks == 0) mob.fireMesh.setRenderingEnable(false);
        return false;
    }

    private boolean hasSkyExposure(VoxelWorld world, Mob mob) {
        int x = floor(mob.x), z = floor(mob.z);
        for (int y = floor(mob.y + 1.0f); y < VoxelWorld.HEIGHT; y++) {
            if (world.isSolidAt(x, y, z)) return false;
        }
        return true;
    }

    private void dropForDeath(Mob mob) {
        int bx = floor(mob.x), by = floor(mob.y), bz = floor(mob.z);
        if (mob.type == PIG) {
            int meat = 1 + random.nextInt(2);
            int pork = mob.fireTicks > 0 ? ItemCatalog.PORK_COOKED : ItemCatalog.PORK_RAW;
            for (int i = 0; i < meat; i++) drops.spawn(pork, bx, by, bz);
        }
        int drop = mob.type == COW ? ItemCatalog.LEATHER :
                   (mob.type == SPIDER ? ItemCatalog.STRING :
                   (mob.type == SLIME && mob.slimeSize == 1 ? ItemCatalog.SLIMEBALL :
                   (mob.type == GHAST || mob.type == CREEPER ? ItemCatalog.GUNPOWDER :
                   (mob.type == SKELETON ? ItemCatalog.ARROW :
                   (mob.type == PIG_ZOMBIE ? ItemCatalog.GOLD_INGOT :
                   (mob.type == ZOMBIE || mob.type == CHICKEN ? ItemCatalog.FEATHER : 0))))));
        int count = drop == 0 ? 0 : 1 + random.nextInt(2);
        for (int i = 0; i < count; i++) drops.spawn(drop, bx, by, bz);
    }

    private void beginDeath(Mob mob) {
        if (mob.dying) return;
        mob.alive = false;
        mob.dying = true;
        mob.deathTicks = 0;
        mob.hurtTicks = 0;
        mob.fireTicks = 0;
        mob.fireMesh.setRenderingEnable(false);
        mob.setHurt(true);
        mob.root.setRenderingEnable(true);
    }

    private void updateDeath(int slot, Mob mob) {
        mob.deathTicks++;
        float progress = mob.deathTicks / 20.0f;
        if (progress > 1.0f) progress = 1.0f;
        float roll = (float)Math.sqrt(progress) * 90.0f;
        mob.pose.setOrientation(roll, 0.0f, 0.0f, 1.0f);
        if (mob.deathTicks >= 20) {
            particles.spawnMobDeath(mob.x, mob.y, mob.z);
            releaseMob(slot);
        }
    }

    private void reset(VoxelWorld world) {
        worldSeed = world.seed;
        difficulty = world.difficulty;
        tick = 0;
        attackCooldown = 0;
        zombiesNightActive = false;
        for (int i = 0; i < arrows.length; i++) arrows[i].active = false;
        for (int i = 0; i < mobs.length; i++) releaseMob(i);
        random.setSeed(world.seed ^ 0x51ED270B);
        for (int i = 0; i < mobs.length; i++) {
            if (isHostile(mobTypeForSlot(i))) continue;
            spawnPassive(world, createMob(i));
        }
    }

    private int mobTypeForSlot(int slot) {
        // Hard mode reuses three passive slots for extra hostile entities,
        // increasing the active hostile cap without allocating a larger mob pool.
        if (difficulty == VoxelWorld.DIFFICULTY_HARD) {
            if (slot == 4) return ZOMBIE;
            if (slot == 5) return SKELETON;
            if (slot == 6) return CREEPER;
        }
        if (slot == 0 || slot == 4) return PIG;
        if (slot == 1 || slot == 5) return SHEEP;
        if (slot == 2 || slot == 6) return COW;
        if (slot == 3) return CHICKEN;
        if (slot == 7) return ZOMBIE;
        if (slot == 8) return SKELETON;
        if (slot == 9) return CREEPER;
        if (slot == 10) return SPIDER;
        if (slot == 11) return SLIME;
        if (slot == 12) return GHAST;
        return PIG_ZOMBIE;
    }

    private Mob createMob(int slot) {
        Mob mob = mobs[slot];
        if (mob != null) return mob;
        int type = mobTypeForSlot(slot);
        int woolColor = type == SHEEP ? naturalSheepColor() : 0;
        Appearance fleece = type == SHEEP ? skin(fleeceTextures[woolColor]) : null;
        mob = new Mob(type, skin(mobTextures[type]), fleece, fireAppearance,
                      type == SPIDER ? spiderEyesAppearance : null);
        mob.woolColor = woolColor;
        mobs[slot] = mob;
        scene.addChild(mob.root);
        return mob;
    }

    private int naturalSheepColor() {
        int roll = random.nextInt(100);
        if (roll < 5) return WoolColors.BLACK;
        if (roll < 10) return WoolColors.LIGHT_GRAY;
        return 0;
    }

    private Image recolor(Image source, int color) {
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getRGB(pixels, 0, width, 0, 0, width, height);
        WoolColors.recolor(pixels, color);
        return Image.createRGBImage(pixels, width, height, true);
    }

    private void releaseMob(int slot) {
        Mob mob = mobs[slot];
        if (mob == null) return;
        mob.fireMesh.setRenderingEnable(false);
        mob.root.setRenderingEnable(false);
        scene.removeChild(mob.root);
        mobs[slot] = null;
    }

    /** Remove distant actors from the active world and clear their transient state. */
    private void unloadDistantMobs(VoxelWorld world) {
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (mob == null || !mob.alive || mob.dying) continue;
            float dx = mob.x - world.playerX;
            float dy = mob.y - world.playerY;
            float dz = mob.z - world.playerZ;
            if (dx * dx + dy * dy + dz * dz <= DESPAWN_DISTANCE_SQUARED) continue;
            releaseMob(i);
        }
    }

    /** Keep the local passive population full by recycling unloaded or dead slots. */
    private void respawnPassiveMob(VoxelWorld world) {
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (isHostile(mobTypeForSlot(i)) || (mob != null && (mob.alive || mob.dying))) continue;
            spawnPassive(world, createMob(i));
            return;
        }
    }

    private void spawnPassive(VoxelWorld world, Mob mob) {
        int x = (int)world.playerX, z = (int)world.playerZ;
        int attempts = 0;
        do {
            int radius = 4 + random.nextInt(16);
            int angle = random.nextInt(360);
            x = (int)world.playerX + (int)(Math.cos(angle * Math.PI / 180.0) * radius);
            z = (int)world.playerZ + (int)(Math.sin(angle * Math.PI / 180.0) * radius);
            attempts++;
        } while ((!validSpawn(world, x, z) || tooClose(mob, x + 0.5f, z + 0.5f)) && attempts < 48);
        mob.x = x + 0.5f;
        mob.z = z + 0.5f;
        mob.y = world.surfaceY(x, z) + 1.0f;
        mob.yaw = random.nextInt(360);
        mob.targetYaw = mob.yaw;
        mob.age = random.nextInt(100);
        mob.health = 10;
        mob.deathTicks = mob.fireTicks = mob.burnDamageTicks = 0;
        mob.attackDelay = mob.fuseTicks = mob.fleeTicks = mob.hurtTicks = 0;
        mob.doorBreakTicks = 0; mob.doorBreakY = -1;
        mob.verticalVelocity = mob.motionX = mob.motionZ = 0.0f;
        mob.walkPhase = mob.walkAmount = 0.0f;
        mob.walkTicks = 0;
        mob.chasing = false;
        mob.dying = false;
        mob.nextWander = tick + 20 + random.nextInt(100);
        mob.fireMesh.setRenderingEnable(false);
        mob.pose.setOrientation(0.0f, 0.0f, 0.0f, 1.0f);
        mob.setHurt(false);
        mob.setFlashWhite(false);
        mob.setSheared(false);
        mob.root.setTranslation(mob.x, mob.y, mob.z);
        mob.root.setScale(1.0f, 1.0f, 1.0f);
        mob.root.setOrientation(modelYaw(mob.yaw), 0.0f, 1.0f, 0.0f);
        mob.alive = true;
        mob.root.setRenderingEnable(true);
    }

    /** Zombies/skeletons burn under open daylight; creepers remain night-only. */
    private void updateHostileCycle(VoxelWorld world) {
        if (world.difficulty == VoxelWorld.DIFFICULTY_PEACEFUL) {
            for (int i = 0; i < mobs.length; i++)
                if (isHostile(mobTypeForSlot(i)) && mobs[i] != null) releaseMob(i);
            zombiesNightActive = false;
            return;
        }
        boolean night = world.nether || world.isNight();
        for (int i = 0; i < mobs.length; i++) {
            if (isHostile(mobTypeForSlot(i)) && !hostileAllowed(mobTypeForSlot(i), world) &&
                    mobs[i] != null) releaseMob(i);
        }
        if (!night) {
            for (int i = 0; i < mobs.length; i++) {
                Mob mob = mobs[i];
                if (!isHostile(mobTypeForSlot(i))) continue;
                if (!hostileAllowed(mobTypeForSlot(i), world) || !hostileSlotAllowed(i, world)) {
                    if (mob != null) releaseMob(i);
                    continue;
                }
                if (mob == null || mob.dying) continue;
                if (mob.type == CREEPER) {
                    releaseMob(i);
                } else if (mob.alive && mob.fireTicks == 0 && hasSkyExposure(world, mob)) {
                    mob.fireTicks = 300;
                    mob.burnDamageTicks = 20;
                }
            }
            zombiesNightActive = false;
            return;
        }
        if (zombiesNightActive) {
            int respawnInterval = world.difficulty == VoxelWorld.DIFFICULTY_HARD ? 60 :
                    (world.difficulty == VoxelWorld.DIFFICULTY_EASY ? 160 : HOSTILE_RESPAWN_INTERVAL);
            if (tick % respawnInterval == 0) {
                for (int i = 0; i < mobs.length; i++) {
                    Mob mob = mobs[i];
                    if (isHostile(mobTypeForSlot(i)) && hostileAllowed(mobTypeForSlot(i), world) && hostileSlotAllowed(i, world) &&
                        (mob == null || (!mob.alive && !mob.dying))) {
                        spawnZombie(world, createMob(i));
                        break;
                    }
                }
            }
            return;
        }
        zombiesNightActive = true;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!isHostile(mobTypeForSlot(i)) || !hostileAllowed(mobTypeForSlot(i), world) || !hostileSlotAllowed(i, world)) continue;
            if (mob == null) mob = createMob(i);
            if (mob.dying) continue;
            if (mob.alive) {
                mob.fireTicks = 0;
                mob.fireMesh.setRenderingEnable(false);
            } else spawnZombie(world, mob);
        }
    }

    private void spawnZombie(VoxelWorld world, Mob mob) {
        mob.alive = false;
        mob.root.setRenderingEnable(false);
        int attempts = 0;
        int x = (int)world.playerX;
        int z = (int)world.playerZ;
        do {
            int radius = 9 + random.nextInt(10);
            int angle = random.nextInt(360);
            x = (int)world.playerX + (int)(Math.cos(angle * Math.PI / 180.0) * radius);
            z = (int)world.playerZ + (int)(Math.sin(angle * Math.PI / 180.0) * radius);
            attempts++;
        } while ((!validSpawn(world, x, z) || tooClose(mob, x + 0.5f, z + 0.5f)) && attempts < 48);
        int ground = world.surfaceY(x, z);
        mob.x = x + 0.5f;
        mob.z = z + 0.5f;
        mob.y = ground + 1.0f;
        mob.yaw = random.nextInt(360);
        mob.targetYaw = mob.yaw;
        mob.age = random.nextInt(100);
        mob.nextWander = tick + 20 + random.nextInt(100);
        mob.health = 20;
        mob.slimeSize = mob.type == SLIME ? 1 << random.nextInt(3) : 1;
        if (mob.type == SLIME) mob.health = mob.slimeSize * mob.slimeSize;
        mob.hurtTicks = 0;
        mob.dying = false;
        mob.deathTicks = 0;
        mob.fireTicks = 0;
        mob.fireMesh.setRenderingEnable(false);
        mob.pose.setOrientation(0.0f, 0.0f, 0.0f, 1.0f);
        mob.verticalVelocity = 0.0f;
        mob.motionX = mob.motionZ = 0.0f;
        mob.setHurt(false);
        mob.setFlashWhite(false);
        mob.setSheared(false);
        mob.attackDelay = 0;
        mob.fuseTicks = 0;
        mob.fleeTicks = 0;
        mob.walkTicks = 0;
        mob.chasing = false;
        mob.alive = true;
        mob.root.setTranslation(mob.x, mob.y, mob.z);
        mob.root.setScale((float)mob.slimeSize, (float)mob.slimeSize, (float)mob.slimeSize);
        mob.root.setOrientation(modelYaw(mob.yaw), 0.0f, 1.0f, 0.0f);
        mob.root.setRenderingEnable(true);
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
            if (other == null || other == mob || !other.alive || other.y <= 0.0f) continue;
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
        boolean airborne = mob.verticalVelocity != 0.0f ||
                !Block.solid(world.get(floor(mob.x), floor(mob.y) - 1, floor(mob.z)));
        float speed = isHostile(mob.type) ? (mob.type == CREEPER ? 0.018f : 0.026f) : 0.014f;
        if (world.difficulty == VoxelWorld.DIFFICULTY_HARD && isHostile(mob.type)) speed *= 1.15f;
        if (!isHostile(mob.type) && mob.fleeTicks > 0) speed *= 1.6f;
        if (!walking) speed = 0.0f;
        float oldX = mob.x;
        float oldZ = mob.z;
        float radians = mob.yaw * (float)Math.PI / 180.0f;
        float nx = mob.x + (float)Math.sin(radians) * speed + mob.motionX;
        float nz = mob.z + (float)Math.cos(radians) * speed + mob.motionZ;
        if (!canMove(world, mob, nx, mob.z)) breakDoorAhead(mob, world, nx, mob.z);
        if (canMove(world, mob, nx, mob.z)) mob.x = nx;
        else {
            if (!airborne && mob.doorBreakTicks == 0) mob.targetYaw += 75.0f;
            mob.motionX = 0.0f;
        }
        if (!canMove(world, mob, mob.x, nz)) breakDoorAhead(mob, world, mob.x, nz);
        if (canMove(world, mob, mob.x, nz)) mob.z = nz;
        else {
            if (!airborne && mob.doorBreakTicks == 0) mob.targetYaw -= 140.0f;
            mob.motionZ = 0.0f;
        }
        mob.motionX *= 0.91f;
        mob.motionZ *= 0.91f;
        float movedX = mob.x - oldX;
        float movedZ = mob.z - oldZ;
        float distance = (float)Math.sqrt(movedX * movedX + movedZ * movedZ);
        // Advance the gait by distance, so slower entities animate more slowly
        // and a collision or stop also brings the limbs to rest.
        mob.walkPhase += distance * 8.0f;
        if (mob.walkPhase >= 6.2831855f) mob.walkPhase %= 6.2831855f;
        if (distance > 0.0001f && speed > 0.0f) {
            mob.walkAmount = distance / speed;
            if (mob.walkAmount > 1.0f) mob.walkAmount = 1.0f;
        } else {
            mob.walkAmount = 0.0f;
        }
        float ground = solidGround(world, floor(mob.x), floor(mob.z)) + 1.0f;
        if (mob.verticalVelocity != 0.0f || mob.y > ground) {
            mob.y += mob.verticalVelocity;
            mob.verticalVelocity *= 0.98f;
            mob.verticalVelocity -= 0.08f;
            if (mob.y <= ground) {
                mob.y = ground;
                mob.verticalVelocity = 0.0f;
            }
        } else {
            mob.y = ground;
        }
    }

    /** Hard-mode zombies batter a wooden door until it gives way. */
    private void breakDoorAhead(Mob mob, VoxelWorld world, float x, float z) {
        if (world.difficulty != VoxelWorld.DIFFICULTY_HARD || mob.type != ZOMBIE) {
            mob.doorBreakTicks = 0;
            return;
        }
        int bx = floor(x), bz = floor(z), by = floor(mob.y);
        int doorY = world.get(bx, by, bz) == Block.DOOR ? by :
                (world.get(bx, by + 1, bz) == Block.DOOR ? by + 1 : -1);
        if (doorY < 0) {
            mob.doorBreakTicks = 0;
            return;
        }
        if (mob.doorBreakX != bx || mob.doorBreakY != doorY || mob.doorBreakZ != bz) {
            mob.doorBreakX = bx; mob.doorBreakY = doorY; mob.doorBreakZ = bz;
            mob.doorBreakTicks = 0;
        }
        mob.targetYaw = yawTo(bx + 0.5f - mob.x, bz + 0.5f - mob.z);
        mob.doorBreakTicks++;
        if (mob.doorBreakTicks >= 60) {
            world.removeDoor(bx, doorY, bz);
            mob.doorBreakTicks = 0;
            mob.doorBreakY = -1;
        }
    }

    private boolean canMove(VoxelWorld world, Mob mob, float x, float z) {
        int bx = (int)x, bz = (int)z;
        int feet = (int)mob.y;
        if (world.isSolidAt(bx, feet, bz) || world.isSolidAt(bx, feet + 1, bz)) return false;
        return true;
    }

    private int solidGround(VoxelWorld world, int x, int z) {
        int y = world.surfaceY(x, z);
        while (y > 0 && !Block.solid(world.get(x, y, z))) y--;
        return y;
    }

    private void animate(Mob mob) {
        float phase = mob.walkPhase;
        float walk = mob.walkAmount;
        if (mob.walkTicks > 0) mob.walkTicks--;
        if (mob.type == SPIDER) {
            for (int i = 0; i < mob.spiderLegs.length; i++) {
                float side = (i & 1) == 0 ? 1.0f : -1.0f;
                float phaseOffset = (i >> 1) * 0.5f * (float)Math.PI;
                float swing = (float)Math.cos(phase + phaseOffset) * 24.0f * walk;
                mob.spiderLegs[i].setOrientation(side * swing, 0, 1, 0);
            }
            return;
        }
        if (mob.type == GHAST) {
            for (int i = 0; i < mob.tentacles.length; i++) {
                float angle = (0.2f * (float)Math.sin(mob.age * 0.3f + i) + 0.4f) * 57.29578f;
                mob.tentacles[i].setOrientation(angle, 1, 0, 0);
            }
            return;
        }
        if (mob.type == SLIME) return;
        if (mob.type == CHICKEN) {
            float stride = (float)Math.cos(phase) * 80.214f * walk;
            mob.legs[0].setOrientation(-stride, 1, 0, 0);
            mob.legs[1].setOrientation(stride, 1, 0, 0);
            float flap = (float)Math.sin(mob.age * 0.30f) * 24.0f;
            mob.arms[0].setOrientation(flap, 0, 0, 1);
            mob.arms[1].setOrientation(-flap, 0, 0, 1);
            return;
        }
        if (mob.type == ZOMBIE || mob.type == SKELETON || mob.type == PIG_ZOMBIE) {
            // ModelBiped.animate() uses a 1.4 radian stride (about 80 degrees).
            // The original renderer flips model Y, so its X-axis pose rotations
            // invert when represented by these M3G groups.
            mob.legs[0].setOrientation(-(float)Math.cos(phase) * 80.214f * walk, 1, 0, 0);
            mob.legs[1].setOrientation(-(float)Math.cos(phase + Math.PI) * 80.214f * walk, 1, 0, 0);
            float armPitch = (float)Math.sin(phase) * 0.05f * 57.29578f * walk;
            float armRoll = (float)Math.cos(phase) * 0.05f * 57.29578f * walk;
            mob.armsPitch[0].setOrientation(90.0f - armPitch, 1, 0, 0);
            mob.armsPitch[1].setOrientation(90.0f + armPitch, 1, 0, 0);
            mob.arms[0].setOrientation(0.1f * 57.29578f, 0, 1, 0);
            mob.arms[1].setOrientation(-0.1f * 57.29578f, 0, 1, 0);
            mob.armsRoll[0].setOrientation(armRoll, 0, 0, 1);
            mob.armsRoll[1].setOrientation(-armRoll, 0, 0, 1);
        } else {
            // ModelQuadruped.animate() also uses a 1.4 radian stride.
            float stride = (float)Math.cos(phase) * 80.214f * walk;
            // Preserve the original diagonal gait after the renderer's vertical flip.
            mob.legs[0].setOrientation(-stride, 1, 0, 0);
            mob.legs[1].setOrientation(stride, 1, 0, 0);
            if (mob.type == CHICKEN) return;
            mob.legs[2].setOrientation(stride, 1, 0, 0);
            mob.legs[3].setOrientation(-stride, 1, 0, 0);
        }
    }

    private Texture2D texture(String resource) throws IOException {
        Image image = GameAssets.image(resource);
        return texture(image);
    }

    private Texture2D texture(Image image) {
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        return texture;
    }

    private boolean isHostile(int type) {
        return type == ZOMBIE || type == CREEPER || type == SKELETON || type == SPIDER ||
               type == SLIME || type == GHAST || type == PIG_ZOMBIE;
    }

    /** Alpha entity dimensions, kept separate from the 16-pixel block scale. */
    private float mobHeight(Mob mob) {
        if (mob.type == SPIDER) return 0.9f;
        if (mob.type == GHAST) return 4.0f;
        if (mob.type == SLIME) return 0.6f * mob.slimeSize;
        if (mob.type == CHICKEN) return 0.7f;
        if (mob.type == PIG) return 0.9f;
        if (mob.type == COW || mob.type == SHEEP) return 1.3f;
        return 1.8f;
    }

    private float mobWidth(Mob mob) {
        if (mob.type == SPIDER) return 1.4f;
        if (mob.type == GHAST) return 4.0f;
        if (mob.type == SLIME) return 0.6f * mob.slimeSize;
        if (mob.type == CHICKEN) return 0.3f;
        if (mob.type == PIG || mob.type == COW || mob.type == SHEEP) return 0.9f;
        return 0.6f;
    }

    /** Easy mode limits the active hostile pool to three entities. */
    private boolean hostileSlotAllowed(int slot, VoxelWorld world) {
        return world.difficulty != VoxelWorld.DIFFICULTY_EASY || slot <= 9;
    }

    private boolean hostileAllowed(int type, VoxelWorld world) {
        if (world.difficulty == VoxelWorld.DIFFICULTY_PEACEFUL) return false;
        if (type == GHAST || type == PIG_ZOMBIE) return world.nether;
        return !world.nether;
    }

    private void shootArrow(Mob shooter, VoxelWorld world) {
        Arrow arrow = arrows[nextArrow++ % arrows.length];
        float dx = world.playerX - shooter.x;
        float dz = world.playerZ - shooter.z;
        float horizontal = (float)Math.sqrt(dx * dx + dz * dz);
        arrow.y = shooter.y + 1.0f;
        float dy = world.playerY - 0.2f - arrow.y + horizontal * 0.2f;
        float length = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.001f) return;
        float speed = 0.6f;
        arrow.x = shooter.x;
        arrow.z = shooter.z;
        arrow.vx = dx / length * speed;
        arrow.vy = dy / length * speed;
        arrow.vz = dz / length * speed;
        arrow.life = 0;
        arrow.active = true;
        arrow.playerOwned = false;
        arrow.root.setTranslation(arrow.x, arrow.y, arrow.z);
        arrow.root.setOrientation(yawTo(dx, dz), 0.0f, 1.0f, 0.0f);
        float pitch = dy * 90.0f / (horizontal + (dy < 0.0f ? -dy : dy) + 0.0001f);
        arrow.pitch.setOrientation(-pitch, 1.0f, 0.0f, 0.0f);
        arrow.root.setRenderingEnable(true);
    }

    boolean shootPlayerArrow(VoxelWorld world, float pitchDegrees) {
        Arrow arrow = arrows[nextArrow++ % arrows.length];
        float yaw = world.yaw * (float)Math.PI / 180.0f;
        float pitch = pitchDegrees * (float)Math.PI / 180.0f;
        float horizontal = (float)Math.cos(pitch);
        float dx = (float)Math.sin(yaw) * horizontal;
        float dy = (float)Math.sin(pitch);
        float dz = (float)Math.cos(yaw) * horizontal;
        arrow.x = world.playerX + dx * 0.5f;
        arrow.y = world.playerY + 1.45f;
        arrow.z = world.playerZ + dz * 0.5f;
        arrow.vx = dx * 1.0f;
        arrow.vy = dy * 1.0f;
        arrow.vz = dz * 1.0f;
        arrow.life = 0;
        arrow.active = true;
        arrow.playerOwned = true;
        arrow.root.setTranslation(arrow.x, arrow.y, arrow.z);
        arrow.root.setOrientation(world.yaw, 0.0f, 1.0f, 0.0f);
        arrow.pitch.setOrientation(-pitchDegrees, 1.0f, 0.0f, 0.0f);
        arrow.root.setRenderingEnable(true);
        return true;
    }

    private int updateArrows(VoxelWorld world, int damage) {
        float targetY = world.playerY + 1.0f;
        for (int i = 0; i < arrows.length; i++) {
            Arrow arrow = arrows[i];
            if (!arrow.active) continue;
            arrow.x += arrow.vx;
            arrow.y += arrow.vy;
            arrow.z += arrow.vz;
            arrow.vy -= 0.025f;
            arrow.life++;
            arrow.root.setTranslation(arrow.x, arrow.y, arrow.z);
            int bx = floor(arrow.x), by = floor(arrow.y), bz = floor(arrow.z);
            boolean hitMob = false;
            if (arrow.playerOwned) {
                for (int m = 0; m < mobs.length; m++) {
                    Mob target = mobs[m];
                    if (target == null || !target.alive || target.dying) continue;
                    float dx = target.x - arrow.x, dy = target.y + 0.5f - arrow.y,
                          dz = target.z - arrow.z;
                    float radius = target.type == SPIDER ? 0.9f : 0.55f;
                    if (dx * dx + dy * dy + dz * dz >= radius * radius) continue;
                    target.health -= 4;
                    if (target.health <= 0) {
                        dropForDeath(target);
                        beginDeath(target);
                    } else {
                        target.hurtTicks = 10;
                        target.setHurt(true);
                        target.fleeTicks = 35;
                    }
                    hitMob = true;
                    break;
                }
            }
            if (hitMob) {
                arrow.active = false;
                arrow.root.setRenderingEnable(false);
                continue;
            }
            float dx = arrow.x - world.playerX;
            float dy = arrow.y - targetY;
            float dz = arrow.z - world.playerZ;
            if (!arrow.playerOwned && dx * dx + dy * dy + dz * dz < 0.30f * 0.30f) {
                if (damage < 2) damage = 2;
                arrow.active = false;
            } else if (world.isSolidAt(bx, by, bz) || arrow.life > 70 || by < 0) {
                if (arrow.playerOwned && world.isSolidAt(bx, by, bz))
                    drops.spawn(ItemCatalog.ARROW, bx, by, bz);
                arrow.active = false;
            }
            if (!arrow.active) arrow.root.setRenderingEnable(false);
        }
        return damage;
    }

    private final class Arrow {
        final Group root = new Group();
        final Group pitch = new Group();
        boolean active;
        boolean playerOwned;
        int life;
        float x, y, z, vx, vy, vz;

        Arrow(Appearance appearance) {
            root.addChild(pitch);
            pitch.addChild(makeBox(0, 0, 1, 1, 8, 1, 1, 8, appearance, false));
            root.setRenderingEnable(false);
        }
    }

    private Appearance skin(Texture2D texture) {
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

    private Mesh makeFireMesh(Appearance appearance, VertexArray texCoords, short[] uv) {
        short[] positions = new short[] {
            -22,0,0, 22,0,0, -22,120,0, 22,120,0,
            0,0,-22, 0,0,22, 0,120,-22, 0,120,22
        };
        VertexArray positionArray = new VertexArray(8, 3, 2);
        positionArray.set(0, 8, positions);
        setFireUV(uv, 0, 1024, FIRE_FRAME_UV_HEIGHT); setFireUV(uv, 2, 0, FIRE_FRAME_UV_HEIGHT);
        setFireUV(uv, 4, 1024, 0);   setFireUV(uv, 6, 0, 0);
        setFireUV(uv, 8, 1024, FIRE_FRAME_UV_HEIGHT); setFireUV(uv, 10, 0, FIRE_FRAME_UV_HEIGHT);
        setFireUV(uv, 12, 1024, 0);  setFireUV(uv, 14, 0, 0);
        texCoords.set(0, 8, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positionArray, 1.0f / 64.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 1024.0f, null);
        int[] strips = {4, 4};
        return new Mesh(vertices, new TriangleStripArray(0, strips), appearance);
    }

    private void setFireUV(short[] uv, int at, int u, int v) {
        uv[at] = (short)u;
        uv[at + 1] = (short)v;
    }

    private boolean blocked(VoxelWorld world, float ox, float oy, float oz,
            float dx, float dy, float dz, float distance) {
        int steps = (int)(distance * 8.0f);
        for (int i = 1; i < steps; i++) {
            float t = i / 8.0f;
            if (world.isSolidAt(floor(ox + dx * t), floor(oy + dy * t),
                                floor(oz + dz * t))) return true;
        }
        return false;
    }

    /** Returns the first ray/AABB intersection, matching the mobs' Alpha hit boxes. */
    private float rayBox(float ox, float oy, float oz, float dx, float dy, float dz,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float near = 0.0f;
        float far = 4.0f;
        if (dx > -0.00001f && dx < 0.00001f) {
            if (ox < minX || ox > maxX) return -1.0f;
        } else {
            float a = (minX - ox) / dx, b = (maxX - ox) / dx;
            if (a > b) { float swap = a; a = b; b = swap; }
            if (a > near) near = a;
            if (b < far) far = b;
            if (near > far) return -1.0f;
        }
        if (dy > -0.00001f && dy < 0.00001f) {
            if (oy < minY || oy > maxY) return -1.0f;
        } else {
            float a = (minY - oy) / dy, b = (maxY - oy) / dy;
            if (a > b) { float swap = a; a = b; b = swap; }
            if (a > near) near = a;
            if (b < far) far = b;
            if (near > far) return -1.0f;
        }
        if (dz > -0.00001f && dz < 0.00001f) {
            if (oz < minZ || oz > maxZ) return -1.0f;
        } else {
            float a = (minZ - oz) / dz, b = (maxZ - oz) / dz;
            if (a > b) { float swap = a; a = b; b = swap; }
            if (a > near) near = a;
            if (b < far) far = b;
            if (near > far) return -1.0f;
        }
        return near;
    }

    private int floor(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    private int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** Model heads face -Z while mob movement yaw describes its travel vector. */
    private float modelYaw(float movementYaw) {
        float angle = movementYaw + 180.0f;
        return angle >= 360.0f ? angle - 360.0f : angle;
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
        final Group pose = new Group();
        final Group head = new Group();
        final Group[] legs = new Group[4];
        final Group[] arms = new Group[2];
        final Group[] armsPitch = new Group[2];
        final Group[] armsRoll = new Group[2];
        final Group[] spiderLegs = new Group[8];
        final Group[] tentacles = new Group[9];
        final Group[] fleece = new Group[6];
        private final Appearance skin;
        private final Material skinMaterial;
        private final Material furMaterial;
        final Mesh fireMesh;
        private final VertexArray fireTexArray;
        private final short[] fireTexCoords = new short[16];
        private int fireFrame = -1;
        int health;
        int slimeSize = 1;
        int age;
        int deathTicks;
        int fireTicks;
        int burnDamageTicks;
        int attackDelay;
        int fuseTicks;
        int fleeTicks;
        int doorBreakTicks;
        int doorBreakX, doorBreakY, doorBreakZ;
        int nextWander;
        boolean alive;
        boolean dying;
        boolean chasing;
        boolean sheared;
        int woolColor;
        int walkTicks;
        int hurtTicks;
        float verticalVelocity;
        float motionX, motionZ;
        float walkPhase, walkAmount;
        float x, y, z, yaw, targetYaw;

        Mob(int type, Appearance appearance, Appearance fur, Appearance fire, Appearance spiderEyes) {
            this.type = type;
            this.skin = appearance;
            this.skinMaterial = appearance.getMaterial();
            this.furMaterial = fur == null ? null : fur.getMaterial();
            root.addChild(pose);
            if (type == ZOMBIE || type == PIG_ZOMBIE) buildZombie(appearance, true);
            else if (type == SKELETON) buildZombie(appearance, false);
            else if (type == CREEPER) buildCreeper(appearance);
            else if (type == COW) buildCow(appearance);
            else if (type == SPIDER) buildSpider(appearance, spiderEyes);
            else if (type == SLIME) buildSlime(appearance);
            else if (type == GHAST) buildGhast(appearance);
            else if (type == CHICKEN) buildChicken(appearance);
            else buildQuadruped(appearance, type == PIG, fur);
            fireTexArray = new VertexArray(8, 2, 2);
            fireMesh = makeFireMesh(fire, fireTexArray, fireTexCoords);
            root.addChild(fireMesh);
            fireMesh.setRenderingEnable(false);
        }

        void updateFireFrame(int frame) {
            if (frame == fireFrame) return;
            fireFrame = frame;
            int v0 = frame * FIRE_FRAME_UV_HEIGHT;
            int v1 = v0 + FIRE_FRAME_UV_HEIGHT;
            short[] uv = fireTexCoords;
            setFireUV(uv, 0, 1024, v1); setFireUV(uv, 2, 0, v1);
            setFireUV(uv, 4, 1024, v0); setFireUV(uv, 6, 0, v0);
            setFireUV(uv, 8, 1024, v1); setFireUV(uv, 10, 0, v1);
            setFireUV(uv, 12, 1024, v0); setFireUV(uv, 14, 0, v0);
            fireTexArray.set(0, 8, uv);
        }

        void setHurt(boolean hurt) {
            int tint = hurt ? 0xFFFF5555 : 0xFFFFFFFF;
            skinMaterial.setColor(Material.AMBIENT, tint);
            skinMaterial.setColor(Material.DIFFUSE, tint);
            if (furMaterial != null) {
                furMaterial.setColor(Material.AMBIENT, tint);
                furMaterial.setColor(Material.DIFFUSE, tint);
            }
        }

        /** Alpha's creeper fuse flash is a bright white overlay, not a red hurt tint. */
        void setFlashWhite(boolean flashing) {
            int emissive = flashing ? 0xFFFFFFFF : 0x00000000;
            skinMaterial.setColor(Material.EMISSIVE, emissive);
            if (furMaterial != null) furMaterial.setColor(Material.EMISSIVE, emissive);
        }

        private void buildQuadruped(Appearance appearance, boolean pig, Appearance fur) {
            if (pig) {
                addPart(pose, 28, 8, 10, 16, 8, 10, 16, 8,
                        0.0f, 0.8125f, 0.125f, -90.0f, 1, 0, 0,
                        0.0f, 0.125f, -0.1875f);
                addPart(head, 0, 0, 8, 8, 8, 8, 8, 8,
                        0.0f, 0.0f, 0.0f, 0, 0, 1, 0,
                        0.0f, 0.0f, -0.25f);
                // Alpha's pig model uses a separate 4x3x1 snout box.
                addPart(head, 16, 16, 4, 3, 1, 4, 3, 1,
                        0.0f, 0.0f, 0.0f, 0, 0, 1, 0,
                        0.0f, -0.09375f, -0.59375f);
                head.setTranslation(0.0f, 0.75f, -0.375f);
                pose.addChild(head);
                addLegs(6, 0.375f, 0.1875f, null);
            } else {
                addPart(pose, 28, 8, 8, 16, 6, 8, 16, 6,
                        0.0f, 1.1875f, 0.125f, -90.0f, 1, 0, 0,
                        0.0f, 0.125f, -0.25f);
                addPart(head, 0, 0, 6, 6, 8, 6, 6, 8,
                        0.0f, 0.0f, 0.0f, 0, 0, 1, 0,
                        0.0f, 0.0625f, -0.125f);
                head.setTranslation(0.0f, 1.125f, -0.5f);
                pose.addChild(head);
                // Sheep fleece is a separate inflated pass over the bare model.
                if (fur != null) {
                    fleece[0] = new Group();
                    head.addChild(fleece[0]);
                    addPart(fleece[0], 0, 0, 7.2f, 7.2f, 7.2f, 6, 6, 6,
                            0.0f, 0.0f, 0.0f, 0, 0, 1, 0, fur,
                            0.0f, 0.0625f, -0.0625f);
                    fleece[1] = new Group();
                    pose.addChild(fleece[1]);
                    addPart(fleece[1], 28, 8, 11.5f, 19.5f, 9.5f, 8, 16, 6,
                            0.0f, 1.1875f, 0.125f, -90.0f, 1, 0, 0, fur,
                            0.0f, 0.125f, -0.25f);
                }
                addLegs(12, 0.75f, 0.1875f, fur);
            }
        }

        private void addLegs(int length, float pivotY, float halfWidth, Appearance fur) {
            for (int i = 0; i < 4; i++) {
                Group leg = new Group();
                // The original renderer mirrors model X; its first and third legs are on -X.
                float x = (i & 1) == 0 ? halfWidth : -halfWidth;
                float z = i < 2 ? 0.3125f : -0.3125f;
                leg.setTranslation(x, pivotY, z);
                pose.addChild(leg);
                float joinedLength = length + JOINT_OVERLAP;
                float joinedCenterY = -(length - JOINT_OVERLAP) / 32.0f;
                addPart(leg, 0, 16, 4, joinedLength, 4, 4, length, 4,
                        0.0f, joinedCenterY, 0.0f, 0, 0, 1, 0);
                if (fur != null) {
                    fleece[2 + i] = new Group();
                    leg.addChild(fleece[2 + i]);
                    // The separate fleece model covers the upper 6 px of each leg.
                    addPart(fleece[2 + i], 0, 16, 5, 7, 5, 4, 6, 4,
                            0.0f, -0.1875f, 0.0f, 0, 0, 1, 0, fur);
                }
                legs[i] = leg;
            }
        }

        void setSheared(boolean value) {
            sheared = value;
            for (int i = 0; i < fleece.length; i++) {
                if (fleece[i] != null) fleece[i].setRenderingEnable(!value);
            }
        }

        private void buildZombie(Appearance appearance, boolean zombieHead) {
            // Lower and extend only the zombie's base head cube slightly so it
            // intersects the torso instead of leaving a visible neck seam.
            float baseHeadHeight = zombieHead ? 9.0f : 8.5f;
            float baseHeadY = zombieHead ? 0.21875f : 0.25f;
            addPart(head, 0, 0, 8, baseHeadHeight, 8, 8, 8, 8,
                    0.0f, 0.0f, 0.0f, 0, 0, 1, 0,
                    0.0f, baseHeadY, 0.0f);
            if (zombieHead) addPart(head, 32, 0, 9, 9, 9, 8, 8, 8,
                    0.0f, 0.0f, 0.0f, 0, 0, 1, 0,
                    0.0f, 0.25f, 0.0f);
            head.setTranslation(0.0f, 1.5f, 0.0f);
            pose.addChild(head);
            addPart(pose, 16, 16, 8, 12, 4, 8, 12, 4,
                    0.0f, 1.5f, 0.0f, 0, 0, 1, 0,
                    0.0f, -0.375f, 0.0f);
            for (int i = 0; i < 2; i++) {
                // RenderLiving mirrors model X, so the model's right limb is +X here.
                float side = i == 0 ? 1.0f : -1.0f;
                Group arm = new Group();
                arm.setTranslation(side * 0.3125f, 1.375f, 0.0f);
                pose.addChild(arm);
                Group armPitch = new Group();
                Group armRoll = new Group();
                arm.addChild(armPitch);
                armPitch.addChild(armRoll);
                int limbWidth = zombieHead ? 4 : 2;
                float armWidth = limbWidth + JOINT_OVERLAP;
                float armCenterX = zombieHead ? side * 0.0625f : 0.0f;
                addPart(armRoll, 40, 16, armWidth, 12, limbWidth, limbWidth, 12, limbWidth,
                        armCenterX, -0.25f, 0.0f, 0, 0, 1, 0,
                        skin, 0.0f, 0.0f, 0.0f, i == 1);
                arms[i] = arm;
                armsPitch[i] = armPitch;
                armsRoll[i] = armRoll;

                Group leg = new Group();
                leg.setTranslation(side * 0.125f, 0.75f, 0.0f);
                pose.addChild(leg);
                int legWidth = zombieHead ? 4 : 2;
                float legLength = 12.0f + JOINT_OVERLAP;
                addPart(leg, 0, 16, legWidth + JOINT_OVERLAP, legLength,
                        legWidth + JOINT_OVERLAP, legWidth, 12, legWidth,
                        0.0f, -(12.0f - JOINT_OVERLAP) / 32.0f, 0.0f, 0, 0, 1, 0,
                        skin, 0.0f, 0.0f, 0.0f, i == 1);
                legs[i] = leg;
            }
        }

        private void buildCreeper(Appearance appearance) {
            addPart(pose, 16, 16, 8, 12, 4, 8, 12, 4,
                    0.0f, 1.25f, 0.0f, 0, 0, 1, 0,
                    0.0f, -0.375f, 0.0f);
            addPart(head, 0, 0, 8, 8.5f, 8, 8, 8, 8,
                    0.0f, 0.0f, 0.0f, 0, 0, 1, 0,
                    0.0f, -0.015625f, 0.0f);
            head.setTranslation(0.0f, 1.5f, 0.0f);
            pose.addChild(head);
            for (int i = 0; i < 4; i++) {
                Group leg = new Group();
                float x = (i & 1) == 0 ? 0.125f : -0.125f;
                float z = i < 2 ? 0.25f : -0.25f;
                leg.setTranslation(x, 0.5f, z);
                pose.addChild(leg);
                addPart(leg, 0, 16, 4, 6.5f, 4, 4, 6, 4,
                        0.0f, -0.171875f, 0.0f, 0, 0, 1, 0);
                legs[i] = leg;
            }
        }

        private void buildCow(Appearance appearance) {
            // Alpha ModelCow: an 8x8x6 head, 12x18x10 body, four 4x12x4
            // legs and two horns.
            addPart(pose, 18, 4, 12, 18, 10, 12, 18, 10,
                    0.0f, 1.1875f, 0.125f, -90.0f, 1, 0, 0, appearance,
                    0.0f, 0.0625f, -0.125f);
            head.setTranslation(0.0f, 1.25f, -0.5f);
            pose.addChild(head);
            addPart(head, 0, 0, 8, 8, 6, 8, 8, 6,
                    0.0f, 0.0f, -0.1875f, 0, 0, 1, 0, appearance);
            addPart(head, 22, 0, 1, 3, 1, 1, 3, 1,
                    0.21875f, 0.28125f, -0.15625f, 0, 0, 1, 0, appearance);
            addPart(head, 22, 0, 1, 3, 1, 1, 3, 1,
                    -0.28125f, 0.28125f, -0.15625f, 0, 0, 1, 0, appearance,
                    0.0f, 0.0f, 0.0f, true);
            for (int i = 0; i < 4; i++) {
                float side = (i & 1) == 0 ? -1.0f : 1.0f;
                float front = i < 2 ? 0.4375f : -0.375f;
                Group leg = new Group();
                leg.setTranslation(side * 0.25f, 0.75f, front);
                pose.addChild(leg);
                addPart(leg, 0, 16, 4, 12, 4, 4, 12, 4,
                        0, -0.375f, 0, 0, 0, 1, 0, appearance);
                legs[i] = leg;
            }
        }

        private void buildChicken(Appearance appearance) {
            // Alpha ModelChicken's eight pieces and original 64x32 UV layout.
            addPart(pose, 0, 9, 6, 8, 6, 6, 8, 6,
                    0.0f, 0.5f, 0.0f, -90.0f, 1, 0, 0, appearance);
            head.setTranslation(0.0f, 0.5625f, -0.25f);
            pose.addChild(head);
            addPart(head, 0, 0, 4, 6, 3, 4, 6, 3,
                    0.0f, 0.0f, 0.0f, 0, 0, 1, 0, appearance,
                    0.0f, 0.1875f, -0.03125f);
            addPart(head, 14, 0, 4, 2, 2, 4, 2, 2,
                    0.0f, 0.0f, 0.0f, 0, 0, 1, 0, appearance,
                    0.0f, 0.1875f, -0.1875f);
            addPart(head, 14, 4, 2, 2, 2, 2, 2, 2,
                    0.0f, 0.0f, 0.0f, 0, 0, 1, 0, appearance,
                    0.0f, 0.0625f, -0.125f);
            for (int i = 0; i < 2; i++) {
                float legX = i == 0 ? 0.125f : -0.0625f;
                float wingSide = i == 0 ? 1.0f : -1.0f;
                Group leg = new Group();
                leg.setTranslation(legX, 0.3125f, 0.0625f);
                pose.addChild(leg);
                addPart(leg, 26, 0, 3, 5, 3, 3, 5, 3,
                        0, -0.15625f, -0.09375f, 0, 0, 1, 0, appearance);
                legs[i] = leg;
                Group wing = new Group();
                wing.setTranslation(wingSide * 0.25f, 0.6875f, 0.0f);
                pose.addChild(wing);
                addPart(wing, 24, 13, 1, 4, 6, 1, 4, 6,
                        0, -0.125f, 0, 0, 0, 1, 0, appearance,
                        -wingSide * 0.03125f, 0.0f, 0.0f);
                arms[i] = wing;
            }
        }

        private void buildSpider(Appearance appearance, Appearance eyes) {
            // Alpha's spider body and legs use the opposite vertical model axis
            // from the head. Keep them in a flipped group so the head stays upright.
            Group body = new Group();
            body.setTranslation(0.0f, 1.125f, 0.0f);
            body.setOrientation(180.0f, 1.0f, 0.0f, 0.0f);
            pose.addChild(body);
            addPart(body, 0, 0, 6, 6, 6, 6, 6, 6,
                    0.0f, 0.5625f, 0.0f, 0, 0, 1, 0, appearance);
            head.setTranslation(0.0f, 0.5625f, -0.1875f);
            pose.addChild(head);
            addPart(head, 32, 4, 8, 8, 8, 8, 8, 8,
                    0.0f, 0.0f, -0.25f, 0, 0, 1, 0, appearance);
            if (eyes != null)
                addPart(head, 32, 4, 8, 8, 8, 8, 8, 8,
                        0.0f, 0.0f, -0.252f, 0, 0, 1, 0, eyes);
            addPart(body, 0, 12, 10, 8, 12, 10, 8, 12,
                    0.0f, 0.5625f, 0.5625f, 0, 0, 1, 0, appearance);
            for (int i = 0; i < 8; i++) {
                boolean left = (i & 1) == 0;
                int row = i >> 1;
                float side = left ? 1.0f : -1.0f;
                float z = (2 - row) * 0.0625f;
                Group leg = new Group();
                leg.setTranslation(side * 0.25f, 0.5625f, z);
                leg.setOrientation(side * (row == 0 || row == 3 ? 45.0f : 33.3f), 0, 0, 1);
                body.addChild(leg);
                Group swing = new Group();
                leg.addChild(swing);
                addPart(swing, 18, 0, 16, 2, 2, 16, 2, 2,
                        side * 0.5f, 0, 0, 0, 0, 1, 0, appearance);
                spiderLegs[i] = swing;
            }
        }

        private void buildSlime(Appearance appearance) {
            // Alpha renders a translucent 8-cube around a smaller inner cube
            // whose UVs contain the eyes and mouth.
            addPart(pose, 0, 0, 8, 8, 8, 8, 8, 8,
                    0.0f, 0.50f, 0.0f, 0, 0, 1, 0, appearance);
            addPart(pose, 0, 16, 6, 6, 6, 6, 6, 6,
                    0.0f, 0.50f, -0.01f, 0, 0, 1, 0, appearance);
            addPart(pose, 32, 0, 2, 2, 2, 2, 2, 2,
                    0.0f, 0.56f, -0.19f, 0, 0, 1, 0, appearance);
            addPart(pose, 32, 4, 2, 2, 2, 2, 2, 2,
                    0.25f, 0.56f, -0.19f, 0, 0, 1, 0, appearance);
            addPart(pose, 32, 8, 1, 1, 1, 1, 1, 1,
                    0.125f, 0.34f, -0.19f, 0, 0, 1, 0, appearance);
        }

        private void buildGhast(Appearance appearance) {
            addPart(pose, 0, 0, 16, 16, 16, 16, 16, 16,
                    0.0f, 1.0f, 0.0f, 0, 0, 1, 0, appearance);
            Random modelRandom = new Random(1660L);
            for (int i = 0; i < tentacles.length; i++) {
                float x = ((((i % 3) - (i / 3 % 2) * 0.5f + 0.25f) / 2.0f) * 2.0f - 1.0f) * 5.0f / 16.0f;
                float z = (((i / 3) / 2.0f) * 2.0f - 1.0f) * 5.0f / 16.0f;
                int length = 8 + modelRandom.nextInt(7);
                Group tentacle = new Group();
                tentacle.setTranslation(x, 0.5625f, z);
                pose.addChild(tentacle);
                addPart(tentacle, 0, 0, 2, length, 2, 2, length, 2,
                        0.0f, -length / 32.0f, 0.0f, 0, 0, 1, 0, appearance);
                tentacles[i] = tentacle;
            }
        }

        private void addPart(Group parent, int u, int v, float width, float height, float depth,
                int layoutWidth, int layoutHeight, int layoutDepth,
                float x, float y, float z, float angle, float ax, float ay, float az) {
            addPart(parent, u, v, width, height, depth, layoutWidth, layoutHeight, layoutDepth,
                    x, y, z, angle, ax, ay, az, skin);
        }

        private void addPart(Group parent, int u, int v, float width, float height, float depth,
                int layoutWidth, int layoutHeight, int layoutDepth,
                float x, float y, float z, float angle, float ax, float ay, float az,
                Appearance appearance) {
            addPart(parent, u, v, width, height, depth, layoutWidth, layoutHeight, layoutDepth,
                    x, y, z, angle, ax, ay, az, appearance, 0.0f, 0.0f, 0.0f);
        }

        private void addPart(Group parent, int u, int v, float width, float height, float depth,
                int layoutWidth, int layoutHeight, int layoutDepth,
                float x, float y, float z, float angle, float ax, float ay, float az,
                float boxX, float boxY, float boxZ) {
            addPart(parent, u, v, width, height, depth, layoutWidth, layoutHeight, layoutDepth,
                    x, y, z, angle, ax, ay, az, skin, boxX, boxY, boxZ);
        }

        private void addPart(Group parent, int u, int v, float width, float height, float depth,
                int layoutWidth, int layoutHeight, int layoutDepth,
                float x, float y, float z, float angle, float ax, float ay, float az,
                Appearance appearance, float boxX, float boxY, float boxZ) {
            addPart(parent, u, v, width, height, depth, layoutWidth, layoutHeight, layoutDepth,
                    x, y, z, angle, ax, ay, az, appearance, boxX, boxY, boxZ, false);
        }

        private void addPart(Group parent, int u, int v, float width, float height, float depth,
                int layoutWidth, int layoutHeight, int layoutDepth,
                float x, float y, float z, float angle, float ax, float ay, float az,
                Appearance appearance, float boxX, float boxY, float boxZ, boolean mirror) {
            Group part = new Group();
            part.setTranslation(x, y, z);
            if (angle != 0.0f) part.setOrientation(angle, ax, ay, az);
            Group box = new Group();
            box.setTranslation(boxX, boxY, boxZ);
            box.addChild(makeBox(u, v, width, height, depth,
                    layoutWidth, layoutHeight, layoutDepth, appearance, mirror));
            part.addChild(box);
            parent.addChild(part);
        }
    }

    private Mesh makeBox(int u, int v, float width, float height, float depth,
            int layoutWidth, int layoutHeight, int layoutDepth, Appearance appearance, boolean mirror) {
        // Six closed quads, each written as two explicit triangles on one diagonal.
        int vertexCount = 36;
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        float x0 = -width * 0.5f, x1 = width * 0.5f;
        if (mirror) { float swap = x0; x0 = x1; x1 = swap; }
        float y0 = -height * 0.5f, y1 = height * 0.5f;
        float z0 = -depth * 0.5f, z1 = depth * 0.5f;
        // Hand-authored corners for +X, -X, -Y, +Y, -Z, +Z faces.
        float[][][] faces = new float[][][] {
            {{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}},
            {{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}},
            {{x1,y0,z1},{x0,y0,z1},{x0,y0,z0},{x1,y0,z0}},
            {{x1,y1,z0},{x0,y1,z0},{x0,y1,z1},{x1,y1,z1}},
            {{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}},
            {{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}
        };
        int[][] rects = new int[][] {
            {u + layoutDepth + layoutWidth, v + layoutDepth,
                u + layoutDepth + layoutWidth + layoutDepth, v + layoutDepth + layoutHeight},
            {u, v + layoutDepth, u + layoutDepth, v + layoutDepth + layoutHeight},
            // The original renderer mirrors model Y; M3G does not. Swap the
            // top/bottom atlas rectangles to keep them on the same world faces.
            {u + layoutDepth + layoutWidth, v, u + layoutDepth + layoutWidth + layoutWidth, v + layoutDepth},
            {u + layoutDepth, v, u + layoutDepth + layoutWidth, v + layoutDepth},
            {u + layoutDepth, v + layoutDepth, u + layoutDepth + layoutWidth, v + layoutDepth + layoutHeight},
            {u + layoutDepth + layoutWidth + layoutDepth, v + layoutDepth,
                u + layoutDepth + layoutWidth + layoutDepth + layoutWidth, v + layoutDepth + layoutHeight}
        };
        // This diagonal shares corners 0 and 2; do not send a perimeter-ordered
        // quad through one triangle strip, whose alternating winding leaves a gap.
        int[] triangleCorners = {0, 1, 2, 0, 2, 3};
        int at = 0;
        for (int face = 0; face < 6; face++) {
            int[] r = rects[face];
            for (int q = 0; q < 6; q++) {
                int quadCorner = triangleCorners[q];
                int corner = mirror ? 3 - quadCorner : quadCorner;
                float[] p = faces[face][corner];
                positions[at * 3] = (short)(p[0] * 64.0f);
                positions[at * 3 + 1] = (short)(p[1] * 64.0f);
                positions[at * 3 + 2] = (short)(p[2] * 64.0f);
                // Preserve the established mob atlas orientation; these offsets
                // account for the texture coordinate convention used by the meshes.
                int uCoord = (corner == 0 || corner == 3) ? r[2] * 16 - 2 : r[0] * 16 + 2;
                int vCoord = corner < 2 ? r[3] * 32 - 3 : r[1] * 32 + 3;
                texCoords[at * 2] = (short)uCoord;
                texCoords[at * 2 + 1] = (short)vCoord;
                at++;
            }
        }
        VertexArray pa = new VertexArray(vertexCount, 3, 2);
        pa.set(0, vertexCount, positions);
        VertexArray ta = new VertexArray(vertexCount, 2, 2);
        ta.set(0, vertexCount, texCoords);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 1024.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        int[] strips = {3,3,3,3,3,3,3,3,3,3,3,3};
        return new Mesh(vb, new TriangleStripArray(0, strips), appearance);
    }

}


