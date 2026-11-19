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
    private static final int MAX_STREAM_RADIUS = 4;
    // One complete nearest chunk is built per sync, matching the supplied JAR.
    private static final int MAX_STREAM_SPAN = MAX_STREAM_RADIUS * 2 + 1;
    static final int CACHE_SIZE = MAX_STREAM_SPAN * MAX_STREAM_SPAN * VoxelWorld.CHUNKS_Y;
    private static final int TILE_COUNT = 256;
    private static final int SECTOR_SIZE = VoxelWorld.CHUNK_XZ / 2;
    private static final int SECTOR_COUNT = 4;
    private static final int ALL_SECTORS_MASK = (1 << SECTOR_COUNT) - 1;
    private static final int TORCH_LIGHT_RADIUS = 4;
    private static final int LAVA_LIGHT_RADIUS = 6;
    private static final int MAX_CHUNK_TORCHES = 128;
    // A material is split into bounded linked segments; no builder grows without limit.
    private static final int MAX_FACE_BUILDER_VERTICES = 2048;
    private static final int MAX_CHUNK_MESHES_PER_SYNC = 1;
    private static final int MAX_CHUNK_SNAPSHOT_CELLS_PER_SYNC = 512;
    private static final int MAX_CHUNK_LIGHT_SCAN_CELLS_PER_SYNC = 512;
    private static final long MAX_CHUNK_MESH_INSTALL_MILLIS = 6L;
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
    // One worker prepares Java geometry; M3G objects remain on the game thread.
    private final ChunkBuildSnapshot chunkBuildSnapshot = new ChunkBuildSnapshot();
    private volatile Chunk readyChunk;
    private volatile boolean buildResultReady;
    private volatile int readySlot, readyX, readyY, readyZ, readyEpoch, readySector, readyInvalidationVersion;
    private final Object chunkBuildWorkerLock = new Object();
    private volatile boolean chunkBuildWorkerBusy;
    private boolean chunkBuildRequestPending;
    private int chunkBuildRequestSlot, chunkBuildRequestX, chunkBuildRequestY;
    private int chunkBuildRequestZ, chunkBuildRequestEpoch, chunkBuildRequestSector;
    private int chunkBuildRequestInvalidationVersion;
    private Thread chunkBuildThread;
    // Copy world data into the worker snapshot in bounded frame-sized slices.
    private boolean chunkSnapshotPreparing;
    private int chunkSnapshotCursor, chunkSnapshotInvalidationVersion;
    private boolean chunkLightSnapshotValid;
    private int chunkLightSnapshotX, chunkLightSnapshotY, chunkLightSnapshotZ;
    private int chunkLightSnapshotRevision, chunkLightSnapshotCount;
    private int chunkSnapshotVoxelX, chunkSnapshotVoxelY, chunkSnapshotVoxelZ;
    private int chunkSnapshotMetadataX, chunkSnapshotMetadataY, chunkSnapshotMetadataZ;
    private int chunkSnapshotSlot, chunkSnapshotX, chunkSnapshotY, chunkSnapshotZ, chunkSnapshotEpoch;
    private int chunkSnapshotSector;
    private int streamEpoch;
    private Chunk installingChunk;
    private int installingSlot, installingX, installingY, installingZ, installingEpoch, installingSector;
    private int installingInvalidationVersion;
    // Small direct-mapped reuse cache for common quad-strip index buffers.
    private final int[] chunkQuadIndexFaceCounts = new int[8];
    private final TriangleStripArray[] chunkQuadIndexBuffers = new TriangleStripArray[8];
    private int chunkQuadIndexCursor;
    private final boolean[] residentChunks = new boolean[CACHE_SIZE];
    private final boolean[] dirtyChunks = new boolean[CACHE_SIZE];
    private final int[] dirtySectorMasks = new int[CACHE_SIZE];
    // The most recent player edit is rebuilt before background streaming work.
    private int urgentChunkX = Integer.MIN_VALUE;
    private int urgentChunkY = Integer.MIN_VALUE;
    private int urgentChunkZ = Integer.MIN_VALUE;
    private int urgentSector = -1;
    private final int[] chunkInvalidationVersion = new int[CACHE_SIZE];
    private final boolean[] chunkFrustumVisible = new boolean[CACHE_SIZE];
    private final int[] chunkWorldX = new int[CACHE_SIZE];
    private final int[] chunkWorldY = new int[CACHE_SIZE];
    private final int[] chunkWorldZ = new int[CACHE_SIZE];
    // Scratch arrays are reused by the single active chunk build.
    private final byte[] chunkVoxelScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
    private final byte[] chunkLightScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
    private final int[] greedyMaskScratch = new int[CHUNK_AREA];
    // One chunk is meshed at a time; reuse the large material lookup table to
    // avoid allocating thousands of references every time the player moves.
    private final FaceBuilder[] chunkFaceBuilders = new FaceBuilder[TILE_COUNT];
    private final FaceBuilder[] chunkFaceBuilderTails = new FaceBuilder[TILE_COUNT];
    // A bounded pool avoids allocating FaceBuilder arrays for every streamed
    // chunk without retaining one builder for every possible tile/light pair.
    private final FaceBuilder[] freeFaceBuilders = new FaceBuilder[16];
    private int freeFaceBuilderCount;
    private final FlowerBuilder[] freeFlowerBuilders = new FlowerBuilder[2];
    private final FireBuilder[] freeFireBuilders = new FireBuilder[2];
    private final PortalBuilder[] freePortalBuilders = new PortalBuilder[2];
    private int freeFlowerBuilderCount, freeFireBuilderCount, freePortalBuilderCount;
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
    private final Mesh crackMesh;
    private final VertexArray crackPositions;
    private final short[] crackPositionData = new short[] { 0,0,0, 0,1,0, 1,0,0, 1,1,0 };
    private final short[] crackUVData = new short[] { 0,16, 0,0, 16,16, 16,0 };
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
    private int builtWidth;
    private int builtHeight;
    private float tanHorizontalFov;
    private float cameraPitch;
    private int cameraMode; // 0 first-person, 1 rear third-person, 2 front-facing second-person
    private boolean playerVisible = true;
    private float thirdPersonDistance = 3.8f;
    private PlayerModel playerModel;
    private final PlayerModel[] lanPlayerModels = new PlayerModel[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerX = new float[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerY = new float[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerZ = new float[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerYaw = new float[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerPitch = new float[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerPreviousX = new float[LanProtocol.MAX_PLAYERS];
    private final float[] lanPlayerPreviousZ = new float[LanProtocol.MAX_PLAYERS];
    private final boolean[] lanPlayerHasPrevious = new boolean[LanProtocol.MAX_PLAYERS];
    private float previousPlayerX, previousPlayerZ;
    private boolean hasPreviousPlayerPosition;
    private float currentDaylight = 1.0f;
    private int currentSkyColor = 0xFF82B8E8;
    private float appliedSunlightIntensity = -1.0f;

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
        VertexArray crackUV = new VertexArray(4, 2, 2);
        crackUV.set(0, 4, crackUVData);
        crackPositions = new VertexArray(4, 3, 2);
        crackPositions.set(0, 4, crackPositionData);
        VertexBuffer crackBuffer = new VertexBuffer();
        crackBuffer.setPositions(crackPositions, 1.0f, null);
        crackBuffer.setTexCoords(0, crackUV, 1.0f / 16.0f, null);
        crackMesh = new Mesh(crackBuffer, new TriangleStripArray(0, new int[] { 4 }), crackAppearance);
        crackMesh.setRenderingEnable(false);
        scene.addChild(crackMesh);
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
        // Shortest is the emergency low-memory preset: a compact 2x2
        // square aligned to the player's facing direction. Keep the generic
        // radius at one as well, so no other streaming path can accidentally
        // expand this preset back to the larger square window.
        shortestDistance = setting == 4;
        streamRadius = shortestDistance ? 1 : (setting == 0 ? 1 : setting + 1);
        if (streamRadius > MAX_STREAM_RADIUS) streamRadius = MAX_STREAM_RADIUS;
        // Shortest keeps the 2x2 streaming window, but limits visible depth
        // to roughly one 16-block chunk.
        cameraFar = shortestDistance ? 16.0f :
                ((streamRadius * 2 + 1) * (float)VoxelWorld.CHUNK_XZ) + 8.0f;
        camera.setPerspective(cameraFov, (float)builtWidth / (float)builtHeight,
                0.1f, cameraFar);
        externalCamera.setPerspective(cameraFov, (float)builtWidth / (float)builtHeight,
                0.1f, cameraFar);
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
        if (handSwinging) {
            handSwingTicks++;
            if (handSwingTicks >= 8) {
                if (handLooping) {
                    handSwingTicks = 0;
                } else {
                    handSwingTicks = 8;
                    handSwinging = false;
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
        try { setPlayerSkin(GameAssets.playerSkin(index)); }
        catch (IOException missingDefault) { }
    }

    void setPlayerSkin(Image image) {
        if (image == null) return;
        Texture2D texture = PlayerModel.sharedSkinTexture(image);
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


    /** Creates and animates a remote avatar using the same biped rig as third/second person. */
    void updateLanPlayer(int playerId, int skinIndex, float x, float y, float z,
            float yaw, float pitch) {
        if (playerId < 0 || playerId >= LanProtocol.MAX_PLAYERS) return;
        PlayerModel model = lanPlayerModels[playerId];
        if (model == null) {
            Image skin;
            try { skin = GameAssets.playerSkin(skinIndex); }
            catch (IOException missingDefault) { return; }
            model = new PlayerModel(skin);
            lanPlayerModels[playerId] = model;
            scene.addChild(model.root);
        }
        float dx = lanPlayerHasPrevious[playerId] ? x - lanPlayerPreviousX[playerId] : 0.0f;
        float dz = lanPlayerHasPrevious[playerId] ? z - lanPlayerPreviousZ[playerId] : 0.0f;
        boolean moving = dx * dx + dz * dz > 0.0004f;
        // Smooth network jitter without delaying the first authoritative position.
        if (lanPlayerHasPrevious[playerId]) {
            lanPlayerX[playerId] += (x - lanPlayerX[playerId]) * 0.62f;
            lanPlayerY[playerId] += (y - lanPlayerY[playerId]) * 0.62f;
            lanPlayerZ[playerId] += (z - lanPlayerZ[playerId]) * 0.62f;
            lanPlayerYaw[playerId] += wrapLanAngle(yaw - lanPlayerYaw[playerId]) * 0.62f;
            lanPlayerPitch[playerId] += (pitch - lanPlayerPitch[playerId]) * 0.62f;
        } else {
            lanPlayerX[playerId] = x; lanPlayerY[playerId] = y; lanPlayerZ[playerId] = z;
            lanPlayerYaw[playerId] = yaw; lanPlayerPitch[playerId] = pitch;
            lanPlayerHasPrevious[playerId] = true;
        }
        lanPlayerPreviousX[playerId] = x;
        lanPlayerPreviousZ[playerId] = z;
        model.root.setRenderingEnable(true);
        model.update(lanPlayerX[playerId], lanPlayerY[playerId], lanPlayerZ[playerId],
                lanPlayerYaw[playerId], dx, dz, lanPlayerPitch[playerId],
                1.0f, moving, false, true);
    }

    void removeLanPlayer(int playerId) {
        if (playerId < 0 || playerId >= LanProtocol.MAX_PLAYERS) return;
        PlayerModel model = lanPlayerModels[playerId];
        if (model != null) {
            scene.removeChild(model.root);
            lanPlayerModels[playerId] = null;
        }
        lanPlayerHasPrevious[playerId] = false;
    }

    private float wrapLanAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
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

    /** Update the transparent crack quad on the selected block face; stage is 0..9. */
    void setMiningCracks(int x, int y, int z, int faceX, int faceY, int faceZ, int stage) {
        if (x < 0 || stage < 0) {
            crackMesh.setRenderingEnable(false);
            return;
        }
        if (stage > 9) stage = 9;
        crackAppearance.setTexture(0, crackFrames[stage]);

        // Keep the quad vertices local (0..1) and position the mesh at the
        // selected world block. This avoids truncating world coordinates into
        // short vertex values, which made overlays fail outside the origin chunk.
        float offset = 0.01f;
        if (Math.abs(faceY) >= Math.abs(faceX) && Math.abs(faceY) >= Math.abs(faceZ)) {
            crackPositionData[0] = 0; crackPositionData[1] = 0; crackPositionData[2] = 0;
            crackPositionData[3] = 0; crackPositionData[4] = 0; crackPositionData[5] = 1;
            crackPositionData[6] = 1; crackPositionData[7] = 0; crackPositionData[8] = 0;
            crackPositionData[9] = 1; crackPositionData[10] = 0; crackPositionData[11] = 1;
            crackMesh.setTranslation(x, y + (faceY > 0 ? 1.0f + offset : -offset), z);
        } else if (Math.abs(faceX) >= Math.abs(faceZ)) {
            crackPositionData[0] = 0; crackPositionData[1] = 0; crackPositionData[2] = 0;
            crackPositionData[3] = 0; crackPositionData[4] = 0; crackPositionData[5] = 1;
            crackPositionData[6] = 0; crackPositionData[7] = 1; crackPositionData[8] = 0;
            crackPositionData[9] = 0; crackPositionData[10] = 1; crackPositionData[11] = 1;
            crackMesh.setTranslation(x + (faceX > 0 ? 1.0f + offset : -offset), y, z);
        } else {
            crackPositionData[0] = 0; crackPositionData[1] = 0; crackPositionData[2] = 0;
            crackPositionData[3] = 0; crackPositionData[4] = 1; crackPositionData[5] = 0;
            crackPositionData[6] = 1; crackPositionData[7] = 0; crackPositionData[8] = 0;
            crackPositionData[9] = 1; crackPositionData[10] = 1; crackPositionData[11] = 0;
            crackMesh.setTranslation(x, y, z + (faceZ > 0 ? 1.0f + offset : -offset));
        }
        crackPositions.set(0, 4, crackPositionData);
        crackMesh.setRenderingEnable(true);
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
            if (appliedSunlightIntensity != 0.45f) {
                sunlight.setIntensity(0.45f);
                appliedSunlightIntensity = 0.45f;
            }
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
        float intensity = 0.28f + sun * 0.72f;
        if (Math.abs(intensity - appliedSunlightIntensity) >= 0.004f) {
            sunlight.setIntensity(intensity);
            appliedSunlightIntensity = intensity;
        }
        currentSkyColor = blendColor(0xFF26354A, 0xFF82B8E8, sun);
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
        // Torch brightness is baked into chunk vertex colors by torchLightAt().
        // No per-frame M3G light updates are needed.
    }

    int hitMob(VoxelWorld world, float dirX, float dirY, float dirZ, int damage) {
        return mobs.hit(world, dirX, dirY, dirZ, damage);
    }

    boolean shootPlayerArrow(VoxelWorld world, float pitch) {
        return mobs.shootPlayerArrow(world, pitch);
    }

    /** Synchronize a player edit and put its chunk at the head of the build queue. */
    void syncEditedBlock(VoxelWorld world, int x, int y, int z) {
        int cx = floorDiv(x, VoxelWorld.CHUNK_XZ);
        int cy = y / VoxelWorld.CHUNK_Y;
        int cz = floorDiv(z, VoxelWorld.CHUNK_XZ);
        urgentChunkX = cx;
        urgentChunkY = cy;
        urgentChunkZ = cz;
        int localX = x - cx * VoxelWorld.CHUNK_XZ;
        int localZ = z - cz * VoxelWorld.CHUNK_XZ;
        urgentSector = (localZ >= SECTOR_SIZE ? 2 : 0) + (localX >= SECTOR_SIZE ? 1 : 0);

        // For the usual one-voxel edit, sync() will invalidate this exact
        // chunk neighborhood from VoxelWorld.changedX/Y/Z. Avoid doing the
        // same cache walk and version bumps twice. Multi-voxel edits (doors,
        // attached plants, rails) still need this explicit first-position pass:
        // changedX/Y/Z can only remember the final voxel in the operation.
        if (world.changedX != x || world.changedY != y || world.changedZ != z) {
            int minX = cx - (x - cx * VoxelWorld.CHUNK_XZ == 0 ? 1 : 0);
            int maxX = cx + (x - cx * VoxelWorld.CHUNK_XZ == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0);
            int minY = cy - (y % VoxelWorld.CHUNK_Y == 0 ? 1 : 0);
            int maxY = cy + (y % VoxelWorld.CHUNK_Y == VoxelWorld.CHUNK_Y - 1 ? 1 : 0);
            int minZ = cz - (z - cz * VoxelWorld.CHUNK_XZ == 0 ? 1 : 0);
            int maxZ = cz + (z - cz * VoxelWorld.CHUNK_XZ == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0);
            for (int chunkY = minY; chunkY <= maxY; chunkY++) {
                if (chunkY < 0 || chunkY >= VoxelWorld.CHUNKS_Y) continue;
                for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                    for (int chunkX = minX; chunkX <= maxX; chunkX++) {
                        int slot = (chunkY * MAX_STREAM_SPAN +
                                floorMod(chunkZ, MAX_STREAM_SPAN)) * MAX_STREAM_SPAN +
                                floorMod(chunkX, MAX_STREAM_SPAN);
                        if (chunkWorldX[slot] == chunkX && chunkWorldY[slot] == chunkY &&
                                chunkWorldZ[slot] == chunkZ)
                            markChunkDirtyRegion(slot,
                                    Math.max(world.bulkChangedMinX - 1, chunkX * VoxelWorld.CHUNK_XZ),
                                    Math.min(world.bulkChangedMaxX + 1, (chunkX + 1) * VoxelWorld.CHUNK_XZ - 1),
                                    Math.max(world.bulkChangedMinZ - 1, chunkZ * VoxelWorld.CHUNK_XZ),
                                    Math.min(world.bulkChangedMaxZ + 1, (chunkZ + 1) * VoxelWorld.CHUNK_XZ - 1));
                    }
                }
            }
        }
        // paint() performs the renderer sync once per frame. Keep this helper to
        // queue the urgent local invalidation without advancing mesh work twice.
    }

    private boolean isUrgentChunk(int cx, int cy, int cz) {
        return cx == urgentChunkX && cy == urgentChunkY && cz == urgentChunkZ;
    }

    void sync(VoxelWorld world) {
        if (world.revision != builtRevision || world.fullRebuild) {
            boolean resetStreaming = builtRevision < 0 || world.fullRebuild;
            if (resetStreaming) {
                rebuildWorld(world);
                world.bulkChangedPending = false;
            } else rebuildChanged(world);
            builtRevision = world.revision;
        }
        // Rail meshes also have a camera-centered streaming window, so they
        // must be refreshed when the player crosses chunk boundaries even if
        // no block in the world changed.
        railSystem.update(world, shortestDistance ? 1 : Math.min(streamRadius, 2));
        world.fullRebuild = false;
        updateVisibleChunks(world);
    }

    private void rebuildChanged(VoxelWorld world) {
        if (world.bulkChangedPending) {
            // TNT changes many voxels at once. Rebuild only resident sections
            // intersecting that blast region, plus a one-block face halo.
            int minChunkX = floorDiv(world.bulkChangedMinX - 1, VoxelWorld.CHUNK_XZ);
            int maxChunkX = floorDiv(world.bulkChangedMaxX + 1, VoxelWorld.CHUNK_XZ);
            int minChunkY = floorDiv(world.bulkChangedMinY - 1, VoxelWorld.CHUNK_Y);
            int maxChunkY = floorDiv(world.bulkChangedMaxY + 1, VoxelWorld.CHUNK_Y);
            int minChunkZ = floorDiv(world.bulkChangedMinZ - 1, VoxelWorld.CHUNK_XZ);
            int maxChunkZ = floorDiv(world.bulkChangedMaxZ + 1, VoxelWorld.CHUNK_XZ);
            for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++) {
                if (chunkY < 0 || chunkY >= VoxelWorld.CHUNKS_Y) continue;
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        int slot = (chunkY * MAX_STREAM_SPAN +
                                floorMod(chunkZ, MAX_STREAM_SPAN)) * MAX_STREAM_SPAN +
                                floorMod(chunkX, MAX_STREAM_SPAN);
                        if (chunkWorldX[slot] == chunkX && chunkWorldY[slot] == chunkY &&
                                chunkWorldZ[slot] == chunkZ)
                            markChunkDirty(slot);
                    }
                }
            }
            world.bulkChangedPending = false;
        }
        int x = world.changedX, y = world.changedY, z = world.changedZ;
        int cx = floorDiv(x, VoxelWorld.CHUNK_XZ);
        int cy = y / VoxelWorld.CHUNK_Y;
        int cz = floorDiv(z, VoxelWorld.CHUNK_XZ);
        int lx = x - cx * VoxelWorld.CHUNK_XZ;
        int ly = y % VoxelWorld.CHUNK_Y;
        int lz = z - cz * VoxelWorld.CHUNK_XZ;
        boolean torchChanged = world.changedOldId == Block.TORCH ||
                world.get(x, y, z) == Block.TORCH;
        int minChunkX = torchChanged ? floorDiv(x - TORCH_LIGHT_RADIUS, VoxelWorld.CHUNK_XZ) :
                cx - (lx == 0 ? 1 : 0);
        int maxChunkX = torchChanged ? floorDiv(x + TORCH_LIGHT_RADIUS, VoxelWorld.CHUNK_XZ) :
                cx + (lx == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0);
        int minChunkY = torchChanged ? floorDiv(y - TORCH_LIGHT_RADIUS, VoxelWorld.CHUNK_Y) :
                cy - (ly == 0 ? 1 : 0);
        int maxChunkY = torchChanged ? floorDiv(y + TORCH_LIGHT_RADIUS, VoxelWorld.CHUNK_Y) :
                cy + (ly == VoxelWorld.CHUNK_Y - 1 ? 1 : 0);
        int minChunkZ = torchChanged ? floorDiv(z - TORCH_LIGHT_RADIUS, VoxelWorld.CHUNK_XZ) :
                cz - (lz == 0 ? 1 : 0);
        int maxChunkZ = torchChanged ? floorDiv(z + TORCH_LIGHT_RADIUS, VoxelWorld.CHUNK_XZ) :
                cz + (lz == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0);

        // Only chunks intersecting the changed block's face neighborhood or
        // torch-light radius can be affected. Address their ring-cache slots
        // directly instead of scanning all 648 entries for every edit.
        for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++) {
            if (chunkY < 0 || chunkY >= VoxelWorld.CHUNKS_Y) continue;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    int slot = (chunkY * MAX_STREAM_SPAN +
                            floorMod(chunkZ, MAX_STREAM_SPAN)) * MAX_STREAM_SPAN +
                            floorMod(chunkX, MAX_STREAM_SPAN);
                    if (chunkWorldX[slot] == chunkX && chunkWorldY[slot] == chunkY &&
                            chunkWorldZ[slot] == chunkZ)
                        markChunkDirtyRegion(slot,
                                Math.max(x - (torchChanged ? TORCH_LIGHT_RADIUS : (lx == 0 ? 1 : 0)), chunkX * VoxelWorld.CHUNK_XZ),
                                Math.min(x + (torchChanged ? TORCH_LIGHT_RADIUS : (lx == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0)), (chunkX + 1) * VoxelWorld.CHUNK_XZ - 1),
                                Math.max(z - (torchChanged ? TORCH_LIGHT_RADIUS : (lz == 0 ? 1 : 0)), chunkZ * VoxelWorld.CHUNK_XZ),
                                Math.min(z + (torchChanged ? TORCH_LIGHT_RADIUS : (lz == VoxelWorld.CHUNK_XZ - 1 ? 1 : 0)), (chunkZ + 1) * VoxelWorld.CHUNK_XZ - 1));
                }
            }
        }
    }

    private void markChunkDirty(int slot) {
        markChunkDirtyMask(slot, ALL_SECTORS_MASK);
    }

    private void markChunkDirtyMask(int slot, int mask) {
        if (mask == 0) return;
        dirtySectorMasks[slot] |= mask;
        dirtyChunks[slot] = true;
        chunkInvalidationVersion[slot]++;
    }

    private void markChunkDirtyRegion(int slot, int minX, int maxX, int minZ, int maxZ) {
        int x0 = chunkWorldX[slot] * VoxelWorld.CHUNK_XZ;
        int z0 = chunkWorldZ[slot] * VoxelWorld.CHUNK_XZ;
        int localMinX = minX - x0, localMaxX = maxX - x0;
        int localMinZ = minZ - z0, localMaxZ = maxZ - z0;
        int firstX = localMinX < SECTOR_SIZE ? 0 : 1;
        int lastX = localMaxX < SECTOR_SIZE ? 0 : 1;
        int firstZ = localMinZ < SECTOR_SIZE ? 0 : 1;
        int lastZ = localMaxZ < SECTOR_SIZE ? 0 : 1;
        int mask = 0;
        for (int sz = firstZ; sz <= lastZ; sz++)
            for (int sx = firstX; sx <= lastX; sx++) mask |= 1 << (sz * 2 + sx);
        markChunkDirtyMask(slot, mask);
    }

    private int nextDirtySector(int slot, int cx, int cz) {
        int mask = dirtySectorMasks[slot];
        if (!residentChunks[slot]) return 0;
        if (isUrgentChunk(cx, chunkWorldY[slot], cz) && urgentSector >= 0 &&
                (mask & (1 << urgentSector)) != 0) return urgentSector;
        for (int sector = 0; sector < SECTOR_COUNT; sector++)
            if ((mask & (1 << sector)) != 0) return sector;
        return 0;
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

    private void setChunkRenderingEnabled(Chunk chunk, boolean enabled) {
        if (chunk == null || chunk.renderingEnabled == enabled) return;
        if (chunk.meshRoot != null) chunk.meshRoot.setRenderingEnable(enabled);
        chunk.renderingEnabled = enabled;
    }

    private void setChunkVisibility(int i, boolean visible) {
        if (chunkFrustumVisible[i] == visible) return;
        chunkFrustumVisible[i] = visible;
        setChunkRenderingEnabled(chunks[i], visible);
    }

    private void replaceChunkSector(int i, int cx, int cy, int cz, int sector, Chunk replacement) {
        Chunk parent = chunks[i];
        if (parent == null) {
            parent = new Chunk();
            parent.sectors = new Chunk[SECTOR_COUNT];
            parent.meshRoot = new Group();
            parent.meshRoot.setRenderingEnable(false);
            parent.worldX = cx * VoxelWorld.CHUNK_XZ;
            parent.worldY = cy * VoxelWorld.CHUNK_Y;
            parent.worldZ = cz * VoxelWorld.CHUNK_XZ;
            parent.renderingEnabled = chunkFrustumVisible[i];
            chunks[i] = parent;
            terrain.addChild(parent.meshRoot);
        }
        Chunk previous = parent.sectors[sector];
        if (previous != null) {
            if (previous.meshRoot != null) parent.meshRoot.removeChild(previous.meshRoot);
            recycleChunkBuilders(previous);
        }
        parent.sectors[sector] = replacement;
        if (replacement != null && replacement.meshRoot != null) {
            replacement.renderingEnabled = parent.renderingEnabled;
            replacement.meshRoot.setRenderingEnable(replacement.renderingEnabled);
            parent.meshRoot.addChild(replacement.meshRoot);
        }
        chunkWorldX[i] = cx;
        chunkWorldY[i] = cy;
        chunkWorldZ[i] = cz;
        residentChunks[i] = true;
        dirtyChunks[i] = dirtySectorMasks[i] != 0;
        if (isUrgentChunk(cx, cy, cz) && dirtySectorMasks[i] == 0) {
            urgentChunkX = urgentChunkY = urgentChunkZ = Integer.MIN_VALUE;
            urgentSector = -1;
        }
    }

    private void rebuildWorld(VoxelWorld world) {
        streamEpoch++;
        chunkSnapshotPreparing = false;
        chunkLightSnapshotValid = false;
        if (installingChunk != null) {
            recycleChunkBuilders(installingChunk);
            installingChunk = null;
        }
        if (buildResultReady) {
            Chunk stale = readyChunk;
            readyChunk = null;
            buildResultReady = false;
            recycleChunkBuilders(stale);
        }
        for (int i = 0; i < chunks.length; i++) removeChunk(i);
    }

    private void removeChunk(int i) {
        Chunk chunk = chunks[i];
        if (chunk != null) {
            setChunkRenderingEnabled(chunk, false);
            if (chunk.meshRoot != null) terrain.removeChild(chunk.meshRoot);
            recycleChunkBuilders(chunk);
            chunks[i] = null;
        }
        residentChunks[i] = false;
        dirtyChunks[i] = false;
        dirtySectorMasks[i] = 0;
        chunkFrustumVisible[i] = false;
    }

    /**
     * Conservative AABB/frustum intersection using support radii against all six
     * camera planes. A chunk is rejected only when its entire box is outside a plane.
     */
    private boolean chunkInFrustum(int cx, int cy, int cz,
            float eyeX, float eyeY, float eyeZ,
            float fx, float fy, float fz, float rx, float rz,
            float ux, float uy, float uz) {
        float hx = VoxelWorld.CHUNK_XZ * 0.5f;
        float hy = VoxelWorld.CHUNK_Y * 0.5f;
        float hz = VoxelWorld.CHUNK_XZ * 0.5f;
        float dx = cx * VoxelWorld.CHUNK_XZ + hx - eyeX;
        float dy = cy * VoxelWorld.CHUNK_Y + hy - eyeY;
        float dz = cz * VoxelWorld.CHUNK_XZ + hz - eyeZ;
        float depth = dx * fx + dy * fy + dz * fz;
        float horizontal = dx * rx + dz * rz;
        float vertical = dx * ux + dy * uy + dz * uz;
        float depthRadius = Math.abs(fx) * hx + Math.abs(fy) * hy + Math.abs(fz) * hz;

        if (depth + depthRadius < 0.1f || depth - depthRadius > cameraFar) return false;

        float leftRadius = Math.abs(fx * tanHorizontalFov + rx) * hx +
                Math.abs(fy * tanHorizontalFov) * hy +
                Math.abs(fz * tanHorizontalFov + rz) * hz;
        if (depth * tanHorizontalFov + horizontal + leftRadius < 0.0f) return false;
        float rightRadius = Math.abs(fx * tanHorizontalFov - rx) * hx +
                Math.abs(fy * tanHorizontalFov) * hy +
                Math.abs(fz * tanHorizontalFov - rz) * hz;
        if (depth * tanHorizontalFov - horizontal + rightRadius < 0.0f) return false;

        float topRadius = Math.abs(fx * tanVerticalFov + ux) * hx +
                Math.abs(fy * tanVerticalFov + uy) * hy +
                Math.abs(fz * tanVerticalFov + uz) * hz;
        if (depth * tanVerticalFov + vertical + topRadius < 0.0f) return false;
        float bottomRadius = Math.abs(fx * tanVerticalFov - ux) * hx +
                Math.abs(fy * tanVerticalFov - uy) * hy +
                Math.abs(fz * tanVerticalFov - uz) * hz;
        if (depth * tanVerticalFov - vertical + bottomRadius < 0.0f) return false;
        return true;
    }

    /** Keep M3G buffers only for chunks intersecting the camera frustum. */
    private void updateVisibleChunks(VoxelWorld world) {
        float eyeX = world.playerX, eyeY = world.playerY + 1.62f, eyeZ = world.playerZ;
        int centerX = floorDiv((int)eyeX, VoxelWorld.CHUNK_XZ);
        int centerZ = floorDiv((int)eyeZ, VoxelWorld.CHUNK_XZ);
        int span = MAX_STREAM_SPAN;

        // Build the camera basis for every preset. Shortest keeps its 2x2
        // horizontal window but still rejects vertical chunks outside the view.
        float yaw = (float)(world.yaw * Math.PI / 180.0);
        float pitch = (float)(cameraPitch * Math.PI / 180.0);
        float sinYaw = (float)Math.sin(yaw), cosYaw = (float)Math.cos(yaw);
        float sinPitch = (float)Math.sin(pitch), cosPitch = (float)Math.cos(pitch);
        int terrainAheadX = centerX, terrainAheadZ = centerZ;
        if (Math.abs(sinYaw) > Math.abs(cosYaw))
            terrainAheadX += sinYaw >= 0.0f ? 1 : -1;
        else
            terrainAheadZ += cosYaw >= 0.0f ? 1 : -1;
        world.prefetchProceduralChunk(terrainAheadX, terrainAheadZ);
        float forwardX = sinYaw * cosPitch, forwardY = sinPitch, forwardZ = cosYaw * cosPitch;
        float rightX = cosYaw, rightZ = -sinYaw;
        float upX = -sinYaw * sinPitch, upY = cosPitch, upZ = -cosYaw * sinPitch;
        int firstChunkX = centerX - streamRadius;
        int lastChunkX = centerX + streamRadius;
        int firstChunkZ = centerZ - streamRadius;
        int lastChunkZ = centerZ + streamRadius;
        if (shortestDistance) {
            // Keep the 2x2 square: current chunk, the chunk ahead,
            // the chunk to camera-right, and their forward-right corner.
            // Quantize yaw to its dominant axis so the window stays stable.
            float facingX = sinYaw;
            float facingZ = cosYaw;
            int forwardChunkX = 0;
            int forwardChunkZ = 0;
            int rightChunkX = 0;
            int rightChunkZ = 0;
            if (Math.abs(facingX) > Math.abs(facingZ)) {
                forwardChunkX = facingX >= 0.0f ? 1 : -1;
                rightChunkZ = facingX >= 0.0f ? -1 : 1;
            } else {
                forwardChunkZ = facingZ >= 0.0f ? 1 : -1;
                rightChunkX = facingZ >= 0.0f ? 1 : -1;
            }
            int cornerX = forwardChunkX + rightChunkX;
            int cornerZ = forwardChunkZ + rightChunkZ;
            firstChunkX = centerX + Math.min(0, Math.min(forwardChunkX, cornerX));
            lastChunkX = centerX + Math.max(0, Math.max(forwardChunkX, cornerX));
            firstChunkZ = centerZ + Math.min(0, Math.min(forwardChunkZ, cornerZ));
            lastChunkZ = centerZ + Math.max(0, Math.max(forwardChunkZ, cornerZ));
        }

        // Apply the actual camera frustum to already-resident chunks too; previously
        // the frustum only filtered new builds while old meshes kept rendering.
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (!residentChunks[i]) continue;
            boolean outsideWindow = chunkWorldX[i] < firstChunkX || chunkWorldX[i] > lastChunkX ||
                    chunkWorldZ[i] < firstChunkZ || chunkWorldZ[i] > lastChunkZ;
            if (outsideWindow) removeChunk(i);
            else setChunkVisibility(i, chunkInFrustum(chunkWorldX[i], chunkWorldY[i], chunkWorldZ[i],
                    eyeX, eyeY, eyeZ, forwardX, forwardY, forwardZ,
                    rightX, rightZ, upX, upY, upZ));
        }

        int firstRx = firstChunkX - centerX + MAX_STREAM_RADIUS;
        int lastRx = lastChunkX - centerX + MAX_STREAM_RADIUS;
        int firstRz = firstChunkZ - centerZ + MAX_STREAM_RADIUS;
        int lastRz = lastChunkZ - centerZ + MAX_STREAM_RADIUS;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        boolean urgentChunkVisible = false;

        // Search and rank candidates only in the active window, not all 648
        // cache slots. In shortest mode this is 32 sectors (2x2x8).
        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int rz = firstRz; rz <= lastRz; rz++) {
                for (int rx = firstRx; rx <= lastRx; rx++) {
                    int cx = centerX + rx - MAX_STREAM_RADIUS;
                    int cz = centerZ + rz - MAX_STREAM_RADIUS;
                    int i = (cy * span + floorMod(cz, span)) * span + floorMod(cx, span);
                    float dx = cx * VoxelWorld.CHUNK_XZ + VoxelWorld.CHUNK_XZ * 0.5f - eyeX;
                    float dy = cy * VoxelWorld.CHUNK_Y + VoxelWorld.CHUNK_Y * 0.5f - eyeY;
                    float dz = cz * VoxelWorld.CHUNK_XZ + VoxelWorld.CHUNK_XZ * 0.5f - eyeZ;
                    boolean visible = chunkInFrustum(cx, cy, cz, eyeX, eyeY, eyeZ,
                            forwardX, forwardY, forwardZ, rightX, rightZ, upX, upY, upZ);

                    boolean sameChunk = chunkWorldX[i] == cx &&
                        chunkWorldY[i] == cy && chunkWorldZ[i] == cz;
                    if (residentChunks[i] && !sameChunk) removeChunk(i);

                    if (!sameChunk) {
                        chunkWorldX[i] = cx; chunkWorldY[i] = cy; chunkWorldZ[i] = cz;
                        dirtySectorMasks[i] = ALL_SECTORS_MASK;
                        dirtyChunks[i] = true;
                        chunkFrustumVisible[i] = visible;
                    } else if (!residentChunks[i]) {
                        // Preserve visibility for a pending or re-requested slot
                        // even when its cached coordinates already match.
                        chunkFrustumVisible[i] = visible;
                    }
                    if (visible && isUrgentChunk(cx, cy, cz))
                        urgentChunkVisible = true;
                    if (!visible) continue;
                    if (!residentChunks[i] || dirtySectorMasks[i] != 0) {
                        if (isUrgentChunk(cx, cy, cz)) {
                            // A just-edited chunk wins over all camera-distance candidates.
                            best = i;
                            bestDistance = -1.0f;
                        } else if (bestDistance >= 0.0f) {
                            float distance = dx * dx + dz * dz + dy * dy * 2.0f;
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = i;
                            }
                        }
                    }
                }
            }
        }

        // If the edited chunk has left the active visible window, release its
        // priority. Its dirty state is retained when resident; if it later re-enters,
        // normal streaming rebuilds it. Otherwise stale urgency can starve all streaming.
        boolean urgentPending = urgentChunkX != Integer.MIN_VALUE;
        if (urgentPending && !urgentChunkVisible) {
            urgentChunkX = urgentChunkY = urgentChunkZ = Integer.MIN_VALUE;
            urgentPending = false;
        }

        // If a player edit arrives while background streaming is in progress,
        // abandon unfinished background work so the edited chunk goes first.
        if (urgentPending && chunkSnapshotPreparing &&
                !isUrgentChunk(chunkSnapshotX, chunkSnapshotY, chunkSnapshotZ)) {
            chunkSnapshotPreparing = false;
        }
        if (urgentPending && installingChunk != null &&
                installingChunk.meshBuildAt == 0 &&
                !isUrgentChunk(installingX, installingY, installingZ)) {
            // Preempt only before native M3G meshes exist. Once installation has
            // allocated scene objects, finish it incrementally instead of
            // abandoning native allocations during a player edit.
            recycleChunkBuilders(installingChunk);
            if (installingSlot >= 0 && installingSlot < CACHE_SIZE)
                dirtyChunks[installingSlot] = true;
            installingChunk = null;
        }

        // Accept completed Java geometry, then spend a small M3G budget each frame.
        if (buildResultReady) {
            Chunk completed = readyChunk;
            int slot = readySlot, cx = readyX, cy = readyY, cz = readyZ;
            int epoch = readyEpoch;
            readyChunk = null;
            buildResultReady = false;
            boolean slotMatches = slot >= 0 && slot < CACHE_SIZE &&
                    chunkWorldX[slot] == cx && chunkWorldY[slot] == cy &&
                    chunkWorldZ[slot] == cz;
            boolean urgentCanPreempt = urgentChunkX != Integer.MIN_VALUE &&
                    !isUrgentChunk(cx, cy, cz);
            if (completed != null && epoch == streamEpoch && slotMatches &&
                    readyInvalidationVersion == chunkInvalidationVersion[slot] &&
                    (dirtySectorMasks[slot] & (1 << readySector)) == 0 && !urgentCanPreempt) {
                installingChunk = completed;
                installingSlot = slot; installingX = cx; installingY = cy; installingZ = cz;
                installingEpoch = epoch;
                installingSector = readySector;
                installingInvalidationVersion = readyInvalidationVersion;
                completed.worldX = cx * VoxelWorld.CHUNK_XZ;
                completed.worldY = cy * VoxelWorld.CHUNK_Y;
                completed.worldZ = cz * VoxelWorld.CHUNK_XZ;
            } else {
                if (completed != null) recycleChunkBuilders(completed);
                if (slotMatches) dirtyChunks[slot] = true;
            }
        }
        if (installingChunk != null) {
            boolean slotMatches = installingSlot >= 0 && installingSlot < CACHE_SIZE &&
                    chunkWorldX[installingSlot] == installingX &&
                    chunkWorldY[installingSlot] == installingY &&
                    chunkWorldZ[installingSlot] == installingZ;
            if (installingEpoch != streamEpoch || !slotMatches ||
                    installingInvalidationVersion != chunkInvalidationVersion[installingSlot] ||
                    (dirtySectorMasks[installingSlot] & (1 << installingSector)) != 0) {
                recycleChunkBuilders(installingChunk);
                if (slotMatches) dirtyChunks[installingSlot] = true;
                installingChunk = null;
            } else {
                try {
                    // Player edits get queue priority, not a larger per-frame
                    // allocation burst. Keep the same HTC frame budget for every chunk.
                    int meshBudget = MAX_CHUNK_MESHES_PER_SYNC;
                    if (finishChunkMeshStep(installingChunk, meshBudget)) {
                        replaceChunkSector(installingSlot, installingX, installingY, installingZ,
                                installingSector, installingChunk);
                        installingChunk = null;
                    }
                } catch (OutOfMemoryError exhausted) {
                    recycleChunkBuilders(installingChunk);
                    dirtyChunks[installingSlot] = true;
                    installingChunk = null;
                } catch (RuntimeException failed) {
                    recycleChunkBuilders(installingChunk);
                    dirtyChunks[installingSlot] = true;
                    installingChunk = null;
                }
            }
        }
        if (chunkSnapshotPreparing) {
            advanceChunkBuildSnapshot(world);
        }
        if (!chunkSnapshotPreparing && best >= 0 &&
                (!residentChunks[best] || dirtyChunks[best]) &&
                !chunkBuildWorkerBusy && !buildResultReady && installingChunk == null) {
            beginChunkBuildSnapshot(world, best, chunkWorldX[best],
                    chunkWorldY[best], chunkWorldZ[best]);
            if (chunkSnapshotPreparing) advanceChunkBuildSnapshot(world);
        }
    }

    private void beginChunkBuildSnapshot(VoxelWorld world, int slot,
                                         int cx, int cy, int cz) {
        chunkSnapshotSector = nextDirtySector(slot, cx, cz);
        if (!world.prepareProceduralChunksForSection(cx, cz)) {
            dirtyChunks[slot] = true;
            return;
        }
        chunkSnapshotPreparing = true;
        chunkSnapshotCursor = 0;
        chunkSnapshotVoxelX = chunkSnapshotVoxelY = chunkSnapshotVoxelZ = -1;
        chunkSnapshotMetadataX = chunkSnapshotMetadataY = chunkSnapshotMetadataZ = 0;
        chunkSnapshotSlot = slot;
        chunkSnapshotX = cx;
        chunkSnapshotY = cy;
        chunkSnapshotZ = cz;
        chunkSnapshotEpoch = streamEpoch;
        chunkSnapshotInvalidationVersion = chunkInvalidationVersion[slot];
        chunkBuildSnapshot.lightScanState[0] = 0;
        chunkBuildSnapshot.lightScanState[1] = 0;
    }

    private void advanceChunkBuildSnapshot(VoxelWorld world) {
        int slot = chunkSnapshotSlot;
        boolean slotMatches = slot >= 0 && slot < CACHE_SIZE &&
                chunkWorldX[slot] == chunkSnapshotX &&
                chunkWorldY[slot] == chunkSnapshotY &&
                chunkWorldZ[slot] == chunkSnapshotZ;
        if (chunkSnapshotEpoch != streamEpoch || !slotMatches ||
                !chunkFrustumVisible[slot] ||
                chunkInvalidationVersion[slot] != chunkSnapshotInvalidationVersion ||
                chunkBuildWorkerBusy || buildResultReady || installingChunk != null) {
            chunkSnapshotPreparing = false;
            if (slotMatches) dirtyChunks[slot] = true;
            return;
        }

        ChunkBuildSnapshot snapshot = chunkBuildSnapshot;
        int x0 = chunkSnapshotX * VoxelWorld.CHUNK_XZ;
        int y0 = chunkSnapshotY * VoxelWorld.CHUNK_Y;
        int z0 = chunkSnapshotZ * VoxelWorld.CHUNK_XZ;
        int voxelCount = ChunkBuildSnapshot.PAD_AREA * (VoxelWorld.CHUNK_Y + 2);
        int metadataCount = VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ;
        int total = voxelCount + metadataCount;
        int snapshotBudget = isUrgentChunk(chunkSnapshotX, chunkSnapshotY, chunkSnapshotZ) ?
                2048 : MAX_CHUNK_SNAPSHOT_CELLS_PER_SYNC;
        int end = chunkSnapshotCursor + snapshotBudget;
        if (end > total) end = total;

        while (chunkSnapshotCursor < end) {
            if (chunkSnapshotCursor < voxelCount) {
                int index = chunkSnapshotCursor++;
                snapshot.voxels[index] = world.get(x0 + chunkSnapshotVoxelX,
                        y0 + chunkSnapshotVoxelY, z0 + chunkSnapshotVoxelZ);
                if (++chunkSnapshotVoxelX > VoxelWorld.CHUNK_XZ) {
                    chunkSnapshotVoxelX = -1;
                    if (++chunkSnapshotVoxelZ > VoxelWorld.CHUNK_XZ) {
                        chunkSnapshotVoxelZ = -1;
                        chunkSnapshotVoxelY++;
                    }
                }
            } else {
                int metadataIndex = chunkSnapshotCursor++ - voxelCount;
                snapshot.metadata[metadataIndex] = (byte)world.metadataAt(
                        x0 + chunkSnapshotMetadataX, y0 + chunkSnapshotMetadataY,
                        z0 + chunkSnapshotMetadataZ);
                if (++chunkSnapshotMetadataX >= VoxelWorld.CHUNK_XZ) {
                    chunkSnapshotMetadataX = 0;
                    if (++chunkSnapshotMetadataZ >= VoxelWorld.CHUNK_XZ) {
                        chunkSnapshotMetadataZ = 0;
                        chunkSnapshotMetadataY++;
                    }
                }
            }
        }
        if (chunkSnapshotCursor < total) return;

        int lightRevision = world.lightRevision;
        boolean cachedLights = chunkLightSnapshotValid &&
                chunkLightSnapshotX == chunkSnapshotX &&
                chunkLightSnapshotY == chunkSnapshotY &&
                chunkLightSnapshotZ == chunkSnapshotZ &&
                chunkLightSnapshotRevision == lightRevision;
        if (cachedLights) {
            snapshot.lightCount = chunkLightSnapshotCount;
        } else {
            boolean lightsReady = world.collectLightSourcesStep(
                    x0 - LAVA_LIGHT_RADIUS, Math.max(0, y0 - LAVA_LIGHT_RADIUS), z0 - LAVA_LIGHT_RADIUS,
                    x0 + VoxelWorld.CHUNK_XZ + LAVA_LIGHT_RADIUS,
                    Math.min(VoxelWorld.HEIGHT, y0 + VoxelWorld.CHUNK_Y + LAVA_LIGHT_RADIUS),
                    z0 + VoxelWorld.CHUNK_XZ + LAVA_LIGHT_RADIUS,
                    snapshot.lightX, snapshot.lightY, snapshot.lightZ,
                    snapshot.lightRadius, snapshot.lavaSource, MAX_CHUNK_TORCHES,
                    TORCH_LIGHT_RADIUS, LAVA_LIGHT_RADIUS,
                    isUrgentChunk(chunkSnapshotX, chunkSnapshotY, chunkSnapshotZ) ?
                            4096 : MAX_CHUNK_LIGHT_SCAN_CELLS_PER_SYNC,
                    snapshot.lightScanState);
            if (!lightsReady) return;
            snapshot.lightCount = snapshot.lightScanState[1];
        }
        snapshot.nether = world.nether;
        if (chunkInvalidationVersion[slot] != chunkSnapshotInvalidationVersion) {
            chunkSnapshotPreparing = false;
            dirtyChunks[slot] = true;
            return;
        }
        if (!cachedLights) {
            chunkLightSnapshotValid = true;
            chunkLightSnapshotX = chunkSnapshotX;
            chunkLightSnapshotY = chunkSnapshotY;
            chunkLightSnapshotZ = chunkSnapshotZ;
            chunkLightSnapshotRevision = lightRevision;
            chunkLightSnapshotCount = snapshot.lightCount;
        }

        synchronized (chunkBuildWorkerLock) {
            if (chunkBuildWorkerBusy || buildResultReady || installingChunk != null) {
                chunkSnapshotPreparing = false;
                dirtyChunks[slot] = true;
                return;
            }
            chunkBuildWorkerBusy = true;
            chunkBuildRequestPending = true;
            chunkBuildRequestSlot = slot;
            chunkBuildRequestX = chunkSnapshotX;
            chunkBuildRequestY = chunkSnapshotY;
            chunkBuildRequestZ = chunkSnapshotZ;
            chunkBuildRequestEpoch = chunkSnapshotEpoch;
            chunkBuildRequestSector = chunkSnapshotSector;
            chunkBuildRequestInvalidationVersion = chunkSnapshotInvalidationVersion;
            dirtySectorMasks[slot] &= ~(1 << chunkSnapshotSector);
            dirtyChunks[slot] = dirtySectorMasks[slot] != 0;
            chunkSnapshotPreparing = false;
            if (chunkBuildThread == null) {
                try {
                    Thread worker = new Thread(new Runnable() {
                        public void run() { runChunkBuildWorker(); }
                    });
                    try {
                        worker.setPriority(Thread.MIN_PRIORITY);
                    } catch (RuntimeException ignored) {
                    }
                    chunkBuildThread = worker;
                    worker.start();
                } catch (OutOfMemoryError exhausted) {
                    chunkBuildThread = null;
                    chunkBuildWorkerBusy = false;
                    chunkBuildRequestPending = false;
                    dirtyChunks[slot] = true;
                } catch (RuntimeException failed) {
                    chunkBuildThread = null;
                    chunkBuildWorkerBusy = false;
                    chunkBuildRequestPending = false;
                    dirtyChunks[slot] = true;
                }
            } else {
                chunkBuildWorkerLock.notifyAll();
            }
        }
    }

    /**
     * Reuses one low-priority thread while chunks are streaming. The worker
     * exits after ten idle seconds so it does not retain an inactive renderer.
     */
    private void runChunkBuildWorker() {
        while (true) {
            int slot, cx, cy, cz, epoch, sector, invalidationVersion;
            synchronized (chunkBuildWorkerLock) {
                while (!chunkBuildRequestPending) {
                    try {
                        chunkBuildWorkerLock.wait(10000L);
                    } catch (InterruptedException ignored) {
                    }
                    if (!chunkBuildRequestPending) {
                        chunkBuildThread = null;
                        return;
                    }
                }
                chunkBuildRequestPending = false;
                slot = chunkBuildRequestSlot;
                cx = chunkBuildRequestX;
                cy = chunkBuildRequestY;
                cz = chunkBuildRequestZ;
                epoch = chunkBuildRequestEpoch;
                sector = chunkBuildRequestSector;
                invalidationVersion = chunkBuildRequestInvalidationVersion;
            }

            Chunk data = null;
            try {
                data = buildChunk(chunkBuildSnapshot, cx, cy, cz, sector);
                if (data == null) {
                    data = new Chunk();
                    data.faceBuilders = new FaceBuilder[TILE_COUNT];
                }
            } catch (OutOfMemoryError exhausted) {
                data = null;
            } catch (RuntimeException failed) {
                data = null;
            }

            synchronized (chunkBuildWorkerLock) {
                readySlot = slot;
                readyX = cx;
                readyY = cy;
                readyZ = cz;
                readyEpoch = epoch;
                readySector = sector;
                readyInvalidationVersion = invalidationVersion;
                readyChunk = data;
                buildResultReady = true;
                chunkBuildWorkerBusy = false;
            }
        }
    }

    /**
     * Create at most maxMeshes M3G meshes per game frame. The worker only
     * produces Java-side builders; all JSR-184 objects and scene mutations
     * stay on the game thread.
     */
    private void appendChunkMesh(Chunk chunk, Mesh mesh) {
        if (chunk.meshRoot == null) {
            chunk.meshRoot = new Group();
            chunk.meshRoot.setRenderingEnable(false);
        }
        chunk.meshes[chunk.meshBuildAt++] = mesh;
        chunk.meshRoot.addChild(mesh);
    }

    private boolean finishChunkMeshStep(Chunk chunk, int maxMeshes) {
        if (chunk.meshes == null) {
            int count = 0;
            if (chunk.faceBuilders != null) {
                for (int i = 0; i < chunk.faceBuilders.length; i++)
                    for (FaceBuilder b = chunk.faceBuilders[i]; b != null; b = b.next)
                        if (b.vertexCount > 0) count++;
            }
            if (chunk.flowers != null && chunk.flowers.vertexCount > 0) count++;
            if (chunk.fires != null && chunk.fires.vertexCount > 0) count++;
            if (chunk.portals != null && chunk.portals.vertexCount > 0) count++;
            chunk.meshes = new Mesh[count];
        }
        long installStarted = System.currentTimeMillis();
        int budget = maxMeshes;
        int installed = 0;
        while (budget > 0 && chunk.meshBuildPhase == 0) {
            if (installed > 0 &&
                    System.currentTimeMillis() - installStarted >= MAX_CHUNK_MESH_INSTALL_MILLIS)
                break;
            if (chunk.faceBuilders == null || chunk.meshBuildIndex >= chunk.faceBuilders.length) {
                chunk.faceBuilders = null;
                chunk.meshBuildPhase = 1;
                break;
            }
            if (chunk.meshBuildCurrent == null)
                chunk.meshBuildCurrent = chunk.faceBuilders[chunk.meshBuildIndex];
            if (chunk.meshBuildCurrent == null) {
                chunk.meshBuildIndex++;
                continue;
            }
            FaceBuilder builder = chunk.meshBuildCurrent;
            chunk.meshBuildCurrent = builder.next;
            chunk.faceBuilders[chunk.meshBuildIndex] = chunk.meshBuildCurrent;
            builder.next = null;
            if (builder.vertexCount > 0) {
                float scale = builder.tile == Block.sideTile(Block.GRASS) ?
                        0.999f / 16.0f : 1.0f / 16.0f;
                Mesh mesh = makeMesh(builder, tileAppearance(builder.tile), scale);
                mesh.setTranslation(chunk.worldX, chunk.worldY, chunk.worldZ);
                appendChunkMesh(chunk, mesh);
                budget--;
                installed++;
            }
            recycleFaceBuilder(builder);
        }
        if (chunk.meshBuildPhase == 1 && budget > 0 &&
                (installed == 0 || System.currentTimeMillis() - installStarted <
                        MAX_CHUNK_MESH_INSTALL_MILLIS)) {
            if (chunk.flowers != null) {
                FlowerBuilder b = chunk.flowers;
                if (b.vertexCount > 0) {
                    Mesh mesh = makeFlowerMesh(b, flowerAppearance);
                    mesh.setTranslation(chunk.worldX, chunk.worldY, chunk.worldZ);
                    appendChunkMesh(chunk, mesh);
                    budget--;
                    installed++;
                }
                chunk.flowers = null;
                recycleFlowerBuilder(b);
            }
            chunk.meshBuildPhase = 2;
        }
        if (chunk.meshBuildPhase == 2 && budget > 0 &&
                (installed == 0 || System.currentTimeMillis() - installStarted <
                        MAX_CHUNK_MESH_INSTALL_MILLIS)) {
            if (chunk.fires != null) {
                if (chunk.fires.vertexCount > 0) {
                    Mesh mesh = makeFireMesh(chunk.fires);
                    mesh.setTranslation(chunk.worldX, chunk.worldY, chunk.worldZ);
                    appendChunkMesh(chunk, mesh);
                    budget--;
                    installed++;
                } else {
                    recycleFireBuilder(chunk.fires);
                    chunk.fires = null;
                }
            }
            chunk.meshBuildPhase = 3;
        }
        if (chunk.meshBuildPhase == 3 && budget > 0 &&
                (installed == 0 || System.currentTimeMillis() - installStarted <
                        MAX_CHUNK_MESH_INSTALL_MILLIS)) {
            if (chunk.portals != null) {
                PortalBuilder b = chunk.portals;
                if (b.vertexCount > 0) {
                    Mesh mesh = makePortalMesh(b);
                    mesh.setTranslation(chunk.worldX, chunk.worldY, chunk.worldZ);
                    appendChunkMesh(chunk, mesh);
                    budget--;
                }
                chunk.portals = null;
                recyclePortalBuilder(b);
            }
            chunk.meshBuildPhase = 4;
        }
        return chunk.meshBuildPhase >= 4;
    }

    private void recycleChunkBuilders(Chunk chunk) {
        if (chunk == null) return;
        if (chunk.sectors != null) {
            for (int i = 0; i < chunk.sectors.length; i++) {
                recycleChunkBuilders(chunk.sectors[i]);
                chunk.sectors[i] = null;
            }
            chunk.sectors = null;
        }
        if (chunk.faceBuilders != null) {
            for (int i = 0; i < chunk.faceBuilders.length; i++) {
                FaceBuilder builder = chunk.faceBuilders[i];
                chunk.faceBuilders[i] = null;
                while (builder != null) {
                    FaceBuilder next = builder.next;
                    builder.next = null;
                    recycleFaceBuilder(builder);
                    builder = next;
                }
            }
            chunk.faceBuilders = null;
        }
        if (chunk.flowers != null) {
            recycleFlowerBuilder(chunk.flowers);
            chunk.flowers = null;
        }
        if (chunk.fires != null) {
            recycleFireBuilder(chunk.fires);
            chunk.fires = null;
        }
        if (chunk.portals != null) {
            recyclePortalBuilder(chunk.portals);
            chunk.portals = null;
        }
    }

    private Chunk buildChunk(ChunkBuildSnapshot snapshot, int cx, int cy, int cz, int sector) {
        FaceBuilder[] builders = chunkFaceBuilders;
        FaceBuilder[] builderTails = chunkFaceBuilderTails;
        for (int i = 0; i < builders.length; i++) { builders[i] = null; builderTails[i] = null; }
        FlowerBuilder flowers = null;
        FireBuilder fires = null;
        PortalBuilder portals = null;
        int x0 = cx * VoxelWorld.CHUNK_XZ;
        int y0 = cy * VoxelWorld.CHUNK_Y;
        int z0 = cz * VoxelWorld.CHUNK_XZ;
        int x1 = x0 + VoxelWorld.CHUNK_XZ;
        int y1 = y0 + VoxelWorld.CHUNK_Y;
        int z1 = z0 + VoxelWorld.CHUNK_XZ;
        int sectorX = (sector & 1) * SECTOR_SIZE;
        int sectorZ = (sector >> 1) * SECTOR_SIZE;

        prepareChunkLightMap(snapshot, x0, y0, z0, x1, y1, z1);

        // Copy voxels once into a compact linear buffer. Special geometry is
        // collected in the same pass, avoiding repeated coordinate arithmetic.
        int nonAirBlocks = 0;
        int localIndex = 0;
        for (int y = y0; y < y1; y++) {
            int localY = y - y0;
            for (int z = z0; z < z1; z++) {
                int localZ = z - z0;
                int localX = 0;
                for (int x = x0; x < x1; x++, localIndex++, localX++) {
                    byte id = snapshot.block(x - x0, y - y0, z - z0);
                    chunkVoxelScratch[localIndex] = id;
                    chunkLightScratch[localIndex] = (byte)(id == Block.LAVA ? 14 : torchLightAt(localIndex));
                    if (id == Block.AIR || localX < sectorX || localX >= sectorX + SECTOR_SIZE ||
                            localZ < sectorZ || localZ >= sectorZ + SECTOR_SIZE) continue;
                    nonAirBlocks++;
                    if (id == Block.PORTAL) {
                        if (portals == null) portals = acquirePortalBuilder();
                        portals.add(localX, localY, localZ,
                                snapshot.portalAxisAt(localX, localY, localZ), portalFrameSize);
                    } else if (id == Block.FIRE) {
                        if (fires == null) fires = acquireFireBuilder();
                        fires.add(localX, localY, localZ);
                    } else if (Block.isFlower(id)) {
                        if (flowers == null) flowers = acquireFlowerBuilder();
                        flowers.add(localX, localY, localZ, Block.sideTile(id));
                    } else if (Block.isDoor(id)) {
                        if (flowers == null) flowers = acquireFlowerBuilder();
                        flowers.addDoor(localX, localY, localZ, id,
                                snapshot.metadata(localX, localY, localZ) & 15);
                    }
                }
            }
        }
        Thread.yield();

        // An all-air section has no geometry at all. Returning null still marks
        // the slot resident in replaceChunk(), so it will not be retried every frame.
        if (nonAirBlocks == 0) {
            Chunk empty = new Chunk();
            empty.faceBuilders = new FaceBuilder[TILE_COUNT];
            return empty;
        }

        // Mine64-style greedy meshing: combine adjacent visible faces on each
        // chunk plane when their atlas tile matches. Tile textures repeat, so
        // the merged rectangles preserve the original per-block texture scale.
        int[] mask = greedyMaskScratch;
        for (int face = 0; face < 6; face++) {
            int slices = face == 0 || face == 5 ? VoxelWorld.CHUNK_Y : SECTOR_SIZE;
            int width = SECTOR_SIZE;
            int height = face == 0 || face == 5 ? SECTOR_SIZE : VoxelWorld.CHUNK_Y;
            for (int slice = 0; slice < slices; slice++) {
                for (int b = 0; b < height; b++) {
                    int row = b * width;
                    for (int a = 0; a < width; a++) {
                        int x, y, z;
                        if (face == 0 || face == 5) {
                            x = x0 + sectorX + a; y = y0 + slice; z = z0 + sectorZ + b;
                        } else if (face == 1 || face == 2) {
                            x = x0 + sectorX + slice; y = y0 + b; z = z0 + sectorZ + a;
                        } else {
                            x = x0 + sectorX + a; y = y0 + b; z = z0 + sectorZ + slice;
                        }
                        int voxelIndex = (((y - y0) * VoxelWorld.CHUNK_XZ) + (z - z0)) *
                                VoxelWorld.CHUNK_XZ + (x - x0);
                        byte id = chunkVoxelScratch[voxelIndex];
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
                                neighbor = chunkVoxelScratch[voxelIndex + CHUNK_AREA];
                            else if (face == 1 && x > x0)
                                neighbor = chunkVoxelScratch[voxelIndex - 1];
                            else if (face == 2 && x + 1 < x1)
                                neighbor = chunkVoxelScratch[voxelIndex + 1];
                            else if (face == 3 && z > z0)
                                neighbor = chunkVoxelScratch[voxelIndex - VoxelWorld.CHUNK_XZ];
                            else if (face == 4 && z + 1 < z1)
                                neighbor = chunkVoxelScratch[voxelIndex + VoxelWorld.CHUNK_XZ];
                            else if (face == 5 && y > y0)
                                neighbor = chunkVoxelScratch[voxelIndex - CHUNK_AREA];
                            else neighbor = snapshot.block(nx - x0, ny - y0, nz - z0);
                            if (Block.faceVisible(id, neighbor)) {
                                int metadata = snapshot.metadata(x - x0, y - y0, z - z0);
                                int tile = renderWorldTile(snapshot.nether, id, face, metadata);
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
                            x = sectorX + a; y = slice; z = sectorZ + b;
                            faceWidth = rectWidth; faceHeight = rectHeight;
                        } else if (face == 1 || face == 2) {
                            x = sectorX + slice; y = b; z = sectorZ + a;
                            faceWidth = rectWidth; faceHeight = rectHeight;
                        } else {
                            x = sectorX + a; y = b; z = sectorZ + slice;
                            faceWidth = rectWidth; faceHeight = rectHeight;
                        }
                        int encoded = key - 1;
                        int tile = encoded % TILE_COUNT;
                        int light = encoded / TILE_COUNT;
                        int builderIndex = tile;
                        FaceBuilder builder = builderTails[builderIndex];
                        if (builder == null) {
                            builder = acquireFaceBuilder(tile);
                            builders[builderIndex] = builder;
                            builderTails[builderIndex] = builder;
                        } else if (builder.vertexCount + 4 > MAX_FACE_BUILDER_VERTICES) {
                            FaceBuilder next = acquireFaceBuilder(tile);
                            builder.next = next;
                            builderTails[builderIndex] = next;
                            builder = next;
                        }
                        builder.addRect(x, y, z, faceWidth, faceHeight, face, light);
                        a += rectWidth;
                    }
                }
                Thread.yield();
            }
        }

        int count = 0;
        for (int i = 0; i < builders.length; i++) if (builders[i] != null) count++;
        int flowerCount = flowers == null ? 0 : 1;
        int fireCount = fires == null ? 0 : 1;
        int portalCount = portals == null ? 0 : 1;
        if (count + flowerCount + fireCount + portalCount == 0) {
            Chunk empty = new Chunk();
            empty.faceBuilders = new FaceBuilder[TILE_COUNT];
            return empty;
        }
        Chunk chunk = new Chunk();
        chunk.faceBuilders = new FaceBuilder[TILE_COUNT];
        for (int i = 0; i < builders.length; i++) {
            chunk.faceBuilders[i] = builders[i];
            builders[i] = null;
            builderTails[i] = null;
        }
        chunk.flowers = flowers;
        chunk.fires = fires;
        chunk.portals = portals;
        return chunk;
    }

    private void add(FaceBuilder[] builders, int tile, int x, int y, int z, int face) {
        if (builders[tile] == null) builders[tile] = new FaceBuilder(tile);
        builders[tile].add(x, y, z, face);
    }

    private synchronized FaceBuilder acquireFaceBuilder(int tile) {
        FaceBuilder builder = freeFaceBuilderCount > 0 ?
                freeFaceBuilders[--freeFaceBuilderCount] : null;
        if (builder != null) freeFaceBuilders[freeFaceBuilderCount] = null;
        if (builder == null) return new FaceBuilder(tile);
        builder.reset(tile);
        return builder;
    }

    private Mesh makeFireMesh(FireBuilder data) {
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        VertexArray texCoords = new VertexArray(vertexCount, 2, 2);
        texCoords.set(0, vertexCount, data.uv);
        data.texCoords = texCoords;
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f / 256.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 1024.0f, null);
        int[] strips = new int[vertexCount / 4];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        return new Mesh(vertices, new TriangleStripArray(0, strips), worldFireAppearance);
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

    private TriangleStripArray chunkQuadIndices(int faceCount) {
        for (int i = 0; i < chunkQuadIndexBuffers.length; i++) {
            if (chunkQuadIndexBuffers[i] != null &&
                    chunkQuadIndexFaceCounts[i] == faceCount)
                return chunkQuadIndexBuffers[i];
        }
        int[] strips = new int[faceCount];
        for (int i = 0; i < faceCount; i++) strips[i] = 4;
        TriangleStripArray indices = new TriangleStripArray(0, strips);
        int slot = chunkQuadIndexCursor++ & (chunkQuadIndexBuffers.length - 1);
        chunkQuadIndexFaceCounts[slot] = faceCount;
        chunkQuadIndexBuffers[slot] = indices;
        return indices;
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
        TriangleStripArray triangles = chunkQuadIndices(data.faceCount);
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
        if (tile == 234 && appearance.getMaterial() != null)
            appearance.getMaterial().setColor(Material.EMISSIVE, 0xFFFF6A18);
        tileAppearances[tile] = appearance;
        return appearance;
    }

    /** Gather nearby torch blocks once per chunk build; lighting stays block-quantized. */
    /**
     * Rasterize each sparse light source into the chunk buffer once. This
     * changes lighting lookup from "all sources per voxel" to one byte read.
     */
    private void prepareChunkLightMap(ChunkBuildSnapshot snapshot, int x0, int y0, int z0,
                                      int x1, int y1, int z1) {
        int volume = VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ;
        for (int i = 0; i < volume; i++) chunkLightScratch[i] = 0;
        for (int source = 0; source < snapshot.lightCount; source++) {
            int sx = snapshot.lightX[source];
            int sy = snapshot.lightY[source];
            int sz = snapshot.lightZ[source];
            int radius = snapshot.lightRadius[source] & 255;
            int minX = sx - radius, maxX = sx + radius;
            int minY = sy - radius, maxY = sy + radius;
            int minZ = sz - radius, maxZ = sz + radius;
            if (minX < x0) minX = x0;
            if (maxX >= x1) maxX = x1 - 1;
            if (minY < y0) minY = y0;
            if (maxY >= y1) maxY = y1 - 1;
            if (minZ < z0) minZ = z0;
            if (maxZ >= z1) maxZ = z1 - 1;
            int multiplier = snapshot.lavaSource[source] ? 2 : 3;
            for (int y = minY; y <= maxY; y++) {
                int dy = Math.abs(y - sy);
                int yBase = (y - y0) * CHUNK_AREA;
                for (int z = minZ; z <= maxZ; z++) {
                    int dz = dy + Math.abs(z - sz);
                    int row = yBase + (z - z0) * VoxelWorld.CHUNK_XZ;
                    for (int x = minX; x <= maxX; x++) {
                        int distance = dz + Math.abs(x - sx);
                        if (distance > radius) continue;
                        int level = (radius + 1 - distance) * multiplier;
                        if (level > 14) level = 14;
                        int index = row + x - x0;
                        if ((chunkLightScratch[index] & 255) < level)
                            chunkLightScratch[index] = (byte)level;
                    }
                }
            }
        }
    }

    /** O(1) block-quantized light lookup from the precomputed chunk map. */
    private int torchLightAt(int localIndex) {
        return chunkLightScratch[localIndex] & 255;
    }

    private int renderWorldTile(boolean nether, byte id, int face, int metadata) {
        if (id == Block.LAVA) return 234;
        if (nether) {
            if (id == Block.STONE) return 232;
            if (id == Block.SAND) return 233;
            if (id == Block.WATER) return 234;
            if (id == Block.COAL) return 235;
        }
        if (id == Block.CHEST) {
            int facing = metadata & 3;
            int frontFace = facing == 0 ? 3 : (facing == 1 ? 2 : (facing == 2 ? 4 : 1));
            if (face == frontFace) return 27;
            if (face == 0) return 25;
            return 26;
        }
        return Block.renderTile(id, face, id == Block.WOOL ? metadata & 15 : 0);
    }

    private void tintFaceBuilder(FaceBuilder data, int tint) {
        int tr = (tint >> 16) & 255;
        int tg = (tint >> 8) & 255;
        int tb = tint & 255;
        for (int i = 0; i < data.vertexCount; i++) {
            int at = i * 4;
            int shade = data.c[at] & 255;
            data.c[at] = (byte)(shade * tr / 255);
            data.c[at + 1] = (byte)(shade * tg / 255);
            data.c[at + 2] = (byte)(shade * tb / 255);
        }
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
        polygon.setCulling(translucent ? PolygonMode.CULL_NONE : PolygonMode.CULL_BACK);
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
            if (chunk == null) continue;
            if (chunk.sectors != null) {
                for (int sector = 0; sector < chunk.sectors.length; sector++) {
                    Chunk part = chunk.sectors[sector];
                    if (part != null && part.fires != null) part.fires.setFrame(fireAnimationFrame);
                }
            } else if (chunk.fires != null) chunk.fires.setFrame(fireAnimationFrame);
        }
    }

    private static final class Chunk {
        Mesh[] meshes;
        Group meshRoot;
        Chunk[] sectors;
        FaceBuilder[] faceBuilders;
        FlowerBuilder flowers;
        FireBuilder fires;
        PortalBuilder portals;
        boolean renderingEnabled;
        int worldX, worldY, worldZ;
        int meshBuildIndex, meshBuildPhase, meshBuildAt;
        FaceBuilder meshBuildCurrent;
    }

    private synchronized void recycleFaceBuilder(FaceBuilder builder) {
        if (builder.p.length > 1536) return;
        if (freeFaceBuilderCount < freeFaceBuilders.length) {
            builder.reset(0);
            freeFaceBuilders[freeFaceBuilderCount++] = builder;
        }
    }

    private synchronized FlowerBuilder acquireFlowerBuilder() {
        if (freeFlowerBuilderCount == 0) return new FlowerBuilder();
        FlowerBuilder builder = freeFlowerBuilders[--freeFlowerBuilderCount];
        freeFlowerBuilders[freeFlowerBuilderCount] = null;
        builder.reset();
        return builder;
    }

    private synchronized FireBuilder acquireFireBuilder() {
        if (freeFireBuilderCount == 0) return new FireBuilder();
        FireBuilder builder = freeFireBuilders[--freeFireBuilderCount];
        freeFireBuilders[freeFireBuilderCount] = null;
        builder.reset();
        return builder;
    }

    private synchronized PortalBuilder acquirePortalBuilder() {
        if (freePortalBuilderCount == 0) return new PortalBuilder();
        PortalBuilder builder = freePortalBuilders[--freePortalBuilderCount];
        freePortalBuilders[freePortalBuilderCount] = null;
        builder.reset();
        return builder;
    }

    private synchronized void recycleFlowerBuilder(FlowerBuilder builder) {
        if (builder.p.length > 4096) return;
        if (freeFlowerBuilderCount < freeFlowerBuilders.length) {
            builder.reset();
            freeFlowerBuilders[freeFlowerBuilderCount++] = builder;
        }
    }

    private synchronized void recycleFireBuilder(FireBuilder builder) {
        if (builder.p.length > 4096) return;
        if (freeFireBuilderCount < freeFireBuilders.length) {
            builder.reset();
            freeFireBuilders[freeFireBuilderCount++] = builder;
        }
    }

    private synchronized void recyclePortalBuilder(PortalBuilder builder) {
        if (builder.p.length > 4096) return;
        if (freePortalBuilderCount < freePortalBuilders.length) {
            builder.reset();
            freePortalBuilders[freePortalBuilderCount++] = builder;
        }
    }

    private static final class ChunkBuildSnapshot {
        private static final int PAD = VoxelWorld.CHUNK_XZ + 2;
        private static final int PAD_AREA = PAD * PAD;
        final byte[] voxels = new byte[PAD_AREA * (VoxelWorld.CHUNK_Y + 2)];
        final byte[] metadata = new byte[VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
        boolean nether;
        final int[] lightX = new int[MAX_CHUNK_TORCHES];
        final int[] lightY = new int[MAX_CHUNK_TORCHES];
        final int[] lightZ = new int[MAX_CHUNK_TORCHES];
        final byte[] lightRadius = new byte[MAX_CHUNK_TORCHES];
        final boolean[] lavaSource = new boolean[MAX_CHUNK_TORCHES];
        final int[] lightScanState = new int[5];
        int lightCount;

        byte block(int x, int y, int z) {
            if (y < -1 || y > VoxelWorld.CHUNK_Y || x < -1 ||
                    x > VoxelWorld.CHUNK_XZ || z < -1 || z > VoxelWorld.CHUNK_XZ)
                return Block.AIR;
            return voxels[(y + 1) * PAD_AREA + (z + 1) * PAD + x + 1];
        }

        int metadata(int x, int y, int z) {
            if (x < 0 || x >= VoxelWorld.CHUNK_XZ || y < 0 ||
                    y >= VoxelWorld.CHUNK_Y || z < 0 || z >= VoxelWorld.CHUNK_XZ) return 0;
            return metadata[(y * VoxelWorld.CHUNK_XZ + z) * VoxelWorld.CHUNK_XZ + x] & 255;
        }

        int portalAxisAt(int x, int y, int z) {
            if (block(x - 1, y, z) == Block.PORTAL || block(x + 1, y, z) == Block.PORTAL) return 0;
            if (block(x, y, z - 1) == Block.PORTAL || block(x, y, z + 1) == Block.PORTAL) return 1;
            return 0;
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

        void reset() { vertexCount = 0; texCoords = null; }

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
        FaceBuilder next;
        short[] p = new short[192];
        short[] uv = new short[128];
        byte[] c = new byte[256];
        int vertexCount;
        int faceCount;

        FaceBuilder(int tile) { reset(tile); }

        void reset(int nextTile) {
            tile = nextTile;
            vertexCount = 0;
            faceCount = 0;
            next = null;
        }

        /** Add one tiled rectangle produced by the chunk's greedy face scan. */
        void addRect(int x, int y, int z, int width, int height, int face, int lightLevel) {
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
            int shade = litShade(face, lightLevel);
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

        private int litShade(int face, int lightLevel) {
            int shade = face == 0 ? 255 : (face == 5 ? 135 : (face <= 2 ? 205 : 180));
            return shade + (255 - shade) * lightLevel / 14;
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
            if (needed > MAX_FACE_BUILDER_VERTICES)
                throw new IllegalStateException("FaceBuilder segment overflow");
            if (needed <= p.length / 3 && needed <= uv.length / 2 && needed <= c.length / 4) return;
            int size = p.length / 3;
            if (uv.length / 2 > size) size = uv.length / 2;
            if (c.length / 4 > size) size = c.length / 4;
            while (size < needed) {
                size <<= 1;
                if (size > MAX_FACE_BUILDER_VERTICES) size = MAX_FACE_BUILDER_VERTICES;
            }
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



