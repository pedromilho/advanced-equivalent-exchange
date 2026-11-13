package br.pedro.aee;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.SmartMoveType;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SortType;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;

final class AlchemicalChestPage extends InteractiveCustomUIPage<InventoryInput> {
    private static final BuilderCodec<InventoryInput> CODEC = BuilderCodec.builder(
        InventoryInput.class, () -> InventoryInput.parse("{}")).build();
    private static final String UI = "AdvancedEquivalency/";
    private static final int FRAME_WIDTH = 1040;
    private static final int FRAME_HEIGHT = 550;
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PITCH = 44;
    private static final Map<Integer, Grid> GRIDS = Map.of(
        InventoryGestures.CHEST, new Grid("#ChestHost", "#ChestGrid", "ChestGrid.ui", 13, 17, 118, 8),
        InventoryGestures.STORAGE, new Grid("#InventoryHost", "#PlayerGrid", "ChestPlayerGrid.ui", 9, 608, 77, 4),
        InventoryGestures.HOTBAR, new Grid("#HotbarHost", "#PlayerGrid", "ChestPlayerGrid.ui", 9, 608, 289, 1),
        InventoryGestures.BACKPACK, new Grid("#BackpackHost", "#PlayerGrid", "ChestPlayerGrid.ui", 9, 608, 369, 2)
    );

    private final World world;
    private final Ref<ChunkStore> blockRef;
    private final ContainerBlockWindow window;
    private final AdvancedEquivalencyPlugin plugin;
    private final String session = UUID.randomUUID().toString();
    private final String language;
    private final boolean portuguese;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Map<Integer, InventoryGestures.Section> sections = new LinkedHashMap<>();
    private final Map<Integer, ItemStack[]> mounted = new HashMap<>();
    private final Map<Integer, Integer> versions = new HashMap<>();
    private final ArrayList<EventRegistration<?, ?>> registrations = new ArrayList<>();
    private final StorageTooltip tooltip = new StorageTooltip();
    private InventoryGestures gestures;
    private InventoryGestures.Slot hovered;
    private String search = "";
    private String notice = "";
    private String chestSummary = "";
    private String mountedSummary;
    private boolean active;

