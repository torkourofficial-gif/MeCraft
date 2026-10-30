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
 * Animated in-game player using the exact ModelBiped box dimensions and UV
 * mapping from SkinGallery, so gameplay and the skin preview share one layout.
 */
final class PlayerModel {
    final Group root = new Group();
    private final Group leftArm = new Group();
    private final Group rightArm = new Group();
    private final Group leftLeg = new Group();
    private final Group rightLeg = new Group();
    private float walkPhase;
    private int actionSwingTicks = 8;

    PlayerModel(Image skin) {
        int textureWidth = skin.getWidth();
        int textureHeight = skin.getHeight();
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, skin));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        Appearance appearance = makeAppearance(texture);

        // Head and torso use the same coordinates and UV rectangles as SkinGallery.
        addBox(root, appearance, -4, 24, -4, 8, 8, 8, 0, 0, false,
                textureWidth, textureHeight);
        addBox(root, appearance, -4, 12, -2, 8, 12, 4, 16, 16, false,
                textureWidth, textureHeight);

        // Pivot the limbs at their shoulders/hips; box geometry is local to each pivot.
        addLimb(leftArm, -6, 24, -2, -2, -12, -2, 4, 12, 4,
                40, 16, false, appearance, textureWidth, textureHeight);
        addLimb(rightArm, 6, 24, -2, -2, -12, -2, 4, 12, 4,
                40, 16, true, appearance, textureWidth, textureHeight);
        addLimb(leftLeg, -2, 12, -2, -2, -12, -2, 4, 12, 4,
                0, 16, false, appearance, textureWidth, textureHeight);
        addLimb(rightLeg, 2, 12, -2, -2, -12, -2, 4, 12, 4,
                0, 16, true, appearance, textureWidth, textureHeight);

        root.addChild(leftArm);
        root.addChild(rightArm);
        root.addChild(leftLeg);
        root.addChild(rightLeg);
    }

    void update(float x, float y, float z, float yaw, boolean moving, boolean airborne) {
        root.setTranslation(x, y, z);
        // The skin mesh's front is already aligned with the camera yaw.
        root.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
        if (moving) walkPhase += 0.42f;
        float swing = moving ? (float)Math.sin(walkPhase) * 38.0f : 0.0f;
        if (airborne) swing = -28.0f;
        leftLeg.setOrientation(swing, 1.0f, 0.0f, 0.0f);
        rightLeg.setOrientation(-swing, 1.0f, 0.0f, 0.0f);
        float actionSwing = 0.0f;
        if (actionSwingTicks < 8) {
            float progress = actionSwingTicks / 8.0f;
            actionSwing = (float)Math.sin(progress * Math.PI) * 82.0f;
        }
        leftArm.setOrientation(-swing * 0.72f + actionSwing * 0.18f,
                1.0f, 0.0f, 0.0f);
        rightArm.setOrientation(swing * 0.72f - actionSwing,
                1.0f, 0.0f, 0.0f);
    }

    void swingAction() {
        actionSwingTicks = 0;
    }

    void tickActionAnimation() {
        if (actionSwingTicks < 8) actionSwingTicks++;
    }

    private void addLimb(Group limb, int pivotX, int pivotY, int pivotZ,
            int x, int y, int z, int width, int height, int depth,
            int texU, int texV, boolean mirror, Appearance appearance,
            int textureWidth, int textureHeight) {
        limb.setTranslation(pivotX / 16.0f, pivotY / 16.0f, pivotZ / 16.0f);
        addBox(limb, appearance, x, y, z, width, height, depth,
                texU, texV, mirror, textureWidth, textureHeight);
    }

    private Appearance makeAppearance(Texture2D texture) {
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
        compositing.setDepthTestEnable(true);
        compositing.setDepthWriteEnable(true);
        compositing.setAlphaThreshold(0.5f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    /** Same six-face ModelBox mesh and mirrored UV handling as SkinGallery. */
    private void addBox(Group parent, Appearance appearance,
            int x, int y, int z, int width, int height, int depth,
            int texU, int texV, boolean mirror, int textureWidth, int textureHeight) {
        short[] positions = new short[72];
        short[] uv = new short[48];
        int[][] vertices = new int[][] {
            {x,y,z+depth, x,y+height,z+depth, x+width,y,z+depth, x+width,y+height,z+depth},
            {x+width,y,z, x+width,y+height,z, x,y,z, x,y+height,z},
            {x,y,z, x,y+height,z, x,y,z+depth, x,y+height,z+depth},
            {x+width,y,z+depth, x+width,y+height,z+depth, x+width,y,z, x+width,y+height,z},
            {x,y+height,z+depth, x,y+height,z, x+width,y+height,z+depth, x+width,y+height,z},
            {x,y,z, x,y,z+depth, x+width,y,z, x+width,y,z+depth}
        };
        int[][] rects = new int[][] {
            {texU + depth, texV + depth, width, height},
            {texU + depth + width + depth, texV + depth, width, height},
            {texU, texV + depth, depth, height},
            {texU + depth + width, texV + depth, depth, height},
            {texU + depth, texV, width, depth},
            {texU + depth + width, texV, width, depth}
        };
        for (int face = 0; face < 6; face++) {
            int[] rect = rects[face];
            int u0 = rect[0], v0 = rect[1], u1 = u0 + rect[2], v1 = v0 + rect[3];
            if (mirror) { int swap = u0; u0 = u1; u1 = swap; }
            int base = face * 4;
            int[] faceUV = new int[] { u0,v1, u0,v0, u1,v1, u1,v0 };
            int vScale = textureWidth / textureHeight;
            for (int vertex = 0; vertex < 4; vertex++) {
                int pi = (base + vertex) * 3;
                positions[pi] = (short)vertices[face][vertex * 3];
                positions[pi + 1] = (short)vertices[face][vertex * 3 + 1];
                positions[pi + 2] = (short)vertices[face][vertex * 3 + 2];
                int ti = (base + vertex) * 2;
                uv[ti] = (short)faceUV[vertex * 2];
                uv[ti + 1] = (short)(faceUV[vertex * 2 + 1] * vScale);
            }
        }
        VertexArray positionArray = new VertexArray(24, 3, 2);
        positionArray.set(0, 24, positions);
        VertexArray uvArray = new VertexArray(24, 2, 2);
        uvArray.set(0, 24, uv);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positionArray, 1.0f / 16.0f, null);
        buffer.setTexCoords(0, uvArray, 1.0f / textureWidth, null);
        int[] strips = new int[] { 4,4,4,4,4,4 };
        parent.addChild(new Mesh(buffer, new TriangleStripArray(0, strips), appearance));
    }
}
