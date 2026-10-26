package mecraft;

/** Shared Alpha-era grass and foliage colors for world and item rendering. */
final class AlphaTint {
    static final int GRASS_TOP = 0xFF7FB238;
    static final int LEAVES = 0xFF48B518;

    private AlphaTint() { }

    static void apply(int[] pixels, int tint) {
        int tr = (tint >> 16) & 255;
        int tg = (tint >> 8) & 255;
        int tb = tint & 255;
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = pixel >>> 24;
            if (alpha == 0) continue;
            int r = (pixel >> 16) & 255;
            int g = (pixel >> 8) & 255;
            int b = pixel & 255;
            int luminance = (r * 77 + g * 150 + b * 29) >> 8;
            pixels[i] = (alpha << 24) | (luminance * tr / 255 << 16) |
                    (luminance * tg / 255 << 8) | (luminance * tb / 255);
        }
    }
}
