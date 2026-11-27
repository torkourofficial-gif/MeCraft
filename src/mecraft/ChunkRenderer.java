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
    private static final int MAX_STREAM_RADIUS = 4;
    // One complete nearest chunk is built per sync, matching the supplied JAR.
    private static final int MAX_STREAM_SPAN = MAX_STREAM_RADIUS * 2 + 1;
    static final int CACHE_SIZE = MAX_STREAM_SPAN * MAX_STREAM_SPAN * VoxelWorld.CHUNKS_Y;
    private static final int TILE_COUNT = 256;
    // Rectangular rebuild islands keep edits local without multiplying sector count excessively.
    private static final int SECTOR_SIZE_X = VoxelWorld.CHUNK_XZ / 4;
    private static final int SECTOR_SIZE_Z = VoxelWorld.CHUNK_XZ / 2;
    private static final int SECTORS_X = VoxelWorld.CHUNK_XZ / SECTOR_SIZE_X;
    private static final int SECTORS_Z = VoxelWorld.CHUNK_XZ / SECTOR_SIZE_Z;
    private static final int SECTOR_COUNT = SECTORS_X * SECTORS_Z;
    private static final int ALL_SECTORS_MASK = (1 << SECTOR_COUNT) - 1;
    private static final int TORCH_LIGHT_RADIUS = 4;
    private static final int LAVA_LIGHT_RADIUS = 6;
    private static final int MAX_CHUNK_TORCHES = 128;
    // A material is split into bounded linked segments; no builder grows without limit.
    private static final int MAX_FACE_BUILDER_VERTICES = 2048;
    // Larger bounded slices reduce streaming latency while keeping each sync short.
    private static final int MAX_CHUNK_MESHES_PER_SYNC = 3;
    private static final int MAX_CHUNK_SNAPSHOT_CELLS_PER_SYNC = 4096;
    // Player edits stay responsive without taking an unbounded frame-sized copy.
    private static final int MAX_URGENT_CHUNK_SNAPSHOT_CELLS_PER_SYNC = 6144;
    private static final int MAX_CHUNK_LIGHT_SCAN_CELLS_PER_SYNC = 6144;
    private static final long MAX_CHUNK_MESH_INSTALL_MILLIS = 6L;
    private static final int CHUNK_AREA = VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_XZ;
    private static final float CHUNK_RADIUS = 12.0f;
    private static final float HELD_BLOCK_SCALE = 0.4f;
    private static final float HELD_BLOCK_OFFSET = -HELD_BLOCK_SCALE * 0.5f;
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
    // Back off after native/heap allocation failures instead of retrying every frame.
    private long chunkBuildRetryAfterMillis;
    private boolean chunkBuildRequestPending;
    private int chunkBuildRequestSlot, chunkBuildRequestX, chunkBuildRequestY;
    private int chunkBuildRequestZ, chunkBuildRequestEpoch, chunkBuildRequestSector;
    private int chunkBuildRequestInvalidationVersion;
    private Thread chunkBuildThread;
    // Copy world data into the worker snapshot in bounded frame-sized slices.
    private boolean chunkSnapshotPreparing;
    private int chunkSnapshotCursor, chunkSnapshotInvalidationVersion;
    private int chunkSnapshotChunkInvalidationVersion;
    private boolean chunkLightSnapshotValid;
    private boolean chunkVoxelSnapshotValid, chunkVoxelSnapshotFull;
    private boolean chunkSnapshotFull;
    private int chunkSnapshotVoxelMinX, chunkSnapshotVoxelMaxX;
    private int chunkSnapshotVoxelMinZ, chunkSnapshotVoxelMaxZ;
    private int chunkSnapshotMetadataMinX, chunkSnapshotMetadataMaxX;
    private int chunkSnapshotMetadataMinZ, chunkSnapshotMetadataMaxZ;
    private int chunkVoxelSnapshotX, chunkVoxelSnapshotY, chunkVoxelSnapshotZ, chunkVoxelSnapshotVersion;
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
    private boolean installingPreviousSectorReleased;
    // Bounded cache for repeated quad-strip layouts. Batch entries include the
    // first vertex offset, avoiding a strips array allocation for each mesh.
    private final int[] chunkQuadIndexFirstVertices = new int[8];
    private final int[] chunkQuadIndexFaceCounts = new int[8];
    private final TriangleStripArray[] chunkQuadIndexBuffers = new TriangleStripArray[8];
    private int chunkQuadIndexCursor;
    // Batch up to three bounded material segments into one M3G Mesh, as in ComcraftHD.
    // The reusable arrays cap temporary Java heap use and avoid per-batch array churn.
    private static final int CHUNK_BATCH_SEGMENTS = 3;
    private static final int CHUNK_BATCH_MAX_VERTICES =
            CHUNK_BATCH_SEGMENTS * MAX_FACE_BUILDER_VERTICES;
    private final FaceBuilder[] chunkBatchBuilders = new FaceBuilder[CHUNK_BATCH_SEGMENTS];
    private final short[] chunkBatchPositions = new short[CHUNK_BATCH_MAX_VERTICES * 3];
    private final short[] chunkBatchTexCoords = new short[CHUNK_BATCH_MAX_VERTICES * 2];
    private final byte[] chunkBatchColors = new byte[CHUNK_BATCH_MAX_VERTICES * 4];
    private final boolean[] residentChunks = new boolean[CACHE_SIZE];
    private final boolean[] dirtyChunks = new boolean[CACHE_SIZE];
    private final int[] dirtySectorMasks = new int[CACHE_SIZE];
    // The most recent player edit is rebuilt before background streaming work.
    private int urgentChunkX = Integer.MIN_VALUE;
    private int urgentChunkY = Integer.MIN_VALUE;
    private int urgentChunkZ = Integer.MIN_VALUE;
    private int urgentSector = -1;
    private final int[] chunkInvalidationVersion = new int[CACHE_SIZE];
    // Per-sector tokens stop unrelated edits in this chunk cancelling valid work.
    private final int[] chunkSectorInvalidationVersion = new int[CACHE_SIZE * SECTOR_COUNT];
    private final boolean[] chunkFrustumVisible = new boolean[CACHE_SIZE];
    private final int[] chunkWorldX = new int[CACHE_SIZE];
    private final int[] chunkWorldY = new int[CACHE_SIZE];
    private final int[] chunkWorldZ = new int[CACHE_SIZE];
    // Scratch arrays are reused by the single active chunk build.
    private final byte[] chunkVoxelScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
    private final byte[] chunkLightScratch = new byte[VoxelWorld.CHUNK_XZ *
            VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ];
    // Sector-local queue plus a one-byte queued marker prevent duplicate sky entries.
    private final short[] chunkSkyQueue = new short[SECTOR_SIZE_X * SECTOR_SIZE_Z * VoxelWorld.CHUNK_Y];
    private final byte[] chunkSkyQueued = new byte[SECTOR_SIZE_X * SECTOR_SIZE_Z * VoxelWorld.CHUNK_Y];
    private volatile boolean lightingEnabled;
    private final int[] greedyMaskScratch = new int[CHUNK_AREA];
    // One chunk is meshed at a time; reuse the large material lookup table to
    // avoid allocating thousands of references every time the player moves.
    private final FaceBuilder[] chunkFaceBuilders = new FaceBuilder[TILE_COUNT];
    private final FaceBuilder[] chunkFaceBuilderTails = new FaceBuilder[TILE_COUNT];
    // A bounded pool avoids allocating FaceBuilder arrays for every streamed
    // chunk without retaining one builder for every possible tile/light pair.
    private final FaceBuilder[] freeFaceBuilders = new FaceBuilder[8];
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
    private final Texture2D[] aetherPortalFrames = new Texture2D[8];
    private final Texture2D[] endPortalFrames = new Texture2D[8];
    private final Texture2D[] crackFrames = new Texture2D[10];
    private final Appearance crackAppearance;
    private final Mesh crackMesh;
    private final VertexArray crackPositions;
    private final short[] crackPositionData = new short[] { 0,0,0, 0,1,0, 1,0,0, 1,1,0 };
    private final short[] crackUVData = new short[] { 0,16, 0,0, 16,16, 16,0 };
    private final Appearance portalAppearance;
    private final Appearance aetherPortalAppearance;
    private final Appearance endPortalAppearance;
    private final Texture2D worldFireTexture;
    private final Appearance worldFireAppearance;
    private int fireAnimationTick;
    private int fireAnimationFrame;
    private int portalFrameSize = 16;
    private int portalAnimationTick;
    private int endPortalAnimationTick;
    private int endPortalAnimationFrame;
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
    private Mesh[] heldEndPortalFrameMeshes;
    private VertexArray heldCreativeBlockUVArray;
    private final short[] heldCreativeBlockUV = new short[48];
    private final ParticleSystem particles;
    private final DroppedItemSystem droppedItems;
    private final MobSystem mobs;
    // Created only after the End has finished loading, keeping its M3G model out of the loading peak.
    private volatile EnderDragonSystem enderDragon;
    private boolean enderDragonLoadFailed;
    private volatile EndCrystalSystem endCrystals;
    private boolean endCrystalsLoadFailed;
    private int pendingEnderDragonDamage;
    private final float[] enderDragonExplosionPosition = new float[3];
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
        makeEndPortalFrames();
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
        aetherPortalAppearance = createPortalAppearance(aetherPortalFrames[0]);
        endPortalAppearance = createPortalAppearance(endPortalFrames[0]);
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
        itemSpriteModelScale.setScale(HELD_BLOCK_SCALE, HELD_BLOCK_SCALE, HELD_BLOCK_SCALE);
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
        scene.addChild(particles.root);
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

    void setLightingEnabled(boolean enabled) {
        if (lightingEnabled == enabled) return;
        lightingEnabled = enabled;
        // Rebuild resident sectors so the selected mode takes effect immediately.
        builtRevision = -1;
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
        // Block meshes use HELD_BLOCK_SCALE directly. Doors use the icon mesh,
        // and flowers are attached beneath the same icon transform, so avoid
        // applying the larger item-icon multiplier to those block models.
        float iconScale = itemId < Block.COUNT &&
                (Block.isDoor((byte)itemId) || Block.isFlower((byte)itemId)) ? 1.0f : 1.5f;
        itemSpriteIconScale.setScale(iconScale, iconScale, iconScale);
        if (itemId == (Block.END_PORTAL_FRAME & 255)) {
            if (heldEndPortalFrameMeshes != null)
                setMeshesVisible(heldEndPortalFrameMeshes, visible);
        } else if (itemId < Block.COUNT && Block.isDoor((byte)itemId)) {
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
            aetherPortalAppearance.setTexture(0, aetherPortalFrames[portalAnimationFrame]);
        }
        // One End animation frame every 8 game ticks (about 400 ms).
        if (++endPortalAnimationTick >= 8) {
            endPortalAnimationTick = 0;
            endPortalAnimationFrame = (endPortalAnimationFrame + 1) & 7;
            endPortalAppearance.setTexture(0, endPortalFrames[endPortalAnimationFrame]);
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

    /** Keep every cube-based held block on the same first-person scale. */
    private void setHeldBlockScale(Mesh mesh) {
        mesh.setScale(HELD_BLOCK_SCALE, HELD_BLOCK_SCALE, HELD_BLOCK_SCALE);
        mesh.setTranslation(HELD_BLOCK_OFFSET, HELD_BLOCK_OFFSET, HELD_BLOCK_OFFSET);
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
                setHeldBlockScale(mesh);
                mesh.setRenderingEnable(false);
                heldItemPitch.addChild(mesh);
                meshes[at++] = mesh;
                builders[i] = null;
            }
            heldItemMeshes[id] = meshes;
        }
        buildCreativeHeldBlock();
        buildHeldEndPortalFrame();
    }

    private void buildHeldEndPortalFrame() {
        FaceBuilder[] builders = new FaceBuilder[TILE_COUNT];
        FaceBuilder[] tails = new FaceBuilder[TILE_COUNT];
        addEndPortalFrameGeometry(builders, tails, 0, 0, 0, 0);
        int count = 0;
        for (int i = 0; i < builders.length; i++)
            if (builders[i] != null) count++;
        heldEndPortalFrameMeshes = new Mesh[count];
        int at = 0;
        for (int i = 0; i < builders.length; i++) {
            FaceBuilder builder = builders[i];
            if (builder == null) continue;
            Mesh mesh = makeMesh(builder, heldItemAppearance);
            setHeldBlockScale(mesh);
            mesh.setRenderingEnable(false);
            heldItemPitch.addChild(mesh);
            heldEndPortalFrameMeshes[at++] = mesh;
        }
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
        // FaceBuilder stores cube coordinates in 1/256 world units, like the
        // dedicated held-block meshes created through makeMesh().
        vertices.setPositions(positions, 1.0f / 256.0f, null);
        vertices.setTexCoords(0, heldCreativeBlockUVArray, 1.0f / 256.0f, null);
        vertices.setColors(colors);
        int[] strips = new int[] { 4,4,4,4,4,4 };
        heldCreativeBlockMesh = new Mesh(vertices, new TriangleStripArray(0, strips), heldItemAppearance);
        setHeldBlockScale(heldCreativeBlockMesh);
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
        if (enderDragon != null) {
            int bursts = enderDragon.consumeDeathExplosionBursts(enderDragonExplosionPosition);
            boolean finalBurst = enderDragon.consumeFinalExplosion();
            if (finalBurst) {
                particles.spawnDragonExplosion(
                        enderDragonExplosionPosition[0],
                        enderDragonExplosionPosition[1],
                        enderDragonExplosionPosition[2], true);
            } else {
                if (bursts > 3) bursts = 3;
                for (int i = 0; i < bursts; i++) {
                    float offset = i - (bursts - 1) * 0.5f;
                    particles.spawnDragonExplosion(
                            enderDragonExplosionPosition[0] + offset * 0.55f,
                            enderDragonExplosionPosition[1] + (i & 1) * 0.45f,
                            enderDragonExplosionPosition[2] - offset * 0.35f, false);
                }
            }
        }
        particles.update(world, yaw, pitch);
    }

    void spawnDroppedItem(int itemId, int x, int y, int z) {
        droppedItems.spawn(itemId, x, y, z);
    }

    boolean spawnDroppedStack(int itemId, int count, int x, int y, int z) {
        return droppedItems.spawnStack(itemId, count, x, y, z) >= 0;
    }

    void refreshDroppedItems() {
        droppedItems.refreshMeshes();
    }

    boolean spawnPrimedTnt(int x, int y, int z, int fuse) {
        return droppedItems.spawnPrimedTnt(x, y, z, fuse);
    }

    int updateDroppedItems(VoxelWorld world, int[] inventory, int[] pickupCapacity,
            PlayerPhysics physics, boolean creative) {
        int picked = droppedItems.update(world, inventory, pickupCapacity, physics, creative);
        if (droppedItems.consumeExplosion()) {
            mobs.damageFromExplosion(world, droppedItems.explosionX(),
                    droppedItems.explosionY(), droppedItems.explosionZ(), 4.0f);
            particles.spawnBlockDestroy(droppedItems.explosionX(), droppedItems.explosionY(),
                    droppedItems.explosionZ(), Block.atlasTile(Block.TNT));
        }
        return picked;
    }

    void onDimensionChanged(VoxelWorld world) {
        mobs.onDimensionChanged(world);
    }

    /**
     * True only after every horizontal sector of the player's current chunk
     * has been installed into the M3G scene. Merely queuing Java geometry is
     * not enough: the arrival terrain must actually be drawable.
     */
    boolean isChunkReadyAt(float x, float y, float z) {
        int cx = floorDiv((int)x, VoxelWorld.CHUNK_XZ);
        int cy = floorDiv((int)y, VoxelWorld.CHUNK_Y);
        int cz = floorDiv((int)z, VoxelWorld.CHUNK_XZ);
        if (cy < 0 || cy >= VoxelWorld.CHUNKS_Y) return false;
        int slot = (cy * MAX_STREAM_SPAN + floorMod(cz, MAX_STREAM_SPAN)) *
                MAX_STREAM_SPAN + floorMod(cx, MAX_STREAM_SPAN);
        Chunk chunk = chunks[slot];
        if (chunkWorldX[slot] != cx || chunkWorldY[slot] != cy ||
                chunkWorldZ[slot] != cz || !residentChunks[slot] ||
                dirtySectorMasks[slot] != 0 || dirtyChunks[slot] ||
                chunk == null || chunk.sectors == null) return false;
        // attachChunkSectorMeshes() publishes a sector before all of its M3G
        // meshes are installed. The dirty bit may already be cleared when its
        // Java snapshot is handed to the worker, so also reject every in-flight
        // stage for this exact chunk until replaceChunkSector() finishes it.
        for (int i = 0; i < chunk.sectors.length; i++)
            if (chunk.sectors[i] == null) return false;
        if (installingChunk != null && installingSlot == slot) return false;
        if (buildResultReady && readySlot == slot) return false;
        if (chunkSnapshotPreparing && chunkSnapshotX == cx &&
                chunkSnapshotY == cy && chunkSnapshotZ == cz) return false;
        if (chunkBuildWorkerBusy && chunkBuildRequestX == cx &&
                chunkBuildRequestY == cy && chunkBuildRequestZ == cz) return false;
        if (chunkBuildRequestPending && chunkBuildRequestX == cx &&
                chunkBuildRequestY == cy && chunkBuildRequestZ == cz) return false;
        return true;
    }

    int updateMobs(VoxelWorld world, float lookX, float lookY, float lookZ) {
        int damage = mobs.update(world, lookX, lookY, lookZ);
        // Dragon damage is produced by its render-thread animation and consumed
        // here on the gameplay thread. Never touch M3G from this method.
        synchronized (this) {
            damage += pendingEnderDragonDamage;
            pendingEnderDragonDamage = 0;
        }
        if (enderDragon != null) {
            boolean activatePortal = enderDragon.consumePortalActivationRequest();
            boolean deactivatePortal = enderDragon.consumePortalDeactivationRequest();
            if (activatePortal) world.activateEndExitPortal();
            else if (deactivatePortal) world.deactivateEndExitPortal();
        }
        return damage;
    }

    void updateEnderDragonVisual(VoxelWorld world) {
        if (endCrystals == null && !endCrystalsLoadFailed &&
                world.dimensionId == VoxelWorld.DIM_END) {
            try {
                endCrystals = new EndCrystalSystem(scene);
            } catch (Throwable unavailable) {
                endCrystalsLoadFailed = true;
                System.out.println("MeCraft: no se pudieron crear los cristales del End: " + unavailable);
            }
        }
        if (endCrystals != null) endCrystals.update(world);
        if (enderDragon == null && !enderDragonLoadFailed &&
                world.dimensionId == VoxelWorld.DIM_END) {
            try {
                enderDragon = new EnderDragonSystem(scene);
            } catch (Throwable unavailable) {
                // A model/texture failure must not terminate the gameplay loop.
                enderDragonLoadFailed = true;
                System.out.println("MeCraft: no se pudo crear el Ender Dragon: " + unavailable);
            }
        }
        if (enderDragon != null) {
            int damage = enderDragon.update(world);
            if (damage > 0) {
                synchronized (this) {
                    pendingEnderDragonDamage += damage;
                }
            }
        }
    }

    int enderDragonHealth() {
        return enderDragon == null ? -1 : enderDragon.health();
    }

    boolean spawnMobFromEgg(VoxelWorld world, int eggType, float x, float y, float z) {
        return mobs.spawnFromEgg(world, eggType, x, y, z);
    }

    boolean spawnBlaze(VoxelWorld world, float x, float y, float z) {
        return mobs.spawnBlaze(world, x, y, z);
    }

    /** Applies Indev's 24000-tick celestial curve; torch light is baked into voxel face colors. */
    void updateDayNight(VoxelWorld world) {
        if (world.dimensionId == VoxelWorld.DIM_AETHER) {
            currentDaylight = 0.96f;
            currentSkyColor = 0xFFBDEBFF;
            if (appliedSunlightIntensity != 0.96f) { sunlight.setIntensity(0.96f); appliedSunlightIntensity = 0.96f; }
            updateTorchLights(world);
            return;
        }
        if (world.dimensionId == VoxelWorld.DIM_END) {
            currentDaylight = 0.58f;
            currentSkyColor = 0xFF10051D;
            if (appliedSunlightIntensity != 0.62f) {
                sunlight.setIntensity(0.62f);
                appliedSunlightIntensity = 0.62f;
            }
            updateTorchLights(world);
            return;
        }
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
        // Warm orange light around the horizon at sunset instead of a cold,
        // prematurely dark blue transition.
        int sunsetDistance = Math.abs(world.dayTime - 10400);
        float sunset = 1.0f - sunsetDistance / 2400.0f;
        if (sunset < 0.0f) sunset = 0.0f;
        if (sunset > 1.0f) sunset = 1.0f;
        if (sunset > 0.0f)
            currentSkyColor = blendColor(currentSkyColor, 0xFFE99A55, sunset * 0.88f);
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
        if (endCrystals != null) {
            int crystal = endCrystals.hit(world, dirX, dirY, dirZ);
            if (crystal >= 0) {
                float x = InfdevTerrain.endPillarX(crystal) + 0.5f;
                float y = InfdevTerrain.endPillarTopY(world, crystal) + 1.75f;
                float z = InfdevTerrain.endPillarZ(crystal) + 0.5f;
                world.explode(x, y, z, 3.0f, droppedItems);
                world.igniteExplosionFire(x, z, 3.0f);
                mobs.damageFromExplosion(world, x, y, z, 3.0f);
                particles.spawnDragonExplosion(x, y, z, false);
                particles.spawnDragonExplosion(x + 0.25f, y + 0.25f, z - 0.25f, false);
                particles.spawnBlockDestroy((int)x, (int)y, (int)z,
                        Block.atlasTile(Block.OBSIDIAN));
                return 2;
            }
        }
        if (enderDragon != null) {
            int result = enderDragon.hit(world, dirX, dirY, dirZ, damage);
            if (result >= 0) return result;
        }
        return mobs.hit(world, dirX, dirY, dirZ, damage);
    }

    boolean shootPlayerArrow(VoxelWorld world, float pitch) {
        return mobs.shootPlayerArrow(world, pitch);
    }

    boolean shootPlayerItem(VoxelWorld world, float pitch, int kind) {
        return mobs.shootPlayerItem(world, pitch, kind);
    }

    boolean shootPlayerEnderPearl(VoxelWorld world, float pitch) {
        return mobs.shootPlayerEnderPearl(world, pitch);
    }

    boolean consumeEnderPearlTeleport() {
        return mobs.consumePearlTeleport();
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
        urgentSector = (localZ / SECTOR_SIZE_Z) * SECTORS_X + localX / SECTOR_SIZE_X;

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
                                    Math.max(x - 1, chunkX * VoxelWorld.CHUNK_XZ),
                                    Math.min(x + 1, (chunkX + 1) * VoxelWorld.CHUNK_XZ - 1),
                                    Math.max(z - 1, chunkZ * VoxelWorld.CHUNK_XZ),
                                    Math.min(z + 1, (chunkZ + 1) * VoxelWorld.CHUNK_XZ - 1));
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
                            markChunkDirtyRegion(slot,
                                    Math.max(world.bulkChangedMinX - 1, chunkX * VoxelWorld.CHUNK_XZ),
                                    Math.min(world.bulkChangedMaxX + 1, (chunkX + 1) * VoxelWorld.CHUNK_XZ - 1),
                                    Math.max(world.bulkChangedMinZ - 1, chunkZ * VoxelWorld.CHUNK_XZ),
                                    Math.min(world.bulkChangedMaxZ + 1, (chunkZ + 1) * VoxelWorld.CHUNK_XZ - 1));
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
        byte changedNow = world.get(x, y, z);
        boolean torchChanged = world.changedOldId == Block.TORCH || changedNow == Block.TORCH ||
                world.changedOldId == Block.LAVA || changedNow == Block.LAVA ||
                world.changedOldId == Block.FIRE || changedNow == Block.FIRE ||
                world.changedOldId == Block.FURNACE || changedNow == Block.FURNACE;
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
                                Math.max(x - (torchChanged ? LAVA_LIGHT_RADIUS : 1), chunkX * VoxelWorld.CHUNK_XZ),
                                Math.min(x + (torchChanged ? LAVA_LIGHT_RADIUS : 1), (chunkX + 1) * VoxelWorld.CHUNK_XZ - 1),
                                Math.max(z - (torchChanged ? LAVA_LIGHT_RADIUS : 1), chunkZ * VoxelWorld.CHUNK_XZ),
                                Math.min(z + (torchChanged ? LAVA_LIGHT_RADIUS : 1), (chunkZ + 1) * VoxelWorld.CHUNK_XZ - 1));
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
        int base = slot * SECTOR_COUNT;
        for (int sector = 0; sector < SECTOR_COUNT; sector++)
            if ((mask & (1 << sector)) != 0) chunkSectorInvalidationVersion[base + sector]++;
    }

    private void markChunkDirtyRegion(int slot, int minX, int maxX, int minZ, int maxZ) {
        int x0 = chunkWorldX[slot] * VoxelWorld.CHUNK_XZ;
        int z0 = chunkWorldZ[slot] * VoxelWorld.CHUNK_XZ;
        int localMinX = minX - x0, localMaxX = maxX - x0;
        int localMinZ = minZ - z0, localMaxZ = maxZ - z0;
        int firstX = localMinX / SECTOR_SIZE_X;
        int lastX = localMaxX / SECTOR_SIZE_X;
        int firstZ = localMinZ / SECTOR_SIZE_Z;
        int lastZ = localMaxZ / SECTOR_SIZE_Z;
        int mask = 0;
        for (int sz = firstZ; sz <= lastZ; sz++)
            for (int sx = firstX; sx <= lastX; sx++)
                mask |= 1 << (sz * SECTORS_X + sx);
        if (!residentChunks[slot]) mask = ALL_SECTORS_MASK;
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

    private void releasePreviousChunkSector(int i, int sector) {
        Chunk parent = chunks[i];
        if (parent == null || parent.sectors == null) return;
        Chunk previous = parent.sectors[sector];
        if (previous == null) return;
        if (previous.meshRoot != null) {
            parent.meshRoot.removeChild(previous.meshRoot);
        } else if (previous.meshes != null) {
            for (int m = 0; m < previous.meshBuildAt; m++)
                if (previous.meshes[m] != null) parent.meshRoot.removeChild(previous.meshes[m]);
        }
        recycleChunkBuilders(previous);
        parent.sectors[sector] = null;
    }

    private Chunk ensureChunkRoot(int i, int cx, int cy, int cz) {
        Chunk parent = chunks[i];
        if (parent != null) return parent;
        parent = new Chunk();
        parent.sectors = new Chunk[SECTOR_COUNT];
        parent.meshRoot = new Group();
        parent.meshRoot.setRenderingEnable(chunkFrustumVisible[i]);
        parent.worldX = cx * VoxelWorld.CHUNK_XZ;
        parent.worldY = cy * VoxelWorld.CHUNK_Y;
        parent.worldZ = cz * VoxelWorld.CHUNK_XZ;
        parent.renderingEnabled = chunkFrustumVisible[i];
        chunks[i] = parent;
        terrain.addChild(parent.meshRoot);
        return parent;
    }

    /** Attach only the mesh just created this frame; never flush a full sector at once. */
    private void attachChunkSectorMeshes(int i, int cx, int cy, int cz, int sector,
                                         Chunk replacement) {
        Chunk parent = ensureChunkRoot(i, cx, cy, cz);
        int start = replacement.meshAttachedCount;
        int end = replacement.meshBuildAt;
        try {
            for (int m = start; m < end; m++) {
                Mesh mesh = replacement.meshes[m];
                if (mesh != null) {
                    parent.meshRoot.addChild(mesh);
                    replacement.meshAttachedCount = m + 1;
                }
            }
        } catch (OutOfMemoryError exhausted) {
            for (int m = replacement.meshAttachedCount - 1; m >= start; m--) {
                try { parent.meshRoot.removeChild(replacement.meshes[m]); }
                catch (RuntimeException ignored) { }
            }
            replacement.meshAttachedCount = start;
            throw exhausted;
        } catch (RuntimeException failed) {
            for (int m = replacement.meshAttachedCount - 1; m >= start; m--) {
                try { parent.meshRoot.removeChild(replacement.meshes[m]); }
                catch (RuntimeException ignored) { }
            }
            replacement.meshAttachedCount = start;
            throw failed;
        }
        parent.sectors[sector] = replacement;
    }

    private void replaceChunkSector(int i, int cx, int cy, int cz, int sector, Chunk replacement) {
        Chunk parent = ensureChunkRoot(i, cx, cy, cz);

        Chunk previous = parent.sectors[sector];
        // Meshes are normally attached incrementally as they are created.
        // This catches an empty/new sector or any not-yet-attached final mesh.
        if (replacement != null && replacement.meshes != null &&
                replacement.meshAttachedCount < replacement.meshBuildAt) {
            attachChunkSectorMeshes(i, cx, cy, cz, sector, replacement);
        }

        if (previous != null && previous != replacement) {
            if (previous.meshRoot != null) {
                parent.meshRoot.removeChild(previous.meshRoot);
            } else if (previous.meshes != null) {
                // Installed sector meshes are direct children of the shared chunk root.
                for (int m = 0; m < previous.meshBuildAt; m++)
                    if (previous.meshes[m] != null) parent.meshRoot.removeChild(previous.meshes[m]);
            }
            recycleChunkBuilders(previous);
        }
        parent.sectors[sector] = replacement;
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
        urgentChunkX = urgentChunkY = urgentChunkZ = Integer.MIN_VALUE;
        urgentSector = -1;
        chunkLightSnapshotValid = false;
        chunkVoxelSnapshotValid = false;
        chunkVoxelSnapshotFull = false;
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
        // A removed chunk must be fully rebuilt on re-entry. The all-sector mask
        // rejects stale worker/install results without discarding a reusable snapshot.
        dirtyChunks[i] = true;
        dirtySectorMasks[i] = ALL_SECTORS_MASK;
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

        int firstChunkY = world.dimensionId == VoxelWorld.DIM_END ?
                InfdevTerrain.END_ISLAND_MIN_Y / VoxelWorld.CHUNK_Y : 0;
        int lastChunkY = world.dimensionId == VoxelWorld.DIM_END ?
                InfdevTerrain.END_RENDER_MAX_Y / VoxelWorld.CHUNK_Y : VoxelWorld.CHUNKS_Y - 1;

        // Apply the actual camera frustum to already-resident chunks too; previously
        // the frustum only filtered new builds while old meshes kept rendering.
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (!residentChunks[i]) continue;
            if (world.dimensionId == VoxelWorld.DIM_END &&
                    (chunkWorldY[i] < firstChunkY || chunkWorldY[i] > lastChunkY)) {
                removeChunk(i);
                continue;
            }
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
        // The End island only occupies Y=25..33. Do not rank, snapshot or
        // stream the six vertical chunk layers that can only contain air.
        for (int cy = firstChunkY; cy <= lastChunkY; cy++) {
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
                        // A slot removed from the scene must rebuild all sectors when it re-enters.
                        if (dirtySectorMasks[i] == 0) {
                            dirtySectorMasks[i] = ALL_SECTORS_MASK;
                            dirtyChunks[i] = true;
                        }
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
                            // Among visible candidates, finish terrain in front of the
                            // camera before spending time on chunks behind the player.
                            float depth = dx * forwardX + dy * forwardY + dz * forwardZ;
                            if (depth < 0.0f) distance += CHUNK_AREA * 4.0f;
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
            urgentSector = -1;
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
                markChunkDirtyMask(installingSlot, 1 << installingSector);
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
            if (completed != null && epoch == streamEpoch && slotMatches) {
                installingChunk = completed;
                installingSlot = slot; installingX = cx; installingY = cy; installingZ = cz;
                installingEpoch = epoch;
                installingSector = readySector;
                installingInvalidationVersion = readyInvalidationVersion;
                installingPreviousSectorReleased = false;
                completed.worldX = cx * VoxelWorld.CHUNK_XZ;
                completed.worldY = cy * VoxelWorld.CHUNK_Y;
                completed.worldZ = cz * VoxelWorld.CHUNK_XZ;
            } else {
                if (completed != null) recycleChunkBuilders(completed);
                if (slotMatches) {
                    markChunkDirtyMask(slot, 1 << readySector);
                    if (completed == null)
                        chunkBuildRetryAfterMillis = System.currentTimeMillis() + 2000L;
                }
            }
        }
        if (installingChunk != null) {
            boolean slotMatches = installingSlot >= 0 && installingSlot < CACHE_SIZE &&
                    chunkWorldX[installingSlot] == installingX &&
                    chunkWorldY[installingSlot] == installingY &&
                    chunkWorldZ[installingSlot] == installingZ;
            if (installingEpoch != streamEpoch || !slotMatches) {
                recycleChunkBuilders(installingChunk);
                if (slotMatches) markChunkDirtyMask(installingSlot, 1 << installingSector);
                installingChunk = null;
            } else {
                try {
                    // Player edits get queue priority, not a larger per-frame
                    // allocation burst. Keep the same HTC frame budget for every chunk.
                    int meshBudget = MAX_CHUNK_MESHES_PER_SYNC;
                    int firstNewMesh = installingChunk.meshBuildAt;
                    boolean chunkFinished = finishChunkMeshStep(installingChunk, meshBudget);
                    if (!installingPreviousSectorReleased && installingChunk.meshBuildAt > firstNewMesh) {
                        releasePreviousChunkSector(installingSlot, installingSector);
                        installingPreviousSectorReleased = true;
                    }
                    if (installingChunk.meshBuildAt > firstNewMesh) {
                        attachChunkSectorMeshes(installingSlot, installingX, installingY, installingZ,
                                installingSector, installingChunk);
                    }
                    if (chunkFinished) {
                        replaceChunkSector(installingSlot, installingX, installingY, installingZ,
                                installingSector, installingChunk);
                        installingChunk = null;
                    }
                } catch (OutOfMemoryError exhausted) {
                    recycleChunkBuilders(installingChunk);
                    markChunkDirtyMask(installingSlot, 1 << installingSector);
                    installingChunk = null;
                    chunkBuildRetryAfterMillis = System.currentTimeMillis() + 2000L;
                } catch (RuntimeException failed) {
                    recycleChunkBuilders(installingChunk);
                    markChunkDirtyMask(installingSlot, 1 << installingSector);
                    installingChunk = null;
                    chunkBuildRetryAfterMillis = System.currentTimeMillis() + 500L;
                }
            }
        }
        if (chunkSnapshotPreparing) {
            advanceChunkBuildSnapshot(world);
        }
        if (!chunkSnapshotPreparing && best >= 0 &&
                (!residentChunks[best] || dirtySectorMasks[best] != 0) &&
                !chunkBuildWorkerBusy && !buildResultReady && installingChunk == null &&
                System.currentTimeMillis() >= chunkBuildRetryAfterMillis) {
            beginChunkBuildSnapshot(world, best, chunkWorldX[best],
                    chunkWorldY[best], chunkWorldZ[best]);
            if (chunkSnapshotPreparing) advanceChunkBuildSnapshot(world);
        }
    }

    private boolean isSectorEmpty(VoxelWorld world, int cx, int cy, int cz, int sector) {
        int x0 = cx * VoxelWorld.CHUNK_XZ +
                (sector % SECTORS_X) * SECTOR_SIZE_X;
        int z0 = cz * VoxelWorld.CHUNK_XZ +
                (sector / SECTORS_X) * SECTOR_SIZE_Z;
        int y0 = cy * VoxelWorld.CHUNK_Y;
        for (int y = 0; y < VoxelWorld.CHUNK_Y; y++) {
            for (int z = 0; z < SECTOR_SIZE_Z; z++) {
                for (int x = 0; x < SECTOR_SIZE_X; x++) {
                    if (world.get(x0 + x, y0 + y, z0 + z) != Block.AIR) return false;
                }
            }
        }
        return true;
    }

    private void beginChunkBuildSnapshot(VoxelWorld world, int slot,
                                         int cx, int cy, int cz) {
        chunkSnapshotSector = nextDirtySector(slot, cx, cz);
        if (!world.prepareProceduralChunksForSection(cx, cz)) {
            dirtyChunks[slot] = dirtySectorMasks[slot] != 0;
            return;
        }
        if (isSectorEmpty(world, cx, cy, cz, chunkSnapshotSector)) {
            // Empty sectors in any dimension need no worker snapshot, lighting scan or M3G mesh.
            // Mark the sector resident so the next sector can be considered.
            dirtySectorMasks[slot] &= ~(1 << chunkSnapshotSector);
            Chunk empty = new Chunk();
            replaceChunkSector(slot, cx, cy, cz, chunkSnapshotSector, empty);
            return;
        }
        chunkSnapshotPreparing = true;
        boolean cachedFullSnapshot = chunkVoxelSnapshotValid && chunkVoxelSnapshotFull &&
                chunkVoxelSnapshotX == cx && chunkVoxelSnapshotY == cy &&
                chunkVoxelSnapshotZ == cz && chunkVoxelSnapshotVersion == chunkInvalidationVersion[slot];
        chunkSnapshotFull = !residentChunks[slot] || cachedFullSnapshot;
        int sectorX = (chunkSnapshotSector % SECTORS_X) * SECTOR_SIZE_X;
        int sectorZ = (chunkSnapshotSector / SECTORS_X) * SECTOR_SIZE_Z;
        if (chunkSnapshotFull) {
            chunkSnapshotVoxelMinX = -1;
            chunkSnapshotVoxelMaxX = VoxelWorld.CHUNK_XZ;
            chunkSnapshotVoxelMinZ = -1;
            chunkSnapshotVoxelMaxZ = VoxelWorld.CHUNK_XZ;
            chunkSnapshotMetadataMinX = 0;
            chunkSnapshotMetadataMaxX = VoxelWorld.CHUNK_XZ - 1;
            chunkSnapshotMetadataMinZ = 0;
            chunkSnapshotMetadataMaxZ = VoxelWorld.CHUNK_XZ - 1;
        } else {
            // One voxel of horizontal halo plus the vertical padded layer is
            // enough for this sector's six face tests and portal-axis lookup.
            chunkSnapshotVoxelMinX = sectorX == 0 ? -1 : sectorX - 1;
            chunkSnapshotVoxelMaxX = sectorX + SECTOR_SIZE_X == VoxelWorld.CHUNK_XZ ?
                    VoxelWorld.CHUNK_XZ : sectorX + SECTOR_SIZE_X;
            chunkSnapshotVoxelMinZ = sectorZ == 0 ? -1 : sectorZ - 1;
            chunkSnapshotVoxelMaxZ = sectorZ + SECTOR_SIZE_Z == VoxelWorld.CHUNK_XZ ?
                    VoxelWorld.CHUNK_XZ : sectorZ + SECTOR_SIZE_Z;
            chunkSnapshotMetadataMinX = sectorX;
            chunkSnapshotMetadataMaxX = sectorX + SECTOR_SIZE_X - 1;
            chunkSnapshotMetadataMinZ = sectorZ;
            chunkSnapshotMetadataMaxZ = sectorZ + SECTOR_SIZE_Z - 1;
            // A partial write invalidates the reusable full-chunk snapshot.
            chunkVoxelSnapshotValid = false;
        }
        chunkSnapshotCursor = cachedFullSnapshot ? ChunkBuildSnapshot.PAD_AREA *
                (VoxelWorld.CHUNK_Y + 2) +
                VoxelWorld.CHUNK_XZ * VoxelWorld.CHUNK_Y * VoxelWorld.CHUNK_XZ : 0;
        chunkSnapshotVoxelX = chunkSnapshotVoxelMinX;
        chunkSnapshotVoxelY = -1;
        chunkSnapshotVoxelZ = chunkSnapshotVoxelMinZ;
        chunkSnapshotMetadataX = chunkSnapshotMetadataMinX;
        chunkSnapshotMetadataY = 0;
        chunkSnapshotMetadataZ = chunkSnapshotMetadataMinZ;
        chunkSnapshotSlot = slot;
        chunkSnapshotX = cx;
        chunkSnapshotY = cy;
        chunkSnapshotZ = cz;
        chunkSnapshotEpoch = streamEpoch;
        chunkSnapshotInvalidationVersion = chunkSectorInvalidationVersion[
                slot * SECTOR_COUNT + chunkSnapshotSector];
        chunkSnapshotChunkInvalidationVersion = chunkInvalidationVersion[slot];
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
                chunkBuildWorkerBusy || buildResultReady || installingChunk != null) {
            chunkSnapshotPreparing = false;
            if (slotMatches) dirtyChunks[slot] = dirtySectorMasks[slot] != 0;
            return;
        }

        ChunkBuildSnapshot snapshot = chunkBuildSnapshot;
        int x0 = chunkSnapshotX * VoxelWorld.CHUNK_XZ;
        int y0 = chunkSnapshotY * VoxelWorld.CHUNK_Y;
        int z0 = chunkSnapshotZ * VoxelWorld.CHUNK_XZ;
        int voxelWidth = chunkSnapshotVoxelMaxX - chunkSnapshotVoxelMinX + 1;
        int voxelDepth = chunkSnapshotVoxelMaxZ - chunkSnapshotVoxelMinZ + 1;
        int voxelCount = voxelWidth * voxelDepth * (VoxelWorld.CHUNK_Y + 2);
        int metadataWidth = chunkSnapshotMetadataMaxX - chunkSnapshotMetadataMinX + 1;
        int metadataDepth = chunkSnapshotMetadataMaxZ - chunkSnapshotMetadataMinZ + 1;
        int metadataCount = metadataWidth * metadataDepth * VoxelWorld.CHUNK_Y;
        int total = voxelCount + metadataCount;
        // Spend a larger bounded slice on the block the player just edited;
        // keep off-screen/background terrain work at the smaller budget.
        int snapshotBudget = isUrgentChunk(chunkSnapshotX, chunkSnapshotY, chunkSnapshotZ) ?
                MAX_URGENT_CHUNK_SNAPSHOT_CELLS_PER_SYNC :
                MAX_CHUNK_SNAPSHOT_CELLS_PER_SYNC;
        int end = chunkSnapshotCursor + snapshotBudget;
        if (end > total) end = total;

        while (chunkSnapshotCursor < end) {
            if (chunkSnapshotCursor < voxelCount) {
                chunkSnapshotCursor++;
                int storageIndex = (chunkSnapshotVoxelY + 1) * ChunkBuildSnapshot.PAD_AREA +
                        (chunkSnapshotVoxelZ + 1) * (VoxelWorld.CHUNK_XZ + 2) +
                        chunkSnapshotVoxelX + 1;
                snapshot.voxels[storageIndex] = world.get(x0 + chunkSnapshotVoxelX,
                        y0 + chunkSnapshotVoxelY, z0 + chunkSnapshotVoxelZ);
                if (++chunkSnapshotVoxelX > chunkSnapshotVoxelMaxX) {
                    chunkSnapshotVoxelX = chunkSnapshotVoxelMinX;
                    if (++chunkSnapshotVoxelZ > chunkSnapshotVoxelMaxZ) {
                        chunkSnapshotVoxelZ = chunkSnapshotVoxelMinZ;
                        chunkSnapshotVoxelY++;
                    }
                }
            } else {
                chunkSnapshotCursor++;
                int metadataIndex = (chunkSnapshotMetadataY * VoxelWorld.CHUNK_XZ +
                        chunkSnapshotMetadataZ) * VoxelWorld.CHUNK_XZ +
                        chunkSnapshotMetadataX;
                snapshot.metadata[metadataIndex] = (byte)world.metadataAt(
                        x0 + chunkSnapshotMetadataX, y0 + chunkSnapshotMetadataY,
                        z0 + chunkSnapshotMetadataZ);
                if (++chunkSnapshotMetadataX > chunkSnapshotMetadataMaxX) {
                    chunkSnapshotMetadataX = chunkSnapshotMetadataMinX;
                    if (++chunkSnapshotMetadataZ > chunkSnapshotMetadataMaxZ) {
                        chunkSnapshotMetadataZ = chunkSnapshotMetadataMinZ;
                        chunkSnapshotMetadataY++;
                    }
                }
            }
        }
        if (chunkSnapshotCursor < total) return;
        if (chunkSnapshotFull &&
                chunkInvalidationVersion[slot] == chunkSnapshotChunkInvalidationVersion) {
            chunkVoxelSnapshotValid = true;
            chunkVoxelSnapshotFull = true;
            chunkVoxelSnapshotX = chunkSnapshotX;
            chunkVoxelSnapshotY = chunkSnapshotY;
            chunkVoxelSnapshotZ = chunkSnapshotZ;
            chunkVoxelSnapshotVersion = chunkSnapshotChunkInvalidationVersion;
        } else {
            chunkVoxelSnapshotValid = false;
            chunkVoxelSnapshotFull = false;
        }

        int lightRevision = world.lightRevision;
        boolean cachedLights = chunkLightSnapshotValid &&
                chunkLightSnapshotX == chunkSnapshotX &&
                chunkLightSnapshotY == chunkSnapshotY &&
                chunkLightSnapshotZ == chunkSnapshotZ &&
                chunkLightSnapshotRevision == lightRevision;
        if (!lightingEnabled) {
            snapshot.lightCount = 0;
        } else if (cachedLights) {
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
                    MAX_CHUNK_LIGHT_SCAN_CELLS_PER_SYNC,
                    snapshot.lightScanState);
            if (!lightsReady) return;
            snapshot.lightCount = snapshot.lightScanState[1];
        }
        snapshot.dimensionId = world.dimensionId;
        float skyBrightness = 0.20f + currentDaylight * 0.80f;
        if (world.dimensionId == VoxelWorld.DIM_NETHER || world.nether) skyBrightness = 0.35f;
        if (world.dimensionId == VoxelWorld.DIM_END) skyBrightness = 0.28f;
        if (skyBrightness > 1.0f) skyBrightness = 1.0f;
        // Keep a gentle ambient floor and enough headroom for cave light to
        // fade over several blocks instead of collapsing at the sector edge.
        snapshot.daylightLevel = 2 + (int)(10.0f * skyBrightness);
        if (snapshot.daylightLevel > 12) snapshot.daylightLevel = 12;

        // Edits made while copying leave this sector dirty. Finish this build,
        // then rebuild once from the final world state instead of restarting the
        // entire snapshot on every mining tick.
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
                dirtyChunks[slot] = dirtySectorMasks[slot] != 0;
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
            if (chunkSectorInvalidationVersion[slot * SECTOR_COUNT + chunkSnapshotSector] ==
                    chunkSnapshotInvalidationVersion)
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
                } catch (Throwable failed) {
                    // A failed thread start must never leave streaming permanently busy.
                    chunkBuildThread = null;
                    chunkBuildWorkerBusy = false;
                    chunkBuildRequestPending = false;
                    markChunkDirtyMask(slot, 1 << chunkSnapshotSector);
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
            } catch (Throwable failed) {
                // Do not allocate a fallback Chunk here: this path is also used
                // after OutOfMemoryError. A null result is retried by sync().
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
        // Store the mesh in build order; sync() attaches it to the sector root
        // immediately after this frame's bounded M3G allocation.
        chunk.meshes[chunk.meshBuildAt++] = mesh;
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

            int batchCount = 0;
            while (batchCount < CHUNK_BATCH_SEGMENTS &&
                    chunk.meshBuildIndex < chunk.faceBuilders.length) {
                if (chunk.meshBuildCurrent == null) {
                    chunk.meshBuildCurrent = chunk.faceBuilders[chunk.meshBuildIndex];
                    if (chunk.meshBuildCurrent == null) {
                        chunk.meshBuildIndex++;
                        continue;
                    }
                }
                FaceBuilder builder = chunk.meshBuildCurrent;
                chunk.meshBuildCurrent = builder.next;
                chunk.faceBuilders[chunk.meshBuildIndex] = chunk.meshBuildCurrent;
                builder.next = null;
                if (chunk.meshBuildCurrent == null) chunk.meshBuildIndex++;
                if (builder.vertexCount > 0) {
                    chunkBatchBuilders[batchCount++] = builder;
                } else {
                    recycleFaceBuilder(builder);
                }
            }
            if (batchCount > 0) {
                try {
                    Mesh mesh = makeChunkBatchMesh(chunkBatchBuilders, batchCount);
                    mesh.setTranslation(chunk.worldX, chunk.worldY, chunk.worldZ);
                    appendChunkMesh(chunk, mesh);
                } finally {
                    // Builders have been detached from the chunk's tile lists.
                    // Always return them to the bounded pool, even if M3G allocation fails.
                    for (int i = 0; i < batchCount; i++) {
                        recycleFaceBuilder(chunkBatchBuilders[i]);
                        chunkBatchBuilders[i] = null;
                    }
                }
                budget--;
                installed++;
            }
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

    /**
     * Build one M3G Mesh from up to three bounded material segments.
     * Each segment keeps its own index buffer and Appearance, while all of
     * them share one VertexBuffer. This cuts native Mesh/VertexBuffer creation
     * and scene-graph attachment calls without increasing any FaceBuilder cap.
     */
    private Mesh makeChunkBatchMesh(FaceBuilder[] builders, int count) {
        int totalVertices = 0;
        for (int i = 0; i < count; i++) totalVertices += builders[i].vertexCount;

        int vertexOffset = 0;
        IndexBuffer[] indices = new IndexBuffer[count];
        Appearance[] appearances = new Appearance[count];
        for (int i = 0; i < count; i++) {
            FaceBuilder builder = builders[i];
            System.arraycopy(builder.p, 0, chunkBatchPositions, vertexOffset * 3,
                    builder.vertexCount * 3);
            System.arraycopy(builder.uv, 0, chunkBatchTexCoords, vertexOffset * 2,
                    builder.vertexCount * 2);
            System.arraycopy(builder.c, 0, chunkBatchColors, vertexOffset * 4,
                    builder.vertexCount * 4);

            indices[i] = chunkQuadIndices(vertexOffset, builder.faceCount);
            appearances[i] = tileAppearance(builder.tile);
            vertexOffset += builder.vertexCount;
        }

        VertexArray positions = new VertexArray(totalVertices, 3, 2);
        positions.set(0, totalVertices, chunkBatchPositions);
        VertexArray texCoords = new VertexArray(totalVertices, 2, 2);
        texCoords.set(0, totalVertices, chunkBatchTexCoords);
        VertexArray colors = new VertexArray(totalVertices, 4, 1);
        colors.set(0, totalVertices, chunkBatchColors);
        VertexBuffer vertices = new VertexBuffer();
        // FaceBuilder stores world positions and atlas UVs in 1/256 units.
        // Keep the same scale as the original per-material chunk mesh path.
        vertices.setPositions(positions, 1.0f / 256.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 16.0f, null);
        vertices.setColors(colors);
        return new Mesh(vertices, indices, appearances);
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


    private void addPartialBlockGeometry(FaceBuilder[] builders, FaceBuilder[] tails,
            int x, int y, int z, byte id, int meta, int light) {
        if (id == Block.WOOL && Block.isBedMeta(meta)) {
            // Beta 1.8.1 mattress uses the six original head/foot terrain tiles.
            // Bed atlas side tiles already include the wooden feet: do not add
            // separate plank legs or they will appear as duplicated geometry.
            addPartialBedBox(builders, tails, x, y, z, meta, 0, 0, 0, 16, 9, 16,
                    light, true, true, true, true, true, true);
            return;
        }
        if (Block.isSlab(id)) {
            addPartialBox(builders, tails, id, x, y, z,
                    toSixteenths(Block.boxMinX(id,meta,0)), toSixteenths(Block.boxMinY(id,meta,0)),
                    toSixteenths(Block.boxMinZ(id,meta,0)), toSixteenths(Block.boxMaxX(id,meta,0)),
                    toSixteenths(Block.boxMaxY(id,meta,0)), toSixteenths(Block.boxMaxZ(id,meta,0)),
                    light, true, true, true, true, true, true);
            return;
        }
        int facing = meta & 3;
        int minX = toSixteenths(Block.boxMinX(id,meta,1));
        int maxX = toSixteenths(Block.boxMaxX(id,meta,1));
        int minZ = toSixteenths(Block.boxMinZ(id,meta,1));
        int maxZ = toSixteenths(Block.boxMaxZ(id,meta,1));
        if ((meta & Block.STAIR_TOP) == 0) {
            // Normal stair: full lower half, plus the raised half on its facing side.
            addPartialBox(builders,tails,id,x,y,z,0,0,0,16,8,16,light,false,true,true,true,true,true);
            int openX0 = facing == 3 ? 8 : 0;
            int openX1 = facing == 1 ? 8 : 16;
            int openZ0 = facing == 0 ? 8 : 0;
            int openZ1 = facing == 2 ? 8 : 16;
            addPartialFace(builders,tails,id,x,y,z,openX0,8,openZ0,openX1,8,openZ1,0,light,0,0,0,0);
            addPartialBox(builders,tails,id,x,y,z,minX,8,minZ,maxX,16,maxZ,
                    light,true,false,true,true,true,true);
        } else {
            // Upside-down stair: full upper half, with the lower step at the facing side.
            addPartialBox(builders,tails,id,x,y,z,0,8,0,16,16,16,light,true,false,true,true,true,true);
            int openX0 = facing == 3 ? 8 : 0;
            int openX1 = facing == 1 ? 8 : 16;
            int openZ0 = facing == 0 ? 8 : 0;
            int openZ1 = facing == 2 ? 8 : 16;
            addPartialFace(builders,tails,id,x,y,z,openX0,8,openZ0,openX1,8,openZ1,5,light,0,0,0,0);
            addPartialBox(builders,tails,id,x,y,z,minX,0,minZ,maxX,8,maxZ,
                    light,false,true,true,true,true,true);
        }
    }

    private int toSixteenths(float value) { return (int)(value * 16.0f + 0.5f); }

    private void addPartialBox(FaceBuilder[] builders, FaceBuilder[] tails, byte id,
            int x,int y,int z,int x0,int y0,int z0,int x1,int y1,int z1,int light,
            boolean top,boolean bottom,boolean west,boolean east,boolean north,boolean south) {
        if (top) addPartialFace(builders,tails,id,x,y,z,x0,y0,z0,x1,y1,z1,0,light,0,0,0,0);
        if (west) addPartialFace(builders,tails,id,x,y,z,x0,y0,z0,x1,y1,z1,1,light,0,0,0,0);
        if (east) addPartialFace(builders,tails,id,x,y,z,x0,y0,z0,x1,y1,z1,2,light,0,0,0,0);
        if (north) addPartialFace(builders,tails,id,x,y,z,x0,y0,z0,x1,y1,z1,3,light,0,0,0,0);
        if (south) addPartialFace(builders,tails,id,x,y,z,x0,y0,z0,x1,y1,z1,4,light,0,0,0,0);
        if (bottom) addPartialFace(builders,tails,id,x,y,z,x0,y0,z0,x1,y1,z1,5,light,0,0,0,0);
    }

    /**
     * Beta 1.8.1 bed block model: side walls run from y=0 to 9/16,
     * while the underside quad is inset to y=3/16 (RenderBlocks offset).
     * The atlas side tiles already draw the feet; no extra leg geometry.
     */
    private void addPartialBedBox(FaceBuilder[] builders, FaceBuilder[] tails,
            int x,int y,int z,int meta,int x0,int y0,int z0,int x1,int y1,int z1,int light,
            boolean top,boolean bottom,boolean west,boolean east,boolean north,boolean south) {
        if (top) addPartialBedFace(builders,tails,x,y,z,meta,x0,y0,z0,x1,y1,z1,0,light);
        if (west) addPartialBedFace(builders,tails,x,y,z,meta,x0,y0,z0,x1,y1,z1,1,light);
        if (east) addPartialBedFace(builders,tails,x,y,z,meta,x0,y0,z0,x1,y1,z1,2,light);
        if (north) addPartialBedFace(builders,tails,x,y,z,meta,x0,y0,z0,x1,y1,z1,3,light);
        if (south) addPartialBedFace(builders,tails,x,y,z,meta,x0,y0,z0,x1,y1,z1,4,light);
        if (bottom) addPartialBedFace(builders,tails,x,y,z,meta,x0,3,z0,x1,3,z1,5,light);
    }

    private void addPartialBedFace(FaceBuilder[] builders, FaceBuilder[] tails,
            int x,int y,int z,int meta,int x0,int y0,int z0,int x1,int y1,int z1,
            int face,int light) {
        int tile = Block.bedFaceTile(meta, face);
        if (tile < 0) return;
        addPartialBedFaceTile(builders,tails,x,y,z,x0,y0,z0,x1,y1,z1,
                face,light,tile,Block.bedFaceFlip(meta,face),
                face == 0 ? (Block.bedFacing(meta) & 3) : -1);
    }

    private void addPartialFace(FaceBuilder[] builders, FaceBuilder[] tails, byte id,
            int x,int y,int z,int x0,int y0,int z0,int x1,int y1,int z1,
            int face,int light,int unused0,int unused1,int unused2,int unused3) {
        addPartialFaceTile(builders,tails,x,y,z,x0,y0,z0,x1,y1,z1,
                face,light,Block.faceTile(id,face),false);
    }

    private void addPartialBedFaceTile(FaceBuilder[] builders, FaceBuilder[] tails,
            int x,int y,int z,int x0,int y0,int z0,int x1,int y1,int z1,
            int face,int light,int tile,boolean flipU,int topFacing) {
        FaceBuilder builder = tails[tile];
        if (builder == null) {
            builder = acquireFaceBuilder(tile);
            builders[tile] = builder;
            tails[tile] = builder;
        } else if (builder.vertexCount + 4 > MAX_FACE_BUILDER_VERTICES) {
            FaceBuilder next = acquireFaceBuilder(tile);
            builder.next = next;
            tails[tile] = next;
            builder = next;
        }
        int px0=x*16+x0, py0=y*16+y0, pz0=z*16+z0;
        int px1=x*16+x1, py1=y*16+y1, pz1=z*16+z1;
        builder.addBetaBedQuad(px0,py0,pz0,px1,py1,pz1,face,light,flipU,topFacing);
    }

    private void addPartialFaceTile(FaceBuilder[] builders, FaceBuilder[] tails,
            int x,int y,int z,int x0,int y0,int z0,int x1,int y1,int z1,
            int face,int light,int tile) {
        addPartialFaceTile(builders,tails,x,y,z,x0,y0,z0,x1,y1,z1,face,light,tile,false);
    }

    private void addPartialFaceTile(FaceBuilder[] builders, FaceBuilder[] tails,
            int x,int y,int z,int x0,int y0,int z0,int x1,int y1,int z1,
            int face,int light,int tile,boolean flipU) {
        FaceBuilder builder = tails[tile];
        if (builder == null) {
            builder = acquireFaceBuilder(tile);
            builders[tile] = builder;
            tails[tile] = builder;
        } else if (builder.vertexCount + 4 > MAX_FACE_BUILDER_VERTICES) {
            FaceBuilder next = acquireFaceBuilder(tile);
            builder.next = next;
            tails[tile] = next;
            builder = next;
        }
        int px0 = x * 16 + x0, py0 = y * 16 + y0, pz0 = z * 16 + z0;
        int px1 = x * 16 + x1, py1 = y * 16 + y1, pz1 = z * 16 + z1;
        // Expand each partial face by 1/256 block along its edges. Without
        // this tiny overlap, adjacent slab/stair faces can leave hairline gaps
        // from fixed-point rounding at shared edges.
        if (face == 0 || face == 5) {
            px0--; px1++; pz0--; pz1++;
        } else if (face == 1 || face == 2) {
            py0--; py1++; pz0--; pz1++;
        } else {
            px0--; px1++; py0--; py1++;
        }
        // Also push exposed outer side planes just past the block boundary so
        // they do not lose the depth test against neighboring full cubes.
        if (face == 1 && x0 == 0) px0--;
        else if (face == 2 && x1 == 16) px1++;
        else if (face == 3 && z0 == 0) pz0--;
        else if (face == 4 && z1 == 16) pz1++;
        builder.addSubQuad(px0, py0, pz0, px1, py1, pz1, face, light, flipU);
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
        int sectorX = (sector % SECTORS_X) * SECTOR_SIZE_X;
        int sectorZ = (sector / SECTORS_X) * SECTOR_SIZE_Z;

        // Copy voxels once into a compact linear buffer. Special geometry is
        // collected in the same pass, avoiding repeated coordinate arithmetic.
        int nonAirBlocks = 0;
        int haloMinX = sectorX > 0 ? sectorX - 1 : 0;
        int haloMaxX = sectorX + SECTOR_SIZE_X < VoxelWorld.CHUNK_XZ ?
                sectorX + SECTOR_SIZE_X : VoxelWorld.CHUNK_XZ - 1;
        int haloMinZ = sectorZ > 0 ? sectorZ - 1 : 0;
        int haloMaxZ = sectorZ + SECTOR_SIZE_Z < VoxelWorld.CHUNK_XZ ?
                sectorZ + SECTOR_SIZE_Z : VoxelWorld.CHUNK_XZ - 1;
        // Only copy this sector and a one-voxel horizontal halo. The halo supplies
        // neighbor visibility tests at internal sector edges without touching the
        // neighboring sectors' scratch data.
        for (int localY = 0; localY < VoxelWorld.CHUNK_Y; localY++) {
            int y = y0 + localY;
            for (int localZ = haloMinZ; localZ <= haloMaxZ; localZ++) {
                int z = z0 + localZ;
                int row = localY * CHUNK_AREA + localZ * VoxelWorld.CHUNK_XZ;
                for (int localX = haloMinX; localX <= haloMaxX; localX++) {
                    byte id = snapshot.block(localX, localY, localZ);
                    int voxelIndex = row + localX;
                    chunkVoxelScratch[voxelIndex] = id;
                    if (localX < sectorX || localX >= sectorX + SECTOR_SIZE_X ||
                            localZ < sectorZ || localZ >= sectorZ + SECTOR_SIZE_Z) continue;
                    chunkLightScratch[voxelIndex] = (byte)(id == Block.LAVA ? 14 : torchLightAt(voxelIndex));
                    if (id == Block.AIR) continue;
                    nonAirBlocks++;
                    if (id == Block.PORTAL || id == Block.END_PORTAL) {
                        if (portals == null) portals = acquirePortalBuilder();
                        portals.add(localX, localY, localZ,
                                snapshot.portalAxisAt(localX, localY, localZ), portalFrameSize,
                                id == Block.END_PORTAL ? 1 :
                                (((snapshot.metadata(localX, localY, localZ) & 4) == 0 &&
                                  (snapshot.metadata(localX, localY, localZ) & 2) != 0) ? 1 : 0),
                                id == Block.END_PORTAL,
                                (snapshot.metadata(localX, localY, localZ) & 4) != 0);
                    } else if (id == Block.END_PORTAL_FRAME) {
                        addEndPortalFrameGeometry(builders, builderTails, localX, localY, localZ,
                                snapshot.metadata(localX, localY, localZ));
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

        // Prepare light before both partial and greedy geometry consume the scratch map.
        prepareChunkLightMap(snapshot, x0, y0, z0, x1, y1, z1, sectorX, sectorZ);

        // Partial blocks bypass greedy cube meshing and emit their exact 1/16 geometry.
        for (int ly = 0; ly < VoxelWorld.CHUNK_Y; ly++) {
            for (int lz = sectorZ; lz < sectorZ + SECTOR_SIZE_Z; lz++) {
                for (int lx = sectorX; lx < sectorX + SECTOR_SIZE_X; lx++) {
                    int vi = (ly * VoxelWorld.CHUNK_XZ + lz) * VoxelWorld.CHUNK_XZ + lx;
                    byte partialId = chunkVoxelScratch[vi];
                    int meta = snapshot.metadata(lx, ly, lz);
                    if (!Block.isPartial(partialId, meta)) continue;
                    addPartialBlockGeometry(builders, builderTails, lx, ly, lz,
                            partialId, meta, chunkLightScratch[vi] & 255);
                }
            }
        }


        // Mine64-style greedy meshing: combine adjacent visible faces on each
        // chunk plane when their atlas tile matches. Tile textures repeat, so
        // the merged rectangles preserve the original per-block texture scale.
        int[] mask = greedyMaskScratch;
        for (int face = 0; face < 6; face++) {
            int slices = face == 0 || face == 5 ? VoxelWorld.CHUNK_Y :
                    (face == 1 || face == 2 ? SECTOR_SIZE_X : SECTOR_SIZE_Z);
            int width = face == 0 || face == 5 ? SECTOR_SIZE_X :
                    (face == 1 || face == 2 ? SECTOR_SIZE_Z : SECTOR_SIZE_X);
            int height = face == 0 || face == 5 ? SECTOR_SIZE_Z : VoxelWorld.CHUNK_Y;
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
                        if (id != Block.AIR && !Block.isPartial(id, snapshot.metadata(x - x0, y - y0, z - z0)) && id != Block.RAIL && id != Block.END_PORTAL_FRAME && !Block.isFlower(id) && !Block.isDoor(id) &&
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
                            if (Block.faceVisible(id, neighbor, snapshot.metadata(nx - x0, ny - y0, nz - z0))) {
                                int metadata = snapshot.metadata(x - x0, y - y0, z - z0);
                                int tile = renderWorldTile(snapshot.dimensionId, id, face, metadata);
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

        // The Aether reuses the normal grass block and atlas, changing only
        // its top-face vertex tint. This keeps inventory/Overworld grass intact.
        if (snapshot.dimensionId == VoxelWorld.DIM_AETHER) {
            // Give Aether foliage dedicated material IDs so its source texture
            // is recolored directly instead of multiplying a pastel tint over
            // the already-dark Overworld texture.
            for (FaceBuilder grassTop = builders[0]; grassTop != null;
                    grassTop = grassTop.next)
                grassTop.tile = 254;
            for (FaceBuilder leaves = builders[34]; leaves != null; leaves = leaves.next)
                leaves.tile = 255;
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

    private void addEndPortalFrameGeometry(FaceBuilder[] builders, FaceBuilder[] tails,
            int x, int y, int z, int metadata) {
        int x0 = x * 16, x1 = x0 + 16, z0 = z * 16, z1 = z0 + 16;
        int y0 = y * 16, top = y0 + 13;
        addFrameQuad(builders, tails, 158, x0,y0+13,z0, x1,y0+13,z1, 0);
        addFrameQuad(builders, tails, 175, x0,y0,z0, x1,y0,z1, 5);
        addFrameQuad(builders, tails, 159, x0,y0,z0, x1,top,z0, 3);
        addFrameQuad(builders, tails, 159, x0,y0,z1, x1,top,z1, 4);
        addFrameQuad(builders, tails, 159, x0,y0,z0, x0,top,z1, 1);
        addFrameQuad(builders, tails, 159, x1,y0,z0, x1,top,z1, 2);
        if ((metadata & 4) != 0) {
            int ex0=x0+5, ex1=x0+11, ez0=z0+5, ez1=z0+11, ey0=top, ey1=y0+16;
            addFrameQuad(builders,tails,174,ex0,ey1,ez0,ex1,ey1,ez1,0);
            addFrameQuad(builders,tails,174,ex0,ey0,ez0,ex1,ey1,ez0,3);
            addFrameQuad(builders,tails,174,ex0,ey0,ez1,ex1,ey1,ez1,4);
            addFrameQuad(builders,tails,174,ex0,ey0,ez0,ex0,ey1,ez1,1);
            addFrameQuad(builders,tails,174,ex1,ey0,ez0,ex1,ey1,ez1,2);
        }
    }

    private void addFrameQuad(FaceBuilder[] builders, FaceBuilder[] tails, int tile,
            int x0,int y0,int z0,int x1,int y1,int z1,int face) {
        FaceBuilder b = tails[tile];
        if (b == null) {
            b = acquireFaceBuilder(tile);
            builders[tile] = b;
            tails[tile] = b;
        } else if (b.vertexCount + 4 > MAX_FACE_BUILDER_VERTICES) {
            FaceBuilder next = acquireFaceBuilder(tile);
            b.next = next;
            tails[tile] = next;
            b = next;
        }
        b.addSubQuad(x0,y0,z0,x1,y1,z1,face);
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
        return new Mesh(vertices, chunkQuadIndices(vertexCount / 4), worldFireAppearance);
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
        return chunkQuadIndices(0, faceCount);
    }

    private TriangleStripArray chunkQuadIndices(int firstVertex, int faceCount) {
        for (int i = 0; i < chunkQuadIndexBuffers.length; i++) {
            if (chunkQuadIndexBuffers[i] != null &&
                    chunkQuadIndexFirstVertices[i] == firstVertex &&
                    chunkQuadIndexFaceCounts[i] == faceCount)
                return chunkQuadIndexBuffers[i];
        }
        int[] strips = new int[faceCount];
        for (int i = 0; i < faceCount; i++) strips[i] = 4;
        TriangleStripArray indices = new TriangleStripArray(firstVertex, strips);
        int slot = chunkQuadIndexCursor++ & (chunkQuadIndexBuffers.length - 1);
        chunkQuadIndexFirstVertices[slot] = firstVertex;
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
        vertices.setPositions(positions, 1.0f / 256.0f, null);
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
        return new Mesh(vertices, chunkQuadIndices(data.vertexCount / 4), appearance);
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
        return new Mesh(vertices, chunkQuadIndices(vertexCount / 4), appearance);
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
        return new Mesh(vertices, chunkQuadIndices(vertexCount / 4),
                data.endPortal ? endPortalAppearance : (data.aetherPortal ? aetherPortalAppearance : portalAppearance));
    }

    private void makeEndPortalFrames() {
        final int size = 16;
        int[] pixels = new int[size * size];
        final int[] starColors = {
            0xFF66D9FF, 0xFF8C7BFF, 0xFF45E6C4,
            0xFFD86BFF, 0xFF6F9BFF, 0xFFFF83C8
        };
        for (int frame = 0; frame < 8; frame++) {
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFF000000;
            // Sparse one-pixel stars, with only a tiny drift between frames.
            for (int star = 0; star < 18; star++) {
                int baseX = (star * 7 + (star / 3) * 3) & 15;
                int baseY = (star * 11 + (star / 4) * 5) & 15;
                int x = (baseX + ((star & 1) == 0 ? frame / 4 : 0)) & 15;
                int y = (baseY + ((star % 3) == 0 ? frame / 4 : 0)) & 15;
                pixels[y * size + x] = starColors[star % starColors.length];
            }
            Image image = Image.createRGBImage(pixels, size, size, true);
            Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
            texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
            texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
            endPortalFrames[frame] = texture;
        }
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
            int[] bluePixels = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i], alpha = c >>> 24;
                if (alpha == 0) { bluePixels[i] = 0; continue; }
                int light = (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
                int red = 28 + light / 6, green = 105 + light / 2, blue = 165 + light / 3;
                if (red > 255) red = 255;
                if (green > 255) green = 255;
                if (blue > 255) blue = 255;
                bluePixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
            Image blueImage = Image.createRGBImage(bluePixels, portalFrameSize, portalFrameSize, true);
            Texture2D blueTexture = new Texture2D(new Image2D(Image2D.RGBA, blueImage));
            blueTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
            blueTexture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
            aetherPortalFrames[frame] = blueTexture;
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
        if (tile == 254 || tile == 255) {
            int sourceTile = tile == 254 ? 0 : 34;
            terrainImage.getRGB(pixels, 0, 16, (sourceTile & 15) * 16,
                    (sourceTile >> 4) * 16, 16, 16);
            AlphaTint.apply(pixels, tile == 254 ?
                    AlphaTint.AETHER_GRASS_TOP : AlphaTint.AETHER_LEAVES);
        } else if (tile >= Block.WOOL_TINT_TILE_START) {
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
        // Glass uses real alpha blending instead of an opaque atlas pass.
        // Keep it partially transparent while retaining the original texture.
        appearance = createBlockAppearance(texture,
                tile == Block.atlasTile(Block.WATER) || tile == Block.atlasTile(Block.GLASS));
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
                                      int x1, int y1, int z1, int sectorX, int sectorZ) {
        // Low-end devices skip all per-voxel propagation unless the player opts in.
        if (!lightingEnabled) {
            int light = snapshot.daylightLevel;
            for (int y = 0; y < VoxelWorld.CHUNK_Y; y++) {
                int row = y * CHUNK_AREA;
                for (int z = sectorZ; z < sectorZ + SECTOR_SIZE_Z; z++)
                    for (int x = sectorX; x < sectorX + SECTOR_SIZE_X; x++)
                        chunkLightScratch[row + z * VoxelWorld.CHUNK_XZ + x] = (byte)light;
            }
            return;
        }
        // Reuse the scratch map safely: each sector build starts from darkness.
        for (int y = 0; y < VoxelWorld.CHUNK_Y; y++) {
            int row = y * CHUNK_AREA;
            for (int z = sectorZ; z < sectorZ + SECTOR_SIZE_Z; z++)
                for (int x = sectorX; x < sectorX + SECTOR_SIZE_X; x++)
                    chunkLightScratch[row + z * VoxelWorld.CHUNK_XZ + x] = 0;
        }
        // Sunlight is directional: it enters from above and stops at opaque blocks.
        // The extra top voxel in the snapshot handles chunk boundaries without
        // querying the world from the background mesh worker.
        int sky = snapshot.daylightLevel;
        for (int z = sectorZ; z < sectorZ + SECTOR_SIZE_Z; z++) {
            for (int x = sectorX; x < sectorX + SECTOR_SIZE_X; x++) {
                boolean exposed = !blocksLight(snapshot.block(x, VoxelWorld.CHUNK_Y, z));
                for (int y = VoxelWorld.CHUNK_Y - 1; y >= 0; y--) {
                    int index = y * CHUNK_AREA + z * VoxelWorld.CHUNK_XZ + x;
                    byte id = chunkVoxelScratch[index];
                    // The exposed top surface itself receives sunlight; the block
                    // then occludes every cell below it in this column.
                    if (exposed) chunkLightScratch[index] = (byte)sky;
                    if (blocksLight(id)) exposed = false;
                }
            }
        }

        // Spread sky light sideways through windows and doorways. A compact
        // sector-local BFS visits each transparent voxel at most once.
        propagateSkyLight(sectorX, sectorZ);

        // Emitters use a bounded breadth-first flood instead of tracing a 3D ray
        // to every candidate voxel. Each voxel enters the queue at most once per
        // source, so the work is linear in the sector volume and has no ray cost.
        for (int source = 0; source < snapshot.lightCount; source++) {
            int sx = snapshot.lightX[source] - x0;
            int sy = snapshot.lightY[source] - y0;
            int sz = snapshot.lightZ[source] - z0;
            int radius = snapshot.lightRadius[source] & 255;
            int multiplier = snapshot.lavaSource[source] ? 2 : 3;
            if (radius <= 0 || sy < 0 || sy >= VoxelWorld.CHUNK_Y) continue;

            // A source can sit in an adjacent sector. Enter this sector at its
            // nearest edge, reducing its starting intensity by the distance.
            int seedX = sx < sectorX ? sectorX :
                    (sx >= sectorX + SECTOR_SIZE_X ? sectorX + SECTOR_SIZE_X - 1 : sx);
            int seedZ = sz < sectorZ ? sectorZ :
                    (sz >= sectorZ + SECTOR_SIZE_Z ? sectorZ + SECTOR_SIZE_Z - 1 : sz);
            int seedDistance = Math.abs(seedX - sx) + Math.abs(seedZ - sz);
            if (seedDistance > radius) continue;
            int level = (radius + 1 - seedDistance) * multiplier;
            if (level > 14) level = 14;
            int seedIndex = sy * CHUNK_AREA + seedZ * VoxelWorld.CHUNK_XZ + seedX;
            if ((chunkLightScratch[seedIndex] & 255) >= level) continue;
            chunkLightScratch[seedIndex] = (byte)level;

            int area = SECTOR_SIZE_X * SECTOR_SIZE_Z;
            int head = 0, tail = 0;
            chunkSkyQueue[tail++] = (short)(sy * area +
                    (seedZ - sectorZ) * SECTOR_SIZE_X + seedX - sectorX);
            while (head < tail) {
                int packed = chunkSkyQueue[head++] & 0xFFFF;
                int y = packed / area;
                int rem = packed - y * area;
                int z = sectorZ + rem / SECTOR_SIZE_X;
                int x = sectorX + rem % SECTOR_SIZE_X;
                int from = y * CHUNK_AREA + z * VoxelWorld.CHUNK_XZ + x;
                int nextLight = (chunkLightScratch[from] & 255) - multiplier;
                if (nextLight <= 0) continue;
                for (int direction = 0; direction < 6; direction++) {
                    int nx = x, ny = y, nz = z;
                    if (direction == 0) ny++;
                    else if (direction == 1) ny--;
                    else if (direction == 2) nx++;
                    else if (direction == 3) nx--;
                    else if (direction == 4) nz++;
                    else nz--;
                    if (nx < sectorX || nx >= sectorX + SECTOR_SIZE_X ||
                            nz < sectorZ || nz >= sectorZ + SECTOR_SIZE_Z ||
                            ny < 0 || ny >= VoxelWorld.CHUNK_Y) continue;
                    int target = ny * CHUNK_AREA + nz * VoxelWorld.CHUNK_XZ + nx;
                    if ((chunkLightScratch[target] & 255) >= nextLight) continue;
                    chunkLightScratch[target] = (byte)nextLight;
                    if (!blocksLight(chunkVoxelScratch[target]) &&
                            tail < chunkSkyQueue.length) {
                        int targetPacked = ny * area +
                                (nz - sectorZ) * SECTOR_SIZE_X + nx - sectorX;
                        chunkSkyQueue[tail++] = (short)targetPacked;
                    }
                }
            }
        }
    }

    private void propagateSkyLight(int sectorX, int sectorZ) {
        int area = SECTOR_SIZE_X * SECTOR_SIZE_Z;
        int volume = area * VoxelWorld.CHUNK_Y;
        int head = 0, tail = 0;

        // Reset only the active sector. The queue stores each sector voxel once,
        // so its fixed capacity is sufficient even when many columns are sunlit.
        for (int i = 0; i < volume; i++) chunkSkyQueued[i] = 0;

        // Do not enqueue every directly sunlit voxel: most of those cells have
        // nothing darker to illuminate. Seed only the lit boundary of a darker
        // transparent region (cave mouths, windows and door openings).
        for (int y = 0; y < VoxelWorld.CHUNK_Y; y++) {
            int row = y * CHUNK_AREA;
            for (int z = sectorZ; z < sectorZ + SECTOR_SIZE_Z; z++) {
                for (int x = sectorX; x < sectorX + SECTOR_SIZE_X; x++) {
                    int index = row + z * VoxelWorld.CHUNK_XZ + x;
                    int light = chunkLightScratch[index] & 255;
                    if (light <= 2 || blocksLight(chunkVoxelScratch[index])) continue;
                    int packed = y * area + (z - sectorZ) * SECTOR_SIZE_X + x - sectorX;
                    if (hasDarkerSkyNeighbor(x, y, z, sectorX, sectorZ, light)) {
                        chunkSkyQueued[packed] = 1;
                        chunkSkyQueue[tail++] = (short)packed;
                    }
                }
            }
        }

        while (head < tail) {
            int packed = chunkSkyQueue[head++] & 0xFFFF;
            int y = packed / area;
            int rem = packed - y * area;
            int z = sectorZ + rem / SECTOR_SIZE_X;
            int x = sectorX + rem % SECTOR_SIZE_X;
            int sourceIndex = y * CHUNK_AREA + z * VoxelWorld.CHUNK_XZ + x;
            int nextLight = (chunkLightScratch[sourceIndex] & 255) - 1;
            if (nextLight <= 1) continue;

            for (int direction = 0; direction < 6; direction++) {
                int nx = x, ny = y, nz = z;
                if (direction == 0) ny++;
                else if (direction == 1) ny--;
                else if (direction == 2) nx++;
                else if (direction == 3) nx--;
                else if (direction == 4) nz++;
                else nz--;
                if (nx < sectorX || nx >= sectorX + SECTOR_SIZE_X ||
                        nz < sectorZ || nz >= sectorZ + SECTOR_SIZE_Z ||
                        ny < 0 || ny >= VoxelWorld.CHUNK_Y) continue;

                int targetIndex = ny * CHUNK_AREA + nz * VoxelWorld.CHUNK_XZ + nx;
                if ((chunkLightScratch[targetIndex] & 255) >= nextLight) continue;
                chunkLightScratch[targetIndex] = (byte)nextLight;
                if (!blocksLight(chunkVoxelScratch[targetIndex])) {
                    int targetPacked = ny * area +
                            (nz - sectorZ) * SECTOR_SIZE_X + nx - sectorX;
                    if (chunkSkyQueued[targetPacked] == 0) {
                        chunkSkyQueued[targetPacked] = 1;
                        // Every packed voxel is queued at most once, hence tail
                        // cannot exceed volume and no propagation is silently lost.
                        chunkSkyQueue[tail++] = (short)targetPacked;
                    }
                }
            }
        }
    }

    private boolean hasDarkerSkyNeighbor(int x, int y, int z,
            int sectorX, int sectorZ, int light) {
        if (y + 1 < VoxelWorld.CHUNK_Y &&
                !blocksLight(chunkVoxelScratch[(y + 1) * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x]) &&
                (chunkLightScratch[(y + 1) * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x] & 255) < light - 1) return true;
        if (y > 0 &&
                !blocksLight(chunkVoxelScratch[(y - 1) * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x]) &&
                (chunkLightScratch[(y - 1) * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x] & 255) < light - 1) return true;
        if (x + 1 < sectorX + SECTOR_SIZE_X &&
                !blocksLight(chunkVoxelScratch[y * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x + 1]) &&
                (chunkLightScratch[y * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x + 1] & 255) < light - 1) return true;
        if (x > sectorX &&
                !blocksLight(chunkVoxelScratch[y * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x - 1]) &&
                (chunkLightScratch[y * CHUNK_AREA +
                        z * VoxelWorld.CHUNK_XZ + x - 1] & 255) < light - 1) return true;
        if (z + 1 < sectorZ + SECTOR_SIZE_Z &&
                !blocksLight(chunkVoxelScratch[y * CHUNK_AREA +
                        (z + 1) * VoxelWorld.CHUNK_XZ + x]) &&
                (chunkLightScratch[y * CHUNK_AREA +
                        (z + 1) * VoxelWorld.CHUNK_XZ + x] & 255) < light - 1) return true;
        if (z > sectorZ &&
                !blocksLight(chunkVoxelScratch[y * CHUNK_AREA +
                        (z - 1) * VoxelWorld.CHUNK_XZ + x]) &&
                (chunkLightScratch[y * CHUNK_AREA +
                        (z - 1) * VoxelWorld.CHUNK_XZ + x] & 255) < light - 1) return true;
        return false;
    }

    private boolean blocksLight(byte id) {
        if (id == Block.AIR || id == Block.FIRE || id == Block.TORCH ||
                id == Block.GLASS || Block.isFlower(id) || Block.isFluid(id) ||
                Block.isDoor(id)) return false;
        // Leaves attenuate light rather than behaving like a full opaque cube.
        return id != Block.LEAVES;
    }

    /** O(1) block-quantized light lookup from the precomputed chunk map. */
    private int torchLightAt(int localIndex) {
        return chunkLightScratch[localIndex] & 255;
    }

    private int renderWorldTile(int dimensionId, byte id, int face, int metadata) {
        if (id == Block.LAVA) return 234;
        if (dimensionId == VoxelWorld.DIM_END && id == Block.STONE) return 121;
        if (dimensionId == VoxelWorld.DIM_NETHER) {
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
        // Shared scene root for a resident chunk and its incrementally attached sector meshes.
        Group meshRoot;
        Chunk[] sectors;
        FaceBuilder[] faceBuilders;
        FlowerBuilder flowers;
        FireBuilder fires;
        PortalBuilder portals;
        boolean renderingEnabled;
        int worldX, worldY, worldZ;
        int meshBuildIndex, meshBuildPhase, meshBuildAt;
        int meshAttachedCount;
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
        int dimensionId;
        int daylightLevel = 3;
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
        boolean endPortal;
        boolean aetherPortal;

        void reset() { vertexCount = 0; endPortal = false; aetherPortal = false; }

        void add(int x, int y, int z, int axis, int frameSize, int portalType,
                 boolean isEndPortal, boolean isAetherPortal) {
            if (isEndPortal) endPortal = true;
            if (isAetherPortal) aetherPortal = true;
            ensure(vertexCount + 4);
            int x0 = x * 256, x1 = x0 + 256;
            int y0 = y * 256, y1 = y0 + 256;
            int z0 = z * 256, z1 = z0 + 256;
            int u1 = frameSize;
            if (portalType == 1) {
                // End Portal surface sits level with the End Portal Frame's
                // 13/16-high top, forming the classic horizontal 3x3 plane.
                int plane = y0 + 208;
                vertex(x0,plane,z0,0,frameSize); vertex(x0,plane,z1,0,0);
                vertex(x1,plane,z0,u1,frameSize); vertex(x1,plane,z1,u1,0);
            } else if (axis == 0) {
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
            // Six smooth brightness bands. The lower bands retain visible
            // texture detail, while daylight is brighter without clipping.
            int band;
            if (lightLevel <= 2) band = 0;
            else if (lightLevel <= 4) band = 1;
            else if (lightLevel <= 6) band = 2;
            else if (lightLevel <= 8) band = 3;
            else if (lightLevel <= 10) band = 4;
            else band = 5;

            int[] top = { 55, 82, 109, 136, 169, 214 };
            int[] side = { 44, 66, 88, 111, 139, 178 };
            int[] bottom = { 32, 49, 66, 83, 105, 137 };
            if (face == 0) return top[band];
            if (face == 5) return bottom[band];
            return side[band];
        }

        private void vertex(int x, int y, int z, int u, int v, int shade) {
            int pi = vertexCount * 3;
            p[pi] = (short)(x * 256); p[pi + 1] = (short)(y * 256); p[pi + 2] = (short)(z * 256);
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            int ci = vertexCount * 4;
            c[ci] = (byte)shade; c[ci + 1] = (byte)shade; c[ci + 2] = (byte)shade;
            // Keep vertex alpha opaque: the glass texture itself contains the
            // alpha mask, so only its designated transparent pixels show through.
            c[ci + 3] = (byte)(tile == Block.atlasTile(Block.WATER) ? 160 : 255);
            vertexCount++;
        }

        void addSubQuad(int x0,int y0,int z0,int x1,int y1,int z1,int face) {
            addSubQuad(x0,y0,z0,x1,y1,z1,face,0);
        }

        void addSubQuad(int x0,int y0,int z0,int x1,int y1,int z1,int face,int lightLevel) {
            addSubQuad(x0,y0,z0,x1,y1,z1,face,lightLevel,false);
        }

        void addSubQuad(int x0,int y0,int z0,int x1,int y1,int z1,int face,int lightLevel,boolean flipU) {
            addSubQuad(x0,y0,z0,x1,y1,z1,face,lightLevel,flipU,-1);
        }

        void addSubQuad(int x0,int y0,int z0,int x1,int y1,int z1,int face,int lightLevel,boolean flipU,int bedTopFacing) {
            int firstVertex = vertexCount;
            ensure(vertexCount + 4);
            int u0=(tile & 15)*16+1, v0=(tile >> 4)*16+1, u1=u0+14, v1=v0+14;
            int shade=litShade(face, lightLevel);
            if(face==0){vertexSub(x0,y1,z0,u0,v1,shade);vertexSub(x0,y1,z1,u0,v0,shade);vertexSub(x1,y1,z0,u1,v1,shade);vertexSub(x1,y1,z1,u1,v0,shade);}
            else if(face==1){vertexSub(x0,y0,z0,u1,v1,shade);vertexSub(x0,y0,z1,u0,v1,shade);vertexSub(x0,y1,z0,u1,v0,shade);vertexSub(x0,y1,z1,u0,v0,shade);}
            else if(face==2){vertexSub(x1,y0,z0,u0,v1,shade);vertexSub(x1,y1,z0,u0,v0,shade);vertexSub(x1,y0,z1,u1,v1,shade);vertexSub(x1,y1,z1,u1,v0,shade);}
            else if(face==3){vertexSub(x0,y0,z0,u0,v1,shade);vertexSub(x0,y1,z0,u0,v0,shade);vertexSub(x1,y0,z0,u1,v1,shade);vertexSub(x1,y1,z0,u1,v0,shade);}
            else if(face==4){vertexSub(x0,y0,z1,u1,v1,shade);vertexSub(x1,y0,z1,u0,v1,shade);vertexSub(x0,y1,z1,u1,v0,shade);vertexSub(x1,y1,z1,u0,v0,shade);}
            else {vertexSub(x0,y0,z0,u0,v0,shade);vertexSub(x1,y0,z0,u1,v0,shade);vertexSub(x0,y0,z1,u0,v1,shade);vertexSub(x1,y0,z1,u1,v1,shade);}
            if (bedTopFacing >= 0 && face == 0) {
                // Exact top-face UV corner permutation from Beta 1.8.1
                // RenderBlocks.renderBlockBed(), in this builder's vertex order:
                // (x0,z0), (x0,z1), (x1,z0), (x1,z1).
                int[][] map = {
                    {u0,v0, u1,v0, u0,v1, u1,v1},
                    {u1,v0, u1,v1, u0,v0, u0,v1},
                    {u1,v1, u0,v1, u1,v0, u0,v0},
                    {u0,v1, u0,v0, u1,v1, u1,v0}
                };
                int[] m=map[bedTopFacing & 3];
                for(int k=0;k<4;k++) {
                    uv[(firstVertex+k)*2]=(short)m[k*2];
                    uv[(firstVertex+k)*2+1]=(short)m[k*2+1];
                }
            }
            if (flipU) {
                int uvMin = u0 + u1;
                for (int i = firstVertex; i < firstVertex + 4; i++) {
                    int ti = i * 2;
                    uv[ti] = (short)(uvMin - uv[ti]);
                }
            }
            faceCount++;
        }

        /**
         * UV projection copied from Beta 1.8.1 RenderBlocks.renderBlockBed and
         * its six face helpers. Coordinates are in 1/256 atlas pixels; unlike
         * ordinary cube faces, the bed uses the full 16px tile span. Beta's
         * 15.99 edge epsilon is below this renderer's integer-pixel UV precision.
         */
        void addBetaBedQuad(int x0,int y0,int z0,int x1,int y1,int z1,
                int face,int lightLevel,boolean flipU,int bedFacing) {
            ensure(vertexCount + 4);
            int u0=(tile & 15)*16, u1=u0+16;
            int v0=(tile >> 4)*16, v1=v0+16;
            int vTop=v0+7, vBottom=v1;
            int shade=litShade(face,lightLevel);
            if(face==0) {
                // Beta's bed top face rotates its UV corners for directions 0..3.
                int[][] map = {
                    {u0,v0, u1,v0, u0,v1, u1,v1},
                    {u1,v0, u1,v1, u0,v0, u0,v1},
                    {u1,v1, u0,v1, u1,v0, u0,v0},
                    {u0,v1, u0,v0, u1,v1, u1,v0}
                };
                int[] m=map[bedFacing & 3];
                vertexSub(x0,y1,z0,m[0],m[1],shade);
                vertexSub(x0,y1,z1,m[2],m[3],shade);
                vertexSub(x1,y1,z0,m[4],m[5],shade);
                vertexSub(x1,y1,z1,m[6],m[7],shade);
            } else if(face==1) {
                // x-min: RenderBlocks.renderNorthFace vertex order remapped to this builder.
                int a=flipU?u1:u0, b=flipU?u0:u1;
                vertexSub(x0,y0,z0,a,vBottom,shade);
                vertexSub(x0,y0,z1,b,vBottom,shade);
                vertexSub(x0,y1,z0,a,vTop,shade);
                vertexSub(x0,y1,z1,b,vTop,shade);
            } else if(face==2) {
                // x-max: RenderBlocks.renderSouthFace.
                int a=flipU?u1:u0, b=flipU?u0:u1;
                vertexSub(x1,y0,z0,b,vBottom,shade);
                vertexSub(x1,y1,z0,b,vTop,shade);
                vertexSub(x1,y0,z1,a,vBottom,shade);
                vertexSub(x1,y1,z1,a,vTop,shade);
            } else if(face==3) {
                // z-min: RenderBlocks.renderEastFace.
                int a=flipU?u1:u0, b=flipU?u0:u1;
                vertexSub(x0,y0,z0,b,vBottom,shade);
                vertexSub(x0,y1,z0,b,vTop,shade);
                vertexSub(x1,y0,z0,a,vBottom,shade);
                vertexSub(x1,y1,z0,a,vTop,shade);
            } else if(face==4) {
                // z-max: RenderBlocks.renderWestFace.
                int a=flipU?u1:u0, b=flipU?u0:u1;
                vertexSub(x0,y0,z1,a,vBottom,shade);
                vertexSub(x1,y0,z1,b,vBottom,shade);
                vertexSub(x0,y1,z1,a,vTop,shade);
                vertexSub(x1,y1,z1,b,vTop,shade);
            } else {
                // Beta bed underside is a separate quad at y=3/16.
                vertexSub(x0,y0,z0,u0,v0,shade);
                vertexSub(x1,y0,z0,u1,v0,shade);
                vertexSub(x0,y0,z1,u0,v1,shade);
                vertexSub(x1,y0,z1,u1,v1,shade);
            }
            faceCount++;
        }

        private void vertexSub(int x16,int y16,int z16,int u,int v,int shade) {
            int pi=vertexCount*3; p[pi]=(short)(x16*16); p[pi+1]=(short)(y16*16); p[pi+2]=(short)(z16*16);
            int ti=vertexCount*2; uv[ti]=(short)u; uv[ti+1]=(short)v;
            int ci=vertexCount*4; c[ci]=(byte)shade; c[ci+1]=(byte)shade; c[ci+2]=(byte)shade; c[ci+3]=(byte)255;
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



