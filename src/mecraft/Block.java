package mecraft;

/** Compact block palette for the Indev-style island world. */
final class Block {
    static final byte AIR = 0;
    static final byte GRASS = 1;
    static final byte DIRT = 2;
    static final byte STONE = 3;
    static final byte SAND = 4;
    static final byte WATER = 5;
    static final byte LOG = 6;
    static final byte LEAVES = 7;
    static final byte BEDROCK = 8;
    static final byte COAL = 9;
    static final byte IRON = 10;
    static final byte PLANKS = 11;

    static final int COUNT = 12;
    static final int[] TILE = new int[] { 0, 0, 2, 1, 18, 205, 20, 52, 17, 34, 33, 4 };
    static final String[] NAME = new String[] {
        "Aire", "Césped", "Tierra", "Piedra", "Arena", "Agua",
        "Tronco", "Hojas", "Roca madre", "Carbón", "Hierro", "Tablones"
    };

    private Block() { }

    static boolean solid(byte id) {
        return id != AIR && id != WATER;
    }

    static boolean faceVisible(byte current, byte neighbor) {
        if (neighbor == AIR) return true;
        if (neighbor == WATER) return current != WATER;
        if (neighbor == LEAVES) return current != LEAVES;
        return false;
    }

    static int topTile(byte id) {
        if (id == GRASS) return 0;
        if (id == LOG) return 21;
        return TILE[id & 255];
    }

    static int sideTile(byte id) {
        if (id == GRASS) return 3;
        if (id == LOG) return 20;
        return TILE[id & 255];
    }

    /** Sprite positions in the supplied 10-column gui_blocks.png atlas. */
    static int inventoryIconTile(byte id) {
        switch (id) {
            case GRASS: return 9;
            case DIRT: return 8;
            case STONE: return 7;
            case SAND: return 14;
            case LOG: return 16;
            case LEAVES: return 4;
            case COAL: return 38;
            case IRON: return 39;
            case PLANKS: return 5;
            default: return 7;
        }
    }
}

