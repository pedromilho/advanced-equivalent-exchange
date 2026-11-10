package br.pedro.aee;

import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class EmcValues {
    static final long MAX_UNIT = 1_000_000_000L;
    static final EmcValues EMPTY = new EmcValues(Map.of());
    private static final String RESOURCE_PREFIX = "resource.";
    private final Map<String, Long> prices;

    private EmcValues(Map<String, Long> prices) {
        this.prices = Map.copyOf(prices);
    }

    long get(String item) {
        return prices.getOrDefault(item, 0L);
    }

    long size() {
        return prices.size();
    }

    static EmcValues load(Properties settings) {
        return load(settings, Map.of());
    }

    static EmcValues load(Properties settings, Map<String, Long> overrides) {
        var items = Item.getAssetMap().getAssetMap();
        var prices = new HashMap<String, Long>();
        var excluded = new HashSet<String>();
        var resourcePrices = new HashMap<String, Long>();
        for (String id : settings.stringPropertyNames()) {
            long value = Long.parseLong(settings.getProperty(id).strip());
            if (value < 0 || value > MAX_UNIT) {
                throw new IllegalArgumentException("EMC out of range for " + id);
            }
            if (id.startsWith(RESOURCE_PREFIX)) {
                if (value > 0) resourcePrices.put(id.substring(RESOURCE_PREFIX.length()), value);
            } else if (value == 0) excluded.add(id);
            else if (eligible(items.get(id))) prices.put(id, value);
        }

        var resources = new HashMap<String, List<Supply>>();
        for (Item item : items.values()) {
            if (!eligible(item) || item.getResourceTypes() == null) continue;
            for (var type : item.getResourceTypes()) {
                int quantity = Math.max(1, type.quantity);
                resources.computeIfAbsent(type.id, key -> new ArrayList<>())
                    .add(new Supply(item.getId(), quantity));
                Long unit = resourcePrices.get(type.id);
                if (unit != null && settings.getProperty(item.getId()) == null) {
                    prices.merge(item.getId(), Math.min(MAX_UNIT, unit * quantity), Math::min);
                }
            }
        }
        for (var entry : overrides.entrySet()) {
            String id = entry.getKey();
            long value = entry.getValue();
            if (value < 0 || value > MAX_UNIT) throw new IllegalArgumentException("EMC out of range for " + id);
            if (!editable(items.get(id))) continue;
            if (value == 0) prices.remove(id);
            else prices.put(id, value);
        }
        var recipes = new ArrayList<Conversion>();
        for (CraftingRecipe recipe : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            var conversion = conversion(recipe, items, resources);
            if (conversion != null && !excluded.contains(conversion.output)
                && !overrides.containsKey(conversion.output)) recipes.add(conversion);
        }

        // Establish prices from base materials, then lower them when a cheaper recipe is found.
        boolean changed;
        do {
            changed = false;
            for (Conversion recipe : recipes) {
                long cost = recipe.cost(prices);
                if (cost > 0 && cost < prices.getOrDefault(recipe.output, Long.MAX_VALUE)) {
                    prices.put(recipe.output, cost);
                    changed = true;
                }
            }
        } while (changed);
        return new EmcValues(prices);
    }

    private static boolean eligible(Item item) {
        return item != null && item != Item.UNKNOWN && item.getMaxStack() > 0
            && item.getItemStackContainerConfig().getCapacity() == 0 && item.getPortalKey() == null;
    }

    static boolean editable(Item item) {
        return eligible(item) && !item.isState() && !item.getId().startsWith("*");
    }

    private static Conversion conversion(CraftingRecipe recipe, Map<String, Item> items,
                                         Map<String, List<Supply>> resources) {
        MaterialQuantity[] outputs = recipe.getOutputs();
        MaterialQuantity output = outputs == null || outputs.length == 0
            ? recipe.getPrimaryOutput()
            : outputs.length == 1 ? outputs[0] : null;
        if (output == null || output.getItemId() == null || output.getQuantity() <= 0
            || output.getMetadata() != null || !eligible(items.get(output.getItemId()))) return null;
        if (recipe.getInput() == null || recipe.getInput().length == 0) return null;
        var inputs = new ArrayList<Input>();
        for (MaterialQuantity input : recipe.getInput()) {
            if (input.getQuantity() <= 0 || input.getMetadata() != null) return null;
            List<Supply> options;
            if (input.getItemId() != null) {
                options = List.of(new Supply(input.getItemId(), 1));
            } else if (input.getResourceTypeId() != null) {
                options = resources.getOrDefault(input.getResourceTypeId(), List.of());
            } else {
                options = Item.getAssetMap().getKeysForTag(input.getTagIndex()).stream()
                    .map(id -> new Supply(id, 1)).toList();
            }
            options = options.stream().filter(option -> !input.isItemExcluded(option.id)).toList();
            if (options.isEmpty()) return null;
            inputs.add(new Input(options, input.getQuantity()));
        }
        return new Conversion(output.getItemId(), output.getQuantity(), inputs);
    }

    private record Supply(String id, int quantity) {}

    private record Input(List<Supply> options, int quantity) {
        long cost(Map<String, Long> prices) {
            long best = Long.MAX_VALUE;
            for (Supply option : options) {
                Long value = prices.get(option.id);
                if (value != null) best = Math.min(best, value * quantity / option.quantity);
            }
            return best;
        }
    }

    private record Conversion(String output, int quantity, List<Input> inputs) {
        long cost(Map<String, Long> prices) {
            long cap = MAX_UNIT * quantity;
            long total = 0;
            for (Input input : inputs) {
                long cost = input.cost(prices);
                if (cost == Long.MAX_VALUE) return -1;
                total = Math.min(cap, total + cost);
            }
            // All ingredients are priced. A low-cost recipe still yields at least 1 EMC per item.
            return Math.max(1, total / quantity);
        }
    }
}
