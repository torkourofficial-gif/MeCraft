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
    private static final int GRADIENT_HEIGHT = 64;
    private final Group yawCounter = new Group();
    private final Group celestialRotation = new Group();
    private final Background skyBackground;
    private final Mesh stars;
    private final Material starsMaterial;
    private final byte[] gradientColors = new byte[GRADIENT_HEIGHT * 3];
    private Image2D skyGradient;
    private byte[] gradientPixels;
    private int viewportWidth;
    private int viewportHeight;
    private int lastGradientSky = -1;
    private int lastDaylightLevel = -1;
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

        resize(width, height);
        updateGradient(1.0f, 0xFF82B8E8);
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0 ||
                (width == viewportWidth && height == viewportHeight)) return;
        viewportWidth = width;
        viewportHeight = height;
        skyGradient = new Image2D(Image2D.RGB, width, height);
        gradientPixels = new byte[width * height * 3];
        lastGradientSky = -1;
        lastDaylightLevel = -1;
        skyBackground.setImage(skyGradient);
        skyBackground.setImageMode(Background.BORDER, Background.BORDER);
        skyBackground.setCrop(0, 0, width, height);
    }

    void update(VoxelWorld world, float yaw, float celestialAngle,
            float daylight, int skyColor) {
        yawCounter.setOrientation(-yaw, 0.0f, 1.0f, 0.0f);
        celestialRotation.setOrientation(celestialAngle * 57.29578f,
                1.0f, 0.0f, 0.0f);
        updateGradient(daylight, skyColor);
        float night = 1.0f - daylight;
        float starLevel = night * night;
        boolean starsVisible = !world.nether && starLevel > 0.015f;
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

    /** Build a tiny 2D color gradient, refreshed only when its palette changes. */
    private void updateGradient(float daylight, int skyColor) {
        if (skyGradient == null || viewportWidth <= 0 || viewportHeight <= 0) return;
        int skyR = skyColor >> 16 & 255;
        int skyG = skyColor >> 8 & 255;
        int skyB = skyColor & 255;
        int quantizedSky = (skyR >> 3) << 10 | (skyG >> 3) << 5 | (skyB >> 3);
        int daylightLevel = (int)(daylight * 16.0f + 0.5f);
        if (daylightLevel < 0) daylightLevel = 0;
        if (daylightLevel > 16) daylightLevel = 16;
        if (quantizedSky == lastGradientSky && daylightLevel == lastDaylightLevel) return;

        float day = daylightLevel / 16.0f;
        int topR = (int)(8.0f + 40.0f * day);
        int topG = (int)(13.0f + 94.0f * day);
        int topB = (int)(35.0f + 167.0f * day);
        for (int y = 0; y < GRADIENT_HEIGHT; y++) {
            float t = y / (float)(GRADIENT_HEIGHT - 1);
            float blend = t * t * (3.0f - 2.0f * t);
            int at = y * 3;
            gradientColors[at] = (byte)(topR + (int)((skyR - topR) * blend));
            gradientColors[at + 1] = (byte)(topG + (int)((skyG - topG) * blend));
            gradientColors[at + 2] = (byte)(topB + (int)((skyB - topB) * blend));
        }
        int output = 0;
        for (int y = 0; y < viewportHeight; y++) {
            float sample = y * (GRADIENT_HEIGHT - 1.0f) /
                    (viewportHeight > 1 ? viewportHeight - 1.0f : 1.0f);
            int low = (int)sample;
            int high = low < GRADIENT_HEIGHT - 1 ? low + 1 : low;
            float fraction = sample - low;
            int lowAt = low * 3, highAt = high * 3;
            int red = (gradientColors[lowAt] & 255) +
                    (int)(((gradientColors[highAt] & 255) - (gradientColors[lowAt] & 255)) * fraction);
            int green = (gradientColors[lowAt + 1] & 255) +
                    (int)(((gradientColors[highAt + 1] & 255) - (gradientColors[lowAt + 1] & 255)) * fraction);
            int blue = (gradientColors[lowAt + 2] & 255) +
                    (int)(((gradientColors[highAt + 2] & 255) - (gradientColors[lowAt + 2] & 255)) * fraction);
            for (int x = 0; x < viewportWidth; x++) {
                gradientPixels[output++] = (byte)red;
                gradientPixels[output++] = (byte)green;
                gradientPixels[output++] = (byte)blue;
            }
        }
        skyGradient.set(0, 0, viewportWidth, viewportHeight, gradientPixels);
        lastGradientSky = quantizedSky;
        lastDaylightLevel = daylightLevel;
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
