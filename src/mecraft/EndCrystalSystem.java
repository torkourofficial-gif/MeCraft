package mecraft;

import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/** Obsidian-pillar crystals for the bounded End island. */
final class EndCrystalSystem {
    private final Group[] roots = new Group[InfdevTerrain.endPillarCount()];
    private final Group[] hover = new Group[roots.length];
    private final Group[] rotorX = new Group[roots.length];
    private final Group[] rotorY = new Group[roots.length];
    private final Group[] rotorZ = new Group[roots.length];
    private final boolean[] alive = new boolean[roots.length];
    private int worldSeed = Integer.MIN_VALUE;
    private int tick;

    EndCrystalSystem(Group scene) {
        Appearance bedrock = makeAppearance(0xFF34333C);
        Appearance pink = makeAppearance(0xFFFF4FCB);
        VertexBuffer cube = makeCube();
        TriangleStripArray indices = new TriangleStripArray(0,
                new int[] { 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3 });
        for (int i = 0; i < roots.length; i++) {
            roots[i] = new Group();
            Group pedestal = new Group();
            pedestal.setTranslation(0.0f, 1.25f, 0.0f);
            pedestal.setScale(0.78f, 0.5f, 0.78f);
            pedestal.addChild(new Mesh(cube, indices, bedrock));
            roots[i].addChild(pedestal);

            hover[i] = new Group();
            hover[i].setTranslation(0.0f, 1.75f, 0.0f);
            roots[i].addChild(hover[i]);
            rotorX[i] = new Group();
            rotorY[i] = new Group();
            rotorZ[i] = new Group();
            rotorX[i].addChild(rotorY[i]);
            rotorY[i].addChild(rotorZ[i]);
            rotorZ[i].setScale(0.4f, 0.4f, 0.4f);
            rotorZ[i].addChild(new Mesh(cube, indices, pink));
            hover[i].addChild(rotorX[i]);
            scene.addChild(roots[i]);
            alive[i] = true;
        }
    }

    synchronized void update(VoxelWorld world) {
        if (worldSeed != world.seed) {
            worldSeed = world.seed;
            tick = 0;
            for (int i = 0; i < alive.length; i++) alive[i] = world.endCrystalAlive(i);
        }
        if (world.dimensionId != VoxelWorld.DIM_END) {
            for (int i = 0; i < roots.length; i++) roots[i].setRenderingEnable(false);
            return;
        }
        tick++;
        for (int i = 0; i < roots.length; i++) {
            roots[i].setRenderingEnable(alive[i]);
            if (!alive[i]) continue;
            int top = InfdevTerrain.endPillarTopY(world, i);
            roots[i].setTranslation(InfdevTerrain.endPillarX(i) + 0.5f,
                    top, InfdevTerrain.endPillarZ(i) + 0.5f);
            rotorX[i].setOrientation(tick * 2.7f + i * 19.0f, 1.0f, 0.0f, 0.0f);
            rotorY[i].setOrientation(tick * 3.5f + i * 31.0f, 0.0f, 1.0f, 0.0f);
            rotorZ[i].setOrientation(tick * 2.1f + i * 13.0f, 0.0f, 0.0f, 1.0f);
            hover[i].setTranslation(0.0f, 1.75f +
                    (float)Math.sin((tick + i * 11) * 0.08f) * 0.04f, 0.0f);
        }
    }

    synchronized int hit(VoxelWorld world, float dirX, float dirY, float dirZ) {
        if (world.dimensionId != VoxelWorld.DIM_END) return -1;
        float ox = world.playerX, oy = world.playerY + 1.62f, oz = world.playerZ;
        for (int i = 0; i < roots.length; i++) {
            if (!alive[i]) continue;
            int top = InfdevTerrain.endPillarTopY(world, i);
            float cx = InfdevTerrain.endPillarX(i) + 0.5f;
            float cy = top + 1.75f +
                    (float)Math.sin((tick + i * 11) * 0.08f) * 0.04f;
            float cz = InfdevTerrain.endPillarZ(i) + 0.5f;
            float along = (cx - ox) * dirX + (cy - oy) * dirY + (cz - oz) * dirZ;
            if (along < 0.0f || along > 7.0f) continue;
            float px = ox + dirX * along - cx;
            float py = oy + dirY * along - cy;
            float pz = oz + dirZ * along - cz;
            if (px * px + py * py + pz * pz > 0.20f) continue;
            int steps = (int)(along * 6.0f);
            boolean blocked = false;
            for (int step = 1; step < steps; step++) {
                float t = step / 6.0f;
                if (world.isSolidAt((int)(ox + dirX * t), (int)(oy + dirY * t),
                        (int)(oz + dirZ * t))) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                alive[i] = false;
                world.destroyEndCrystal(i);
                return i;
            }
        }
        return -1;
    }

    private Appearance makeAppearance(int color) {
        Appearance appearance = new Appearance();
        Material material = new Material();
        material.setColor(Material.AMBIENT, color);
        material.setColor(Material.DIFFUSE, color);
        material.setLightingEnable(false);
        if ((color & 0x00FFFFFF) == 0x00FF4FCB)
            material.setColor(Material.EMISSIVE, 0xFFFF4FCB);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        return appearance;
    }

    private VertexBuffer makeCube() {
        short[] corners = new short[] {
            32,-32,32, 32,-32,-32, 32,32,-32, 32,32,32,
            -32,-32,-32, -32,-32,32, -32,32,32, -32,32,-32,
            32,-32,32, -32,-32,32, -32,-32,-32, 32,-32,-32,
            32,32,-32, -32,32,-32, -32,32,32, 32,32,32,
            32,-32,-32, -32,-32,-32, -32,32,-32, 32,32,-32,
            -32,-32,32, 32,-32,32, 32,32,32, -32,32,32
        };
        // Expand each quad into two independent triangles sharing the same
        // v0-v2 diagonal. Separate strips avoid driver-dependent strip winding
        // that can leave a triangular hole on rotating crystals.
        short[] positions = new short[36 * 3];
        int[] cornersInTriangleOrder = new int[] {
            0,1,2, 0,2,3,
            4,5,6, 4,6,7,
            8,9,10, 8,10,11,
            12,13,14, 12,14,15,
            16,17,18, 16,18,19,
            20,21,22, 20,22,23
        };
        int at = 0;
        for (int i = 0; i < cornersInTriangleOrder.length; i++) {
            int source = cornersInTriangleOrder[i] * 3;
            positions[at++] = corners[source];
            positions[at++] = corners[source + 1];
            positions[at++] = corners[source + 2];
        }
        VertexArray array = new VertexArray(36, 3, 2);
        array.set(0, 36, positions);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(array, 1.0f / 64.0f, null);
        return buffer;
    }
}
