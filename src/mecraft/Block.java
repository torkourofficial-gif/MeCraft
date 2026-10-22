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
    static final byte COBBLESTONE = 13;
    static final byte FARMLAND = 14;
    static final byte RED_FLOWER = 15;
    static final byte YELLOW_FLOWER = 16;
    static final byte TORCH = 17;
    static final byte WOOL = 18;

    static final int LEGACY_COUNT = 14;
    static final int COUNT = 19;
    static final int[] TILE = new int[] { 0, 0, 2, 1, 18, 205, 20, 52, 17, 34, 33, 4, 59, 16, 2, 12, 13, 80, 64 };
    static final String[] NAME = new String[] {
        "Aire", "Césped", "Tierra", "Piedra", "Arena", "Agua",
        "Tronco", "Hojas", "Roca madre", "Carbón", "Hierro", "Tablones",
        "Mesa de crafteo", "Piedra labrada", "Tierra de cultivo", "Flor roja", "Flor amarilla", "Antorcha", "Lana"
    };

    private Block() { }

    static boolean solid(byte id) {
        return id != AIR && id != WATER && !isFlower(id) && id != TORCH;
    }

    static boolean isFlower(byte id) {
        return id == RED_FLOWER || id == YELLOW_FLOWER || id == TORCH;
    }

    static boolean replaceable(byte id) {
        return id == AIR || id == WATER || id == RED_FLOWER || id == YELLOW_FLOWER;
    }

    /** Mining time in 50 ms game-loop steps when standing on dry ground. */
    static int breakTicks(byte id) {
        float strength = breakStrength(id, true, false);
        if (strength <= 0.0f) return 0x7FFFFFFF;
        return (int)(1.0f / strength + 0.999f);
    }

    /** Port of Indev blockStrength for an empty hand, with its hardness table. */
    static float breakStrength(byte id, boolean grounded, boolean submerged) {
        return breakStrength(id, grounded, submerged, AIR);
    }

    /** Block.blockStrength port with the active Indev tool's speed and harvest rule. */
    static float breakStrength(byte id, boolean grounded, boolean submerged, int heldItem) {
        int hardnessTenths = hardnessTenths(id);
        if (hardnessTenths < 0) return 0.0f;
        if (hardnessTenths == 0) return 1.0f;
        boolean canHarvest = ItemCatalog.canHarvest(heldItem, id);
        float efficiency = canHarvest ? ItemCatalog.efficiency(heldItem, id) : 1.0f;
        float strength = efficiency * 10.0f / ((float)hardnessTenths * (canHarvest ? 30.0f : 100.0f));
        if (canHarvest && submerged) strength /= 5.0f;
        if (canHarvest && !grounded) strength /= 5.0f;
        return strength;
    }

    static boolean canHarvest(byte id) {
        return canHarvest(id, AIR);
    }

    static boolean canHarvest(byte id, int heldItem) {
        return hardnessTenths(id) >= 0 && ItemCatalog.canHarvest(heldItem, id);
    }

    static boolean dropsItem(byte id) {
        return id != AIR && id != WATER && id != BEDROCK;
    }

    static byte dropId(byte id) {
        if (id == GRASS || id == FARMLAND) return DIRT;
        return id == STONE ? COBBLESTONE : id;
    }

    private static int hardnessTenths(byte id) {
        if (id == BEDROCK) return -1;
        if (isFlower(id)) return 0;
        if (id == LEAVES) return 2;
        if (id == DIRT || id == SAND) return 5;
        if (id == GRASS) return 6;
        if (id == STONE) return 15;
        if (id == LOG || id == PLANKS) return 20;
        if (id == CRAFTING_TABLE) return 25;
        if (id == WOOL) return 8;
        if (id == COAL || id == IRON) return 30;
        if (id == COBBLESTONE) return 20;
        if (id == FARMLAND) return 6;
        return 10;
    }

    static boolean faceVisible(byte current, byte neighbor) {
        if (neighbor == AIR || isFlower(neighbor)) return true;
        if (neighbor == WATER) return current != WATER;
        if (neighbor == LEAVES) return current != LEAVES;
        return false;
    }

    static int topTile(byte id) {
        if (id == GRASS) return 0;
        if (id == LOG) return 21;
        if (id == CRAFTING_TABLE) return 43;
        if (id == FARMLAND) return 87;
        return TILE[id & 255];
    }

    static int sideTile(byte id) {
        if (id == GRASS) return 3;
        if (id == LOG) return 20;
        if (id == CRAFTING_TABLE) return 59;
        if (id == FARMLAND) return 2;
        return TILE[id & 255];
    }

    /** Indev terrain tile for the renderer's face order: top, x-, x+, z-, z+, bottom. */
    static int faceTile(byte id, int face) {
        if (face == 0) return topTile(id);
        if (face == 5) {
            if (id == GRASS) return TILE[DIRT];
            if (id == CRAFTING_TABLE) return TILE[PLANKS];
            if (id == FARMLAND) return 2;
            return topTile(id);
        }
        if (id == CRAFTING_TABLE) return (face == 1 || face == 3) ? 59 : 60;
        return sideTile(id);
    }

}
