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
    static final byte CRAFTING_TABLE = 12;
    static final byte FURNACE = 13;

    static final int COUNT = 14;
    static final int[] TILE = new int[] { 0, 0, 2, 1, 18, 205, 20, 52, 17, 34, 33, 4, 59, 45 };
    static final String[] NAME = new String[] {
        "Aire", "Césped", "Tierra", "Piedra", "Arena", "Agua",
        "Tronco", "Hojas", "Roca madre", "Carbón", "Hierro", "Tablones",
        "Mesa de crafteo", "Horno"
    };

    private Block() { }

    static boolean solid(byte id) {
        return id != AIR && id != WATER;
    }

    /** Mining time in 50 ms game-loop steps when standing on dry ground. */
    static int breakTicks(byte id) {
        float strength = breakStrength(id, true, false);
        if (strength <= 0.0f) return 0x7FFFFFFF;
        return (int)(1.0f / strength + 0.999f);
    }

    /** Port of Indev blockStrength for an empty hand, with its hardness table. */
    static float breakStrength(byte id, boolean grounded, boolean submerged) {
        int hardnessTenths = hardnessTenths(id);
        if (hardnessTenths < 0) return 0.0f;
        if (hardnessTenths == 0) return 1.0f;
        boolean canHarvest = canHarvest(id);
        float strength = 10.0f / ((float)hardnessTenths * (canHarvest ? 30.0f : 100.0f));
        if (canHarvest && submerged) strength /= 5.0f;
        if (canHarvest && !grounded) strength /= 5.0f;
        return strength;
    }

    static boolean canHarvest(byte id) {
        return id != STONE && id != COAL && id != IRON && id != FURNACE && id != BEDROCK;
    }

    static boolean dropsItem(byte id) {
        return id != AIR && id != WATER && id != BEDROCK;
    }

    static byte dropId(byte id) {
        return id == GRASS ? DIRT : id;
    }

    private static int hardnessTenths(byte id) {
        if (id == BEDROCK) return -1;
        if (id == LEAVES) return 2;
        if (id == DIRT || id == SAND) return 5;
        if (id == GRASS) return 6;
        if (id == STONE) return 15;
        if (id == LOG || id == PLANKS) return 20;
        if (id == CRAFTING_TABLE) return 25;
        if (id == COAL || id == IRON) return 30;
        if (id == FURNACE) return 35;
        return 10;
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
        if (id == CRAFTING_TABLE) return 43;
        if (id == FURNACE) return 1;
        return TILE[id & 255];
    }

    static int sideTile(byte id) {
        if (id == GRASS) return 3;
        if (id == LOG) return 20;
        if (id == CRAFTING_TABLE) return 59;
        if (id == FURNACE) return 45;
        return TILE[id & 255];
    }

    /** Indev terrain tile for the renderer's face order: top, x-, x+, z-, z+, bottom. */
    static int faceTile(byte id, int face) {
        if (face == 0) return topTile(id);
        if (face == 5) {
            if (id == GRASS) return TILE[DIRT];
            if (id == CRAFTING_TABLE) return TILE[PLANKS];
            if (id == FURNACE) return 1;
            return topTile(id);
        }
        if (id == CRAFTING_TABLE) return (face == 1 || face == 3) ? 59 : 60;
        if (id == FURNACE) return face == 4 ? 44 : 45;
        return sideTile(id);
    }

}
