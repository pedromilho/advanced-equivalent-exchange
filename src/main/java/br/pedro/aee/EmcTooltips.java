package br.pedro.aee;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import java.util.HashMap;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonInvalidOperationException;
import org.bson.json.JsonParseException;

/** Adds the EMC line to the native item descriptions sent to the client. */
final class EmcTooltips {
    private static final String DESCRIPTION_PREFIX = "server.aee.emcDescription.";
    private static final String EMC_COLOR = "#FFD700";
    private final Supplier<EmcValues> prices;

    EmcTooltips(Supplier<EmcValues> prices) {
        this.prices = prices;
    }

    static String line(long emc) {
        return "EMC: " + emc;
    }

    void decorate(Packet packet) {
        var values = prices.get();
        switch (packet) {
            case UpdateItems update -> decorateItems(update);
            case UpdateTranslations update -> decorateTranslations(update, values);
            case UpdatePlayerInventory update -> {
                decorateSection(update.storage, values);
                decorateSection(update.hotbar, values);
                decorateSection(update.armor, values);
                decorateSection(update.tools, values);
                decorateSection(update.utility, values);
                decorateSection(update.backpack, values);
                decorateSection(update.abilitySlots, values);
                decorateSection(update.runeBag, values);
            }
            case OpenWindow window -> decorateSection(window.inventory, values);
            case UpdateWindow window -> decorateSection(window.inventory, values);
            default -> { }
        }
    }

    private static void decorateItems(UpdateItems update) {
        if (update.items == null || update.items.isEmpty()) return;
        var copies = new HashMap<String, ItemBase>(update.items.size());
        update.items.forEach((id, item) -> {
            String key = DESCRIPTION_PREFIX + id;
            var original = item.translationProperties;
            if (original != null && key.equals(original.description)) {
                copies.put(id, item);
                return;
            }
            // Item.toPacket() can return the same definition to other server code.
            var copy = new ItemBase(item);
            copy.translationProperties = original == null
                ? new ItemTranslationProperties() : new ItemTranslationProperties(original);
            if (copy.translationProperties.name == null) {
                var asset = Item.getAssetMap().getAsset(id);
                if (asset != null) copy.translationProperties.name = asset.getTranslationKey();
            }
            copy.translationProperties.description = key;
            copies.put(id, copy);
        });
        update.items = copies;
    }

    private static void decorateTranslations(UpdateTranslations update, EmcValues values) {
        if (update.translations == null || update.type == UpdateType.Remove) return;
        var originals = update.translations;
        var copies = new HashMap<>(originals);
        for (Item item : Item.getAssetMap().getAssetMap().values()) {
            String description = originals.get(item.getDescriptionTranslationKey());
            if (update.type != UpdateType.Init && description == null) continue;
            String emc = "<color is=\"" + EMC_COLOR + "\">" + line(values.get(item.getId())) + "</color>";
            copies.put(DESCRIPTION_PREFIX + item.getId(),
                description == null || description.isBlank() ? emc : description + "\n\n" + emc);
        }
        update.translations = copies;
    }

    private static void decorateSection(InventorySection section, EmcValues values) {
        if (section == null || section.items == null) return;
        HashMap<Integer, ItemWithAllMetadata> copies = null;
        for (var entry : section.items.entrySet()) {
            var original = entry.getValue();
            var copy = decorateStackDescription(original, values);
            if (copy == original) continue;
            if (copies == null) copies = new HashMap<>(section.items);
            copies.put(entry.getKey(), copy);
        }
        if (copies != null) section.items = copies;
    }

    private static ItemWithAllMetadata decorateStackDescription(ItemWithAllMetadata original, EmcValues values) {
        if (original == null || original.itemId == null || original.metadata == null
            || !original.metadata.contains("\"" + ItemDisplayMetadata.KEY + "\"")) return original;
        try {
            var metadata = BsonDocument.parse(original.metadata);
            var display = metadata.getDocument(ItemDisplayMetadata.KEY, null);
            if (display == null || !display.containsKey("Description")) return original;
            var extra = new ExtraInfo();
            var description = Message.CODEC.decode(display.get("Description"), extra);
            if (description == null) return original;

            String suffix = "\n\n" + line(values.get(original.itemId));
            var formatted = description.getFormattedMessage();
            var children = formatted.children;
            var tail = children == null || children.length == 0 ? null : children[children.length - 1];
            var line = Message.raw(suffix).color(EMC_COLOR);
            if (tail != null && EMC_COLOR.equals(tail.color) && tail.rawText != null
                && tail.rawText.startsWith("\n\nEMC: ")) {
                if (suffix.equals(tail.rawText)) return original;
                var updated = new FormattedMessage(formatted);
                updated.children = children.clone();
                updated.children[children.length - 1] = line.getFormattedMessage();
                description = new Message(updated);
            } else {
                description = Message.join(description, line);
            }
            // A custom stack description overrides the item asset's description on the client.
            display.put("Description", Message.CODEC.encode(description, extra));
            var copy = new ItemWithAllMetadata(original);
            copy.metadata = metadata.toJson();
            return copy;
        } catch (JsonParseException | BsonInvalidOperationException invalid) {
            return original;
        }
    }
}
