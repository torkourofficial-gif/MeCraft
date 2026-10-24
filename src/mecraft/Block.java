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
    static final byte TNT = 19;

    static final int LEGACY_COUNT = 14;
    static final int DECORATIVE_START = 20;
    /* Cells with image detail in the bundled 16x16 terrain atlas. A dot is
       an empty/placeholder cell; these stay out of the creative palette. */
    private static final String[] TEXTURED_TILE_ROWS = new String[] {
        "#++#+####++.#+.+", "#+###########+..", "##############..", "#+############..",
        "##++########....", "####++#++#######", "####++..........", "#..#............",
        "#...............", "................", "................", "................",
        ".............+++", "..............++", ".............+++", "++++++++++....++"
    };
    private static final int[] EXISTING_TILES = new int[] {
        0,2,1,18,20,52,17,34,33,4,59,16,12,13,64,21,43,60,80,87
    };
    private static final int[] EXTRA_TILES = makeExtraTiles();
    static final int COUNT = DECORATIVE_START + EXTRA_TILES.length;
    static final int[] TILE = makeTiles();
    static final String[] NAME = makeNames();
    static final int[] CREATIVE_PALETTE = makeCreativePalette();
    /** Uses the original wooden-door tile already present in the terrain atlas. */
    static final byte DOOR = (byte)findTileBlock(97);

    private static int findTileBlock(int tile) {
        for (int i = DECORATIVE_START; i < TILE.length; i++)
            if (TILE[i] == tile) return i;
        return -1;
    }

    private static int[] makeExtraTiles() {
        int[] temporary = new int[110];
        int count = 0;
        for (int tile = 0; tile < 80; tile++) {
            if (!isAtlasBlockTile(tile) || !hasAtlasTexture(tile)) continue;
            if (tile == 8 || tile == 9 || tile == 10 || isExistingTile(tile)) continue;
            temporary[count++] = tile;
        }
        // The atlas also contains the old door, foliage, and rail tile cells.
        int[] lowerAtlasTiles = new int[] { 81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,
                                            97,98,99,100,101,112,128 };
        for (int i = 0; i < lowerAtlasTiles.length; i++) {
            int tile = lowerAtlasTiles[i];
            if (!isExistingTile(tile) && hasAtlasTexture(tile)) temporary[count++] = tile;
        }
        int[] result = new int[count];
        System.arraycopy(temporary, 0, result, 0, count);
        return result;
    }

    private static int[] makeCreativePalette() {
        int[] temporary = new int[COUNT];
        int count = 0;
        // Keep the original gameplay blocks (except non-placeable world blocks).
        for (int id = 1; id < DECORATIVE_START; id++) {
            if (id != WATER && id != BEDROCK) temporary[count++] = id;
        }
        for (int id = DECORATIVE_START; id < COUNT; id++) {
            if (hasAtlasTexture(TILE[id])) temporary[count++] = id;
        }
        int[] palette = new int[count];
        System.arraycopy(temporary, 0, palette, 0, count);
        return palette;
    }

    private static boolean hasAtlasTexture(int tile) {
        if (tile < 0 || tile >= 256) return false;
        return TEXTURED_TILE_ROWS[tile >> 4].charAt(tile & 15) != '.';
    }

    private static boolean isAtlasBlockTile(int tile) {
        int row = tile >> 4, column = tile & 15;
        if (row == 0) return column <= 12 || column == 14;
        if (row == 1) return column <= 13;
        if (row == 2) return column <= 13;
        if (row == 3 || row == 4) return column <= 11;
        return false;
    }

    private static boolean isExistingTile(int tile) {
        for (int i = 0; i < EXISTING_TILES.length; i++) if (EXISTING_TILES[i] == tile) return true;
        return false;
    }

    private static int[] makeTiles() {
        int[] result = new int[COUNT];
        int[] basic = new int[] { 0,0,2,1,18,205,20,52,17,34,33,4,59,16,2,12,13,80,64,8 };
        System.arraycopy(basic, 0, result, 0, basic.length);
        for (int i = 0; i < EXTRA_TILES.length; i++) result[DECORATIVE_START + i] = EXTRA_TILES[i];
        return result;
    }

    private static String[] makeNames() {
        String[] result = new String[COUNT];
        String[] basic = new String[] {
        "Aire", "Césped", "Tierra", "Piedra", "Arena", "Agua",
        "Tronco", "Hojas", "Roca madre", "Carbón", "Hierro", "Tablones",
        "Mesa de crafteo", "Piedra labrada", "Tierra de cultivo", "Flor roja", "Flor amarilla", "Antorcha", "Lana", "TNT"
        };
        System.arraycopy(basic, 0, result, 0, basic.length);
        for (int i = 0; i < EXTRA_TILES.length; i++) {
            result[DECORATIVE_START + i] = EXTRA_TILES[i] == 97 ? "Puerta de madera" :
                    new StringBuffer().append("Decoración ").append(EXTRA_TILES[i]).toString();
        }
        return result;
    }

    private Block() { }

    static boolean solid(byte id) {
        return id != AIR && id != WATER && id != DOOR && !isFlower(id) && id != TORCH;
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
        return id != AIR && id != WATER && id != BEDROCK && id != TNT;
    }

    static byte dropId(byte id) {
        if (id == GRASS || id == FARMLAND) return DIRT;
        return id == STONE ? COBBLESTONE : id;
    }

    private static int hardnessTenths(byte id) {
        if (id == BEDROCK) return -1;
        if (isFlower(id) || id == TNT) return 0;
        if (id == LEAVES) return 2;
        if (id == DIRT || id == SAND) return 5;
        if (id == GRASS) return 6;
        if (id == STONE) return 15;
        if (id == LOG || id == PLANKS) return 20;
        if (id == CRAFTING_TABLE) return 25;
        if (id == WOOL) return 8;
        if (id == COAL || id == IRON) return 30;
        if (id == DOOR) return 30;
        if (id == COBBLESTONE) return 20;
        if (id == FARMLAND) return 6;
        return 10;
    }

    static boolean faceVisible(byte current, byte neighbor) {
        if (neighbor == AIR || isFlower(neighbor) || neighbor == DOOR) return true;
        if (neighbor == WATER) return current != WATER;
        if (neighbor == LEAVES) return current != LEAVES;
        return false;
    }

    static int topTile(byte id) {
        if (id == TNT) return 9;
        if (isDecorative(id)) return TILE[id & 255];
        if (id == GRASS) return 0;
        if (id == LOG) return 21;
        if (id == CRAFTING_TABLE) return 43;
        if (id == FARMLAND) return 87;
        return TILE[id & 255];
    }

    static int sideTile(byte id) {
        if (id == DOOR) return 97;
        if (id == TNT) return 8;
        if (isDecorative(id)) return TILE[id & 255];
        if (id == GRASS) return 3;
        if (id == LOG) return 20;
        if (id == CRAFTING_TABLE) return 59;
        if (id == FARMLAND) return 2;
        return TILE[id & 255];
    }

    /** Indev terrain tile for the renderer's face order: top, x-, x+, z-, z+, bottom. */
    static int faceTile(byte id, int face) {
        if (id == DOOR) return face == 0 || face == 5 ? 81 : 97;
        if (face == 0) return topTile(id);
        if (id == TNT && face == 5) return 10;
        if (face == 5) {
            if (id == GRASS) return TILE[DIRT];
            if (id == CRAFTING_TABLE) return TILE[PLANKS];
            if (id == FARMLAND) return 2;
            return topTile(id);
        }
        if (id == CRAFTING_TABLE) return (face == 1 || face == 3) ? 59 : 60;
        return sideTile(id);
    }

    static boolean isDecorative(byte id) {
        return (id & 255) >= DECORATIVE_START && (id & 255) < COUNT && id != DOOR;
    }

    static int atlasTile(int id) {
        return id >= 0 && id < TILE.length ? TILE[id] : 0;
    }

}

