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

/** Builds a bounded set of shared-texture chunk meshes for JSR-184. */
final class ChunkRenderer {
    static final int CACHE_SIZE = VoxelWorld.CHUNKS_X * VoxelWorld.CHUNKS_Y * VoxelWorld.CHUNKS_Z;
    private static final int TILE_COUNT = 256;

    final World scene = new World();
    final Camera camera = new Camera();
    private final Transform cameraTransform = new Transform();
    private final Transform handTransform = new Transform();
    final Group cameraPivot = new Group();
    private final Group cameraEffectPivot = new Group();
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
    private final Group terrain = new Group();
    private final Background sky = new Background();
    private final Light sunlight = new Light();
    private final Light[] torchLights = new Light[3];
    private final float[] nearestTorchDistance = new float[] { 65.0f, 65.0f, 65.0f };
    private final int[] nearestTorchX = new int[3];
    private final int[] nearestTorchY = new int[3];
    private final int[] nearestTorchZ = new int[3];
    private final Chunk[] chunks = new Chunk[CACHE_SIZE];
    private final Texture2D atlas;
    private final Texture2D itemAtlas;
    private final Appearance blockAppearance;
    private final Appearance flowerAppearance;
    private final Appearance heldItemAppearance;
    private final Appearance itemSpriteAppearance;
    private final Appearance handAppearance;
    private final Mesh handMesh;
    private final Mesh itemSpriteMesh;
    private VertexArray itemSpriteTexCoordArray;
    private final short[] itemSpriteUV;
    private final Mesh[][] heldItemMeshes = new Mesh[Block.COUNT][];
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

