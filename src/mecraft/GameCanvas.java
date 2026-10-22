package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.m3g.Graphics3D;

/** Game loop, phone controls, M3G view and compact HUD. */
final class GameCanvas extends Canvas implements Runnable, CommandListener {
    private static final int MENU = 0;
    private static final int PLAYING = 1;
    private static final int PAUSED = 2;
    private static final int ERROR = 3;
    private static final int MENU_CONTINUE = 0;
    private static final int MENU_SURVIVAL = 1;
    private static final int MENU_CREATIVE = 2;
    private static final int MENU_CONTROLS = 3;
    private static final int MENU_EXIT = 4;
    private static final float PLAYER_WIDTH = 0.6f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_RADIUS = PLAYER_WIDTH * 0.5f;
    private static final float PLAYER_EYE_HEIGHT = 1.62f;

    private final MeCraft app;
    private final boolean[] keys = new boolean[10];
    private final int[] hotbar = new int[] {
        Block.DIRT, Block.STONE, Block.LOG, Block.SAND, Block.PLANKS,
        Block.LEAVES, Block.COAL, Block.IRON, Block.GRASS
    };
    private final int[] inventory = new int[hotbar.length];
    private final Command mineCommand = new Command("Romper", Command.SCREEN, 1);
    private final Command placeCommand = new Command("Colocar", Command.SCREEN, 2);
    private final Command pauseCommand = new Command("Pausa", Command.SCREEN, 3);
    private final Command selectCommand = new Command("Elegir", Command.OK, 1);
    private final Command exitCommand = new Command("Salir", Command.EXIT, 9);

    private volatile boolean running;
    private volatile boolean visible;
    private volatile int state = MENU;
    private int menuSelection = MENU_SURVIVAL;
    private int pauseSelection;
    private boolean selectCommandAttached = true;
    private boolean hasSavedWorld;
    private float verticalVelocity;
    private float fallDistance;
    private float cameraPitch = -5.0f;
    private boolean lookUp, lookDown, lookLeft, lookRight;
    private VoxelWorld world;
    private ChunkRenderer renderer;
    private Image title;
    private Image guiTexture;
    private Image iconsTexture;
    private final Image[] blockIcons = new Image[Block.COUNT];
    private String errorMessage;
    private boolean diagnosticError;
    private int diagnosticScroll;

    GameCanvas(MeCraft app) {
        this.app = app;
        setFullScreenMode(true);
        setCommandListener(this);
        loadTitle();
        loadHudTextures();
        hasSavedWorld = VoxelWorld.hasSave();
        menuSelection = hasSavedWorld ? MENU_CONTINUE : MENU_SURVIVAL;
        addCommand(selectCommand);
        addCommand(exitCommand);
    }

    void start() {
        if (running) return;
        running = true;
        visible = true;
        Thread loop = new Thread(this);
        loop.start();
    }

    synchronized void stop() {
        running = false;
        saveIfNeeded();
    }

    synchronized void pauseGame() {
        visible = false;
        clearKeys();
        saveIfNeeded();
    }

    void resumeGame() {
        visible = true;
    }

    public void run() {
        while (running) {
            long start = System.currentTimeMillis();
            if (visible && state == PLAYING) updateGame();
            if (visible) repaint();
            long elapsed = System.currentTimeMillis() - start;
            long wait = 50L - elapsed;
            if (wait < 8L) wait = 8L;
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
        }
    }

