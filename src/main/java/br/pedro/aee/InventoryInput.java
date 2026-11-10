package br.pedro.aee;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Locale;

/** Inventory event fields supplied by the Hytale client and the current grid binding. */
record InventoryInput(String action, String session, Integer grid, Integer slot, Integer sourceSection,
                      Integer sourceSlot, Integer quantity, Integer clicks, Integer version, String itemId,
                      String search, boolean shift, boolean right, boolean dragRight) {
    private enum Gesture {
        PICK(CustomUIEventBindingType.SlotClicking),
        DOUBLE(CustomUIEventBindingType.SlotDoubleClicking),
        RELEASE(CustomUIEventBindingType.SlotClickReleaseWhileDragging),
        PRESS(CustomUIEventBindingType.SlotClickPressWhileDragging),
        DROP(CustomUIEventBindingType.Dropped),
        CANCEL(CustomUIEventBindingType.DragCancelled),
        ENTER(CustomUIEventBindingType.SlotMouseEntered),
        EXIT(CustomUIEventBindingType.SlotMouseExited);

        private final CustomUIEventBindingType type;

        Gesture(CustomUIEventBindingType type) {
            this.type = type;
        }
    }

    static void bindGrid(UIEventBuilder events, String selector, int grid, int version, String session) {
        for (var gesture : Gesture.values()) {
            var data = EventData.of("Action", gesture.name().toLowerCase(Locale.ROOT))
                .append("Section", Integer.toString(grid))
                .append("GridVersion", Integer.toString(version))
                .append("Session", session);
            events.addEventBinding(gesture.type, selector, data, false);
        }
    }

    static InventoryInput parse(String raw) {
        var data = JsonParser.parseString(raw).getAsJsonObject();
        String action = text(data, "Action", "");
        String pickupButton = text(data, "PressedMouseButton", "");
        String button = text(data, "ClickMouseButton", text(data, "MouseButton", text(data, "Button", "")));
        if (button.isEmpty() && ("pick".equals(action) || "double".equals(action))) button = pickupButton;
        return new InventoryInput(action, text(data, "Session", ""), number(data, "Section"),
            number(data, "SlotIndex"), number(data, "DragSourceInventorySectionId", "SourceInventorySectionId"),
            number(data, "DragSourceSlotId", "SourceSlotId"), number(data, "DragItemStackQuantity", "ItemStackQuantity"),
            number(data, "ClickCount"), number(data, "GridVersion"),
            text(data, "DragItemStackId", text(data, "ItemStackId", null)), text(data, "@Search", ""),
            Boolean.parseBoolean(text(data, "ShiftHeld", "")), isRight(button),
            isRight(text(data, "DragPressedMouseButton", pickupButton)));
    }

    private static boolean isRight(String button) {
        return "3".equals(button) || button.toLowerCase(Locale.ROOT).contains("right");
    }

    private static String text(JsonObject object, String key, String fallback) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static Integer number(JsonObject object, String key) {
        String value = text(object, key, null);
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Integer number(JsonObject object, String preferred, String fallback) {
        Integer value = number(object, preferred);
        return value == null ? number(object, fallback) : value;
    }
}
