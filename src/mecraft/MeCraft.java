package mecraft;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

/** MIDP 2.0 entry point for MeCraft. */
public final class MeCraft extends MIDlet {
    private GameCanvas canvas;
    private boolean closing;

    protected void startApp() {
        if (canvas == null) {
            canvas = new GameCanvas(this);
            Display.getDisplay(this).setCurrent(canvas);
            canvas.start();
        } else {
            Display.getDisplay(this).setCurrent(canvas);
            canvas.resumeGame();
        }
    }

    protected void pauseApp() {
        if (canvas != null) canvas.pauseGame();
    }

    protected void destroyApp(boolean unconditional) {
        closeGame();
    }

    void closeGame() {
        if (closing) return;
        closing = true;
        if (canvas != null) canvas.stop();
        notifyDestroyed();
    }
}
