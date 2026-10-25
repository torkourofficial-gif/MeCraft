package mecraft;

import java.io.IOException;
import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Appearance;
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
    private final Group yawCounter = new Group();
    private final Group celestialRotation = new Group();
    private final Mesh stars;
    private final Material starsMaterial;
    private final Material cloudsMaterial;
    private final Material oceanMaterial;
    private VertexArray cloudUVArray;
    private final short[] cloudUV = new short[12];

    SkySystem(Group cameraPivot) throws IOException {
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
        Texture2D cloudTexture = new Texture2D(new Image2D(Image2D.RGBA, cloudImage));
        cloudTexture.setFiltering(Texture2D.FILTER_NEAREST, Texture2D.FILTER_NEAREST);
        cloudTexture.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        Appearance cloudAppearance = alphaAppearance(cloudTexture);
        cloudsMaterial = cloudAppearance.getMaterial();
        Mesh cloudPlane = makeCloudPlane(cloudAppearance);
        yawCounter.addChild(cloudPlane);

        Appearance oceanAppearance = makeOceanAppearance();
        oceanMaterial = oceanAppearance.getMaterial();
        yawCounter.addChild(makeOceanPlane(oceanAppearance));
    }

    void update(VoxelWorld world, float yaw, float celestialAngle, float daylight) {
        yawCounter.setOrientation(-yaw, 0.0f, 1.0f, 0.0f);
        celestialRotation.setOrientation(celestialAngle * 57.29578f,
                1.0f, 0.0f, 0.0f);

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

        int cloudTint = (int)(190.0f + daylight * 65.0f);
        int tint = 0xFF000000 | (cloudTint << 16) |
                   (cloudTint << 8) | cloudTint;
        cloudsMaterial.setColor(Material.AMBIENT, tint);
        cloudsMaterial.setColor(Material.DIFFUSE, tint);
        oceanMaterial.setColor(Material.AMBIENT, 0xFFD8F1FF);
        oceanMaterial.setColor(Material.DIFFUSE, 0xFFD8F1FF);

        // Indev advances the cloud texture a tiny amount each world tick.
        int shift = (int)(world.dayTime * 0.00375f);
        int repeat = 3 * 256;
        int u = shift % repeat;
        cloudUV[0] = (short)u;          cloudUV[1] = 0;
        cloudUV[2] = (short)(u + repeat); cloudUV[3] = 0;
        cloudUV[4] = (short)(u + repeat); cloudUV[5] = (short)repeat;
        cloudUV[6] = (short)u;          cloudUV[7] = 0;
        cloudUV[8] = (short)(u + repeat); cloudUV[9] = (short)repeat;
        cloudUV[10] = (short)u;         cloudUV[11] = (short)repeat;
        cloudUVArray.set(0, 6, cloudUV);
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

    private Mesh makeCloudPlane(Appearance appearance) {
        // Indev lays the cloud texture out over 512-block quads at 0.5/1024
        // UV scale: one full texture repeat spans 2048 world blocks. Keep
        // three texture repeats here, so the plane spans 6144 blocks.
        short[] corners = largeQuad(-3072.0f, VoxelWorld.HEIGHT + 10, -3072.0f,
                3072.0f, VoxelWorld.HEIGHT + 10, 3072.0f);
        short[] p = triangleQuad(corners, 3);
        int span = 3 * 256;
        cloudUV[0] = 0; cloudUV[1] = 0;
        cloudUV[2] = (short)span; cloudUV[3] = 0;
        cloudUV[4] = (short)span; cloudUV[5] = (short)span;
        cloudUV[6] = 0; cloudUV[7] = 0;
        cloudUV[8] = (short)span; cloudUV[9] = (short)span;
        cloudUV[10] = 0; cloudUV[11] = (short)span;
        VertexArray positions = new VertexArray(6, 3, 2);
        positions.set(0, 6, p);
        cloudUVArray = new VertexArray(6, 2, 2);
        cloudUVArray.set(0, 6, cloudUV);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, 1.0f / 16.0f, null);
        vertices.setTexCoords(0, cloudUVArray, 1.0f / 256.0f, null);
        return new Mesh(vertices, new TriangleStripArray(0, new int[] { 3, 3 }), appearance);
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
        float y = VoxelWorld.SEA_LEVEL + 0.98f;
        short[] corners = largeQuad(-3072.0f, y, -3072.0f, 3072.0f, y, 3072.0f);
        short[] p = triangleQuad(corners, 3);
        short[] uv = new short[] {
            0,0, 768,0, 768,768, 0,0, 768,768, 0,768
        };
        return mesh(p, uv, 1.0f / 16.0f, 1.0f / 256.0f, appearance);
    }

    private short[] largeQuad(float x0, float y, float z0, float x1, float y1, float z1) {
        return new short[] {
            largeFixed(x0), largeFixed(y), largeFixed(z0),
            largeFixed(x1), largeFixed(y), largeFixed(z0),
            largeFixed(x1), largeFixed(y1), largeFixed(z1),
            largeFixed(x0), largeFixed(y1), largeFixed(z1)
        };
    }

    private short largeFixed(float value) {
        return (short)(value * 16.0f);
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
