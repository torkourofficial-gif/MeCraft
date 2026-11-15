package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.Camera;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Fog;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.IndexBuffer;
import javax.microedition.m3g.Light;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.Transform;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;
import javax.microedition.m3g.World;

/** Builds shared-texture chunk meshes on demand for the visible part of the world. */
final class ChunkRenderer {
    // The fixed slot ring supports the largest normal stream window. Shortest
    // distance scans only the chunk columns whose bounds can intersect its
    // 24-block (1.5-chunk) radius; it does not need an extra candidate ring.
    private static final int MAX_STREAM_RADIUS = 7;
    // All vertical layers are accumulated into one block mesh per chunk.
    private ChunkBuild pendingBuild;
    private int pendingBuildSlot = -1;
    // One complete chunk is built synchronously per renderer update.
    // Cache the active mesh job's camera key to skip a full stream-window scan
    // on every paint while that same chunk is being built.
    private boolean pendingViewKeyValid;
    private float pendingViewX, pendingViewY, pendingViewZ;
    private float pendingViewYaw;
    private float pendingViewPitch, pendingViewFar;
    private int pendingViewCenterX, pendingViewCenterZ, pendingViewRadius;
    private boolean pendingViewShortest;
    // Remember the next dirty chunk so paint calls can reuse the stream scan.
    private int pendingTargetSlot = -1;
    private int pendingTargetX, pendingTargetY, pendingTargetZ;
    // Reuse the camera basis while the player moves: PS1-style precomputed
    // transform data avoids repeating expensive trigonometry for every scan.
    private float basisYaw = Float.NaN, basisPitch = Float.NaN;
    private float basisForwardX, basisForwardY, basisForwardZ;
    private float basisRightX, basisRightZ;
    private float basisUpX, basisUpY, basisUpZ;
    private static final int MAX_STREAM_SPAN = MAX_STREAM_RADIUS * 2 + 1;
    static final int CACHE_SIZE = MAX_STREAM_SPAN * MAX_STREAM_SPAN * VoxelWorld.CHUNKS_Y;
    private static final int TILE_COUNT = 256;
    private static final int TORCH_LIGHT_RADIUS = 6;
    private static final int LAVA_LIGHT_RADIUS = 6;
    private static final int MAX_CHUNK_TORCHES = 128;
    private final int[] chunkTorchX = new int[MAX_CHUNK_TORCHES];
    private final int[] chunkTorchY = new int[MAX_CHUNK_TORCHES];
    private final int[] chunkTorchZ = new int[MAX_CHUNK_TORCHES];
    private final byte[] chunkLightRadius = new byte[MAX_CHUNK_TORCHES];
    private final boolean[] chunkLavaSource = new boolean[MAX_CHUNK_TORCHES];
    private int chunkTorchCount;
    private static final int CHUNK_AREA = VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_XZ;
    private static final float CHUNK_RADIUS = 12.0f;
    private static final float NORMAL_FOV = 68.0f;
    private static final float SPRINT_FOV = 76.0f;
    private float cameraFov = NORMAL_FOV;
    private int streamRadius = 2;
    private boolean shortestDistance;
    private float cameraFar = 88.0f;
    private float tanVerticalFov = (float)Math.tan(Math.PI * 34.0 / 180.0);

