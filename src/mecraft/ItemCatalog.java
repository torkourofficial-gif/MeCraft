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
    static final int COUNT = FEATHER + 1;

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

    private ItemCatalog() { }

    static int tool(int kind, int tier) {
        return TOOL_START + tier * TOOL_KINDS + kind;
    }

    static boolean isTool(int id) {
        return id >= TOOL_START && id < IRON_INGOT;
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
        return isTool(id) ? 1 : 64;
    }

    static int iconIndex(int id) {
        if (id == STICK) return 53;
        if (isTool(id)) {
            int offset = id - TOOL_START;
            return TOOL_ICON_BASE[offset % TOOL_KINDS] + offset / TOOL_KINDS;
        }
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
                                  block != Block.BEDROCK;
        if (block == Block.BEDROCK) return false;
        if (block == Block.STONE || block == Block.COBBLESTONE || block == Block.COAL) {
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
                                    block == Block.COAL || block == Block.IRON;
        if (kind == SHOVEL) return block == Block.GRASS || block == Block.DIRT ||
                                    block == Block.SAND || block == Block.FARMLAND;
        if (kind == AXE) return block == Block.LOG || block == Block.PLANKS || block == Block.CRAFTING_TABLE;
        return kind == SWORD;
    }
}