    private synchronized void updateGame() {
        if (!visible || state != PLAYING || world == null || renderer == null) return;
        if (lookLeft) world.yaw -= 3.0f;
        if (lookRight) world.yaw += 3.0f;
        if (world.yaw < 0.0f) world.yaw += 360.0f;
        if (world.yaw >= 360.0f) world.yaw -= 360.0f;
        if (lookUp) cameraPitch += 2.5f;
        if (lookDown) cameraPitch -= 2.5f;
        if (cameraPitch > 80.0f) cameraPitch = 80.0f;
        if (cameraPitch < -80.0f) cameraPitch = -80.0f;

        float forward = 0.0f;
        float strafe = 0.0f;
        if (keys[2]) forward += 1.0f;
        if (keys[8]) forward -= 1.0f;
        if (keys[6]) strafe += 1.0f;
        if (keys[4]) strafe -= 1.0f;
        if (forward != 0.0f || strafe != 0.0f) {
            float length = (float)Math.sqrt(forward * forward + strafe * strafe);
            float speed = world.mode == 1 ? 0.145f : 0.105f;
            forward = forward / length * speed;
            strafe = strafe / length * speed;
            double radians = world.yaw * Math.PI / 180.0;
            float dx = (float)Math.sin(radians) * forward - (float)Math.cos(radians) * strafe;
            float dz = (float)Math.cos(radians) * forward + (float)Math.sin(radians) * strafe;
            tryMove(dx, dz);
        }

        verticalVelocity -= 0.012f;
        if (verticalVelocity < -0.22f) verticalVelocity = -0.22f;
        float nextY = world.playerY + verticalVelocity;
        if (verticalVelocity < 0.0f) fallDistance -= verticalVelocity;
        if (verticalVelocity < 0.0f && collides(world.playerX, nextY, world.playerZ)) {
            if (world.mode == 0 && fallDistance > 3.0f) {
                int damage = (int)(fallDistance - 3.0f);
                world.health -= damage;
                if (world.health < 0) world.health = 0;
                if (world.health == 0) respawn();
            }
            fallDistance = 0.0f;
            verticalVelocity = 0.0f;
            world.playerY = floorBlock(world.playerY);
        } else if (verticalVelocity > 0.0f && collides(world.playerX, nextY, world.playerZ)) {
            verticalVelocity = 0.0f;
        } else {
            world.playerY = nextY;
        }
        if (world.playerY < 1.0f) {
            world.playerX = VoxelWorld.WIDTH / 2 + 0.5f;
            world.playerZ = VoxelWorld.DEPTH / 2 + 0.5f;
            world.playerY = world.surfaceY(VoxelWorld.WIDTH / 2, VoxelWorld.DEPTH / 2) + 1.0f;
            verticalVelocity = 0.0f;
            fallDistance = 0.0f;
        }
        renderer.updateCamera(world, cameraPitch);
        renderer.updateParticles(world.yaw, cameraPitch);
    }

    private void tryMove(float dx, float dz) {
        float nx = world.playerX + dx;
        if (nx > 1.0f && nx < VoxelWorld.WIDTH - 1.0f && !collides(nx, world.playerY, world.playerZ)) world.playerX = nx;
        float nz = world.playerZ + dz;
        if (nz > 1.0f && nz < VoxelWorld.DEPTH - 1.0f && !collides(world.playerX, world.playerY, nz)) world.playerZ = nz;
    }

    private boolean collides(float x, float y, float z) {
        int minX = floorBlock(x - PLAYER_RADIUS + 0.001f);
        int maxX = floorBlock(x + PLAYER_RADIUS - 0.001f);
        int minZ = floorBlock(z - PLAYER_RADIUS + 0.001f);
        int maxZ = floorBlock(z + PLAYER_RADIUS - 0.001f);
        int minY = floorBlock(y + 0.001f);
        int maxY = floorBlock(y + PLAYER_HEIGHT - 0.001f);
        for (int by = minY; by <= maxY; by++) {
            if (Block.solid(world.get(minX, by, minZ)) || Block.solid(world.get(maxX, by, minZ)) ||
                Block.solid(world.get(minX, by, maxZ)) || Block.solid(world.get(maxX, by, maxZ))) return true;
        }
        return false;
    }

    private void jump() {
        if (state != PLAYING || verticalVelocity != 0.0f) return;
        if (collides(world.playerX, world.playerY - 0.01f, world.playerZ)) verticalVelocity = 0.20f;
    }

    private void respawn() {
        world.playerX = VoxelWorld.WIDTH / 2 + 0.5f;
        world.playerZ = VoxelWorld.DEPTH / 2 + 0.5f;
        world.playerY = world.surfaceY(VoxelWorld.WIDTH / 2, VoxelWorld.DEPTH / 2) + 1.0f;
        world.health = 20;
        verticalVelocity = 0.0f;
        fallDistance = 0.0f;
    }

