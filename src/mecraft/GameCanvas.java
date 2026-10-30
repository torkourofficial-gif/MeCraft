package mecraft;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.m3g.Graphics3D;

/** Game loop, phone controls, M3G view and compact HUD. */
final class GameCanvas extends Canvas implements Runnable, CommandListener {
    private static final int MENU = 0;
    private static final int PLAYING = 1;
    private static final int PAUSED = 2;
    private static final int ERROR = 3;
    private static final int INVENTORY = 4;
    private static final int WORKBENCH = 5;
    private static final int CREATIVE_INVENTORY = 6;
    private static final int WORLD_SELECT = 7;
    private static final int WORLD_CREATE = 8;
    private static final int SETTINGS = 9;
    private static final int WORLD_DELETE = 10;
    private static final int GAME_OVER = 11;
    private static final int WORLD_NAME = 12;
    private static final int SKIN_SELECT = 13;
    private static final int CONTROLS = 14;
    private static final int CHEST_INVENTORY = 15;
    private static final int LOADING = 16;
    private static final int TOUCH_SETUP = 17;
    private static final int CONTROL_LOOK_UP = 0, CONTROL_LOOK_DOWN = 1;
    private static final int CONTROL_LOOK_LEFT = 2, CONTROL_LOOK_RIGHT = 3;
    private static final int CONTROL_FORWARD = 4, CONTROL_BACK = 5;
    private static final int CONTROL_STRAFE_LEFT = 6, CONTROL_STRAFE_RIGHT = 7;
    private static final int CONTROL_JUMP = 8, CONTROL_MINE = 9, CONTROL_USE = 10;
    private static final int CONTROL_INVENTORY = 11, CONTROL_SLOT_PREVIOUS = 12;
    private static final int CONTROL_SLOT_NEXT = 13, CONTROL_PAUSE = 14;
    private static final int CONTROL_DESCEND = 15, CONTROL_COUNT = 16;
    private static final String[] CONTROL_NAMES = new String[] {
        "Mirar arriba", "Mirar abajo", "Mirar izquierda", "Mirar derecha",
        "Avanzar", "Retroceder", "Desplazarse izquierda", "Desplazarse derecha",
        "Saltar / volar", "Romper / atacar", "Usar / colocar / comer", "Inventario",
        "Casilla anterior", "Casilla siguiente", "Pausa", "Descender (creativo)"
    };
    private static final int MENU_SINGLEPLAYER = 0;
    private static final int MENU_CONTROLS = 1;
    private static final int MENU_SETTINGS = 2;
    private static final int MENU_EXIT = 3;
    private static final float PLAYER_WIDTH = 0.6f;
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_RADIUS = PLAYER_WIDTH * 0.5f;
    private static final float PLAYER_EYE_HEIGHT = 1.62f;
    private static final int[] CRACK_LINES = new int[] {
        50,0,42,17, 42,17,51,29, 51,29,37,43, 37,43,48,55,
        48,55,32,70, 32,70,43,86, 43,17,26,23, 51,29,69,22,
        37,43,20,49, 48,55,70,47, 32,70,18,77, 43,86,61,94,
        26,23,24,8, 69,22,78,8, 20,49,7,38, 70,47,91,42,
        18,77,6,66, 61,94,77,87, 26,23,13,18, 69,22,82,29,
        20,49,8,57, 70,47,84,60, 32,70,23,86, 61,94,55,100
    };
    private static final int[] DEFAULT_HOTBAR = new int[] {
        Block.DIRT, Block.STONE, Block.LOG, Block.SAND, Block.PLANKS,
        Block.LEAVES, Block.COAL, Block.IRON, Block.TORCH
    };
    private static final String[] PAUSE_ROWS = new String[] {
        "Volver", "Guardar y menú", "Guardar y salir"
    };
    private static final String SELECT_MARKER = ">  ";
    private static final String EMPTY_MARKER = "   ";
    private static int selectedPlayerSkin;

    private final MeCraft app;
    private final boolean[] keys = new boolean[10];
    private final int[] hotbar = new int[] {
        Block.DIRT, Block.STONE, Block.LOG, Block.SAND, Block.PLANKS,
        Block.LEAVES, Block.COAL, Block.IRON, Block.TORCH
    };
    private final int[] inventory = new int[ItemCatalog.COUNT];
    private volatile boolean running;
    private volatile boolean visible;
    private volatile int state = MENU;
    private int menuSelection = MENU_SINGLEPLAYER;
    private int settingsSelection;
    private int touchSetupSelection;
    private int language = Language.ENGLISH;
    private int cameraSensitivity = 2;
    private int renderDistance = 1;
    private boolean controlsReturnSettings;
    private final int[] controlCodes = new int[CONTROL_COUNT];
    private final boolean[] customControls = new boolean[CONTROL_COUNT];
    private int controlsSelection;
    private int controlsScroll;
    private boolean controlsListening;
    private String controlsMessage;
    private int skinSelection;
    private int frameSkip;
    private int renderFrame;
    private int worldSelection;
    private int worldDeleteSelection;
    private boolean worldDeleteFailed;
    private int createSelection;
    private int newWorldMode;
    private int newWorldSize;
    private int newWorldType;
    private String newWorldName = "Mundo 1";
    private TextBox worldNameInput;
    private Command acceptWorldName;
    private Command cancelWorldName;
    private int pauseSelection;
    private int gameOverSelection;
    private int gameOverTicks;
    private VoxelWorld.SavedWorld[] savedWorlds = new VoxelWorld.SavedWorld[0];
    private final PlayerPhysics playerPhysics = new PlayerPhysics();
    private boolean jumpHeld;
    private boolean descendHeld;
    private boolean flying;
    private boolean hasJumpTap;
    private long lastJumpTap;
    // Classic sprint double-tap window: seven 20 Hz gameplay ticks.
    private int sprintToggleTimer;
    private boolean sprinting;
    private float cameraPitch = -5.0f;
    private boolean lookUp, lookDown, lookLeft, lookRight;
    private VoxelWorld world;
    private ChunkRenderer renderer;
    private boolean ridingBoat;
    private boolean ridingMinecart;
    private MusicController music;
    private SkinGallery skinGallery;
    private Image title;
    private Image skybox;
    private Image menuPanorama;
    private int panoramaTick;
    private int panoramaWidth;
    private int panoramaHeight;
    private Image guiTexture;
    private Image iconsTexture;
    private Image containerTexture;
    private Image terrainTexture;
    private final Image[] blockIcons = new Image[Block.COUNT];
    private final Image[] itemIcons = new Image[ItemCatalog.COUNT];
    private final Image[] mutedItemIcons = new Image[ItemCatalog.COUNT];
    private int randomSeed = 0x4D454352;
    private String errorMessage;
    private volatile String loadingMessage = "Preparando...";
    private boolean diagnosticError;
    private int diagnosticScroll;
    private boolean touchEnabled;
    private int touchKey;
    private boolean touchLooking;
    private int touchLastX, touchLastY;
    private Image dpadTexture;
    private boolean touchMoveUp, touchMoveDown, touchMoveLeft, touchMoveRight;
    private boolean touchJumpActive;
    private boolean touchActionPending, touchMiningStarted;
    private long touchActionStart;
    private static final long TOUCH_MINE_DELAY = 260L;
    private int recipeSelection;
    private int recipeScroll;
    private int inventoryPage;
    private final int[] chestItems = new int[27];
    private final int[] chestCounts = new int[27];
    private int chestX, chestY, chestZ, chestSelection;
    private String chestStoreName;
    private int creativeSelection;
    private int miningX = -1, miningY = -1, miningZ = -1;
    private int miningFaceX, miningFaceY, miningFaceZ;
    private float miningDamage;
    private int miningHitWait;
    private final int[] traceResult = new int[6];
    private static final int MAX_AIR = 300;
    private int airTicks = MAX_AIR;
    private int hurtTicks;
    private int cactusDamageCooldown;
    private int netherLavaDamageCooldown;
    private int portalChargeTicks;
    private float overworldPortalReturnX, overworldPortalReturnY, overworldPortalReturnZ;
    private float netherPortalReturnX, netherPortalReturnY, netherPortalReturnZ;
    private boolean hasOverworldPortalReturn, hasNetherPortalReturn;
    private static final int PORTAL_CHARGE_TICKS = 100;
    private Image waterTint;
    private Image chestOverlayImage;
    private Image hurtTint;
    private Image pauseTint;
    private Image deathTint;

    GameCanvas(MeCraft app) {
        this.app = app;
        int[] settings = new int[] { GameSettings.loadFrameSkip(), cameraSensitivity,
                                     selectedPlayerSkin, language, renderDistance, -1 };
        GameSettings.load(settings, controlCodes, customControls);
        frameSkip = clamp(settings[0], 0, 2);
        cameraSensitivity = clamp(settings[1], 0, 4);
        selectedPlayerSkin = clamp(settings[2], 0, 4);
        language = clamp(settings[3], Language.ENGLISH, Language.SPANISH);
        renderDistance = clamp(settings[4], 0, 3);
        touchEnabled = settings[5] == 1;
        Language.set(language);
        try { music = new MusicController(); }
        catch (NoClassDefFoundError unsupported) { music = null; }
        setFullScreenMode(true);
        loadTitle();
        loadHudTextures();
        refreshWorldList();
        menuSelection = MENU_SINGLEPLAYER;
        state = settings[5] < 0 ? TOUCH_SETUP : MENU;
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
        if (music != null) music.close();
        saveIfNeeded();
    }

    synchronized void pauseGame() {
        visible = false;
        if (music != null) music.update(MusicController.OFF);
        clearKeys();
        saveIfNeeded();
    }

    void resumeGame() {
        visible = true;
    }

    public void run() {
        while (running) {
            long start = System.currentTimeMillis();
            if (music != null) music.update(visible ? musicModeForState() : MusicController.OFF);
            if (visible && state == PLAYING) updateGame();
            if (visible && state == GAME_OVER && gameOverTicks < 12) gameOverTicks++;
            if (visible && state == MENU) panoramaTick++;
            if (visible && (state != PLAYING || renderFrame++ % (frameSkip + 1) == 0)) repaint();
            long elapsed = System.currentTimeMillis() - start;
            long wait = 50L - elapsed;
            if (wait < 8L) wait = 8L;
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
        }
    }

    private int musicModeForState() {
        if (state == PLAYING || state == PAUSED || state == INVENTORY || state == WORKBENCH ||
                state == CREATIVE_INVENTORY || state == CHEST_INVENTORY || state == GAME_OVER) return MusicController.GAMEPLAY;
        if (state == MENU || state == WORLD_SELECT || state == WORLD_CREATE ||
                state == WORLD_DELETE || state == WORLD_NAME || state == SETTINGS ||
                state == SKIN_SELECT || state == CONTROLS) return MusicController.MENU;
        return MusicController.OFF;
    }

    private synchronized void updateGame() {
        if (!visible || state != PLAYING || world == null || renderer == null) return;
        if (sprintToggleTimer > 0) sprintToggleTimer--;
        float sensitivity = cameraSensitivityScale();
        if (lookLeft) world.yaw += 3.0f * sensitivity;
        if (lookRight) world.yaw -= 3.0f * sensitivity;
        if (world.yaw < 0.0f) world.yaw += 360.0f;
        if (world.yaw >= 360.0f) world.yaw -= 360.0f;
        if (lookUp) cameraPitch += 2.5f * sensitivity;
        if (lookDown) cameraPitch -= 2.5f * sensitivity;
        if (cameraPitch > 80.0f) cameraPitch = 80.0f;
        if (cameraPitch < -80.0f) cameraPitch = -80.0f;
        renderer.updateHandAnimation();

        world.tickTime();
        renderer.updateDayNight(world);

        world.updateFluids();
        float forward = 0.0f;
        float strafe = 0.0f;
        if (keys[2]) forward += 1.0f;
        if (keys[8]) forward -= 1.0f;
        if (keys[6]) strafe += 1.0f;
        if (keys[4]) strafe -= 1.0f;
        boolean canKeepSprinting = keys[2] && !keys[8] &&
                !(world.mode == 1 && flying) && !playerPhysics.isInWater(world);
        if (sprinting && !canKeepSprinting) sprinting = false;
        boolean sprint = sprinting;
        MinecartEntity activeMinecart = renderer.minecart();
        if (activeMinecart != null && !activeMinecart.alive) {
            if (ridingMinecart) ridingMinecart = false;
            renderer.removeMinecart();
            activeMinecart = null;
        }
        if (ridingMinecart && (activeMinecart == null || jumpHeld)) {
            if (activeMinecart != null && jumpHeld) {
                world.playerX = activeMinecart.x + 0.8f;
                world.playerY = activeMinecart.y + 0.35f;
                world.playerZ = activeMinecart.z;
            }
            ridingMinecart = false;
            playerPhysics.reset();
        }
        if (activeMinecart != null) {
            activeMinecart.update(world, ridingMinecart ? forward : 0.0f, ridingMinecart);
            if (ridingMinecart) {
                world.playerX = activeMinecart.x;
                world.playerY = activeMinecart.y + 0.55f;
                world.playerZ = activeMinecart.z;
                playerPhysics.reset();
            }
        }
        BoatEntity activeBoat = renderer.boat();
        if (activeBoat != null && !activeBoat.alive) {
            if (ridingBoat) ridingBoat = false;
            int bx = floorBlock(activeBoat.x), by = floorBlock(activeBoat.y), bz = floorBlock(activeBoat.z);
            renderer.spawnDroppedItem(Block.PLANKS, bx, by, bz);
            renderer.spawnDroppedItem(Block.PLANKS, bx, by, bz);
            renderer.spawnDroppedItem(Block.PLANKS, bx, by, bz);
            renderer.spawnDroppedItem(ItemCatalog.STICK, bx, by, bz);
            renderer.spawnDroppedItem(ItemCatalog.STICK, bx, by, bz);
            renderer.removeBoat();
            activeBoat = null;
        }
        if (ridingBoat && (activeBoat == null || jumpHeld)) {
            if (activeBoat != null && jumpHeld) {
                // Step off the hull instead of leaving the player embedded in it.
                world.playerX = activeBoat.x + 0.85f;
                world.playerY = activeBoat.y + 0.45f;
                world.playerZ = activeBoat.z;
            }
            ridingBoat = false;
            playerPhysics.reset();
        }
        int fallDamage = 0;
        if (activeBoat != null && activeBoat.alive) {
            if (ridingBoat) {
                activeBoat.yaw = world.yaw;
                activeBoat.update(world, forward, strafe, true);
                world.playerX = activeBoat.x;
                world.playerY = activeBoat.y + 0.15f;
                world.playerZ = activeBoat.z;
                playerPhysics.reset();
            } else {
                activeBoat.update(world, 0.0f, 0.0f, false);
            }
        }
        if (!ridingBoat && !ridingMinecart) {
            fallDamage = playerPhysics.update(world, forward, strafe, jumpHeld,
                    world.mode == 1 && flying, descendHeld, sprint);
        }
        if (sprinting && playerPhysics.hasHorizontalCollision()) sprinting = false;
        if (world.mode == 0 && fallDamage > 0) damagePlayer(fallDamage);
        if (world.touchesPortal(world.playerX, world.playerY, world.playerZ)) {
            if (++portalChargeTicks >= PORTAL_CHARGE_TICKS) {
                portalChargeTicks = 0;
                travelThroughPortal();
            }
        } else {
            // Leaving even briefly cancels the transfer, so the player can step
            // out of the destination portal during the same five-second window.
            portalChargeTicks = 0;
        }
        if (state != PLAYING) return;
        int mobDamage = renderer.updateMobs(world);
        if (world.mode == 0 && mobDamage > 0) damagePlayer(mobDamage);
        if (cactusDamageCooldown > 0) cactusDamageCooldown--;
        if (netherLavaDamageCooldown > 0) netherLavaDamageCooldown--;
        if (world.mode == 0 && cactusDamageCooldown == 0 && playerPhysics.touchesCactus(world)) {
            damagePlayer(1);
            cactusDamageCooldown = 10;
        }
        boolean touchingLava = playerTouchesLava();
        if (world.nether && playerPhysics.isInWater(world)) touchingLava = true;
        if (world.mode == 0 && netherLavaDamageCooldown == 0 && touchingLava) {
            damagePlayer(2);
            netherLavaDamageCooldown = 10;
        }
        if (state != PLAYING) return;
        tickAirAndHurt();
        if (state != PLAYING) return;
        int healthBeforeDrops = world.health;
        int pickedItem = renderer.updateDroppedItems(world, inventory);
        if (world.mode == 0 && world.health < healthBeforeDrops) {
            hurtTicks = 10;
            if (world.health <= 0) enterGameOver();
        }
        if (state != PLAYING) return;
        if (pickedItem > Block.AIR) ensureHotbarItem(pickedItem);
        if (touchActionPending && !touchMiningStarted &&
                System.currentTimeMillis() - touchActionStart >= TOUCH_MINE_DELAY) {
            touchMiningStarted = true;
            touchLooking = false;
            keys[1] = true;
            if (renderer != null) renderer.swingHand();
        }
        if (keys[1]) updateMining();
        renderer.updateFieldOfView(sprinting);
        renderer.updateCamera(world, cameraPitch);
        renderer.setHurtCameraRoll(hurtCameraRoll());
        renderer.updateParticles(world, world.yaw, cameraPitch);
    }

    /** Indev drains 300 air ticks, waits another 20 ticks, then deals a heart. */
    private void tickAirAndHurt() {
        if (hurtTicks > 0) hurtTicks--;
        if (world.mode != 0) {
            airTicks = MAX_AIR;
            return;
        }
        if (cameraUnderwater()) {
            if (airTicks > -20) airTicks--;
            if (airTicks <= -20) {
                airTicks = 0;
                damagePlayer(2);
            }
        } else {
            airTicks = MAX_AIR;
        }
    }

    private boolean cameraUnderwater() {
        if (world == null || world.nether) return false;
        return world.get(floorBlock(world.playerX), floorBlock(world.playerY + PLAYER_EYE_HEIGHT),
                         floorBlock(world.playerZ)) == Block.WATER;
    }

    private boolean playerTouchesLava() {
        float x = world.playerX, y = world.playerY, z = world.playerZ;
        int minX = floorBlock(x - PLAYER_RADIUS), maxX = floorBlock(x + PLAYER_RADIUS);
        int minY = floorBlock(y), maxY = floorBlock(y + PLAYER_HEIGHT - 0.0001f);
        int minZ = floorBlock(z - PLAYER_RADIUS), maxZ = floorBlock(z + PLAYER_RADIUS);
        for (int by = minY; by <= maxY; by++)
            for (int bx = minX; bx <= maxX; bx++)
                for (int bz = minZ; bz <= maxZ; bz++)
                    if (world.get(bx, by, bz) == Block.LAVA) return true;
        return false;
    }

    private void damagePlayer(int amount) {
        if (world == null || world.mode != 0 || world.health <= 0) return;
        world.health -= amount;
        if (world.health < 0) world.health = 0;
        hurtTicks = 10;
        if (world.health == 0) enterGameOver();
    }

    /** Matches Indev's hurt camera pulse, with a 14 degree roll. */
    private float hurtCameraRoll() {
        if (hurtTicks <= 0) return 0.0f;
        float progress = hurtTicks / 10.0f;
        float squared = progress * progress;
        return (float)Math.sin(squared * squared * Math.PI) * 14.0f;
    }

    private void respawn() {
        world.playerX = VoxelWorld.WIDTH / 2 + 0.5f;
        world.playerZ = VoxelWorld.DEPTH / 2 + 0.5f;
        world.playerY = world.surfaceY(VoxelWorld.WIDTH / 2, VoxelWorld.DEPTH / 2) + 1.0f;
        world.health = 20;
        playerPhysics.reset();
        airTicks = MAX_AIR;
        hurtTicks = 0;
        cactusDamageCooldown = 0;
        netherLavaDamageCooldown = 0;
    }

    private void enterGameOver() {
        if (state == GAME_OVER) return;
        state = GAME_OVER;
        gameOverSelection = 0;
        gameOverTicks = 0;
        clearKeys();
        resetMining();
        repaint();
    }

