package mecraft;

import java.io.IOException;
import java.io.InputStream;
import javax.microedition.lcdui.Image;

/** Loads resources from either NetBeans' /mecraft package or a flat JAR root. */
final class GameAssets {
    private static Image terrain;

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

    static InputStream stream(String fileName) throws IOException {
        String packaged = "/mecraft/" + fileName;
        InputStream input = GameAssets.class.getResourceAsStream(packaged);
        if (input == null) input = GameAssets.class.getResourceAsStream("/" + fileName);
        if (input == null) throw new IOException("Recurso no encontrado: " + packaged);
        return input;
    }
}
