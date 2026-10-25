package mecraft;

import java.io.IOException;
import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
import javax.microedition.m3g.Background;
import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Group;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.Material;
import javax.microedition.m3g.Mesh;
import javax.microedition.m3g.PolygonMode;
import javax.microedition.m3g.Texture2D;
import javax.microedition.m3g.TriangleStripArray;
import javax.microedition.m3g.VertexArray;
import javax.microedition.m3g.VertexBuffer;

/** Indev-style sky objects, kept in a small fixed set of M3G meshes. */
final class SkySystem {
    private static final float FIXED_SCALE = 1.0f / 256.0f;
    private static final int STAR_COUNT = 500;
    private static final int[] OCEAN_TRIANGLE_ORDER = new int[] { 0, 1, 2, 0, 2, 3 };
    private final Group yawCounter = new Group();
    private final Group celestialRotation = new Group();
    private final Background skyBackground;
    private final Mesh stars;
    private final Material starsMaterial;
    private final Material oceanMaterial;
    private final Mesh oceanPlane;
    private final int cloudWidth;
    private final int cloudHeight;
    private final byte[] cloudAlpha;
    private Image2D cloudBackground;
    private byte[] backgroundPixels;
    private float[] screenX;
    private float[] screenY;
    private int viewportWidth;
    private int viewportHeight;