    ChunkRenderer(int width, int height) throws IOException {
        if (width <= 0) width = 1;
        if (height <= 0) height = 1;
        Image source = Image.createImage("/mecraft/textures.png");
        Image2D image = new Image2D(Image2D.RGBA, source);
        atlas = new Texture2D(image);
        atlas.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        atlas.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        blockAppearance = createBlockAppearance();
        flowerAppearance = createFlowerAppearance();
        heldItemAppearance = createFirstPersonAppearance(atlas, true);
        Image itemSource = Image.createImage("/mecraft/items.png");
        itemAtlas = new Texture2D(new Image2D(Image2D.RGBA, itemSource));
        itemAtlas.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        itemAtlas.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        itemSpriteAppearance = createFirstPersonAppearance(itemAtlas, true);
        ItemSpriteData itemData = makeItemSpriteMesh();
        itemSpriteMesh = itemData.mesh;
        itemSpriteTexCoordArray = itemData.texCoords;
        itemSpriteUV = itemData.uv;
        handAppearance = createHandAppearance();
        handMesh = makeHandMesh();
        armBase.setTranslation(handBaseX(width, height), -0.60f, -0.72f);
        armSwingYaw.setOrientation(45.0f, 0.0f, 1.0f, 0.0f);
        armSwingOffset.addChild(armBase);
        armBase.addChild(armSwingYaw);
        armSwingYaw.addChild(armSwingRoll);
        armSwingRoll.addChild(handMesh);
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
        mobs = new MobSystem(scene, droppedItems);

        camera.setPerspective(68.0f, (float)width / (float)height, 0.1f, 38.0f);
        cameraPivot.addChild(cameraEffectPivot);
        cameraEffectPivot.addChild(camera);
        cameraEffectPivot.addChild(handPivot);
        scene.addChild(terrain);
        scene.addChild(particles.mesh);
        scene.addChild(droppedItems.mesh);
        scene.addChild(droppedItems.spriteMesh);
        scene.addChild(cameraPivot);
        scene.setActiveCamera(camera);
        sky.setColor(0xFF82B8E8);
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
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0 || (width == builtWidth && height == builtHeight)) return;
        builtWidth = width;
        builtHeight = height;
        camera.setPerspective(68.0f, (float)width / (float)height, 0.1f, 38.0f);
        armBase.setTranslation(handBaseX(width, height), -0.60f, -0.72f);
    }

    private float handBaseX(int width, int height) {
        // Indev's 0.64 view-space offset was authored for a 4:3 viewport.
        // Scale only its horizontal placement to keep the same apparent angle
        // on narrow Java ME portrait screens.
        handHorizontalScale = ((float)width / (float)height) / (4.0f / 3.0f);
        if (handHorizontalScale > 1.0f) handHorizontalScale = 1.0f;
        return 0.64f * handHorizontalScale;
    }

    void updateCamera(VoxelWorld world, float pitch) {
        cameraPivot.setTranslation(world.playerX, world.playerY + 1.62f, world.playerZ);
        // M3G cameras look down local -Z; 180 degrees makes yaw zero face +Z.
        cameraPivot.setOrientation(world.yaw + 180.0f, 0.0f, 1.0f, 0.0f);
        camera.setOrientation(pitch, 1.0f, 0.0f, 0.0f);
        handPivot.setOrientation(pitch, 1.0f, 0.0f, 0.0f);
    }

    /** Rotate the full first-person view during the Indev hurt-camera pulse. */
    void setHurtCameraRoll(float degrees) {
        cameraEffectPivot.setOrientation(degrees, 0.0f, 0.0f, 1.0f);
    }

    /** Render the first-person hand and held block in a final overlay pass. */
    void render(Graphics3D graphics) {
        handPivot.setRenderingEnable(false);
        try {
            graphics.render(scene);
        } finally {
            handPivot.setRenderingEnable(true);
        }
        camera.getTransformTo(scene, cameraTransform);
        handPivot.getTransformTo(scene, handTransform);
        graphics.setCamera(camera, cameraTransform);
        graphics.render(handPivot, handTransform);
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
        if (itemId < Block.COUNT) {
            if (heldItemMeshes[itemId] != null) setMeshesVisible(heldItemMeshes[itemId], visible);
        } else {
            if (visible) setItemSprite(itemId);
            itemSpriteMesh.setRenderingEnable(visible && ItemCatalog.iconIndex(itemId) >= 0);
        }
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
    }

    void setHandLooping(boolean looping) {
        handLooping = looping;
    }

    /** Indev first-person swing curve, sampled once per 50 ms game tick. */
    void updateHandAnimation() {
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
            armSwingOffset.setTranslation(0.0f, 0.0f, 0.0f);
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
        return itemId >= ItemCatalog.STICK ||
               (itemId > Block.AIR && itemId < Block.COUNT && Block.isFlower((byte)itemId));
    }

    private void setRestHandPose() {
        armSwingOffset.setTranslation(0.0f, 0.0f, 0.0f);
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
        for (int id = 1; id < Block.COUNT; id++) {
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
        int[] xyz = new int[] {x0,y0,z0,x1,y1,z1,x2,y2,z2,x3,y3,z3};
        int[] uv = new int[] {u0,v0,u1,v1,u2,v2,u3,v3};
        for (int i = 0; i < 4; i++) {
            positions[vertex * 3] = (short)xyz[i * 3];
            positions[vertex * 3 + 1] = (short)xyz[i * 3 + 1];
            positions[vertex * 3 + 2] = (short)xyz[i * 3 + 2];
            texCoords[vertex * 2] = (short)uv[i * 2];
            texCoords[vertex * 2 + 1] = (short)uv[i * 2 + 1];
            vertex++;
        }
        return vertex;
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
        material.setColor(Material.AMBIENT, 0xFFFFD6A0);
        material.setColor(Material.DIFFUSE, 0xFFFFD6A0);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        polygon.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setDepthTestEnable(false);
        compositing.setDepthWriteEnable(false);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private Appearance createFirstPersonAppearance(Texture2D texture, boolean alphaBlending) {
        Appearance appearance = new Appearance();
        if (texture != null) appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
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
        final float inv16 = 1.0f / 16.0f;
        final float positionScale = 1024.0f;
        final float sinY = (float)Math.sin(-135.0 * Math.PI / 180.0);
        final float cosY = (float)Math.cos(-135.0 * Math.PI / 180.0);
        final float sinX = (float)Math.sin(200.0 * Math.PI / 180.0);
        final float cosX = (float)Math.cos(200.0 * Math.PI / 180.0);
        final float sinZ = (float)Math.sin(120.0 * Math.PI / 180.0);
        final float cosZ = (float)Math.cos(120.0 * Math.PI / 180.0);
        for (int i = 0; i < 24; i++) {
            float x = (p[i * 3] + 1.0f) * inv16;
            float y = (p[i * 3 + 1] + 2.0f) * inv16;
            float z = p[i * 3 + 2] * inv16;

            float rotatedX = x * cosY + z * sinY;
            float rotatedZ = -x * sinY + z * cosY;
            float rotatedY = y * cosX - rotatedZ * sinX;
            float finalZ = y * sinX + rotatedZ * cosX;
            float finalX = rotatedX * cosZ - rotatedY * sinZ - 0.2f;
            float finalY = rotatedX * sinZ + rotatedY * cosZ - 0.3f;
            finalZ += 0.1f;

            p[i * 3] = quantizePosition(finalX, positionScale);
            p[i * 3 + 1] = quantizePosition(finalY, positionScale);
            p[i * 3 + 2] = quantizePosition(finalZ, positionScale);
        }
        int[] strips = new int[] { 4,4,4,4,4,4 };
        VertexArray positions = new VertexArray(24, 3, 2);
        positions.set(0, 24, p);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f / positionScale, null);
        TriangleStripArray triangles = new TriangleStripArray(0, strips);
        return new Mesh(vertices, triangles, handAppearance);
    }

    /** Quantize a model-space float without relying on Math.round in CLDC. */
    private short quantizePosition(float value, float scale) {
        float scaled = value * scale;
        int rounded = (int)(scaled >= 0.0f ? scaled + 0.5f : scaled - 0.5f);
        if (rounded > 32767) rounded = 32767;
        if (rounded < -32768) rounded = -32768;
        return (short)rounded;
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

    int updateDroppedItems(VoxelWorld world, int[] inventory) {
        return droppedItems.update(world, inventory);
    }

    int updateMobs(VoxelWorld world) {
        return mobs.update(world);
    }

    /** Applies Indev's 24000-tick celestial curve and lights nearby torches with a fixed M3G pool. */
    void updateDayNight(VoxelWorld world) {
        float angle = ((float)world.dayTime / (float)VoxelWorld.DAY_LENGTH - 0.15f) *
                      6.2831855f;
        float sun = (float)Math.cos(angle) * 1.5f + 0.5f;
        if (sun < 0.0f) sun = 0.0f;
        if (sun > 1.0f) sun = 1.0f;
        sunlight.setIntensity(0.04f + sun * 0.96f);
        sky.setColor(blendColor(0xFF070C1B, 0xFF82B8E8, sun));
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
        for (int i = 0; i < torchLights.length; i++) nearestTorchDistance[i] = 65.0f;
        int px = (int)world.playerX, py = (int)world.playerY, pz = (int)world.playerZ;
        for (int y = py - 5; y <= py + 6; y++) {
            for (int z = pz - 8; z <= pz + 8; z++) {
                for (int x = px - 8; x <= px + 8; x++) {
                    if (world.get(x, y, z) != Block.TORCH) continue;
                    float dx = x + 0.5f - world.playerX;
                    float dy = y + 0.8f - (world.playerY + 1.0f);
                    float dz = z + 0.5f - world.playerZ;
                    float distance = dx * dx + dy * dy + dz * dz;
                    if (distance > 64.0f || distance >= nearestTorchDistance[2]) continue;
                    int at = 2;
                    while (at > 0 && distance < nearestTorchDistance[at - 1]) {
                        nearestTorchDistance[at] = nearestTorchDistance[at - 1];
                        nearestTorchX[at] = nearestTorchX[at - 1];
                        nearestTorchY[at] = nearestTorchY[at - 1];
                        nearestTorchZ[at] = nearestTorchZ[at - 1];
                        at--;
                    }
                    nearestTorchDistance[at] = distance;
                    nearestTorchX[at] = x;
                    nearestTorchY[at] = y;
                    nearestTorchZ[at] = z;
                }
            }
        }
        for (int i = 0; i < torchLights.length; i++) {
            if (nearestTorchDistance[i] <= 64.0f) {
                torchLights[i].setTranslation(nearestTorchX[i] + 0.5f,
                    nearestTorchY[i] + 0.8f, nearestTorchZ[i] + 0.5f);
                torchLights[i].setIntensity(0.85f);
            } else {
                torchLights[i].setIntensity(0.0f);
            }
        }
    }

    int hitMob(VoxelWorld world, float pitch, int damage) {
        return mobs.hit(world, pitch, damage);
    }

    void sync(VoxelWorld world) {
        if (world.revision != builtRevision) {
            if (builtRevision < 0) rebuildWorld(world);
            else rebuildChanged(world);
            builtRevision = world.revision;
        }
    }

    private void rebuildChanged(VoxelWorld world) {
        boolean[] dirty = new boolean[chunks.length];
        int x = world.changedX, y = world.changedY, z = world.changedZ;
        markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ);
        if (x % VoxelWorld.CHUNK_XZ == 0) markDirty(dirty, x / VoxelWorld.CHUNK_XZ - 1, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ);
        if (x % VoxelWorld.CHUNK_XZ == VoxelWorld.CHUNK_XZ - 1) markDirty(dirty, x / VoxelWorld.CHUNK_XZ + 1, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ);
        if (y % VoxelWorld.CHUNK_Y == 0) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y - 1, z / VoxelWorld.CHUNK_XZ);
        if (y % VoxelWorld.CHUNK_Y == VoxelWorld.CHUNK_Y - 1) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y + 1, z / VoxelWorld.CHUNK_XZ);
        if (z % VoxelWorld.CHUNK_XZ == 0) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ - 1);
        if (z % VoxelWorld.CHUNK_XZ == VoxelWorld.CHUNK_XZ - 1) markDirty(dirty, x / VoxelWorld.CHUNK_XZ, y / VoxelWorld.CHUNK_Y, z / VoxelWorld.CHUNK_XZ + 1);
        for (int i = 0; i < dirty.length; i++) if (dirty[i]) replaceChunk(world, i);
    }

    private void markDirty(boolean[] dirty, int cx, int cy, int cz) {
        if (cx < 0 || cx >= VoxelWorld.CHUNKS_X || cy < 0 || cy >= VoxelWorld.CHUNKS_Y ||
            cz < 0 || cz >= VoxelWorld.CHUNKS_Z) return;
        dirty[(cy * VoxelWorld.CHUNKS_Z + cz) * VoxelWorld.CHUNKS_X + cx] = true;
    }

    private void replaceChunk(VoxelWorld world, int i) {
        if (chunks[i] != null) {
            for (int m = 0; m < chunks[i].meshes.length; m++) terrain.removeChild(chunks[i].meshes[m]);
        }
        int cx = i % VoxelWorld.CHUNKS_X;
        int cz = (i / VoxelWorld.CHUNKS_X) % VoxelWorld.CHUNKS_Z;
        int cy = i / (VoxelWorld.CHUNKS_X * VoxelWorld.CHUNKS_Z);
        chunks[i] = buildChunk(world, cx, cy, cz);
        if (chunks[i] != null) {
            for (int m = 0; m < chunks[i].meshes.length; m++) terrain.addChild(chunks[i].meshes[m]);
        }
    }

    private void rebuildWorld(VoxelWorld world) {
        for (int i = 0; i < chunks.length; i++) {
            if (chunks[i] != null) {
                for (int m = 0; m < chunks[i].meshes.length; m++) terrain.removeChild(chunks[i].meshes[m]);
                chunks[i] = null;
            }
        }
        for (int cy = 0; cy < VoxelWorld.CHUNKS_Y; cy++) {
            for (int cz = 0; cz < VoxelWorld.CHUNKS_Z; cz++) {
                for (int cx = 0; cx < VoxelWorld.CHUNKS_X; cx++) {
                    int i = (cy * VoxelWorld.CHUNKS_Z + cz) * VoxelWorld.CHUNKS_X + cx;
                    chunks[i] = buildChunk(world, cx, cy, cz);
                    if (chunks[i] != null) {
                        for (int m = 0; m < chunks[i].meshes.length; m++) terrain.addChild(chunks[i].meshes[m]);
                    }
                }
            }
        }
    }

    private Chunk buildChunk(VoxelWorld world, int cx, int cy, int cz) {
        FaceBuilder[] builders = new FaceBuilder[TILE_COUNT];
        FlowerBuilder[] flowers = new FlowerBuilder[1];
        int x0 = cx * VoxelWorld.CHUNK_XZ;
        int y0 = cy * VoxelWorld.CHUNK_Y;
        int z0 = cz * VoxelWorld.CHUNK_XZ;
        int x1 = x0 + VoxelWorld.CHUNK_XZ;
        int y1 = y0 + VoxelWorld.CHUNK_Y;
        int z1 = z0 + VoxelWorld.CHUNK_XZ;

        for (int y = y0; y < y1; y++) {
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    byte id = world.get(x, y, z);
                    if (id == Block.AIR) continue;
                    if (Block.isFlower(id)) {
                        if (flowers[0] == null) flowers[0] = new FlowerBuilder();
                        flowers[0].add(x - x0, y - y0, z - z0, Block.sideTile(id));
                        continue;
                    }
                    if (Block.faceVisible(id, world.get(x, y + 1, z))) {
                        add(builders, Block.faceTile(id, 0), x - x0, y - y0, z - z0, 0);
                    }
                    if (Block.faceVisible(id, world.get(x - 1, y, z)))
                        add(builders, Block.faceTile(id, 1), x - x0, y - y0, z - z0, 1);
                    if (Block.faceVisible(id, world.get(x + 1, y, z)))
                        add(builders, Block.faceTile(id, 2), x - x0, y - y0, z - z0, 2);
                    if (Block.faceVisible(id, world.get(x, y, z - 1)))
                        add(builders, Block.faceTile(id, 3), x - x0, y - y0, z - z0, 3);
                    if (Block.faceVisible(id, world.get(x, y, z + 1)))
                        add(builders, Block.faceTile(id, 4), x - x0, y - y0, z - z0, 4);
                    if (y > 0 && Block.faceVisible(id, world.get(x, y - 1, z)))
                        add(builders, Block.faceTile(id, 5), x - x0, y - y0, z - z0, 5);
                }
            }
        }

        int count = 0;
        for (int i = 0; i < builders.length; i++) if (builders[i] != null) count++;
        int flowerCount = 0;
        for (int i = 0; i < flowers.length; i++) if (flowers[i] != null) flowerCount++;
        if (count + flowerCount == 0) return null;
        Mesh[] meshes = new Mesh[count + flowerCount];
        int at = 0;
        for (int i = 0; i < builders.length; i++) {
            if (builders[i] == null) continue;
            meshes[at++] = makeMesh(builders[i]);
            builders[i] = null;
        }
        for (int i = 0; i < flowers.length; i++) {
            if (flowers[i] != null) meshes[at++] = makeFlowerMesh(flowers[i], flowerAppearance);
        }
        Chunk chunk = new Chunk();
        chunk.meshes = meshes;
        int wx = x0, wy = y0, wz = z0;
        for (int i = 0; i < meshes.length; i++) meshes[i].setTranslation(wx, wy, wz);
        return chunk;
    }

    private void add(FaceBuilder[] builders, int tile, int x, int y, int z, int face) {
        if (builders[tile] == null) builders[tile] = new FaceBuilder(tile);
        builders[tile].add(x, y, z, face);
    }

    private Mesh makeMesh(FaceBuilder data) {
        return makeMesh(data, blockAppearance);
    }

    private Mesh makeMesh(FaceBuilder data, Appearance appearance) {
        int vertexCount = data.vertexCount;
        VertexArray positions = new VertexArray(vertexCount, 3, 2);
        positions.set(0, vertexCount, data.p);
        VertexArray texCoords = new VertexArray(vertexCount, 2, 2);
        texCoords.set(0, vertexCount, data.uv);
        VertexArray colors = new VertexArray(vertexCount, 4, 1);
        colors.set(0, vertexCount, data.c);

        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 256.0f, null);
        vertices.setColors(colors);
        int[] strips = new int[data.faceCount];
        for (int i = 0; i < strips.length; i++) strips[i] = 4;
        TriangleStripArray triangles = new TriangleStripArray(0, strips);

        return new Mesh(vertices, triangles, appearance);
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

    private Appearance createBlockAppearance() {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, atlas);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        material.setVertexColorTrackingEnable(true);
        appearance.setMaterial(material);
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

    private static final class Chunk { Mesh[] meshes; }

    /** Two crossed, alpha-cutout quads per flower, grouped by chunk and type. */
    private static final class FlowerBuilder {
        short[] p = new short[48];
        short[] uv = new short[32];
        int vertexCount;

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
        final int tile;
        short[] p = new short[192];
        short[] uv = new short[128];
        byte[] c = new byte[256];
        int vertexCount;
        int faceCount;

        FaceBuilder(int tile) { this.tile = tile; }

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

        private void vertex(int x, int y, int z, int u, int v, int shade) {
            int pi = vertexCount * 3;
            p[pi] = (short)x; p[pi + 1] = (short)y; p[pi + 2] = (short)z;
            int ti = vertexCount * 2;
            uv[ti] = (short)u; uv[ti + 1] = (short)v;
            int ci = vertexCount * 4;
            c[ci] = (byte)shade; c[ci + 1] = (byte)shade; c[ci + 2] = (byte)shade; c[ci + 3] = (byte)255;
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

