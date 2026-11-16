package mecraft;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Camera;
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

/** Small M3G skybox cube used by the title screen. */
final class MenuPanorama {
    private static final int FACE_SIZE = 256;
    private final World world = new World();
    private final Camera camera = new Camera();
    private final Group baseRotation = new Group();
    private final Group tilt = new Group();
    private final Group cube = new Group();

    MenuPanorama(Image atlas, int width, int height) {
        if (atlas == null) throw new IllegalArgumentException("Missing skybox");
        int[] cellX = new int[] { 0, 258, 515, 0, 258, 515 };
        int[] cellY = new int[] { 12, 12, 12, 269, 269, 269 };

        for (int face = 0; face < 6; face++) {
            Image image = Image.createImage(atlas, cellX[face], cellY[face],
                    FACE_SIZE, FACE_SIZE, Sprite.TRANS_NONE);
            cube.addChild(makeFace(image, face));
        }
        tilt.addChild(cube);
        baseRotation.addChild(tilt);
        world.addChild(baseRotation);

        camera.setPerspective(120.0f, (float)width / (float)height, 0.05f, 10.0f);
        world.addChild(camera);
        world.setActiveCamera(camera);

        Background background = new Background();
        background.setColor(0xFF000000);
        background.setDepthClearEnable(true);
        world.setBackground(background);
        Light ambient = new Light();
        ambient.setMode(Light.AMBIENT);
        ambient.setIntensity(1.0f);
        world.addChild(ambient);
    }

    void resize(int width, int height) {
        if (width > 0 && height > 0)
            camera.setPerspective(120.0f, (float)width / (float)height, 0.05f, 10.0f);
    }

    void render(Graphics3D graphics, Graphics target, int tick, int width, int height) {
        float timer = (float)tick;
        baseRotation.setOrientation(180.0f, 1.0f, 0.0f, 0.0f);
        cube.setOrientation(timer * 0.1f, 0.0f, 1.0f, 0.0f);
        float pitch = (float)Math.sin(timer / 400.0f) * 25.0f + 20.0f;
        tilt.setOrientation(-pitch, 1.0f, 0.0f, 0.0f);
        graphics.bindTarget(target);
        try {
            graphics.setViewport(0, 0, width, height);
            graphics.render(world);
        } finally {
            graphics.releaseTarget();
        }
    }

    private Mesh makeFace(Image image, int face) {
        short[][] vertices = new short[][] {
            { -1,-1,-1,  1,-1,-1, -1, 1,-1,  1, 1,-1 }, // front
            {  1,-1,-1,  1,-1, 1,  1, 1,-1,  1, 1, 1 }, // right
            {  1,-1, 1, -1,-1, 1,  1, 1, 1, -1, 1, 1 }, // back
            { -1,-1, 1, -1,-1,-1, -1, 1, 1, -1, 1,-1 }, // left
            { -1, 1,-1,  1, 1,-1, -1, 1, 1,  1, 1, 1 }, // up
            { -1,-1, 1,  1,-1, 1, -1,-1,-1,  1,-1,-1 }  // down
        };
        short[] uv = new short[] { 0,256, 256,256, 0,0, 256,0 };

        VertexArray positions = new VertexArray(4, 3, 2);
        positions.set(0, 4, vertices[face]);
        VertexArray texCoords = new VertexArray(4, 2, 2);
        texCoords.set(0, 4, uv);

        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positions, 1.0f, null);
        buffer.setTexCoords(0, texCoords, 1.0f / 256.0f, null);

        Appearance appearance = new Appearance();
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);

        Texture2D texture = new Texture2D(new Image2D(Image2D.RGB, image));
        texture.setFiltering(Texture2D.FILTER_LINEAR, Texture2D.FILTER_LINEAR);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        appearance.setTexture(0, texture);
        return new Mesh(buffer, new TriangleStripArray(0, new int[] { 4 }), appearance);
    }
}