    private void startNewWorld(int mode) {
        String stage = "preparando mundo";
        try {
            title = null;
            System.gc();
            if (renderer != null) {
                renderer = null;
                System.gc();
            }
            world = new VoxelWorld((int)(System.currentTimeMillis() ^ System.currentTimeMillis() >>> 32), mode);
            fallDistance = 0.0f;
            resetInventory(mode);
            copyInventoryToWorld();
            stage = "cargando /textures.png y creando escena M3G";
            renderer = new ChunkRenderer(getWidth(), getHeight());
            stage = "construyendo mallas M3G para el terreno";
            renderer.sync(world);
            stage = "configurando cámara M3G";
            renderer.updateCamera(world, cameraPitch);
            state = PLAYING;
            removeSelectCommand();
            addCommand(mineCommand);
            addCommand(placeCommand);
            addCommand(pauseCommand);
            repaint();
        } catch (OutOfMemoryError exhausted) {
            showError("No hay memoria suficiente para crear el mundo. Libera memoria y vuelve a intentarlo.");
        } catch (Throwable problem) {
            showDiagnostic(stage, problem);
        }
    }

    private void continueWorld() {
        title = null;
        System.gc();
        VoxelWorld saved = VoxelWorld.load();
        if (saved == null) {
            startNewWorld(0);
            return;
        }
        String stage = "restaurando partida guardada";
        try {
            if (renderer != null) { renderer = null; System.gc(); }
            world = saved;
            world.health = clamp(world.health, 0, 20);
            fallDistance = 0.0f;
            resetInventory(world.mode);
            for (int i = 0; i < inventory.length; i++) inventory[i] = world.inventory[i];
            world.selectedSlot = clamp(world.selectedSlot, 0, inventory.length - 1);
            stage = "cargando /textures.png y creando escena M3G";
            renderer = new ChunkRenderer(getWidth(), getHeight());
            stage = "construyendo mallas M3G del mundo guardado";
            renderer.sync(world);
            stage = "configurando cámara M3G";
            renderer.updateCamera(world, cameraPitch);
            state = PLAYING;
            removeSelectCommand();
            addCommand(mineCommand);
            addCommand(placeCommand);
            addCommand(pauseCommand);
        } catch (OutOfMemoryError exhausted) {
            showError("No hay memoria suficiente para cargar el mundo guardado.");
        } catch (Throwable problem) {
            showDiagnostic(stage, problem);
        }
    }

    private void resetInventory(int mode) {
        for (int i = 0; i < inventory.length; i++) inventory[i] = mode == 1 ? 64 : 0;
        if (mode == 0) {
            inventory[0] = 16;
            inventory[2] = 4;
            inventory[3] = 8;
            inventory[4] = 8;
        }
    }

    private void showError(String message) {
        errorMessage = message;
        diagnosticError = false;
        diagnosticScroll = 0;
        state = ERROR;
        removeCommand(mineCommand);
        removeCommand(placeCommand);
        removeCommand(pauseCommand);
        addSelectCommand();
    }

    private void showDiagnostic(String stage, Throwable problem) {
        String message = problem.getMessage();
        if (message == null || message.length() == 0) message = "(sin mensaje)";
        String m3gVersion = readProperty("microedition.m3g.version");
        String requiredProfile = readAppProperty("MicroEdition-Profile");
        String runtimeProfiles = readProperty("microedition.profiles");
        String requiredConfig = readAppProperty("MicroEdition-Configuration");
        String runtimeConfig = readProperty("microedition.configuration");
        errorMessage = "Etapa: " + stage + " | Tipo: " + problem.getClass().getName() +
                       " | Mensaje: " + message + " | M3G runtime: " + m3gVersion +
                       " | Perfil solicitado por la app (JAD): " + requiredProfile +
                       " | Perfiles que soporta el emulador: " + runtimeProfiles +
                       " | Config solicitada (JAD): " + requiredConfig +
                       " | Config que soporta el emulador: " + runtimeConfig;
        diagnosticError = true;
        diagnosticScroll = 0;
        state = ERROR;
        removeCommand(mineCommand);
        removeCommand(placeCommand);
        removeCommand(pauseCommand);
        addSelectCommand();
    }

