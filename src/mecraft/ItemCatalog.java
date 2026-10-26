package mecraft;

/** Item IDs and Indev tool material properties used by inventory and mining. */
final class ItemCatalog {
    static final int STICK = Block.COUNT;
    static final int TOOL_START = STICK + 1;
    static final int TOOL_KINDS = 5;
    static final int TOOL_TIERS = 5;
    static final int IRON_INGOT = TOOL_START + TOOL_KINDS * TOOL_TIERS;
    static final int DIAMOND = IRON_INGOT + 1;
    static final int GOLD_INGOT = IRON_INGOT + 2;
    static final int SEEDS = GOLD_INGOT + 1;
    static final int PORK_RAW = SEEDS + 1;
    static final int FEATHER = PORK_RAW + 1;
    static final int WOOL_COLOR_START = FEATHER + 1;
    static final int WOOL_COLOR_COUNT = WoolColors.COUNT - 1;
    static final int ALPHA_ITEM_START = WOOL_COLOR_START + WOOL_COLOR_COUNT;
    static final int COAL_ITEM = ALPHA_ITEM_START;
    static final int FLINT_AND_STEEL = COAL_ITEM + 1;
    static final int APPLE = FLINT_AND_STEEL + 1;
    static final int BOW = APPLE + 1;
    static final int ARROW = BOW + 1;
    static final int BOWL = ARROW + 1;
    static final int MUSHROOM_STEW = BOWL + 1;
    static final int STRING = MUSHROOM_STEW + 1;
    static final int GUNPOWDER = STRING + 1;
    static final int WHEAT = GUNPOWDER + 1;
    static final int BREAD = WHEAT + 1;
    static final int LEATHER_HELMET = BREAD + 1;
    static final int LEATHER_CHESTPLATE = LEATHER_HELMET + 1;
    static final int LEATHER_LEGGINGS = LEATHER_CHESTPLATE + 1;
    static final int LEATHER_BOOTS = LEATHER_LEGGINGS + 1;
    static final int CHAIN_HELMET = LEATHER_BOOTS + 1;
    static final int CHAIN_CHESTPLATE = CHAIN_HELMET + 1;
    static final int CHAIN_LEGGINGS = CHAIN_CHESTPLATE + 1;
    static final int CHAIN_BOOTS = CHAIN_LEGGINGS + 1;
    static final int IRON_HELMET = CHAIN_BOOTS + 1;
    static final int IRON_CHESTPLATE = IRON_HELMET + 1;
    static final int IRON_LEGGINGS = IRON_CHESTPLATE + 1;
    static final int IRON_BOOTS = IRON_LEGGINGS + 1;
    static final int DIAMOND_HELMET = IRON_BOOTS + 1;
    static final int DIAMOND_CHESTPLATE = DIAMOND_HELMET + 1;
    static final int DIAMOND_LEGGINGS = DIAMOND_CHESTPLATE + 1;
    static final int DIAMOND_BOOTS = DIAMOND_LEGGINGS + 1;
    static final int GOLD_HELMET = DIAMOND_BOOTS + 1;
    static final int GOLD_CHESTPLATE = GOLD_HELMET + 1;
    static final int GOLD_LEGGINGS = GOLD_CHESTPLATE + 1;
    static final int GOLD_BOOTS = GOLD_LEGGINGS + 1;
    static final int FLINT = GOLD_BOOTS + 1;
    static final int PORK_COOKED = FLINT + 1;
    static final int PAINTING = PORK_COOKED + 1;
    static final int GOLDEN_APPLE = PAINTING + 1;
    static final int SIGN = GOLDEN_APPLE + 1;
    static final int EMPTY_BUCKET = SIGN + 1;
    static final int WATER_BUCKET = EMPTY_BUCKET + 1;
    static final int LAVA_BUCKET = WATER_BUCKET + 1;
    static final int MINECART = LAVA_BUCKET + 1;
    static final int SADDLE = MINECART + 1;
    static final int IRON_DOOR = SADDLE + 1;
    static final int REDSTONE = IRON_DOOR + 1;
    static final int SNOWBALL = REDSTONE + 1;
    static final int BOAT = SNOWBALL + 1;
    static final int LEATHER = BOAT + 1;
    static final int MILK_BUCKET = LEATHER + 1;
    static final int BRICK = MILK_BUCKET + 1;
    static final int CLAY = BRICK + 1;
    static final int REED = CLAY + 1;
    static final int PAPER = REED + 1;
    static final int BOOK = PAPER + 1;
    static final int SLIMEBALL = BOOK + 1;
    static final int CHEST_MINECART = SLIMEBALL + 1;
    static final int FURNACE_MINECART = CHEST_MINECART + 1;
    static final int EGG = FURNACE_MINECART + 1;
    static final int COMPASS = EGG + 1;
    static final int FISHING_ROD = COMPASS + 1;
    static final int CLOCK = FISHING_ROD + 1;
    static final int GLOWSTONE_DUST = CLOCK + 1;
    static final int FISH_RAW = GLOWSTONE_DUST + 1;
    static final int FISH_COOKED = FISH_RAW + 1;
    static final int RECORD_13 = FISH_COOKED + 1;
    static final int RECORD_CAT = RECORD_13 + 1;
    static final int COUNT = RECORD_CAT + 1;

