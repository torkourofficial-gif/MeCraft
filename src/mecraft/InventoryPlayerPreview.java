package mecraft;

import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Light;
import javax.microedition.m3g.World;

/** Small M3G player preview rendered into the black portrait area of inventory.png. */
final class InventoryPlayerPreview {
    private final World world = new World();
    private final Camera camera = new Camera();
    private final PlayerModel player;

    InventoryPlayerPreview(Image skin, int width, int height) {
        player = new PlayerModel(skin);
        player.root.setTranslation(0.0f, -1.0f, 0.0f);
        world.addChild(player.root);
        camera.setPerspective(42.0f, (float)width / (float)height, 0.1f, 16.0f);
        camera.setTranslation(0.0f, 0.0f, 4.2f);
        world.addChild(camera);
        world.setActiveCamera(camera);
        Background background = new Background();
        background.setColor(0xFF000000);
        background.setDepthClearEnable(true);
        world.setBackground(background);
        Light light = new Light();
        light.setMode(Light.AMBIENT);
        light.setIntensity(1.0f);
        world.addChild(light);
        player.update(0.0f, -1.0f, 0.0f, -22.0f, 0.0f, 0.0f,
                0.0f, 1.0f, false, false, true);
    }

    void resize(int width, int height) {
        if (width > 0 && height > 0)
            camera.setPerspective(42.0f, (float)width / (float)height, 0.1f, 16.0f);
    }

    void render(Graphics3D graphics) {
        graphics.render(world);
    }
}