    private String readProperty(String name) {
        try {
            String value = System.getProperty(name);
            return value == null ? "no informado" : value;
        } catch (Throwable ignored) {
            return "no disponible";
        }
    }

    private String readAppProperty(String name) {
        try {
            String value = app.getAppProperty(name);
            return value == null ? "no informado" : value;
        } catch (Throwable ignored) {
            return "no disponible";
        }
    }

    private void addSelectCommand() {
        if (!selectCommandAttached) {
            addCommand(selectCommand);
            selectCommandAttached = true;
        }
    }

    private void removeSelectCommand() {
        if (selectCommandAttached) {
            removeCommand(selectCommand);
            selectCommandAttached = false;
        }
    }

    private void copyInventoryToWorld() {
        if (world == null) return;
        for (int i = 0; i < inventory.length; i++) world.inventory[i] = inventory[i];
    }

    private void saveIfNeeded() {
        if (world != null && state != MENU && state != ERROR) {
            copyInventoryToWorld();
            world.save();
            hasSavedWorld = VoxelWorld.hasSave();
        }
    }

    private void mineTarget() {
        if (world == null) return;
        int[] target = trace(5.0f);
        if (target == null) return;
        byte id = world.get(target[0], target[1], target[2]);
        if (id == Block.AIR || id == Block.BEDROCK || id == Block.WATER) return;
        if (world.mode == 0) {
            int slot = findSlot(id);
            if (slot >= 0 && inventory[slot] < 64) inventory[slot]++;
        }
        renderer.spawnBlockDestroy(target[0], target[1], target[2], Block.sideTile(id));
        world.set(target[0], target[1], target[2], Block.AIR);
        renderer.sync(world);
    }

    private void placeBlock() {
        if (world == null) return;
        int[] target = trace(5.0f);
        if (target == null || target[3] == Integer.MIN_VALUE) return;
        int x = target[0] + target[3];
        int y = target[1] + target[4];
        int z = target[2] + target[5];
        if (y <= 0 || y >= VoxelWorld.HEIGHT) return;
        float px = world.playerX, py = world.playerY, pz = world.playerZ;
        if (x + 1.0f > px - PLAYER_RADIUS && x < px + PLAYER_RADIUS &&
            z + 1.0f > pz - PLAYER_RADIUS && z < pz + PLAYER_RADIUS &&
            y + 1.0f > py && y < py + PLAYER_HEIGHT) return;
        int slot = world.selectedSlot;
        if (world.mode == 0 && inventory[slot] <= 0) return;
        if (world.get(x, y, z) != Block.AIR && world.get(x, y, z) != Block.WATER) return;
        if (!world.set(x, y, z, (byte)hotbar[slot])) return;
        if (world.mode == 0) inventory[slot]--;
        renderer.sync(world);
    }

    /** Short ray march; returns hit voxel and the previous empty voxel offset. */
    private int[] trace(float reach) {
        double yaw = world.yaw * Math.PI / 180.0;
        double pitch = cameraPitch * Math.PI / 180.0;
        float ox = world.playerX;
        float oy = world.playerY + PLAYER_EYE_HEIGHT;
        float oz = world.playerZ;
        int oldX = floorBlock(ox), oldY = floorBlock(oy), oldZ = floorBlock(oz);
        for (float d = 0.35f; d <= reach; d += 0.12f) {
            float x = ox + (float)(Math.sin(yaw) * Math.cos(pitch) * d);
            float y = oy + (float)(Math.sin(pitch) * d);
            float z = oz + (float)(Math.cos(yaw) * Math.cos(pitch) * d);
            int bx = floorBlock(x), by = floorBlock(y), bz = floorBlock(z);
            byte id = world.get(bx, by, bz);
            if (id != Block.AIR && id != Block.WATER) {
                int[] hit = new int[6];
                hit[0] = bx; hit[1] = by; hit[2] = bz;
                hit[3] = oldX - bx; hit[4] = oldY - by; hit[5] = oldZ - bz;
                return hit;
            }
            oldX = bx; oldY = by; oldZ = bz;
        }
        return null;
    }