    private void activateGameOverSelection() {
        // Indev offers to generate a new level or load a saved one from here.
        // Keep this world's progress by saving it in its respawned state first.
        int mode = world == null ? 0 : world.mode;
        respawn();
        saveIfNeeded();
        renderer = null;
        world = null;
        System.gc();
        if (gameOverSelection == 0) {
            newWorldName = defaultWorldName(savedWorlds.length + 1);
            newWorldMode = mode;
            createSelection = 0;
            state = WORLD_CREATE;
        } else {
            refreshWorldList();
            worldSelection = 0;
            state = WORLD_SELECT;
        }
        clearKeys();
        repaint();
    }

    private void startNewWorld(int mode, int size, int type, String name) {
        if (state == LOADING) return;
        state = LOADING;
        loadingMessage = "Preparando mundo...";
        clearKeys();
        repaint();
        serviceRepaints();
        final int requestedMode = mode;
        final int requestedSize = size;
        final int requestedType = type;
        final String requestedName = name;
        Thread loader = new Thread(new Runnable() {
            public void run() { createNewWorldNow(requestedMode, requestedSize, requestedType, requestedName); }
        });
        loader.start();
    }

    private void createNewWorldNow(int mode, int size, int type, String name) {
        String stage = "preparando mundo";
        try {
            title = null;
            System.gc();
            if (renderer != null) {
                renderer = null;
                System.gc();
            }
            stage = "generando el terreno";
            loadingMessage = "Generando terreno...";
            world = new VoxelWorld((int)(System.currentTimeMillis() ^ System.currentTimeMillis() >>> 32), mode, size, type);
            stage = "inicializando inventario y posición";
            world.worldName = name == null || name.trim().length() == 0 ? "Mundo" : name.trim();
            // Nether access is reached through a wool portal in the normal world.
            world.saveStoreName = VoxelWorld.nextWorldStoreName();
            copyHotbarFromWorld();
            playerPhysics.reset();
            flying = false;
            hasJumpTap = false;
            sprinting = false;
            sprintToggleTimer = 0;
            airTicks = MAX_AIR;
            hurtTicks = 0;
            cactusDamageCooldown = 0;
            netherLavaDamageCooldown = 0;
            resetInventory(mode);
            copyInventoryToWorld();
            stage = "guardando el mundo recién creado";
            loadingMessage = "Guardando mundo...";
            world.save();
            stage = "cargando /mecraft/textures.png y creando escena M3G";
            ridingBoat = false;
            ridingMinecart = false;
            renderer = new ChunkRenderer(getWidth(), getHeight());
            renderer.setPlayerSkinIndex(selectedPlayerSkin);
            renderer.setRenderDistance(renderDistance);
            stage = "configurando cámara M3G";
            renderer.updateCamera(world, cameraPitch);
            stage = "construyendo sectores visibles M3G";
            loadingMessage = "Construyendo chunks visibles...";
            renderer.sync(world);
            state = PLAYING;
            repaint();
        } catch (OutOfMemoryError exhausted) {
            showError("No hay memoria suficiente para crear el mundo. Libera memoria y vuelve a intentarlo.");
        } catch (Throwable problem) {
            if ("generando el terreno".equals(stage)) {
                stage = VoxelWorld.getGenerationDiagnosticStage();
            }
            showDiagnostic(stage, problem);
        }
    }

    private void openSavedWorld(final VoxelWorld.SavedWorld savedWorld) {
        if (state == LOADING) return;
        state = LOADING;
        loadingMessage = "Cargando mundo guardado...";
        clearKeys();
        repaint();
        serviceRepaints();
        Thread loader = new Thread(new Runnable() {
            public void run() { loadSavedWorldNow(savedWorld); }
        });
        loader.start();
    }

    private void loadSavedWorldNow(VoxelWorld.SavedWorld savedWorld) {
        title = null;
        System.gc();
        VoxelWorld saved = VoxelWorld.load(savedWorld.storeName);
        if (saved == null) {
            showError("No se pudo cargar el mundo guardado.");
            return;
        }
        String stage = "restaurando partida guardada";
        try {
            if (renderer != null) { renderer = null; System.gc(); }
            world = saved;
            world.health = clamp(world.health, 0, 20);
            playerPhysics.reset();
            flying = false;
            hasJumpTap = false;
            sprinting = false;
            sprintToggleTimer = 0;
            airTicks = MAX_AIR;
            hurtTicks = 0;
            cactusDamageCooldown = 0;
            netherLavaDamageCooldown = 0;
            copyHotbarFromWorld();
            resetInventory(world.mode);
            for (int i = 0; i < inventory.length; i++) inventory[i] = world.inventory[i];
            world.selectedSlot = clamp(world.selectedSlot, 0, hotbar.length - 1);
            stage = "cargando /mecraft/textures.png y creando escena M3G";
            renderer = new ChunkRenderer(getWidth(), getHeight());
            renderer.setPlayerSkinIndex(selectedPlayerSkin);
            renderer.setRenderDistance(renderDistance);
            stage = "configurando cámara M3G";
            renderer.updateCamera(world, cameraPitch);
            stage = "construyendo sectores visibles M3G";
            renderer.sync(world);
            state = world.mode == 0 && world.health <= 0 ? GAME_OVER : PLAYING;
            if (state == GAME_OVER) {
                gameOverSelection = 0;
                gameOverTicks = 0;
            }
        } catch (OutOfMemoryError exhausted) {
            showError("No hay memoria suficiente para cargar el mundo guardado.");
        } catch (Throwable problem) {
            showDiagnostic(stage, problem);
        }
    }

