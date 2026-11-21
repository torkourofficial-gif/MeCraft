package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/**
 * Small pooled Ender Dragon boss for the End dimension.
 * Geometry is built once; updates only change existing group transforms.
 */
final class EnderDragonSystem {
    private final Group root = new Group();
    private final Group leftWing = new Group();
    private final Group rightWing = new Group();
    private final Group leftWingTip = new Group();
    private final Group rightWingTip = new Group();
    private final Group dragonHead = new Group();
    private final Group jaw = new Group();
    private final Group[] neckSegments = new Group[5];
    private final Group[] tailSegments = new Group[12];
    private VertexBuffer cubeVertexBuffer;
    private TriangleStripArray cubeIndexBuffer;
    private short[] cubePositions;
    private short[] cubeUV;
    private int worldSeed = Integer.MIN_VALUE;
    private int tick;
    private int attackCooldown;
    private int playerHitCooldown;
    private int health = 200;
    private boolean defeated;
    private boolean absentAfterVictory;
    private int deathAnimationTicks;
    private int respawnDelayTicks;
    private int pendingDeathExplosionBursts;
    private boolean pendingFinalExplosion;
    private boolean pendingPortalActivation;
    private boolean pendingPortalDeactivation;
    private float deathX, deathY, deathZ;

    EnderDragonSystem(Group scene) throws IOException {
        Image image = GameAssets.image("ender.png");
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
        createSharedCubeGeometry();

        // ModelDragon's 1.2.5 boxes converted from pixels to blocks (16 px = 1 block).
        addBoxBatch(root, new float[] { 1.5f,1.5f,4.0f, 0.0f,0.0f,1.5f }, appearance, 0, 0);
        addBoxBatch(root, new float[] {
            0.125f,0.375f,0.75f, 0.0f,0.94f,-0.3f,
            0.125f,0.375f,0.75f, 0.0f,0.94f,0.95f,
            0.125f,0.375f,0.75f, 0.0f,0.94f,2.2f
        }, appearance, 220, 53);

        // Five original 10x10x10 neck segments bridge the torso and skull.
        for (int i = 0; i < neckSegments.length; i++) {
            neckSegments[i] = new Group();
            neckSegments[i].setTranslation(0.0f, -0.25f, -0.75f - i * 0.625f);
            root.addChild(neckSegments[i]);
            addBoxBatch(neckSegments[i], new float[] { 0.625f,0.625f,0.625f, 0,0,0 },
                    appearance, 192, 104);
            addBoxBatch(neckSegments[i], new float[] { 0.125f,0.25f,0.375f, 0,0.42f,0.125f },
                    appearance, 48, 0);
        }

        // ModelDragon renders twelve more 10x10x10 neck pieces as the tail.
        for (int i = 0; i < tailSegments.length; i++) {
            Group segment = new Group();
            segment.setTranslation(0.0f, 0.625f, 3.25f + i * 0.625f);
            root.addChild(segment);
            addBoxBatch(segment, new float[] { 0.625f,0.625f,0.625f, 0,0,0 },
                    appearance, 192, 104);
            addBoxBatch(segment, new float[] { 0.125f,0.25f,0.375f, 0,0.25f,0.125f },
                    appearance, 48, 0);
            tailSegments[i] = segment;
        }

        dragonHead.setTranslation(0.0f, -0.25f, -3.85f);
        root.addChild(dragonHead);
        addBoxBatch(dragonHead, new float[] { 1.0f,1.0f,1.0f, 0,0,-0.125f },
                appearance, 112, 30);
        addBoxBatch(dragonHead, new float[] { 0.75f,0.3125f,1.0f, 0,-0.094f,-1.0f },
                appearance, 176, 44);
        addBoxBatch(dragonHead, new float[] {
            0.125f,0.25f,0.375f, -0.25f,0.625f,-0.0625f,
            0.125f,0.25f,0.375f, 0.25f,0.625f,-0.0625f
        }, appearance, 0, 0);
        addBoxBatch(dragonHead, new float[] {
            0.125f,0.125f,0.25f, -0.25f,-0.125f,-1.25f,
            0.125f,0.125f,0.25f, 0.25f,-0.125f,-1.25f
        }, appearance, 112, 0);
        jaw.setTranslation(0.0f, -0.375f, -0.5f);
        dragonHead.addChild(jaw);
        addBoxBatch(jaw, new float[] { 0.75f,0.25f,1.0f, 0.0f,0.0f,-0.5f }, appearance, 176, 65);

        buildLegs(appearance);
        buildWing(leftWing, leftWingTip, -1.0f, appearance);
        buildWing(rightWing, rightWingTip, 1.0f, appearance);
        root.setRenderingEnable(false);
        scene.addChild(root);
    }

