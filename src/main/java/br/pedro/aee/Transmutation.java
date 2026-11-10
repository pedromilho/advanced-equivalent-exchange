package br.pedro.aee;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

final class Transmutation {
    private Transmutation() {}

    static long burnValue(ItemStack stack, EmcValues values) {
        if (ItemStack.isEmpty(stack)) return 0;
        Item item = stack.getItem();
        if (item == null || item == Item.UNKNOWN || stack.getQualityIndex() != item.getQualityIndex()) {
            return 0;
        }
        long value = values.get(stack.getItemId());
        double maximum = item.getMaxDurability();
        if (maximum <= 0) return value;
        // Repairing or recycling worn gear must never refund more than a new item costs.
        return (long) Math.floor(value * Math.clamp(stack.getDurability() / maximum, 0, 1));
    }

    static long burn(AlchemyKnowledge knowledge, EmcValues values, ItemContainer inventory,
                     short slot, ItemStack expected, boolean wholeStack) {
        return burn(knowledge, values, inventory, slot, expected,
            wholeStack && !ItemStack.isEmpty(expected) ? expected.getQuantity() : 1);
    }

    static long burn(AlchemyKnowledge knowledge, EmcValues values, ItemContainer inventory,
                     short slot, ItemStack expected, int count) {
        if (ItemStack.isEmpty(expected) || count <= 0 || count > expected.getQuantity()
            || !expected.equals(inventory.getItemStack(slot))) return 0;
        long unit = burnValue(expected, values);
        if (unit <= 0 || unit > (Long.MAX_VALUE - knowledge.emc) / count) return 0;
        var removal = inventory.removeItemStackFromSlot(slot, expected, count);
        if (!removal.succeeded() || !ItemStack.isEmpty(removal.getRemainder())) return 0;
        long credited = unit * count;
        knowledge.emc += credited;
        knowledge.items.add(expected.getItemId());
        knowledge.lastBurn = expected.withQuantity(count);
        knowledge.returnCost = unit;
        return credited;
    }

    static int recover(AlchemyKnowledge knowledge, ItemContainer inventory, Short destination, int quantity) {
        var stack = knowledge.lastBurn;
        if (ItemStack.isEmpty(stack)) return 0;
        int count = deliver(knowledge, inventory, destination, stack,
            Math.min(quantity, stack.getQuantity()), knowledge.returnCost);
        if (count > 0) {
            int remaining = stack.getQuantity() - count;
            knowledge.lastBurn = remaining == 0 ? null : stack.withQuantity(remaining);
            if (remaining == 0) knowledge.returnCost = 0;
        }
        return count;
    }

    static int create(AlchemyKnowledge knowledge, EmcValues values, ItemContainer inventory,
                      String id, boolean stack) {
        return create(knowledge, values, inventory, null, id, stack ? Integer.MAX_VALUE : 1);
    }

    static int create(AlchemyKnowledge knowledge, EmcValues values, ItemContainer inventory,
                      Short destination, String id, int quantity) {
        if (id == null || !knowledge.items.contains(id)) return 0;
        Item item = Item.getAssetMap().getAsset(id);
        long unit = values.get(id);
        if (unit <= 0 || item == null) return 0;
        return deliver(knowledge, inventory, destination, new ItemStack(id),
            Math.min(quantity, item.getMaxStack()), unit);
    }

    private static int deliver(AlchemyKnowledge knowledge, ItemContainer inventory, Short destination,
                               ItemStack template, int quantity, long unit) {
        if (unit <= 0 || quantity <= 0 || knowledge.emc < unit) return 0;
        int requested = (int) Math.min(quantity, knowledge.emc / unit);
        var stack = template.withQuantity(requested);
        ItemStack remainder;
        if (destination == null) {
            var added = inventory.addItemStack(stack, false, false, true);
            if (!added.succeeded()) return 0;
            remainder = added.getRemainder();
        } else {
            var added = inventory.addItemStackToSlot(destination, stack, false, true);
            if (!added.succeeded()) return 0;
            remainder = added.getRemainder();
        }
        int created = requested - (ItemStack.isEmpty(remainder) ? 0 : remainder.getQuantity());
        knowledge.emc -= unit * created;
        return created;
    }
}
