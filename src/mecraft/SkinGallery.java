package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.Light;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;
import javax.microedition.m3g.World;

/** Single-model viewer for the classic Indev player-skin layout. */
final class SkinGallery {
    private static final int SKIN_COUNT = 5;
    private final World world = new World();
    private final Camera camera = new Camera();
    private final Group model;
    private final Image[] skinImages = new Image[SKIN_COUNT];
    private final Texture2D[] skinTextures = new Texture2D[SKIN_COUNT];
    private final boolean[] fallback = new boolean[SKIN_COUNT];
    private final Appearance appearance;

    SkinGallery(int width, int height) throws IOException {
        if (width <= 0) width = 240;
        if (height <= 0) height = 200;
        Image defaultSkin = GameAssets.image("char.png");
        int textureWidth = 64;
        int textureHeight = 64;
        for (int i = 0; i < SKIN_COUNT; i++) {
            try {
                skinImages[i] = GameAssets.image("skin" + (i + 1) + ".png");
            } catch (IOException missingSkin) {
                skinImages[i] = defaultSkin;
                fallback[i] = true;
            }
            skinTextures[i] = createSkinTexture(skinImages[i]);
            if (i == 0) {
                textureWidth = skinImages[i].getWidth();
                textureHeight = skinImages[i].getHeight();
            }
        }
        appearance = skinAppearance();
        model = makePlayer(appearance, textureWidth, textureHeight);
        model.setScale(2.0f, 2.0f, 2.0f);
        model.setTranslation(0.0f, -2.0f, 0.0f);
        world.addChild(model);

        camera.setPerspective(54.0f, (float)width / (float)height, 0.1f, 24.0f);
        camera.setTranslation(0.0f, 0.0f, 8.0f);
        world.addChild(camera);
        world.setActiveCamera(camera);

        Background background = new Background();
        background.setColor(0xFF35271C);
        background.setDepthClearEnable(true);
        world.setBackground(background);
        Light light = new Light();
        light.setMode(Light.AMBIENT);
        light.setIntensity(1.0f);
        world.addChild(light);
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        camera.setPerspective(54.0f, (float)width / (float)height, 0.1f, 24.0f);
    }

    void render(Graphics3D graphics, long timeMillis) {
        float angle = (float)((timeMillis % 12000L) * 360.0 / 12000.0);
        model.setOrientation(angle, 0.0f, 1.0f, 0.0f);
        graphics.render(world);
    }

    boolean isFallback(int index) { return fallback[index]; }

    void selectSkin(int index) {
        if (index < 0) index = 0;
        if (index >= SKIN_COUNT) index = SKIN_COUNT - 1;
        appearance.setTexture(0, skinTextures[index]);
    }

    private Texture2D createSkinTexture(Image image) {
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        return texture;
    }

    private Appearance skinAppearance() {
        Appearance appearance = new Appearance();
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setAlphaThreshold(0.5f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private Group makePlayer(Appearance appearance, int textureWidth, int textureHeight) {
        Group body = new Group();
        // The classic 64x32 ModelBiped dimensions and texture rectangles.
        addBox(body, appearance, -4, 24, -4, 8, 8, 8, 0, 0, false, textureWidth, textureHeight);
        addBox(body, appearance, -4, 12, -2, 8, 12, 4, 16, 16, false, textureWidth, textureHeight);
        addBox(body, appearance, -8, 12, -2, 4, 12, 4, 40, 16, false, textureWidth, textureHeight);
        addBox(body, appearance, 4, 12, -2, 4, 12, 4, 40, 16, true, textureWidth, textureHeight);
        addBox(body, appearance, -4, 0, -2, 4, 12, 4, 0, 16, false, textureWidth, textureHeight);
        addBox(body, appearance, 0, 0, -2, 4, 12, 4, 0, 16, true, textureWidth, textureHeight);
        return body;
    }

    /** ModelBox-style six-face UV net, including mirrored left limbs. */
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