    static final int SWORD = 0;
    static final int SHOVEL = 1;
    static final int PICKAXE = 2;
    static final int AXE = 3;
    static final int HOE = 4;

    static final int WOOD = 0;
    static final int STONE = 1;
    static final int IRON = 2;
    static final int DIAMOND_TIER = 3;
    static final int GOLD = 4;

    // items.png stores tools in columns by kind: swords 64+, shovels 80+,
    // pickaxes 96+, axes 112+, and hoes 128+. Each material advances one tile.
    private static final int[] TOOL_ICON_BASE = new int[] { 64,80,96,112,128 };
    private static final int[] MATERIAL_ICON = new int[] { 23,55,39,9,87,24 };
    private static final String[] MATERIAL_NAMES = new String[] {
        "madera", "piedra", "hierro", "diamante", "oro"
    };
    private static final String[] KIND_NAMES = new String[] {
        "Espada", "Pala", "Pico", "Hacha", "Azada"
    };
    private static final String[] ALPHA_NAMES = new String[] {
        "Carbón", "Pedernal y acero", "Manzana", "Arco", "Flecha", "Cuenco", "Estofado de setas",
        "Hilo", "Pólvora", "Trigo", "Pan", "Casco de cuero", "Peto de cuero",
        "Grebas de cuero", "Botas de cuero", "Casco de cota", "Peto de cota",
        "Grebas de cota", "Botas de cota", "Casco de hierro", "Peto de hierro",
        "Grebas de hierro", "Botas de hierro", "Casco de diamante", "Peto de diamante",
        "Grebas de diamante", "Botas de diamante", "Casco de oro", "Peto de oro",
        "Grebas de oro", "Botas de oro", "Pedernal", "Cerdo cocinado", "Cuadro",
        "Manzana dorada", "Cartel", "Cubo vacío", "Cubo de agua", "Cubo de lava",
        "Vagoneta", "Silla de montar", "Puerta de hierro", "Redstone", "Bola de nieve",
        "Barca", "Cuero", "Cubo de leche", "Ladrillo", "Arcilla", "Caña de azúcar",
        "Papel", "Libro", "Bola de slime", "Vagoneta con cofre", "Vagoneta con horno",
        "Huevo", "Brújula", "Caña de pescar", "Reloj", "Polvo de piedra luminosa",
        "Pescado crudo", "Pescado cocinado", "Disco 13", "Disco Cat"
    };
    private static final int[] ALPHA_ICONS = new int[] {
        7,5,10,21,37,71,72,8,40,25,41,0,16,32,48,1,17,33,49,2,18,34,50,
        3,19,35,51,4,20,36,52,6,88,26,11,42,74,75,76,135,104,44,56,14,
        136,103,77,22,57,27,58,59,30,151,167,12,54,69,70,73,89,90,240,241
    };

    private ItemCatalog() { }

    static int tool(int kind, int tier) {
        return TOOL_START + tier * TOOL_KINDS + kind;
    }

    static boolean isTool(int id) {
        return id >= TOOL_START && id < IRON_INGOT;
    }

    static boolean isColoredWool(int id) {
        return id >= WOOL_COLOR_START && id < ALPHA_ITEM_START;
    }

    static boolean isFood(int id) {
        return id == PORK_RAW || id == APPLE || id == MUSHROOM_STEW || id == BREAD ||
               id == PORK_COOKED || id == GOLDEN_APPLE || id == FISH_RAW || id == FISH_COOKED;
    }

    static int foodHealing(int id) {
        if (id == MUSHROOM_STEW || id == GOLDEN_APPLE) return 10;
        if (id == BREAD || id == PORK_COOKED) return 5;
        if (id == APPLE) return 4;
        if (id == PORK_RAW) return 3;
        if (id == FISH_COOKED) return 5;
        if (id == FISH_RAW) return 2;
        return 0;
    }

    static int woolColor(int id) {
        return isColoredWool(id) ? id - WOOL_COLOR_START + 1 : 0;
    }

    static int woolItem(int color) {
        return color <= 0 ? Block.WOOL : WOOL_COLOR_START + color - 1;
    }

    static int kind(int id) { return (id - TOOL_START) % TOOL_KINDS; }
    static int tier(int id) { return (id - TOOL_START) / TOOL_KINDS; }

    /** Gold tools use Indev's material level zero for speed and durability. */
    static int materialLevel(int id) {
        int tier = tier(id);
        return tier == GOLD ? 0 : tier;
    }

    static int maxDamage(int id) {
        return isTool(id) ? 32 << materialLevel(id) : 0;
    }

