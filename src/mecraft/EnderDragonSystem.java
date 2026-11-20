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
    private final Group[] tailSegments = new Group[6];
    private final Group[] frontLegs = new Group[2];
    private final Group[] rearLegs = new Group[2];
    private final Group jaw = new Group();
    private int worldSeed = Integer.MIN_VALUE;
    private int tick;
    private int attackCooldown;
    private int health = 200;
    private boolean defeated;

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

        addBox(root, 1.6f, 1.35f, 3.8f, 0.0f, 0.0f, 0.0f, appearance);
        addBox(root, 0.85f, 0.85f, 1.0f, 0.0f, 0.35f, -2.15f, appearance);
        addBox(root, 0.72f, 0.72f, 0.85f, 0.0f, 0.42f, -2.85f, appearance);
        addBox(root, 0.62f, 0.62f, 0.72f, 0.0f, 0.48f, -3.45f, appearance);
        addBox(root, 1.15f, 0.95f, 1.15f, 0.0f, 0.55f, -4.05f, appearance);
        jaw.setTranslation(0.0f, 0.12f, -4.25f);
        root.addChild(jaw);
        addBox(jaw, 0.95f, 0.28f, 0.85f, 0.0f, 0.0f, 0.0f, appearance);
        for (int i = 0; i < tailSegments.length; i++) {
            Group segment = new Group();
            segment.setTranslation(0.0f, 0.0f, 2.35f + i * 0.52f);
            root.addChild(segment);
            float size = 0.58f - i * 0.065f;
            addBox(segment, size, size, 0.72f, 0.0f, 0.0f, 0.0f, appearance);
            tailSegments[i] = segment;
        }
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? -1.0f : 1.0f;
            Group front = new Group();
            front.setTranslation(sign * 0.68f, -0.48f, -1.05f);
            root.addChild(front);
            addBox(front, 0.50f, 1.45f, 0.50f, 0.0f, -0.62f, 0.0f, appearance);
            addBox(front, 0.40f, 0.28f, 0.92f, 0.0f, -1.42f, -0.18f, appearance);
            frontLegs[side] = front;
            Group rear = new Group();
            rear.setTranslation(sign * 0.72f, -0.45f, 1.72f);
            root.addChild(rear);
            addBox(rear, 0.92f, 1.75f, 0.92f, 0.0f, -0.78f, 0.0f, appearance);
            addBox(rear, 0.72f, 0.32f, 1.10f, 0.0f, -1.75f, 0.10f, appearance);
            rearLegs[side] = rear;
        }

        leftWing.setTranslation(-0.9f, 0.35f, -0.1f);
        rightWing.setTranslation(0.9f, 0.35f, -0.1f);
        root.addChild(leftWing);
        root.addChild(rightWing);
        addBox(leftWing, 4.8f, 0.12f, 3.2f, -1.9f, 0.0f, 0.0f, appearance);
        addBox(leftWing, 2.2f, 0.10f, 2.4f, -3.1f, 0.0f, 0.35f, appearance);
        addBox(rightWing, 4.8f, 0.12f, 3.2f, 1.9f, 0.0f, 0.0f, appearance);
        addBox(rightWing, 2.2f, 0.10f, 2.4f, 3.1f, 0.0f, 0.35f, appearance);
        root.setRenderingEnable(false);
        scene.addChild(root);
    }

    private void addBox(Group parent, float width, float height, float depth,
                        float x, float y, float z, Appearance appearance) {
        Group part = new Group();
        part.setTranslation(x, y, z);
        part.addChild(makeBox(width, height, depth, appearance));
        parent.addChild(part);
    }

    private Mesh makeBox(float width, float height, float depth, Appearance appearance) {
        short[] positions = new short[24 * 3];
        short[] uv = new short[24 * 2];
        float x0 = -width * 0.5f, x1 = width * 0.5f;
        float y0 = -height * 0.5f, y1 = height * 0.5f;
        float z0 = -depth * 0.5f, z1 = depth * 0.5f;
        float[][][] faces = new float[][][] {
            {{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}},
            {{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}},
            {{x1,y0,z1},{x0,y0,z1},{x0,y0,z0},{x1,y0,z0}},
            {{x1,y1,z0},{x0,y1,z0},{x0,y1,z1},{x1,y1,z1}},
            {{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}},
            {{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}
        };
        int at = 0;
        for (int face = 0; face < 6; face++) {
            for (int corner = 0; corner < 4; corner++) {
                float[] p = faces[face][corner];
                positions[at * 3] = (short)(p[0] * 64.0f);
                positions[at * 3 + 1] = (short)(p[1] * 64.0f);
                positions[at * 3 + 2] = (short)(p[2] * 64.0f);
                uv[at * 2] = (short)((corner == 0 || corner == 3) ? 0 : 1024);
                uv[at * 2 + 1] = (short)(corner < 2 ? 1024 : 0);
                at++;
            }
        }
        VertexArray pa = new VertexArray(24, 3, 2);
        pa.set(0, 24, positions);
        VertexArray ta = new VertexArray(24, 2, 2);
        ta.set(0, 24, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 64.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        int[] strips = {4,4,4,4,4,4};
        return new Mesh(vb, new TriangleStripArray(0, strips), appearance);
    }

    int update(VoxelWorld world) {
        if (worldSeed != world.seed) {
            worldSeed = world.seed;
            tick = 0;
            attackCooldown = 0;
            health = 200;
            defeated = false;
        }
        if (world.dimensionId != VoxelWorld.DIM_END || defeated) {
            root.setRenderingEnable(false);
            return 0;
        }
        root.setRenderingEnable(true);
        tick++;
        if (attackCooldown > 0) attackCooldown--;
        float angle = tick * 0.025f;
        float orbitRadius = tick % 240 < 150 ? 9.0f : 4.5f;
        float x = VoxelWorld.WIDTH * 0.5f + (float)Math.cos(angle) * orbitRadius;
        float z = VoxelWorld.DEPTH * 0.5f + (float)Math.sin(angle) * orbitRadius;
        float y = 38.0f + (float)Math.sin(angle * 2.0f) * 2.0f;
        float dx = world.playerX - x, dz = world.playerZ - z;
        root.setTranslation(x, y, z);
        root.setOrientation(yawTo(dx, dz), 0.0f, 1.0f, 0.0f);
        float flap = (float)Math.sin(tick * 0.22f) * 38.0f;
        leftWing.setOrientation(flap, 0.0f, 0.0f, 1.0f);
        rightWing.setOrientation(-flap, 0.0f, 0.0f, 1.0f);
        jaw.setOrientation((float)Math.sin(tick * 0.16f) * 9.0f, 1.0f, 0.0f, 0.0f);
        for (int i = 0; i < tailSegments.length; i++) {
            float sway = (float)Math.sin(tick * 0.08f + i * 0.45f) * (2.0f + i * 0.5f);
            tailSegments[i].setOrientation(sway, 0.0f, 1.0f, 0.0f);
        }
        float legSwing = (float)Math.sin(tick * 0.22f) * 12.0f;
        for (int i = 0; i < 2; i++) {
            frontLegs[i].setOrientation(legSwing, 1.0f, 0.0f, 0.0f);
            rearLegs[i].setOrientation(-legSwing, 1.0f, 0.0f, 0.0f);
        }
        float distance = dx * dx + (world.playerY + 1.0f - y) * (world.playerY + 1.0f - y) + dz * dz;
        if (distance < 36.0f && attackCooldown == 0) {
            attackCooldown = 30;
            return 6;
        }
        return 0;
    }

    int hit(VoxelWorld world, float dirX, float dirY, float dirZ, int damage) {
        if (world.dimensionId != VoxelWorld.DIM_END || defeated) return -1;
        float ox = world.playerX, oy = world.playerY + 1.62f, oz = world.playerZ;
        float angle = tick * 0.025f;
        float orbitRadius = tick % 240 < 150 ? 9.0f : 4.5f;
        float cx = VoxelWorld.WIDTH * 0.5f + (float)Math.cos(angle) * orbitRadius;
        float cz = VoxelWorld.DEPTH * 0.5f + (float)Math.sin(angle) * orbitRadius;
        float cy = 38.0f + (float)Math.sin(angle * 2.0f) * 2.0f;
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
        if (attackCooldown > 0) return 0;
        attackCooldown = 6;
        health -= damage;
        if (health <= 0) {
            health = 0;
            defeated = true;
            root.setRenderingEnable(false);
        }
        return 1;
    }

    private float yawTo(float dx, float dz) {
        if (dx == 0.0f && dz == 0.0f) return 0.0f;
        double angle;
        if (dz > 0.0f) {
            angle = Math.atan(dx / dz);
        } else if (dz < 0.0f) {
            angle = Math.atan(dx / dz);
            angle += dx >= 0.0f ? Math.PI : -Math.PI;
        } else {
            angle = dx > 0.0f ? Math.PI * 0.5 : -Math.PI * 0.5;
        }
        return (float)(angle * 57.29578);
    }
}