    SkySystem(Group cameraPivot, Background background, int width, int height) throws IOException {
        skyBackground = background;
        yawCounter.addChild(celestialRotation);
        cameraPivot.addChild(yawCounter);

        Mesh sun = skyQuad(32.0f, 9.6f, textureAppearance("sun.png"));
        Mesh moon = skyQuad(-32.0f, 6.4f, textureAppearance("moon.png"));
        celestialRotation.addChild(sun);
        celestialRotation.addChild(moon);

        Appearance starAppearance = new Appearance();
        starsMaterial = new Material();
        starsMaterial.setColor(Material.AMBIENT, 0xFFFFFFFF);
        starsMaterial.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        starsMaterial.setColor(Material.EMISSIVE, 0xFFFFFFFF);
        starAppearance.setMaterial(starsMaterial);
        PolygonMode starPolygon = new PolygonMode();
        starPolygon.setCulling(PolygonMode.CULL_NONE);
        starAppearance.setPolygonMode(starPolygon);
        stars = makeStars(starAppearance);
        celestialRotation.addChild(stars);

        Image cloudImage = GameAssets.image("clouds.png");
        cloudWidth = cloudImage.getWidth();
        cloudHeight = cloudImage.getHeight();
        int[] sourcePixels = new int[cloudWidth * cloudHeight];
        cloudImage.getRGB(sourcePixels, 0, cloudWidth, 0, 0, cloudWidth, cloudHeight);
        cloudAlpha = new byte[sourcePixels.length];
        for (int i = 0; i < sourcePixels.length; i++)
            cloudAlpha[i] = (byte)(sourcePixels[i] >>> 24);
        sourcePixels = null;
        resize(width, height);

        Appearance oceanAppearance = makeOceanAppearance();
        oceanMaterial = oceanAppearance.getMaterial();
        oceanPlane = makeOceanPlane(oceanAppearance);
        yawCounter.addChild(oceanPlane);
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0 ||
                (width == viewportWidth && height == viewportHeight)) return;
        viewportWidth = width;
        viewportHeight = height;
        int sampleWidth = (width + 1) >> 1;
        int sampleHeight = (height + 1) >> 1;
        screenX = new float[sampleWidth];
        screenY = new float[sampleHeight];
        float focal = height / (2.0f * TAN_HALF_FOV);
        for (int x = 0; x < sampleWidth; x++)
            screenX[x] = (x * 2 + 1.0f - width * 0.5f) / focal;
        for (int y = 0; y < sampleHeight; y++)
            screenY[y] = (height * 0.5f - (y * 2 + 1.0f)) / focal;
        backgroundPixels = new byte[width * height * 3];
        cloudBackground = new Image2D(Image2D.RGB, width, height);
        for (int at = 0; at < backgroundPixels.length; at += 3) {
            backgroundPixels[at] = (byte)0x82;
            backgroundPixels[at + 1] = (byte)0xB8;
            backgroundPixels[at + 2] = (byte)0xE8;
        }
        cloudBackground.set(0, 0, width, height, backgroundPixels);
        skyBackground.setImage(cloudBackground);
        skyBackground.setImageMode(Background.BORDER, Background.BORDER);
        skyBackground.setCrop(0, 0, width, height);
    }

    private static final float TAN_HALF_FOV =
            (float)Math.tan(34.0 * Math.PI / 180.0);

    void update(VoxelWorld world, float yaw, float cloudYaw, float celestialAngle,
            float daylight, int skyColor) {
        yawCounter.setOrientation(-yaw, 0.0f, 1.0f, 0.0f);
        celestialRotation.setOrientation(celestialAngle * 57.29578f,
                1.0f, 0.0f, 0.0f);
        renderCloudBackground(world.dayTime, cloudYaw, daylight, skyColor);
        oceanPlane.setTranslation(-world.playerX,
                VoxelWorld.SEA_LEVEL + 1.0f - (world.playerY + 1.62f), -world.playerZ);

        float night = 1.0f - daylight;
        float starLevel = night * night;
        stars.setRenderingEnable(starLevel > 0.015f);
        float shimmer = 0.88f + 0.12f *
                (0.5f + 0.5f * (float)Math.sin(world.dayTime * 0.035f));
        int brightness = (int)(starLevel * shimmer * 255.0f);
        int starColor = 0xFF000000 | (brightness << 16) |
                        (brightness << 8) | brightness;
        starsMaterial.setColor(Material.AMBIENT, starColor);
        starsMaterial.setColor(Material.DIFFUSE, starColor);
        starsMaterial.setColor(Material.EMISSIVE, starColor);

        oceanMaterial.setColor(Material.AMBIENT, 0xFFD8F1FF);
        oceanMaterial.setColor(Material.DIFFUSE, 0xFFD8F1FF);
    }

    /** Rasterize Indev's cloud map into the sky background at 1 texel / 8 blocks. */
    private void renderCloudBackground(int dayTime, float yawDegrees,
            float daylight, int skyColor) {
        // Keep the cloud panorama fixed at the world origin: translation and
        // pitch never affect it. Horizontal camera yaw changes the sampled
        // world direction, so the image scrolls oppositely to the player turn.
        double yaw = yawDegrees * Math.PI / 180.0;
        float sinYaw = (float)Math.sin(yaw), cosYaw = (float)Math.cos(yaw);
        float forwardX = sinYaw, forwardZ = cosYaw;
        float rightX = cosYaw, rightZ = -sinYaw;
        float virtualEyeY = VoxelWorld.HEIGHT * 0.5f;
        float cloudLevel = VoxelWorld.HEIGHT + 10.0f - virtualEyeY;
        float focal = viewportHeight / (2.0f * TAN_HALF_FOV);
        int shift = (int)(dayTime * 0.00375f);
        int cloudTint = (int)(190.0f + daylight * 65.0f);
        int skyR = skyColor >> 16 & 255;
        int skyG = skyColor >> 8 & 255;
        int skyB = skyColor & 255;
        int halfWidth = (viewportWidth + 1) >> 1;
        int halfHeight = (viewportHeight + 1) >> 1;
        for (int sy = 0; sy < halfHeight; sy++) {
            float screenUp = screenY[sy];
            float distance = screenUp > 0.0001f ? cloudLevel / screenUp : 0.0f;
            float startX = screenUp > 0.0001f
                    ? (forwardX + screenX[0] * rightX) * distance : 0.0f;
            float startZ = screenUp > 0.0001f
                    ? (forwardZ + screenX[0] * rightZ) * distance : 0.0f;
            float stepX = rightX * distance * 2.0f / focal;
            float stepZ = rightZ * distance * 2.0f / focal;
            for (int sx = 0; sx < halfWidth; sx++) {
                int red = skyR, green = skyG, blue = skyB;
                if (screenUp > 0.0001f) {
                    int tx = floor(startX * cloudWidth / 2048.0f) + shift;
                    int tz = floor(startZ * cloudHeight / 2048.0f);
                    tx = wrap(tx, cloudWidth);
                    tz = wrap(tz, cloudHeight);
                    int alpha = cloudAlpha[tz * cloudWidth + tx] & 255;
                    if (alpha > 12) {
                        red = (skyR * (255 - alpha) + cloudTint * alpha) / 255;
                        green = (skyG * (255 - alpha) + cloudTint * alpha) / 255;
                        blue = (skyB * (255 - alpha) + cloudTint * alpha) / 255;
                    }
                    startX += stepX;
                    startZ += stepZ;
                }
                int x = sx << 1, y = sy << 1;
                putBackgroundPixel(x, y, red, green, blue);
                if (x + 1 < viewportWidth) putBackgroundPixel(x + 1, y, red, green, blue);
                if (y + 1 < viewportHeight) {
                    putBackgroundPixel(x, y + 1, red, green, blue);
                    if (x + 1 < viewportWidth)
                        putBackgroundPixel(x + 1, y + 1, red, green, blue);
                }
            }
        }
        cloudBackground.set(0, 0, viewportWidth, viewportHeight, backgroundPixels);
    }

    private void putBackgroundPixel(int x, int y, int red, int green, int blue) {
        int at = (y * viewportWidth + x) * 3;
        backgroundPixels[at] = (byte)red;
        backgroundPixels[at + 1] = (byte)green;
        backgroundPixels[at + 2] = (byte)blue;
    }

    private int floor(float value) {
        int result = (int)value;
        return value < result ? result - 1 : result;
    }

    private int wrap(int value, int length) {
        value %= length;
        return value < 0 ? value + length : value;
    }

    private Mesh skyQuad(float y, float halfSize, Appearance appearance) {
        short[] corners = new short[] {
            fixed(-halfSize), fixed(y), fixed(-halfSize),
            fixed(halfSize), fixed(y), fixed(-halfSize),
            fixed(halfSize), fixed(y), fixed(halfSize),
            fixed(-halfSize), fixed(y), fixed(halfSize)
        };
        short[] p = triangleQuad(corners, 3);
        short[] uv = triangleQuadUV();
        return mesh(p, uv, FIXED_SCALE, 1.0f / 256.0f, appearance);
    }

    /** Indev fills the area beyond the finite island with a distant water plane. */
    private Appearance makeOceanAppearance() throws IOException {
        Image terrain = GameAssets.terrain();
        int tile = Block.atlasTile(Block.WATER);
        int[] pixels = new int[16 * 16];
        terrain.getRGB(pixels, 0, 16, (tile & 15) * 16, (tile >> 4) * 16, 16, 16);
        Image waterImage = Image.createRGBImage(pixels, 16, 16, true);
        Texture2D waterTexture = new Texture2D(new Image2D(Image2D.RGBA, waterImage));
        waterTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        waterTexture.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        return alphaAppearance(waterTexture);
    }

    private Mesh makeOceanPlane(Appearance appearance) {
        short[] positions = new short[24 * 3];
        short[] texCoords = new short[24 * 2];
        int pAt = 0, uvAt = 0;
        int margin = 2048;
        int maxX = VoxelWorld.WIDTH, maxZ = VoxelWorld.DEPTH;
        // Indev's out-of-bounds ocean renderer fills only the space outside
        // the finite map. These four strips begin exactly at the island edge.
        pAt = addOceanQuad(positions, texCoords, pAt, uvAt,
                -margin, -margin, 0, maxZ + margin); uvAt += 12;
        pAt = addOceanQuad(positions, texCoords, pAt, uvAt,
                maxX, -margin, maxX + margin, maxZ + margin); uvAt += 12;
        pAt = addOceanQuad(positions, texCoords, pAt, uvAt,
                0, -margin, maxX, 0); uvAt += 12;
        addOceanQuad(positions, texCoords, pAt, uvAt,
                0, maxZ, maxX, maxZ + margin);

        VertexArray positionArray = new VertexArray(24, 3, 2);
        positionArray.set(0, 24, positions);
        VertexArray texCoordArray = new VertexArray(24, 2, 2);
        texCoordArray.set(0, 24, texCoords);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positionArray, 1.0f / 8.0f, null);
        vertices.setTexCoords(0, texCoordArray, 1.0f / 256.0f, null);
        return new Mesh(vertices, new TriangleStripArray(0,
                new int[] { 3, 3, 3, 3, 3, 3, 3, 3 }), appearance);
    }

    private int addOceanQuad(short[] positions, short[] texCoords, int pAt, int uvAt,
            int x0, int z0, int x1, int z1) {
        for (int i = 0; i < OCEAN_TRIANGLE_ORDER.length; i++) {
            int corner = OCEAN_TRIANGLE_ORDER[i];
            int x = corner == 0 || corner == 3 ? x0 : x1;
            int z = corner < 2 ? z0 : z1;
            positions[pAt++] = (short)(x * 8);
            positions[pAt++] = 0;
            positions[pAt++] = (short)(z * 8);
            // 256 UV units per 2048 world blocks, matching Indev's tiling.
            texCoords[uvAt++] = (short)(x / 8);
            texCoords[uvAt++] = (short)(z / 8);
        }
        return pAt;
    }

    private Mesh makeStars(Appearance appearance) {
        short[] p = new short[STAR_COUNT * 6 * 3];
        short[] uv = new short[STAR_COUNT * 6 * 2];
        int[] strips = new int[STAR_COUNT * 2];
        Random random = new Random(10842L);
        int pa = 0;
        int ta = 0;
        for (int i = 0; i < STAR_COUNT; i++) {
            double theta = random.nextDouble() * Math.PI * 2.0;
            double z = random.nextDouble() * 2.0 - 1.0;
            double radial = Math.sqrt(1.0 - z * z);
            float cx = (float)(Math.cos(theta) * radial * -31.0);
            float cy = (float)(z * -31.0);
            float cz = (float)(Math.sin(theta) * radial * -31.0);
            float size = 0.25f + random.nextFloat() * 0.25f;
            float roll = random.nextFloat() * 6.2831855f;
            float ux = cx / 31.0f, uy = cy / 31.0f, uz = cz / 31.0f;
            float ex = uz, ez = -ux;
            float el = (float)Math.sqrt(ex * ex + ez * ez);
            if (el < 0.001f) { ex = 1.0f; ez = 0.0f; el = 1.0f; }
            ex /= el; ez /= el;
            float fx = uy * ez, fy = uz * ex - ux * ez, fz = -uy * ex;
            float ax = (ex * (float)Math.cos(roll) + fx * (float)Math.sin(roll)) * size;
            float ay = (fy * (float)Math.sin(roll)) * size;
            float az = (ez * (float)Math.cos(roll) + fz * (float)Math.sin(roll)) * size;
            float bx = (-ex * (float)Math.sin(roll) + fx * (float)Math.cos(roll)) * size;
            float by = fy * (float)Math.cos(roll) * size;
            float bz = (-ez * (float)Math.sin(roll) + fz * (float)Math.cos(roll)) * size;
            float x0 = cx - ax - bx, y0 = cy - ay - by, z0 = cz - az - bz;
            float x1 = cx + ax - bx, y1 = cy + ay - by, z1 = cz + az - bz;
            float x2 = cx + ax + bx, y2 = cy + ay + by, z2 = cz + az + bz;
            float x3 = cx - ax + bx, y3 = cy - ay + by, z3 = cz - az + bz;
            pa = putVertex(p, pa, x0, y0, z0); pa = putVertex(p, pa, x1, y1, z1);
            pa = putVertex(p, pa, x2, y2, z2); pa = putVertex(p, pa, x0, y0, z0);
            pa = putVertex(p, pa, x2, y2, z2); pa = putVertex(p, pa, x3, y3, z3);
            uv[ta++] = 0; uv[ta++] = 0; uv[ta++] = 256; uv[ta++] = 0;
            uv[ta++] = 256; uv[ta++] = 256; uv[ta++] = 0; uv[ta++] = 0;
            uv[ta++] = 256; uv[ta++] = 256; uv[ta++] = 0; uv[ta++] = 256;
            strips[i * 2] = 3;
            strips[i * 2 + 1] = 3;
        }
        VertexArray positions = new VertexArray(STAR_COUNT * 6, 3, 2);
        positions.set(0, STAR_COUNT * 6, p);
        VertexArray texCoords = new VertexArray(STAR_COUNT * 6, 2, 2);
        texCoords.set(0, STAR_COUNT * 6, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, FIXED_SCALE, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 256.0f, null);
        return new Mesh(vertices, new TriangleStripArray(0, strips), appearance);
    }

    private Mesh mesh(short[] p, short[] uv, float positionScale,
            float uvScale, Appearance appearance) {
        VertexArray positions = new VertexArray(6, 3, 2);
        positions.set(0, 6, p);
        VertexArray texCoords = new VertexArray(6, 2, 2);
        texCoords.set(0, 6, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, positionScale, null);
        vertices.setTexCoords(0, texCoords, uvScale, null);
        return new Mesh(vertices, new TriangleStripArray(0, new int[] { 3, 3 }), appearance);
    }

    private short[] triangleQuad(short[] corners, int stride) {
        short[] triangles = new short[18];
        int[] order = { 0, 1, 2, 0, 2, 3 };
        int at = 0;
        for (int i = 0; i < order.length; i++) {
            int from = order[i] * stride;
            for (int c = 0; c < stride; c++) triangles[at++] = corners[from + c];
        }
        return triangles;
    }

    private short[] triangleQuadUV() {
        return new short[] { 0, 0, 256, 0, 256, 256, 0, 0, 256, 256, 0, 256 };
    }

    private int putVertex(short[] positions, int at, float x, float y, float z) {
        positions[at++] = fixed(x);
        positions[at++] = fixed(y);
        positions[at++] = fixed(z);
        return at;
    }

    private Appearance textureAppearance(String file) throws IOException {
        Image image = GameAssets.image(file);
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
        texture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        return alphaAppearance(texture);
    }

    private Appearance alphaAppearance(Texture2D texture) {
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        Material material = new Material();
        material.setColor(Material.AMBIENT, 0xFFFFFFFF);
        material.setColor(Material.DIFFUSE, 0xFFFFFFFF);
        appearance.setMaterial(material);
        PolygonMode polygon = new PolygonMode();
        polygon.setCulling(PolygonMode.CULL_NONE);
        appearance.setPolygonMode(polygon);
        CompositingMode compositing = new CompositingMode();
        compositing.setBlending(CompositingMode.ALPHA);
        compositing.setAlphaThreshold(0.05f);
        compositing.setDepthWriteEnable(false);
        appearance.setCompositingMode(compositing);
        return appearance;
    }

    private static short fixed(float value) {
        return (short)(value * 256.0f);
    }
}
