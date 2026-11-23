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
    // Reuse formerly unnamed palette slots so saved item IDs remain stable.
    static final byte WOOD_SLAB = 27, WOOD_STAIRS = 28, STONE_STAIRS = 29;
    static final int SLAB_BOTTOM = 0, SLAB_TOP = 1, SLAB_DOUBLE = 2;
    static final int STAIR_TOP = 4;
    // Beta 1.8.1 bed halves reuse wool metadata; block/item IDs stay compatible.
    static final int BED_MARKER = 0x40, BED_HEAD = 0x80;
    static final int BED_FACING_SHIFT = 4, BED_FACING_MASK = 0x30;
    static boolean isBedMeta(int meta) { return (meta & BED_MARKER) != 0; }
    static boolean isBedHead(int meta) { return (meta & BED_HEAD) != 0; }
    static int bedFacing(int meta) { return (meta & BED_FACING_MASK) >> BED_FACING_SHIFT; }

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
    static final int COUNT = DECORATIVE_START + EXTRA_TILES.length + 1;
    static final int[] TILE = makeTiles();
    static final String[] NAME = makeNames();
    static final int[] CREATIVE_PALETTE = makeCreativePalette();
    /** Uses the original wooden-door tile already present in the terrain atlas. */
    static final byte CHEST = (byte)findTileBlock(85); // Original chest tile in the terrain atlas.
    static final byte DOOR = (byte)findTileBlock(97);
    /** Alpha iron-door lower panel tile; kept on its existing atlas-backed block ID. */
    static final byte IRON_DOOR = (byte)findTileBlock(98);
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
    static final byte NETHER_BRICK = (byte)findTileBlock(112);
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
    /** Alpha 1.2.6 rail block uses terrain atlas tile 128 and is rendered as a thin plane. */
    static final byte RAIL = (byte)findTileBlock(128);
    static final byte END_PORTAL_FRAME = (byte)findTileBlock(158);
    /** Stronghold palette blocks from the original terrain atlas. */
    static final byte STONE_SLAB = (byte)findTileBlock(6);
    static final byte MOSSY_COBBLESTONE = (byte)findTileBlock(36);
    /** Minecraft End Stone terrain tile. */
    static final byte END_STONE = (byte)findTileBlock(121);
    /** Separate, one-way End portal surface; its visual is generated by the renderer. */
    static final byte END_PORTAL = (byte)(COUNT - 1);

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
        // Reuse two unnamed legacy atlas entries for End blocks. Keep EXTRA_TILES
        // length unchanged so saved block/item IDs remain stable.
        replaceTile(result, 94, 158);
        replaceTile(result, 95, 121);
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
        // Normal placeable blocks first; omit farmland and internal variants.
        for (int id = 1; id < DECORATIVE_START; id++) {
            if (id == FARMLAND) continue;
            temporary[count++] = id;
        }
        for (int id = DECORATIVE_START; id < COUNT; id++) {
            int tile = TILE[id];
            if (alphaBlockName(tile) == null || !hasAtlasTexture(tile)) continue;
            // Fire and portal are world-generated/internal blocks, not inventory items.
            // Redstone torch states are also internal variants; the normal torch stays available.
            if (tile == 14 || tile == 31 || tile == 96 || tile == 99 || tile == 115) continue;
            boolean duplicate = false;
            for (int i = 0; i < count; i++) {
                if (TILE[temporary[i]] == tile) { duplicate = true; break; }
            }
            if (!duplicate) temporary[count++] = id;
        }
        temporary[count++] = WOOD_SLAB;
        temporary[count++] = WOOD_STAIRS;
        temporary[count++] = STONE_STAIRS;
        // The chest is a real placeable block even if its tile is unnamed.
        int chest = findTileBlock(85);
        if (chest > 0 && chest < COUNT) {
            boolean found = false;
            for (int i = 0; i < count; i++) if (temporary[i] == chest) found = true;
            if (!found) temporary[count++] = chest;
        }
        int[] palette = new int[count];
        System.arraycopy(temporary, 0, palette, 0, count);
        return palette;
    }

    private static boolean hasAtlasTexture(int tile) {
        if (tile < 0 || tile >= 256) return false;
        if (tile == 14 || tile == 31 || tile == 61 || tile == 62 || tile == 96 || tile == 99 || tile == 102 ||
            tile == 121 || tile == 158 || tile == 159 || tile == 174 || tile == 175 || tile == 103 || tile == 104 || tile == 105 || tile == 115) return true;
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
        result[WOOD_SLAB] = 4;
        result[WOOD_STAIRS] = 4;
        result[STONE_STAIRS] = 16;
        result[COUNT - 1] = 256; // Internal special block: no terrain-atlas tile.
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
        result[WOOD_SLAB] = "Losa de madera";
        result[WOOD_STAIRS] = "Escaleras de madera";
        result[STONE_STAIRS] = "Escaleras de piedra";
        result[COUNT - 1] = "Portal del End";
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
        if (tile == 112) return "Ladrillos del Nether";
        if (tile == 121) return "Piedra del End";
        if (tile == 128) return "Riel";
        if (tile == 158) return "Marco del portal del End";
        return null;
    }

    private Block() { }

    static boolean isDoor(byte id) { return id == DOOR || id == IRON_DOOR; }

    static boolean isSlab(byte id) { return id == STONE_SLAB || id == WOOD_SLAB; }
    static boolean isStairs(byte id) { return id == WOOD_STAIRS || id == STONE_STAIRS; }
    static boolean isPartial(byte id) { return isSlab(id) || isStairs(id); }
    static boolean isPartial(byte id, int meta) { return isPartial(id) || (id == WOOL && isBedMeta(meta)); }

    static int collisionBoxCount(byte id, int meta) { return isStairs(id) ? 2 : 1; }
    static float boxMinX(byte id, int meta, int box) {
        if (!isStairs(id) || box == 0) return 0.0f;
        return (meta & 3) == 1 ? 0.5f : 0.0f;
    }
    static float boxMaxX(byte id, int meta, int box) {
        if (!isStairs(id) || box == 0) return 1.0f;
        return (meta & 3) == 3 ? 0.5f : 1.0f;
    }
    static float boxMinZ(byte id, int meta, int box) {
        if (!isStairs(id) || box == 0) return 0.0f;
        return (meta & 3) == 2 ? 0.5f : 0.0f;
    }
    static float boxMaxZ(byte id, int meta, int box) {
        if (!isStairs(id) || box == 0) return 1.0f;
        return (meta & 3) == 0 ? 0.5f : 1.0f;
    }
    static float boxMinY(byte id, int meta, int box) {
        if (id == WOOL && isBedMeta(meta)) return 0.0f;
        if (isSlab(id)) return (meta & 3) == SLAB_TOP ? 0.5f : 0.0f;
        if (!isStairs(id)) return 0.0f;
        return box == 0 ? ((meta & STAIR_TOP) != 0 ? 0.5f : 0.0f) :
                          ((meta & STAIR_TOP) != 0 ? 0.0f : 0.5f);
    }
    static float boxMaxY(byte id, int meta, int box) {
        if (id == WOOL && isBedMeta(meta)) return 9.0f / 16.0f;
        if (isSlab(id)) return (meta & 3) == SLAB_BOTTOM ? 0.5f : 1.0f;
        if (!isStairs(id)) return 1.0f;
        return box == 0 ? ((meta & STAIR_TOP) != 0 ? 1.0f : 0.5f) :
                          ((meta & STAIR_TOP) != 0 ? 0.5f : 1.0f);
    }
    static boolean intersectsPlayer(byte id, int meta, int bx, int by, int bz,
            float px, float py, float pz, float radius, float height) {
        for (int i=0; i<collisionBoxCount(id,meta); i++) {
            if (bx + boxMaxX(id,meta,i) > px-radius && bx + boxMinX(id,meta,i) < px+radius &&
                bz + boxMaxZ(id,meta,i) > pz-radius && bz + boxMinZ(id,meta,i) < pz+radius &&
                by + boxMaxY(id,meta,i) > py && by + boxMinY(id,meta,i) < py+height) return true;
        }
        return false;
    }

    static float rayHitDistance(byte id, int meta, float ox, float oy, float oz,
            float dx, float dy, float dz, int bx, int by, int bz) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < collisionBoxCount(id, meta); i++) {
            float minX = bx + boxMinX(id,meta,i), maxX = bx + boxMaxX(id,meta,i);
            float minY = by + boxMinY(id,meta,i), maxY = by + boxMaxY(id,meta,i);
            float minZ = bz + boxMinZ(id,meta,i), maxZ = bz + boxMaxZ(id,meta,i);
            float near = 0.0f, far = Float.MAX_VALUE, a, b;
            if (Math.abs(dx) < 0.000001f) {
                if (ox < minX || ox > maxX) continue;
            } else {
                a = (minX - ox) / dx; b = (maxX - ox) / dx;
                if (a > b) { float swap = a; a = b; b = swap; }
                if (a > near) near = a;
                if (b < far) far = b;
                if (far < near) continue;
            }
            if (Math.abs(dy) < 0.000001f) {
                if (oy < minY || oy > maxY) continue;
            } else {
                a = (minY - oy) / dy; b = (maxY - oy) / dy;
                if (a > b) { float swap = a; a = b; b = swap; }
                if (a > near) near = a;
                if (b < far) far = b;
                if (far < near) continue;
            }
            if (Math.abs(dz) < 0.000001f) {
                if (oz < minZ || oz > maxZ) continue;
            } else {
                a = (minZ - oz) / dz; b = (maxZ - oz) / dz;
                if (a > b) { float swap = a; a = b; b = swap; }
                if (a > near) near = a;
                if (b < far) far = b;
                if (far < near) continue;
            }
            if (far >= 0.0f && near < best) best = near;
        }
        return best == Float.MAX_VALUE ? -1.0f : best;
    }

    static boolean solid(byte id) {
        return id != AIR && !isFluid(id) && !isDoor(id) && id != RAIL && !isFlower(id) && id != TORCH;
    }

    static boolean isFluid(byte id) {
        return id == WATER || id == LAVA;
    }

    static boolean isFlower(byte id) {
        return id == RED_FLOWER || id == YELLOW_FLOWER || id == TORCH || id == PORTAL ||
               id == FIRE || id == END_PORTAL || id == BROWN_MUSHROOM || id == RED_MUSHROOM || id == SAPLING ||
               id == CROPS || id == REED;
    }

    static boolean replaceable(byte id) {
        return id == AIR || isFluid(id) || isFlower(id) || id == RAIL;
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
        // Fire and End Portal Frames are not collectible drops; the frame's
        // special multi-tile model is not a normal dropped block cube.
        return id != AIR && id != FIRE && id != END_PORTAL && id != END_PORTAL_FRAME &&
                !isFluid(id) && id != BEDROCK && id != TNT && id != PORTAL;
    }

    static int dropId(byte id) {
        if (id == IRON_DOOR) return ItemCatalog.IRON_DOOR;
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
        if (id == BEDROCK || id == PORTAL || id == END_PORTAL) return -1;
        if (isFlower(id) || id == TNT || id == RAIL) return 0;
        if (isFluid(id)) return 1000;
        if (id == LEAVES) return 2;
        if (id == WOOD_SLAB || id == WOOD_STAIRS) return 20;
        if (id == STONE_SLAB || id == STONE_STAIRS) return 20;
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
        if (id == END_STONE) return 30;
        if (id == GLASS) return 3;
        if (isDoor(id)) return 30;
        if (id == COBBLESTONE) return 20;
        if (id == FARMLAND) return 6;
        return 10;
    }

    static boolean faceVisible(byte current, byte neighbor) {
        // Keep the uncovered part of a full cube visible beside a half block.
        if (isPartial(neighbor)) return true;
        if (neighbor == AIR || neighbor == END_PORTAL || neighbor == END_PORTAL_FRAME || isFlower(neighbor) || isDoor(neighbor) || neighbor == RAIL) return true;
        if (isFluid(neighbor)) return current != neighbor;
        if (neighbor == LEAVES) return current != LEAVES;
        // Glass keeps the opaque block face behind it visible, but adjacent
        // glass cubes do not generate hidden internal faces.
        if (neighbor == GLASS) return current != GLASS;
        return false;
    }

    static int topTile(byte id) {
        if (id == WOOD_SLAB || id == WOOD_STAIRS) return 4;
        if (id == STONE_SLAB) return 6;
        if (id == STONE_STAIRS) return 1;
        if (id == END_PORTAL_FRAME) return 158;
        // Alpha 1.2.6 BlockChest: blockIndexInTexture=26, top/bottom use 26-1.
        if (id == CHEST) return 25;
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
        if (id == WOOD_SLAB || id == WOOD_STAIRS) return 4;
        if (id == STONE_SLAB) return 6;
        if (id == STONE_STAIRS) return 16;
        if (id == END_PORTAL_FRAME) return 159;
        // Alpha 1.2.6 BlockChest: ordinary sides use tile 26; the front is 27.
        if (id == CHEST) return 26;
        if (id == DOOR) return 97;
        if (id == IRON_DOOR) return 98;
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
        if (id == END_STONE) return 175;
        if (id == END_PORTAL_FRAME) return face == 0 ? 158 : (face == 5 ? 175 : 159);
        if (id == LAVA) return 234;
        if (id == DOOR) return face == 0 || face == 5 ? 81 : 97;
        if (id == IRON_DOOR) return face == 0 || face == 5 ? 82 : 98;
        // Alpha 1.2.6 BlockFurnace UVs: stone on top/bottom (tile 1),
        // side texture 45, and the idle front texture 44 on the default -Z face.
        if (id == FURNACE) {
            if (face == 0 || face == 5) return 1;
            return face == 3 ? 44 : 45;
        }
        if (face == 0) return topTile(id);
        // Alpha 1.2.6 BlockChest uses tile 27 for its default front
        // (side 3 / -Z), tile 26 for the other vertical faces, and tile 25
        // for both top and bottom. This project currently stores block IDs
        // without chest-facing metadata, so use Alpha's default orientation.
        if (id == CHEST && face == 3) return 27;
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
        if (id == END_PORTAL_FRAME) return face == 0 ? 158 : (face == 5 ? 175 : 159);
        return id == WOOL && woolColor > 0 ? WOOL_TINT_TILE_START + woolColor : faceTile(id, face);
    }

    static boolean isDecorative(byte id) {
        return (id & 255) >= DECORATIVE_START && (id & 255) < COUNT && !isDoor(id);
    }

    static int atlasTile(int id) {
        return id >= 0 && id < TILE.length ? TILE[id] : 0;
    }

}

