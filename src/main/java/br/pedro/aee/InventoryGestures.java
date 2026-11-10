package br.pedro.aee;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Adapted from Advanced Storage's CatalogGestures for physical inventory slots. */
final class InventoryGestures {
    static final int CHEST = -1001;
    static final int STORAGE = -1002;
    static final int HOTBAR = -1003;
    static final int BACKPACK = -1004;
    static final int BURN = -1005;
    static final int TRADE = -1006;
    static final int CONDENSER_MOLD = -1007;
    static final int CONDENSER_OUTPUT = -1008;

    record Section(int nativeId, ItemContainer container, boolean virtual, int firstSlot, int capacity) {
        Section(int nativeId, ItemContainer container) {
            this(nativeId, container, false);
        }

        Section(int nativeId, ItemContainer container, boolean virtual) {
            this(nativeId, container, virtual, 0, container.getCapacity());
        }

        short physicalSlot(int slot) {
            return (short) (firstSlot + slot);
        }
    }
    record Slot(int grid, int index) {}
    private record Pickup(Slot slot, ItemStack item, int quantity, boolean quick, boolean one) {}
    private record Drop(Slot source, Slot destination, String item, Integer quantity) {}

    interface Moves {
        void move(Section from, int slot, int quantity, Section to, int destination);
        void quick(Section from, int slot, int quantity);
    }

    enum Result { PICKED, MOVED, CANCELLED, IGNORED, STALE, REJECTED }

    private final Map<Integer, Section> sections;
    private final Moves moves;
    private final Map<Integer, ItemStack[]> shown = new HashMap<>();
    private final Set<Slot> visited = new HashSet<>();
    private final Set<Slot> placedOnPress = new HashSet<>();
    private Pickup pickup;
    private Slot completedSource;
    private Drop completedDrop;
    private long completedAt;
    private Integer resetGrid;
    private boolean sweeping;

    InventoryGestures(Map<Integer, Section> sections, Moves moves) {
        this.sections = new LinkedHashMap<>(sections);
        this.moves = moves;
        capture();
    }

    boolean holding() {
        return pickup != null;
    }

    void clearPickup() {
        pickup = null;
        sweeping = false;
        visited.clear();
        placedOnPress.clear();
    }

    void cancel() {
        if (pickup != null) finish(pickup.slot);
        clearPickup();
    }

    Integer takeResetGrid() {
        var grid = resetGrid;
        resetGrid = null;
        return grid;
    }

    private void finish(Slot source) {
        pickup = null;
        completedSource = source;
        resetGrid = source.grid;
    }

    void capture() {
        if (holding()) return;
        for (var entry : sections.entrySet()) {
            var section = entry.getValue();
            var snapshot = new ItemStack[section.capacity];
            for (int i = 0; i < snapshot.length; i++) snapshot[i] = snapshot(stackAt(section, i));
            shown.put(entry.getKey(), snapshot);
        }
    }

    ItemStack[] displayed(int grid) {
        return shown.getOrDefault(grid, ItemStack.EMPTY_ARRAY);
    }

    private boolean valid(Slot slot) {
        if (slot == null) return false;
        var section = sections.get(slot.grid);
        return section != null && slot.index >= 0 && slot.index < section.capacity;
    }

    private ItemStack shown(Slot slot) {
        var items = displayed(slot.grid);
        return slot.index < items.length ? items[slot.index] : null;
    }

    private ItemStack current(Slot slot) {
        return stackAt(sections.get(slot.grid), slot.index);
    }

