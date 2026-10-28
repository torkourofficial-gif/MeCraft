package mecraft;

/** Indev shaped recipes exposed as a compact, scrollable item list. */
final class Crafting {
    static final int LOG_TO_PLANKS = 0;
    static final int PLANKS_TO_TABLE = 1;
    static final int PLANKS_TO_STICKS = 2;
    static final int TORCHES = 3;
    static final int BASIC_RECIPES = 4;
    static final int DOOR = BASIC_RECIPES;
    static final int TOOL_RECIPES = BASIC_RECIPES + 1;
    private static final int TOOL_RECIPE_COUNT = ItemCatalog.TOOL_KINDS * ItemCatalog.TOOL_TIERS;
    private static final int FOOD_AND_ITEM_RECIPES = 8;
    private static final int ARMOR_RECIPES = 4 * 4;
    static final int RECIPE_COUNT = TOOL_RECIPES + TOOL_RECIPE_COUNT +
                                    FOOD_AND_ITEM_RECIPES + ARMOR_RECIPES + 2;

    static final int[] OUTPUT = new int[RECIPE_COUNT];
    static final int[] OUTPUT_COUNT = new int[RECIPE_COUNT];
    static final int[] INGREDIENTS = new int[RECIPE_COUNT * 8];
    static final String[] NAME = new String[RECIPE_COUNT];

    static {
        setRecipe(LOG_TO_PLANKS, Block.PLANKS, 4, "4 Tablones", Block.LOG, 1, 0, 0);
        setRecipe(PLANKS_TO_TABLE, Block.CRAFTING_TABLE, 1, "Mesa de trabajo", Block.PLANKS, 4, 0, 0);
        setRecipe(PLANKS_TO_STICKS, ItemCatalog.STICK, 4, "4 Palos", Block.PLANKS, 2, 0, 0);
        setRecipe(TORCHES, Block.TORCH, 4, "4 Antorchas", Block.COAL, 1, ItemCatalog.STICK, 1);
        setRecipe(DOOR, Block.DOOR, 3, "3 Puertas", Block.PLANKS, 6, 0, 0);
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
        int extra = TOOL_RECIPES + TOOL_RECIPE_COUNT;
        setRecipe(extra++, ItemCatalog.BREAD, 1, "Pan", ItemCatalog.WHEAT, 3);
        setRecipe(extra++, ItemCatalog.MUSHROOM_STEW, 1, "Estofado de setas",
                ItemCatalog.BOWL, 1, Block.BROWN_MUSHROOM, 1,
                Block.RED_MUSHROOM, 1);
        setRecipe(extra++, ItemCatalog.BOW, 1, "Arco", ItemCatalog.STICK, 3,
                ItemCatalog.STRING, 3);
        setRecipe(extra++, ItemCatalog.ARROW, 4, "4 Flechas", ItemCatalog.FLINT, 1,
                ItemCatalog.STICK, 1, ItemCatalog.FEATHER, 1);
        setRecipe(extra++, ItemCatalog.FLINT_AND_STEEL, 1, "Pedernal y acero",
                ItemCatalog.IRON_INGOT, 1, ItemCatalog.FLINT, 1);
        setRecipe(extra++, ItemCatalog.GOLDEN_APPLE, 1, "Manzana dorada",
                ItemCatalog.APPLE, 1, ItemCatalog.GOLD_INGOT, 8);
        setRecipe(extra++, Block.CHEST, 1, "Cofre", Block.PLANKS, 8);
        setRecipe(extra++, ItemCatalog.BOAT, 1, "Barca", Block.PLANKS, 5);
        setRecipe(extra++, Block.RAIL, 16, "16 Rieles", ItemCatalog.IRON_INGOT, 6,
                ItemCatalog.STICK, 1);
        setRecipe(extra++, ItemCatalog.MINECART, 1, "Vagoneta", ItemCatalog.IRON_INGOT, 5);
        int[] armorItems = new int[] {
            ItemCatalog.LEATHER_HELMET, ItemCatalog.LEATHER_CHESTPLATE,
            ItemCatalog.LEATHER_LEGGINGS, ItemCatalog.LEATHER_BOOTS,
            ItemCatalog.IRON_HELMET, ItemCatalog.IRON_CHESTPLATE,
            ItemCatalog.IRON_LEGGINGS, ItemCatalog.IRON_BOOTS,
            ItemCatalog.DIAMOND_HELMET, ItemCatalog.DIAMOND_CHESTPLATE,
            ItemCatalog.DIAMOND_LEGGINGS, ItemCatalog.DIAMOND_BOOTS,
            ItemCatalog.GOLD_HELMET, ItemCatalog.GOLD_CHESTPLATE,
            ItemCatalog.GOLD_LEGGINGS, ItemCatalog.GOLD_BOOTS
        };
        int[] armorMaterials = new int[] {
            ItemCatalog.LEATHER, ItemCatalog.LEATHER, ItemCatalog.LEATHER, ItemCatalog.LEATHER,
            ItemCatalog.IRON_INGOT, ItemCatalog.IRON_INGOT, ItemCatalog.IRON_INGOT, ItemCatalog.IRON_INGOT,
            ItemCatalog.DIAMOND, ItemCatalog.DIAMOND, ItemCatalog.DIAMOND, ItemCatalog.DIAMOND,
            ItemCatalog.GOLD_INGOT, ItemCatalog.GOLD_INGOT, ItemCatalog.GOLD_INGOT, ItemCatalog.GOLD_INGOT
        };
        int[] armorCounts = new int[] { 5,8,7,4, 5,8,7,4, 5,8,7,4, 5,8,7,4 };
        for (int i = 0; i < armorItems.length; i++)
            setRecipe(extra++, armorItems[i], 1, ItemCatalog.name(armorItems[i]),
                    armorMaterials[i], armorCounts[i]);
    }

    private Crafting() { }

    private static void setRecipe(int recipe, int output, int outputCount, String name,
                                  int item1, int count1) {
        setRecipe(recipe, output, outputCount, name, item1, count1, 0, 0, 0, 0, 0, 0);
    }

    private static void setRecipe(int recipe, int output, int outputCount, String name,
                                  int item1, int count1, int item2, int count2) {
        setRecipe(recipe, output, outputCount, name, item1, count1, item2, count2, 0, 0, 0, 0);
    }

    private static void setRecipe(int recipe, int output, int outputCount, String name,
                                  int item1, int count1, int item2, int count2,
                                  int item3, int count3) {
        setRecipe(recipe, output, outputCount, name,
                  item1, count1, item2, count2, item3, count3, 0, 0);
    }

    private static void setRecipe(int recipe, int output, int outputCount, String name,
                                  int item1, int count1, int item2, int count2,
                                  int item3, int count3, int item4, int count4) {
        OUTPUT[recipe] = output;
        OUTPUT_COUNT[recipe] = outputCount;
        NAME[recipe] = name;
        int offset = recipe * 8;
        INGREDIENTS[offset] = item1;
        INGREDIENTS[offset + 1] = count1;
        INGREDIENTS[offset + 2] = item2;
        INGREDIENTS[offset + 3] = count2;
        INGREDIENTS[offset + 4] = item3;
        INGREDIENTS[offset + 5] = count3;
        INGREDIENTS[offset + 6] = item4;
        INGREDIENTS[offset + 7] = count4;
    }

    static int count(boolean workbench) {
        return workbench ? RECIPE_COUNT : BASIC_RECIPES;
    }

    static boolean requiresWorkbench(int recipe) {
        return recipe >= BASIC_RECIPES;
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

