package mecraft;

import java.io.IOException;
import javax.microedition.m3g.Graphics3D;

/**
 * Fixed-seed title scene backed by the real world generator and chunk renderer.
 * The menu uses a creative world that is never saved or modified.
 */
final class MenuDiorama {
    private static final int MENU_SEED = 0x574F4F44; // "WOOD": semilla fija, bioma inicial de pradera
    private final VoxelWorld world;
    private final ChunkRenderer renderer;

    MenuDiorama(int width, int height) throws IOException {
        world = new VoxelWorld(MENU_SEED, 1, VoxelWorld.SIZE_INFINITE,
                VoxelWorld.TYPE_STANDARD);
        // A fixed time and spawn make the title scene reproducible as well.
        world.dayTime = 6000;
        world.yaw = 0.0f;

        renderer = new ChunkRenderer(width, height);
        renderer.setPlayerSkinIndex(0);
        renderer.setPlayerVisible(false); // La cámara conserva el punto de vista, pero no muestra al avatar.
        renderer.setThirdPersonDistance(15.0f); // Vista panorámica: 15 bloques de distancia.
        renderer.setRenderDistance(4); // shortest: stable 3x3 chunk window
        renderer.cycleCameraMode();   // third-person camera; hide first-person hand
        renderer.updateCamera(world, -10.0f);
        renderer.sync(world);
    }

    void render(Graphics3D graphics, int width, int height, int tick) {
        if (width <= 0 || height <= 0) return;
        // Rotate the camera around the fixed spawn point without changing terrain.
        world.yaw = (tick * 0.25f) % 360.0f;
        renderer.updateCamera(world, -10.0f);
        renderer.sync(world);
        renderer.render(graphics);
    }
}
