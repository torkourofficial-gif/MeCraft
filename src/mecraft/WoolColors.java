package mecraft;

import javax.microedition.lcdui.Image;

/** Classic 16-color wool palette and texture recoloring helpers. */
final class WoolColors {
    static final int COUNT = 16;
    private static final String[] NAMES = new String[] {
        "Blanca", "Naranja", "Magenta", "Azul claro", "Amarilla", "Lima",
        "Rosa", "Gris", "Gris claro", "Cian", "Morada", "Azul",
        "Marrón", "Verde", "Roja", "Negra"
    };
    private static final int[] RGB = new int[] {
        0xF0F0F0, 0xF9801D, 0xC74EBD, 0x3AB3DA,
        0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
        0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
        0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    };

    private WoolColors() { }

    static int rgb(int color) {
        if (color < 0 || color >= COUNT) color = 0;
        return RGB[color];
    }

    static String name(int color) {
        if (color < 0 || color >= COUNT) color = 0;
        return NAMES[color];
    }

    static Image texture(Image terrain, int color) {
        int tile = Block.atlasTile(Block.WOOL);
        int[] pixels = new int[16 * 16];
        terrain.getRGB(pixels, 0, 16, (tile & 15) * 16, (tile >> 4) * 16, 16, 16);
        recolor(pixels, color);
        return Image.createRGBImage(pixels, 16, 16, true);
    }

    static void recolor(int[] pixels, int color) {
        if (color <= 0 || color >= COUNT) return;
        int dye = RGB[color];
        int dr = dye >> 16 & 255, dg = dye >> 8 & 255, db = dye & 255;
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = pixel >>> 24;
            if (alpha == 0) continue;
            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
            int shade = (r * 30 + g * 59 + b * 11) / 100;
            pixels[i] = alpha << 24 | (dr * shade / 255) << 16 |
                        (dg * shade / 255) << 8 | db * shade / 255;
        }
    }
}