    private void resetInventory(int mode) {
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = mode == 1 ? ItemCatalog.maxStack(i) : 0;
        }
    }

    private void showError(String message) {
        errorMessage = Language.text(message);
        diagnosticError = false;
        diagnosticScroll = 0;
        state = ERROR;
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

    private void copyInventoryToWorld() {
        if (world == null) return;
        for (int i = 0; i < inventory.length; i++) world.inventory[i] = inventory[i];
        for (int i = 0; i < hotbar.length; i++) world.hotbar[i] = hotbar[i];
    }

    private void copyHotbarFromWorld() {
        if (world == null) return;
        for (int i = 0; i < hotbar.length; i++) {
            int id = world.hotbar[i];
            hotbar[i] = id <= Block.AIR || id >= ItemCatalog.COUNT ?
                    DEFAULT_HOTBAR[i] : id;
        }
    }

    private void saveIfNeeded() {
        if (state == CHEST_INVENTORY) saveChestContents();
        if (world != null && state != MENU && state != ERROR) {
            copyInventoryToWorld();
            world.save();
            refreshWorldList();
        }
    }

    private void refreshWorldList() {
        savedWorlds = VoxelWorld.listWorlds();
    }

    private void mineTarget() {
        if (world != null) {
            int mobHit = renderer.hitMob(world, cameraPitch, heldAttackDamage());
            if (mobHit >= 0) {
                if (mobHit > 0) onMobHit();
                return;
            }
            renderer.swingHand();
            advanceMining(trace(5.0f));
        }
    }

    private int heldAttackDamage() {
        int item = hotbar[world.selectedSlot];
        if (!ItemCatalog.isTool(item) || ItemCatalog.kind(item) != ItemCatalog.SWORD) return 1;
        int tier = ItemCatalog.tier(item);
        // Indev swords deal 4 base damage plus their material bonus; gold uses wood's bonus.
        return 4 + (tier == ItemCatalog.GOLD ? 0 : tier);
    }

    private void onMobHit() {
        renderer.swingHand();
        int item = hotbar[world.selectedSlot];
        if (ItemCatalog.isTool(item) && ItemCatalog.kind(item) == ItemCatalog.SWORD)
            damageTool(item, 1);
    }

    private void updateMining() {
        if (world == null) return;
        int mobHit = renderer.hitMob(world, cameraPitch, heldAttackDamage());
        if (mobHit >= 0) {
            if (mobHit > 0) onMobHit();
            resetMining();
            return;
        }
        if (miningHitWait > 0) {
            miningHitWait--;
            return;
        }
        MinecartEntity cart = renderer.minecart();
        if (cart != null && cart.alive && minecartInCrosshair(cart)) {
            // The minecart is a complete target: attack breaks the entity, not
            // the rail or block behind it, and drops one minecart item.
            cart.alive = false;
            renderer.spawnDroppedItem(ItemCatalog.MINECART,
                    floorBlock(cart.x), floorBlock(cart.y), floorBlock(cart.z));
            renderer.swingHand();
            miningHitWait = 6;
            resetMining();
            world.save();
            return;
        }
        BoatEntity boat = renderer.boat();
        if (boat != null && boat.alive && boatInCrosshair(boat)) {
            boat.damage(heldAttackDamage());
            onMobHit();
            miningHitWait = 6;
            resetMining();
            return;
        }
        advanceMining(trace(5.0f));
        renderer.setHandLooping(miningX >= 0);
        if (miningX >= 0) {
            int stage = (int)(miningDamage * 10.0f);
            if (stage > 9) stage = 9;
            renderer.setMiningCracks(miningX, miningY, miningZ, miningFaceX, miningFaceY, miningFaceZ, stage);
        } else renderer.setMiningCracks(-1, -1, -1, 0, 0, 0, -1);
    }

    /** Ray-pick the full minecart bounds, stopping when a solid block occludes it. */
    private boolean minecartInCrosshair(MinecartEntity cart) {
        if (world == null || cart == null || !cart.alive) return false;
        double yaw = world.yaw * Math.PI / 180.0;
        double pitch = cameraPitch * Math.PI / 180.0;
        float ox = world.playerX, oy = world.playerY + PLAYER_EYE_HEIGHT, oz = world.playerZ;
        double rx = Math.sin(yaw) * Math.cos(pitch);
        double ry = Math.sin(pitch);
        double rz = Math.cos(yaw) * Math.cos(pitch);
        for (float d = 0.20f; d <= 5.0f; d += 0.05f) {
            float px = ox + (float)(rx * d);
            float py = oy + (float)(ry * d);
            float pz = oz + (float)(rz * d);
            if (px >= cart.x - 0.68f && px <= cart.x + 0.68f &&
                    py >= cart.y - 0.32f && py <= cart.y + 0.42f &&
                    pz >= cart.z - 0.68f && pz <= cart.z + 0.68f) return true;
            byte block = world.get(floorBlock(px), floorBlock(py), floorBlock(pz));
            if (block != Block.AIR && !Block.isFluid(block)) return false;
        }
        return false;
    }

    private boolean boatInCrosshair(BoatEntity boat) {
        double yaw = world.yaw * Math.PI / 180.0;
        double pitch = cameraPitch * Math.PI / 180.0;
        float ox = world.playerX, oy = world.playerY + PLAYER_EYE_HEIGHT, oz = world.playerZ;
        float dx = boat.x - ox, dy = boat.y - oy, dz = boat.z - oz;
        double rx = Math.sin(yaw) * Math.cos(pitch);
        double ry = Math.sin(pitch);
        double rz = Math.cos(yaw) * Math.cos(pitch);
        double along = dx * rx + dy * ry + dz * rz;
        if (along < 0.0 || along > 5.0) return false;
        double ex = dx - rx * along, ey = dy - ry * along, ez = dz - rz * along;
        return ex * ex + ey * ey + ez * ez < 1.8 * 1.8;
    }

    private void advanceMining(int[] target) {
        if (target == null) { resetMining(); return; }
        int x = target[0], y = target[1], z = target[2];
        byte id = world.get(x, y, z);
        if (id == Block.AIR || id == Block.BEDROCK || Block.isFluid(id)) { resetMining(); return; }
        // Indev TNT is primed by the attack; it never drops or breaks as a
        // regular block. Replace it with the fuse entity immediately.
        if (id == Block.TNT) {
            if (!world.set(x, y, z, Block.AIR)) {
                miningDamage = 0.99f;
                return;
            }
            renderer.spawnPrimedTnt(x, y, z, 80);
            renderer.swingHand();
            renderer.sync(world);
            resetMining();
            miningHitWait = 5;
            return;
        }
        if (id == Block.CHEST) {
            loadChestContents(x, y, z);
            for (int i = 0; i < chestItems.length; i++) {
                int item = chestItems[i], amount = chestCounts[i];
                if (item > 0 && amount > 0) {
                    int room = Math.max(0, 64 - inventory[item]);
                    int moved = Math.min(room, amount);
                    inventory[item] += moved;
                    // Overflow still appears in the world as a dropped stack.
                    if (amount > moved) renderer.spawnDroppedItem(item, x, y, z);
                }
            }
            copyInventoryToWorld();
            clearChestContents();
        }
        if (id == Block.DOOR) {
            int meta = world.doorMetadata(x, y, z);
            int lowerY = (meta & 8) != 0 ? y - 1 : y;
            if (!world.removeDoor(x, y, z)) {
                miningDamage = 0.99f;
                return;
            }
            if (world.mode == 0)
                renderer.spawnDroppedItem(Block.DOOR, x, lowerY, z);
            renderer.swingHand();
            if (world.mode == 0)
                damageTool(hotbar[world.selectedSlot],
                        ItemCatalog.durabilityCost(hotbar[world.selectedSlot]));
            // Rebuild the removed door first, then emit debris so the visual burst
            // cannot be lost behind a terrain rebuild on slower M3G implementations.
            renderer.sync(world);
            renderer.spawnBlockDestroy(x, lowerY, z, 97);
            resetMining();
            miningHitWait = 5;
            return;
        }
        if (x != miningX || y != miningY || z != miningZ) {
            miningX = x; miningY = y; miningZ = z; miningDamage = 0.0f;
            renderer.setHandLooping(true);
            miningFaceX = target[3]; miningFaceY = target[4]; miningFaceZ = target[5];
            return;
        }
        boolean grounded = playerPhysics.isOnGround();
        boolean submerged = playerPhysics.isInWater(world);
        if (world.mode == 1) miningDamage = 1.0f;
        else miningDamage += Block.breakStrength(id, grounded, submerged, hotbar[world.selectedSlot]);
        if (miningDamage >= 1.0f) {
            int heldItem = hotbar[world.selectedSlot];
            boolean canHarvest = Block.canHarvest(id, heldItem);
            int dropId = world.dropItemId(x, y, z);
            if (!world.set(x, y, z, Block.AIR)) {
                miningDamage = 0.99f;
                return;
            }
            if (world.mode == 0 && Block.dropsItem(id) && canHarvest)
                renderer.spawnDroppedItem(dropId, x, y, z);
            renderer.swingHand();
            if (y + 1 < VoxelWorld.HEIGHT &&
                world.get(x, y + 1, z) == Block.DOOR && !Block.solid(world.get(x, y, z))) {
                if (world.mode == 0) renderer.spawnDroppedItem(Block.DOOR, x, y + 1, z);
                world.removeDoor(x, y + 1, z);
            }
            if (world.mode == 0) damageTool(heldItem, ItemCatalog.durabilityCost(heldItem));
            // Sync the changed terrain before creating the burst. This keeps
            // particle creation separate from mesh replacement during block breaks.
            renderer.sync(world);
            renderer.spawnBlockDestroy(x, y, z, Block.sideTile(id));
            resetMining();
            miningHitWait = 5;
        }
    }

    private void resetMining() {
        miningX = miningY = miningZ = -1;
        miningFaceX = miningFaceY = miningFaceZ = 0;
        miningDamage = 0.0f;
        if (renderer != null) { renderer.setHandLooping(false); renderer.setMiningCracks(-1, -1, -1, 0, 0, 0, -1); }
    }

    private void cancelMining() {
        keys[1] = false;
        resetMining();
        miningHitWait = 0;
    }

    private void damageTool(int itemId, int amount) {
        if (!ItemCatalog.isTool(itemId) || amount <= 0 || inventory[itemId] <= 0) return;
        world.itemDamage[itemId] += amount;
        // Indev ItemStack breaks only once damage exceeds maxDamage.
        if (world.itemDamage[itemId] > ItemCatalog.maxDamage(itemId)) {
            inventory[itemId] = 0;
            world.itemDamage[itemId] = 0;
            restoreHotbarItem(itemId);
        }
    }

    /** ItemHoe.onItemUse: turn exposed grass/dirt into farmland and wear one use. */
    private void tillSoil(int x, int y, int z, int hoe) {
        if (y <= 0 || y >= VoxelWorld.HEIGHT - 1) return;
        byte soil = world.get(x, y, z);
        if ((soil != Block.GRASS && soil != Block.DIRT) || Block.solid(world.get(x, y + 1, z))) return;
        if (!world.set(x, y, z, Block.FARMLAND)) return;
        damageTool(hoe, 1);
        if (soil == Block.GRASS && (nextRandom() & 7) == 0 && inventory[ItemCatalog.SEEDS] < 64)
            inventory[ItemCatalog.SEEDS]++;
        renderer.swingHand();
        renderer.sync(world);
        copyInventoryToWorld();
    }

    private int nextRandom() {
        randomSeed = randomSeed * 1664525 + 1013904223;
        return randomSeed >>> 1;
    }

    private void placeBlock() {
        if (world == null) return;
        int[] target = trace(5.0f);
        if (target != null && world.get(target[0], target[1], target[2]) == Block.CRAFTING_TABLE) {
            openCrafting(true);
            return;
        }
        if (target != null && world.get(target[0], target[1], target[2]) == Block.CHEST) {
            openChest(target[0], target[1], target[2]);
            return;
        }
        if (target != null && world.get(target[0], target[1], target[2]) == Block.DOOR) {
            if (world.toggleDoor(target[0], target[1], target[2])) {
                renderer.swingHand();
                renderer.sync(world);
            }
            return;
        }
        int selectedItem = hotbar[world.selectedSlot];
        boolean coloredWool = ItemCatalog.isColoredWool(selectedItem);
        if (target == null || target[3] == Integer.MIN_VALUE) {
            // Rails can be laid directly beneath the player, as in Alpha:
            // use the block underfoot as the support when the ray misses.
            if (selectedItem != Block.RAIL) return;
            int railX = (int)Math.floor(world.playerX);
            int railY = (int)Math.floor(world.playerY);
            int railZ = (int)Math.floor(world.playerZ);
            if (railY <= 0 || railY >= VoxelWorld.HEIGHT ||
                    !Block.solid(world.get(railX, railY - 1, railZ)) ||
                    !Block.replaceable(world.get(railX, railY, railZ))) return;
            target = new int[] { railX, railY - 1, railZ, 0, 1, 0 };
        }
        if (ItemCatalog.isTool(selectedItem) && ItemCatalog.kind(selectedItem) == ItemCatalog.HOE) {
            tillSoil(target[0], target[1], target[2], selectedItem);
            return;
        }
        if (selectedItem == Block.AIR || selectedItem >= Block.COUNT && !coloredWool) return;
        int block = coloredWool ? Block.WOOL : selectedItem;
        int x = target[0] + target[3];
        int y = target[1] + target[4];
        int z = target[2] + target[5];
        if (y <= 0 || y >= VoxelWorld.HEIGHT) return;
        if (selectedItem == Block.DOOR) {
            // Alpha ItemDoor only accepts the upper face and places a two-cell
            // door on the supporting block.
            if (target[4] != 1 || world.mode == 0 && inventory[Block.DOOR] <= 0) return;
            int doorY = target[1] + 1;
            int orientation = doorOrientation(x, doorY, z);
            float px = world.playerX, py = world.playerY, pz = world.playerZ;
            if (x + 1.0f > px - PLAYER_RADIUS && x < px + PLAYER_RADIUS &&
                z + 1.0f > pz - PLAYER_RADIUS && z < pz + PLAYER_RADIUS &&
                doorY + 2.0f > py && doorY < py + PLAYER_HEIGHT) return;
            if (!world.placeDoor(x, doorY, z, orientation)) return;
            renderer.swingHand();
            if (world.mode == 0) {
                inventory[Block.DOOR]--;
                if (inventory[Block.DOOR] == 0) restoreHotbarItem(Block.DOOR);
            }
            renderer.sync(world);
            return;
        }
        if (block == Block.TORCH && !Block.solid(world.get(x, y - 1, z))) return;
        // Alpha rails are non-solid flat pieces and require a solid block beneath them.
        if (block == Block.RAIL && !Block.solid(world.get(x, y - 1, z))) return;
        if (!canPlaceAlphaPlant((byte)block, x, y, z)) return;
        float px = world.playerX, py = world.playerY, pz = world.playerZ;
        if (block != Block.RAIL &&
            x + 1.0f > px - PLAYER_RADIUS && x < px + PLAYER_RADIUS &&
            z + 1.0f > pz - PLAYER_RADIUS && z < pz + PLAYER_RADIUS &&
            y + 1.0f > py && y < py + PLAYER_HEIGHT) return;
        if (world.mode == 0 && inventory[selectedItem] <= 0) return;
        if (!Block.replaceable(world.get(x, y, z))) return;
        boolean placed = coloredWool ? world.setWool(x, y, z,
                ItemCatalog.woolColor(selectedItem)) : world.set(x, y, z, (byte)block);
        if (!placed) return;
        if (block == Block.RAIL) {
            // Alpha 1.2.6 derives rail metadata from connected rails, including
            // corners and uphill/downhill links, then refreshes nearby pieces.
            orientRailNetwork(x, y, z);
        }
        renderer.swingHand();
        if (world.mode == 0) {
            inventory[selectedItem]--;
            if (inventory[selectedItem] == 0) restoreHotbarItem(selectedItem);
        }
        renderer.sync(world);
    }


    private boolean railExistsNear(int x, int y, int z) {
        return world.get(x, y, z) == Block.RAIL ||
               world.get(x, y + 1, z) == Block.RAIL ||
               world.get(x, y - 1, z) == Block.RAIL;
    }

    private void orientRailNetwork(int x, int y, int z) {
        orientRailAt(x, y, z);
        orientRailAt(x - 1, y, z);
        orientRailAt(x + 1, y, z);
        orientRailAt(x, y, z - 1);
        orientRailAt(x, y, z + 1);
        orientRailAt(x - 1, y + 1, z);
        orientRailAt(x + 1, y + 1, z);
        orientRailAt(x, y + 1, z - 1);
        orientRailAt(x, y + 1, z + 1);
        orientRailAt(x - 1, y - 1, z);
        orientRailAt(x + 1, y - 1, z);
        orientRailAt(x, y - 1, z - 1);
        orientRailAt(x, y - 1, z + 1);
    }

    /** Compact port of Alpha's MinecartTrackLogic connection/metadata rules. */
    private void orientRailAt(int x, int y, int z) {
        if (world.get(x, y, z) != Block.RAIL) return;
        boolean north = railExistsNear(x, y, z - 1);
        boolean south = railExistsNear(x, y, z + 1);
        boolean west = railExistsNear(x - 1, y, z);
        boolean east = railExistsNear(x + 1, y, z);
        int orientation = -1;
        if ((north || south) && !west && !east) orientation = 0;
        if ((west || east) && !north && !south) orientation = 1;
        if (south && east && !north && !west) orientation = 6;
        if (south && west && !north && !east) orientation = 7;
        if (north && west && !south && !east) orientation = 8;
        if (north && east && !south && !west) orientation = 9;
        if (orientation < 0) {
            if (north || south) orientation = 0;
            if (west || east) orientation = 1;
        }
        if (orientation == 0) {
            if (world.get(x, y + 1, z - 1) == Block.RAIL) orientation = 4;
            if (world.get(x, y + 1, z + 1) == Block.RAIL) orientation = 5;
        } else if (orientation == 1) {
            if (world.get(x + 1, y + 1, z) == Block.RAIL) orientation = 2;
            if (world.get(x - 1, y + 1, z) == Block.RAIL) orientation = 3;
        }
        if (orientation < 0) orientation = 0;
        world.setRailMetadata(x, y, z, orientation);
    }

    private boolean canPlaceAlphaPlant(byte block, int x, int y, int z) {
        byte below = world.get(x, y - 1, z);
        if (block == Block.SAPLING) return below == Block.GRASS || below == Block.DIRT;
        if (block == Block.CROPS) return below == Block.FARMLAND;
        if (block == Block.REED) {
            if (below != Block.GRASS && below != Block.DIRT && below != Block.SAND &&
                    below != Block.REED) return false;
            return world.get(x - 1, y - 1, z) == Block.WATER ||
                   world.get(x + 1, y - 1, z) == Block.WATER ||
                   world.get(x, y - 1, z - 1) == Block.WATER ||
                   world.get(x, y - 1, z + 1) == Block.WATER;
        }
        if (block == Block.CACTUS) {
            if (below != Block.SAND && below != Block.CACTUS) return false;
            return !Block.solid(world.get(x - 1, y, z)) &&
                   !Block.solid(world.get(x + 1, y, z)) &&
                   !Block.solid(world.get(x, y, z - 1)) &&
                   !Block.solid(world.get(x, y, z + 1));
        }
        return true;
    }

    /** Mirrors Alpha ItemDoor's yaw quadrant and nearby-wall hinge selection. */
    private int doorOrientation(int x, int y, int z) {
        int orientation = floorBlock((float)((world.yaw + 180.0f) * 4.0f / 360.0f) - 0.5f) & 3;
        int sideX = orientation == 1 ? -1 : (orientation == 3 ? 1 : 0);
        int sideZ = orientation == 0 ? 1 : (orientation == 2 ? -1 : 0);
        int nearWalls = (Block.solid(world.get(x - sideX, y, z - sideZ)) ? 1 : 0) +
                        (Block.solid(world.get(x - sideX, y + 1, z - sideZ)) ? 1 : 0);
        int farWalls = (Block.solid(world.get(x + sideX, y, z + sideZ)) ? 1 : 0) +
                       (Block.solid(world.get(x + sideX, y + 1, z + sideZ)) ? 1 : 0);
        boolean nearDoor = world.get(x - sideX, y, z - sideZ) == Block.DOOR ||
                           world.get(x - sideX, y + 1, z - sideZ) == Block.DOOR;
        boolean farDoor = world.get(x + sideX, y, z + sideZ) == Block.DOOR ||
                          world.get(x + sideX, y + 1, z + sideZ) == Block.DOOR;
        boolean reverseHinge = nearDoor && !farDoor || farWalls > nearWalls;
        if (reverseHinge) orientation = (orientation - 1) & 3 | 4;
        return orientation;
    }

    /** Key 3 uses raw pork when selected; other items retain their place action. */
    private void useSelectedItem() {
        if (world == null) return;
        // Mount only when the minecart itself is the object under the crosshair.
        // A nearby cart must not steal clicks intended for blocks or other objects.
        MinecartEntity targetedMinecart = renderer == null ? null : renderer.minecart();
        if (targetedMinecart != null && targetedMinecart.alive &&
                !ridingMinecart && !ridingBoat && minecartInCrosshair(targetedMinecart)) {
            ridingMinecart = true;
            playerPhysics.reset();
            return;
        }
        BoatEntity nearbyBoat = renderer == null ? null : renderer.boat();
        if (nearbyBoat != null && nearbyBoat.alive &&
                distanceSquared(world.playerX, world.playerZ, nearbyBoat.x, nearbyBoat.z) < 3.5f * 3.5f) {
            ridingBoat = true;
            world.yaw = nearbyBoat.yaw;
            return;
        }
        int selected = hotbar[world.selectedSlot];
        if (selected == ItemCatalog.BOAT) {
            placeBoat();
            return;
        }
        if (selected == ItemCatalog.MINECART) {
            placeMinecart();
            return;
        }
        if (ItemCatalog.isTool(selected) && ItemCatalog.kind(selected) == ItemCatalog.SWORD &&
                ItemCatalog.tier(selected) == ItemCatalog.DIAMOND_TIER) {
            int[] target = trace(5.0f);
            if (target != null && world.activatePortalAtWool(target[0], target[1], target[2])) {
                renderer.swingHand();
                if (world.mode == 0) damageTool(selected, 1);
                renderer.sync(world);
                resetMining();
                copyInventoryToWorld();
                world.save();
                repaint();
                return;
            }
        }
        if (selected == ItemCatalog.BOW) {
            if ((world.mode == 1 || inventory[ItemCatalog.ARROW] > 0) &&
                    renderer.shootPlayerArrow(world, cameraPitch)) {
                if (world.mode == 0) inventory[ItemCatalog.ARROW]--;
                renderer.swingHand();
                copyInventoryToWorld();
                world.save();
                repaint();
            }
            return;
        }
        if (selected == ItemCatalog.FLINT_AND_STEEL) {
            igniteBlock();
            return;
        }
        if (selected == ItemCatalog.EMPTY_BUCKET || selected == ItemCatalog.WATER_BUCKET ||
                selected == ItemCatalog.LAVA_BUCKET) {
            useBucket(selected);
            return;
        }
        if (world.mode == 0 && ItemCatalog.isFood(selected)) {
            if (inventory[selected] > 0 && world.health < 20) {
                world.health += ItemCatalog.foodHealing(selected);
                if (world.health > 20) world.health = 20;
                inventory[selected]--;
                if (selected == ItemCatalog.MUSHROOM_STEW &&
                        inventory[ItemCatalog.BOWL] < ItemCatalog.maxStack(ItemCatalog.BOWL))
                    inventory[ItemCatalog.BOWL]++;
                if (inventory[selected] == 0) restoreHotbarItem(selected);
                if (renderer != null) renderer.swingHand();
                copyInventoryToWorld();
                repaint();
            }
            return;
        }
        placeBlock();
    }

    private void placeBoat() {
        if (world == null || renderer == null) return;
        int selected = hotbar[world.selectedSlot];
        if (world.mode == 0 && inventory[selected] <= 0) return;
        double yaw = world.yaw * Math.PI / 180.0;
        double pitch = cameraPitch * Math.PI / 180.0;
        double dirX = Math.sin(yaw) * Math.cos(pitch);
        double dirY = Math.sin(pitch);
        double dirZ = Math.cos(yaw) * Math.cos(pitch);
        int bx = 0, by = 0, bz = 0;
        boolean found = false;
        // Block targeting intentionally skips fluids; boats therefore use a dedicated
        // short ray that finds the first water voxel rather than the shoreline behind it.
        for (float d = 0.35f; d <= 5.0f && !found; d += 0.12f) {
            int tx = floorBlock(world.playerX + (float)(dirX * d));
            int ty = floorBlock(world.playerY + PLAYER_EYE_HEIGHT + (float)(dirY * d));
            int tz = floorBlock(world.playerZ + (float)(dirZ * d));
            if (world.get(tx, ty, tz) == Block.WATER) {
                bx = tx; by = ty; bz = tz; found = true;
            }
        }
        if (!found) return;
        if (world.mode == 0) {
            inventory[selected]--;
            if (inventory[selected] <= 0) restoreHotbarItem(selected);
            copyInventoryToWorld();
        }
        renderer.spawnBoat(bx + 0.5f, by + 0.72f, bz + 0.5f);
        renderer.swingHand();
        world.save();
        repaint();
    }

    /** Mining/attack input breaks a nearby cart and drops it in survival. */
    private boolean breakNearbyMinecart() {
        if (renderer == null || world == null) return false;
        MinecartEntity cart = renderer.minecart();
        if (cart == null || !cart.alive) return false;
        float dx = world.playerX - cart.x;
        float dz = world.playerZ - cart.z;
        float dy = (world.playerY + PLAYER_EYE_HEIGHT) - cart.y;
        if (dx * dx + dz * dz > 2.7f * 2.7f || dy < -1.2f || dy > 2.2f) return false;
        int dropX = floorBlock(cart.x);
        int dropY = floorBlock(cart.y);
        int dropZ = floorBlock(cart.z);
        cart.alive = false;
        if (world.mode == 0) renderer.spawnDroppedItem(ItemCatalog.MINECART, dropX, dropY, dropZ);
        if (ridingMinecart) ridingMinecart = false;
        renderer.removeMinecart();
        renderer.swingHand();
        world.save();
        return true;
    }

    /** Alpha 1.2.6 ItemMinecart only places a cart on a rail block. */
    private void placeMinecart() {
        if (world == null || renderer == null) return;
        int selected = hotbar[world.selectedSlot];
        // This first implementation tracks one minecart at a time; do not
        // consume another item or silently replace the cart already in-world.
        if (renderer.minecart() != null) return;
        if (world.mode == 0 && inventory[selected] <= 0) return;
        int[] target = trace(5.0f);
        if (target == null || world.get(target[0], target[1], target[2]) != Block.RAIL) return;
        int x = target[0], y = target[1], z = target[2];
        if (world.mode == 0) {
            inventory[selected]--;
            if (inventory[selected] <= 0) restoreHotbarItem(selected);
            copyInventoryToWorld();
        }
        renderer.spawnMinecart(x + 0.5f, y + 0.18f, z + 0.5f);
        renderer.swingHand();
        world.save();
        repaint();
    }

    private static float distanceSquared(float x1, float z1, float x2, float z2) {
        float dx = x1 - x2, dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private void igniteBlock() {
        int[] target = trace(5.0f);
        if (target == null || target[3] == Integer.MIN_VALUE) return;
        int x = target[0] + target[3], y = target[1] + target[4], z = target[2] + target[5];
        if (y <= 0 || y >= VoxelWorld.HEIGHT || !Block.replaceable(world.get(x, y, z)) ||
                !Block.solid(world.get(x, y - 1, z))) return;
        if (!world.set(x, y, z, Block.FIRE)) return;
        renderer.swingHand();
        renderer.sync(world);
        copyInventoryToWorld();
        world.save();
    }

    private void useBucket(int selected) {
        int[] target = trace(5.0f);
        if (target == null) return;
        int x = target[0], y = target[1], z = target[2];
        byte hit = world.get(x, y, z);
        if (selected == ItemCatalog.EMPTY_BUCKET && Block.isFluid(hit) &&
                world.fluidLevel(x, y, z) == 0) {
            if (world.set(x, y, z, Block.AIR)) {
                if (world.mode == 0) inventory[selected]--;
                inventory[hit == Block.LAVA || world.nether && hit == Block.WATER ?
                        ItemCatalog.LAVA_BUCKET : ItemCatalog.WATER_BUCKET]++;
            }
        } else if (selected != ItemCatalog.EMPTY_BUCKET) {
            x += target[3]; y += target[4]; z += target[5];
            if (y <= 0 || y >= VoxelWorld.HEIGHT ||
                    !Block.replaceable(world.get(x, y, z))) return;
            if (world.nether && selected == ItemCatalog.WATER_BUCKET) {
                if (world.mode == 0) {
                    inventory[selected]--;
                    inventory[ItemCatalog.EMPTY_BUCKET]++;
                }
            } else if (world.set(x, y, z,
                    selected == ItemCatalog.LAVA_BUCKET ? Block.LAVA : Block.WATER)) {
                if (world.mode == 0) inventory[selected]--;
                inventory[ItemCatalog.EMPTY_BUCKET]++;
            }
        } else return;
        if (inventory[selected] <= 0) restoreHotbarItem(selected);
        renderer.swingHand();
        renderer.sync(world);
        copyInventoryToWorld();
        world.save();
    }

    private void travelThroughPortal() {
        boolean leavingNether = world.nether;
        float sourceX = world.playerX, sourceY = world.playerY, sourceZ = world.playerZ;
        int sx = (int)sourceX, sy = (int)sourceY, sz = (int)sourceZ;
        int[] source = null;
        for (int y = sy - 1; y <= sy + 2 && source == null; y++)
            for (int z = sz - 1; z <= sz + 1 && source == null; z++)
                for (int x = sx - 1; x <= sx + 1 && source == null; x++)
                    source = world.portalFrameAt(x, y, z);
        int axis = source == null ? 0 : source[3];

        // Remember the exact position in the dimension being left, so the
        // return trip goes back to the same portal instead of relying on 8:1 rounding.
        if (leavingNether) {
            netherPortalReturnX = sourceX;
            netherPortalReturnY = sourceY;
            netherPortalReturnZ = sourceZ;
            hasNetherPortalReturn = true;
        } else {
            overworldPortalReturnX = sourceX;
            overworldPortalReturnY = sourceY;
            overworldPortalReturnZ = sourceZ;
            hasOverworldPortalReturn = true;
        }

        float tx = leavingNether ? sourceX * 8.0f : sourceX / 8.0f;
        float tz = leavingNether ? sourceZ * 8.0f : sourceZ / 8.0f;
        world.switchDimension();

        // If this dimension has already been visited, restore its exact entry
        // position. This also avoids landing at a nearby, unrelated portal.
        boolean hasReturn = world.nether ? hasNetherPortalReturn : hasOverworldPortalReturn;
        if (hasReturn) {
            world.playerX = world.nether ? netherPortalReturnX : overworldPortalReturnX;
            world.playerY = world.nether ? netherPortalReturnY : overworldPortalReturnY;
            world.playerZ = world.nether ? netherPortalReturnZ : overworldPortalReturnZ;
        } else {
            int[] destination = world.findPortalNear((int)tx, (int)tz);
            if (destination == null) {
                int bx = (int)tx, bz = (int)tz;
                if (bx < 2) bx = 2;
                if (bz < 2) bz = 2;
                if (bx > VoxelWorld.WIDTH - 6) bx = VoxelWorld.WIDTH - 6;
                if (bz > VoxelWorld.DEPTH - 6) bz = VoxelWorld.DEPTH - 6;
                int by = world.surfaceY(bx + 1, bz + 1) + 1;
                if (by < 1) by = 1;
                if (by > VoxelWorld.HEIGHT - 6) by = VoxelWorld.HEIGHT - 6;
                destination = world.createPortalAt(bx, by, bz, axis);
            }
            if (destination == null) {
                world.switchDimension();
                portalChargeTicks = 0;
                return;
            }
            int[] d = destination;
            world.playerX = d[3] == 0 ? d[0] + 2.5f : d[0] + 0.5f;
            world.playerZ = d[3] == 0 ? d[2] + 0.5f : d[2] + 2.5f;
            world.playerY = d[1] + 1.05f;
        }

        // Keep the player above the destination floor if the saved Y is no
        // longer safe (for example, terrain changed around the portal).
        int px = (int)world.playerX, pz = (int)world.playerZ;
        int floor = world.surfaceY(px, pz);
        if (world.playerY < floor + 1.0f || world.playerY > VoxelWorld.HEIGHT - 2)
            world.playerY = floor + 1.05f;
        portalChargeTicks = 0;
        resetMining();
        renderer.sync(world);
        renderer.updateDayNight(world);
        renderer.updateCamera(world, cameraPitch);
        copyInventoryToWorld();
        world.save();
    }

    private void openChest(int x, int y, int z) {
        if (state != PLAYING || world == null) return;
        chestX=x; chestY=y; chestZ=z; chestSelection=0;
        loadChestContents(x,y,z);
        state=CHEST_INVENTORY;
        clearKeys();
        repaint();
    }

    private String chestStoreName(int x, int y, int z) {
        String base = world == null || world.saveStoreName == null ? "mecraft-save" : world.saveStoreName;
        return "mcch" + Integer.toHexString((base + ":" + world.nether + ":" + x + ":" + y + ":" + z).hashCode());
    }

    private void loadChestContents(int x, int y, int z) {
        chestStoreName=chestStoreName(x,y,z);
        for(int i=0;i<27;i++){chestItems[i]=0;chestCounts[i]=0;}
        RecordStore rs=null;
        try {
            rs=RecordStore.openRecordStore(chestStoreName,false);
            if(rs.getNumRecords()>0){
                byte[] data=rs.getRecord(1);
                DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
                if(in.readInt()==0x4D434843){
                    for(int i=0;i<27;i++){chestItems[i]=in.readInt();chestCounts[i]=in.readInt();
                        if(chestItems[i]<0||chestItems[i]>=ItemCatalog.COUNT||chestCounts[i]<0||chestCounts[i]>64){chestItems[i]=0;chestCounts[i]=0;}}
                }
                in.close();
            }
        } catch(Exception ignored) { }
        finally { if(rs!=null) try{rs.closeRecordStore();}catch(Exception ignored){} }
    }

    private void saveChestContents() {
        if(chestStoreName==null)return;
        RecordStore rs=null;
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(220);
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(0x4D434843);
            for(int i=0;i<27;i++){out.writeInt(chestItems[i]);out.writeInt(chestCounts[i]);}
            out.flush(); byte[] data=bytes.toByteArray();
            rs=RecordStore.openRecordStore(chestStoreName,true);
            if(rs.getNumRecords()==0)rs.addRecord(data,0,data.length);else rs.setRecord(1,data,0,data.length);
            out.close();
        } catch(Exception ignored) { }
        finally {if(rs!=null)try{rs.closeRecordStore();}catch(Exception ignored){}}
    }

    private void clearChestContents() {
        if(chestStoreName==null)return;
        try{RecordStore.deleteRecordStore(chestStoreName);}catch(Exception ignored){}
        chestStoreName=null;
    }

    private void transferChestSlot() {
        if (world == null) return;
        int row = chestSelection / 9, col = chestSelection % 9;
        if (row < 3) {
            int slot = row * 9 + col;
            int id = chestItems[slot], amount = chestCounts[slot];
            if (id > 0 && amount > 0) {
                int moved = Math.min(Math.max(0, 64 - inventory[id]), amount);
                if (moved > 0) {
                    inventory[id] += moved;
                    chestCounts[slot] -= moved;
                    if (chestCounts[slot] <= 0) { chestItems[slot] = 0; chestCounts[slot] = 0; }
                    if (hotbarIndex(id) < 0) hotbar[world.selectedSlot] = id;
                }
            }
        } else {
            int id = row < 6 ? inventoryCellItem((row - 3) * 9 + col) : hotbar[col];
            if (id > 0 && id < ItemCatalog.COUNT && inventory[id] > 0) {
                int destination = -1;
                for (int i = 0; i < 27; i++) {
                    if (chestItems[i] == id && chestCounts[i] < 64) { destination = i; break; }
                    if (destination < 0 && chestItems[i] == 0) destination = i;
                }
                if (destination >= 0) {
                    int room = chestItems[destination] == id ? 64 - chestCounts[destination] : 64;
                    int moved = Math.min(room, inventory[id]);
                    if (moved > 0) {
                        if (chestItems[destination] == 0) chestItems[destination] = id;
                        chestCounts[destination] += moved;
                        inventory[id] -= moved;
                        if (inventory[id] <= 0) { inventory[id] = 0; restoreHotbarItem(id); }
                    }
                }
            }
        }
        copyInventoryToWorld();
        saveChestContents();
        repaint();
    }

    private void drawChest(Graphics g) {
        int w = getWidth(), h = getHeight(), pw = 176, ph = 166;
        int left = (w - pw) / 2, top = (h - ph) / 2;

        // Alpha 1.2.6 container.png chest background is exactly 176x166.
        // Never crop the extra atlas area below it: that area belongs to
        // other GUI elements and is what made the chest look like the title UI.
        if (containerTexture != null && containerTexture.getWidth() >= pw &&
                containerTexture.getHeight() >= ph) {
            g.drawRegion(containerTexture, 0, 0, pw, ph, Sprite.TRANS_NONE,
                    left, top, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(198, 198, 198); g.fillRect(left, top, pw, ph);
            g.setColor(35, 35, 35); g.drawRect(left, top, pw - 1, ph - 1);
        }

        g.setColor(35, 35, 35);
        g.drawString(Language.text("Cofre"), left + 8, top + 6, Graphics.TOP | Graphics.LEFT);
        g.drawString(Language.text("Inventario"), left + 8, top + 73, Graphics.TOP | Graphics.LEFT);

        // Chest inventory: 3 rows x 9 columns, matching vanilla slot anchors.
        for (int i = 0; i < 27; i++) {
            int x = left + 8 + (i % 9) * 18;
            int y = top + 18 + (i / 9) * 18;
            if (i == chestSelection) {
                g.setColor(255, 255, 160);
                g.drawRect(x - 1, y - 1, 17, 17);
            }
            if (chestItems[i] > 0 && chestCounts[i] > 0) {
                drawIcon(g, chestItems[i], x, y, false);
                if (chestCounts[i] > 1)
                    drawOutlinedCount(g, Integer.toString(chestCounts[i]), x + 16, y + 16,
                            Graphics.RIGHT | Graphics.BOTTOM);
            }
        }

        // Player inventory: 3 rows and a hotbar, all 36 slots. These rows
        // were missing before, leaving the chest GUI visibly incomplete.
        for (int i = 0; i < 27; i++) {
            int x = left + 8 + (i % 9) * 18;
            int y = top + 85 + (i / 9) * 18;
            if (chestSelection >= 27 && chestSelection < 54 && i == chestSelection - 27) {
                g.setColor(255, 255, 160); g.drawRect(x - 1, y - 1, 17, 17);
            }
            int id = inventoryCellItem(i);
            if (id > 0 && id < ItemCatalog.COUNT) {
                drawIcon(g, id, x, y, false);
                if (!ItemCatalog.isTool(id) && inventory[id] > 1)
                    drawOutlinedCount(g, Integer.toString(inventory[id]), x + 16, y + 16,
                            Graphics.RIGHT | Graphics.BOTTOM);
            }
        }
        for (int i = 0; i < 9; i++) {
            int x = left + 8 + i * 18, y = top + 143;
            if (i == world.selectedSlot) {
                g.setColor(255, 255, 160);
                g.drawRect(x - 1, y - 1, 17, 17);
            }
            if (chestSelection >= 54 && chestSelection - 54 == i) {
                g.setColor(255, 255, 160); g.drawRect(x - 1, y - 1, 17, 17);
            }
            int id = hotbar[i];
            if (id > 0 && id < ItemCatalog.COUNT && inventory[id] > 0) {
                drawIcon(g, id, x, y, false);
                if (!ItemCatalog.isTool(id) && inventory[id] > 1)
                    drawOutlinedCount(g, Integer.toString(inventory[id]), x + 16, y + 16,
                            Graphics.RIGHT | Graphics.BOTTOM);
            }
        }
        g.setColor(35, 35, 35);
        g.drawString(Language.text("2/8 fila  4/6 mover  5 transferir  # cerrar"), w / 2, top + ph + 1,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void openCrafting(boolean workbench) {
        if (state != PLAYING || world == null || (world.mode == 1 && !workbench)) return;
        state = workbench ? WORKBENCH : INVENTORY;
        recipeSelection = 0;
        recipeScroll = 0;
        inventoryPage = 0;
        clearKeys();
        repaint();
    }

    private void closeCrafting() {
        if (state == CHEST_INVENTORY) { saveChestContents(); state = PLAYING; repaint(); return; }
        if (state != INVENTORY && state != WORKBENCH) return;
        state = PLAYING;
        repaint();
    }

    private void craftSelected() {
        boolean workbench = state == WORKBENCH;
        if (state != INVENTORY && !workbench) return;
        int recipeCount = Crafting.count(workbench);
        if (recipeSelection < 0 || recipeSelection >= recipeCount) return;
        if (!workbench && Crafting.requiresWorkbench(recipeSelection)) return;
        int output = Crafting.OUTPUT[recipeSelection];
        int slot = hotbarIndex(output);
        if (slot < 0) slot = emptyHotbarIndex();
        if (slot < 0 && world.mode == 1) slot = 8;
        if (slot < 0) return;
        if (!Crafting.craft(recipeSelection, inventory, world.mode == 1)) return;
        hotbar[slot] = output;
        world.selectedSlot = slot;
        copyInventoryToWorld();
        repaint();
    }

    private void moveRecipeSelection(int delta) {
        int count = Crafting.count(state == WORKBENCH);
        recipeSelection += delta;
        if (recipeSelection < 0) recipeSelection = count - 1;
        if (recipeSelection >= count) recipeSelection = 0;
        if (recipeSelection < recipeScroll) recipeScroll = recipeSelection;
        if (recipeSelection >= recipeScroll + 6) recipeScroll = recipeSelection - 5;
        repaint();
    }

    private void moveInventoryPage(int delta) {
        int pages = inventoryPageCount();
        inventoryPage = (inventoryPage + delta + pages) % pages;
        repaint();
    }

    private int inventoryPageCount() {
        int visibleBlocks = 0;
        for (int id = 1; id < Block.DECORATIVE_START; id++)
            if (id != Block.WATER && id != Block.BEDROCK && id != Block.LAVA) visibleBlocks++;
        int pageSize = 27 - visibleBlocks - 1;
        int items = ItemCatalog.COUNT - Block.COUNT;
        return Math.max(1, (items + pageSize - 1) / pageSize);
    }

    /** Short ray march; returns hit voxel and the previous empty voxel offset. */
    private int[] trace(float reach) {
        double yaw = world.yaw * Math.PI / 180.0;
        double pitch = cameraPitch * Math.PI / 180.0;
        float ox = world.playerX;
        float oy = world.playerY + PLAYER_EYE_HEIGHT;
        float oz = world.playerZ;
        double dirX = Math.sin(yaw) * Math.cos(pitch);
        double dirY = Math.sin(pitch);
        double dirZ = Math.cos(yaw) * Math.cos(pitch);
        int oldX = floorBlock(ox), oldY = floorBlock(oy), oldZ = floorBlock(oz);
        for (float d = 0.35f; d <= reach; d += 0.12f) {
            float x = ox + (float)(dirX * d);
            float y = oy + (float)(dirY * d);
            float z = oz + (float)(dirZ * d);
            int bx = floorBlock(x), by = floorBlock(y), bz = floorBlock(z);
            byte id = world.get(bx, by, bz);
            if (id != Block.AIR && !Block.isFluid(id)) {
                int[] hit = traceResult;
                hit[0] = bx; hit[1] = by; hit[2] = bz;
                hit[3] = oldX - bx; hit[4] = oldY - by; hit[5] = oldZ - bz;
                return hit;
            }
            oldX = bx; oldY = by; oldZ = bz;
        }
        return null;
    }

    private int hotbarIndex(int id) {
        for (int i = 0; i < hotbar.length; i++) if (hotbar[i] == id) return i;
        return -1;
    }

    private int emptyHotbarIndex() {
        for (int i = 0; i < hotbar.length; i++) if (inventory[hotbar[i]] == 0) return i;
        return -1;
    }

    private void ensureHotbarItem(int id) {
        if (hotbarIndex(id) >= 0) return;
        int slot = emptyHotbarIndex();
        if (slot >= 0) hotbar[slot] = id;
    }

    private void restoreHotbarItem(int id) {
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] == id) hotbar[i] = DEFAULT_HOTBAR[i];
        }
    }

    /** Java ME compatible floor conversion, including negative coordinates. */
    private int floorBlock(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    public synchronized void keyPressed(int keyCode) {
        if (state == TOUCH_SETUP) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_NUM2 || keyCode == KEY_NUM4 || action == UP || action == LEFT) {
                touchSetupSelection = 0;
                repaint();
            } else if (keyCode == KEY_NUM8 || keyCode == KEY_NUM6 || action == DOWN || action == RIGHT) {
                touchSetupSelection = 1;
                repaint();
            } else if (keyCode == KEY_NUM1) chooseTouchMode(true);
            else if (keyCode == KEY_NUM0 || keyCode == KEY_NUM3) chooseTouchMode(false);
            else if (keyCode == KEY_NUM5 || action == FIRE) chooseTouchMode(touchSetupSelection == 0);
            return;
        }
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
        if (state == WORLD_SELECT) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_NUM2 || action == UP) moveWorldSelection(-1);
            else if (keyCode == KEY_NUM8 || action == DOWN) moveWorldSelection(1);
            else if (keyCode == KEY_NUM1 && worldSelection < savedWorlds.length) {
                worldDeleteSelection = worldSelection;
                worldDeleteFailed = false;
                state = WORLD_DELETE;
                repaint();
            }
            else if (keyCode == KEY_NUM5 || action == FIRE) activateWorldSelection();
            else if (keyCode == KEY_NUM0) backToMainMenu();
            return;
        }
        if (state == WORLD_DELETE) {
            if (keyCode == KEY_NUM5 || gameAction(keyCode) == FIRE) {
                if (worldDeleteSelection >= 0 && worldDeleteSelection < savedWorlds.length &&
                    VoxelWorld.deleteWorld(savedWorlds[worldDeleteSelection].storeName)) {
                    refreshWorldList();
                    worldSelection = worldDeleteSelection;
                    if (worldSelection > savedWorlds.length) worldSelection = savedWorlds.length;
                    state = WORLD_SELECT;
                } else worldDeleteFailed = true;
                repaint();
            } else if (keyCode == KEY_NUM0) {
                state = WORLD_SELECT;
                repaint();
            }
            return;
        }
        if (state == WORLD_CREATE) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_NUM2 || action == UP) {
                if (createSelection > 0) createSelection--;
                repaint();
            } else if (keyCode == KEY_NUM8 || action == DOWN) {
                if (createSelection < 5) createSelection++;
                repaint();
            } else if (keyCode == KEY_NUM4 || action == LEFT) changeCreateOption(-1);
            else if (keyCode == KEY_NUM6 || action == RIGHT) changeCreateOption(1);
            else if (keyCode == KEY_NUM5 || action == FIRE) activateCreateSelection();
            else if (keyCode == KEY_NUM0) { state = WORLD_SELECT; repaint(); }
            return;
        }
        if (state == WORLD_NAME) {
            return;
        }
        if (state == SETTINGS) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_NUM2 || action == UP) {
                settingsSelection = (settingsSelection + 6) % 7;
                repaint();
            } else if (keyCode == KEY_NUM8 || action == DOWN) {
                settingsSelection = (settingsSelection + 1) % 7;
                repaint();
            } else if (settingsSelection == 0 && (keyCode == KEY_NUM4 || action == LEFT)) changeFrameSkip(-1);
            else if (settingsSelection == 0 && (keyCode == KEY_NUM6 || action == RIGHT)) changeFrameSkip(1);
            else if (settingsSelection == 1 && (keyCode == KEY_NUM4 || action == LEFT)) changeSensitivity(-1);
            else if (settingsSelection == 1 && (keyCode == KEY_NUM6 || action == RIGHT)) changeSensitivity(1);
            else if (settingsSelection == 2 && (keyCode == KEY_NUM4 || action == LEFT)) changeRenderDistance(-1);
            else if (settingsSelection == 2 && (keyCode == KEY_NUM6 || action == RIGHT)) changeRenderDistance(1);
            else if (settingsSelection == 3 && (keyCode == KEY_NUM5 || action == FIRE)) openSkinSelect();
            else if (settingsSelection == 4 && (keyCode == KEY_NUM4 || action == LEFT ||
                    keyCode == KEY_NUM6 || action == RIGHT)) changeLanguage();
            else if (settingsSelection == 5 && (keyCode == KEY_NUM5 || action == FIRE)) {
                controlsReturnSettings = true;
                state = CONTROLS;
                repaint();
            } else if (settingsSelection == 6 && (keyCode == KEY_NUM4 || action == LEFT ||
                    keyCode == KEY_NUM6 || action == RIGHT || keyCode == KEY_NUM5 || action == FIRE)) {
                touchEnabled = !touchEnabled;
                saveSettings();
                repaint();
            }
            else if (keyCode == KEY_NUM0 || keyCode == KEY_POUND) {
                state = MENU;
                repaint();
            }
            return;
        }
        if (state == CONTROLS) {
            int action = gameAction(keyCode);
            if (controlsListening) {
                if (keyCode == KEY_NUM0) {
                    controlsListening = false;
                    controlsMessage = "Asignacion cancelada";
                } else assignControl(keyCode);
                repaint();
            } else if (keyCode == KEY_NUM7) {
                customControls[controlsSelection] = false;
                controlCodes[controlsSelection] = 0;
                controlsMessage = "Control restaurado";
                saveSettings();
                repaint();
            } else if (keyCode == KEY_NUM2 || action == UP) moveControlSelection(-1);
            else if (keyCode == KEY_NUM8 || action == DOWN) moveControlSelection(1);
            else if (keyCode == KEY_NUM5 || action == FIRE) {
                controlsListening = true;
                controlsMessage = "Pulsa una tecla nueva (0 cancela)";
                repaint();
            } else if (keyCode == KEY_NUM0 || keyCode == KEY_POUND) {
                controlsListening = false;
                state = controlsReturnSettings ? SETTINGS : MENU;
                repaint();
            }
            return;
        }
        if (state == SKIN_SELECT) {
            if (keyCode == KEY_NUM4 || gameAction(keyCode) == LEFT) {
                skinSelection = (skinSelection + 4) % 5;
                if (skinGallery != null) skinGallery.selectSkin(skinSelection);
                repaint();
            } else if (keyCode == KEY_NUM6 || gameAction(keyCode) == RIGHT) {
                skinSelection = (skinSelection + 1) % 5;
                if (skinGallery != null) skinGallery.selectSkin(skinSelection);
                repaint();
            } else if (keyCode == KEY_NUM5 || gameAction(keyCode) == FIRE) {
                selectedPlayerSkin = skinSelection;
                if (renderer != null) renderer.setPlayerSkinIndex(selectedPlayerSkin);
                saveSettings();
                skinGallery = null;
                state = SETTINGS;
                repaint();
            } else if (keyCode == KEY_NUM0 || keyCode == KEY_POUND) {
                skinGallery = null;
                state = SETTINGS;
                repaint();
            }
            return;
        }
        if (state == GAME_OVER) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_NUM2 || action == UP) {
                gameOverSelection = (gameOverSelection + 1) % 2;
                repaint();
            } else if (keyCode == KEY_NUM8 || action == DOWN) {
                gameOverSelection = (gameOverSelection + 1) % 2;
                repaint();
            } else if (keyCode == KEY_NUM5 || action == FIRE) activateGameOverSelection();
            else if (keyCode == KEY_NUM0) {
                gameOverSelection = 1;
                activateGameOverSelection();
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
        if (state == CHEST_INVENTORY) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_POUND || keyCode == KEY_NUM0) closeCrafting();
            else if (keyCode == KEY_NUM2 || action == UP) chestSelection = (chestSelection + 54) % 63;
            else if (keyCode == KEY_NUM8 || action == DOWN) chestSelection = (chestSelection + 9) % 63;
            else if (keyCode == KEY_NUM4 || action == LEFT) chestSelection = chestSelection % 9 == 0 ? chestSelection + 8 : chestSelection - 1;
            else if (keyCode == KEY_NUM6 || action == RIGHT) chestSelection = chestSelection % 9 == 8 ? chestSelection - 8 : chestSelection + 1;
            else if (keyCode == KEY_NUM5 || action == FIRE) transferChestSlot();
            repaint();
            return;
        }
        if (state == INVENTORY || state == WORKBENCH) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_POUND || keyCode == KEY_NUM0) closeCrafting();
            else if (keyCode == KEY_NUM2 || action == UP) moveRecipeSelection(-1);
            else if (keyCode == KEY_NUM8 || action == DOWN) moveRecipeSelection(1);
            else if (keyCode == KEY_NUM4 || action == LEFT) moveInventoryPage(-1);
            else if (keyCode == KEY_NUM6 || action == RIGHT) moveInventoryPage(1);
            else if (keyCode == KEY_NUM5 || action == FIRE) craftSelected();
            return;
        }
        if (state == CREATIVE_INVENTORY) {
            int action = gameAction(keyCode);
            if (keyCode == KEY_POUND || keyCode == KEY_NUM0) closeCreativeInventory();
            else if (keyCode == KEY_NUM2 || action == UP) moveCreativeSelection(-9);
            else if (keyCode == KEY_NUM8 || action == DOWN) moveCreativeSelection(9);
            else if (keyCode == KEY_NUM4 || action == LEFT) moveCreativeSelection(-1);
            else if (keyCode == KEY_NUM6 || action == RIGHT) moveCreativeSelection(1);
            else if (keyCode == KEY_NUM5 || action == FIRE) selectCreativeBlock();
            return;
        }
        if (state != PLAYING) return;
        if (isControlKey(CONTROL_INVENTORY, keyCode, KEY_POUND, 0)) {
            if (world.mode == 1) openCreativeInventory();
            else openCrafting(false);
            return;
        }
        if (isControlKey(CONTROL_FORWARD, keyCode, KEY_NUM2, 0)) {
            if (!keys[2]) {
                boolean canStartSprint = playerPhysics.isOnGround() &&
                        !(world.mode == 1 && flying) && !playerPhysics.isInWater(world);
                if (canStartSprint && sprintToggleTimer > 0) {
                    sprinting = true;
                    sprintToggleTimer = 0;
                } else {
                    sprinting = false;
                    sprintToggleTimer = canStartSprint ? 7 : 0;
                }
            }
            keys[2] = true;
        }
        else if (isControlKey(CONTROL_BACK, keyCode, KEY_NUM8, 0)) keys[8] = true;
        else if (isControlKey(CONTROL_STRAFE_LEFT, keyCode, KEY_NUM4, 0)) keys[4] = true;
        else if (isControlKey(CONTROL_STRAFE_RIGHT, keyCode, KEY_NUM6, 0)) keys[6] = true;
        else if (isControlKey(CONTROL_MINE, keyCode, KEY_NUM1, 0)) {
            if (!keys[1] && renderer != null) {
                if (breakNearbyMinecart()) {
                    keys[1] = true;
                    return;
                }
                int mobHit = renderer.hitMob(world, cameraPitch, heldAttackDamage());
                if (mobHit >= 0) {
                    if (mobHit > 0) onMobHit();
                    keys[1] = true;
                    return;
                }
                renderer.swingHand();
            }
            keys[1] = true;
        }
        if (isControlKey(CONTROL_LOOK_UP, keyCode, 0, UP)) lookUp = true;
        if (isControlKey(CONTROL_LOOK_DOWN, keyCode, 0, DOWN)) lookDown = true;
        if (isControlKey(CONTROL_LOOK_LEFT, keyCode, 0, LEFT)) lookLeft = true;
        if (isControlKey(CONTROL_LOOK_RIGHT, keyCode, 0, RIGHT)) lookRight = true;
        boolean toggledFlight = false;
        if (isControlKey(CONTROL_JUMP, keyCode, KEY_NUM5, 0)) {
            if (!jumpHeld && world.mode == 1) {
                long now = System.currentTimeMillis();
                if (hasJumpTap && now - lastJumpTap <= 350L) {
                    flying = !flying;
                    toggledFlight = true;
                    hasJumpTap = false;
                    playerPhysics.clearVerticalMotion();
                } else {
                    hasJumpTap = true;
                    lastJumpTap = now;
                }
            }
            jumpHeld = !(toggledFlight && !flying);
        }
        if (isControlKey(CONTROL_DESCEND, keyCode, KEY_NUM0, 0) && world.mode == 1) descendHeld = true;
        if (isControlKey(CONTROL_USE, keyCode, KEY_NUM3, 0)) useSelectedItem();
        if (isControlKey(CONTROL_SLOT_PREVIOUS, keyCode, KEY_NUM7, 0)) cycleSlot(-1);
        if (isControlKey(CONTROL_SLOT_NEXT, keyCode, KEY_NUM9, 0)) cycleSlot(1);
        if (isControlKey(CONTROL_PAUSE, keyCode, KEY_STAR, 0)) openPause();
    }

    public synchronized void keyReleased(int keyCode) {
        if (isControlKey(CONTROL_JUMP, keyCode, KEY_NUM5, 0)) jumpHeld = false;
        if (isControlKey(CONTROL_DESCEND, keyCode, KEY_NUM0, 0)) descendHeld = false;
        if (isControlKey(CONTROL_MINE, keyCode, KEY_NUM1, 0)) cancelMining();
        else if (isControlKey(CONTROL_FORWARD, keyCode, KEY_NUM2, 0)) {
            keys[2] = false;
            sprinting = false;
        }
        else if (isControlKey(CONTROL_BACK, keyCode, KEY_NUM8, 0)) keys[8] = false;
        else if (isControlKey(CONTROL_STRAFE_LEFT, keyCode, KEY_NUM4, 0)) keys[4] = false;
        else if (isControlKey(CONTROL_STRAFE_RIGHT, keyCode, KEY_NUM6, 0)) keys[6] = false;
        if (isControlKey(CONTROL_LOOK_UP, keyCode, 0, UP)) lookUp = false;
        if (isControlKey(CONTROL_LOOK_DOWN, keyCode, 0, DOWN)) lookDown = false;
        if (isControlKey(CONTROL_LOOK_LEFT, keyCode, 0, LEFT)) lookLeft = false;
        if (isControlKey(CONTROL_LOOK_RIGHT, keyCode, 0, RIGHT)) lookRight = false;
    }

    private void moveSelection(int delta) {
        if (state == MENU) {
            menuSelection = (menuSelection + delta + 4) % 4;
        } else if (state == PAUSED) {
            pauseSelection += delta;
            if (pauseSelection < 0) pauseSelection = 2;
            if (pauseSelection > 2) pauseSelection = 0;
        }
    }

    private void changeFrameSkip(int delta) {
        frameSkip += delta;
        if (frameSkip < 0) frameSkip = 2;
        if (frameSkip > 2) frameSkip = 0;
        saveSettings();
        repaint();
    }

    private float cameraSensitivityScale() {
        if (cameraSensitivity == 0) return 0.5f;
        if (cameraSensitivity == 1) return 0.75f;
        if (cameraSensitivity == 3) return 1.25f;
        if (cameraSensitivity >= 4) return 1.5f;
        return 1.0f;
    }

    private void changeSensitivity(int delta) {
        cameraSensitivity = (cameraSensitivity + delta + 5) % 5;
        saveSettings();
        repaint();
    }

    private void changeRenderDistance(int delta) {
        renderDistance += delta;
        if (renderDistance < 0) renderDistance = 3;
        if (renderDistance > 3) renderDistance = 0;
        saveSettings();
        repaint();
    }

    private void saveSettings() {
        GameSettings.save(new int[] { frameSkip, cameraSensitivity, selectedPlayerSkin,
                                      language, renderDistance, touchEnabled ? 1 : 0 },
                controlCodes, customControls);
    }

    private void changeLanguage() {
        language = language == Language.ENGLISH ? Language.SPANISH : Language.ENGLISH;
        Language.set(language);
        if ("World 1".equals(newWorldName) || "Mundo 1".equals(newWorldName))
            newWorldName = defaultWorldName(savedWorlds.length + 1);
        saveSettings();
        repaint();
    }

    private String defaultWorldName(int number) {
        return Language.text("Mundo") + " " + number;
    }

    private boolean isControlKey(int control, int keyCode, int defaultKeyCode, int defaultAction) {
        if (customControls[control]) return controlCodes[control] == keyCode;
        for (int i = 0; i < customControls.length; i++)
            if (i != control && customControls[i] && controlCodes[i] == keyCode) return false;
        if (defaultKeyCode != 0 && keyCode == defaultKeyCode) return true;
        return defaultAction != 0 && !isNumericKey(keyCode) && gameAction(keyCode) == defaultAction;
    }

    private boolean isNumericKey(int keyCode) {
        return keyCode >= KEY_NUM0 && keyCode <= KEY_NUM9;
    }

    private void assignControl(int keyCode) {
        for (int i = 0; i < customControls.length; i++) {
            if (i != controlsSelection && customControls[i] && controlCodes[i] == keyCode) {
                controlsMessage = "Esa tecla ya esta asignada";
                return;
            }
        }
        controlCodes[controlsSelection] = keyCode;
        customControls[controlsSelection] = true;
        controlsListening = false;
        controlsMessage = "Control guardado";
        saveSettings();
    }

    private void moveControlSelection(int delta) {
        controlsSelection = (controlsSelection + delta + CONTROL_COUNT) % CONTROL_COUNT;
        int rows = controlsVisibleRows();
        if (controlsSelection < controlsScroll) controlsScroll = controlsSelection;
        else if (controlsSelection >= controlsScroll + rows) controlsScroll = controlsSelection - rows + 1;
        controlsMessage = null;
        repaint();
    }

    private int controlsVisibleRows() {
        int height = getHeight();
        int panelY = Math.max(12, (height - 238) / 2);
        int panelHeight = Math.min(height - panelY - 8, 238);
        int rowHeight = Font.getDefaultFont().getHeight() + 3;
        return Math.max(1, (panelHeight - 86) / rowHeight);
    }

    private String controlKeyName(int control) {
        if (!customControls[control]) {
            if (control == CONTROL_LOOK_UP) return "Dpad arriba";
            if (control == CONTROL_LOOK_DOWN) return "Dpad abajo";
            if (control == CONTROL_LOOK_LEFT) return "Dpad izquierda";
            if (control == CONTROL_LOOK_RIGHT) return "Dpad derecha";
            if (control == CONTROL_FORWARD) return "2";
            if (control == CONTROL_BACK) return "8";
            if (control == CONTROL_STRAFE_LEFT) return "4";
            if (control == CONTROL_STRAFE_RIGHT) return "6";
            if (control == CONTROL_JUMP) return "5";
            if (control == CONTROL_MINE) return "1";
            if (control == CONTROL_USE) return "3";
            if (control == CONTROL_INVENTORY) return "#";
            if (control == CONTROL_SLOT_PREVIOUS) return "7";
            if (control == CONTROL_SLOT_NEXT) return "9";
            if (control == CONTROL_PAUSE) return "*";
            if (control == CONTROL_DESCEND) return "0";
        }
        int code = controlCodes[control];
        if (code >= KEY_NUM0 && code <= KEY_NUM9) return Integer.toString(code - KEY_NUM0);
        if (code == KEY_POUND) return "#";
        if (code == KEY_STAR) return "*";
        int action = gameAction(code);
        if (action == UP) return "Dpad arriba";
        if (action == DOWN) return "Dpad abajo";
        if (action == LEFT) return "Dpad izquierda";
        if (action == RIGHT) return "Dpad derecha";
        return Integer.toString(code);
    }

    private void openSkinSelect() {
        skinSelection = selectedPlayerSkin;
        try {
            skinGallery = new SkinGallery(getWidth(), Math.max(1, getHeight() - 120));
            skinGallery.selectSkin(skinSelection);
            state = SKIN_SELECT;
            repaint();
        } catch (IOException unavailable) {
            showError("No se pudieron cargar las texturas de jugador.");
        }
    }

    private void openCreativeInventory() {
        if (state != PLAYING || world == null || world.mode != 1) return;
        state = CREATIVE_INVENTORY;
        creativeSelection = 0;
        clearKeys();
        repaint();
    }

    private void closeCreativeInventory() {
        if (state != CREATIVE_INVENTORY) return;
        state = PLAYING;
        repaint();
    }

    private void moveCreativeSelection(int delta) {
        int count = creativeItemCount();
        creativeSelection = (creativeSelection + delta) % count;
        if (creativeSelection < 0) creativeSelection += count;
        repaint();
    }

    private void selectCreativeBlock() {
        int item = creativeItemAt(creativeSelection);
        if (item <= Block.AIR || item >= ItemCatalog.COUNT) return;
        hotbar[world.selectedSlot] = item;
        inventory[item] = ItemCatalog.maxStack(item);
        copyInventoryToWorld();
        closeCreativeInventory();
    }

    private int creativeItemCount() {
        return Block.CREATIVE_PALETTE.length + ItemCatalog.COUNT - Block.COUNT;
    }

    private int creativeItemAt(int index) {
        int toolItems = 1 + ItemCatalog.TOOL_KINDS * ItemCatalog.TOOL_TIERS;
        if (index < toolItems) return Block.COUNT + index;
        index -= toolItems;
        if (index < Block.CREATIVE_PALETTE.length) return Block.CREATIVE_PALETTE[index];
        index -= Block.CREATIVE_PALETTE.length;
        return ItemCatalog.IRON_INGOT + index;
    }

    private void moveWorldSelection(int delta) {
        int count = savedWorlds.length + 2;
        worldSelection = (worldSelection + delta + count) % count;
        repaint();
    }

    private void activateWorldSelection() {
        if (worldSelection < savedWorlds.length) {
            openSavedWorld(savedWorlds[worldSelection]);
        } else if (worldSelection == savedWorlds.length) {
            newWorldName = defaultWorldName(savedWorlds.length + 1);
            newWorldMode = 0;
            newWorldSize = VoxelWorld.SIZE_INFINITE;
            newWorldType = VoxelWorld.TYPE_STANDARD;
            createSelection = 0;
            state = WORLD_CREATE;
            repaint();
        } else {
            backToMainMenu();
        }
    }

    private void activateCreateSelection() {
        if (createSelection == 0) {
            openNativeWorldNameInput();
        } else if (createSelection >= 1 && createSelection <= 3) {
            changeCreateOption(1);
        } else if (createSelection == 4) {
            String name = newWorldName == null ? "" : newWorldName.trim();
            if (name.length() == 0) name = defaultWorldName(savedWorlds.length + 1);
            startNewWorld(newWorldMode, newWorldSize, newWorldType, name);
        } else {
            state = WORLD_SELECT;
            repaint();
        }
    }

    private void changeCreateOption(int delta) {
        if (createSelection == 1) newWorldMode = 1 - newWorldMode;
        else if (createSelection == 2) newWorldSize = 1 - newWorldSize;
        else if (createSelection == 3) newWorldType = 1 - newWorldType;
        repaint();
    }

    /** Opens MIDP's native text editor so hardware QWERTY keyboards work. */
    private void openNativeWorldNameInput() {
        try {
            String initialName = newWorldName == null ? "" : newWorldName;
            if (initialName.length() > 24) initialName = initialName.substring(0, 24);
            worldNameInput = new TextBox(Language.text("Nombre del mundo"), initialName, 24, TextField.ANY);
            acceptWorldName = new Command(Language.text("Aceptar"), Command.OK, 1);
            cancelWorldName = new Command(Language.text("Cancelar"), Command.CANCEL, 2);
            worldNameInput.addCommand(acceptWorldName);
            worldNameInput.addCommand(cancelWorldName);
            worldNameInput.setCommandListener(this);
            state = WORLD_NAME;
            Display.getDisplay(app).setCurrent(worldNameInput);
        } catch (RuntimeException unavailable) {
            worldNameInput = null;
            state = WORLD_CREATE;
            repaint();
        }
    }

    public synchronized void commandAction(Command command, Displayable displayable) {
        if (displayable != worldNameInput || state != WORLD_NAME) return;
        if (command == acceptWorldName) {
            String value = worldNameInput.getString().trim();
            newWorldName = value.length() == 0 ? defaultWorldName(savedWorlds.length + 1) : value;
        } else if (command != cancelWorldName) return;
        worldNameInput = null;
        acceptWorldName = null;
        cancelWorldName = null;
        state = WORLD_CREATE;
        Display.getDisplay(app).setCurrent(this);
        repaint();
    }

    private void activateSelection() {
        if (state == MENU) {
            if (menuSelection == MENU_SINGLEPLAYER) {
                refreshWorldList();
                worldSelection = 0;
                state = WORLD_SELECT;
                repaint();
            }
            else if (menuSelection == MENU_CONTROLS) {
                controlsReturnSettings = false;
                controlsSelection = controlsScroll = 0;
                state = CONTROLS;
                repaint();
            }
            else if (menuSelection == MENU_SETTINGS) { state = SETTINGS; repaint(); }
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
        backToMainMenu();
    }

    private void backToMainMenu() {
        refreshWorldList();
        state = MENU;
        menuSelection = MENU_SINGLEPLAYER;
        clearKeys();
        renderer = null;
        world = null;
        System.gc();
        loadTitle();
    }

    private void loadTitle() {
        try {
            Image source = GameAssets.image("title.png");
            title = scaleMenuLogo(source);
        } catch (Throwable ignored) { title = null; }
    }

    /** Preserve the logo's aspect ratio and scale it to the active device display. */
    private Image scaleMenuLogo(Image source) {
        final int cropX = 17, cropY = 66, cropWidth = 340, cropHeight = 102;
        if (source.getWidth() < cropX + cropWidth || source.getHeight() < cropY + cropHeight)
            return source;
        int outputWidth = Math.min(getWidth() * 4 / 5, getHeight() * 3 / 5);
        if (outputWidth < 1) outputWidth = 1;
        int outputHeight = outputWidth * cropHeight / cropWidth;
        if (outputHeight > getHeight() / 5) {
            outputHeight = getHeight() / 5;
            outputWidth = outputHeight * cropWidth / cropHeight;
        }
        if (outputWidth < 1 || outputHeight < 1) return source;
        int[] pixels = new int[outputWidth * outputHeight];
        int[] upper = new int[cropWidth];
        int[] lower = new int[cropWidth];
        for (int y = 0; y < outputHeight; y++) {
            float sourceY = ((y + 0.5f) * cropHeight / outputHeight) - 0.5f;
            int y0 = (int)sourceY;
            if (y0 < 0) y0 = 0;
            int y1 = y0 + 1;
            if (y1 >= cropHeight) y1 = cropHeight - 1;
            float fy = sourceY - y0;
            source.getRGB(upper, 0, cropWidth, cropX, cropY + y0, cropWidth, 1);
            source.getRGB(lower, 0, cropWidth, cropX, cropY + y1, cropWidth, 1);
            for (int x = 0; x < outputWidth; x++) {
                float sourceX = ((x + 0.5f) * cropWidth / outputWidth) - 0.5f;
                int x0 = (int)sourceX;
                if (x0 < 0) x0 = 0;
                int x1 = x0 + 1;
                if (x1 >= cropWidth) x1 = cropWidth - 1;
                float fx = sourceX - x0;
                int p00 = upper[x0], p10 = upper[x1], p01 = lower[x0], p11 = lower[x1];
                int a = bilinearChannel(p00 >>> 24, p10 >>> 24, p01 >>> 24, p11 >>> 24, fx, fy);
                int r = bilinearChannel(p00 >> 16 & 255, p10 >> 16 & 255,
                        p01 >> 16 & 255, p11 >> 16 & 255, fx, fy);
                int g = bilinearChannel(p00 >> 8 & 255, p10 >> 8 & 255,
                        p01 >> 8 & 255, p11 >> 8 & 255, fx, fy);
                int b = bilinearChannel(p00 & 255, p10 & 255, p01 & 255, p11 & 255, fx, fy);
                pixels[y * outputWidth + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return Image.createRGBImage(pixels, outputWidth, outputHeight, true);
    }

    private int bilinearChannel(int p00, int p10, int p01, int p11, float fx, float fy) {
        float upper = p00 + (p10 - p00) * fx;
        float lower = p01 + (p11 - p01) * fx;
        return (int)(upper + (lower - upper) * fy + 0.5f);
    }

    /** Downscale the 227px touch pad using one source row at a time to limit peak heap use. */
    private Image scaleDpadTexture(Image source, int size) {
        if (source == null || size < 1) return source;
        int sourceWidth = source.getWidth(), sourceHeight = source.getHeight();
        if (sourceWidth == size && sourceHeight == size) return source;
        int[] pixels = new int[size * size];
        int[] row = new int[sourceWidth];
        for (int y = 0; y < size; y++) {
            int sourceY = y * sourceHeight / size;
            source.getRGB(row, 0, sourceWidth, 0, sourceY, sourceWidth, 1);
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = row[x * sourceWidth / size];
            }
        }
        return Image.createRGBImage(pixels, size, size, true);
    }

    private void loadHudTextures() {
        try { guiTexture = GameAssets.image("gui.png"); } catch (IOException ignored) { guiTexture = null; }
        try { iconsTexture = GameAssets.image("icons.png"); } catch (IOException ignored) { iconsTexture = null; }
        try { containerTexture = GameAssets.image("container.png"); } catch (IOException ignored) { containerTexture = null; }
        try {
            Image sourceDpad = GameAssets.image("dpad.png");
            int targetSize = Math.min(100, Math.max(72, getWidth() / 2 - 28));
            dpadTexture = scaleDpadTexture(sourceDpad, targetSize);
        } catch (Throwable ignored) { dpadTexture = null; }
        int[] blueTintPixels = new int[16 * 16];
        int[] hurtTintPixels = new int[16 * 16];
        int[] pauseTintPixels = new int[16 * 16];
        int deathHeight = getHeight() > 0 ? getHeight() : 320;
        int[] deathTintPixels = new int[16 * deathHeight];
        for (int i = 0; i < blueTintPixels.length; i++) {
            blueTintPixels[i] = 0x500035A0;
            hurtTintPixels[i] = 0x503F0000;
            pauseTintPixels[i] = 0xA0000000;
        }
        int deathTop = 0x60500000;
        int deathBottom = 0xA0803030;
        for (int y = 0; y < deathHeight; y++) {
            int step = deathHeight <= 1 ? 0 : y * 255 / (deathHeight - 1);
            int a = ((deathTop >>> 24) * (255 - step) + (deathBottom >>> 24) * step) / 255;
            int r = ((deathTop >> 16 & 255) * (255 - step) + (deathBottom >> 16 & 255) * step) / 255;
            int green = ((deathTop >> 8 & 255) * (255 - step) + (deathBottom >> 8 & 255) * step) / 255;
            int b = ((deathTop & 255) * (255 - step) + (deathBottom & 255) * step) / 255;
            int color = a << 24 | r << 16 | green << 8 | b;
            for (int x = 0; x < 16; x++) deathTintPixels[y * 16 + x] = color;
        }
        try { waterTint = Image.createRGBImage(blueTintPixels, 16, 16, true); } catch (Throwable ignored) { waterTint = null; }
        try { hurtTint = Image.createRGBImage(hurtTintPixels, 16, 16, true); } catch (Throwable ignored) { hurtTint = null; }
        try { pauseTint = Image.createRGBImage(pauseTintPixels, 16, 16, true); } catch (Throwable ignored) { pauseTint = null; }
        try { deathTint = Image.createRGBImage(deathTintPixels, 16, deathHeight, true); } catch (Throwable ignored) { deathTint = null; }
        blueTintPixels = null;
        hurtTintPixels = null;
        pauseTintPixels = null;
        deathTintPixels = null;
        Image source = null;
        try {
            source = GameAssets.terrain();
            terrainTexture = source;
            int[] sourcePixels = new int[14 * 14];
            int[] iconPixels = new int[16 * 16];
            for (int block = 1; block < Block.COUNT; block++) {
                if (Block.isDecorative((byte)block) || block == Block.TNT) continue;
                int tile = block == Block.CHEST ? 27 : Block.sideTile((byte)block);
                int cropX = (tile & 15) * 16 + 1;
                int cropY = (tile >> 4) * 16 + 1;
                source.getRGB(sourcePixels, 0, 14, cropX, cropY, 14, 14);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int sourceX = x * 14 / 16, sourceY = y * 14 / 16;
                        iconPixels[y * 16 + x] = sourcePixels[sourceY * 14 + sourceX];
                    }
                }
                if (block == Block.LEAVES) AlphaTint.apply(iconPixels, AlphaTint.LEAVES);
                blockIcons[block] = Image.createRGBImage(iconPixels, 16, 16, true);
                itemIcons[block] = blockIcons[block];
                mutedItemIcons[block] = makeMutedIcon(blockIcons[block]);
            }
        } catch (Throwable unavailableAtlas) {
            for (int i = 0; i < blockIcons.length; i++) blockIcons[i] = null;
        }
        try {
            Image portalSource = GameAssets.image("portal.png");
            int[] portalPixels = new int[16 * 16];
            portalSource.getRGB(portalPixels, 0, 16, 0, 0, 16, 16);
            blockIcons[Block.PORTAL] = Image.createRGBImage(portalPixels, 16, 16, true);
            itemIcons[Block.PORTAL] = blockIcons[Block.PORTAL];
            mutedItemIcons[Block.PORTAL] = makeMutedIcon(blockIcons[Block.PORTAL]);
        } catch (Throwable unavailablePortalTexture) {
            blockIcons[Block.PORTAL] = null;
            itemIcons[Block.PORTAL] = null;
            mutedItemIcons[Block.PORTAL] = null;
        }
        source = null;
        Image woolSource = null;
        try {
            woolSource = GameAssets.terrain();
            source = GameAssets.image("items.png");
            int[] iconPixels = new int[16 * 16];
            for (int id = Block.COUNT; id < ItemCatalog.COUNT; id++) {
                if (ItemCatalog.isColoredWool(id)) {
                    if (woolSource != null) {
                        itemIcons[id] = WoolColors.texture(woolSource, ItemCatalog.woolColor(id));
                        mutedItemIcons[id] = makeMutedIcon(itemIcons[id]);
                    }
                    continue;
                }
                int tile = ItemCatalog.iconIndex(id);
                if (tile < 0) continue;
                source.getRGB(iconPixels, 0, 16, (tile & 15) * 16, (tile >> 4) * 16, 16, 16);
                itemIcons[id] = Image.createRGBImage(iconPixels, 16, 16, true);
                mutedItemIcons[id] = makeMutedIcon(itemIcons[id]);
            }
        } catch (Throwable unavailableItemsAtlas) {
            for (int i = Block.COUNT; i < itemIcons.length; i++) itemIcons[i] = null;
        }
        woolSource = null;
        source = null;
        System.gc();
    }

    private Image makeMutedIcon(Image source) {
        if (source == null) return null;
        int[] pixels = new int[16 * 16];
        source.getRGB(pixels, 0, 16, 0, 0, 16, 16);
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int alpha = color >>> 24;
            int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
            int gray = (red * 30 + green * 59 + blue * 11) / 100;
            pixels[i] = alpha << 24 | gray << 16 | gray << 8 | gray;
        }
        try { return Image.createRGBImage(pixels, 16, 16, true); }
        catch (Throwable ignored) { return source; }
    }

    private void clearKeys() {
        for (int i = 0; i < keys.length; i++) keys[i] = false;
        sprinting = false;
        sprintToggleTimer = 0;
        jumpHeld = false;
        descendHeld = false;
        lookUp = lookDown = lookLeft = lookRight = false;
        resetMining();
        miningHitWait = 0;
    }

    private void cycleSlot(int delta) {
        world.selectedSlot = (world.selectedSlot + delta + hotbar.length) % hotbar.length;
    }

    private int gameAction(int keyCode) {
        try { return getGameAction(keyCode); } catch (IllegalArgumentException ignored) { return 0; }
    }

    protected synchronized void sizeChanged(int width, int height) {
        if (renderer != null) renderer.resize(width, height);
        if (skinGallery != null) skinGallery.resize(width - 16, Math.max(1, height - 120));
        if (state == MENU && width > 0 && height > 0) loadTitle();
    }

    protected synchronized void paint(Graphics g) {
        if (state == TOUCH_SETUP) {
            drawTouchSetup(g);
        } else if (state == PLAYING || state == PAUSED || state == INVENTORY || state == WORKBENCH ||
            state == CREATIVE_INVENTORY || state == GAME_OVER || state == CHEST_INVENTORY) {
            String stage = "actualizando mallas M3G";
            try {
                renderer.sync(world);
                int heldBlock = hotbar[world.selectedSlot];
                renderer.updateHeldItem(heldBlock,
                    world.mode == 1 || inventory[heldBlock] > 0);
                stage = "obteniendo contexto Graphics3D";
                Graphics3D graphics3D = Graphics3D.getInstance();
                stage = "enlazando Graphics del Canvas a M3G";
                graphics3D.bindTarget(g);
                try {
                    stage = "configurando viewport M3G";
                    graphics3D.setViewport(0, 0, getWidth(), getHeight());
                    stage = "dibujando World M3G";
                    renderer.render(graphics3D);
                }
                finally { graphics3D.releaseTarget(); }
                stage = "dibujando HUD 2D";
                if (state == CHEST_INVENTORY) {
                    drawTint(g, chestOverlay());
                    drawChest(g);
                }
                if (state == PLAYING || state == PAUSED) {
                    if (cameraUnderwater()) drawTint(g, waterTint);
                    if (hurtTicks > 0) drawTint(g, hurtTint);
                    drawHud(g);
                    if (touchEnabled && state == PLAYING) drawTouchControls(g);
                }
                if (state == PAUSED) drawPause(g);
                if (state == GAME_OVER) {
                    drawDeathGradient(g);
                    drawGameOver(g);
                }
                if (state == INVENTORY || state == WORKBENCH) drawCrafting(g, state == WORKBENCH);
                if (state == CREATIVE_INVENTORY) drawCreativeInventory(g);
            } catch (OutOfMemoryError exhausted) {
                showError("M3G agotó el heap. El diseño apunta a móviles con heap total de 4 MiB o más.");
                drawError(g);
            } catch (Throwable problem) {
                showDiagnostic(stage, problem);
                drawError(g);
            }
        } else if (state == LOADING) drawLoading(g);
        else if (state == ERROR) drawError(g);
        else if (state == WORLD_SELECT) drawWorldSelect(g);
        else if (state == WORLD_DELETE) drawWorldDelete(g);
        else if (state == WORLD_CREATE) drawWorldCreate(g);
        else if (state == SETTINGS) drawSettings(g);
        else if (state == CONTROLS) drawControls(g);
        else if (state == SKIN_SELECT) drawSkinSelect(g);
        else drawMenu(g);
    }

    private void chooseTouchMode(boolean enabled) {
        touchEnabled = enabled;
        saveSettings();
        state = MENU;
        menuSelection = MENU_SINGLEPLAYER;
        clearKeys();
        repaint();
    }

    private void drawTouchSetup(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawPanoramaBackground(g);
        drawInsetPanel(g, 12, h / 2 - 76, w - 24, 152);
        g.setColor(255, 244, 210);
        g.drawString("MeCraft", w / 2, h / 2 - 61, Graphics.TOP | Graphics.HCENTER);
        g.setColor(255, 255, 255);
        g.drawString("¿Dispositivo con pantalla tactil?", w / 2, h / 2 - 30,
                Graphics.TOP | Graphics.HCENTER);
        drawTouchButton(g, "SI - controles tactiles",  h / 2 - 4, touchSetupSelection == 0);
        drawTouchButton(g, "NO - teclado/botones", h / 2 + 34, touchSetupSelection == 1);
        g.setColor(230, 220, 200);
        g.drawString("2/8 elegir  5 aceptar  0 = no", w / 2, h / 2 + 65,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawTouchButton(Graphics g, String label, int y, boolean selected) {
        int w = getWidth();
        int x = 22, width = w - 44, height = 30;
        g.setColor(selected ? 100 : 65, selected ? 105 : 55, selected ? 70 : 40);
        g.fillRect(x, y, width, height);
        g.setColor(selected ? 255 : 170, selected ? 220 : 145, selected ? 125 : 100);
        g.drawRect(x, y, width - 1, height - 1);
        g.setColor(255, 255, 255);
        g.drawString(label, w / 2, y + 8, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawTouchControls(Graphics g) {
        int w = getWidth(), h = getHeight();
        int dpadW = dpadTexture == null ? 88 : dpadTexture.getWidth();
        int dpadH = dpadTexture == null ? 88 : dpadTexture.getHeight();
        int left = 10, top = h - dpadH - 10;
        if (dpadTexture != null) {
            g.drawImage(dpadTexture, left, top, Graphics.TOP | Graphics.LEFT);
        } else {
            int cell = dpadW / 3;
            drawTouchButtonAt(g, "^", left + cell, top, cell);
            drawTouchButtonAt(g, "<", left, top + cell, cell);
            drawTouchButtonAt(g, "v", left + cell, top + cell * 2, cell);
            drawTouchButtonAt(g, ">", left + cell * 2, top + cell, cell);
        }
        int jumpSize = Math.max(58, Math.min(72, dpadH - 12));
        int jumpX = left + dpadW + 10;
        int jumpY = top + dpadH - jumpSize;
        g.setColor(28, 28, 28);
        g.fillRect(jumpX + 2, jumpY + 2, jumpSize, jumpSize);
        g.setColor(85, 85, 85);
        g.fillRect(jumpX, jumpY, jumpSize, jumpSize);
        g.setColor(205, 190, 160);
        g.drawRect(jumpX, jumpY, jumpSize - 1, jumpSize - 1);
        int pauseW = 54, pauseH = 25;
        drawTouchButtonAt(g, "II", (w - pauseW) / 2, 7, pauseW);
        drawTouchButtonAt(g, "INV", w - 62, h - 43, 52);
    }

    private void drawTouchButtonAt(Graphics g, String label, int x, int y, int width) {
        int height = label.length() > 4 ? 29 : 27;
        g.setColor(28, 28, 28);
        g.fillRect(x + 1, y + 1, width, height);
        g.setColor(75, 75, 75);
        g.fillRect(x, y, width, height);
        g.setColor(205, 190, 160);
        g.drawRect(x, y, width - 1, height - 1);
        g.setColor(255, 255, 255);
        g.drawString(label, x + width / 2, y + (height - Font.getDefaultFont().getHeight()) / 2,
                Graphics.TOP | Graphics.HCENTER);
    }

    protected synchronized void pointerPressed(int x, int y) {
        if (state == TOUCH_SETUP) {
            int mid = getHeight() / 2;
            if (y >= mid - 8 && y < mid + 29) chooseTouchMode(true);
            else if (y >= mid + 30 && y < mid + 65) chooseTouchMode(false);
            return;
        }
        if (!touchEnabled) return;
        if (state == PLAYING && world != null) {
            int w = getWidth(), h = getHeight();
            if (y >= h - 27 && x >= w / 2 - 91 && x < w / 2 + 91) {
                int slot = (x - (w / 2 - 91)) / 20;
                if (slot >= 0 && slot < 9) world.selectedSlot = slot;
                repaint();
                return;
            }
            if (x >= w / 2 - 29 && x <= w / 2 + 29 && y <= 39) {
                openPause();
                repaint();
                return;
            }
            if (x >= w - 66 && y >= h - 52) {
                if (world.mode == 1) openCreativeInventory(); else openCrafting(false);
                repaint();
                return;
            }
            int dpadW = dpadTexture == null ? 88 : dpadTexture.getWidth();
            int dpadH = dpadTexture == null ? 88 : dpadTexture.getHeight();
            int left = 10, top = h - dpadH - 10;
            if (x >= left && x < left + dpadW && y >= top && y < top + dpadH) {
                int col = (x - left) * 3 / dpadW;
                int row = (y - top) * 3 / dpadH;
                if (row == 0 && col == 1) touchMoveUp = true;
                else if (row == 2 && col == 1) touchMoveDown = true;
                else if (row == 1 && col == 0) touchMoveLeft = true;
                else if (row == 1 && col == 2) touchMoveRight = true;
                applyTouchMovement();
                return;
            }
            int jumpSize = Math.max(58, Math.min(72, dpadH - 12));
            int jumpX = left + dpadW + 10, jumpY = top + dpadH - jumpSize;
            if (x >= jumpX && x < jumpX + jumpSize && y >= jumpY && y < jumpY + jumpSize) {
                touchJumpPressed();
                return;
            }
            touchActionPending = true;
            touchMiningStarted = false;
            touchActionStart = System.currentTimeMillis();
            touchLooking = true;
            touchLastX = x;
            touchLastY = y;
            return;
        }
        handleTouchMenu(x, y);
    }

    private void applyTouchMovement() {
        keys[2] = touchMoveUp;
        keys[8] = touchMoveDown;
        keys[4] = touchMoveLeft;
        keys[6] = touchMoveRight;
        if (!touchMoveUp) sprinting = false;
    }

    private void touchJumpPressed() {
        if (!jumpHeld && world.mode == 1) {
            long now = System.currentTimeMillis();
            if (hasJumpTap && now - lastJumpTap <= 350L) {
                flying = !flying;
                hasJumpTap = false;
                playerPhysics.clearVerticalMotion();
            } else {
                hasJumpTap = true;
                lastJumpTap = now;
            }
        }
        jumpHeld = true;
        touchJumpActive = true;
    }

    protected synchronized void pointerDragged(int x, int y) {
        if (!touchEnabled || !touchLooking || state != PLAYING || world == null) return;
        int dx = x - touchLastX, dy = y - touchLastY;
        if (touchActionPending && (dx > 4 || dx < -4 || dy > 4 || dy < -4)) {
            touchActionPending = false;
        }
        float sensitivity = cameraSensitivityScale();
        world.yaw -= dx * 0.55f * sensitivity;
        while (world.yaw < 0.0f) world.yaw += 360.0f;
        while (world.yaw >= 360.0f) world.yaw -= 360.0f;
        cameraPitch -= dy * 0.45f * sensitivity;
        if (cameraPitch > 80.0f) cameraPitch = 80.0f;
        if (cameraPitch < -80.0f) cameraPitch = -80.0f;
        touchLastX = x; touchLastY = y;
        repaint();
    }

    protected synchronized void pointerReleased(int x, int y) {
        if (touchMoveUp || touchMoveDown || touchMoveLeft || touchMoveRight) {
            touchMoveUp = touchMoveDown = touchMoveLeft = touchMoveRight = false;
            applyTouchMovement();
        }
        if (touchJumpActive) {
            jumpHeld = false;
            touchJumpActive = false;
        }
        if (touchActionPending && !touchMiningStarted && state == PLAYING && world != null) {
            useSelectedItem();
            repaint();
        }
        if (touchMiningStarted) {
            keys[1] = false;
            cancelMining();
        }
        touchActionPending = false;
        touchMiningStarted = false;
        touchLooking = false;
    }

    private void handleTouchMenu(int x, int y) {
        int w = getWidth(), h = getHeight();
        if (state == MENU) {
            int bh = Font.getDefaultFont().getHeight() + 8;
            int step = bh + 5;
            int first = h / 2 - (bh * 4 + 15) / 2 + 15;
            if (y >= first - 3 && y < first + step * 4) {
                int row = (y - first) / step;
                if (row >= 0 && row < 4) { menuSelection = row; activateSelection(); }
            }
        } else if (state == WORLD_SELECT) {
            int row = (y - 38) / 30;
            int first = Math.max(0, Math.min(worldSelection - 6, savedWorlds.length));
            if (y >= 38 && y < 248 && row >= 0 && row < 7) {
                worldSelection = first + row;
                if (worldSelection >= savedWorlds.length + 2) worldSelection = savedWorlds.length + 1;
                activateWorldSelection();
            } else if (y >= h - 36) backToMainMenu();
        } else if (state == WORLD_CREATE) {
            int row = (y - 70 + 17) / 34;
            if (row >= 0 && row <= 5) { createSelection = row; activateCreateSelection(); }
        } else if (state == PAUSED) {
            int row = (y - (h / 2 - 75 + 40) + 13) / 27;
            if (row >= 0 && row < 3) { pauseSelection = row; activateSelection(); }
        } else if (state == SETTINGS) {
            int panelY = Math.max(12, (h - 202) / 2);
            int rowStep = Math.min(23, Math.max(14, (Math.min(h - panelY - 8, 202) - 54) / 6));
            int row = (y - (panelY + 32) + rowStep / 2) / rowStep;
            if (row >= 0 && row < 6) {
                settingsSelection = row;
                if (row == 3 || row == 5) keyPressed(KEY_NUM5);
                else if (row == 4) changeLanguage();
                else if (row == 0) changeFrameSkip(1);
                else if (row == 1) changeSensitivity(1);
                else if (row == 2) changeRenderDistance(1);
            } else if (y > h - 28) state = MENU;
        } else if (state == WORLD_DELETE) {
            if (y > h / 2) keyPressed(KEY_NUM0); else keyPressed(KEY_NUM5);
        } else if (state == ERROR) keyPressed(KEY_NUM5);
        else if (state == GAME_OVER) keyPressed(KEY_NUM5);
        else if (state == INVENTORY || state == WORKBENCH) {
            if (y > h - 45) craftSelected();
            else if (x < w / 2) moveRecipeSelection(y < h / 2 ? -1 : 1);
            else moveInventoryPage(y < h / 2 ? -1 : 1);
        } else if (state == CREATIVE_INVENTORY) {
            if (y > h - 45) closeCreativeInventory();
            else {
                int col = (x - (w / 2 - 4 * 22)) / 22;
                int row = (y - 54) / 22;
                if (col >= 0 && col < 9 && row >= 0 && row < 5) {
                    creativeSelection = (creativeSelection / 45) * 45 + row * 9 + col;
                    selectCreativeBlock();
                }
            }
        } else if (state == CHEST_INVENTORY) {
            int left = (w - 176) / 2, top = (h - 166) / 2;
            if (x >= left + 7 && x < left + 8 + 9 * 18 &&
                    y >= top + 17 && y < top + 162) {
                int col = (x - (left + 8)) / 18;
                if (col < 0) col = 0;
                if (col > 8) col = 8;
                if (y < top + 73) chestSelection = ((y - (top + 18)) / 18) * 9 + col;
                else if (y < top + 141) {
                    int row = (y - (top + 85)) / 18;
                    if (row < 0) row = 0;
                    if (row > 2) row = 2;
                    chestSelection = 27 + row * 9 + col;
                } else chestSelection = 54 + col;
                if (chestSelection < 0) chestSelection = 0;
                if (chestSelection > 62) chestSelection = 62;
                transferChestSlot();
            } else if (y > top + 166 || x < left || x > left + 176) closeCrafting();
            repaint();
        } else if (state == SKIN_SELECT) {
            if (y > h - 45) keyPressed(KEY_NUM5);
            else { skinSelection = x < w / 2 ? Math.max(0, skinSelection - 1) : Math.min(4, skinSelection + 1); repaint(); }
        } else if (state == CONTROLS) {
            int rowHeight = Font.getDefaultFont().getHeight() + 3;
            int row = (y - (Math.max(12, (h - 238) / 2) + 40)) / rowHeight + controlsScroll;
            if (row >= 0 && row < CONTROL_COUNT) { controlsSelection = row; controlsListening = false; }
            else if (y > h - 35) keyPressed(KEY_NUM0);
            repaint();
        }
    }

    private void drawLoading(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(25, 25, 25);
        g.fillRect(0, 0, w, h);
        g.setColor(205, 205, 205);
        g.fillRect(0, h / 2 - 16, w, 1);
        g.fillRect(0, h / 2 + 16, w, 1);
        g.setColor(255, 255, 255);
        Font old = g.getFont();
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM));
        g.drawString("MeCraft", w / 2, h / 2 - 48, Graphics.HCENTER | Graphics.TOP);
        g.setFont(Font.getDefaultFont());
        String message = loadingMessage;
        if (message == null) message = "Cargando...";
        int maxChars = Math.max(8, (w - 16) / Math.max(1, g.getFont().charWidth('M')));
        if (message.length() > maxChars) message = message.substring(0, maxChars);
        g.drawString(message, w / 2, h / 2 - 8, Graphics.HCENTER | Graphics.TOP);
        int barWidth = Math.min(w - 32, 150);
        if (barWidth < 20) barWidth = Math.max(1, w - 8);
        int bx = (w - barWidth) / 2, by = h / 2 + 25;
        g.setColor(90, 90, 90);
        g.drawRect(bx, by, barWidth, 5);
        g.setColor(205, 175, 95);
        int pulse = (int)(System.currentTimeMillis() / 80L % Math.max(1, barWidth - 8));
        g.fillRect(bx + 2 + pulse, by + 2, Math.min(8, Math.max(1, barWidth - 4 - pulse)), 2);
        g.setFont(old);
    }

    private void drawMenu(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawPanoramaBackground(g);
        int logoY = Math.max(8, h / 12);
        if (title != null) g.drawImage(title, w / 2, logoY, Graphics.TOP | Graphics.HCENTER);
        else {
            Font oldFont = g.getFont();
            g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE));
            g.setColor(255, 255, 255);
            g.drawString("MeCraft", w / 2, logoY, Graphics.TOP | Graphics.HCENTER);
            g.setFont(oldFont);
        }
        int buttonHeight = Font.getDefaultFont().getHeight() + 8;
        int buttonStep = buttonHeight + 5;
        int stackHeight = buttonHeight * 4 + 15;
        int firstY = h / 2 - stackHeight / 2 + 15;
        drawIndevMenuButton(g, "Un jugador", firstY, buttonHeight,
                menuSelection == MENU_SINGLEPLAYER);
        drawIndevMenuButton(g, "Controles", firstY + buttonStep, buttonHeight,
                menuSelection == MENU_CONTROLS);
        drawIndevMenuButton(g, "Ajustes", firstY + buttonStep * 2, buttonHeight,
                menuSelection == MENU_SETTINGS);
        drawIndevMenuButton(g, "Salir", firstY + buttonStep * 3, buttonHeight,
                menuSelection == MENU_EXIT);
        g.setColor(235, 222, 196);
        g.drawString(Language.text("2/8 elegir   5 aceptar"), w / 2, h - 27,
                Graphics.TOP | Graphics.HCENTER);
    }

    /** Classic Indev gray button with a raised pixel border and gold focus. */
    private void drawIndevMenuButton(Graphics g, String label, int y,
            int height, boolean selected) {
        int w = getWidth();
        int width = Math.min(200, w - 32);
        if (width < 1) width = 1;
        int x = (w - width) / 2;
        g.setColor(20, 20, 20);
        g.fillRect(x + 2, y + 2, width, height);
        g.setColor(selected ? 132 : 100, selected ? 132 : 100, selected ? 132 : 100);
        g.fillRect(x, y, width, height);
        g.setColor(selected ? 224 : 166, selected ? 224 : 166, selected ? 224 : 166);
        g.fillRect(x + 1, y + 1, width - 2, 2);
        g.setColor(selected ? 72 : 54, selected ? 72 : 54, selected ? 72 : 54);
        g.fillRect(x + 1, y + height - 3, width - 2, 2);
        g.setColor(selected ? 255 : 160, selected ? 226 : 160, selected ? 112 : 160);
        g.drawRect(x, y, width - 1, height - 1);
        g.setColor(selected ? 255 : 245, selected ? 224 : 245, selected ? 125 : 245);
        g.drawString(Language.text(label), w / 2, y + (height - Font.getDefaultFont().getHeight()) / 2,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawSettings(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawDirtBackground(g);
        int panelY = Math.max(8, (h - 222) / 2);
        int panelHeight = Math.min(h - panelY - 6, 222);
        int rowStep = Math.min(22, Math.max(13, (panelHeight - 54) / 7));
        int firstRowY = panelY + 32;
        drawInsetPanel(g, 12, panelY, w - 24, panelHeight);
        g.setColor(255, 244, 200);
        g.drawString(Language.text("Ajustes"), w / 2, panelY + 10, Graphics.TOP | Graphics.HCENTER);
        String value = frameSkip == 0 ? "Desactivado" : (frameSkip == 1 ? "1 de cada 2" : "2 de cada 3");
        String[] sensitivityNames = new String[] { "Muy baja", "Baja", "Normal", "Alta", "Muy alta" };
        String[] distanceNames = language == Language.ENGLISH ?
                new String[] { "Short", "Normal", "Far", "Maximum" } :
                new String[] { "Corta", "Normal", "Lejana", "Máxima" };
        drawMenuItem(g, "Frameskip: " + value, firstRowY, settingsSelection == 0);
        drawMenuItem(g, "Sensibilidad: " + sensitivityNames[cameraSensitivity], firstRowY + rowStep,
                settingsSelection == 1);
        drawMenuItem(g, "Render: " + distanceNames[renderDistance],
                firstRowY + rowStep * 2, settingsSelection == 2);
        drawMenuItem(g, "Skin del jugador", firstRowY + rowStep * 3, settingsSelection == 3);
        drawMenuItem(g, "Idioma: " + (language == Language.ENGLISH ? "English" : "Español"),
                firstRowY + rowStep * 4, settingsSelection == 4);
        drawMenuItem(g, "Controles", firstRowY + rowStep * 5, settingsSelection == 5);
        drawMenuItem(g, "Controles tactiles: " + (touchEnabled ? "Si" : "No"),
                firstRowY + rowStep * 6, settingsSelection == 6);
        g.setColor(240, 224, 190);
        g.drawString(Language.text("2/8 elegir   4/6 cambiar   5 abrir"), w / 2, panelY + panelHeight - 32,
                Graphics.TOP | Graphics.HCENTER);
        g.drawString(Language.text("0 volver"), w / 2, panelY + panelHeight - 16, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawControls(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawDirtBackground(g);
        int panelY = Math.max(12, (h - 238) / 2);
        int panelHeight = Math.min(h - panelY - 8, 238);
        drawInsetPanel(g, 10, panelY, w - 20, panelHeight);
        g.setColor(255, 244, 200);
        g.drawString(Language.text("Controles"), w / 2, panelY + 10, Graphics.TOP | Graphics.HCENTER);
        if (controlsListening) {
            g.setColor(255, 215, 128);
            g.drawString(Language.text("Pulsa una tecla nueva (0 cancela)"), w / 2, panelY + 27,
                    Graphics.TOP | Graphics.HCENTER);
        }
        int rows = controlsVisibleRows();
        int rowHeight = Font.getDefaultFont().getHeight() + 3;
        int firstY = panelY + (controlsListening ? 48 : 34);
        for (int row = 0; row < rows; row++) {
            int index = controlsScroll + row;
            if (index >= CONTROL_COUNT) break;
            int y = firstY + row * rowHeight;
            boolean selected = index == controlsSelection;
            if (selected) {
                g.setColor(99, 69, 43);
                g.fillRect(15, y - 1, w - 30, rowHeight);
            }
            g.setColor(selected ? 255 : 225, selected ? 229 : 215, selected ? 166 : 195);
            g.drawString(Language.text(CONTROL_NAMES[index]), 18, y, Graphics.TOP | Graphics.LEFT);
            g.setColor(selected ? 255 : 190, selected ? 239 : 180, selected ? 177 : 145);
            g.drawString(Language.text(controlKeyName(index)), w - 18, y, Graphics.TOP | Graphics.RIGHT);
        }
        g.setColor(240, 224, 190);
        if (controlsMessage != null)
            g.drawString(Language.text(controlsMessage), w / 2, panelY + panelHeight - 42,
                    Graphics.TOP | Graphics.HCENTER);
        g.drawString(Language.text("5: reasignar   7: restaurar fila"), w / 2, panelY + panelHeight - 26,
                Graphics.TOP | Graphics.HCENTER);
        g.drawString(Language.text("2/8 mover   0 volver"), w / 2, panelY + panelHeight - 12,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawSkinSelect(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawDirtBackground(g);
        drawInsetPanel(g, 6, 5, w - 12, h - 10);
        g.setColor(255, 244, 200);
        g.drawString(Language.text("Skins"), w / 2, 9, Graphics.TOP | Graphics.HCENTER);

        int viewportX = 10, viewportY = 30;
        int viewportWidth = w - 20;
        int viewportHeight = Math.max(1, h - 116);
        if (skinGallery != null && viewportWidth > 0) {
            try {
                Graphics3D graphics3D = Graphics3D.getInstance();
                graphics3D.bindTarget(g);
                try {
                    graphics3D.setViewport(viewportX, viewportY, viewportWidth, viewportHeight);
                    skinGallery.render(graphics3D, System.currentTimeMillis());
                } finally { graphics3D.releaseTarget(); }
            } catch (Throwable problem) {
                g.setColor(255, 220, 180);
                g.drawString(Language.text("No se pudo mostrar la vista 3D"), w / 2,
                        viewportY + viewportHeight / 2, Graphics.TOP | Graphics.HCENTER);
            }
        }

        int slotY = h - 72;
        int slotWidth = Math.max(30, (w - 28) / 5);
        for (int i = 0; i < 5; i++) {
            int x = 9 + i * slotWidth;
            boolean selected = i == skinSelection;
            g.setColor(selected ? 198 : 76, selected ? 177 : 56, selected ? 116 : 38);
            g.fillRect(x, slotY, slotWidth - 3, 22);
            g.setColor(selected ? 255 : 164, selected ? 230 : 130, selected ? 176 : 91);
            g.drawRect(x, slotY, slotWidth - 4, 21);
            g.drawString(String.valueOf(i + 1), x + (slotWidth - 3) / 2, slotY + 3,
                    Graphics.TOP | Graphics.HCENTER);
        }
        g.setColor(240, 224, 190);
        if (skinGallery != null && skinGallery.isFallback(skinSelection)) {
            g.drawString(Language.text("Falta skin") + (skinSelection + 1) + ".png", w / 2, h - 46,
                    Graphics.TOP | Graphics.HCENTER);
        }
        g.drawString(Language.text("4/6 elegir   5 usar   0 volver"), w / 2, h - 23,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawMenuItem(Graphics g, String label, int y, boolean selected) {
        int w = getWidth();
        int x = 24, width = w - 48, height = Font.getDefaultFont().getHeight() + 7;
        g.setColor(35, 27, 20); g.fillRect(x + 2, y + 2, width, height);
        g.setColor(selected ? 198 : 122, selected ? 177 : 122, selected ? 116 : 122);
        g.fillRect(x, y, width, height);
        g.setColor(selected ? 228 : 150, selected ? 210 : 150, selected ? 148 : 150);
        g.drawRect(x, y, width - 1, height - 1);
        g.setColor(selected ? 255 : 224, selected ? 244 : 224, selected ? 180 : 224);
        Font font = g.getFont();
        String marker = selected ? SELECT_MARKER : EMPTY_MARKER;
        String shownLabel = Language.text(label);
        int textWidth = font.stringWidth(marker) + font.stringWidth(shownLabel);
        int textX = (w - textWidth) / 2;
        g.drawString(marker, textX, y + 3, Graphics.TOP | Graphics.LEFT);
        g.drawString(shownLabel, textX + font.stringWidth(marker), y + 3, Graphics.TOP | Graphics.LEFT);
    }

    /**
     * Pocket Edition-style animated title panorama. Only the main menu uses
     * this background; world, settings, controls and other submenus retain
     * their existing dirt texture and panel rendering.
     */
    private void drawPanoramaBackground(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (skybox == null) {
            try { skybox = GameAssets.image("skybox.png"); }
            catch (Throwable ignored) { skybox = null; }
        }
        if (skybox == null) {
            drawDirtBackground(g);
            return;
        }
        // Refresh the perspective cube view periodically instead of allocating
        // a new frame on every repaint.
        if (menuPanorama == null || panoramaWidth != w || panoramaHeight != h ||
                panoramaTick % 2 == 0) {
            try {
                menuPanorama = buildMenuPanorama(h);
                panoramaWidth = w;
                panoramaHeight = h;
            } catch (Throwable ignored) {
                menuPanorama = null;
            }
        }
        if (menuPanorama == null) {
            drawDirtBackground(g);
            return;
        }
        g.drawImage(menuPanorama, 0, 0, Graphics.TOP | Graphics.LEFT);
    }

    /**
     * Maps the six square faces in the supplied 3x2 Minecraft panorama atlas
     * onto a perspective cube view. The atlas has four side faces in reading
     * order, followed by sky (bottom-middle) and ground (bottom-right).
     * A small software-rendered frame is cached between menu updates.
     */
    private Image buildMenuPanorama(int targetHeight) {
        if (skybox == null || targetHeight <= 0) return null;
        int w = getWidth();
        int h = targetHeight;
        int sw = skybox.getWidth(), sh = skybox.getHeight();
        if (w <= 0 || sw < 700 || sh < 500) return null;

        // This asset is a screenshot/export of a 3x2 cubemap atlas. Exclude
        // the "Panorama" header and credits footer; sample the six 257px faces.
        int faceSize = Math.min(sw / 3, (sh - 24) / 2);
        if (faceSize < 32) return null;
        int[] src = new int[sw * sh];
        skybox.getRGB(src, 0, sw, 0, 0, sw, sh);
        int[] faces = new int[6 * faceSize * faceSize];
        int[] cellX = new int[] { 0, 258, 515, 0, 258, 515 };
        int[] cellY = new int[] { 12, 12, 12, 269, 269, 269 };
        for (int f = 0; f < 6; f++) {
            for (int y = 0; y < faceSize; y++) {
                int sy = cellY[f] + y;
                if (sy >= sh) sy = sh - 1;
                for (int x = 0; x < faceSize; x++) {
                    int sx = cellX[f] + x;
                    if (sx >= sw) sx = sw - 1;
                    faces[f * faceSize * faceSize + y * faceSize + x] = src[sy * sw + sx];
                }
            }
        }
        src = null;

        // Render at quarter resolution: MIDP has no hardware cube mapping, so
        // reducing ray samples is the main way to avoid stalling old devices.
        int rw = (w + 3) / 4;
        int rh = (h + 3) / 4;
        int[] low = new int[rw * rh];
        double yaw = panoramaTick * 0.04 * Math.PI / 180.0;
        double pitch = -0.035;
        // Trigonometric values are constant for the whole frame. Calculate
        // them once rather than invoking Math.sin/cos for every output pixel.
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double fovScale = Math.tan(Math.toRadians(32.0));
        double aspect = (double)rw / (double)rh;
        for (int y = 0; y < rh; y++) {
            double sy = (1.0 - 2.0 * (y + 0.5) / rh) * fovScale;
            for (int x = 0; x < rw; x++) {
                double sx = (2.0 * (x + 0.5) / rw - 1.0) * fovScale * aspect;
                double vx = sx * cosYaw + sinYaw;
                double vy = sy * cosPitch + sinPitch * (sx * sinYaw - cosYaw);
                double vz = sx * sinYaw - cosYaw;
                double ax = Math.abs(vx), ay = Math.abs(vy), az = Math.abs(vz);
                int face;
                double u, v, major;
                if (ay >= ax && ay >= az) {
                    major = ay;
                    if (vy >= 0) { face = 4; u = vx / major; v = vz / major; }
                    else { face = 5; u = vx / major; v = -vz / major; }
                } else if (ax >= az) {
                    major = ax;
                    if (vx >= 0) { face = 1; u = -vz / major; v = -vy / major; }
                    else { face = 3; u = vz / major; v = -vy / major; }
                } else {
                    major = az;
                    if (vz >= 0) { face = 2; u = vx / major; v = -vy / major; }
                    else { face = 0; u = -vx / major; v = -vy / major; }
                }
                // Minecraft panorama order: 0 front, 1 right, 2 back,
                // 3 left, 4 up, 5 down. Atlas cells are in row-major layout:
                // 0,1,2 across the top; 3,4,5 across the bottom.
                int atlasFace;
                if (face == 4) atlasFace = 4;
                else if (face == 5) atlasFace = 5;
                else if (face == 1) atlasFace = 1; // +X: right
                else if (face == 3) atlasFace = 3; // -X: left
                else if (face == 2) atlasFace = 2; // +Z: back
                else atlasFace = 0;                // -Z: front
                int tx = (int)((u + 1.0) * 0.5 * (faceSize - 1));
                int ty = (int)((v + 1.0) * 0.5 * (faceSize - 1));
                if (tx < 0) tx = 0; else if (tx >= faceSize) tx = faceSize - 1;
                if (ty < 0) ty = 0; else if (ty >= faceSize) ty = faceSize - 1;
                low[y * rw + x] = faces[atlasFace * faceSize * faceSize + ty * faceSize + tx];
            }
        }
        faces = null;

        int[] output = new int[w * h];
        for (int y = 0; y < h; y++) {
            // Both dimensions were rendered at quarter resolution. Using
            // y / 2 here walked past the low-resolution buffer and could
            // throw on taller screens, causing the panorama to disappear.
            int ly = y / 4;
            if (ly >= rh) ly = rh - 1;
            for (int x = 0; x < w; x++) {
                output[y * w + x] = low[ly * rw + x / 4];
            }
        }
        low = null;
        return Image.createRGBImage(output, w, h, true);
    }

    private void drawDirtBackground(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (terrainTexture == null || terrainTexture.getWidth() < 48 || terrainTexture.getHeight() < 16) {
            g.setColor(108, 75, 47); g.fillRect(0, 0, w, h); return;
        }
        int tile = Block.atlasTile(Block.DIRT);
        for (int y = 0; y < h; y += 16) {
            int tileHeight = Math.min(16, h - y);
            for (int x = 0; x < w; x += 16) {
                int tileWidth = Math.min(16, w - x);
                g.drawRegion(terrainTexture, (tile & 15) * 16, (tile >> 4) * 16,
                        tileWidth, tileHeight, Sprite.TRANS_NONE, x, y,
                        Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    private void drawInsetPanel(Graphics g, int x, int y, int width, int height) {
        g.setColor(48, 34, 24); g.fillRect(x + 3, y + 3, width, height);
        g.setColor(93, 65, 42); g.fillRect(x, y, width, height);
        g.setColor(159, 121, 80); g.drawRect(x, y, width - 1, height - 1);
        g.setColor(68, 46, 31); g.drawRect(x + 3, y + 3, width - 7, height - 7);
    }

    private void drawWorldSelect(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawDirtBackground(g);
        drawInsetPanel(g, 8, 7, w - 16, h - 42);
        g.setColor(255, 255, 255);
        g.drawString(Language.text("Seleccionar mundo"), w / 2, 12, Graphics.TOP | Graphics.HCENTER);
        int top = 38, rowHeight = 30, visible = 7;
        int total = savedWorlds.length + 2;
        int first = worldSelection - visible + 1;
        if (first < 0) first = 0;
        int maxFirst = total - visible;
        if (maxFirst < 0) maxFirst = 0;
        if (first > maxFirst) first = maxFirst;
        for (int row = 0; row < visible; row++) {
            int index = first + row;
            if (index >= total) break;
            int y = top + row * rowHeight;
            boolean selected = index == worldSelection;
            g.setColor(selected ? 101 : 67, selected ? 73 : 49, selected ? 48 : 34);
            g.fillRect(15, y, w - 30, rowHeight - 2);
            g.setColor(selected ? 224 : 145, selected ? 191 : 115, selected ? 132 : 83);
            g.drawRect(15, y, w - 31, rowHeight - 3);
            g.setColor(255, 244, 220);
            if (index < savedWorlds.length) {
                VoxelWorld.SavedWorld entry = savedWorlds[index];
                String name = trimText(entry.name, 21);
                g.drawString(name, 21, y + 2, Graphics.TOP | Graphics.LEFT);
                g.setColor(214, 196, 167);
                g.drawString(Language.text(entry.mode == 1 ? "Creativo" : "Supervivencia"), 21,
                        y + 15, Graphics.TOP | Graphics.LEFT);
            } else {
                String label = index == savedWorlds.length ? "Crear mundo nuevo" : "Volver";
                g.drawString((selected ? "> " : "  ") + Language.text(label), w / 2, y + 7,
                        Graphics.TOP | Graphics.HCENTER);
            }
        }
        g.setColor(255, 240, 205);
        g.drawString(Language.text("2/8 elegir  5 abrir  1 borrar"), w / 2, h - 27,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawWorldDelete(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawDirtBackground(g);
        drawInsetPanel(g, 12, h / 2 - 64, w - 24, 128);
        g.setColor(255, 255, 255);
        g.drawString(Language.text("Borrar mundo"), w / 2, h / 2 - 52,
                Graphics.TOP | Graphics.HCENTER);
        String name = worldDeleteSelection >= 0 && worldDeleteSelection < savedWorlds.length ?
                savedWorlds[worldDeleteSelection].name : Language.text("Mundo");
        g.setColor(255, 230, 205);
        g.drawString(trimText(name, 22), w / 2, h / 2 - 20,
                Graphics.TOP | Graphics.HCENTER);
        g.setColor(255, 110, 90);
        g.drawString(Language.text(worldDeleteFailed ? "No se pudo borrar" : "Se eliminara para siempre"),
                w / 2, h / 2 + 3, Graphics.TOP | Graphics.HCENTER);
        g.setColor(255, 240, 205);
        g.drawString(Language.text("5 confirmar  0 cancelar"), w / 2, h / 2 + 36,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawWorldCreate(Graphics g) {
        int w = getWidth();
        drawDirtBackground(g);
        drawInsetPanel(g, 8, 34, w - 16, 258);
        g.setColor(255, 255, 255);
        g.drawString(Language.text("Crear mundo"), w / 2, 40, Graphics.TOP | Graphics.HCENTER);
        drawMenuItem(g, "Nombre: " + trimText(newWorldName, 17), 70, createSelection == 0);
        drawMenuItem(g, "Modo: " + (newWorldMode == 0 ? "Supervivencia" : "Creativo"), 104, createSelection == 1);
        drawMenuItem(g, "Tamaño: " + (newWorldSize == VoxelWorld.SIZE_OLD ? "Old 240x240" : "Infinite"), 138, createSelection == 2);
        drawMenuItem(g, "Tipo: " + (newWorldType == VoxelWorld.TYPE_FLAT ? "Plano" : "Standard"), 172, createSelection == 3);
        drawMenuItem(g, "Crear mundo", 206, createSelection == 4);
        drawMenuItem(g, "Volver", 240, createSelection == 5);
        g.setColor(255, 240, 205);
        g.drawString(Language.text("2/8 mover  4/6 cambiar  5 elegir"), w / 2, 276, Graphics.TOP | Graphics.HCENTER);
    }

    private String trimText(String text, int maxChars) {
        if (text == null) return "Mundo";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 3) + "...";
    }

    private void drawHud(Graphics g) {
        int w = getWidth(), h = getHeight();
        int centerX = w / 2, centerY = h / 2;
        g.setColor(255, 255, 255);
        g.drawLine(centerX - 4, centerY, centerX + 4, centerY);
        g.drawLine(centerX, centerY - 4, centerX, centerY + 4);
        if (world.mode == 0) {
            drawHearts(g);
            if (cameraUnderwater()) drawBubbles(g);
        }
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
            if (id > Block.AIR && id < ItemCatalog.COUNT &&
                (world.mode == 1 || inventory[id] > 0)) drawIcon(g, id, x, y + 3, false);
            if (world.mode == 0 && inventory[id] > 0) {
                if (!ItemCatalog.isTool(id)) {
                    drawOutlinedCount(g, Integer.toString(inventory[id]), x + 17, y + 20,
                            Graphics.RIGHT | Graphics.BOTTOM);
                } else drawDurability(g, id, x + 2, y + 19);
            }
        }
        g.drawString(Language.text(world.mode == 0 ? "Supervivencia" : "Creativo"), 3, 2, Graphics.TOP | Graphics.LEFT);
        g.drawString(Language.text(world.isNight() ? "Noche" : "Día"), w - 3, 2, Graphics.TOP | Graphics.RIGHT);
        if (world.mode == 1)
            g.drawString(Language.text(flying ? "Volando  5 subir  0 bajar" : "5 dos veces: volar"),
                    centerX, 13, Graphics.TOP | Graphics.HCENTER);
        if (portalChargeTicks > 0) {
            int seconds = (PORTAL_CHARGE_TICKS - portalChargeTicks + 19) / 20;
            g.drawString(Language.text("Portal: sal para cancelar") + " " + seconds + "s",
                    centerX, 27, Graphics.TOP | Graphics.HCENTER);
        }
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

    private void drawBubbles(Graphics g) {
        int full = (airTicks * 10 + MAX_AIR - 1) / MAX_AIR;
        if (full < 0) full = 0;
        if (full > 10) full = 10;
        int y = getHeight() - 41;
        for (int i = 0; i < 10; i++) {
            int x = getWidth() / 2 - 91 + i * 8;
            if (iconsTexture != null && iconsTexture.getWidth() >= 34 && iconsTexture.getHeight() >= 27) {
                g.drawRegion(iconsTexture, i < full ? 16 : 25, 18, 9, 9, Sprite.TRANS_NONE,
                        x, y, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    private Image chestOverlay() {
        if (chestOverlayImage == null) {
            int[] pixels = new int[16 * 16];
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0x80000000;
            chestOverlayImage = Image.createRGBImage(pixels, 16, 16, true);
        }
        return chestOverlayImage;
    }

    /** Tile a tiny alpha image to tint the rendered world without a screen-sized heap buffer. */
    private void drawTint(Graphics g, Image tint) {
        if (tint == null) return;
        int width = getWidth(), height = getHeight();
        for (int y = 0; y < height; y += 16) {
            int tileHeight = Math.min(16, height - y);
            for (int x = 0; x < width; x += 16) {
                int tileWidth = Math.min(16, width - x);
                g.drawRegion(tint, 0, 0, tileWidth, tileHeight, Sprite.TRANS_NONE,
                        x, y, Graphics.TOP | Graphics.LEFT);
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
        if (id == Block.CRAFTING_TABLE) return 0x9B6534;
        if (id == Block.COBBLESTONE) return 0x777777;
        return 0x777777;
    }

    private void drawCrafting(Graphics g, boolean workbench) {
        int w = getWidth(), h = getHeight();
        final int panelW = 176, panelH = 222;
        int left = (w - panelW) / 2;
        int top = (h - panelH) / 2;
        int count = Crafting.count(workbench);
        int selected = clamp(recipeSelection, 0, count - 1);
        boolean available = recipeAvailable(selected, workbench);

        if (containerTexture != null && containerTexture.getWidth() >= panelW &&
            containerTexture.getHeight() >= panelH) {
            g.drawRegion(containerTexture, 0, 0, panelW, panelH, Sprite.TRANS_NONE,
                    left, top, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(198, 198, 198); g.fillRect(left, top, panelW, panelH);
            g.setColor(40, 40, 40); g.drawRect(left, top, panelW - 1, panelH - 1);
        }

        // The reference container is 9 columns by 6 rows. Use those rows as a
        // compact recipe list; unavailable entries remain visible in gray.
        int first = recipeScroll;
        if (first > count - 6) first = count > 6 ? count - 6 : 0;
        g.setClip(left + 3, top + 1, panelW - 6, 15);
        g.setColor(35, 35, 35);
        g.drawString(Language.text(workbench ? "Recetas de la mesa" : "Crafteo 2x2"),
                left + panelW / 2, top + 1, Graphics.TOP | Graphics.HCENTER);
        g.drawString((inventoryPage + 1) + "/" + inventoryPageCount(),
                left + panelW - 7, top + 1, Graphics.TOP | Graphics.RIGHT);
        g.setClip(0, 0, w, h);
        for (int row = 0; row < 6; row++) {
            int recipe = first + row;
            if (recipe >= count) break;
            int y = top + 17 + row * 18;
            boolean rowAvailable = recipeAvailable(recipe, workbench);
            if (recipe == selected) {
                g.setColor(104, 104, 104);
                g.fillRect(left + 4, y, panelW - 8, 18);
            }
            int output = Crafting.OUTPUT[recipe];
            drawIcon(g, output, left + 6, y + 1, !rowAvailable);
            g.setColor(rowAvailable ? (recipe == selected ? 255 : 35) : 115,
                       rowAvailable ? (recipe == selected ? 230 : 35) : 115,
                       rowAvailable ? (recipe == selected ? 100 : 35) : 115);
            g.drawString(Language.text(Crafting.NAME[recipe]), left + 26, y + 2, Graphics.TOP | Graphics.LEFT);
            String amount = "x" + Crafting.OUTPUT_COUNT[recipe];
            g.drawString(amount, left + panelW - 7, y + 2, Graphics.TOP | Graphics.RIGHT);
        }

        // Player inventory: 27 item slots and the nine hotbar slots below it.
        for (int slot = 0; slot < 27; slot++) {
            int sx = left + 5 + (slot % 9) * 18;
            int sy = top + 139 + (slot / 9) * 18;
            int itemId = inventoryCellItem(slot);
            if (itemId < 0 || itemId >= ItemCatalog.COUNT) continue;
            drawInventoryCell(g, itemId, sx, sy);
        }
        for (int slot = 0; slot < hotbar.length; slot++) {
            int sx = left + 5 + slot * 18;
            int sy = top + 199;
            drawInventoryCell(g, hotbar[slot], sx, sy);
            if (hotbar[slot] > 0 && inventory[hotbar[slot]] > 0 && !ItemCatalog.isTool(hotbar[slot])) {
                drawOutlinedCount(g, Integer.toString(inventory[hotbar[slot]]), sx + 16, sy + 16,
                        Graphics.RIGHT | Graphics.BOTTOM);
            }
        }

        g.setColor(35, 35, 35);
        g.drawString(Language.text(available ? "5 crear" : "No disponible"),
                w / 2, top + panelH + 2, Graphics.TOP | Graphics.HCENTER);
        g.drawString(Language.text("# cerrar  |  2/8 recetas  4/6 objetos"), w / 2, top + panelH + 15,
                Graphics.TOP | Graphics.HCENTER);
    }

    private boolean recipeAvailable(int recipe, boolean workbench) {
        return (!Crafting.requiresWorkbench(recipe) || workbench) &&
               Crafting.canCraft(recipe, inventory, world.mode == 1);
    }

    private int inventoryCellItem(int cell) {
        int visibleBlocks = 0;
        for (int id = 1; id < Block.DECORATIVE_START; id++) {
            if (id == Block.WATER || id == Block.BEDROCK || id == Block.LAVA) continue;
            if (visibleBlocks++ == cell) return id;
        }
        if (cell == visibleBlocks) return Block.DOOR;
        int pageSize = 27 - visibleBlocks - 1;
        int item = Block.COUNT + inventoryPage * pageSize + cell - visibleBlocks - 1;
        return item < ItemCatalog.COUNT ? item : -1;
    }

    private void drawCreativeInventory(Graphics g) {
        int w = getWidth(), h = getHeight();
        final int panelW = 176, panelH = 222, pageSize = 54;
        int left = (w - panelW) / 2;
        int top = (h - panelH) / 2;
        if (containerTexture != null && containerTexture.getWidth() >= panelW &&
            containerTexture.getHeight() >= panelH) {
            g.drawRegion(containerTexture, 0, 0, panelW, panelH, Sprite.TRANS_NONE,
                    left, top, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(198, 198, 198); g.fillRect(left, top, panelW, panelH);
            g.setColor(40, 40, 40); g.drawRect(left, top, panelW - 1, panelH - 1);
        }
        int first = creativeSelection / pageSize * pageSize;
        int pageCount = (creativeItemCount() + pageSize - 1) / pageSize;
        g.setColor(35, 35, 35);
        g.drawString(Language.text("Inventario creativo"), w / 2, top + 3, Graphics.TOP | Graphics.HCENTER);
        for (int cell = 0; cell < pageSize; cell++) {
            int paletteIndex = first + cell;
            if (paletteIndex >= creativeItemCount()) break;
            int block = creativeItemAt(paletteIndex);
            int x = left + 7 + (cell % 9) * 18;
            int y = top + 23 + (cell / 9) * 18;
            if (paletteIndex == creativeSelection) {
                g.setColor(255, 255, 255);
                g.fillRect(x - 1, y - 1, 18, 18);
                g.setColor(90, 90, 90);
                g.fillRect(x, y, 16, 16);
            }
            drawIcon(g, block, x, y, false);
        }
        for (int slot = 0; slot < hotbar.length; slot++) {
            int x = left + 7 + slot * 18;
            int y = top + 199;
            drawInventoryCell(g, hotbar[slot], x, y);
        }
        int selectedBlock = creativeItemAt(creativeSelection);
        g.setColor(35, 35, 35);
        String name = ItemCatalog.name(selectedBlock);
        if (name.length() > 16) name = name.substring(0, 16);
        g.drawString(name + "  " + (creativeSelection / pageSize + 1) + "/" + pageCount,
                w / 2, top + 137, Graphics.TOP | Graphics.HCENTER);
        g.drawString(Language.text("4/6 mover  2/8 fila  5 tomar  # cerrar"),
                w / 2, top + panelH + 2, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawInventoryCell(Graphics g, int id, int x, int y) {
        if (id <= 0 || id >= ItemCatalog.COUNT) return;
        int amount = inventory[id];
        if (world.mode == 1 || amount > 0) {
            drawIcon(g, id, x + 1, y + 1, false);
            if (world.mode == 0 && amount > 1 && !ItemCatalog.isTool(id)) {
                drawOutlinedCount(g, Integer.toString(amount), x + 17, y + 17,
                        Graphics.RIGHT | Graphics.BOTTOM);
            }
            if (ItemCatalog.isTool(id) && amount > 0) drawDurability(g, id, x + 1, y + 16);
        }
    }

    private void drawOutlinedCount(Graphics g, String text, int x, int y, int anchor) {
        g.setColor(0, 0, 0);
        g.drawString(text, x - 1, y, anchor);
        g.drawString(text, x + 1, y, anchor);
        g.drawString(text, x, y - 1, anchor);
        g.drawString(text, x, y + 1, anchor);
        g.setColor(255, 255, 255);
        g.drawString(text, x, y, anchor);
    }

    private void drawIcon(Graphics g, int id, int x, int y, boolean muted) {
        if (id <= Block.AIR || id >= ItemCatalog.COUNT) return;
        if (id < Block.COUNT && terrainTexture != null &&
            (Block.isDecorative((byte)id) || id == Block.TNT || id == (Block.CHEST & 255))) {
            // A chest's internal block ID is not its terrain-atlas tile.
            int tile = id == (Block.CHEST & 255) ? 25 : Block.atlasTile(id);
            g.drawRegion(terrainTexture, (tile & 15) * 16, (tile >> 4) * 16, 16, 16,
                    Sprite.TRANS_NONE, x, y, Graphics.TOP | Graphics.LEFT);
            return;
        }
        Image icon = muted ? mutedItemIcons[id] : itemIcons[id];
        if (icon == null && id < blockIcons.length) icon = blockIcons[id];
        if (icon != null) g.drawImage(icon, x, y, Graphics.TOP | Graphics.LEFT);
        else {
            g.setColor(muted ? 120 : blockColor(id));
            g.fillRect(x + 2, y + 2, 12, 12);
        }
    }

    private void drawDurability(Graphics g, int id, int x, int y) {
        int max = ItemCatalog.maxDamage(id);
        if (max <= 0) return;
        int remaining = max - world.itemDamage[id];
        int width = remaining * 14 / max;
        if (width < 0) width = 0;
        g.setColor(0, 0, 0); g.fillRect(x, y, 14, 2);
        g.setColor(255 - width * 255 / 14, width * 255 / 14, 0);
        g.fillRect(x, y, width, 1);
    }

    private void drawPause(Graphics g) {
        int w = getWidth(), h = getHeight();
        drawTint(g, pauseTint);
        int panelWidth = Math.min(220, w - 20);
        int panelHeight = 150;
        int panelX = (w - panelWidth) / 2;
        int panelY = (h - panelHeight) / 2;
        drawInsetPanel(g, panelX, panelY, panelWidth, panelHeight);
        Font oldFont = g.getFont();
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE));
        g.setColor(255, 244, 210);
        g.drawString(Language.text("Menú del juego"), w / 2, panelY + 9,
                Graphics.HCENTER | Graphics.TOP);
        g.setFont(oldFont);
        g.setColor(52, 37, 26);
        g.drawLine(panelX + 9, panelY + 34, panelX + panelWidth - 10, panelY + 34);
        g.setColor(174, 140, 99);
        g.drawLine(panelX + 9, panelY + 35, panelX + panelWidth - 10, panelY + 35);
        for (int i = 0; i < PAUSE_ROWS.length; i++)
            drawMenuItem(g, PAUSE_ROWS[i], panelY + 40 + i * 27, i == pauseSelection);
        g.setColor(237, 220, 187);
        g.drawString(Language.text("2/8 elegir   5 aceptar   0 menú"), w / 2, panelY + 126,
                Graphics.TOP | Graphics.HCENTER);
    }

    private void drawGameOver(Graphics g) {
        int w = getWidth(), h = getHeight();
        float progress = gameOverTicks / 12.0f;
        if (progress > 1.0f) progress = 1.0f;
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
        int enterOffset = (int)((1.0f - eased) * 18.0f);
        Font oldFont = g.getFont();
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE));
        g.setColor(255, 255, 255);
        g.drawString(Language.text("Game over!"), w / 2, h / 4 - 20 - enterOffset,
                Graphics.HCENTER | Graphics.TOP);
        g.setFont(oldFont);
        String scorePrefix = "Score: ";
        int scoreWidth = oldFont.stringWidth(scorePrefix + "0");
        int scoreX = (w - scoreWidth) / 2;
        int scoreY = h / 4 + 20 - enterOffset / 2;
        g.setColor(255, 255, 255);
        g.drawString(scorePrefix, scoreX, scoreY, Graphics.TOP | Graphics.LEFT);
        g.setColor(255, 220, 55);
        g.drawString("0", scoreX + oldFont.stringWidth(scorePrefix), scoreY,
                Graphics.TOP | Graphics.LEFT);
        int buttonOffset = (int)((1.0f - eased) * 14.0f);
        drawGameOverButton(g, "Generate new level...", h / 4 + 72 + buttonOffset, gameOverSelection == 0);
        drawGameOverButton(g, "Load level..", h / 4 + 96 + buttonOffset, gameOverSelection == 1);
    }

    /** Fade the Indev red overlay in during the first twelve game-over frames. */
    private void drawDeathGradient(Graphics g) {
        int height = Math.min(getHeight(), deathTint == null ? getHeight() : deathTint.getHeight());
        int revealHeight = (height * gameOverTicks) / 12;
        if (revealHeight <= 0) return;
        if (deathTint == null) {
            g.setColor(112, 32, 32);
            g.fillRect(0, 0, getWidth(), revealHeight);
            return;
        }
        int width = getWidth();
        for (int x = 0; x < width; x += deathTint.getWidth()) {
            g.drawRegion(deathTint, 0, 0, deathTint.getWidth(), revealHeight,
                    Sprite.TRANS_NONE, x, 0, Graphics.TOP | Graphics.LEFT);
        }
    }

    /** Grey, inset button styling and positions follow GuiGameOver's 240x320 layout. */
    private void drawGameOverButton(Graphics g, String label, int y, boolean selected) {
        int width = Math.min(200, getWidth() - 16);
        int x = (getWidth() - width) / 2;
        int height = 20;
        int fill = selected ? 0x808080 : 0x606060;
        g.setColor(0, 0, 0);
        g.fillRect(x, y, width, height);
        g.setColor(0xA0A0A0);
        g.drawLine(x, y, x + width - 1, y);
        g.drawLine(x, y, x, y + height - 1);
        g.setColor(fill);
        g.fillRect(x + 1, y + 1, width - 2, height - 2);
        g.setColor(0x404040);
        g.drawLine(x + 1, y + height - 1, x + width - 1, y + height - 1);
        g.drawLine(x + width - 1, y + 1, x + width - 1, y + height - 1);
        g.setColor(255, 255, 255);
        g.drawString(label, getWidth() / 2, y + (height - Font.getDefaultFont().getHeight()) / 2,
                Graphics.TOP | Graphics.HCENTER);
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



