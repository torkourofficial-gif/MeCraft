package mecraft;

/** Indev shaped recipes exposed as a compact, scrollable item list. */
final class Crafting {
    static final int LOG_TO_PLANKS = 0;
    static final int PLANKS_TO_TABLE = 1;
    static final int PLANKS_TO_STICKS = 2;
    static final int TORCHES = 3;
    static final int TOOL_RECIPES = 4;
    static final int RECIPE_COUNT = TOOL_RECIPES + ItemCatalog.TOOL_KINDS * ItemCatalog.TOOL_TIERS;

    static final int[] OUTPUT = new int[RECIPE_COUNT];
    static final int[] OUTPUT_COUNT = new int[RECIPE_COUNT];
    static final int[] INGREDIENTS = new int[RECIPE_COUNT * 8];
    static final String[] NAME = new String[RECIPE_COUNT];

    static {
        setRecipe(LOG_TO_PLANKS, Block.PLANKS, 4, "4 Tablones", Block.LOG, 1, 0, 0);
        setRecipe(PLANKS_TO_TABLE, Block.CRAFTING_TABLE, 1, "Mesa de trabajo", Block.PLANKS, 4, 0, 0);
        setRecipe(PLANKS_TO_STICKS, ItemCatalog.STICK, 4, "4 Palos", Block.PLANKS, 2, 0, 0);
        setRecipe(TORCHES, Block.TORCH, 4, "4 Antorchas", Block.COAL, 1, ItemCatalog.STICK, 1);
        for (int tier = 0; tier < ItemCatalog.TOOL_TIERS; tier++) {
            int material = ItemCatalog.materialItem(tier);
            for (int kind = 0; kind < ItemCatalog.TOOL_KINDS; kind++) {
                int recipe = TOOL_RECIPES + tier * ItemCatalog.TOOL_KINDS + kind;
                int materialCount = kind == ItemCatalog.SHOVEL ? 1 :
                                    (kind == ItemCatalog.SWORD || kind == ItemCatalog.HOE ? 2 : 3);
                int stickCount = kind == ItemCatalog.SWORD ? 1 : 2;
                String name = ItemCatalog.name(ItemCatalog.tool(kind, tier));
                setRecipe(recipe, ItemCatalog.tool(kind, tier), 1, name,
                          material, materialCount, ItemCatalog.STICK, stickCount);
            }
        }
    }

    private Crafting() { }

    private static void setRecipe(int recipe, int output, int outputCount, String name,
                                  int first, int firstCount, int second, int secondCount) {
        OUTPUT[recipe] = output;
        OUTPUT_COUNT[recipe] = outputCount;
        NAME[recipe] = name;
        int offset = recipe * 8;
        INGREDIENTS[offset] = first;
        INGREDIENTS[offset + 1] = firstCount;
        INGREDIENTS[offset + 2] = second;
        INGREDIENTS[offset + 3] = secondCount;
    }

    static int count(boolean workbench) {
        return workbench ? RECIPE_COUNT : TOOL_RECIPES;
    }

    static boolean requiresWorkbench(int recipe) {
        return recipe >= TOOL_RECIPES;
    }

    static boolean canCraft(int recipe, int[] inventory, boolean creative) {
        if (recipe < 0 || recipe >= RECIPE_COUNT) return false;
        if (creative) return true;
        int output = OUTPUT[recipe];
        if (inventory[output] >= ItemCatalog.maxStack(output)) return false;
        int offset = recipe * 8;
        for (int i = 0; i < 4; i++) {
            int id = INGREDIENTS[offset + i * 2];
            if (id == 0) continue;
            if (inventory[id] < INGREDIENTS[offset + i * 2 + 1]) return false;
        }
        return inventory[output] + OUTPUT_COUNT[recipe] <= ItemCatalog.maxStack(output);
    }

    static boolean craft(int recipe, int[] inventory, boolean creative) {
        if (!canCraft(recipe, inventory, creative)) return false;
        int result = OUTPUT[recipe];
        if (creative) {
            inventory[result] = ItemCatalog.maxStack(result);
            return true;
        }
        int offset = recipe * 8;
        for (int i = 0; i < 4; i++) {
            int id = INGREDIENTS[offset + i * 2];
            if (id != 0) inventory[id] -= INGREDIENTS[offset + i * 2 + 1];
        }
        inventory[result] += OUTPUT_COUNT[recipe];
        return true;
    }
}
