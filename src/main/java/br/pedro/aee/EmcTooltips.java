package br.pedro.aee;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.InventorySection;
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
import org.bson.BsonDocument;
import org.bson.BsonInvalidOperationException;
import org.bson.json.JsonParseException;

/** Adds the EMC line to the native item descriptions sent to the client. */
final class EmcTooltips {
    private static final String DESCRIPTION_PREFIX = "server.aee.emcDescription.";
    private static final String EMC_COLOR = "#FFD700";
    private final EmcValues values;

    EmcTooltips(EmcValues values) {
        this.values = values;
    }

    static String line(long emc) {
        return "EMC: " + emc;
    }

    void decorate(Packet packet) {
        switch (packet) {
            case UpdateItems update -> decorateItems(update);
            case UpdateTranslations update -> decorateTranslations(update);
            case UpdatePlayerInventory update -> {
                decorateSection(update.storage);
                decorateSection(update.hotbar);
                decorateSection(update.armor);
                decorateSection(update.tools);
                decorateSection(update.utility);
                decorateSection(update.backpack);
                decorateSection(update.abilitySlots);
                decorateSection(update.runeBag);
            }
            case OpenWindow window -> decorateSection(window.inventory);
            case UpdateWindow window -> decorateSection(window.inventory);
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

    private void decorateTranslations(UpdateTranslations update) {
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

    private void decorateSection(InventorySection section) {
        if (section == null || section.items == null) return;
        HashMap<Integer, ItemWithAllMetadata> copies = null;
        for (var entry : section.items.entrySet()) {
            var original = entry.getValue();
            var copy = decorateStackDescription(original);
            if (copy == original) continue;
            if (copies == null) copies = new HashMap<>(section.items);
            copies.put(entry.getKey(), copy);
        }
        if (copies != null) section.items = copies;
    }

    private ItemWithAllMetadata decorateStackDescription(ItemWithAllMetadata original) {
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
            var children = description.getFormattedMessage().children;
            if (children != null && children.length > 0 && suffix.equals(children[children.length - 1].rawText)) {
                return original;
            }
            // A custom stack description overrides the item asset's description on the client.
            display.put("Description",
                Message.CODEC.encode(Message.join(description, Message.raw(suffix).color(EMC_COLOR)), extra));
            var copy = new ItemWithAllMetadata(original);
            copy.metadata = metadata.toJson();
            return copy;
        } catch (JsonParseException | BsonInvalidOperationException invalid) {
            return original;
        }
    }
}