    static int maxStack(int id) {
        if (isTool(id) || id == FLINT_AND_STEEL || id == BOW || id == FISHING_ROD ||
                (id >= LEATHER_HELMET && id <= GOLD_BOOTS) || id == MINECART ||
                id == CHEST_MINECART || id == FURNACE_MINECART || id == BOAT ||
                id == EMPTY_BUCKET || id == WATER_BUCKET || id == LAVA_BUCKET ||
                id == MILK_BUCKET || id == MUSHROOM_STEW) return 1;
        return 64;
    }

    static int iconIndex(int id) {
        // Alpha's wooden door item icon in items.png (tile 43).
        if (id == Block.DOOR) return 43;
        if (id == STICK) return 53;
        if (isTool(id)) {
            int offset = id - TOOL_START;
            return TOOL_ICON_BASE[offset % TOOL_KINDS] + offset / TOOL_KINDS;
        }
        if (id >= ALPHA_ITEM_START && id < COUNT) return ALPHA_ICONS[id - ALPHA_ITEM_START];
        if (id >= IRON_INGOT && id < COUNT)
            return MATERIAL_ICON[id - IRON_INGOT];
        return -1;
    }

    static String name(int id) {
        if (id == STICK) return "Palo";
        if (isTool(id)) {
            int kind = kind(id), tier = tier(id);
            return KIND_NAMES[kind] + " de " + MATERIAL_NAMES[tier];
        }
        if (id == IRON_INGOT) return "Lingote de hierro";
        if (id == DIAMOND) return "Diamante";
        if (id == GOLD_INGOT) return "Lingote de oro";
        if (id == SEEDS) return "Semillas";
        if (id == PORK_RAW) return "Cerdo crudo";
        if (id == FEATHER) return "Pluma";
        if (isColoredWool(id)) return "Lana " + WoolColors.name(woolColor(id));
        if (id >= ALPHA_ITEM_START && id < COUNT) return ALPHA_NAMES[id - ALPHA_ITEM_START];
        if (id > 0 && id < Block.COUNT) return Block.NAME[id];
        return "Objeto";
    }

    static String shortName(int id) {
        if (isTool(id)) {
            String tierName = tier(id) == DIAMOND_TIER ? "diam." : MATERIAL_NAMES[tier(id)];
            return KIND_NAMES[kind(id)] + " " + tierName;
        }
        if (id == Block.CRAFTING_TABLE) return "Mesa";
        return name(id);
    }

    static int materialItem(int tier) {
        if (tier == WOOD) return Block.PLANKS;
        if (tier == STONE) return Block.COBBLESTONE;
        if (tier == IRON) return IRON_INGOT;
        if (tier == DIAMOND_TIER) return DIAMOND;
        return GOLD_INGOT;
    }

    static int durabilityCost(int id) {
        if (!isTool(id)) return 0;
        return kind(id) == SWORD ? 2 : (kind(id) == HOE ? 0 : 1);
    }

    static float efficiency(int id, byte block) {
        if (!isTool(id)) return 1.0f;
        int kind = kind(id);
        if (kind == SWORD) return 1.5f;
        if (!effective(id, block)) return 1.0f;
        int tier = materialLevel(id);
        if (tier == 0 && ItemCatalog.tier(id) == GOLD) return 12.0f;
        return (float)((tier + 1) << 1);
    }

    static boolean canHarvest(int id, byte block) {
        if (!isTool(id)) return block != Block.STONE && block != Block.COBBLESTONE &&
                                  block != Block.COAL && block != Block.IRON &&
                                  block != Block.GOLD_ORE && block != Block.DIAMOND_ORE &&
                                  block != Block.REDSTONE_ORE &&
                                  block != Block.BEDROCK;
        if (block == Block.BEDROCK) return false;
        if (block == Block.STONE || block == Block.COBBLESTONE || block == Block.COAL ||
            block == Block.GOLD_ORE || block == Block.DIAMOND_ORE || block == Block.REDSTONE_ORE) {
            return kind(id) == PICKAXE;
        }
        if (block == Block.IRON) {
            return kind(id) == PICKAXE && materialLevel(id) > 0;
        }
        return true;
    }

    static boolean effective(int id, byte block) {
        if (!isTool(id)) return false;
        int kind = kind(id);
        if (kind == PICKAXE) return block == Block.STONE || block == Block.COBBLESTONE ||
                                    block == Block.COAL || block == Block.IRON ||
                                    block == Block.GOLD_ORE || block == Block.DIAMOND_ORE ||
                                    block == Block.REDSTONE_ORE || block == Block.GOLD_BLOCK ||
                                    block == Block.IRON_BLOCK || block == Block.DIAMOND_BLOCK ||
                                    block == Block.NETHERRACK || block == Block.GLOWSTONE;
        if (kind == SHOVEL) return block == Block.GRASS || block == Block.DIRT ||
                                    block == Block.SAND || block == Block.FARMLAND || block == Block.SNOW;
        if (kind == AXE) return block == Block.LOG || block == Block.PLANKS ||
                                  block == Block.CRAFTING_TABLE || block == Block.DOOR || block == Block.CACTUS;
        return kind == SWORD;
    }
}

