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
    private static final float NORMAL_TAN_HALF_FOV = (float)Math.tan(34.0 * Math.PI / 180.0);
    private static final int STAR_COUNT = 500;
    private static final int CLOUD_BAND_HEIGHT = 160;
    private static final int CLOUD_COLOR_STEP = 8;
    private final Group yawCounter = new Group();
    private final Group celestialRotation = new Group();
    private final Background skyBackground;
    private final Mesh stars;
    private final Material starsMaterial;
    private final int cloudWidth;
    private final byte[] cloudAlpha;
    private Image2D cloudBackground;
    private byte[] cloudPixels;
    private float tanHalfFov = NORMAL_TAN_HALF_FOV;
    private int viewportWidth;
    private int viewportHeight;
    private int lastCloudTint;
    private int lastSkyColor;
    private boolean starColorReady;
    private int lastStarColor;
    private boolean starVisibilityReady;
    private boolean lastStarsVisible;

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
        int cloudHeight = cloudImage.getHeight();
        int[] sourcePixels = new int[cloudWidth * cloudHeight];
        cloudImage.getRGB(sourcePixels, 0, cloudWidth, 0, 0, cloudWidth, cloudHeight);
        cloudAlpha = new byte[sourcePixels.length];
        for (int i = 0; i < sourcePixels.length; i++)
            cloudAlpha[i] = (byte)(sourcePixels[i] >>> 24);
        sourcePixels = null;
        cloudPixels = new byte[cloudWidth * CLOUD_BAND_HEIGHT * 3];
        cloudBackground = new Image2D(Image2D.RGB, cloudWidth, CLOUD_BAND_HEIGHT);
        resize(width, height);
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0 ||
                (width == viewportWidth && height == viewportHeight)) return;
        viewportWidth = width;
        viewportHeight = height;
        skyBackground.setImage(cloudBackground);
        skyBackground.setImageMode(Background.REPEAT, Background.BORDER);
        skyBackground.setCrop(0, 0, width, height);
    }

    void setFieldOfView(float fieldOfView) {
        float next = (float)Math.tan(fieldOfView * Math.PI / 360.0);
        if (Math.abs(next - tanHalfFov) < 0.0001f) return;
        tanHalfFov = next;
    }

    void update(VoxelWorld world, float yaw, float cloudYaw, float pitch,
            float celestialAngle,
            float daylight, int skyColor) {
        yawCounter.setOrientation(-yaw, 0.0f, 1.0f, 0.0f);
        celestialRotation.setOrientation(celestialAngle * 57.29578f,
                1.0f, 0.0f, 0.0f);
        renderCloudBackground(world.dayTime, cloudYaw, pitch, daylight, skyColor);
        float night = 1.0f - daylight;
        float starLevel = night * night;
        boolean starsVisible = starLevel > 0.015f;
        if (!starVisibilityReady || starsVisible != lastStarsVisible) {
            stars.setRenderingEnable(starsVisible);
            lastStarsVisible = starsVisible;
            starVisibilityReady = true;
        }
        float shimmer = 0.88f + 0.12f *
                (0.5f + 0.5f * (float)Math.sin(world.dayTime * 0.035f));
        int brightness = (int)(starLevel * shimmer * 255.0f);
        int starColor = 0xFF000000 | (brightness << 16) |
                        (brightness << 8) | brightness;
        if (!starColorReady || starColor != lastStarColor) {
            starsMaterial.setColor(Material.AMBIENT, starColor);
            starsMaterial.setColor(Material.DIFFUSE, starColor);
            starsMaterial.setColor(Material.EMISSIVE, starColor);
            lastStarColor = starColor;
            starColorReady = true;
        }
    }

    /** Composite a small 2D cloud strip; M3G scrolls it as a repeated background. */
    private void renderCloudBackground(int dayTime, float yawDegrees,
            float pitchDegrees, float daylight, int skyColor) {
        int skyR = quantizeColor(skyColor >> 16 & 255);
        int skyG = quantizeColor(skyColor >> 8 & 255);
        int skyB = quantizeColor(skyColor & 255);
        int quantizedSky = 0xFF000000 | (skyR << 16) | (skyG << 8) | skyB;
        int cloudTint = quantizeColor((int)(190.0f + daylight * 65.0f));
        if (cloudTint != lastCloudTint || quantizedSky != lastSkyColor) {
            for (int y = 0; y < CLOUD_BAND_HEIGHT; y++) {
                int source = y * cloudWidth;
                int target = source * 3;
                for (int x = 0; x < cloudWidth; x++) {
                    int alpha = cloudAlpha[source + x] & 255;
                    if (alpha <= 12) alpha = 0;
                    int inverse = 255 - alpha;
                    cloudPixels[target++] = (byte)((skyR * inverse + cloudTint * alpha) / 255);
                    cloudPixels[target++] = (byte)((skyG * inverse + cloudTint * alpha) / 255);
                    cloudPixels[target++] = (byte)((skyB * inverse + cloudTint * alpha) / 255);
                }
            }
            cloudBackground.set(0, 0, cloudWidth, CLOUD_BAND_HEIGHT, cloudPixels);
            skyBackground.setColor(quantizedSky);
            lastCloudTint = cloudTint;
            lastSkyColor = quantizedSky;
        }

        int dayShift = (int)(dayTime * 0.00375f);
        int yawShift = (int)(yawDegrees * cloudWidth / 360.0f);
        int cropX = -yawShift - dayShift;
        int cropY = -(int)(pitchDegrees * 0.35f);
        float fovScale = tanHalfFov / NORMAL_TAN_HALF_FOV;
        int cropWidth = (int)(viewportWidth * fovScale);
        int cropHeight = (int)(viewportHeight * fovScale);
        if (cropWidth < 1) cropWidth = 1;
        if (cropHeight < 1) cropHeight = 1;
        skyBackground.setCrop(cropX, cropY, cropWidth, cropHeight);
    }

    private int quantizeColor(int value) {
        return value / CLOUD_COLOR_STEP * CLOUD_COLOR_STEP;
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