    private void buildLegs(Appearance appearance) {
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1.0f : 1.0f;
            Group front = new Group();
            front.setTranslation(sign * 0.75f, -0.25f, -0.875f);
            root.addChild(front);
            addBox(front, 0.5f, 1.5f, 0.5f, 0.0f, -0.5f, 0.0f, appearance, 112, 104);
            Group frontTip = new Group();
            frontTip.setTranslation(0.0f, -1.25f, -0.0625f);
            front.addChild(frontTip);
            addBox(frontTip, 0.375f, 1.5f, 0.375f, 0.0f, -0.6875f, 0.0f, appearance, 226, 138);
            Group frontFoot = new Group();
            frontFoot.setTranslation(0.0f, -0.1875f, 0.0f);
            frontTip.addChild(frontFoot);
            addBox(frontFoot, 0.5f, 0.25f, 1.0f, 0.0f, -0.125f, -0.25f, appearance, 144, 104);

            Group rear = new Group();
            rear.setTranslation(sign * 1.0f, 0.0f, 1.625f);
            root.addChild(rear);
            addBox(rear, 1.0f, 2.0f, 1.0f, 0.0f, -0.75f, 0.0f, appearance, 0, 0);
            Group rearTip = new Group();
            rearTip.setTranslation(0.0f, -2.0f, -0.25f);
            rear.addChild(rearTip);
            addBox(rearTip, 0.75f, 2.0f, 0.75f, 0.0f, -0.875f, 0.0f, appearance, 196, 0);
            Group rearFoot = new Group();
            rearFoot.setTranslation(0.0f, 0.0625f, 0.25f);
            rearTip.addChild(rearFoot);
            addBox(rearFoot, 1.125f, 0.375f, 1.5f, 0.0f, -0.1875f, -0.5f, appearance, 112, 0);
        }
    }

    private void buildWing(Group wing, Group tip, float side, Appearance appearance) {
        wing.setTranslation(side * 0.75f, 0.6875f, -0.875f);
        root.addChild(wing);
        addBoxBatch(wing, new float[] { 3.5f,0.5f,0.5f, side * 1.75f,0.0f,0.0f },
                appearance, 112, 88);
        addBoxBatch(wing, new float[] { 3.5f,0.025f,3.5f, side * 1.75f,0.0f,1.875f },
                appearance, -56, 88);
        tip.setTranslation(side * 3.5f, 0.0f, 0.0f);
        wing.addChild(tip);
        addBoxBatch(tip, new float[] { 3.5f,0.25f,0.25f, side * 1.75f,0.0f,0.0f },
                appearance, 112, 136);
        addBoxBatch(tip, new float[] { 3.5f,0.025f,3.5f, side * 1.75f,0.0f,1.875f },
                appearance, -56, 144);
    }

    private void createSharedCubeGeometry() {
        cubePositions = new short[] {
            32,-32,32, 32,-32,-32, 32,32,-32, 32,32,32,
            -32,-32,-32, -32,-32,32, -32,32,32, -32,32,-32,
            32,-32,32, -32,-32,32, -32,-32,-32, 32,-32,-32,
            32,32,-32, -32,32,-32, -32,32,32, 32,32,32,
            32,-32,-32, -32,-32,-32, -32,32,-32, 32,32,-32,
            -32,-32,32, 32,-32,32, 32,32,32, -32,32,32
        };
        cubeUV = new short[] {
            0,1024,1024,1024,1024,0,0,0,
            0,1024,1024,1024,1024,0,0,0,
            0,1024,1024,1024,1024,0,0,0,
            0,1024,1024,1024,1024,0,0,0,
            0,1024,1024,1024,1024,0,0,0,
            0,1024,1024,1024,1024,0,0,0
        };
        VertexArray pa = new VertexArray(24, 3, 2);
        pa.set(0, 24, cubePositions);
        VertexArray ta = new VertexArray(24, 2, 2);
        ta.set(0, 24, cubeUV);
        cubeVertexBuffer = new VertexBuffer();
        cubeVertexBuffer.setPositions(pa, 1.0f / 64.0f, null);
        cubeVertexBuffer.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        cubeIndexBuffer = new TriangleStripArray(0, new int[] {4,4,4,4,4,4});
    }

    private void addBox(Group parent, float width, float height, float depth,
                        float x, float y, float z, Appearance appearance) {
        addBox(parent, width, height, depth, x, y, z, appearance, 0, 0);
    }

    private void addBox(Group parent, float width, float height, float depth,
                        float x, float y, float z, Appearance appearance, int textureU, int textureV) {
        Group part = new Group();
        part.setTranslation(x, y, z);
        addBoxBatch(part, new float[] { width,height,depth,0.0f,0.0f,0.0f }, appearance,
                textureU, textureV);
        parent.addChild(part);
    }

    /** Combine rigid boxes under one transform into a single M3G draw call. */
    private void addBoxBatch(Group parent, float[] boxes, Appearance appearance) {
        addBoxBatch(parent, boxes, appearance, 0, 0);
    }

    /** Map the box faces into the same 256x256 atlas rectangles as ModelDragon. */
    private void addBoxBatch(Group parent, float[] boxes, Appearance appearance,
                             int textureU, int textureV) {
        int boxCount = boxes.length / 6;
        int vertexCount = boxCount * 24;
        short[] positions = new short[vertexCount * 3];
        short[] uv = new short[vertexCount * 2];
        int[] stripLengths = new int[boxCount * 6];
        int positionAt = 0, uvAt = 0;
        for (int box = 0; box < boxCount; box++) {
            int boxAt = box * 6;
            float width = boxes[boxAt], height = boxes[boxAt + 1], depth = boxes[boxAt + 2];
            float x = boxes[boxAt + 3], y = boxes[boxAt + 4], z = boxes[boxAt + 5];
            for (int vertex = 0; vertex < 24; vertex++) {
                // A triangle strip uses vertices 1 and 2 as its shared diagonal.
                // Reorder the outline corners to [0,1,3,2] so both halves fill the quad.
                int sourceVertex = (vertex / 4) * 4 + ((vertex & 3) == 2 ? 3 :
                        ((vertex & 3) == 3 ? 2 : (vertex & 3)));
                int source = sourceVertex * 3;
                positions[positionAt++] = quantizePosition(cubePositions[source] * width + x * 64.0f);
                positions[positionAt++] = quantizePosition(cubePositions[source + 1] * height + y * 64.0f);
                positions[positionAt++] = quantizePosition(cubePositions[source + 2] * depth + z * 64.0f);
                int face = vertex / 4;
                int local = vertex & 3;
                // Dimensions are non-negative; round to the nearest texture pixel
                // without depending on Math.round(float) in the CLDC toolchain.
                int w = (int)(width * 16.0f + 0.5f);
                int h = (int)(height * 16.0f + 0.5f);
                int d = (int)(depth * 16.0f + 0.5f);
                int u0, v0, faceWidth, faceHeight;
                if (face == 0) { u0 = textureU + d; v0 = textureV + d; faceWidth = w; faceHeight = h; }
                else if (face == 1) { u0 = textureU + d + w + d; v0 = textureV + d; faceWidth = w; faceHeight = h; }
                else if (face == 2) { u0 = textureU + d; v0 = textureV; faceWidth = w; faceHeight = d; }
                else if (face == 3) { u0 = textureU + d + w; v0 = textureV; faceWidth = w; faceHeight = d; }
                else if (face == 4) { u0 = textureU; v0 = textureV + d; faceWidth = d; faceHeight = h; }
                else { u0 = textureU + d + w; v0 = textureV + d; faceWidth = d; faceHeight = h; }
                // Keep UV corners in the same strip-safe order as the positions.
                if (local == 0 || local == 2) uv[uvAt++] = (short)((u0 + faceWidth) * 4);
                else uv[uvAt++] = (short)(u0 * 4);
                if (local < 2) uv[uvAt++] = (short)((v0 + faceHeight) * 4);
                else uv[uvAt++] = (short)(v0 * 4);
            }
            for (int strip = 0; strip < 6; strip++) stripLengths[box * 6 + strip] = 4;
        }
        VertexArray positionArray = new VertexArray(vertexCount, 3, 2);
        positionArray.set(0, vertexCount, positions);
        VertexArray textureArray = new VertexArray(vertexCount, 2, 2);
        textureArray.set(0, vertexCount, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positionArray, 1.0f / 64.0f, null);
        vertices.setTexCoords(0, textureArray, 1.0f / 1024.0f, null);
        parent.addChild(new Mesh(vertices, new TriangleStripArray(0, stripLengths), appearance));
    }

    private int putBox(float[] boxes, int box, float width, float height, float depth,
                       float x, float y, float z) {
        int at = box * 6;
        boxes[at] = width;
        boxes[at + 1] = height;
        boxes[at + 2] = depth;
        boxes[at + 3] = x;
        boxes[at + 4] = y;
        boxes[at + 5] = z;
        return box + 1;
    }

    private short quantizePosition(float value) {
        return (short)(value < 0.0f ? value - 0.5f : value + 0.5f);
    }

    synchronized int update(VoxelWorld world) {
        if (worldSeed != world.seed) {
            worldSeed = world.seed;
            tick = 0;
            attackCooldown = 0;
            playerHitCooldown = 0;
            boolean exitActive = world.isEndExitPortalActive();
            int crystals = world.remainingEndCrystals();
            absentAfterVictory = exitActive && crystals == 0;
            health = absentAfterVictory ? 0 : 200;
            defeated = absentAfterVictory;
            deathAnimationTicks = 0;
            respawnDelayTicks = 0;
            pendingDeathExplosionBursts = 0;
            pendingFinalExplosion = false;
            pendingPortalActivation = false;
            pendingPortalDeactivation = exitActive && crystals > 0;
        }
        if (world.dimensionId != VoxelWorld.DIM_END) {
            root.setRenderingEnable(false);
            return 0;
        }
        if (respawnDelayTicks > 0) {
            if (world.remainingEndCrystals() == 0) {
                respawnDelayTicks = 0;
                absentAfterVictory = true;
                pendingPortalActivation = true;
            } else if (--respawnDelayTicks == 0) {
                health = 200;
                defeated = false;
                absentAfterVictory = false;
                pendingPortalDeactivation = true;
            }
            root.setRenderingEnable(false);
            return 0;
        }
        if (absentAfterVictory) {
            root.setRenderingEnable(false);
            return 0;
        }
        root.setRenderingEnable(true);
        tick++;
        if (attackCooldown > 0) attackCooldown--;
        if (playerHitCooldown > 0) playerHitCooldown--;
        if (!defeated && health < 200 && world.remainingEndCrystals() > 0 && tick % 8 == 0)
            health++;
        float angle = tick * 0.025f;
        float orbitRadius = tick % 240 < 150 ? 9.0f : 4.5f;
        float x = VoxelWorld.WIDTH * 0.5f + (float)Math.cos(angle) * orbitRadius;
        float z = VoxelWorld.DEPTH * 0.5f + (float)Math.sin(angle) * orbitRadius;
        float y = 49.0f + (float)Math.sin(angle * 2.0f) * 2.5f;
        float faceX = world.playerX, faceZ = world.playerZ;
        if (defeated && deathAnimationTicks > 0) {
            int elapsed = 100 - deathAnimationTicks;
            float targetX = VoxelWorld.WIDTH * 0.5f + 5.5f;
            float targetZ = VoxelWorld.DEPTH * 0.5f + 0.5f;
            float targetY = InfdevTerrain.endExitBaseY(world.seed) + 3.5f;
            if (elapsed < 25) {
                float approach = elapsed / 25.0f;
                x += (targetX - x) * approach;
                z += (targetZ - z) * approach;
                y += (targetY - y) * approach;
            } else if (elapsed < 60) {
                x = targetX; y = targetY; z = targetZ;
            } else {
                float rise = (elapsed - 60) / 40.0f;
                x = targetX; z = targetZ;
                y = targetY + rise * 8.0f;
                if ((elapsed - 60) % 7 == 0) pendingDeathExplosionBursts++;
            }
            faceX = VoxelWorld.WIDTH * 0.5f;
            faceZ = VoxelWorld.DEPTH * 0.5f;
            deathX = x; deathY = y; deathZ = z;
            deathAnimationTicks--;
            if (deathAnimationTicks == 0) {
                pendingDeathExplosionBursts++;
                pendingFinalExplosion = true;
                if (world.remainingEndCrystals() == 0) {
                    absentAfterVictory = true;
                    pendingPortalActivation = true;
                } else {
                    respawnDelayTicks = 70;
                }
            }
        }
        float dx = faceX - x, dz = faceZ - z;
        root.setTranslation(x, y, z);
        root.setOrientation(yawTo(dx, dz), 0.0f, 1.0f, 0.0f);
        float flap = (float)Math.sin(tick * (defeated ? 0.16f : 0.22f)) *
                (defeated ? 12.0f : 38.0f);
        leftWing.setOrientation(flap + 7.16f, 0.0f, 0.0f, 1.0f);
        rightWing.setOrientation(-flap - 7.16f, 0.0f, 0.0f, 1.0f);
        float wingTipFlap = (float)Math.sin(tick * 0.22f + 2.0f) * 43.0f;
        leftWingTip.setOrientation(-wingTipFlap, 0.0f, 0.0f, 1.0f);
        rightWingTip.setOrientation(wingTipFlap, 0.0f, 0.0f, 1.0f);
        for (int i = 0; i < neckSegments.length; i++) {
            neckSegments[i].setOrientation(
                    (float)Math.sin(tick * 0.035f + i * 0.45f) * 5.0f,
                    0.0f, 1.0f, 0.0f);
        }
        for (int i = 0; i < tailSegments.length; i++) {
            tailSegments[i].setOrientation(
                    (float)Math.sin(tick * 0.035f + i * 0.35f) * 4.0f,
                    0.0f, 1.0f, 0.0f);
        }
        dragonHead.setOrientation((float)Math.sin(tick * 0.045f) * 4.0f,
                0.0f, 1.0f, 0.0f);
        jaw.setOrientation((float)Math.sin(tick * 0.16f) * 9.0f, 1.0f, 0.0f, 0.0f);
        float distance = dx * dx + (world.playerY + 1.0f - y) * (world.playerY + 1.0f - y) + dz * dz;
        if (!defeated && distance < 36.0f && attackCooldown == 0) {
            attackCooldown = 30;
            return 6;
        }
        return 0;
    }

    synchronized int hit(VoxelWorld world, float dirX, float dirY, float dirZ, int damage) {
        if (world.dimensionId != VoxelWorld.DIM_END || defeated) return -1;
        float ox = world.playerX, oy = world.playerY + 1.62f, oz = world.playerZ;
        float angle = tick * 0.025f;
        float orbitRadius = tick % 240 < 150 ? 9.0f : 4.5f;
        float cx = VoxelWorld.WIDTH * 0.5f + (float)Math.cos(angle) * orbitRadius;
        float cz = VoxelWorld.DEPTH * 0.5f + (float)Math.sin(angle) * orbitRadius;
        float cy = 49.0f + (float)Math.sin(angle * 2.0f) * 2.5f;
        float along = (cx - ox) * dirX + (cy - oy) * dirY + (cz - oz) * dirZ;
        if (along < 0.0f || along > 7.0f) return -1;
        float px = ox + dirX * along - cx;
        float py = oy + dirY * along - cy;
        float pz = oz + dirZ * along - cz;
        if (px * px + py * py + pz * pz > 5.0f) return -1;
        int steps = (int)(along * 6.0f);
        for (int i = 1; i < steps; i++) {
            float t = i / 6.0f;
            if (world.isSolidAt((int)(ox + dirX * t), (int)(oy + dirY * t), (int)(oz + dirZ * t))) return -1;
        }
        // The dragon's attack timer is independent from its hurt timer. Using
        // one counter for both made a nearby dragon reset the timer every tick,
        // so the player could never hit it while it was in attack range.
        if (playerHitCooldown > 0) return -1;
        playerHitCooldown = 6;
        health -= damage;
        if (health <= 0) {
            health = 0;
            defeated = true;
            deathAnimationTicks = 100;
            deathX = cx; deathY = cy; deathZ = cz;
            if (world.remainingEndCrystals() == 0) world.activateEndExitPortal();
        }
        return 1;
    }

    synchronized int health() { return absentAfterVictory ? -1 : health; }

    synchronized int consumeDeathExplosionBursts(float[] position) {
        if (position != null && position.length >= 3) {
            position[0] = deathX; position[1] = deathY; position[2] = deathZ;
        }
        int result = pendingDeathExplosionBursts;
        pendingDeathExplosionBursts = 0;
        return result;
    }

    synchronized boolean consumeFinalExplosion() {
        boolean result = pendingFinalExplosion;
        pendingFinalExplosion = false;
        return result;
    }

    synchronized boolean consumePortalActivationRequest() {
        boolean result = pendingPortalActivation;
        pendingPortalActivation = false;
        return result;
    }

    synchronized boolean consumePortalDeactivationRequest() {
        boolean result = pendingPortalDeactivation;
        pendingPortalDeactivation = false;
        return result;
    }

    private float yawTo(float dx, float dz) {
        if (dx == 0.0f && dz == 0.0f) return 0.0f;
        float ax = dx < 0.0f ? -dx : dx;
        float az = dz < 0.0f ? -dz : dz;
        float angle;
        if (az >= ax) {
            float ratio = az == 0.0f ? 0.0f : ax / az;
            angle = ratio * (0.7853982f + 0.273f * (1.0f - ratio));
        } else {
            float ratio = az / ax;
            float small = ratio * (0.7853982f + 0.273f * (1.0f - ratio));
            angle = 1.5707963f - small;
        }
        if (dz < 0.0f) {
            angle = dx >= 0.0f ? 3.1415927f - angle : angle - 3.1415927f;
        } else if (dx < 0.0f) {
            angle = -angle;
        }
        return angle * 57.29578f;
    }
}
