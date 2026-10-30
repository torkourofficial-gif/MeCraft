package mecraft;

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

/**
 * Small Alpha-style player model. Geometry is built once and reused; only the
 * root transform and four limb pivots change while the player moves.
 */
final class PlayerModel {
    final Group root = new Group();
    private final Group head = new Group();
    private final Group leftArm = new Group();
    private final Group rightArm = new Group();
    private final Group leftLeg = new Group();
    private final Group rightLeg = new Group();
    private float walkPhase;

    PlayerModel(Image skin) {
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, skin));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        Appearance appearance = makeAppearance(texture);

        // Model coordinates are in blocks. The UV layout is the classic 64x32
        // Alpha/Steve layout used by the project's player skins.
        addBox(root, 0, 0, 8, 8, 8, 8, 8, 8, 0.0f, 1.5f, 0.0f, appearance);
        addBox(root, 16, 16, 8, 12, 4, 8, 12, 4, 0.0f, 0.75f, 0.0f, appearance);

        leftArm.setTranslation(-0.375f, 1.35f, 0.0f);
        rightArm.setTranslation(0.375f, 1.35f, 0.0f);
        leftLeg.setTranslation(-0.125f, 0.75f, 0.0f);
        rightLeg.setTranslation(0.125f, 0.75f, 0.0f);
        root.addChild(leftArm);
        root.addChild(rightArm);
        root.addChild(leftLeg);
        root.addChild(rightLeg);

        addBox(leftArm, 40, 16, 4, 12, 4, 4, 12, 4, 0.0f, -0.375f, 0.0f, appearance);
        addBox(rightArm, 40, 16, 4, 12, 4, 4, 12, 4, 0.0f, -0.375f, 0.0f, appearance);
        addBox(leftLeg, 0, 16, 4, 12, 4, 4, 12, 4, 0.0f, -0.375f, 0.0f, appearance);
        addBox(rightLeg, 0, 16, 4, 12, 4, 4, 4, 12, 4, 0.0f, -0.375f, 0.0f, appearance);
    }

    void update(float x, float y, float z, float yaw, boolean moving, boolean airborne) {
        root.setTranslation(x, y, z);
        root.setOrientation(yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        if (moving) walkPhase += 0.42f;
        float swing = moving ? (float)Math.sin(walkPhase) * 38.0f : 0.0f;
        if (airborne) swing = -28.0f;
        leftLeg.setOrientation(swing, 1.0f, 0.0f, 0.0f);
        rightLeg.setOrientation(-swing, 1.0f, 0.0f, 0.0f);
        leftArm.setOrientation(-swing * 0.72f, 1.0f, 0.0f, 0.0f);
        rightArm.setOrientation(swing * 0.72f, 1.0f, 0.0f, 0.0f);
    }

    private Appearance makeAppearance(Texture2D texture) {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_BACK);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setDepthTestEnable(true);
        compositing.setDepthWriteEnable(true);
        compositing.setAlphaThreshold(0.5f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private void addBox(Group parent, int u, int v, float width, float height, float depth,
            int layoutWidth, int layoutHeight, int layoutDepth,
            float x, float y, float z, Appearance appearance) {
        Group part = new Group();
        part.setTranslation(x, y, z);
        float x0 = -width / 32.0f, x1 = width / 32.0f;
        float y0 = -height / 32.0f, y1 = height / 32.0f;
        float z0 = -depth / 32.0f, z1 = depth / 32.0f;
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
            {u + layoutDepth + layoutWidth, v,
             u + layoutDepth + layoutWidth + layoutWidth, v + layoutDepth},
            {u + layoutDepth, v, u + layoutDepth + layoutWidth, v + layoutDepth},
            {u + layoutDepth, v + layoutDepth,
             u + layoutDepth + layoutWidth, v + layoutDepth + layoutHeight},
            {u + layoutDepth + layoutWidth + layoutDepth, v + layoutDepth,
             u + layoutDepth + layoutWidth + layoutDepth + layoutWidth, v + layoutDepth + layoutHeight}
        };
        int[] order = {0,1,2,0,2,3};
        short[] positions = new short[36 * 3];
        short[] uv = new short[36 * 2];
        int at = 0;
        for (int face = 0; face < 6; face++) {
            int[] rect = rects[face];
            for (int q = 0; q < 6; q++) {
                int corner = order[q];
                float[] p = faces[face][corner];
                positions[at * 3] = (short)(p[0] * 1024.0f);
                positions[at * 3 + 1] = (short)(p[1] * 1024.0f);
                positions[at * 3 + 2] = (short)(p[2] * 1024.0f);
                uv[at * 2] = (short)((corner == 0 || corner == 3) ?
                        rect[2] * 16 - 2 : rect[0] * 16 + 2);
                uv[at * 2 + 1] = (short)(corner < 2 ?
                        rect[3] * 32 - 3 : rect[1] * 32 + 3);
                at++;
            }
        }
        VertexArray pa = new VertexArray(36, 3, 2);
        pa.set(0, 36, positions);
        VertexArray ta = new VertexArray(36, 2, 2);
        ta.set(0, 36, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 1024.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        int[] strips = {3,3,3,3,3,3,3,3,3,3,3,3};
        part.addChild(new Mesh(vb, new TriangleStripArray(0, strips), appearance));
        parent.addChild(part);
    }
}