    private int findSlot(byte id) {
        for (int i = 0; i < hotbar.length; i++) if (hotbar[i] == id) return i;
        return -1;
    }

    /** Java ME compatible floor conversion, including negative coordinates. */
    private int floorBlock(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    public synchronized void keyPressed(int keyCode) {
        if (state == ERROR && diagnosticError) {
            int action = gameAction(keyCode);
            if (action == UP || keyCode == KEY_NUM2) {
                if (diagnosticScroll > 0) diagnosticScroll--;
                repaint();
            } else if (action == DOWN || keyCode == KEY_NUM8) {
                int max = Math.max(12, getWidth() / Math.max(5, Font.getDefaultFont().charWidth('M')));
                int total = wrappedLineCount(errorMessage, max);
                int visible = Math.max(1, (getHeight() - 58) / (Font.getDefaultFont().getHeight() + 2));
                if (diagnosticScroll < total - visible) diagnosticScroll++;
                repaint();
            } else if (action == FIRE || keyCode == KEY_NUM5 || keyCode == KEY_NUM0) {
                backToMenu();
            }
            return;
        }
        if (state == MENU || state == PAUSED || state == ERROR) {
            int action = gameAction(keyCode);
            if (action == UP || keyCode == KEY_NUM2) moveSelection(-1);
            else if (action == DOWN || keyCode == KEY_NUM8) moveSelection(1);
            else if (action == FIRE || keyCode == KEY_NUM5) activateSelection();
            else if (keyCode == KEY_NUM0) backToMenu();
            return;
        }
        if (state != PLAYING) return;
        if (keyCode == KEY_NUM2) keys[2] = true;
        else if (keyCode == KEY_NUM8) keys[8] = true;
        else if (keyCode == KEY_NUM4) keys[4] = true;
        else if (keyCode == KEY_NUM6) keys[6] = true;
        else {
            int action = gameAction(keyCode);
            if (action == UP) lookUp = true;
            else if (action == DOWN) lookDown = true;
            else if (action == LEFT) lookLeft = true;
            else if (action == RIGHT) lookRight = true;
        }
        if (keyCode == KEY_NUM5) jump();
        if (keyCode == KEY_NUM1) mineTarget();
        if (keyCode == KEY_NUM3) placeBlock();
        if (keyCode == KEY_NUM7) cycleSlot(-1);
        if (keyCode == KEY_NUM9) cycleSlot(1);
        if (keyCode == KEY_STAR) openPause();
    }

    public synchronized void keyReleased(int keyCode) {
        if (keyCode == KEY_NUM2) keys[2] = false;
        else if (keyCode == KEY_NUM8) keys[8] = false;
        else if (keyCode == KEY_NUM4) keys[4] = false;
        else if (keyCode == KEY_NUM6) keys[6] = false;
        else {
            int action = gameAction(keyCode);
            if (action == UP) lookUp = false;
            else if (action == DOWN) lookDown = false;
            else if (action == LEFT) lookLeft = false;
            else if (action == RIGHT) lookRight = false;
        }
    }

    private void moveSelection(int delta) {
        if (state == MENU) {
            int min = VoxelWorldHasSave() ? MENU_CONTINUE : MENU_SURVIVAL;
            menuSelection += delta;
            if (menuSelection < min) menuSelection = MENU_EXIT;
            if (menuSelection > MENU_EXIT) menuSelection = min;
        } else if (state == PAUSED) {
            pauseSelection += delta;
            if (pauseSelection < 0) pauseSelection = 2;
            if (pauseSelection > 2) pauseSelection = 0;
        }
    }

    private boolean VoxelWorldHasSave() { return hasSavedWorld; }

    private void activateSelection() {
        if (state == MENU) {
            if (menuSelection == MENU_CONTINUE) continueWorld();
            else if (menuSelection == MENU_SURVIVAL) startNewWorld(0);
            else if (menuSelection == MENU_CREATIVE) startNewWorld(1);
            else if (menuSelection == MENU_CONTROLS) {
                errorMessage = "Controles: cruceta mira alrededor; 2/8 avanzar y retroceder; 4/6 desplazarse; 5 saltar; 1 romper; 3 colocar; 7/9 elegir bloque; * pausa.";
                state = ERROR;
            }
            else if (menuSelection == MENU_EXIT) app.closeGame();
        } else if (state == PAUSED) {
            if (pauseSelection == 0) { state = PLAYING; }
            else if (pauseSelection == 1) { saveIfNeeded(); backToMenu(); }
            else { saveIfNeeded(); app.closeGame(); }
        } else if (state == ERROR) {
            if (errorMessage != null && errorMessage.indexOf("Controles") >= 0) state = MENU;
            else backToMenu();
        }
    }

    private void openPause() {
        if (state == PLAYING) {
            state = PAUSED;
            pauseSelection = 0;
            clearKeys();
            saveIfNeeded();
        }
    }

    private void backToMenu() {
        saveIfNeeded();
        state = MENU;
        menuSelection = VoxelWorldHasSave() ? MENU_CONTINUE : MENU_SURVIVAL;
        removeCommand(mineCommand);
        removeCommand(placeCommand);
        removeCommand(pauseCommand);
        addSelectCommand();
        clearKeys();
        renderer = null;
        world = null;
        System.gc();
        loadTitle();
    }

    private void loadTitle() {
        try { title = Image.createImage("/mecraft/title.png"); } catch (IOException ignored) { title = null; }
    }

    private void loadHudTextures() {
        try { guiTexture = Image.createImage("/mecraft/gui.png"); } catch (IOException ignored) { guiTexture = null; }
        try { iconsTexture = Image.createImage("/mecraft/icons.png"); } catch (IOException ignored) { iconsTexture = null; }
        Image source = null;
        try {
            source = Image.createImage("/mecraft/gui_blocks.png");
            int[] sourcePixels = new int[48 * 48];
            int[] iconPixels = new int[16 * 16];
            for (int i = 0; i < hotbar.length; i++) {
                int block = hotbar[i];
                int tile = Block.inventoryIconTile((byte)block);
                source.getRGB(sourcePixels, 0, 48, (tile % 10) * 48, (tile / 10) * 48, 48, 48);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) iconPixels[y * 16 + x] = sourcePixels[(y * 3 + 1) * 48 + x * 3 + 1];
                }
                blockIcons[block] = Image.createRGBImage(iconPixels, 16, 16, true);
            }
        } catch (Throwable unavailableAtlas) {
            for (int i = 0; i < blockIcons.length; i++) blockIcons[i] = null;
        }
        source = null;
        System.gc();
    }

    private void clearKeys() {
        for (int i = 0; i < keys.length; i++) keys[i] = false;
        lookUp = lookDown = lookLeft = lookRight = false;
    }

    private void cycleSlot(int delta) {
        world.selectedSlot = (world.selectedSlot + delta + hotbar.length) % hotbar.length;
    }

    private int gameAction(int keyCode) {
        try { return getGameAction(keyCode); } catch (IllegalArgumentException ignored) { return 0; }
    }

    public synchronized void commandAction(Command command, javax.microedition.lcdui.Displayable displayable) {
        if (command == exitCommand) {
            if (state == PLAYING) openPause(); else app.closeGame();
        } else if (command == selectCommand) {
            if (state == MENU) activateSelection(); else backToMenu();
        } else if (command == mineCommand) mineTarget();
        else if (command == placeCommand) placeBlock();
        else if (command == pauseCommand) openPause();
    }

    protected synchronized void sizeChanged(int width, int height) {
        if (renderer != null) renderer.resize(width, height);
    }

    protected synchronized void paint(Graphics g) {
        if (state == PLAYING || state == PAUSED) {
            String stage = "actualizando mallas M3G";
            try {
                renderer.sync(world);
                stage = "obteniendo contexto Graphics3D";
                Graphics3D graphics3D = Graphics3D.getInstance();
                stage = "enlazando Graphics del Canvas a M3G";
                graphics3D.bindTarget(g);
                try {
                    stage = "configurando viewport M3G";
                    graphics3D.setViewport(0, 0, getWidth(), getHeight());
                    stage = "dibujando World M3G";
                    graphics3D.render(renderer.scene);
                }
                finally { graphics3D.releaseTarget(); }
                stage = "dibujando HUD 2D";
                drawHud(g);
                if (state == PAUSED) drawPause(g);
            } catch (OutOfMemoryError exhausted) {
                showError("M3G agotó el heap. El diseño apunta a móviles con heap total de 4 MiB o más.");
                drawError(g);
            } catch (Throwable problem) {
                showDiagnostic(stage, problem);
                drawError(g);
            }
        } else if (state == ERROR) drawError(g);
        else drawMenu(g);
    }

    private void drawMenu(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(18, 34, 48); g.fillRect(0, 0, w, h);
        if (title != null) g.drawImage(title, w / 2, 3, Graphics.TOP | Graphics.HCENTER);
        g.setColor(255, 255, 255);
        Font f = Font.getDefaultFont();
        int y = h / 2 + 12;
        if (VoxelWorldHasSave()) {
            drawMenuItem(g, "Continuar", y, menuSelection == MENU_CONTINUE); y += f.getHeight() + 4;
        }
        drawMenuItem(g, "Nuevo mundo: Supervivencia", y, menuSelection == MENU_SURVIVAL); y += f.getHeight() + 4;
        drawMenuItem(g, "Nuevo mundo: Creativo", y, menuSelection == MENU_CREATIVE); y += f.getHeight() + 4;
        drawMenuItem(g, "Controles", y, menuSelection == MENU_CONTROLS); y += f.getHeight() + 4;
        drawMenuItem(g, "Salir", y, menuSelection == MENU_EXIT);
        g.setColor(225, 225, 225);
        g.drawString("MECRAFT  |  Java ME 3D", w / 2, h - 8, Graphics.BOTTOM | Graphics.HCENTER);
    }

    private void drawMenuItem(Graphics g, String label, int y, boolean selected) {
        int w = getWidth();
        if (selected) {
            g.setColor(36, 81, 112);
            g.fillRoundRect(5, y - 1, w - 10, Font.getDefaultFont().getHeight() + 2, 5, 5);
        }
        g.setColor(selected ? 255 : 220, selected ? 224 : 220, selected ? 92 : 220);
        g.drawString((selected ? "> " : "  ") + label, w / 2, y, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawHud(Graphics g) {
        int w = getWidth(), h = getHeight();
        int centerX = w / 2, centerY = h / 2;
        g.setColor(255, 255, 255);
        g.drawLine(centerX - 4, centerY, centerX + 4, centerY);
        g.drawLine(centerX, centerY - 4, centerX, centerY + 4);
        if (world.mode == 0) drawHearts(g);
        int left = w / 2 - 91;
        int y = h - 22;
        if (guiTexture != null && guiTexture.getWidth() >= 182 && guiTexture.getHeight() >= 44) {
            g.drawRegion(guiTexture, 0, 0, 182, 22, Sprite.TRANS_NONE, left, y, Graphics.TOP | Graphics.LEFT);
            g.drawRegion(guiTexture, 0, 22, 24, 22, Sprite.TRANS_NONE, left - 1 + world.selectedSlot * 20,
                         y - 1, Graphics.TOP | Graphics.LEFT);
        }
        for (int i = 0; i < 9; i++) {
            int x = left + 2 + i * 20;
            int id = hotbar[i];
            Image icon = blockIcons[id];
            if (icon != null) g.drawImage(icon, x, y + 3, Graphics.TOP | Graphics.LEFT);
            else {
                g.setColor(blockColor(id));
                g.fillRect(x + 2, y + 4, 14, 14);
            }
            if (world.mode == 0 && inventory[i] > 0) {
                g.setColor(255, 255, 255);
                g.drawString(Integer.toString(inventory[i]), x + 17, y + 20, Graphics.RIGHT | Graphics.BOTTOM);
            }
        }
        g.drawString(world.mode == 0 ? "Supervivencia" : "Creativo", 3, 2, Graphics.TOP | Graphics.LEFT);
    }

    private void drawHearts(Graphics g) {
        int y = getHeight() - 32;
        for (int i = 0; i < 10; i++) {
            int x = getWidth() / 2 - 91 + i * 8;
            if (iconsTexture != null && iconsTexture.getWidth() >= 70 && iconsTexture.getHeight() >= 9) {
                int healthInHeart = world.health - i * 2;
                int tileX = healthInHeart >= 2 ? 52 : (healthInHeart == 1 ? 61 : 16);
                g.drawRegion(iconsTexture, tileX, 0, 9, 9, Sprite.TRANS_NONE, x, y, Graphics.TOP | Graphics.LEFT);
            } else {
                g.setColor(world.health > i * 2 ? 202 : 100, world.health > i * 2 ? 54 : 100,
                           world.health > i * 2 ? 47 : 100);
                g.fillArc(x, y, 7, 7, 0, 360);
            }
        }
    }

    private int blockColor(int id) {
        if (id == Block.GRASS) return 0x4F9B3A;
        if (id == Block.DIRT) return 0x85552E;
        if (id == Block.STONE) return 0x858585;
        if (id == Block.SAND) return 0xD8C980;
        if (id == Block.LOG) return 0x79502E;
        if (id == Block.PLANKS) return 0xB08A55;
        if (id == Block.LEAVES) return 0x4F813B;
        if (id == Block.COAL) return 0x414141;
        if (id == Block.IRON) return 0xB8A98A;
        return 0x777777;
    }

    private void drawPause(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0, 0, 0); g.fillRect(0, 0, w, h);
        g.setColor(255, 255, 255); g.drawString("Partida pausada", w / 2, h / 2 - 30, Graphics.HCENTER | Graphics.TOP);
        String[] rows = new String[] { "Volver", "Guardar y menú", "Guardar y salir" };
        for (int i = 0; i < rows.length; i++) drawMenuItem(g, rows[i], h / 2 + i * 19, i == pauseSelection);
    }

    private void drawError(Graphics g) {
        g.setColor(19, 26, 31); g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(255, 255, 255);
        g.drawString("MECRAFT", getWidth() / 2, 10, Graphics.HCENTER | Graphics.TOP);
        String text = errorMessage == null ? "Error" : errorMessage;
        int y = 32;
        int bottom = getHeight() - 24;
        int max = Math.max(12, getWidth() / Math.max(5, Font.getDefaultFont().charWidth('M')));
        int line = 0;
        while (text.length() > max) {
            int cut = text.lastIndexOf(' ', max);
            if (cut < 1) cut = max;
            if (line >= diagnosticScroll && y < bottom) {
                g.drawString(text.substring(0, cut), getWidth() / 2, y, Graphics.HCENTER | Graphics.TOP);
                y += Font.getDefaultFont().getHeight() + 2;
            }
            line++;
            text = text.substring(cut).trim();
        }
        if (line >= diagnosticScroll && y < bottom)
            g.drawString(text, getWidth() / 2, y, Graphics.HCENTER | Graphics.TOP);
        g.drawString(diagnosticError ? "2/8 ver detalles  5 volver" : "Pulsa 5 para volver",
                     getWidth() / 2, getHeight() - 10, Graphics.HCENTER | Graphics.BOTTOM);
    }

    private int wrappedLineCount(String value, int max) {
        String text = value == null ? "" : value;
        int lines = 1;
        while (text.length() > max) {
            int cut = text.lastIndexOf(' ', max);
            if (cut < 1) cut = max;
            text = text.substring(cut).trim();
            lines++;
        }
        return lines;
    }

    private int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}


