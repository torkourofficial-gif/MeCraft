package mecraft;

/** Small recipe book. Each recipe stores pairs of block id and required count. */
final class Crafting {
    static final int LOG_TO_PLANKS = 0;
    static final int PLANKS_TO_TABLE = 1;
    static final int STONE_TO_FURNACE = 2;

    static final int[] OUTPUT = new int[] {
        Block.PLANKS, Block.CRAFTING_TABLE, Block.FURNACE
    };
    static final int[] OUTPUT_COUNT = new int[] { 4, 1, 1 };
    static final int[] INGREDIENTS = new int[] {
        Block.LOG, 1, 0, 0,
        Block.PLANKS, 4, 0, 0,
        Block.STONE, 8, 0, 0
    };
    static final String[] NAME = new String[] {
        "4 Tablones", "Mesa de crafteo", "Horno"
    };

    private Crafting() { }

    static int count(boolean workbench) {
        return workbench ? OUTPUT.length : 2;
    }

    static boolean canCraft(int recipe, int[] inventory, boolean creative) {
        if (creative) return true;
        int offset = recipe * 4;
        for (int i = 0; i < 4 && INGREDIENTS[offset + i * 2] != 0; i++) {
            int id = INGREDIENTS[offset + i * 2];
            if (inventory[id] < INGREDIENTS[offset + i * 2 + 1]) return false;
        }
        return true;
    }

    static boolean craft(int recipe, int[] inventory, boolean creative) {
        if (recipe < 0 || recipe >= OUTPUT.length || !canCraft(recipe, inventory, creative)) return false;
        int result = OUTPUT[recipe];
        if (creative) {
            if (inventory[result] < 64) inventory[result] = 64;
            return true;
        }
        if (inventory[result] + OUTPUT_COUNT[recipe] > 64) return false;
        int offset = recipe * 4;
        for (int i = 0; i < 4 && INGREDIENTS[offset + i * 2] != 0; i++) {
            int id = INGREDIENTS[offset + i * 2];
            inventory[id] -= INGREDIENTS[offset + i * 2 + 1];
        }
        inventory[result] += OUTPUT_COUNT[recipe];
        return true;
    }
}

