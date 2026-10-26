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
    static final int WOOL_TINT_TILE_START = 240;

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
    static final byte CHEST = (byte)findTileBlock(128); // Reuse an otherwise decorative atlas slot to preserve saved item IDs.
    static final byte DOOR = (byte)findTileBlock(97);
    static final byte CACTUS = (byte)findTileBlock(70);
    static final int CACTUS_TOP_TILE = 71;
    static final byte SNOW = (byte)findTileBlock(66);
    static final byte PORTAL = (byte)findTileBlock(14);
    static final byte FIRE = (byte)findTileBlock(31);
    static final byte FURNACE = (byte)findTileBlock(62);
    static final byte LEVER = (byte)findTileBlock(96);
    static final byte REDSTONE_TORCH_ON = (byte)findTileBlock(99);
    static final byte PUMPKIN = (byte)findTileBlock(102);
    static final byte NETHERRACK = (byte)findTileBlock(103);
    static final byte SOUL_SAND = (byte)findTileBlock(104);
    static final byte GLOWSTONE = (byte)findTileBlock(105);
    static final byte REDSTONE_TORCH_OFF = (byte)findTileBlock(115);
    static final byte GOLD_ORE = (byte)findTileBlock(32);
    static final byte GRAVEL = (byte)findTileBlock(19);
    static final byte DIAMOND_ORE = (byte)findTileBlock(50);
    static final byte REDSTONE_ORE = (byte)findTileBlock(51);
    static final byte GOLD_BLOCK = (byte)findTileBlock(39);
    static final byte IRON_BLOCK = (byte)findTileBlock(38);
    static final byte DIAMOND_BLOCK = (byte)findTileBlock(40);
    static final byte CLAY_BLOCK = (byte)findTileBlock(72);
    static final byte OBSIDIAN = (byte)findTileBlock(37);
    static final byte GLASS = (byte)findTileBlock(49);
    static final byte BROWN_MUSHROOM = (byte)findTileBlock(29);
    static final byte RED_MUSHROOM = (byte)findTileBlock(28);
    static final byte SAPLING = (byte)findTileBlock(15);
    static final byte CROPS = (byte)findTileBlock(88);
    static final byte REED = (byte)findTileBlock(73);
    static final byte LAVA = (byte)findTileBlock(237);

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
            if (!isExistingTile(tile) && hasAtlasTexture(tile) && !containsTile(temporary, count, tile))
                temporary[count++] = tile;
        }
        int[] alphaSpecialTiles = new int[] { 31,62,96,99,102,103,104,105,115 };
        for (int i = 0; i < alphaSpecialTiles.length; i++) {
            int tile = alphaSpecialTiles[i];
            if (!isExistingTile(tile) && hasAtlasTexture(tile) && !containsTile(temporary, count, tile))
                temporary[count++] = tile;
        }
        int[] result = new int[count];
        System.arraycopy(temporary, 0, result, 0, count);
        // These atlas cells were loose placeholders in the old creative list.
        // Reuse their stable block IDs for Alpha's sapling and lava blocks.
        replaceTile(result, 3, 237);
        replaceTile(result, 5, 15);
        // Tile 14 is the Alpha portal, animated from portal.png rather than
        // the static terrain atlas. Reuse a nameless placeholder's stable ID.
        replaceTile(result, 22, 14);
        // Alpha's inactive furnace uses a special atlas tile that is outside
        // the normal block range. Reuse an unnamed placeholder slot to keep
        // COUNT (and the item IDs stored after the block range) stable.
        replaceTile(result, 41, 61);
        return result;
    }

    private static void replaceTile(int[] tiles, int oldTile, int newTile) {
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] == oldTile) { tiles[i] = newTile; return; }
        }
    }

    private static boolean containsTile(int[] tiles, int count, int tile) {
        for (int i = 0; i < count; i++) if (tiles[i] == tile) return true;
        return false;
    }

    private static int[] makeCreativePalette() {
        int[] temporary = new int[COUNT];
        int count = 0;
        // Keep the core Alpha blocks, including both liquids and bedrock.
        for (int id = 1; id < DECORATIVE_START; id++) {
            temporary[count++] = id;
        }
        for (int id = DECORATIVE_START; id < COUNT; id++) {
            int tile = TILE[id];
            if (alphaBlockName(tile) != null && hasAtlasTexture(tile))
                temporary[count++] = id;
        }
        int[] palette = new int[count];
        System.arraycopy(temporary, 0, palette, 0, count);
        return palette;
    }

    private static boolean hasAtlasTexture(int tile) {
        if (tile < 0 || tile >= 256) return false;
        if (tile == 14 || tile == 31 || tile == 61 || tile == 62 || tile == 96 || tile == 99 || tile == 102 ||
            tile == 103 || tile == 104 || tile == 105 || tile == 115) return true;
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
            String name = alphaBlockName(EXTRA_TILES[i]);
            result[DECORATIVE_START + i] = name == null ? "Bloque sin nombre" : name;
        }
        return result;
    }

    private static String alphaBlockName(int tile) {
        if (tile == 14) return "Portal del Nether";
        if (tile == 6) return "Losa de piedra";
        if (tile == 7) return "Ladrillos";
        if (tile == 15) return "Brote";
        if (tile == 19) return "Grava";
        if (tile == 237) return "Lava";
        if (tile == 28) return "Seta roja";
        if (tile == 29) return "Seta marrón";
        if (tile == 31) return "Fuego";
        if (tile == 32) return "Mena de oro";
        if (tile == 35) return "Estantería";
        if (tile == 36) return "Piedra musgosa";
        if (tile == 37) return "Obsidiana";
        if (tile == 38) return "Bloque de hierro";
        if (tile == 39) return "Bloque de oro";
        if (tile == 40) return "Bloque de diamante";
        if (tile == 48) return "Esponja";
        if (tile == 49) return "Cristal";
        if (tile == 50) return "Mena de diamante";
        if (tile == 51) return "Mena de redstone";
        if (tile == 61) return "Horno apagado";
        if (tile == 62) return "Horno encendido";
        if (tile == 65) return "Generador de mobs";
        if (tile == 66) return "Nieve";
        if (tile == 67) return "Hielo";
        if (tile == 70) return "Cactus";
        if (tile == 72) return "Arcilla";
        if (tile == 73) return "Caña de azúcar";
        if (tile == 74) return "Tocadiscos";
        if (tile == 81) return "Cartel";
        if (tile == 83) return "Escalera";
        if (tile == 84) return "Cable de redstone";
        if (tile == 85) return "Cofre";
        if (tile == 86) return "Bloque de oro";
        if (tile == 87) return "Bloque de hierro";
        if (tile == 88) return "Cultivo";
        if (tile == 96) return "Palanca";
        if (tile == 97) return "Puerta de madera";
        if (tile == 98) return "Puerta de hierro";
        if (tile == 99 || tile == 115) return "Antorcha de redstone";
        if (tile == 102) return "Calabaza";
        if (tile == 103) return "Roca abisal";
        if (tile == 104) return "Arena de almas";
        if (tile == 105) return "Piedra luminosa";
        if (tile == 112) return "Riel";
        if (tile == 128) return "Riel";
        return null;
    }

    private Block() { }

    static boolean solid(byte id) {
        return id != AIR && !isFluid(id) && id != DOOR && !isFlower(id) && id != TORCH;
    }

    static boolean isFluid(byte id) {
        return id == WATER || id == LAVA;
    }

    static boolean isFlower(byte id) {
        return id == RED_FLOWER || id == YELLOW_FLOWER || id == TORCH || id == PORTAL ||
               id == FIRE || id == BROWN_MUSHROOM || id == RED_MUSHROOM || id == SAPLING ||
               id == CROPS || id == REED;
    }

    static boolean replaceable(byte id) {
        return id == AIR || isFluid(id) || isFlower(id);
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
        return id != AIR && !isFluid(id) && id != BEDROCK && id != TNT && id != PORTAL;
    }

    static int dropId(byte id) {
        if (id == CHEST) return CHEST;
        if (id == GRASS || id == FARMLAND) return DIRT;
        if (id == STONE) return COBBLESTONE;
        if (id == COAL) return ItemCatalog.COAL_ITEM;
        if (id == DIAMOND_ORE) return ItemCatalog.DIAMOND;
        if (id == REDSTONE_ORE || id == REDSTONE_TORCH_ON || id == REDSTONE_TORCH_OFF)
            return ItemCatalog.REDSTONE;
        if (id == GLOWSTONE) return ItemCatalog.GLOWSTONE_DUST;
        if (id == CLAY_BLOCK) return ItemCatalog.CLAY;
        if (id == SAPLING) return ItemCatalog.SEEDS;
        if (id == CROPS) return ItemCatalog.WHEAT;
        if (id == REED) return ItemCatalog.REED;
        return id;
    }

    private static int hardnessTenths(byte id) {
        if (id == BEDROCK || id == PORTAL) return -1;
        if (isFlower(id) || id == TNT) return 0;
        if (isFluid(id)) return 1000;
        if (id == LEAVES) return 2;
        if (id == SNOW || id == CACTUS) return 5;
        if (id == DIRT || id == SAND) return 5;
        if (id == GRASS) return 6;
        if (id == STONE) return 15;
        if (id == LOG || id == PLANKS) return 20;
        if (id == CRAFTING_TABLE || id == CHEST) return 25;
        if (id == WOOL) return 8;
        if (id == COAL || id == IRON) return 30;
        if (id == GOLD_ORE || id == DIAMOND_ORE || id == REDSTONE_ORE) return 30;
        if (id == GOLD_BLOCK || id == IRON_BLOCK || id == DIAMOND_BLOCK) return 50;
        if (id == OBSIDIAN) return 100;
        if (id == GLASS) return 3;
        if (id == DOOR) return 30;
        if (id == COBBLESTONE) return 20;
        if (id == FARMLAND) return 6;
        return 10;
    }

    static boolean faceVisible(byte current, byte neighbor) {
        if (neighbor == AIR || isFlower(neighbor) || neighbor == DOOR) return true;
        if (isFluid(neighbor)) return current != neighbor;
        if (neighbor == LEAVES) return current != LEAVES;
        return false;
    }

    static int topTile(byte id) {
        if (id == CHEST) return 4;
        if (id == TNT) return 9;
        if (id == CACTUS) return CACTUS_TOP_TILE;
        if (isDecorative(id)) return TILE[id & 255];
        if (id == GRASS) return 0;
        if (id == LOG) return 21;
        if (id == CRAFTING_TABLE) return 43;
        if (id == FARMLAND) return 87;
        return TILE[id & 255];
    }

    static int sideTile(byte id) {
        if (id == CHEST) return 4;
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
        if (id == LAVA) return 234;
        if (id == DOOR) return face == 0 || face == 5 ? 81 : 97;
        if (id == FURNACE) return face == 0 || face == 5 ? 61 : 62;
        if (face == 0) return topTile(id);
        if (id == TNT && face == 5) return 10;
        if (face == 5) {
            if (id == GRASS) return TILE[DIRT];
            if (id == CACTUS) return 2;
            if (id == CRAFTING_TABLE) return TILE[PLANKS];
            if (id == FARMLAND) return 2;
            return topTile(id);
        }
        if (id == CRAFTING_TABLE) return (face == 1 || face == 3) ? 59 : 60;
        return sideTile(id);
    }

    static int renderTile(byte id, int face, int woolColor) {
        return id == WOOL && woolColor > 0 ? WOOL_TINT_TILE_START + woolColor : faceTile(id, face);
    }

    static boolean isDecorative(byte id) {
        return (id & 255) >= DECORATIVE_START && (id & 255) < COUNT && id != DOOR;
    }

    static int atlasTile(int id) {
        return id >= 0 && id < TILE.length ? TILE[id] : 0;
    }

}

