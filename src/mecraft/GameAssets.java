package mecraft;

import java.io.IOException;
import javax.microedition.lcdui.Image;

/** Loads resources from either NetBeans' /mecraft package or a flat JAR root. */
final class GameAssets {
    private GameAssets() { }

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
}
