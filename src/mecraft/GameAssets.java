package mecraft;

import java.io.IOException;
import java.io.InputStream;
import javax.microedition.lcdui.Image;

/** Loads resources from either NetBeans' /mecraft package or a flat JAR root. */
final class GameAssets {
    private static Image terrain;
    private static final Image[] playerSkins = new Image[5];
    private static final boolean[] playerSkinFallback = new boolean[5];
    private static Image defaultPlayerSkin;

    private GameAssets() { }

    static Image terrain() throws IOException {
        if (terrain == null) terrain = image("textures.png");
        return terrain;
    }

    static Image image(String fileName) throws IOException {
        String packaged = "/mecraft/" + fileName;
        try {
            return Image.createImage(packaged);
        } catch (IOException packagedMissing) {
            try {
                return Image.createImage("/" + fileName);
            } catch (IOException rootMissing) {
                throw new IOException("Recurso no encontrado: " + packaged + " ni /" + fileName);
            }
        }
    }

    /**
     * Loads each player skin at most once. Missing numbered skins share the
     * single decoded default image instead of decoding char.png repeatedly.
     */
    static synchronized Image playerSkin(int index) throws IOException {
        if (index < 0 || index >= playerSkins.length) index = 0;
        if (playerSkins[index] == null) {
            try {
                playerSkins[index] = image("skin" + (index + 1) + ".png");
            } catch (IOException missingSkin) {
                if (defaultPlayerSkin == null) defaultPlayerSkin = image("char.png");
                playerSkins[index] = defaultPlayerSkin;
                playerSkinFallback[index] = true;
            }
        }
        return playerSkins[index];
    }

    static synchronized boolean playerSkinFallback(int index) {
        if (index < 0 || index >= playerSkins.length) index = 0;
        try { playerSkin(index); }
        catch (IOException missingDefault) { return true; }
        return playerSkinFallback[index];
    }

    static InputStream stream(String fileName) throws IOException {
        String packaged = "/mecraft/" + fileName;
        InputStream input = GameAssets.class.getResourceAsStream(packaged);
        if (input == null) input = GameAssets.class.getResourceAsStream("/" + fileName);
        if (input == null) throw new IOException("Recurso no encontrado: " + packaged);
        return input;
    }
}