    private static ItemStack stackAt(Section section, int slot) {
        var item = section.container.getItemStack(section.physicalSlot(slot));
        return ItemStack.isEmpty(item) ? null : item;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack snapshot(ItemStack item) {
        return item == null ? null : item.withMetadata(item.getMetadata());
    }

    private boolean matches(Pickup source) {
        if (!valid(source.slot)) return false;
        var live = current(source.slot);
        return live != null && live.isStackableWith(source.item) && live.getQuantity() >= source.quantity;
    }

    private Slot source(InventoryInput input) {
        if (input.sourceSection() == null || input.sourceSlot() == null) return null;
        for (var entry : sections.entrySet()) {
            if (entry.getKey().equals(input.sourceSection()) || entry.getValue().nativeId == input.sourceSection()) {
                return new Slot(entry.getKey(), input.sourceSlot());
            }
        }
        return null;
    }

    private void remember(Slot slot) {
        var items = displayed(slot.grid);
        if (slot.index < items.length) items[slot.index] = snapshot(current(slot));
    }

    private void move(Slot source, int quantity, Slot target) {
        moves.move(sections.get(source.grid), source.index, quantity, sections.get(target.grid), target.index);
    }

    private Result gather(Slot target) {
        if (sections.get(target.grid).virtual) return Result.IGNORED;
        var expected = shown(target);
        if (expected == null || !expected.equals(current(target))) return stale(target);
        clearPickup();
        finish(target);
        placedOnPress.add(target);
        int maximum = expected.getItem().getMaxStack();
        boolean changed = false;
        // Sections are supplied in player inventory order, with the chest last.
        for (var entry : sections.entrySet()) {
            if (entry.getValue().virtual) continue;
            for (int i = 0; i < entry.getValue().capacity; i++) {
                var source = new Slot(entry.getKey(), i);
                if (source.equals(target)) continue;
                var into = current(target);
                if (into == null || !into.isStackableWith(expected)) return stale(target);
                int space = maximum - into.getQuantity();
                if (space <= 0) return changed ? Result.MOVED : Result.IGNORED;
                var item = current(source);
                if (item == null || !item.isStackableWith(expected) || !item.equals(shown(source))) continue;
                move(source, Math.min(space, item.getQuantity()), target);
                if (!Objects.equals(into, current(target))) {
                    changed = true;
                    remember(source);
                    remember(target);
                }
            }
        }
        return changed ? Result.MOVED : Result.IGNORED;
    }

    private Result pick(Slot slot, InventoryInput input) {
        completedSource = null;
        completedDrop = null;
        if (holding()) return Result.IGNORED;
        clearPickup();
        var item = shown(slot);
        if (item == null) return Result.IGNORED;
        if (!expectedId(input, item)) return stale(slot);
        int quantity = item.getQuantity();
        if (input.right()) quantity = input.shift() ? quantity / 2 + quantity % 2 : 1;
        if (slot.grid != TRADE && input.quantity() != null && input.quantity() > 0) {
            quantity = Math.min(quantity, input.quantity());
        }
        var chosen = new Pickup(slot, item, quantity, input.shift() && !input.right(), input.right() && !input.shift());
        if (!matches(chosen)) return stale(slot);
        pickup = chosen;
        return Result.PICKED;
    }

    Result quick(Slot slot, String expectedId) {
        if (!valid(slot)) return Result.IGNORED;
        var item = shown(slot);
        if (item == null) return Result.IGNORED;
        if (!item.equals(current(slot)) || expectedId != null && !expectedId.equals(item.getItemId())) {
            return stale(slot);
        }
        clearPickup();
        moves.quick(sections.get(slot.grid), slot.index, item.getQuantity());
        finish(slot);
        return Objects.equals(item, current(slot)) ? Result.REJECTED : Result.MOVED;
    }

    Result handle(InventoryInput input) {
        if ("cancel".equals(input.action())) {
            cancel();
            return Result.CANCELLED;
        }
        if (input.grid() == null || input.slot() == null) return Result.IGNORED;
        var target = new Slot(input.grid(), input.slot());
        if (!valid(target)) return Result.IGNORED;
        var namedSource = source(input);
        boolean press = "press".equals(input.action());
        boolean drop = "drop".equals(input.action());
        boolean hover = "enter".equals(input.action());
        boolean doublePress = press && target.equals(namedSource) && input.clicks() != null
            && input.clicks() >= 2 && input.clicks() % 2 == 0;
        if (!input.right() && ("double".equals(input.action()) || doublePress)) return gather(target);
        if (hover && target.equals(completedSource)) completedSource = null;
        if ("exit".equals(input.action())) {
            visited.remove(target);
            return Result.IGNORED;
        }
        if ("pick".equals(input.action())) return pick(target, input);
        if (drop && placedOnPress.contains(target)) {
            sweeping = false;
            visited.clear();
            return Result.IGNORED;
        }

        var envelope = new Drop(namedSource, target, input.itemId(), input.quantity());
        boolean wasHolding = holding();
        if (holding() && press && namedSource != null) {
            if (!namedSource.equals(pickup.slot)) pickup = null;
            else if (pickup.slot.grid != TRADE && pickup.one && input.quantity() != null && input.quantity() > 1) {
                pickup = new Pickup(pickup.slot, pickup.item, Math.min(pickup.item.getQuantity(), input.quantity()), false, false);
            }
        }
        // A split can arrive in a press/drop envelope without an earlier pickup event.
        if (!holding() && (drop || press)) {
            if (namedSource == null || namedSource.equals(completedSource) || input.itemId() == null
                || input.quantity() == null || input.quantity() <= 0
                || (drop && envelope.equals(completedDrop) && System.nanoTime() - completedAt < 200_000_000L)) {
                return Result.IGNORED;
            }
            if (!valid(namedSource)) return stale(target);
            var item = shown(namedSource);
            if (item == null || !expectedId(input, item)) return stale(namedSource);
            pickup = new Pickup(namedSource, item, Math.min(item.getQuantity(), input.quantity()),
                false, input.quantity() == 1 && input.dragRight());
        }
        if (!holding()) return Result.IGNORED;
        var selected = pickup;
        if ("release".equals(input.action())) {
            sweeping = false;
            visited.clear();
            if (!selected.quick || !selected.slot.equals(target)) return Result.IGNORED;
            if (!matches(selected)) return stale(selected.slot);
            var before = current(selected.slot);
            moves.quick(sections.get(target.grid), target.index, selected.quantity);
            cancel();
            return Objects.equals(before, current(target)) ? Result.REJECTED : Result.MOVED;
        }
        if ((!press && !drop && !hover) || (hover && !sweeping)) return Result.IGNORED;
        if (press && !input.right()) {
            sweeping = false;
            visited.clear();
            placedOnPress.clear();
        }
        if (!matches(selected) || (namedSource != null && !namedSource.equals(selected.slot))
            || (input.sourceSection() != null && namedSource == null && input.sourceSlot() != null)
            || !expectedId(input, selected.item)) return stale(selected.slot);
        if (press && input.right()) {
            if (!sweeping) {
                placedOnPress.clear();
                visited.clear();
            }
            sweeping = true;
            if (selected.one && selected.slot.grid != TRADE) {
                selected = new Pickup(selected.slot, selected.item, current(selected.slot).getQuantity(), false, false);
                pickup = selected;
            }
        }
        if (target.equals(selected.slot)) {
            if (drop || (press && (wasHolding || !input.right()) && !selected.quick)) {
                cancel();
                return Result.CANCELLED;
            }
            return Result.IGNORED;
        }
        if (sweeping && !visited.add(target)) return Result.IGNORED;
        boolean one = hover || input.right();
        var before = current(selected.slot);
        var into = current(target);
        if (!Objects.equals(shown(target), into)) return stale(selected.slot);
        if (one && target.grid != BURN && into != null && (!into.isStackableWith(selected.item)
            || into.getQuantity() >= into.getItem().getMaxStack())) return Result.REJECTED;
        int quantity = one ? 1 : selected.quantity;
        if (selected.slot.grid != TRADE && input.quantity() != null && input.quantity() > 0) {
            quantity = Math.min(quantity, input.quantity());
        }
        move(selected.slot, quantity, target);
        var after = current(selected.slot);
        boolean changed = !Objects.equals(before, after) || !Objects.equals(into, current(target));
        if (changed) {
            int remaining = after != null && after.isStackableWith(selected.item) ? after.getQuantity() : 0;
            int stillHeld = selected.quantity - Math.max(0, before.getQuantity() - remaining);
            if (one && stillHeld > 0 && remaining > 0) {
                pickup = new Pickup(selected.slot, selected.item, Math.min(stillHeld, remaining), false, false);
            } else {
                finish(selected.slot);
            }
            remember(selected.slot);
            remember(target);
            if (one || press) placedOnPress.add(target);
        } else if (!one) {
            finish(selected.slot);
        }
        completedDrop = envelope;
        completedAt = System.nanoTime();
        return changed ? Result.MOVED : Result.REJECTED;
    }

    private Result stale(Slot slot) {
        clearPickup();
        finish(slot);
        return Result.STALE;
    }

    private static boolean expectedId(InventoryInput input, ItemStack item) {
        return input.itemId() == null || input.itemId().equals(item.getItemId());
    }
}