    final World scene = new World();
    final Camera camera = new Camera();
    private final Camera externalCamera = new Camera();
    private final Group externalCameraPivot = new Group();
    private final Group externalCameraYawPivot = new Group();
    private final Transform cameraTransform = new Transform();
    private final Transform handTransform = new Transform();
    final Group cameraPivot = new Group();
    private final Group cameraEffectPivot = new Group();
    private final Group cameraYawPivot = new Group();
    private final Group handPivot = new Group();
    private final Group heldItemOffset = new Group();
    private final Group heldItemYaw = new Group();
    private final Group heldItemRoll = new Group();
    private final Group heldItemPitch = new Group();
    private final Group itemSpriteBaseYaw = new Group();
    private final Group itemSpriteSwingYaw = new Group();
    private final Group itemSpriteSwingRoll = new Group();
    private final Group itemSpriteSwingPitch = new Group();
    private final Group itemSpriteModelScale = new Group();
    private final Group itemSpriteOffset = new Group();
    private final Group itemSpriteIconScale = new Group();
    private final Group itemSpriteIconYaw = new Group();
    private final Group itemSpriteIconRoll = new Group();
    private final Group itemSpriteOrigin = new Group();
    private final Group armSwingOffset = new Group();
    private final Group armBase = new Group();
    private final Group armSwingYaw = new Group();
    private final Group armSwingRoll = new Group();
    private final Group handOffset = new Group();
    private final Group handRotateZ = new Group();
    private final Group handRotateX = new Group();
    private final Group handRotateY = new Group();
    private final Group handScale = new Group();
    private final Group handModelOffset = new Group();
    private final Group terrain = new Group();
    private final Background sky = new Background();
    // Shared linear fog softens the edge of the compact radial view.
    private final Fog terrainFog = new Fog();
    private final SkySystem skyObjects;
    private final Light sunlight = new Light();
    private final Light[] torchLights = new Light[3];
    private final float[] nearestTorchDistance = new float[] { 65.0f, 65.0f, 65.0f };
    private final int[] nearestTorchX = new int[3];
    private final int[] nearestTorchY = new int[3];
    private final int[] nearestTorchZ = new int[3];
    private int torchScanX = Integer.MIN_VALUE;
    private int torchScanY = Integer.MIN_VALUE;
    private int torchScanZ = Integer.MIN_VALUE;
    private int torchScanRevision = Integer.MIN_VALUE;
    private final Chunk[] chunks = new Chunk[CACHE_SIZE];
    // Assigned slots include chunks whose first mesh is still being built.
    private final boolean[] assignedChunks = new boolean[CACHE_SIZE];
    private final boolean[] dirtyChunks = new boolean[CACHE_SIZE];
    private final int[] chunkWorldX = new int[CACHE_SIZE];
    private final int[] chunkWorldY = new int[CACHE_SIZE];
    private final int[] chunkWorldZ = new int[CACHE_SIZE];
    // Full voxel snapshot for one chunk section; each source layer is copied into place once.
    private final byte[] chunkVoxelScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
    // Separate staging row prevents copyLoadedChunkLayer from overwriting earlier snapshot layers.
    private final byte[] chunkVoxelLayerScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_XZ];
    private final byte[] chunkLightScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
    private final int[] greedyMaskScratch = new int[CHUNK_AREA];
    // One chunk is meshed at a time; reuse the large material lookup table to
    // avoid allocating thousands of references every time the player moves.
    // One material batch per atlas tile. Light levels remain separate greedy
    // rectangles, but their vertex colors share the same M3G mesh.
    private final FaceBuilder[] chunkFaceBuilders = new FaceBuilder[TILE_COUNT];
    private final int[] activeFaceBuilderIndices = new int[TILE_COUNT];
    private int activeFaceBuilderCount;
    private int completedChunkBuilds;
    private int completedDirtyRebuilds;
    private long lastChunkBuildMillis;
    private long lastSnapshotBuildMillis;
    private long lastMeshAssemblyMillis;
    private long chunkBuildStartedAt;
    // Per-chunk accumulators: tile geometry is retained across every vertical layer.
    private FlowerBuilder buildingFlowers;
    private FireBuilder buildingFires;
    private PortalBuilder buildingPortals;
    // A bounded pool avoids allocating FaceBuilder arrays for every streamed
    // chunk without retaining one builder for every possible tile/light pair.
    private final FaceBuilder[] freeFaceBuilders = new FaceBuilder[32];
    private int freeFaceBuilderCount;
    private final FlowerBuilder reusableFlowerBuilder = new FlowerBuilder();
    private final PortalBuilder reusablePortalBuilder = new PortalBuilder();
    private final Image terrainImage;
    private final Texture2D atlas;
    private final Texture2D itemAtlas;
    private final Texture2D boatTexture;
    private final Texture2D cartTexture;
    private final RailSystem railSystem;
    private BoatEntity boat;
    private MinecartEntity minecart;
    private final Texture2D[] portalFrames = new Texture2D[8];
    private final Texture2D[] crackFrames = new Texture2D[10];
    private final Appearance crackAppearance;
    private final VertexArray crackUV;
    private final short[] crackUVData = new short[] { 0,16, 0,0, 16,16, 16,0 };
    private int activeMiningChunkSlot = -1;
    private int activeCrackChunkSlot = -1;
    // Keep the crack overlay's world-space target so a chunk remesh can refresh
    // its separate M3G mesh in the same frame as the terrain replacement.
    private int activeCrackX = -1, activeCrackY = -1, activeCrackZ = -1;
    private int activeCrackFaceX, activeCrackFaceY, activeCrackFaceZ;
    private int activeCrackStage = -1;
    private final Appearance portalAppearance;
    private final Texture2D worldFireTexture;
    private final Appearance worldFireAppearance;
    private int fireAnimationTick;
    private int fireAnimationFrame;
    private int portalFrameSize = 16;
    private int portalAnimationTick;
    private int portalAnimationFrame;
    private final Appearance[] tileAppearances = new Appearance[TILE_COUNT];
    private final Appearance flowerAppearance;
    private final Appearance heldItemAppearance;
    private final Appearance itemSpriteAppearance;
    private final Appearance handAppearance;
    private final Mesh handMesh;
    private VertexArray handTexCoords;
    private final Mesh itemSpriteMesh;
    private VertexArray itemSpriteTexCoordArray;
    private final short[] itemSpriteUV;
    private final Mesh[][] heldItemMeshes = new Mesh[Block.COUNT][];
    private Mesh heldCreativeBlockMesh;
    private VertexArray heldCreativeBlockUVArray;
    private final short[] heldCreativeBlockUV = new short[48];
    private final ParticleSystem particles;
    private final DroppedItemSystem droppedItems;
    private final MobSystem mobs;
    private int visibleHeldItem = -1;
    private int handSwingTicks = 8;
    private boolean handSwinging;
    private boolean handLooping;
    private float handHorizontalScale = 1.0f;
    private int builtRevision = -1;
    private boolean builtNether;
    private boolean hasBuiltDimension;
    private int builtWidth;
    private int builtHeight;
    private float tanHorizontalFov;
    private float cameraPitch;
    private int cameraMode; // 0 first-person, 1 rear third-person, 2 front-facing second-person
    private boolean playerVisible = true;
    private float thirdPersonDistance = 3.8f;
    private PlayerModel playerModel;
    private float previousPlayerX, previousPlayerZ;
    private boolean hasPreviousPlayerPosition;
    private float currentDaylight = 1.0f;
    private int currentSkyColor = 0xFF82B8E8;

    ChunkRenderer(int width, int height) throws IOException {
        if (width <= 0) width = 1;
        if (height <= 0) height = 1;
        terrainImage = GameAssets.terrain();
        Image2D image = new Image2D(Image2D.RGBA, terrainImage);
        atlas = new Texture2D(image);
        atlas.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        atlas.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        boatTexture = new Texture2D(new Image2D(Image2D.RGBA, GameAssets.image("boat.png")));
        boatTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        boatTexture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        cartTexture = new Texture2D(new Image2D(Image2D.RGBA, GameAssets.image("cart.png")));
        cartTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        cartTexture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        railSystem = new RailSystem(atlas);
        makePortalFrames(GameAssets.image("portal.png"));
        makeCrackFrames(terrainImage);
        crackAppearance = createFirstPersonAppearance(crackFrames[0], true, false, false);
        // Unlike the first-person hand, cracks must respect terrain depth so they
        // cannot show through blocks; keep depth writes disabled for the thin overlay.
        CompositingMode crackCompositing = new CompositingMode();
        crackCompositing.setDepthTestEnable(true);
        crackCompositing.setDepthWriteEnable(false);
        crackCompositing.setAlphaThreshold(0.1f);
        crackCompositing.setBlending(CompositingMode.ALPHA);
        crackAppearance.setCompositingMode(crackCompositing);
        crackUV = new VertexArray(4, 2, 2);
        crackUV.set(0, 4, crackUVData);
        portalAppearance = createPortalAppearance(portalFrames[0]);
        Image fireImage = GameAssets.image("fire.png");
        worldFireTexture = new Texture2D(new Image2D(Image2D.RGBA, fireImage));
        worldFireTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        worldFireTexture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        worldFireAppearance = createFireAppearance(worldFireTexture);
        flowerAppearance = createFlowerAppearance();
        heldItemAppearance = createFirstPersonAppearance(atlas, true, true, true);
        Image itemSource = GameAssets.image("items.png");
        itemAtlas = new Texture2D(new Image2D(Image2D.RGBA, itemSource));
        itemAtlas.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        itemAtlas.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        itemSpriteAppearance = createFirstPersonAppearance(itemAtlas, true, false, false);
        ItemSpriteData itemData = makeItemSpriteMesh();
        itemSpriteMesh = itemData.mesh;
        itemSpriteTexCoordArray = itemData.texCoords;
        itemSpriteUV = itemData.uv;
        handAppearance = createHandAppearance();
        handMesh = makeHandMesh();
        armBase.setTranslation(handBaseX(width, height), -0.60f, -0.72f);
        armSwingOffset.setTranslation(-0.08f * handHorizontalScale, 0.15f, 0.0f);
        armSwingYaw.setOrientation(45.0f, 0.0f, 1.0f, 0.0f);
        armSwingOffset.addChild(armBase);
        armBase.addChild(armSwingYaw);
        armSwingYaw.addChild(armSwingRoll);
        armSwingRoll.addChild(handOffset);
        handOffset.addChild(handRotateZ);
        handRotateZ.addChild(handRotateX);
        handRotateX.addChild(handRotateY);
        handRotateY.addChild(handScale);
        handScale.addChild(handModelOffset);
        handModelOffset.addChild(handMesh);
        handOffset.setTranslation(-0.2f, -0.3f, 0.1f);
        handRotateZ.setOrientation(120.0f, 0.0f, 0.0f, 1.0f);
        handRotateX.setOrientation(200.0f, 1.0f, 0.0f, 0.0f);
        handRotateY.setOrientation(-135.0f, 0.0f, 1.0f, 0.0f);
        handScale.setScale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
        // Indev translates by +6 before drawing the arm, whose pivot is (-5, 2, 0).
        handModelOffset.setTranslation(1.0f / 16.0f, 2.0f / 16.0f, 0.0f);
        handPivot.addChild(armSwingOffset);
        handPivot.addChild(heldItemOffset);
        heldItemOffset.addChild(heldItemYaw);
        heldItemYaw.addChild(heldItemRoll);
        heldItemRoll.addChild(heldItemPitch);
        heldItemOffset.addChild(itemSpriteBaseYaw);
        itemSpriteBaseYaw.addChild(itemSpriteSwingYaw);
        itemSpriteSwingYaw.addChild(itemSpriteSwingRoll);
        itemSpriteSwingRoll.addChild(itemSpriteSwingPitch);
        itemSpriteSwingPitch.addChild(itemSpriteModelScale);
        itemSpriteModelScale.addChild(itemSpriteOffset);
        itemSpriteOffset.addChild(itemSpriteIconScale);
        itemSpriteIconScale.addChild(itemSpriteIconYaw);
        itemSpriteIconYaw.addChild(itemSpriteIconRoll);
        itemSpriteIconRoll.addChild(itemSpriteOrigin);
        itemSpriteOrigin.addChild(itemSpriteMesh);
        itemSpriteModelScale.setScale(0.4f, 0.4f, 0.4f);
        itemSpriteOffset.setTranslation(0.0f, -0.3f, 0.0f);
        itemSpriteIconScale.setScale(1.5f, 1.5f, 1.5f);
        itemSpriteIconYaw.setOrientation(50.0f, 0.0f, 1.0f, 0.0f);
        itemSpriteIconRoll.setOrientation(335.0f, 0.0f, 0.0f, 1.0f);
        itemSpriteOrigin.setTranslation(-15.0f / 16.0f, -1.0f / 16.0f, 0.0f);
        buildHeldItems();
        particles = new ParticleSystem(atlas);
        droppedItems = new DroppedItemSystem(atlas, itemAtlas);
        mobs = new MobSystem(scene, droppedItems, particles);

        camera.setPerspective(cameraFov, (float)width / (float)height, 0.1f, cameraFar);
        externalCamera.setPerspective(cameraFov, (float)width / (float)height, 0.1f, cameraFar);
        externalCameraPivot.addChild(externalCameraYawPivot);
        externalCameraYawPivot.addChild(externalCamera);
        cameraPivot.setTranslation(0.0f, -0.13f, 0.0f);
        cameraPivot.addChild(cameraEffectPivot);
        cameraEffectPivot.addChild(cameraYawPivot);
        cameraYawPivot.addChild(camera);
        cameraEffectPivot.addChild(handPivot);
        skyObjects = new SkySystem(cameraPivot, sky, width, height);
        scene.addChild(cameraPivot);
        scene.addChild(terrain);
        scene.addChild(railSystem.group);
        scene.addChild(particles.mesh);
        scene.addChild(droppedItems.mesh);
        scene.addChild(droppedItems.spriteMesh);
        scene.addChild(droppedItems.tntFlashMesh);
        scene.setActiveCamera(camera);
        sky.setDepthClearEnable(true);
        scene.setBackground(sky);
        sunlight.setMode(Light.AMBIENT);
        sunlight.setIntensity(1.0f);
        scene.addChild(sunlight);
        for (int i = 0; i < torchLights.length; i++) {
            Light light = new Light();
            light.setMode(Light.OMNI);
            light.setColor(0xFFFFC46B);
            light.setAttenuation(1.0f, 0.10f, 0.035f);
            light.setIntensity(0.0f);
            torchLights[i] = light;
            scene.addChild(light);
        }
        builtWidth = width;
        builtHeight = height;
        tanHorizontalFov = tanVerticalFov * (float)width / (float)height;
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0 || (width == builtWidth && height == builtHeight)) return;
        builtWidth = width;
        builtHeight = height;
        tanHorizontalFov = tanVerticalFov * (float)width / (float)height;
        skyObjects.resize(width, height);
        camera.setPerspective(cameraFov, (float)width / (float)height, 0.1f, cameraFar);
        externalCamera.setPerspective(cameraFov, (float)width / (float)height, 0.1f, cameraFar);
        armBase.setTranslation(handBaseX(width, height), -0.60f, -0.72f);
        if (!handSwinging)
            armSwingOffset.setTranslation(-0.08f * handHorizontalScale, 0.15f, 0.0f);
    }

    private float handBaseX(int width, int height) {
        // Indev's 0.64 view-space offset was authored for a 4:3 viewport.
        // Scale only its horizontal placement to keep the same apparent angle
        // on narrow Java ME portrait screens.
        handHorizontalScale = ((float)width / (float)height) / (4.0f / 3.0f);
        if (handHorizontalScale > 1.0f) handHorizontalScale = 1.0f;
        return 0.64f * handHorizontalScale;
    }

    void updateFieldOfView(boolean sprinting) {
        float target = sprinting ? SPRINT_FOV : NORMAL_FOV;
        float next = cameraFov + (target - cameraFov) * 0.22f;
        if (Math.abs(target - next) < 0.15f) next = target;
        if (Math.abs(next - cameraFov) < 0.001f) return;
        cameraFov = next;
        tanVerticalFov = (float)Math.tan(cameraFov * Math.PI / 360.0);
        tanHorizontalFov = tanVerticalFov * (float)builtWidth / (float)builtHeight;
        camera.setPerspective(cameraFov, (float)builtWidth / (float)builtHeight, 0.1f, cameraFar);
        externalCamera.setPerspective(cameraFov, (float)builtWidth / (float)builtHeight, 0.1f, cameraFar);
    }

    void setRenderDistance(int setting) {
        if (setting < 0) setting = 0;
        if (setting > 4) setting = 4;
        // Shortest keeps the world's 16x16 generation chunks and admits
        // only chunk columns intersecting the 1.5-chunk circular view radius.
        shortestDistance = setting == 4;
        streamRadius = setting == 0 ? 1 : setting + 1;
        if (streamRadius > MAX_STREAM_RADIUS) streamRadius = MAX_STREAM_RADIUS;
        // Keep world chunks at 16x16. Shortest is a true 1.5-chunk (24-block)
        // radial admission distance, not a smaller chunk size or a 1-chunk grid.
        cameraFar = shortestDistance ? 1.5f * (float)VoxelWorld.CHUNK_XZ :
                ((streamRadius * 2 + 1) * (float)VoxelWorld.CHUNK_XZ) + 8.0f;
        terrainFog.setColor(currentSkyColor);
        terrainFog.setMode(Fog.LINEAR);
        // Match Alpha's linear fog: fade begins at one quarter of view distance.
        terrainFog.setLinear(cameraFar * 0.25f, cameraFar);
        camera.setPerspective(cameraFov, (float)builtWidth / (float)builtHeight,
                0.1f, cameraFar);
        externalCamera.setPerspective(cameraFov, (float)builtWidth / (float)builtHeight,
                0.1f, cameraFar);
        updateTerrainFog();
    }

    private void updateTerrainFog() {
        Fog fog = terrainFog;
        for (int i = 0; i < tileAppearances.length; i++) {
            if (tileAppearances[i] != null) tileAppearances[i].setFog(fog);
        }
        flowerAppearance.setFog(fog);
        worldFireAppearance.setFog(fog);
        portalAppearance.setFog(fog);
    }

    float fieldOfView() { return cameraFov; }

    BoatEntity boat() { return boat; }

    BoatEntity spawnBoat(float x, float y, float z) {
        if (boat != null) scene.removeChild(boat.mesh);
        boat = new BoatEntity(boatTexture, x, y, z);
        scene.addChild(boat.mesh);
        return boat;
    }

    void removeBoat() {
        if (boat != null) {
            scene.removeChild(boat.mesh);
            boat = null;
        }
    }

    MinecartEntity minecart() { return minecart; }

    MinecartEntity spawnMinecart(float x, float y, float z) {
        if (minecart != null) scene.removeChild(minecart.mesh);
        minecart = new MinecartEntity(cartTexture, x, y, z);
        scene.addChild(minecart.mesh);
        return minecart;
    }

    void removeMinecart() {
        if (minecart != null) {
            scene.removeChild(minecart.mesh);
            minecart = null;
        }
    }

    void updateCamera(VoxelWorld world, float pitch) {
        cameraPitch = pitch;

        // Keep the original first-person camera rig permanently independent.
        // External views use a second camera, like Minecraft's third-person
        // camera, so perspective switching never reparents the first-person rig.
        cameraPivot.setTranslation(world.playerX, world.playerY + 1.62f, world.playerZ);
        cameraPivot.setOrientation(world.yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        skyObjects.update(world, world.yaw + 180.0f,
                ((float)world.dayTime / (float)VoxelWorld.DAY_LENGTH - 0.15f) * 6.2831855f,
                currentDaylight, currentSkyColor);

        float moveX = 0.0f;
        float moveZ = 0.0f;
        boolean moving = false;
        if (hasPreviousPlayerPosition) {
            moveX = world.playerX - previousPlayerX;
            moveZ = world.playerZ - previousPlayerZ;
            moving = moveX * moveX + moveZ * moveZ > 0.0004f;
        }
        previousPlayerX = world.playerX;
        previousPlayerZ = world.playerZ;
        hasPreviousPlayerPosition = true;
        if (playerModel != null) {
            playerModel.root.setRenderingEnable(cameraMode != 0 && playerVisible);
            playerModel.update(world.playerX, world.playerY, world.playerZ,
                    world.yaw, moveX, moveZ, cameraPitch, 1.0f, moving, false, cameraMode != 0 && playerVisible);
        }

        if (cameraMode == 0) {
            // Original touch-branch first-person camera: no avatar parenting.
            camera.setTranslation(0.0f, 0.0f, 0.0f);
            camera.setOrientation(pitch, 1.0f, 0.0f, 0.0f);
            scene.setActiveCamera(camera);
        } else {
            float wanted = thirdPersonDistance;
            float yawRadians = world.yaw * 0.0174532925f;
            float side = cameraMode == 1 ? -1.0f : 1.0f;
            float dx = (float)Math.sin(yawRadians) * side;
            float dz = (float)Math.cos(yawRadians) * side;
            float cameraOffset = wanted;
            for (float distance = 0.20f; distance <= wanted; distance += 0.20f) {
                if (world.isSolidAt(floorCamera(world.playerX + dx * distance),
                        floorCamera(world.playerY + 1.55f),
                        floorCamera(world.playerZ + dz * distance))) {
                    cameraOffset = distance - 0.28f;
                    if (cameraOffset < 0.0f) cameraOffset = 0.0f;
                    break;
                }
            }
            // The external camera is parented to the avatar's head pivot.
            // Its local Z offset therefore follows both head yaw and pitch.
            externalCameraPivot.setTranslation(0.0f, 0.0f, 0.0f);
            externalCameraPivot.setOrientation(0.0f, 0.0f, 1.0f, 0.0f);
            externalCamera.setTranslation(0.0f, 0.0f, cameraOffset);
            externalCameraYawPivot.setOrientation(cameraMode == 2 ? 180.0f : 0.0f,
                    0.0f, 1.0f, 0.0f);
            // Head pitch is inherited from PlayerModel; do not apply it twice.
            externalCamera.setOrientation(0.0f, 1.0f, 0.0f, 0.0f);
            scene.setActiveCamera(externalCamera);
        }
        handPivot.setRenderingEnable(cameraMode == 0);
        handPivot.setOrientation(pitch, 1.0f, 0.0f, 0.0f);
    }

    private int floorCamera(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    /** Toggle avatar rendering independently from the camera, used by the title panorama. */
    void setPlayerVisible(boolean visible) {
        playerVisible = visible;
        if (playerModel != null) playerModel.root.setRenderingEnable(visible && cameraMode != 0);
    }

    /** Adjust third-person distance without changing gameplay's default camera. */
    void setThirdPersonDistance(float distance) {
        if (distance < 2.0f) distance = 2.0f;
        if (distance > 20.0f) distance = 20.0f;
        thirdPersonDistance = distance;
    }

    void cycleCameraMode() {
        cameraMode = (cameraMode + 1) % 3;
        hasPreviousPlayerPosition = false;
    }

    int getCameraMode() { return cameraMode; }

    /** Rotate the full first-person view during the Indev hurt-camera pulse. */
    void setHurtCameraRoll(float degrees) {
        cameraEffectPivot.setOrientation(degrees, 0.0f, 0.0f, 1.0f);
    }

    /** Render the first-person hand and held block in a final overlay pass. */
    void render(Graphics3D graphics) {
        boolean showHand = cameraMode == 0;
        if (showHand) handPivot.setRenderingEnable(false);
        try {
            graphics.render(scene);
        } finally {
            handPivot.setRenderingEnable(showHand);
        }
        if (showHand) {
            camera.getTransformTo(scene, cameraTransform);
            handPivot.getTransformTo(scene, handTransform);
            graphics.setCamera(camera, cameraTransform);
            graphics.render(handPivot, handTransform);
        }
    }

    void updateHeldItem(int itemId, boolean visible) {
        if (!visible || itemId <= Block.AIR || itemId >= ItemCatalog.COUNT) itemId = 0;
        handMesh.setRenderingEnable(itemId == Block.AIR);
        if (itemId == visibleHeldItem) return;
        setHeldVisualVisible(visibleHeldItem, false);
        visibleHeldItem = itemId;
        setHeldVisualVisible(visibleHeldItem, true);
        applyHandPose();
    }

    private void setHeldVisualVisible(int itemId, boolean visible) {
        if (itemId <= Block.AIR) return;
        if (itemId < Block.COUNT && Block.isDoor((byte)itemId)) {
            if (visible) setItemSprite(itemId);
            itemSpriteMesh.setRenderingEnable(visible && ItemCatalog.iconIndex(itemId) >= 0);
        } else if (Block.isDecorative((byte)itemId) || itemId == Block.TNT ||
                   ItemCatalog.isColoredWool(itemId)) {
            if (visible) setBlockMeshTile(itemId);
            if (heldCreativeBlockMesh != null) heldCreativeBlockMesh.setRenderingEnable(visible);
        } else if (itemId < Block.COUNT) {
            if (heldItemMeshes[itemId] != null) setMeshesVisible(heldItemMeshes[itemId], visible);
        } else {
            if (visible) setItemSprite(itemId);
            itemSpriteMesh.setRenderingEnable(visible && ItemCatalog.iconIndex(itemId) >= 0);
        }
    }

    private void setBlockMeshTile(int blockId) {
        boolean coloredWool = ItemCatalog.isColoredWool(blockId);
        int woolColor = coloredWool ? ItemCatalog.woolColor(blockId) : 0;
        if (coloredWool) heldCreativeBlockMesh.setAppearance(0,
                tileAppearance(Block.WOOL_TINT_TILE_START + woolColor));
        else heldCreativeBlockMesh.setAppearance(0, heldItemAppearance);
        int at = 0;
        for (int face = 0; face < 6; face++) {
            int tile = coloredWool ? 0 : Block.faceTile((byte)blockId, face);
            int u0 = (tile & 15) * 16 + 1, v0 = (tile >> 4) * 16 + 1;
            at = writeHeldCubeFace(at, face, u0, v0, u0 + 14, v0 + 14);
        }
        heldCreativeBlockUVArray.set(0, 24, heldCreativeBlockUV);
    }

    private int writeHeldCubeFace(int at, int face, int u0, int v0, int u1, int v1) {
        if (face == 0) {
            at = putHeldUV(at,u0,v1); at = putHeldUV(at,u0,v0);
            at = putHeldUV(at,u1,v1); at = putHeldUV(at,u1,v0);
        } else if (face == 1 || face == 4) {
            at = putHeldUV(at,u1,v1); at = putHeldUV(at,u0,v1);
            at = putHeldUV(at,u1,v0); at = putHeldUV(at,u0,v0);
        } else if (face == 5) {
            at = putHeldUV(at,u0,v0); at = putHeldUV(at,u1,v0);
            at = putHeldUV(at,u0,v1); at = putHeldUV(at,u1,v1);
        } else {
            at = putHeldUV(at,u0,v1); at = putHeldUV(at,u0,v0);
            at = putHeldUV(at,u1,v1); at = putHeldUV(at,u1,v0);
        }
        return at;
    }

    private int putHeldUV(int at, int u, int v) {
        heldCreativeBlockUV[at++] = (short)u;
        heldCreativeBlockUV[at++] = (short)v;
        return at;
    }

    private void setItemSprite(int itemId) {
        int tile = ItemCatalog.iconIndex(itemId);
        if (tile < 0) return;
        // Keep the held-item mesh bound to items.png; block meshes use textures.png.
        itemSpriteAppearance.setTexture(0, itemAtlas);
        int u0 = (tile & 15) * 16;
        int v0 = (tile >> 4) * 16;
        fillItemSpriteUV(u0, v0);
        itemSpriteTexCoordArray.set(0, itemSpriteUV.length / 2, itemSpriteUV);
    }

    void swingHand() {
        handSwingTicks = 0;
        handSwinging = true;
        if (playerModel != null) playerModel.swingAction();
    }

    void setHandLooping(boolean looping) {
        handLooping = looping;
        if (playerModel != null) playerModel.setActionLooping(looping);
        if (looping && !handSwinging) { handSwingTicks = 0; handSwinging = true; }
    }

    /** Indev first-person swing curve, sampled once per 50 ms game tick. */
    void updateHandAnimation() {
        if (playerModel != null) playerModel.tickActionAnimation();
        if (++portalAnimationTick >= 2) {
            portalAnimationTick = 0;
            portalAnimationFrame = (portalAnimationFrame + 1) & 7;
            portalAppearance.setTexture(0, portalFrames[portalAnimationFrame]);
        }
        if (++fireAnimationTick >= 2) {
            fireAnimationTick = 0;
            fireAnimationFrame = (fireAnimationFrame + 1) & 7;
            updateFireMeshes();
        }
        boolean chunkLooping = false;
        if (activeMiningChunkSlot >= 0 && activeMiningChunkSlot < chunks.length) {
            Chunk miningChunk = chunks[activeMiningChunkSlot];
            if (miningChunk != null) {
                if (miningChunk.handSwinging) {
                    miningChunk.handSwingTicks++;
                    if (miningChunk.handSwingTicks >= 8) {
                        if (miningChunk.handLooping) miningChunk.handSwingTicks = 0;
                        else { miningChunk.handSwingTicks = 8; miningChunk.handSwinging = false; }
                    }
                }
                handSwingTicks = miningChunk.handSwingTicks;
                handSwinging = miningChunk.handSwinging;
                chunkLooping = miningChunk.handLooping;
            }
        }
        if (!chunkLooping && (activeMiningChunkSlot < 0 || chunks[activeMiningChunkSlot] == null)) {
            if (handSwinging) {
                handSwingTicks++;
                if (handSwingTicks >= 8) {
                    if (handLooping) handSwingTicks = 0;
                    else { handSwingTicks = 8; handSwinging = false; }
                }
            }
        }
        applyHandPose();
    }

    private void applyHandPose() {
        float progress = handSwinging ? (float)handSwingTicks / 8.0f : 1.0f;
        float sinRoot = (float)Math.sin(Math.sqrt(progress) * Math.PI);
        float upDown = (float)Math.sin(Math.sqrt(progress) * Math.PI * 2.0);
        float sinLinear = (float)Math.sin(progress * Math.PI);
        if (handSwinging) {
            // Empty-hand swing from the Indev first-person arm path.
            armSwingOffset.setTranslation(-sinRoot * 0.3f * handHorizontalScale,
                    upDown * 0.4f, -sinLinear * 0.4f);
            armSwingYaw.setOrientation(45.0f + sinRoot * 70.0f, 0.0f, 1.0f, 0.0f);
            armSwingRoll.setOrientation(-sinLinear * 20.0f, 0.0f, 0.0f, 1.0f);
        } else {
            // Lift and move the resting hand inward so more of it stays on-screen.
            armSwingOffset.setTranslation(-0.08f * handHorizontalScale, 0.15f, 0.0f);
            armSwingYaw.setOrientation(45.0f, 0.0f, 1.0f, 0.0f);
            armSwingRoll.setOrientation(0.0f, 0.0f, 0.0f, 1.0f);
        }

        if (visibleHeldItem > 0) {
            float squaredProgress = progress * progress;
            float itemYaw = (float)Math.sin(squaredProgress * Math.PI) * -20.0f;
            float itemRoll = sinRoot * -20.0f;
            float itemPitch = sinRoot * -80.0f;
            float swingX = handSwinging ? -sinRoot * 0.4f : 0.0f;
            float swingY = handSwinging ? upDown * 0.2f : 0.0f;
            float swingZ = handSwinging ? -sinLinear * 0.2f : 0.0f;
            if (usesSpriteHandPose(visibleHeldItem)) {
                heldItemOffset.setTranslation(0.56f * handHorizontalScale + swingX,
                        -0.52f + swingY, -0.72f + swingZ);
                itemSpriteBaseYaw.setOrientation(45.0f, 0.0f, 1.0f, 0.0f);
                itemSpriteSwingYaw.setOrientation(itemYaw, 0.0f, 1.0f, 0.0f);
                itemSpriteSwingRoll.setOrientation(itemRoll, 0.0f, 0.0f, 1.0f);
                itemSpriteSwingPitch.setOrientation(itemPitch, 1.0f, 0.0f, 0.0f);
            } else {
                // Indev applies the same held-item swing to block models before
                // scaling them and calling renderBlockOnInventory.
                heldItemOffset.setTranslation(0.56f * handHorizontalScale + swingX,
                        -0.52f + swingY, -0.72f + swingZ);
                heldItemYaw.setOrientation(45.0f + itemYaw, 0.0f, 1.0f, 0.0f);
                heldItemRoll.setOrientation(itemRoll, 0.0f, 0.0f, 1.0f);
                heldItemPitch.setOrientation(itemPitch, 1.0f, 0.0f, 0.0f);
            }
        } else {
            setRestHeldItemPose();
        }
    }

    /** Flowers use the same first-person grip and swing path as Indev's stick. */
    private boolean usesSpriteHandPose(int itemId) {
        return (itemId < Block.COUNT && Block.isDoor((byte)itemId)) || itemId >= ItemCatalog.STICK ||
               (itemId > Block.AIR && itemId < Block.COUNT && Block.isFlower((byte)itemId));
    }

    private void setRestHandPose() {
        armSwingOffset.setTranslation(-0.08f * handHorizontalScale, 0.15f, 0.0f);
        armSwingYaw.setOrientation(45.0f, 0.0f, 1.0f, 0.0f);
        armSwingRoll.setOrientation(0.0f, 0.0f, 0.0f, 1.0f);
        setRestHeldItemPose();
    }

    private void setRestHeldItemPose() {
        heldItemOffset.setTranslation(0.64f * handHorizontalScale, -0.60f, -0.72f);
        heldItemYaw.setOrientation(45.0f, 0.0f, 1.0f, 0.0f);
        heldItemRoll.setOrientation(0.0f, 0.0f, 0.0f, 1.0f);
        heldItemPitch.setOrientation(0.0f, 1.0f, 0.0f, 0.0f);
    }

    private void buildHeldItems() {
        // The atlas palette is large; only build dedicated 3D held meshes for
        // the original gameplay blocks. Creative decorations use one shared
        // first-person sprite mesh to keep the M3G heap bounded.
        for (int id = 1; id < Block.DECORATIVE_START; id++) {
            if (Block.isFlower((byte)id)) {
                FlowerBuilder flower = new FlowerBuilder();
                flower.add(0, 0, 0, Block.sideTile((byte)id));
                Mesh flowerMesh = makeFlowerMesh(flower, heldItemAppearance);
                // Keep the crossed 3D plant, but parent it to the same pose
                // groups as the stick so its grip and swing line up exactly.
                flowerMesh.setTranslation(-0.5f, 0.0f, -0.5f);
                flowerMesh.setRenderingEnable(false);
                itemSpriteOrigin.addChild(flowerMesh);
                heldItemMeshes[id] = new Mesh[] { flowerMesh };
                continue;
            }
            FaceBuilder[] builders = new FaceBuilder[TILE_COUNT];
            for (int face = 0; face < 6; face++) {
                int tile = Block.faceTile((byte)id, face);
                add(builders, tile, 0, 0, 0, face);
            }
            int count = 0;
            for (int i = 0; i < builders.length; i++) if (builders[i] != null) count++;
            Mesh[] meshes = new Mesh[count];
            int at = 0;
            for (int i = 0; i < builders.length; i++) {
                if (builders[i] == null) continue;
                if (id == Block.GRASS && i == 0) tintFaceBuilder(builders[i], AlphaTint.GRASS_TOP);
                else if (id == Block.LEAVES && (i == 52 || i == 53))
                    tintFaceBuilder(builders[i], AlphaTint.LEAVES);
                Mesh mesh = makeMesh(builders[i], heldItemAppearance);
                // Center the cube at the hand anchor and keep it slightly smaller
                // than Indev's 0.4 inventory-render scale.
                mesh.setScale(0.34f, 0.34f, 0.34f);
                mesh.setTranslation(-0.17f, -0.17f, -0.17f);
                mesh.setRenderingEnable(false);
                heldItemPitch.addChild(mesh);
                meshes[at++] = mesh;
                builders[i] = null;
            }
            heldItemMeshes[id] = meshes;
        }
        buildCreativeHeldBlock();
    }

    private void buildCreativeHeldBlock() {
        FaceBuilder data = new FaceBuilder(0);
        for (int face = 0; face < 6; face++) data.add(0, 0, 0, face);
        // The first-person cube uses the same outside-facing winding as the
        // held block meshes. Keep this separate from world chunk geometry.
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        heldCreativeBlockUVArray = new VertexArray(vertexCount, 2, 2);
        heldCreativeBlockUVArray.set(0, vertexCount, data.uv);
        VertexArray colors = new VertexArray(vertexCount, 4, 1);
        colors.set(0, vertexCount, data.c);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        vertices.setTexCoords(0, heldCreativeBlockUVArray, 1.0f / 256.0f, null);
        vertices.setColors(colors);
        int[] strips = new int[] { 4,4,4,4,4,4 };
        heldCreativeBlockMesh = new Mesh(vertices, new TriangleStripArray(0, strips), heldItemAppearance);
        heldCreativeBlockMesh.setScale(0.34f, 0.34f, 0.34f);
        heldCreativeBlockMesh.setTranslation(-0.17f, -0.17f, -0.17f);
        heldCreativeBlockMesh.setRenderingEnable(false);
        heldItemPitch.addChild(heldCreativeBlockMesh);
    }

    /** Build Indev's 1/16-thick, front/back and extruded first-person icon. */
    private ItemSpriteData makeItemSpriteMesh() {
        final int strips = 2 + 16 * 4;
        final int verticesCount = strips * 4;
        short[] p = new short[verticesCount * 3];
        short[] uv = new short[verticesCount * 2];
        int vertex = 0;
        // Indev emits the front and back icon quads first.
        vertex = itemSpriteFace(p, uv, vertex, 0,0,0, 1024,0,0, 1024,1024,0, 0,1024,0,
                255,0, 0,0, 0,0, 0,0);
        // Rewrite UVs after the geometry is built; temporary UV arguments above are ignored.
        vertex = itemSpriteFace(p, uv, vertex, 0,1024,-64, 1024,1024,-64,
                1024,0,-64, 0,0,-64, 0,0, 0,0, 0,0, 0,0);
        // Left/right edge strips and the top/bottom strips use the icon's edge pixels.
        for (int i = 0; i < 16; i++) {
            int y0 = i * 64, y1 = y0 + 64;
            vertex = itemSpriteFace(p, uv, vertex,
                    0,y0,-64, 0,y0,0, 0,y1,0, 0,y1,-64,
                    0,0,0,0,0,0,0,0);
        }
        for (int i = 0; i < 16; i++) {
            int y0 = i * 64, y1 = y0 + 64;
            vertex = itemSpriteFace(p, uv, vertex,
                    1024,y1,-64, 1024,y1,0, 1024,y0,0, 1024,y0,-64,
                    0,0,0,0,0,0,0,0);
        }
        for (int i = 0; i < 16; i++) {
            int x0 = i * 64, x1 = x0 + 64;
            vertex = itemSpriteFace(p, uv, vertex,
                    x0,0,0, x1,0,0, x1,0,-64, x0,0,-64,
                    0,0,0,0,0,0,0,0);
        }
        for (int i = 0; i < 16; i++) {
            int x0 = i * 64, x1 = x0 + 64;
            vertex = itemSpriteFace(p, uv, vertex,
                    x1,1024,0, x0,1024,0, x0,1024,-64, x1,1024,-64,
                    0,0,0,0,0,0,0,0);
        }
        itemSpriteTexCoordArray = new VertexArray(verticesCount, 2, 2);
        itemSpriteTexCoordArray.set(0, verticesCount, uv);
        VertexBuffer buffer = new VertexBuffer();
        VertexArray positionArray = new VertexArray(verticesCount, 3, 2);
        positionArray.set(0, verticesCount, p);
        buffer.setPositions(positionArray, 1.0f / 1024.0f, null);
        buffer.setTexCoords(0, itemSpriteTexCoordArray, 1.0f / 256.0f, null);
        int[] stripLengths = new int[strips];
        for (int i = 0; i < strips; i++) stripLengths[i] = 4;
        Mesh mesh = new Mesh(buffer, new TriangleStripArray(0, stripLengths), itemSpriteAppearance);
        mesh.setRenderingEnable(false);
        return new ItemSpriteData(mesh, itemSpriteTexCoordArray, uv);
    }

    /** Appends one quad in Indev's 1/1024 icon-model units. */
    private int itemSpriteFace(short[] positions, short[] texCoords, int vertex,
            int x0,int y0,int z0, int x1,int y1,int z1,
            int x2,int y2,int z2, int x3,int y3,int z3,
            int u0,int v0,int u1,int v1,int u2,int v2,int u3,int v3) {
        vertex = putItemSpriteVertex(positions, texCoords, vertex, x0,y0,z0,u0,v0);
        vertex = putItemSpriteVertex(positions, texCoords, vertex, x1,y1,z1,u1,v1);
        vertex = putItemSpriteVertex(positions, texCoords, vertex, x2,y2,z2,u2,v2);
        vertex = putItemSpriteVertex(positions, texCoords, vertex, x3,y3,z3,u3,v3);
        return vertex;
    }

    private int putItemSpriteVertex(short[] positions, short[] texCoords, int vertex,
            int x, int y, int z, int u, int v) {
        int p = vertex * 3, t = vertex * 2;
        positions[p] = (short)x;
        positions[p + 1] = (short)y;
        positions[p + 2] = (short)z;
        texCoords[t] = (short)u;
        texCoords[t + 1] = (short)v;
        return vertex + 1;
    }

    private void fillItemSpriteUV(int tileU, int tileV) {
        // Indev's front quad maps right-to-left. Its 16 side strips sample the
        // exact border row/column of the 16x16 icon, extruding those pixels.
        int at = 0;
        at = writeSpriteUV(at, tileU + 16,tileV + 16, tileU,tileV + 16,
                tileU,tileV, tileU + 16,tileV);
        at = writeSpriteUV(at, tileU + 16,tileV, tileU,tileV,
                tileU,tileV + 16, tileU + 16,tileV + 16);
        for (int i = 0; i < 16; i++) {
            int v0 = tileV + 16 - i, v1 = v0 - 1;
            at = writeSpriteUV(at, tileU + 16,v0, tileU + 16,v0,
                    tileU + 16,v1, tileU + 16,v1);
        }
        for (int i = 0; i < 16; i++) {
            int vAtY1 = tileV + 15 - i, vAtY0 = vAtY1 + 1;
            at = writeSpriteUV(at, tileU,vAtY1, tileU,vAtY1,
                    tileU,vAtY0, tileU,vAtY0);
        }
        for (int i = 0; i < 16; i++) {
            int uAtX0 = tileU + 16 - i, uAtX1 = uAtX0 - 1;
            at = writeSpriteUV(at, uAtX0,tileV + 16, uAtX1,tileV + 16,
                    uAtX1,tileV + 16, uAtX0,tileV + 16);
        }
        for (int i = 0; i < 16; i++) {
            int uAtX0 = tileU + 16 - i, uAtX1 = uAtX0 - 1;
            at = writeSpriteUV(at, uAtX1,tileV, uAtX0,tileV,
                    uAtX0,tileV, uAtX1,tileV);
        }
    }

    private int writeSpriteUV(int at, int u0,int v0, int u1,int v1,
            int u2,int v2, int u3,int v3) {
        itemSpriteUV[at++] = (short)u0; itemSpriteUV[at++] = (short)v0;
        itemSpriteUV[at++] = (short)u1; itemSpriteUV[at++] = (short)v1;
        itemSpriteUV[at++] = (short)u2; itemSpriteUV[at++] = (short)v2;
        itemSpriteUV[at++] = (short)u3; itemSpriteUV[at++] = (short)v3;
        return at;
    }

    private static final class ItemSpriteData {
        final Mesh mesh;
        final VertexArray texCoords;
        final short[] uv;
        ItemSpriteData(Mesh mesh, VertexArray texCoords, short[] uv) {
            this.mesh = mesh; this.texCoords = texCoords; this.uv = uv;
        }
    }

    private void setMeshesVisible(Mesh[] meshes, boolean visible) {
        for (int i = 0; i < meshes.length; i++) meshes[i].setRenderingEnable(visible);
    }

    private Appearance createHandAppearance() {
        Appearance appearance = new Appearance();
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_BACK);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setDepthTestEnable(false);
        compositing.setDepthWriteEnable(false);
        compositing.setAlphaThreshold(0.5f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    void setPlayerSkinIndex(int index) {
        if (index < 0 || index > 4) index = 0;
        Image image;
        try { image = GameAssets.image("skin" + (index + 1) + ".png"); }
        catch (IOException missingSkin) {
            try { image = GameAssets.image("char.png"); }
            catch (IOException missingDefault) { return; }
        }
        setPlayerSkin(image);
    }

    void setPlayerSkin(Image image) {
        if (image == null) return;
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        handAppearance.setTexture(0, texture);
        if (playerModel != null) {
            playerModel.detachCamera(externalCameraPivot);
            scene.removeChild(playerModel.root);
        }
        playerModel = new PlayerModel(image);
        playerModel.attachCamera(externalCameraPivot);
        scene.addChild(playerModel.root);
        playerModel.root.setRenderingEnable(cameraMode != 0);
        hasPreviousPlayerPosition = false;
        if (handTexCoords != null) {
            int vScale = image.getWidth() / image.getHeight();
            if (vScale < 1) vScale = 1;
            handTexCoords.set(0, 24, makeHandUV(vScale));
        }
    }


    /** Extract the ten classic destroy-stage tiles from the bottom-left atlas strip. */
    private void makeCrackFrames(Image source) {
        int[] pixels = new int[256];
        int[] sourcePixels = new int[256];
        for (int frame = 0; frame < 10; frame++) {
            source.getRGB(sourcePixels, 0, 16, frame * 16, 240, 16, 16);
            for (int i = 0; i < 256; i++) {
                int color = sourcePixels[i];
                int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
                int distance = Math.abs(red - 127) + Math.abs(green - 127) + Math.abs(blue - 127);
                pixels[i] = (color & 0x00FFFFFF) | ((distance <= 18 ? 0 : 255) << 24);
            }
            Image frameImage = Image.createRGBImage(pixels, 16, 16, true);
            crackFrames[frame] = new Texture2D(new Image2D(Image2D.RGBA, frameImage));
            crackFrames[frame].setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
            crackFrames[frame].setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        }
    }

    /** Find the resident chunk that owns a world-space mining target. */
    private int findChunkSlot(int x, int y, int z) {
        int cx = floorDiv(x, VoxelWorld.CHUNK_XZ);
        int cy = floorDiv(y, VoxelWorld.CHUNK_Y);
        int cz = floorDiv(z, VoxelWorld.CHUNK_XZ);
        for (int i = 0; i < chunks.length; i++) {
            if (assignedChunks[i] && chunkWorldX[i] == cx &&
                    chunkWorldY[i] == cy && chunkWorldZ[i] == cz) return i;
        }
        return -1;
    }

    private void ensureChunkCrackMesh(Chunk chunk) {
        if (chunk.crackMesh != null) return;
        chunk.crackPositionData = new short[] { 0,0,0, 0,1,0, 1,0,0, 1,1,0 };
        chunk.crackPositions = new VertexArray(4, 3, 2);
        chunk.crackPositions.set(0, 4, chunk.crackPositionData);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(chunk.crackPositions, 1.0f, null);
        buffer.setTexCoords(0, crackUV, 1.0f / 16.0f, null);
        chunk.crackMesh = new Mesh(buffer, new TriangleStripArray(0, new int[] { 4 }), crackAppearance);
        chunk.crackMesh.setRenderingEnable(false);
        scene.addChild(chunk.crackMesh);
    }

    /** Mining overlay belongs to the resident chunk, not to a global world-space mesh. */
    void setMiningCracks(int x, int y, int z, int faceX, int faceY, int faceZ, int stage) {
        if (activeCrackChunkSlot >= 0 && activeCrackChunkSlot < chunks.length &&
                chunks[activeCrackChunkSlot] != null &&
                chunks[activeCrackChunkSlot].crackMesh != null)
            chunks[activeCrackChunkSlot].crackMesh.setRenderingEnable(false);
        activeCrackChunkSlot = -1;
        activeCrackX = activeCrackY = activeCrackZ = -1;
        activeCrackStage = -1;
        if (x < 0 || stage < 0) return;
        int slot = findChunkSlot(x, y, z);
        if (slot < 0 || chunks[slot] == null) return;
        Chunk chunk = chunks[slot];
        ensureChunkCrackMesh(chunk);
        if (stage > 9) stage = 9;
        crackAppearance.setTexture(0, crackFrames[stage]);
        short[] p = chunk.crackPositionData;
        float offset = 0.01f;
        if (Math.abs(faceY) >= Math.abs(faceX) && Math.abs(faceY) >= Math.abs(faceZ)) {
            p[0]=0; p[1]=0; p[2]=0; p[3]=0; p[4]=0; p[5]=1;
            p[6]=1; p[7]=0; p[8]=0; p[9]=1; p[10]=0; p[11]=1;
            chunk.crackMesh.setTranslation(x, y + (faceY > 0 ? 1.0f + offset : -offset), z);
        } else if (Math.abs(faceX) >= Math.abs(faceZ)) {
            p[0]=0; p[1]=0; p[2]=0; p[3]=0; p[4]=1; p[5]=0;
            p[6]=0; p[7]=1; p[8]=0; p[9]=0; p[10]=1; p[11]=1;
            chunk.crackMesh.setTranslation(x + (faceX > 0 ? 1.0f + offset : -offset), y, z);
        } else {
            p[0]=0; p[1]=0; p[2]=0; p[3]=0; p[4]=1; p[5]=0;
            p[6]=1; p[7]=0; p[8]=0; p[9]=1; p[10]=1; p[11]=0;
            chunk.crackMesh.setTranslation(x, y, z + (faceZ > 0 ? 1.0f + offset : -offset));
        }
        chunk.crackPositions.set(0, 4, p);
        chunk.crackMesh.setRenderingEnable(true);
        activeCrackChunkSlot = slot;
        activeCrackX = x; activeCrackY = y; activeCrackZ = z;
        activeCrackFaceX = faceX; activeCrackFaceY = faceY; activeCrackFaceZ = faceZ;
        activeCrackStage = stage;
    }

    /** The loop timer is stored by chunk, while the visible first-person arm
     * samples only the currently mined chunk's timer. */
    void setHandLooping(int x, int y, int z, boolean looping) {
        if (playerModel != null) playerModel.setActionLooping(looping);
        // The global first-person arm must never retain a mining loop after the
        // target is released or its chunk leaves the streaming cache.
        handLooping = looping;
        int slot = x < 0 ? -1 : findChunkSlot(x, y, z);
        if (activeMiningChunkSlot >= 0 && activeMiningChunkSlot < chunks.length &&
                chunks[activeMiningChunkSlot] != null && activeMiningChunkSlot != slot)
            chunks[activeMiningChunkSlot].handLooping = false;
        if (slot >= 0 && chunks[slot] != null) {
            Chunk chunk = chunks[slot];
            chunk.handLooping = looping;
            if (looping && !chunk.handSwinging) {
                chunk.handSwingTicks = 0;
                chunk.handSwinging = true;
            }
            activeMiningChunkSlot = slot;
        } else if (!looping) {
            activeMiningChunkSlot = -1;
            handLooping = false;
        }
    }

    private Appearance createFirstPersonAppearance(Texture2D texture,
            boolean alphaBlending, boolean cullBackFaces, boolean vertexColors) {
        Appearance appearance = new Appearance();
        if (texture != null) appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        if (vertexColors) material.setVertexColorTrackingEnable(true);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(cullBackFaces ? PolygonMode.CULL_BACK : PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setDepthTestEnable(false);
        compositing.setDepthWriteEnable(false);
        if (alphaBlending) {
            // The atlas uses binary transparent pixels. The threshold also
            // cuts those pixels out on M3G implementations with weak blending.
            compositing.setAlphaThreshold(0.5f);
            compositing.setBlending(CompositingMode.ALPHA);
        }
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    /** Indev ModelBiped right arm, transformed into first-person local space. */
    private Mesh makeHandMesh() {
        short[] p = new short[] {
            -3,-2,2, -3,10,2, 1,-2,2, 1,10,2,
            1,-2,-2, 1,10,-2, -3,-2,-2, -3,10,-2,
            -3,-2,-2, -3,10,-2, -3,-2,2, -3,10,2,
            1,-2,2, 1,10,2, 1,-2,-2, 1,10,-2,
            -3,10,2, -3,10,-2, 1,10,2, 1,10,-2,
            -3,-2,-2, -3,-2,2, 1,-2,-2, 1,-2,2
        };
        // ModelBiped's arm quads arrive in the opposite order to M3G's
        // triangle-strip front-face convention. Reverse each quad so its
        // two triangles point out of the arm rather than into it.
        short[] uv = makeHandUV(2);
        reverseQuadWinding(p, 6);
        int[] strips = new int[] { 4,4,4,4,4,4 };
        VertexArray positions = new VertexArray(24, 3, 2);
        positions.set(0, 24, p);
        handTexCoords = new VertexArray(24, 2, 2);
        handTexCoords.set(0, 24, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        vertices.setTexCoords(0, handTexCoords, 1.0f / 64.0f, null);
        TriangleStripArray triangles = new TriangleStripArray(0, strips);
        return new Mesh(vertices, triangles, handAppearance);
    }

    private short[] makeHandUV(int vScale) {
        short[] uv = new short[48];
        // Classic ModelBiped/ModelBox right arm: UV origin (40,16), 4x12x4.
        putHandFaceUV(uv, 0, 44,20,48,32, false);
        putHandFaceUV(uv, 4, 52,20,56,32, true);
        putHandFaceUV(uv, 8, 40,20,44,32, false);
        putHandFaceUV(uv, 12, 48,20,52,32, false);
        putHandFaceUV(uv, 16, 48,16,52,20, false);
        putHandFaceUV(uv, 20, 44,16,48,20, false);
        for (int i = 0; i < 24; i++) uv[i * 2 + 1] = (short)(uv[i * 2 + 1] * vScale);
        for (int face = 0; face < 6; face++) {
            swapShortVertex(uv, face * 4 + 1, face * 4 + 2, 2);
        }
        return uv;
    }

    private void putHandFaceUV(short[] uv, int firstVertex,
            int u0, int v0, int u1, int v1, boolean reverse) {
        int[] coordinates = reverse
                ? new int[] { u1,v1, u1,v0, u0,v1, u0,v0 }
                : new int[] { u0,v1, u0,v0, u1,v1, u1,v0 };
        for (int i = 0; i < 4; i++) {
            uv[(firstVertex + i) * 2] = (short)coordinates[i * 2];
            uv[(firstVertex + i) * 2 + 1] =
                    (short)(v0 + v1 - coordinates[i * 2 + 1]);
        }
    }

    void spawnBlockDestroy(int x, int y, int z, int tile) {
        particles.spawnBlockDestroy(x, y, z, tile);
    }

    void updateParticles(VoxelWorld world, float yaw, float pitch) {
        particles.update(world, yaw, pitch);
    }

    void spawnDroppedItem(int itemId, int x, int y, int z) {
        droppedItems.spawn(itemId, x, y, z);
    }

    boolean spawnPrimedTnt(int x, int y, int z, int fuse) {
        return droppedItems.spawnPrimedTnt(x, y, z, fuse);
    }

    int updateDroppedItems(VoxelWorld world, int[] inventory, PlayerPhysics physics) {
        int picked = droppedItems.update(world, inventory, physics);
        if (droppedItems.consumeExplosion()) {
            mobs.damageFromExplosion(world, droppedItems.explosionX(),
                    droppedItems.explosionY(), droppedItems.explosionZ(), 4.0f);
            particles.spawnBlockDestroy(droppedItems.explosionX(), droppedItems.explosionY(),
                    droppedItems.explosionZ(), Block.atlasTile(Block.TNT));
        }
        return picked;
    }

    int updateMobs(VoxelWorld world) {
        return mobs.update(world);
    }

    boolean spawnMobFromEgg(VoxelWorld world, int eggType, float x, float y, float z) {
        return mobs.spawnFromEgg(world, eggType, x, y, z);
    }

    /** Applies Indev's 24000-tick celestial curve; torch light is baked into voxel face colors. */
    void updateDayNight(VoxelWorld world) {
        if (world.nether) {
            currentDaylight = 0.32f;
            currentSkyColor = 0xFF4A1717;
            terrainFog.setColor(currentSkyColor);
            sunlight.setIntensity(0.45f);
            updateTorchLights(world);
            return;
        }
        float angle = ((float)world.dayTime / (float)VoxelWorld.DAY_LENGTH - 0.15f) *
                      6.2831855f;
        float sun = (float)Math.cos(angle) * 1.5f + 0.5f;
        if (sun < 0.0f) sun = 0.0f;
        if (sun > 1.0f) sun = 1.0f;
        currentDaylight = sun;
        // Keep moonlight visible on small, low-brightness phone displays.
        sunlight.setIntensity(0.28f + sun * 0.72f);
        currentSkyColor = blendColor(0xFF26354A, 0xFF82B8E8, sun);
        terrainFog.setColor(currentSkyColor);
        updateTorchLights(world);
    }

    private int blendColor(int night, int day, float amount) {
        int r = (night >> 16) & 255;
        int g = (night >> 8) & 255;
        int b = night & 255;
        r += (int)(((day >> 16) & 255) - r) * amount;
        g += (int)(((day >> 8) & 255) - g) * amount;
        b += (int)((day & 255) - b) * amount;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void updateTorchLights(VoxelWorld world) {
        // No M3G point lights: nearby blocks receive discrete brightness levels
        // in torchLightAt() while their chunk meshes are built.
        for (int i = 0; i < torchLights.length; i++) torchLights[i].setIntensity(0.0f);
    }

    int hitMob(VoxelWorld world, float pitch, int damage) {
        return mobs.hit(world, pitch, damage);
    }

    boolean shootPlayerArrow(VoxelWorld world, float pitch) {
        return mobs.shootPlayerArrow(world, pitch);
    }

    /**
     * Only the spawn chunk and its face-neighbor terrain data are required
     * before play. Neighbor meshes and diagonal chunks stream in afterwards.
     */
    boolean isInitialNeighborhoodReady(VoxelWorld world) {
        int centerX = (int)Math.floor(world.playerX / (float)VoxelWorld.CHUNK_XZ);
        int centerZ = (int)Math.floor(world.playerZ / (float)VoxelWorld.CHUNK_XZ);
        if (!world.isProceduralChunkReady(centerX, centerZ) ||
            !world.isProceduralChunkReady(centerX - 1, centerZ) ||
            !world.isProceduralChunkReady(centerX + 1, centerZ) ||
            !world.isProceduralChunkReady(centerX, centerZ - 1) ||
            !world.isProceduralChunkReady(centerX, centerZ + 1)) return false;
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (!assignedChunks[i] || chunkWorldX[i] != centerX ||
                chunkWorldZ[i] != centerZ) continue;
            if (chunks[i] != null && !dirtyChunks[i]) return true;
        }
        return false;
    }

    void sync(VoxelWorld world) {
        if (world.revision != builtRevision || world.fullRebuild) {
            // changedX/Y/Z stores only the latest edit. If several voxels changed
            // between renderer syncs (doors, support cascades, fluid spread), a
            // single-coordinate invalidation would leave stale meshes behind.
            if (builtRevision < 0 || world.fullRebuild ||
                    world.revision != builtRevision + 1) {
                rebuildWorld(world);
                prioritizeChangedChunk(world);
            } else {
                // Drop the previous cached target before invalidating meshes.
                // The edited chunk must be selected again even if the old target
                // was a different chunk that finished between two paint calls.
                pendingTargetSlot = -1;
                rebuildChanged(world);
            }
            builtRevision = world.revision;
            pendingViewKeyValid = false;
        }
        builtNether = world.nether;
        hasBuiltDimension = true;
        // Rail meshes also have a camera-centered streaming window, so they
        // must be refreshed when the player crosses chunk boundaries even if
        // no block in the world changed.
        railSystem.update(world);
        world.fullRebuild = false;
        updateVisibleChunks(world);
    }

    /** Ensure the latest changed coordinate wins even after a multi-edit revision. */
    private void prioritizeChangedChunk(VoxelWorld world) {
        int cx = floorDiv(world.changedX, VoxelWorld.CHUNK_XZ);
        int cy = world.changedY / VoxelWorld.CHUNK_Y;
        int cz = floorDiv(world.changedZ, VoxelWorld.CHUNK_XZ);
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (!assignedChunks[i] || chunkWorldX[i] != cx ||
                    chunkWorldY[i] != cy || chunkWorldZ[i] != cz) continue;
            dirtyChunks[i] = true;
            pendingTargetSlot = i;
            pendingTargetX = cx;
            pendingTargetY = cy;
            pendingTargetZ = cz;
            return;
        }
    }

    private void rebuildChanged(VoxelWorld world) {
        int x = world.changedX, y = world.changedY, z = world.changedZ;
        int cx = floorDiv(x, VoxelWorld.CHUNK_XZ);
        int cy = y / VoxelWorld.CHUNK_Y;
        int cz = floorDiv(z, VoxelWorld.CHUNK_XZ);
        int lx = x - cx * VoxelWorld.CHUNK_XZ;
        int ly = y % VoxelWorld.CHUNK_Y;
        int lz = z - cz * VoxelWorld.CHUNK_XZ;
        boolean torchChanged = world.changedOldId == Block.TORCH ||
                world.getLoaded(x, y, z) == Block.TORCH;

        // Keep an in-progress mesh job when this edit is elsewhere. Throwing
        // away a partially meshed visible chunk on every world revision caused
        // the renderer to repeat expensive M3G work while the player was moving.
        if (pendingBuild != null && pendingBuildSlot >= 0) {
            boolean affectsPending;
            if (torchChanged) {
                int bx0 = pendingBuild.x * VoxelWorld.CHUNK_XZ;
                int by0 = pendingBuild.y * VoxelWorld.CHUNK_Y;
                int bz0 = pendingBuild.z * VoxelWorld.CHUNK_XZ;
                affectsPending = x >= bx0 - TORCH_LIGHT_RADIUS &&
                        x < bx0 + VoxelWorld.CHUNK_XZ + TORCH_LIGHT_RADIUS &&
                        y >= by0 - TORCH_LIGHT_RADIUS &&
                        y < by0 + VoxelWorld.CHUNK_Y + TORCH_LIGHT_RADIUS &&
                        z >= bz0 - TORCH_LIGHT_RADIUS &&
                        z < bz0 + VoxelWorld.CHUNK_XZ + TORCH_LIGHT_RADIUS;
            } else {
                affectsPending =
                        Math.abs(pendingBuild.x - cx) <=
                                (lx == 0 || lx == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0) &&
                        Math.abs(pendingBuild.y - cy) <=
                                (ly == 0 || ly == VoxelWorld.CHUNK_Y - 1 ? 1 : 0) &&
                        Math.abs(pendingBuild.z - cz) <=
                                (lz == 0 || lz == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0);
            }
            if (affectsPending) {
                // Discard the stale in-progress build and rebuild this chunk
                // once from the current world state.
                dirtyChunks[pendingBuildSlot] = true;
                pendingBuild = null;
                pendingBuildSlot = -1;
            }
        }

        for (int i = 0; i < CACHE_SIZE; i++) {
            // Include a chunk whose first mesh is still being built. Such a
            // slot already has world coordinates but is not resident yet.
            if (!assignedChunks[i]) continue;
            if (torchChanged) {
                int bx0 = chunkWorldX[i] * VoxelWorld.CHUNK_XZ;
                int by0 = chunkWorldY[i] * VoxelWorld.CHUNK_Y;
                int bz0 = chunkWorldZ[i] * VoxelWorld.CHUNK_XZ;
                if (x < bx0 - TORCH_LIGHT_RADIUS || x >= bx0 + VoxelWorld.CHUNK_XZ + TORCH_LIGHT_RADIUS ||
                    y < by0 - TORCH_LIGHT_RADIUS || y >= by0 + VoxelWorld.CHUNK_Y + TORCH_LIGHT_RADIUS ||
                    z < bz0 - TORCH_LIGHT_RADIUS || z >= bz0 + VoxelWorld.CHUNK_XZ + TORCH_LIGHT_RADIUS)
                    continue;
                // The chunk is rebuilt as a whole, so one light-source edit
                // invalidates this chunk without tracking individual slices.
                dirtyChunks[i] = true;
                if (chunkWorldX[i] == cx && chunkWorldY[i] == cy &&
                        chunkWorldZ[i] == cz) pendingTargetSlot = i;
                continue;
            }
            if (Math.abs(chunkWorldX[i] - cx) > (lx == 0 || lx == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0) ||
                Math.abs(chunkWorldY[i] - cy) > (ly == 0 || ly == VoxelWorld.CHUNK_Y - 1 ? 1 : 0) ||
                Math.abs(chunkWorldZ[i] - cz) > (lz == 0 || lz == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0)) continue;
            // Rebuild the full chunk; horizontal boundary edits also invalidate
            // the adjacent chunk whose shared face may have changed.
            dirtyChunks[i] = true;
            // Prioritize the exact edited chunk. Otherwise a nearby dirty
            // neighbor can win the distance scan and delay the visible edit.
            if (chunkWorldX[i] == cx && chunkWorldY[i] == cy &&
                    chunkWorldZ[i] == cz) pendingTargetSlot = i;
        }
    }


    private int floorDiv(int value, int divisor) {
        int result = value / divisor;
        return value < 0 && value % divisor != 0 ? result - 1 : result;
    }

    /** Stable cache coordinate: moving the player must not reshuffle resident chunks. */
    private int floorMod(int value, int divisor) {
        int result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private void rebuildWorld(VoxelWorld world) {
        // A dimension swap changes every voxel. Old-dimension meshes must be
        // removed immediately; ordinary full invalidations can be rebuilt in
        // slices while keeping their previous meshes visible.
        if (hasBuiltDimension && builtNether != world.nether) {
            pendingBuild = null;
            pendingBuildSlot = -1;
            for (int i = 0; i < chunks.length; i++) removeChunk(i);
            return;
        }
        if (pendingBuild != null && pendingBuildSlot >= 0)
            dirtyChunks[pendingBuildSlot] = true;
        pendingBuild = null;
        pendingBuildSlot = -1;
        for (int i = 0; i < chunks.length; i++) {
            if (!assignedChunks[i]) continue;
            dirtyChunks[i] = true;
        }
    }

    private void removeChunk(int i) {
        if (pendingBuildSlot == i) { pendingBuild = null; pendingBuildSlot = -1; }
        if (chunks[i] != null) {
            if (chunks[i].meshes != null) {
                for (int m = 0; m < chunks[i].meshes.length; m++)
                    terrain.removeChild(chunks[i].meshes[m]);
            }
            if (chunks[i].crackMesh != null) scene.removeChild(chunks[i].crackMesh);
            chunks[i] = null;
        }
        if (activeMiningChunkSlot == i) {
            activeMiningChunkSlot = -1;
            handLooping = false;
            handSwinging = false;
            handSwingTicks = 8;
        }
        if (activeCrackChunkSlot == i) activeCrackChunkSlot = -1;
        assignedChunks[i] = false;
        dirtyChunks[i] = false;
        chunkWorldX[i] = Integer.MIN_VALUE;
        chunkWorldY[i] = Integer.MIN_VALUE;
        chunkWorldZ[i] = Integer.MIN_VALUE;
    }

    /** Keep M3G buffers only for chunks intersecting the camera frustum. */
    private void updateVisibleChunks(VoxelWorld world) {
        float eyeX = world.playerX, eyeY = world.playerY + 1.62f, eyeZ = world.playerZ;
        int centerX = floorDiv((int)eyeX, VoxelWorld.CHUNK_XZ);
        int centerZ = floorDiv((int)eyeZ, VoxelWorld.CHUNK_XZ);
        // Camera/player motion is continuous, but the stream window only needs
        // refreshing after a meaningful change. The old exact-float key forced
        // a full multi-hundred-slot scan on virtually every paint while walking.
        float yawDelta = Math.abs(world.yaw - pendingViewYaw);
        if (yawDelta > 180) yawDelta = 360.0f - yawDelta;
        boolean samePendingView = pendingViewKeyValid &&
                centerX == pendingViewCenterX && centerZ == pendingViewCenterZ &&
                // The stream window is chunk-based, not block-based. Re-scan
                // only after a meaningful camera movement; the 12-block frustum
                // padding keeps edge chunks resident during this hysteresis.
                Math.abs(eyeX - pendingViewX) < 4.0f &&
                Math.abs(eyeY - pendingViewY) < 4.0f &&
                Math.abs(eyeZ - pendingViewZ) < 4.0f &&
                yawDelta < 6 && Math.abs(cameraPitch - pendingViewPitch) < 6.0f &&
                cameraFar == pendingViewFar && shortestDistance == pendingViewShortest &&
                streamRadius == pendingViewRadius;
        if (samePendingView) {
            // Resume the current build, or retry the selected dirty chunk when its
            // procedural neighbors were not ready on the previous paint.
            if (pendingBuild != null && pendingBuildSlot >= 0 &&
                    assignedChunks[pendingBuildSlot] &&
                    chunkWorldX[pendingBuildSlot] == pendingBuild.x &&
                    chunkWorldY[pendingBuildSlot] == pendingBuild.y &&
                    chunkWorldZ[pendingBuildSlot] == pendingBuild.z) {
                advanceChunkBuild(world, pendingBuildSlot,
                        pendingBuild.x, pendingBuild.y, pendingBuild.z);
            } else if (pendingTargetSlot >= 0) {
                if (assignedChunks[pendingTargetSlot] &&
                        dirtyChunks[pendingTargetSlot] &&
                        chunkWorldX[pendingTargetSlot] == pendingTargetX &&
                        chunkWorldY[pendingTargetSlot] == pendingTargetY &&
                        chunkWorldZ[pendingTargetSlot] == pendingTargetZ) {
                    advanceChunkBuild(world, pendingTargetSlot,
                            pendingTargetX, pendingTargetY, pendingTargetZ);
                    return;
                }
                // The previous target finished or left the window. Select the
                // next dirty chunk with one fresh scan instead of stalling.
                pendingTargetSlot = -1;
                pendingViewKeyValid = false;
            } else {
                return;
            }
            if (pendingViewKeyValid) return;
        }
        int span = MAX_STREAM_SPAN;

        // Shortest uses a circular horizontal radius inside the candidate
        // window. The cache ring remains a slot-addressing mechanism only.
        float forwardX = 0.0f, forwardY = 0.0f, forwardZ = 1.0f;
        float rightX = 1.0f, rightZ = 0.0f;
        float upX = 0.0f, upY = 1.0f, upZ = 0.0f;
        {
            if (world.yaw != basisYaw || cameraPitch != basisPitch) {
                float yaw = (float)(world.yaw * Math.PI / 180.0);
                float pitch = (float)(cameraPitch * Math.PI / 180.0);
                float sinYaw = (float)Math.sin(yaw);
                float cosYaw = (float)Math.cos(yaw);
                float sinPitch = (float)Math.sin(pitch);
                float cosPitch = (float)Math.cos(pitch);
                basisForwardX = sinYaw * cosPitch;
                basisForwardY = sinPitch;
                basisForwardZ = cosYaw * cosPitch;
                basisRightX = cosYaw;
                basisRightZ = -sinYaw;
                basisUpX = -sinYaw * sinPitch;
                basisUpY = cosPitch;
                basisUpZ = -cosYaw * sinPitch;
                basisYaw = world.yaw;
                basisPitch = cameraPitch;
            }
            forwardX = basisForwardX; forwardY = basisForwardY; forwardZ = basisForwardZ;
            rightX = basisRightX; rightZ = basisRightZ;
            upX = basisUpX; upY = basisUpY; upZ = basisUpZ;
        }

        // A chunk column can intersect the 24-block circle only when its
        // coordinate differs by at most ceil(radius / chunkSize) from the
        // player's chunk. The AABB/circle test below then rejects corner chunks.
        // Do not add another ring: it needlessly scans and retains extra chunks.
        int shortestChunkRadius = shortestDistance ?
                (int)Math.ceil(cameraFar / (float)VoxelWorld.CHUNK_XZ) : streamRadius;
        int firstChunkX = centerX - shortestChunkRadius;
        int lastChunkX = centerX + shortestChunkRadius;
        int firstChunkZ = centerZ - shortestChunkRadius;
        int lastChunkZ = centerZ + shortestChunkRadius;
        // Old worlds have a hard 240x240 border (15x15 chunks). Clamp the
        // candidate rectangle before scanning or assigning cache slots: the
        // infinite-world path still streams freely, while finite worlds avoid
        // visiting impossible coordinates and never reserve edge slots outside
        // their playable area. The 15-slot ring maps every old-world chunk
        // coordinate uniquely, so no extra modulo/collision handling is needed.
        if (world.worldSize == VoxelWorld.SIZE_OLD) {
            int lastWorldChunk = 240 / VoxelWorld.CHUNK_XZ - 1;
            if (firstChunkX < 0) firstChunkX = 0;
            if (firstChunkZ < 0) firstChunkZ = 0;
            if (lastChunkX > lastWorldChunk) lastChunkX = lastWorldChunk;
            if (lastChunkZ > lastWorldChunk) lastChunkZ = lastWorldChunk;
        }

        // Evict only chunks that have actually left the horizontal stream window.
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (assignedChunks[i] &&
                    (chunkWorldX[i] < firstChunkX || chunkWorldX[i] > lastChunkX ||
                     chunkWorldZ[i] < firstChunkZ || chunkWorldZ[i] > lastChunkZ)) removeChunk(i);
        }

        int firstRx = firstChunkX - centerX + MAX_STREAM_RADIUS;
        int lastRx = lastChunkX - centerX + MAX_STREAM_RADIUS;
        int firstRz = firstChunkZ - centerZ + MAX_STREAM_RADIUS;
        int lastRz = lastChunkZ - centerZ + MAX_STREAM_RADIUS;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        // Keep progressing the current mesh job while its chunk remains visible.
        // Re-selecting the nearest dirty chunk every frame can otherwise abandon
        // a partially meshed chunk whenever the player/camera moves slightly.
        boolean pendingVisible = false;

        // Search only the active window, then use the circle/AABB and
        // camera-frustum tests below to reject corner sectors before meshing.
        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int rz = firstRz; rz <= lastRz; rz++) {
                for (int rx = firstRx; rx <= lastRx; rx++) {
                    int cx = centerX + rx - MAX_STREAM_RADIUS;
                    int cz = centerZ + rz - MAX_STREAM_RADIUS;
                    // Finite Old coordinates are already 0..14, so their
                    // cache address is a direct row-major lookup. Infinite
                    // worlds retain the wrapping ring used by streamed chunks.
                    int i = world.worldSize == VoxelWorld.SIZE_OLD ?
                            (cy * span + cz) * span + cx :
                            (cy * span + floorMod(cz, span)) * span + floorMod(cx, span);
                    float dx = cx * VoxelWorld.CHUNK_XZ + VoxelWorld.CHUNK_XZ * 0.5f - eyeX;
                    float dy = cy * VoxelWorld.CHUNK_Y + VoxelWorld.CHUNK_Y * 0.5f - eyeY;
                    float dz = cz * VoxelWorld.CHUNK_XZ + VoxelWorld.CHUNK_XZ * 0.5f - eyeZ;

                    boolean visible = shortestDistance;
                    if (shortestDistance) {
                        // A horizontal cylinder gives the old circular render
                        // boundary. Keep 16x16 columns intact, then use the
                        // camera frustum to avoid building vertical slices that
                        // cannot contribute to the current view.
                        float nearX = Math.abs(dx) - VoxelWorld.CHUNK_XZ * 0.5f;
                        float nearZ = Math.abs(dz) - VoxelWorld.CHUNK_XZ * 0.5f;
                        if (nearX < 0.0f) nearX = 0.0f;
                        if (nearZ < 0.0f) nearZ = 0.0f;
                        boolean insideCircle = nearX * nearX + nearZ * nearZ <=
                                cameraFar * cameraFar;
                        float depth = dx * forwardX + dy * forwardY + dz * forwardZ;
                        float horizontal = dx * rightX + dz * rightZ;
                        float vertical = dx * upX + dy * upY + dz * upZ;
                        visible = insideCircle && depth + CHUNK_RADIUS >= 0.1f &&
                            depth - CHUNK_RADIUS <= cameraFar &&
                            Math.abs(horizontal) <= Math.max(0.0f, depth) * tanHorizontalFov + CHUNK_RADIUS &&
                            Math.abs(vertical) <= Math.max(0.0f, depth) * tanVerticalFov + CHUNK_RADIUS;
                    } else {
                        float depth = dx * forwardX + dy * forwardY + dz * forwardZ;
                        float horizontal = dx * rightX + dz * rightZ;
                        float vertical = dx * upX + dy * upY + dz * upZ;
                        visible = depth + CHUNK_RADIUS >= 0.1f &&
                            depth - CHUNK_RADIUS <= cameraFar &&
                            Math.abs(horizontal) <= Math.max(0.0f, depth) * tanHorizontalFov + CHUNK_RADIUS &&
                            Math.abs(vertical) <= Math.max(0.0f, depth) * tanVerticalFov + CHUNK_RADIUS;
                    }

                    boolean sameChunk = assignedChunks[i] && chunkWorldX[i] == cx &&
                        chunkWorldY[i] == cy && chunkWorldZ[i] == cz;
                    // A slot can own a partially meshed chunk before it becomes
                    // resident. Always detach that partial mesh before reusing
                    // the ring slot for a different world coordinate.
                    if (assignedChunks[i] && !sameChunk) removeChunk(i);
                    if (!visible) continue;
                    if (pendingBuild != null && pendingBuildSlot == i &&
                            pendingBuild.x == cx && pendingBuild.y == cy && pendingBuild.z == cz)
                        pendingVisible = true;

                    if (!sameChunk) {
                        chunkWorldX[i] = cx; chunkWorldY[i] = cy; chunkWorldZ[i] = cz;
                        assignedChunks[i] = true;
                        dirtyChunks[i] = true;
                    }
                    if (!sameChunk || dirtyChunks[i]) {
                        // Show the block edit before remeshing unrelated chunks.
                        float distance = i == pendingTargetSlot ? -1.0f :
                                dx * dx + dz * dz + dy * dy * 2.0f;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = i;
                        }
                    }
                }
            }
        }

        // Finish a visible in-progress job before switching to another chunk.
        // This is the streaming equivalent of Minicraft 3D's load-shedding:
        // avoid throwing away expensive partial work when frame priorities change.
        if (pendingVisible && pendingBuild != null) {
            int slot = pendingBuildSlot;
            advanceChunkBuild(world, slot, pendingBuild.x, pendingBuild.y, pendingBuild.z);
        } else if (best >= 0) {
            advanceChunkBuild(world, best, chunkWorldX[best], chunkWorldY[best], chunkWorldZ[best]);
        }
        pendingViewCenterX = centerX; pendingViewCenterZ = centerZ;
        pendingViewX = eyeX; pendingViewY = eyeY; pendingViewZ = eyeZ;
        pendingViewYaw = world.yaw; pendingViewPitch = cameraPitch;
        pendingViewFar = cameraFar; pendingViewShortest = shortestDistance;
        pendingViewRadius = streamRadius;
        if (best >= 0 && dirtyChunks[best]) {
            pendingTargetSlot = best;
            pendingTargetX = chunkWorldX[best];
            pendingTargetY = chunkWorldY[best];
            pendingTargetZ = chunkWorldZ[best];
        } else if (pendingBuild == null) {
            pendingTargetSlot = -1;
        }
        // Keep the view cached while a dirty chunk waits for procedural neighbors;
        // retry the selected target without rescanning the stream window.
        pendingViewKeyValid = true;
    }

    private void advanceChunkBuild(VoxelWorld world, int slot, int cx, int cy, int cz) {
        // Do not bake exposed faces against procedural neighbors that have not
        // finished loading yet.
        world.requestProceduralChunk(cx, cz);
        if (!world.areProceduralChunkNeighborsReady(cx, cz)) return;

        if (pendingBuild == null || pendingBuildSlot != slot ||
            pendingBuild.x != cx || pendingBuild.y != cy || pendingBuild.z != cz) {
            chunkBuildStartedAt = System.currentTimeMillis();
            pendingBuild = new ChunkBuild(slot, cx, cy, cz);
            pendingBuildSlot = slot;
            resetChunkMeshAccumulators();
            // Take one compact snapshot of the chunk. The mesher reads this
            // snapshot directly, avoiding a separate cave flood-fill pass.
            long caveStartedAt = System.currentTimeMillis();
            for (int layer = 0; layer < VoxelWorld.CHUNK_Y; layer++) {
                world.copyLoadedChunkLayer(cx * VoxelWorld.CHUNK_XZ,
                        cy * VoxelWorld.CHUNK_Y + layer,
                        cz * VoxelWorld.CHUNK_XZ, chunkVoxelLayerScratch);
                System.arraycopy(chunkVoxelLayerScratch, 0, chunkVoxelScratch,
                        layer * CHUNK_AREA, CHUNK_AREA);
            }
            lastSnapshotBuildMillis = System.currentTimeMillis() - caveStartedAt;
            collectChunkTorches(world, cx * VoxelWorld.CHUNK_XZ,
                    cy * VoxelWorld.CHUNK_Y, cz * VoxelWorld.CHUNK_XZ,
                    (cx + 1) * VoxelWorld.CHUNK_XZ,
                    (cy + 1) * VoxelWorld.CHUNK_Y,
                    (cz + 1) * VoxelWorld.CHUNK_XZ);
        }
        ChunkBuild job = pendingBuild;
        // A chunk is one render unit: rebuild all of its vertical slices into
        // one replacement mesh, rather than tracking masks that were never used
        // to avoid the full mesh assembly.
        while (job.nextLayer < VoxelWorld.CHUNK_Y) {
            buildChunkLayer(world, cx, cy, cz, job.nextLayer++);
        }

        FireBuilder completedFireBuilder = buildingFires;
        long meshStartedAt = System.currentTimeMillis();
        Mesh[] meshes = finishChunkMeshes(cx, cy, cz);
        lastMeshAssemblyMillis = System.currentTimeMillis() - meshStartedAt;
        Chunk previousChunk = chunks[slot];
        // Remove the old scene nodes before replacing the mesh reference.
        // The resident wrapper is reused, so assigning complete.meshes first
        // would overwrite the only references to the old meshes and orphan
        // them in the terrain group permanently.
        if (previousChunk != null && previousChunk.meshes != null) {
            for (int i = 0; i < previousChunk.meshes.length; i++)
                terrain.removeChild(previousChunk.meshes[i]);
        }
        // Reuse the resident chunk wrapper on remesh instead of allocating a new
        // object for every block edit; crack and hand-animation state stays attached.
        Chunk complete = previousChunk == null ? new Chunk() : previousChunk;
        complete.meshes = meshes;
        complete.fires = completedFireBuilder == null ? null :
                new FireBuilder[] { completedFireBuilder };
        for (int i = 0; i < meshes.length; i++) terrain.addChild(meshes[i]);
        chunks[slot] = complete;
        chunkWorldX[slot] = cx; chunkWorldY[slot] = cy; chunkWorldZ[slot] = cz;
        dirtyChunks[slot] = false;
        completedChunkBuilds++;
        if (previousChunk != null) completedDirtyRebuilds++;
        lastChunkBuildMillis = System.currentTimeMillis() - chunkBuildStartedAt;
        pendingBuild = null;
        pendingBuildSlot = -1;
        // Crack overlay is a separate M3G mesh. Re-upload its vertex positions
        // after terrain replacement so both meshes stay visually synchronized.
        if (activeCrackChunkSlot == slot && activeCrackStage >= 0) {
            int crackX = activeCrackX, crackY = activeCrackY, crackZ = activeCrackZ;
            int faceX = activeCrackFaceX, faceY = activeCrackFaceY, faceZ = activeCrackFaceZ;
            int stage = activeCrackStage;
            setMiningCracks(crackX, crackY, crackZ, faceX, faceY, faceZ, stage);
        }
    }

    /** Last completed chunk timings, shown only while the pause menu is open. */
    String performanceStats() {
        return "B" + completedChunkBuilds + " R" + completedDirtyRebuilds +
                " T" + lastChunkBuildMillis + " S" + lastSnapshotBuildMillis +
                " M" + lastMeshAssemblyMillis + "ms";
    }

    /** Start clean per-chunk geometry accumulators before reading its first layer. */
    private void resetChunkMeshAccumulators() {
        activeFaceBuilderCount = 0;
        for (int i = 0; i < TILE_COUNT; i++) chunkFaceBuilders[i] = null;
        buildingFlowers = null;
        buildingFires = null;
        buildingPortals = null;
        reusableFlowerBuilder.reset();
        reusablePortalBuilder.reset();
    }

    /** Assemble one block Mesh with one submesh/material per atlas tile. */
    private Mesh[] finishChunkMeshes(int cx, int cy, int cz) {
        int count = activeFaceBuilderCount;
        int flowerCount = buildingFlowers == null ? 0 : 1;
        int fireCount = buildingFires == null ? 0 : 1;
        int portalCount = buildingPortals == null ? 0 : 1;
        if (count + flowerCount + fireCount + portalCount == 0) return new Mesh[0];

        Mesh[] meshes = new Mesh[(count > 0 ? 1 : 0) +
                flowerCount + fireCount + portalCount];
        int at = 0;
        if (count > 0) meshes[at++] = makeChunkBlockMesh();
        if (buildingFlowers != null)
            meshes[at++] = makeFlowerMesh(buildingFlowers, flowerAppearance);
        if (buildingFires != null) {
            buildingFires.setFrame(fireAnimationFrame);
            meshes[at++] = makeFireMesh(buildingFires, worldFireAppearance);
        }
        if (buildingPortals != null)
            meshes[at++] = makePortalMesh(buildingPortals);

        int x = cx * VoxelWorld.CHUNK_XZ;
        int y = cy * VoxelWorld.CHUNK_Y;
        int z = cz * VoxelWorld.CHUNK_XZ;
        for (int i = 0; i < meshes.length; i++) meshes[i].setTranslation(x, y, z);

        // Return material builders to the bounded pool only after their data
        // has been copied into the shared vertex buffer.
        for (int active = 0; active < activeFaceBuilderCount; active++) {
            int index = activeFaceBuilderIndices[active];
            FaceBuilder builder = chunkFaceBuilders[index];
            chunkFaceBuilders[index] = null;
            if (freeFaceBuilderCount < freeFaceBuilders.length &&
                    builder.p.length <= 1536) {
                builder.reset(0, 0);
                freeFaceBuilders[freeFaceBuilderCount++] = builder;
            }
        }
        buildingFlowers = null;
         chunkAirQueueScratch[tail++] = (short)index;
        }
        return tail;
    }

    private void buildChunkLayer(VoxelWorld world, int cx, int cy, int cz,
                                   int layer) {
        FaceBuilder[] builders = chunkFaceBuilders;
        int x0 = cx * VoxelWorld.CHUNK_XZ;
        int y0 = cy * VoxelWorld.CHUNK_Y + layer;
        int z0 = cz * VoxelWorld.CHUNK_XZ;
        int x1 = x0 + VoxelWorld.CHUNK_XZ;
        int y1 = y0 + 1;
        int z1 = z0 + VoxelWorld.CHUNK_XZ;

        // Torch sources are gathered once when this resumable chunk build starts.
        fillChunkLightSlice(x0, y0, z0);

        // Crossed flowers do not form cube faces, so collect them separately.
        // Empty vertical sections are common above the terrain: detect them in the
        // shared voxel snapshot and avoid allocating face builders/M3G meshes.
        int nonAirBlocks = 0;
        int voxelLayerOffset = layer * CHUNK_AREA;
        for (int z = 0; z < VoxelWorld.CHUNK_XZ; z++) {
            for (int x = 0; x < VoxelWorld.CHUNK_XZ; x++) {
                int localIndex = z * VoxelWorld.CHUNK_XZ + x;
                byte id = chunkVoxelScratch[voxelLayerOffset + localIndex];
                if (id != Block.AIR) nonAirBlocks++;
                chunkLightScratch[localIndex] = (byte)(id == Block.LAVA ? 14 :
                        (chunkLightScratch[localIndex] & 255));
                if (id == Block.PORTAL) {
                    if (buildingPortals == null) buildingPortals = reusablePortalBuilder;
                    buildingPortals.add(x, layer, z,
                            world.portalAxisAt(x0 + x, y0, z0 + z), portalFrameSize);
                } else if (id == Block.FIRE) {
                    if (buildingFires == null) buildingFires = new FireBuilder();
                    buildingFires.add(x, layer, z);
                } else if (Block.isFlower(id)) {
                    if (buildingFlowers == null) buildingFlowers = reusableFlowerBuilder;
                    buildingFlowers.add(x, layer, z, Block.sideTile(id));
                } else if (Block.isDoor(id)) {
                    if (buildingFlowers == null) buildingFlowers = reusableFlowerBuilder;
                    buildingFlowers.addDoor(x, layer, z, id,
                            world.doorMetadata(x0 + x, y0, z0 + z));
                }
            }
        }

        // Empty slices contribute no faces; keep the same accumulators for the
        // next vertical layer.
        if (nonAirBlocks == 0) return;

        // Mine64-style greedy meshing: combine adjacent visible faces on each
        // chunk plane when their atlas tile matches. Tile textures repeat, so
        // the merged rectangles preserve the original per-block texture scale.
        int[] mask = greedyMaskScratch;
        for (int face = 0; face < 6; face++) {
            int slices = face == 0 || face == 5 ? 1 : VoxelWorld.CHUNK_XZ;
            int width = VoxelWorld.CHUNK_XZ;
            int height = face == 0 || face == 5 ? VoxelWorld.CHUNK_XZ : 1;
            for (int slice = 0; slice < slices; slice++) {
                for (int b = 0; b < height; b++) {
                    int row = b * width;
                    for (int a = 0; a < width; a++) {
                        int x, y, z;
                        if (face == 0 || face == 5) {
                            x = x0 + a; y = y0 + slice; z = z0 + b;
                        } else if (face == 1 || face == 2) {
                            x = x0 + slice; y = y0 + b; z = z0 + a;
                        } else {
                            x = x0 + a; y = y0 + b; z = z0 + slice;
                        }
                        int voxelIndex = (((y - y0) * VoxelWorld.CHUNK_XZ) + (z - z0)) *
                                VoxelWorld.CHUNK_XZ + (x - x0);
                        byte id = chunkVoxelScratch[voxelLayerOffset + voxelIndex];
                        int key = 0;
                        if (id != Block.AIR && id != Block.RAIL && !Block.isFlower(id) && !Block.isDoor(id) &&
                            !(face == 5 && y == 0)) {
                            int nx = x, ny = y, nz = z;
                            if (face == 0) ny++;
                            else if (face == 1) nx--;
                            else if (face == 2) nx++;
                            else if (face == 3) nz--;
                            else if (face == 4) nz++;
                            else ny--;
                            byte neighbor;
                            if (face == 0 && y + 1 < y1)
                                neighbor = chunkVoxelScratch[voxelLayerOffset + voxelIndex + CHUNK_AREA];
                            else if (face == 1 && x > x0)
                                neighbor = chunkVoxelScratch[voxelLayerOffset + voxelIndex - 1];
                            else if (face == 2 && x + 1 < x1)
                                neighbor = chunkVoxelScratch[voxelLayerOffset + voxelIndex + 1];
                            else if (face == 3 && z > z0)
                                neighbor = chunkVoxelScratch[voxelLayerOffset + voxelIndex - VoxelWorld.CHUNK_XZ];
                            else if (face == 4 && z + 1 < z1)
                                neighbor = chunkVoxelScratch[voxelLayerOffset + voxelIndex + VoxelWorld.CHUNK_XZ];
                            else if (face == 5 && y > y0)
                                neighbor = chunkVoxelScratch[voxelLayerOffset + voxelIndex - CHUNK_AREA];
                            else neighbor = world.getLoaded(nx, ny, nz);
                            boolean faceVisible = Block.faceVisible(id, neighbor);
                            if (faceVisible) {
                                int tile = renderWorldTile(world, id, face, x, y, z,
                                        id == Block.WOOL ? world.woolColor(x, y, z) : 0);
                                int light = chunkLightScratch[voxelIndex] & 255;
                                key = light * TILE_COUNT + tile + 1;
                            }
                        }
                        mask[row + a] = key;
                    }
                }
                for (int b = 0; b < height; b++) {
                    for (int a = 0; a < width; ) {
                        int key = mask[b * width + a];
                        if (key == 0) { a++; continue; }
                        int rectWidth = 1;
                        while (a + rectWidth < width && mask[b * width + a + rectWidth] == key)
                            rectWidth++;
                        int rectHeight = 1;
                        boolean extend = true;
                        while (b + rectHeight < height && extend) {
                            int nextRow = (b + rectHeight) * width + a;
                            for (int k = 0; k < rectWidth; k++) {
                                if (mask[nextRow + k] != key) { extend = false; break; }
                            }
                            if (extend) rectHeight++;
                        }
                        for (int clearY = 0; clearY < rectHeight; clearY++) {
                            int clearRow = (b + clearY) * width + a;
                            for (int clearX = 0; clearX < rectWidth; clearX++) mask[clearRow + clearX] = 0;
                        }
                        int x, y, z, faceWidth, faceHeight;
                        if (face == 0 || face == 5) {
                            x = a; y = slice; z = b;
                            faceWidth = rectWidth; faceHeight = rectHeight;
                        } else if (face == 1 || face == 2) {
                            x = slice; y = b; z = a;
                            faceWidth = rectWidth; faceHeight = rectHeight;
                        } else {
                            x = a; y = b; z = slice;
                            faceWidth = rectWidth; faceHeight = rectHeight;
                        }
                        int encoded = key - 1;
                        int tile = encoded % TILE_COUNT;
                        int light = encoded / TILE_COUNT;
                        // Batch every light level of this atlas tile into one mesh;
                        // addRect stores the correct brightness in its own vertices.
                        int builderIndex = tile;
                        if (builders[builderIndex] == null) {
                            FaceBuilder builder = freeFaceBuilderCount > 0 ?
                                    freeFaceBuilders[--freeFaceBuilderCount] : null;
                            if (builder == null) builder = new FaceBuilder(tile, light);
                            else builder.reset(tile, light);
                            builders[builderIndex] = builder;
                            activeFaceBuilderIndices[activeFaceBuilderCount++] = builderIndex;
                        }
                        builders[builderIndex].addRect(x, y + layer, z,
                                faceWidth, faceHeight, face, light);
                        a += rectWidth;
                    }
                }
            }
        }

        // Geometry remains in the shared per-material builders until all
        // vertical layers have been processed by advanceChunkBuild().
    }

    /** Apply an Alpha-era biome tint to the vertex colors of a held block mesh. */
    private void tintFaceBuilder(FaceBuilder builder, int tint) {
        int red = (tint >> 16) & 255;
        int green = (tint >> 8) & 255;
        int blue = tint & 255;
        for (int vertex = 0; vertex < builder.vertexCount; vertex++) {
            int color = vertex * 4;
            builder.c[color] = (byte)((builder.c[color] & 255) * red / 255);
            builder.c[color + 1] = (byte)((builder.c[color + 1] & 255) * green / 255);
            builder.c[color + 2] = (byte)((builder.c[color + 2] & 255) * blue / 255);
            // Keep the original vertex alpha (including water/transparency).
        }
    }

    private void add(FaceBuilder[] builders, int tile, int x, int y, int z, int face) {
        if (builders[tile] == null) builders[tile] = new FaceBuilder(tile, 0);
        builders[tile].add(x, y, z, face);
    }

    /**
     * All solid block faces for this chunk share one VertexBuffer and one M3G
     * Mesh. Each atlas tile remains a separate submesh so its original texture,
     * transparency, tint and fog settings are preserved.
     */
    private Mesh makeChunkBlockMesh() {
        int totalVertices = 0;
        for (int active = 0; active < activeFaceBuilderCount; active++) {
            FaceBuilder builder = chunkFaceBuilders[activeFaceBuilderIndices[active]];
            totalVertices += builder.vertexCount;
        }
        if (totalVertices <= 0) return null;

        VertexArray positions = new VertexArray(totalVertices, 3, 2);
        VertexArray texCoords = new VertexArray(totalVertices, 2, 2);
        VertexArray colors = new VertexArray(totalVertices, 4, 1);
        IndexBuffer[] submeshes = new IndexBuffer[activeFaceBuilderCount];
        Appearance[] appearances = new Appearance[activeFaceBuilderCount];
        int vertexOffset = 0;
        for (int active = 0; active < activeFaceBuilderCount; active++) {
            int index = activeFaceBuilderIndices[active];
            FaceBuilder builder = chunkFaceBuilders[index];
            // VertexArray.set copies directly from each reusable material buffer,
            // avoiding a second full-size temporary copy on memory-limited phones.
            positions.set(vertexOffset, builder.vertexCount, builder.p);
            texCoords.set(vertexOffset, builder.vertexCount, builder.uv);
            colors.set(vertexOffset, builder.vertexCount, builder.c);
            int[] strips = new int[builder.faceCount];
            for (int face = 0; face < strips.length; face++) strips[face] = 4;
            submeshes[active] = new TriangleStripArray(vertexOffset, strips);
            appearances[active] = tileAppearance(index);
            vertexOffset += builder.vertexCount;
        }
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        // A single scale is shared by all atlas-tile submeshes. The tiny 0.1%
        // inset also prevents the grass-side repeat seam from bleeding.
        vertices.setTexCoords(0, texCoords, 0.999f / 16.0f, null);
        vertices.setColors(colors);
        return new Mesh(vertices, submeshes, appearances);
    }

    private Mesh makeMesh(FaceBuilder data, Appearance appearance) {
        return makeMesh(data, appearance, 1.0f / 256.0f);
    }

    /** Build a first-person cube with outward-facing faces, without changing chunk meshes. */
    /** Swap vertices 1 and 2 in each arm quad to point its face outward. */
    private void reverseQuadWinding(short[] positions, int quadCount) {
        for (int quad = 0; quad < quadCount; quad++) {
            int vertex = quad * 4;
            swapShortVertex(positions, vertex + 1, vertex + 2, 3);
        }
    }

    private void swapShortVertex(short[] values, int first, int second, int components) {
        int a = first * components;
        int b = second * components;
        for (int i = 0; i < components; i++) {
            short value = values[a + i];
            values[a + i] = values[b + i];
            values[b + i] = value;
        }
    }

    private Mesh makeMesh(FaceBuilder data, Appearance appearance, float textureScale) {
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        VertexArray texCoords = new VertexArray(vertexCount, 2, 2);
        texCoords.set(0, vertexCount, data.uv);
        VertexArray colors = new VertexArray(vertexCount, 4, 1);
        colors.set(0, vertexCount, data.c);

        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        vertices.setTexCoords(0, texCoords, textureScale, null);
        vertices.setColors(colors);
        int[] strips = new int[data.faceCount];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        TriangleStripArray triangles = new TriangleStripArray(0, strips);

        return new Mesh(vertices, triangles, appearance);
    }

    private Mesh makeFireMesh(FireBuilder data, Appearance appearance) {
        VertexArray positions = new VertexArray(data.vertexCount, 3, 2);
        positions.set(0, data.vertexCount, data.p);
        data.texCoords = new VertexArray(data.vertexCount, 2, 2);
        data.texCoords.set(0, data.vertexCount, data.uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f / 256.0f, null);
        vertices.setTexCoords(0, data.texCoords, 1.0f / 1024.0f, null);
        int[] strips = new int[data.vertexCount / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        return new Mesh(vertices, new TriangleStripArray(0, strips), appearance);
    }

    private Mesh makeFlowerMesh(FlowerBuilder data, Appearance appearance) {
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        VertexArray texCoords = new VertexArray(vertexCount, 2, 2);
        texCoords.set(0, vertexCount, data.uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f / 256.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 256.0f, null);
        int[] strips = new int[vertexCount / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        return new Mesh(vertices, new TriangleStripArray(0, strips), appearance);
    }

    private Mesh makePortalMesh(PortalBuilder data) {
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        VertexArray texCoords = new VertexArray(vertexCount, 2, 2);
        texCoords.set(0, vertexCount, data.uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f / 256.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / portalFrameSize, null);
        int[] strips = new int[vertexCount / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        return new Mesh(vertices, new TriangleStripArray(0, strips), portalAppearance);
    }

    private void makePortalFrames(Image source) {
        portalFrameSize = source.getWidth();
        if (portalFrameSize <= 0 || source.getHeight() < portalFrameSize * 8) {
            portalFrameSize = source.getHeight() / 8;
        }
        if (portalFrameSize <= 0) throw new IllegalArgumentException("portal.png sin 8 frames");
        int[] pixels = new int[portalFrameSize * portalFrameSize];
        for (int frame = 0; frame < 8; frame++) {
            source.getRGB(pixels, 0, portalFrameSize, 0, frame * portalFrameSize,
                    portalFrameSize, portalFrameSize);
            Image image = Image.createRGBImage(pixels, portalFrameSize, portalFrameSize, true);
            Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
            texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
            texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
            portalFrames[frame] = texture;
        }
    }

    private Appearance createPortalAppearance(Texture2D texture) {
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
        compositing.setAlphaThreshold(0.01f);
        compositing.setBlending(CompositingMode.ALPHA);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private Appearance tileAppearance(int tile) {
        Appearance appearance = tileAppearances[tile];
        if (appearance != null) return appearance;
        int[] pixels = new int[16 * 16];
        if (tile >= Block.WOOL_TINT_TILE_START) {
            int woolTile = Block.atlasTile(Block.WOOL);
            terrainImage.getRGB(pixels, 0, 16, (woolTile & 15) * 16,
                    (woolTile >> 4) * 16, 16, 16);
            WoolColors.recolor(pixels, tile - Block.WOOL_TINT_TILE_START);
        } else {
            int sourceTile = tile;
            if (tile == 232) sourceTile = 103;      // Alpha netherrack
            else if (tile == 233) sourceTile = 104; // Alpha soul sand
            else if (tile == 234) sourceTile = 237; // Alpha still lava
            else if (tile == 235) sourceTile = 105; // Alpha glowstone
            terrainImage.getRGB(pixels, 0, 16, (sourceTile & 15) * 16,
                    (sourceTile >> 4) * 16, 16, 16);
            if (tile == 0) AlphaTint.apply(pixels, AlphaTint.GRASS_TOP);
            else if (tile == 52 || tile == 53) AlphaTint.apply(pixels, AlphaTint.LEAVES);
        }
        Image tileImage = Image.createRGBImage(pixels, 16, 16, true);
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, tileImage));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        appearance = createBlockAppearance(texture, tile == Block.atlasTile(Block.WATER));
        appearance.setFog(terrainFog);
        if (tile == 234 && appearance.getMaterial() != null)
            appearance.getMaterial().setColor(Material.EMISSIVE, 0xFFFF6A18);
        tileAppearances[tile] = appearance;
        return appearance;
    }

    /** Gather nearby torch blocks once per chunk build; lighting stays block-quantized. */
    private void collectChunkTorches(VoxelWorld world, int x0, int y0, int z0,
                                     int x1, int y1, int z1) {
        int scanRadius = LAVA_LIGHT_RADIUS;
        int minY = Math.max(0, y0 - scanRadius);
        int maxY = Math.min(VoxelWorld.HEIGHT - 1, y1 + scanRadius - 1);
        chunkTorchCount = world.collectTorchSources(
                x0 - scanRadius, minY, z0 - scanRadius,
                x1 + scanRadius - 1, maxY, z1 + scanRadius - 1,
                chunkTorchX, chunkTorchY, chunkTorchZ,
                chunkLavaSource, MAX_CHUNK_TORCHES);
        for (int i = 0; i < chunkTorchCount; i++) {
            chunkLightRadius[i] = (byte)(chunkLavaSource[i] ?
                    LAVA_LIGHT_RADIUS : TORCH_LIGHT_RADIUS);
        }
    }

    /**
     * Precomputes this one-block-high slice by projecting each nearby source
     * into its bounded Manhattan-radius footprint. This replaces a scan of
     * every source for every voxel during meshing.
     */
    private void fillChunkLightSlice(int x0, int y, int z0) {
        for (int i = 0; i < CHUNK_AREA; i++) chunkLightScratch[i] = 0;
        for (int source = 0; source < chunkTorchCount; source++) {
            int radius = chunkLightRadius[source] & 255;
            int dy = Math.abs(y - chunkTorchY[source]);
            if (dy > radius) continue;
            int remaining = radius - dy;
            int minX = Math.max(0, chunkTorchX[source] - x0 - remaining);
            int maxX = Math.min(VoxelWorld.CHUNK_XZ - 1, chunkTorchX[source] - x0 + remaining);
            int minZ = Math.max(0, chunkTorchZ[source] - z0 - remaining);
            int maxZ = Math.min(VoxelWorld.CHUNK_XZ - 1, chunkTorchZ[source] - z0 + remaining);
            int multiplier = chunkLavaSource[source] ? 2 : 3;
            for (int z = minZ; z <= maxZ; z++) {
                int zDistance = Math.abs(z0 + z - chunkTorchZ[source]);
                for (int x = minX; x <= maxX; x++) {
                    int distance = dy + zDistance + Math.abs(x0 + x - chunkTorchX[source]);
                    int level = (radius + 1 - distance) * multiplier;
                    if (level > 14) level = 14;
                    int at = z * VoxelWorld.CHUNK_XZ + x;
                    if (level > (chunkLightScratch[at] & 255))
                        chunkLightScratch[at] = (byte)level;
                }
            }
        }
    }

    /** Block-quantized torch falloff: each Manhattan step loses one brightness level. */
    private int torchLightAt(int x, int y, int z) {
        // Most chunks have no torches nearby. Avoid entering the per-block
        // torch loop entirely in that common case.
        if (chunkTorchCount == 0) return 0;
        int best = 0;
        for (int i = 0; i < chunkTorchCount; i++) {
            int distance = Math.abs(x - chunkTorchX[i]) +
                    Math.abs(y - chunkTorchY[i]) + Math.abs(z - chunkTorchZ[i]);
            int radius = chunkLightRadius[i] & 255;
            int level = (radius + 1 - distance) * (chunkLavaSource[i] ? 2 : 3);
            if (level > 14) level = 14;
            if (level > best) best = level;
            if (best >= 14) return 14;
        }
        return best;
    }

    private int renderWorldTile(VoxelWorld world, byte id, int face,
                                int x, int y, int z, int woolColor) {
        if (id == Block.LAVA) return 234;
        if (world.nether) {
            if (id == Block.STONE) return 232;
            if (id == Block.SAND) return 233;
            if (id == Block.WATER) return 234;
            if (id == Block.COAL) return 235;
        }
        if (id == Block.CHEST) {
            int facing = world.chestFacing(x, y, z);
            int frontFace = facing == 0 ? 3 : (facing == 1 ? 2 : (facing == 2 ? 4 : 1));
            if (face == frontFace) return 27;
            if (face == 0) return 25;
            return 26;
        }
        return Block.renderTile(id, face, woolColor);
    }

    private Appearance createBlockAppearance(Texture2D texture, boolean translucent) {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        material.setVertexColorTrackingEnable(true);
        appearance.setMaterial(material);
        if (translucent) {
            CompositingMode compositing = new CompositingMode();
            compositing.setBlending(CompositingMode.ALPHA);
            compositing.setAlphaThreshold(0.01f);
            compositing.setDepthTestEnable(true);
            compositing.setDepthWriteEnable(false);
            appearance.setCompositingMode(compositing);
        }
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        return appearance;
    }

    private Appearance createFlowerAppearance() {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, atlas);
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

    private Appearance createFireAppearance(Texture2D texture) {
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
        compositing.setAlphaThreshold(0.01f);
        compositing.setBlending(CompositingMode.ALPHA);
        compositing.setDepthTestEnable(true);
        compositing.setDepthWriteEnable(false);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private void updateFireMeshes() {
        for (int i = 0; i < chunks.length; i++) {
            Chunk chunk = chunks[i];
            if (chunk == null || chunk.fires == null) continue;
            for (int f = 0; f < chunk.fires.length; f++)
                if (chunk.fires[f] != null) chunk.fires[f].setFrame(fireAnimationFrame);
        }
    }

    private static final class Chunk {
        Mesh[] meshes;
        FireBuilder[] fires;
        Mesh crackMesh;
        VertexArray crackPositions;
        short[] crackPositionData;
        int handSwingTicks = 8;
        boolean handSwinging;
        boolean handLooping;
    }

    private static final class ChunkBuild {
        final int slot, x, y, z;
        int nextLayer;
        ChunkBuild(int slot, int x, int y, int z) {
            this.slot = slot; this.x = x; this.y = y; this.z = z;
        }
    }

    /** One full-cell portal plane, sharing the eight-frame portal material. */
    private static final class PortalBuilder {
        short[] p = new short[48];
        short[] uv = new short[32];
        int vertexCount;

        void reset() { vertexCount = 0; }

        void add(int x, int y, int z, int axis, int frameSize) {
            ensure(vertexCount + 4);
            int x0 = x * 256, x1 = x0 + 256;
            int y0 = y * 256, y1 = y0 + 256;
            int z0 = z * 256, z1 = z0 + 256;
            int u1 = frameSize;
            if (axis == 0) {
                int plane = z0 + 128;
                vertex(x0,y0,plane, 0,frameSize); vertex(x0,y1,plane, 0,0);
                vertex(x1,y0,plane, u1,frameSize); vertex(x1,y1,plane, u1,0);
            } else {
                int plane = x0 + 128;
                vertex(plane,y0,z0, 0,frameSize); vertex(plane,y1,z0, 0,0);
                vertex(plane,y0,z1, u1,frameSize); vertex(plane,y1,z1, u1,0);
            }
        }

        private void vertex(int x, int y, int z, int u, int v) {
            int pi = vertexCount * 3;
            p[pi] = (short)x; p[pi + 1] = (short)y; p[pi + 2] = (short)z;
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            vertexCount++;
        }

        private void ensure(int needed) {
            if (needed <= p.length / 3) return;
            int size = p.length / 3;
            while (size < needed) size <<= 1;
            short[] nextP = new short[size * 3];
            short[] nextUv = new short[size * 2];
            System.arraycopy(p, 0, nextP, 0, vertexCount * 3);
            System.arraycopy(uv, 0, nextUv, 0, vertexCount * 2);
            p = nextP; uv = nextUv;
        }
    }

    /** Two crossed, alpha-cutout quads per fire block, animated from fire.png. */
    private static final class FireBuilder {
        private static final int FRAME_UV_HEIGHT = 32;
        short[] p = new short[48];
        short[] uv = new short[32];
        int vertexCount;
        VertexArray texCoords;

        void add(int x, int y, int z) {
            ensure(vertexCount + 8);
            int x0 = x * 256, x1 = x0 + 256;
            int y0 = y * 256, y1 = y0 + 256;
            int z0 = z * 256, z1 = z0 + 256;
            addQuad(x0,y0,z0, x1,y0,z1, x1,y1,z1, x0,y1,z0);
            addQuad(x0,y0,z1, x1,y0,z0, x1,y1,z0, x0,y1,z1);
        }

        private void addQuad(int x0,int y0,int z0, int x1,int y1,int z1,
                             int x2,int y2,int z2, int x3,int y3,int z3) {
            int v0 = 0, v1 = FRAME_UV_HEIGHT;
            vertex(x0,y0,z0, 0,v1); vertex(x1,y1,z1, 1024,v1);
            vertex(x2,y2,z2, 1024,v0); vertex(x3,y3,z3, 0,v0);
        }

        void setFrame(int frame) {
            int v0 = frame * FRAME_UV_HEIGHT;
            int v1 = v0 + FRAME_UV_HEIGHT;
            for (int i = 0; i < vertexCount; i += 4) {
                int at = i * 2;
                uv[at] = 0; uv[at + 1] = (short)v1;
                uv[at + 2] = 1024; uv[at + 3] = (short)v1;
                uv[at + 4] = 1024; uv[at + 5] = (short)v0;
                uv[at + 6] = 0; uv[at + 7] = (short)v0;
            }
            if (texCoords != null) texCoords.set(0, vertexCount, uv);
        }

        private void vertex(int x, int y, int z, int u, int v) {
            int pi = vertexCount * 3;
            p[pi] = (short)x; p[pi + 1] = (short)y; p[pi + 2] = (short)z;
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            vertexCount++;
        }

        private void ensure(int needed) {
            if (needed <= p.length / 3) return;
            int size = p.length / 3;
            while (size < needed) size <<= 1;
            short[] nextP = new short[size * 3];
            short[] nextUv = new short[size * 2];
            System.arraycopy(p, 0, nextP, 0, vertexCount * 3);
            System.arraycopy(uv, 0, nextUv, 0, vertexCount * 2);
            p = nextP; uv = nextUv;
        }
    }

    /** Two crossed, alpha-cutout quads per flower, grouped by chunk and type. */
    private static final class FlowerBuilder {
        short[] p = new short[48];
        short[] uv = new short[32];
        int vertexCount;

        void reset() { vertexCount = 0; }

        void add(int x, int y, int z, int tile) {
            ensure(vertexCount + 8);
            int x0 = x * 256 + 77, x1 = x * 256 + 179;
            int y0 = y * 256, y1 = y * 256 + 205;
            int z0 = z * 256 + 77, z1 = z * 256 + 179;
            int u0 = (tile & 15) * 16 + 1, v0 = (tile >> 4) * 16 + 1;
            int u1 = u0 + 14, v1 = v0 + 14;
            vertex(x0,y0,z0,u0,v1); vertex(x1,y0,z1,u1,v1);
            vertex(x1,y1,z1,u1,v0); vertex(x0,y1,z0,u0,v0);
            vertex(x0,y0,z1,u0,v1); vertex(x1,y0,z0,u1,v1);
            vertex(x1,y1,z0,u1,v0); vertex(x0,y1,z1,u0,v0);
        }

        /** Alpha's door is a two-sided thin panel, rotated by its 4-bit state. */
        void addDoor(int x, int y, int z, byte doorBlock, int metadata) {
            ensure(vertexCount + 4);
            int state = (metadata & 4) != 0 ? metadata & 3 : (metadata - 1) & 3;
            int x0 = x * 256, x1 = x0 + 256;
            int y0 = y * 256, y1 = y0 + 256;
            int z0 = z * 256, z1 = z0 + 256;
            int edge = 48; // Alpha's 3/16-block door thickness.
            int tile = doorBlock == Block.IRON_DOOR ?
                    ((metadata & 8) != 0 ? 82 : 98) :
                    ((metadata & 8) != 0 ? 81 : 97);
            int u0 = (tile & 15) * 16 + 1, v0 = (tile >> 4) * 16 + 1;
            int u1 = u0 + 14, v1 = v0 + 14;
            // TriangleStripArray joins vertices 1 and 2. Order the vertices
            // as two triangles sharing the panel diagonal, not as its outline.
            if (state == 0) {
                vertex(x0,y0,z0+edge,u0,v1); vertex(x0,y1,z0+edge,u0,v0);
                vertex(x1,y0,z0+edge,u1,v1); vertex(x1,y1,z0+edge,u1,v0);
            } else if (state == 1) {
                vertex(x1-edge,y0,z0,u0,v1); vertex(x1-edge,y1,z0,u0,v0);
                vertex(x1-edge,y0,z1,u1,v1); vertex(x1-edge,y1,z1,u1,v0);
            } else if (state == 2) {
                vertex(x1,y0,z1-edge,u0,v1); vertex(x1,y1,z1-edge,u0,v0);
                vertex(x0,y0,z1-edge,u1,v1); vertex(x0,y1,z1-edge,u1,v0);
            } else {
                vertex(x0+edge,y0,z1,u0,v1); vertex(x0+edge,y1,z1,u0,v0);
                vertex(x0+edge,y0,z0,u1,v1); vertex(x0+edge,y1,z0,u1,v0);
            }
        }

        private void vertex(int x, int y, int z, int u, int v) {
            int pi = vertexCount * 3;
            p[pi] = (short)x; p[pi + 1] = (short)y; p[pi + 2] = (short)z;
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            vertexCount++;
        }

        private void ensure(int needed) {
            if (needed <= p.length / 3) return;
            int size = p.length / 3;
            while (size < needed) size <<= 1;
            short[] nextP = new short[size * 3];
            short[] nextUv = new short[size * 2];
            System.arraycopy(p, 0, nextP, 0, vertexCount * 3);
            System.arraycopy(uv, 0, nextUv, 0, vertexCount * 2);
            p = nextP; uv = nextUv;
        }
    }

    /** Reuses one short position array per material; no per-block scene nodes. */
    private static final class FaceBuilder {
        int tile;
        int lightLevel;
        short[] p = new short[192];
        short[] uv = new short[128];
        byte[] c = new byte[256];
        int vertexCount;
        int faceCount;

        FaceBuilder(int tile) { this(tile, 0); }
        FaceBuilder(int tile, int lightLevel) { reset(tile, lightLevel); }

        void reset(int nextTile, int nextLightLevel) {
            tile = nextTile;
            lightLevel = nextLightLevel;
            vertexCount = 0;
            faceCount = 0;
        }

        /** Add one tiled rectangle produced by the chunk's greedy face scan. */
        void addRect(int x, int y, int z, int width, int height, int face, int light) {
            ensure(vertexCount + 4);
            int u0 = 1, v0 = 1;
            int u1 = 1 + width * 16, v1 = 1 + height * 16;
            if (tile == Block.sideTile(Block.GRASS) && face >= 1 && face <= 4) {
                // Alpha samples within the grass-side tile, ending at 15.99px.
                // These meshes use repeat-wrapped 16px textures, so start at 0
                // and scale the 16px edge just inside the repeat seam.
                u0 = 0; v0 = 0;
                u1 = width * 16; v1 = height * 16;
            }
            int shade = litShade(face, light);
            if (face == 0) {
                vertex(x,y+1,z, u0,v1,shade); vertex(x,y+1,z+height, u0,v0,shade);
                vertex(x+width,y+1,z, u1,v1,shade); vertex(x+width,y+1,z+height, u1,v0,shade);
            } else if (face == 1) {
                vertex(x,y,z, u1,v1,shade); vertex(x,y,z+width, u0,v1,shade);
                vertex(x,y+height,z, u1,v0,shade); vertex(x,y+height,z+width, u0,v0,shade);
            } else if (face == 2) {
                vertex(x+1,y,z, u0,v1,shade); vertex(x+1,y+height,z, u0,v0,shade);
                vertex(x+1,y,z+width, u1,v1,shade); vertex(x+1,y+height,z+width, u1,v0,shade);
            } else if (face == 3) {
                vertex(x,y,z, u0,v1,shade); vertex(x,y+height,z, u0,v0,shade);
                vertex(x+width,y,z, u1,v1,shade); vertex(x+width,y+height,z, u1,v0,shade);
            } else if (face == 4) {
                vertex(x,y,z+1, u1,v1,shade); vertex(x+width,y,z+1, u0,v1,shade);
                vertex(x,y+height,z+1, u1,v0,shade); vertex(x+width,y+height,z+1, u0,v0,shade);
            } else {
                vertex(x,y,z, u0,v0,shade); vertex(x+width,y,z, u1,v0,shade);
                vertex(x,y,z+height, u0,v1,shade); vertex(x+width,y,z+height, u1,v1,shade);
            }
            faceCount++;
        }

        void add(int x, int y, int z, int face) {
            ensure(vertexCount + 4);
            int u0 = (tile & 15) * 16 + 1;
            int v0 = (tile >> 4) * 16 + 1;
            int u1 = u0 + 14;
            int v1 = v0 + 14;
            int shade = face == 0 ? 255 : (face == 5 ? 135 : (face <= 2 ? 205 : 180));
            if (face == 0) {
                vertex(x,y+1,z, u0,v1,shade); vertex(x,y+1,z+1, u0,v0,shade);
                vertex(x+1,y+1,z, u1,v1,shade); vertex(x+1,y+1,z+1, u1,v0,shade);
            } else if (face == 1) {
                vertex(x,y,z, u1,v1,shade); vertex(x,y,z+1, u0,v1,shade);
                vertex(x,y+1,z, u1,v0,shade); vertex(x,y+1,z+1, u0,v0,shade);
            } else if (face == 2) {
                vertex(x+1,y,z, u0,v1,shade); vertex(x+1,y+1,z, u0,v0,shade);
                vertex(x+1,y,z+1, u1,v1,shade); vertex(x+1,y+1,z+1, u1,v0,shade);
            } else if (face == 3) {
                vertex(x,y,z, u0,v1,shade); vertex(x,y+1,z, u0,v0,shade);
                vertex(x+1,y,z, u1,v1,shade); vertex(x+1,y+1,z, u1,v0,shade);
            } else if (face == 4) {
                vertex(x,y,z+1, u1,v1,shade); vertex(x+1,y,z+1, u0,v1,shade);
                vertex(x,y+1,z+1, u1,v0,shade); vertex(x+1,y+1,z+1, u0,v0,shade);
            } else {
                vertex(x,y,z, u0,v0,shade); vertex(x+1,y,z, u1,v0,shade);
                vertex(x,y,z+1, u0,v1,shade); vertex(x+1,y,z+1, u1,v1,shade);
            }
            faceCount++;
        }

        private int litShade(int face) {
            return litShade(face, lightLevel);
        }

        private int litShade(int face, int light) {
            int shade = face == 0 ? 255 : (face == 5 ? 135 : (face <= 2 ? 205 : 180));
            return shade + (255 - shade) * light / 14;
        }

        private void vertex(int x, int y, int z, int u, int v, int shade) {
            int pi = vertexCount * 3;
            p[pi] = (short)x; p[pi + 1] = (short)y; p[pi + 2] = (short)z;
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            int ci = vertexCount * 4;
            c[ci] = (byte)shade; c[ci + 1] = (byte)shade; c[ci + 2] = (byte)shade; c[ci + 3] = (byte)(tile == Block.atlasTile(Block.WATER) ? 160 : 255);
            vertexCount++;
        }

        private void ensure(int needed) {
            if (needed <= p.length / 3 && needed <= uv.length / 2 && needed <= c.length / 4) return;
            int size = p.length / 3;
            if (uv.length / 2 > size) size = uv.length / 2;
            if (c.length / 4 > size) size = c.length / 4;
            while (size < needed) size <<= 1;
            short[] nextP = new short[size * 3];
            short[] nextUv = new short[size * 2];
            byte[] nextC = new byte[size * 4];
            System.arraycopy(p, 0, nextP, 0, vertexCount * 3);
            System.arraycopy(uv, 0, nextUv, 0, vertexCount * 2);
            System.arraycopy(c, 0, nextC, 0, vertexCount * 4);
            p = nextP; uv = nextUv; c = nextC;
        }

    }
}