    AlchemicalChestPage(PlayerRef player, World world, Ref<ChunkStore> blockRef, ContainerBlockWindow window,
                        AdvancedEquivalencyPlugin plugin) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, CODEC);
        this.world = world;
        this.blockRef = blockRef;
        this.window = window;
        this.plugin = plugin;
        language = player.getLanguage() == null ? "en-US" : player.getLanguage().replace('_', '-');
        portuguese = "pt".equals(Locale.forLanguageTag(language).getLanguage());
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        var inventory = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        var hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        var backpack = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        sections.put(InventoryGestures.STORAGE,
            new InventoryGestures.Section(InventoryComponent.STORAGE_SECTION_ID, inventory.getInventory()));
        sections.put(InventoryGestures.HOTBAR,
            new InventoryGestures.Section(InventoryComponent.HOTBAR_SECTION_ID, hotbar.getInventory()));
        if (backpack != null && backpack.getInventory().getCapacity() > 0) {
            sections.put(InventoryGestures.BACKPACK,
                new InventoryGestures.Section(InventoryComponent.BACKPACK_SECTION_ID, backpack.getInventory()));
        }
        sections.put(InventoryGestures.CHEST, new InventoryGestures.Section(window.getId(), window.getItemContainer()));

        gestures = new InventoryGestures(sections, new InventoryGestures.Moves() {
            @Override
            public void move(InventoryGestures.Section from, int slot, int quantity, InventoryGestures.Section to,
                             int destination) {
                InventoryUtils.moveItem(ref, from.nativeId(), slot, quantity, to.nativeId(), destination, store);
            }

            @Override
            public void quick(InventoryGestures.Section from, int slot, int quantity) {
                var settings = store.getComponent(ref, PlayerSettings.getComponentType());
                InventoryUtils.smartMoveItem(ref, from.nativeId(), slot, quantity, SmartMoveType.PutInHotbarOrWindow,
                    settings == null ? PlayerSettings.defaults() : settings, store);
            }
        });

        ui.append(UI + "AlchemicalChest.ui");
        ui.append("#PageTooltipHost", UI + "StorageTooltip.ui");
        ui.set("#Heading.Text", tr("Baú Alquímico", "Alchemical Chest"));
        ui.set("#InventoryTitle.Text", tr("Inventário", "Inventory"));
        ui.set("#HotbarTitle.Text", tr("Barra rápida", "Hotbar"));
        ui.set("#BackpackTitle.Text", tr("Mochila", "Backpack"));
        ui.set("#BackpackTitle.Visible", sections.containsKey(InventoryGestures.BACKPACK));
        ui.set("#Sort.Text", tr("Ordenar", "Sort"));
        ui.set("#Search.PlaceholderText", tr("Buscar no baú…", "Search chest…"));
        ui.set("#Search.Value", search);
        for (int grid : sections.keySet()) mount(ui, events, grid);

        bind(events, CustomUIEventBindingType.ValueChanged, "#Search",
            EventData.of("Action", "search").append("@Search", "#Search.Value"));
        bind(events, CustomUIEventBindingType.Activating, "#Sort", EventData.of("Action", "sort"));
        bind(events, CustomUIEventBindingType.Dismissing, "#ChestRoot", EventData.of("Action", "close"));
        render(ui);

        if (!active) {
            active = true;
            for (var section : sections.values()) {
                registrations.add(section.container().registerChangeEvent(event -> scheduleRefresh(ref, store)));
            }
            registrations.add(window.registerCloseEvent(event -> world.execute(() -> {
                if (active) close();
            })));
        }
    }

    private void mount(UICommandBuilder ui, UIEventBuilder events, int key) {
        var grid = GRIDS.get(key);
        int version = versions.merge(key, 1, Integer::sum);
        String selector = grid.host + " " + grid.element;
        ui.clear(grid.host);
        ui.append(grid.host, UI + grid.template);
        ui.set(selector + ".InventorySectionId", sections.get(key).nativeId());
        InventoryInput.bindGrid(events, selector, key, version, session);
        mounted.remove(key);
    }

    private void bind(UIEventBuilder events, CustomUIEventBindingType type, String selector, EventData data) {
        events.addEventBinding(type, selector, data.append("Session", session), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String raw) {
        if (!active || raw == null || raw.length() > 16384) return;
        InventoryInput input;
        try {
            input = InventoryInput.parse(raw);
        } catch (RuntimeException invalid) {
            return;
        }
        if (!session.equals(input.session())) return;
        if (input.grid() != null && (!sections.containsKey(input.grid())
            || !versions.get(input.grid()).equals(input.version()))) return;
        if ("close".equals(input.action()) || !valid(ref, store)) {
            close();
            return;
        }

        switch (input.action()) {
            case "search" -> {
                var query = input.search();
                search = query.substring(0, Math.min(100, query.length())).strip().toLowerCase(Locale.ROOT);
                gestures.cancel();
                mounted.remove(InventoryGestures.CHEST);
                hovered = null;
                notice = "";
            }
            case "sort" -> {
                gestures.cancel();
                window.getItemContainer().sortItems(SortType.NAME);
                hovered = null;
                notice = "";
            }
            default -> {
                updateHover(input);
                var result = gestures.handle(input);
                if (result == InventoryGestures.Result.STALE) {
                    notice = tr("O inventário mudou. Selecione a pilha de novo.", "Inventory changed. Pick up the stack again.");
                } else if (result == InventoryGestures.Result.REJECTED) {
                    notice = tr("Sem espaço para mover a pilha.", "No room to move that stack.");
                } else if (result != InventoryGestures.Result.IGNORED) {
                    notice = "";
                }
                if ((result == InventoryGestures.Result.IGNORED || result == InventoryGestures.Result.PICKED)
                    && (input.action().equals("enter") || input.action().equals("exit") || input.action().equals("pick"))) {
                    var ui = new UICommandBuilder();
                    renderTooltip(ui);
                    flush(ui, null);
                    return;
                }
            }
        }
        refresh();
    }

    private void updateHover(InventoryInput input) {
        if ("enter".equals(input.action()) && input.grid() != null && input.slot() != null) {
            hovered = new InventoryGestures.Slot(input.grid(), input.slot());
        } else if ("exit".equals(input.action()) && hovered != null) {
            if (Integer.valueOf(hovered.grid()).equals(input.grid()) && Integer.valueOf(hovered.index()).equals(input.slot())) {
                hovered = null;
            }
        } else {
            hovered = null;
        }
    }

    private void scheduleRefresh(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!refreshPending.compareAndSet(false, true)) return;
        world.execute(() -> {
            refreshPending.set(false);
            if (!active || !ref.isValid()) return;
            if (valid(ref, store)) refresh();
            else close();
        });
    }

    private boolean valid(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!ref.isValid() || !blockRef.isValid() || store.getExternalData().getWorld() != world
            || !window.validate(ref, store)) return false;
        var player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getWindowManager().getWindow(window.getId()) == window;
    }

    private void refresh() {
        var ui = new UICommandBuilder();
        var events = new UIEventBuilder();
        Integer reset = gestures.takeResetGrid();
        if (reset != null) mount(ui, events, reset);
        render(ui);
        flush(ui, events);
    }

    private void render(UICommandBuilder ui) {
        gestures.capture();
        for (int key : sections.keySet()) {
            var items = gestures.displayed(key);
            if (Arrays.equals(items, mounted.get(key))) continue;
            var grid = GRIDS.get(key);
            var slots = new ItemGridSlot[items.length];
            int used = 0;
            int matches = 0;
            for (int i = 0; i < items.length; i++) {
                var slot = new ItemGridSlot();
                var stack = items[i];
                if (!ItemStack.isEmpty(stack)) {
                    slot.setItemStack(stack.withMetadata((BsonDocument) null));
                    if (key == InventoryGestures.CHEST) {
                        used++;
                        boolean match = search.isEmpty() || matchesSearch(stack);
                        if (match) matches++;
                        slot.setItemUncraftable(!match);
                    }
                }
                slot.setActivatable(true);
                slots[i] = slot;
            }
            ui.set(grid.host + " " + grid.element + ".Slots", slots);
            mounted.put(key, items.clone());
            if (key == InventoryGestures.CHEST) {
                chestSummary = used + " / " + items.length + tr(" espaços ocupados", " slots occupied");
                if (!search.isEmpty()) chestSummary += "  ·  " + matches + tr(" correspondências", " matches");
            }
        }
        String summary = notice.isEmpty() ? chestSummary : notice;
        if (!summary.equals(mountedSummary)) {
            ui.set("#Summary.Text", summary);
            mountedSummary = summary;
        }
        renderTooltip(ui);
    }

    private boolean matchesSearch(ItemStack stack) {
        if (stack.getItemId().toLowerCase(Locale.ROOT).contains(search)) return true;
        String key = stack.getItem().getTranslationKey();
        if (key == null) return false;
        String name = I18nModule.get().getMessage(language, key);
        if (name == null) name = I18nModule.get().getMessage("en-US", key);
        return name != null && name.toLowerCase(Locale.ROOT).contains(search);
    }

    private void renderTooltip(UICommandBuilder ui) {
        ItemStack item = null;
        int x = 0;
        int y = 0;
        if (hovered != null && !gestures.holding()) {
            var items = gestures.displayed(hovered.grid());
            int slot = hovered.index();
            if (slot >= 0 && slot < items.length) {
                var grid = GRIDS.get(hovered.grid());
                item = items[slot];
                x = grid.x + slot % grid.columns * SLOT_PITCH;
                y = grid.y + Math.min(grid.visibleRows - 1, slot / grid.columns) * SLOT_PITCH;
            }
        }
        tooltip.render(ui, item, language, x, y, SLOT_SIZE, FRAME_WIDTH, FRAME_HEIGHT,
            EmcTooltips.line(Transmutation.burnValue(item, plugin.emcValues())));
    }

    private void flush(UICommandBuilder ui, UIEventBuilder events) {
        if (ui.getCommands().length > 0 || (events != null && events.getEvents().length > 0)) {
            sendUpdate(ui, events, false);
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        active = false;
        registrations.forEach(EventRegistration::unregister);
        registrations.clear();
        if (!ref.isValid()) return;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;
        var manager = player.getWindowManager();
        if (manager.getWindow(window.getId()) == window) manager.closeWindow(ref, window.getId(), store);
    }

    private String tr(String pt, String en) {
        return portuguese ? pt : en;
    }

    private record Grid(String host, String element, String template, int columns, int x, int y, int visibleRows) {}
}
