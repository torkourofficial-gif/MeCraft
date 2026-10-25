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
    private VertexArray cloudUVArray;
    private final short[] cloudUV = new short[8];

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
    }

    void update(VoxelWorld world, float yaw, float celestialAngle, float daylight) {
        yawCounter.setOrientation(-yaw, 0.0f, 1.0f, 0.0f);
        celestialRotation.setOrientation(celestialAngle * 57.29578f,
                1.0f, 0.0f, 0.0f);

        float night = 1.0f - daylight;
        float starLevel = night * night;
        stars.setRenderingEnable(starLevel > 0.015f);
        int brightness = (int)(starLevel * 255.0f);
        int starColor = 0xFF000000 | (brightness << 16) |
                        (brightness << 8) | brightness;
        starsMaterial.setColor(Material.AMBIENT, starColor);
        starsMaterial.setColor(Material.DIFFUSE, starColor);

        int cloudTint = (int)(190.0f + daylight * 65.0f);
        int tint = 0xFF000000 | (cloudTint << 16) |
                   (cloudTint << 8) | cloudTint;
        cloudsMaterial.setColor(Material.AMBIENT, tint);
        cloudsMaterial.setColor(Material.DIFFUSE, tint);

        // Indev advances the cloud texture a tiny amount each world tick.
        int shift = (int)(world.dayTime * 0.00375f);
        int repeat = 16 * 256;
        int u = shift % repeat;
        cloudUV[0] = (short)u;          cloudUV[1] = 0;
        cloudUV[2] = (short)(u + repeat); cloudUV[3] = 0;
        cloudUV[4] = (short)(u + repeat); cloudUV[5] = (short)repeat;
        cloudUV[6] = (short)u;          cloudUV[7] = (short)repeat;
        cloudUVArray.set(0, 4, cloudUV);
    }

    private Mesh skyQuad(float y, float halfSize, Appearance appearance) {
        short[] p = new short[] {
            fixed(-halfSize), fixed(y), fixed(-halfSize),
            fixed(halfSize), fixed(y), fixed(-halfSize),
            fixed(halfSize), fixed(y), fixed(halfSize),
            fixed(-halfSize), fixed(y), fixed(halfSize)
        };
        short[] uv = new short[] { 0, 0, 256, 0, 256, 256, 0, 256 };
        return mesh(p, uv, FIXED_SCALE, 1.0f / 256.0f, appearance);
    }

    private Mesh makeCloudPlane(Appearance appearance) {
        short[] p = new short[] {
            fixed(-64), fixed(18), fixed(-64),
            fixed(64), fixed(18), fixed(-64),
            fixed(64), fixed(18), fixed(64),
            fixed(-64), fixed(18), fixed(64)
        };
        int span = 16 * 256;
        short[] uv = new short[] { 0, 0, (short)span, 0, (short)span, (short)span, 0, (short)span };
        VertexArray positions = new VertexArray(4, 3, 2);
        positions.set(0, 4, p);
        cloudUVArray = new VertexArray(4, 2, 2);
        cloudUVArray.set(0, 4, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, FIXED_SCALE, null);
        vertices.setTexCoords(0, cloudUVArray, 1.0f / 256.0f, null);
        return new Mesh(vertices, new TriangleStripArray(0, new int[] { 4 }), appearance);
    }

    private Mesh makeStars(Appearance appearance) {
        short[] p = new short[STAR_COUNT * 4 * 3];
        short[] uv = new short[STAR_COUNT * 4 * 2];
        int[] strips = new int[STAR_COUNT];
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
            float ux = cx / -31.0f, uy = cy / -31.0f, uz = cz / -31.0f;
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
            p[pa++] = fixed(cx - ax - bx); p[pa++] = fixed(cy - ay - by); p[pa++] = fixed(cz - az - bz);
            p[pa++] = fixed(cx + ax - bx); p[pa++] = fixed(cy + ay - by); p[pa++] = fixed(cz + az - bz);
            p[pa++] = fixed(cx + ax + bx); p[pa++] = fixed(cy + ay + by); p[pa++] = fixed(cz + az + bz);
            p[pa++] = fixed(cx - ax + bx); p[pa++] = fixed(cy - ay + by); p[pa++] = fixed(cz - az + bz);
            uv[ta++] = 0; uv[ta++] = 0;
            uv[ta++] = 256; uv[ta++] = 0;
            uv[ta++] = 256; uv[ta++] = 256;
            uv[ta++] = 0; uv[ta++] = 256;
            strips[i] = 4;
        }
        VertexArray positions = new VertexArray(STAR_COUNT * 4, 3, 2);
        positions.set(0, STAR_COUNT * 4, p);
        VertexArray texCoords = new VertexArray(STAR_COUNT * 4, 2, 2);
        texCoords.set(0, STAR_COUNT * 4, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, FIXED_SCALE, null);
        vertices.setTexCoords(0, texCoords, 1.0f / 256.0f, null);
        return new Mesh(vertices, new TriangleStripArray(0, strips), appearance);
    }

    private Mesh mesh(short[] p, short[] uv, float positionScale,
            float uvScale, Appearance appearance) {
        VertexArray positions = new VertexArray(4, 3, 2);
        positions.set(0, 4, p);
        VertexArray texCoords = new VertexArray(4, 2, 2);
        texCoords.set(0, 4, uv);
        VertexBuffer vertices = new VertexBuffer();
        vertices.setPositions(positions, positionScale, null);
        vertices.setTexCoords(0, texCoords, uvScale, null);
        return new Mesh(vertices, new TriangleStripArray(0, new int[] { 4 }), appearance);
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
