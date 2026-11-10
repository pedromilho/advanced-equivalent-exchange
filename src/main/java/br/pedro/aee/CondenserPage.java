package br.pedro.aee;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.PreventInventoryAccess;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;

final class CondenserPage extends InteractiveCustomUIPage<InventoryInput> {
    private static final BuilderCodec<InventoryInput> CODEC = BuilderCodec.builder(
        InventoryInput.class, () -> InventoryInput.parse("{}")).build();
    private static final String UI = "AdvancedEquivalentExchange/";
    private static final int INPUT = InventoryGestures.CHEST;
    private static final int OUTPUT = InventoryGestures.CONDENSER_OUTPUT;
    private static final int MOLD = InventoryGestures.CONDENSER_MOLD;
    private static final int STORAGE = InventoryGestures.STORAGE;
    private static final int HOTBAR = InventoryGestures.HOTBAR;
    private static final int BACKPACK = InventoryGestures.BACKPACK;
    private static final Map<UUID, CondenserPage> OPEN = new ConcurrentHashMap<>();

    private final EquivalentExchangePlugin plugin;
    private final World world;
    private final BlockPosition target;
    private final Ref<ChunkStore> block;
    private final EnergyCondenser machine;
    private final SimpleItemContainer stock;
    private final Map<Integer, ContainerWindow> windows = new LinkedHashMap<>();
    private final Map<Integer, InventoryGestures.Section> sections = new LinkedHashMap<>();
    private final Map<Integer, Grid> grids = new LinkedHashMap<>();
    private final Map<Integer, Integer> versions = new HashMap<>();
    private final Map<Integer, ItemStack[]> mounted = new HashMap<>();
    private final ArrayList<EventRegistration<?, ?>> registrations = new ArrayList<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final StorageTooltip tooltip = new StorageTooltip();
    private final String session = UUID.randomUUID().toString();
    private final String language;
    private final boolean portuguese;
    private final NumberFormat numbers;
    private InventoryGestures gestures;
    private InventoryGestures.Slot hovered;
    private ItemContainer playerInventory;
    private String notice = "";
    private boolean active;

    CondenserPage(PlayerRef player, EquivalentExchangePlugin plugin, World world, BlockPosition target,
                  Ref<ChunkStore> block, EnergyCondenser machine, SimpleItemContainer stock) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, CODEC);
        this.plugin = plugin;
        this.world = world;
        this.target = target;
        this.block = block;
        this.machine = machine;
        this.stock = stock;
        language = player.getLanguage() == null ? "en-US" : player.getLanguage().replace('_', '-');
        var locale = Locale.forLanguageTag(language);
        portuguese = locale.getLanguage().equals("pt");
        numbers = NumberFormat.getIntegerInstance(locale);
        windows.put(INPUT, TransmutationPage.emptyWindow((short) machine.inputs()));
        if (machine.advanced()) windows.put(OUTPUT, TransmutationPage.emptyWindow((short) machine.outputCount()));
        windows.put(MOLD, TransmutationPage.emptyWindow((short) 1));
    }

    ContainerWindow[] windows() { return windows.values().toArray(ContainerWindow[]::new); }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        playerInventory = InventoryComponent.getCombined(store, ref, InventoryComponent.STORAGE_HOTBAR_BACKPACK);
        addPlayerGrid(ref, store, STORAGE, InventoryComponent.STORAGE_SECTION_ID);
        addPlayerGrid(ref, store, HOTBAR, InventoryComponent.HOTBAR_SECTION_ID);
        addPlayerGrid(ref, store, BACKPACK, InventoryComponent.BACKPACK_SECTION_ID);
        addMachineGrid(INPUT, 0, machine.inputs());
        if (machine.advanced()) addMachineGrid(OUTPUT, machine.outputStart(), machine.outputCount());
        addMachineGrid(MOLD, machine.moldSlot(), 1);
        gestures = new InventoryGestures(sections, new InventoryGestures.Moves() {
            @Override
            public void move(InventoryGestures.Section from, int slot, int quantity,
                             InventoryGestures.Section to, int destination) {
                moveStack(ref, store, from, slot, quantity, to, destination);
            }

            @Override
            public void quick(InventoryGestures.Section from, int slot, int quantity) {
                quickStack(from, slot, quantity);
            }
        });

        ui.append(UI + "Condenser.ui");
        ui.append("#PageTooltipHost", UI + "StorageTooltip.ui");
        ui.set("#Heading.Text", tr("Condensador de Energia", "Energy Condenser")
            + (machine.advanced() ? " Tier 2" : " Tier 1"));
        ui.set("#MoldTitle.Text", tr("Molde", "Target"));
        ui.set("#InputTitle.Text", machine.advanced() ? tr("Entrada", "Input") : tr("Condensador", "Condenser"));
        ui.set("#OutputTitle.Text", tr("Saída", "Output"));
        ui.set("#OutputTitle.Visible", machine.advanced());
        ui.set("#InventoryTitle.Text", tr("Inventário", "Inventory"));
        ui.set("#HotbarTitle.Text", tr("Barra rápida", "Hotbar"));
        ui.set("#BackpackTitle.Text", tr("Mochila", "Backpack"));
        layout(ui);
        for (int grid : sections.keySet()) mount(ui, events, grid);
        var close = EventData.of("Action", "close").append("Session", session);
        events.addEventBinding(CustomUIEventBindingType.Dismissing, "#CondenserRoot", close, false);
        render(ui);

        if (active) return;
        active = true;
        OPEN.put(playerRef.getUuid(), this);
        for (var section : sections.values()) {
            if (section.container() != stock) {
                registrations.add(section.container().registerChangeEvent(event -> scheduleRefresh(ref, store)));
            }
        }
        registrations.add(stock.registerChangeEvent(event -> scheduleRefresh(ref, store)));
        for (var window : windows.values()) {
            registrations.add(window.registerCloseEvent(event -> world.execute(() -> {
                if (active) close();
            })));
        }
    }

    private void addPlayerGrid(Ref<EntityStore> ref, Store<EntityStore> store, int grid, int nativeId) {
        var container = InventoryUtils.getSectionById(ref, nativeId, store);
        if (container != null && container.getCapacity() > 0) {
            sections.put(grid, new InventoryGestures.Section(nativeId, container));
        }
    }

    private void addMachineGrid(int grid, int first, int capacity) {
        sections.put(grid, new InventoryGestures.Section(windows.get(grid).getId(), stock, grid == MOLD, first, capacity));
    }

    private void layout(UICommandBuilder ui) {
        boolean backpack = sections.containsKey(BACKPACK);
        int playerLeft = backpack ? 62 : 314;
        if (!backpack) ui.setObject("#PlayerStorage.Anchor", anchor(296, 0, 480, 268));
        ui.set("#BackpackTitle.Visible", backpack);
        if (machine.advanced()) {
            grids.put(INPUT, new Grid("#InputHost", "#ChestGrid", "ChestGrid.ui", 8, 222, 98, 40));
            grids.put(OUTPUT, new Grid("#OutputHost", "#ChestGrid", "ChestGrid.ui", 8, 628, 98, 40));
        } else {
            ui.setObject("#InputHost.Anchor", anchor(313, 46, 572, 220));
            ui.setObject("#InputTitle.Anchor", anchor(313, 14, 572, 24));
            grids.put(INPUT, new Grid("#InputHost", "#ChestGrid", "ChestGrid.ui", 13, 313, 98, 40));
        }
        grids.put(MOLD, new Grid("#MoldHost", "#TableGrid", "BurnGrid.ui", 1, 68, 118, 60));
        grids.put(STORAGE, new Grid("#InventoryHost", "#PlayerGrid", "ChestPlayerGrid.ui", 9, playerLeft, 385, 40));
        grids.put(HOTBAR, new Grid("#HotbarHost", "#PlayerGrid", "ChestPlayerGrid.ui", 9, playerLeft, 566, 40));
        grids.put(BACKPACK, new Grid("#BackpackHost", "#PlayerGrid", "ChestPlayerGrid.ui", 9, 566, 385, 40));
    }

    private static Anchor anchor(int left, int top, int width, int height) {
        var anchor = new Anchor();
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private void mount(UICommandBuilder ui, UIEventBuilder events, int key) {
        var grid = grids.get(key);
        int version = versions.merge(key, 1, Integer::sum);
        ui.clear(grid.host);
        ui.append(grid.host, UI + grid.template);
        ui.set(grid.selector() + ".InventorySectionId", sections.get(key).nativeId());
        if (key == INPUT || key == OUTPUT) ui.set(grid.selector() + ".SlotsPerRow", grid.columns);
        InventoryInput.bindGrid(events, grid.selector(), key, version, session);
        mounted.remove(key);
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
        if ("close".equals(input.action()) || !valid(ref, store)) {
            close();
            return;
        }
        if (input.grid() == null || !sections.containsKey(input.grid())
            || !versions.get(input.grid()).equals(input.version())) return;
        boolean entering = "enter".equals(input.action());
        boolean exiting = "exit".equals(input.action());
        var slot = input.slot() == null ? null : new InventoryGestures.Slot(input.grid(), input.slot());
        if (entering) hovered = slot;
        else if (!exiting || slot != null && slot.equals(hovered)) hovered = null;
        if (!entering && !exiting) notice = "";
        var result = gestures.handle(input);
        if ((entering || exiting) && result == InventoryGestures.Result.IGNORED) {
            var ui = new UICommandBuilder();
            renderTooltip(ui);
            sendUpdate(ui, null, false);
            return;
        }
        showResult(result);
        refresh();
    }

    private void showResult(InventoryGestures.Result result) {
        if (!notice.isEmpty()) return;
        if (result == InventoryGestures.Result.STALE) {
            notice = tr("O inventário mudou. Pegue a pilha de novo.", "The inventory changed. Pick up the stack again.");
        } else if (result == InventoryGestures.Result.REJECTED) {
            notice = tr("Não foi possível mover a pilha.", "That stack could not be moved.");
        }
    }

    private void moveStack(Ref<EntityStore> ref, Store<EntityStore> store, InventoryGestures.Section from, int slot,
                           int quantity, InventoryGestures.Section to, int destination) {
        short source = from.physicalSlot(slot);
        short target = to.physicalSlot(destination);
        if (to == sections.get(MOLD)) {
            var item = from.container().getItemStack(source);
            if (!ItemStack.isEmpty(stock.getItemStack(target))) {
                notice = tr("Retire o molde atual antes de trocar.", "Remove the current target first.");
            } else if (from.container() == stock || Transmutation.burnValue(item, plugin.emcValues()) <= 0) {
                notice = tr("Escolha um item com EMC do seu inventário.", "Choose an item with EMC from your inventory.");
            } else if (machine.hasInput(stock, item.getItemId())) {
                notice = tr("Retire esse item do condensador antes de usá-lo como molde.",
                    "Take this item out of the condenser before using it as the target.");
            } else {
                machine.moveMold(from.container(), source, stock, target);
            }
        } else if (from == sections.get(MOLD)) {
            if (to.container() != stock && to.container().canAddItemStackToSlot(target, stock.getItemStack(source), true, true)) {
                machine.moveMold(stock, source, to.container(), target);
            }
        } else if (from.container() != stock && to.container() != stock) {
            InventoryUtils.moveItem(ref, from.nativeId(), slot, quantity, to.nativeId(), destination, store);
        } else if (to != sections.get(OUTPUT)) {
            if (to.container() == stock && !canDeposit(from.container().getItemStack(source))) return;
            from.container().moveItemStackFromSlotToSlot(source, quantity, to.container(), target);
        }
    }

    private void quickStack(InventoryGestures.Section from, int slot, int quantity) {
        short source = from.physicalSlot(slot);
        if (from == sections.get(MOLD)) {
            machine.moveMold(stock, source, playerInventory, null);
        } else if (from.container() == stock) {
            stock.moveItemStackFromSlot(source, quantity, playerInventory);
        } else if (canDeposit(from.container().getItemStack(source))) {
            from.container().moveItemStackFromSlot(source, quantity, stock);
        }
    }

    private boolean canDeposit(ItemStack item) {
        if (machine.acceptsFuel(stock, item, plugin.emcValues())) return true;
        notice = Transmutation.burnValue(item, plugin.emcValues()) <= 0
            ? tr("O condensador aceita apenas itens com EMC.", "The condenser only accepts items with EMC.")
            : tr("O item do molde só entra pela geração.", "Only generated copies of the target may enter.");
        return false;
    }

    static boolean handleSmartMove(PlayerRef player, int nativeSection, int index) {
        var page = OPEN.get(player.getUuid());
        if (page == null) return false;
        for (var entry : page.sections.entrySet()) {
            if (entry.getValue().nativeId() != nativeSection) continue;
            var slot = new InventoryGestures.Slot(entry.getKey(), index);
            page.world.execute(() -> page.handleNativeQuick(slot));
            return true;
        }
        return false;
    }

    private void handleNativeQuick(InventoryGestures.Slot slot) {
        var ref = playerRef.getReference();
        if (!active || ref == null || !ref.isValid()) return;
        if (!valid(ref, ref.getStore())) {
            close();
            return;
        }
        hovered = null;
        notice = "";
        showResult(gestures.quick(slot, null));
        refresh();
    }

    private boolean valid(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!block.isValid() || !ref.isValid() || store.getArchetype(ref).contains(PreventInventoryAccess.getComponentType())
            || !EquivalentExchangePlugin.canAccess(ref, store, world, target,
                machine.advanced() ? EnergyCondenser.TIER_TWO : EnergyCondenser.TIER_ONE)) return false;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return false;
        var manager = player.getWindowManager();
        return windows.values().stream().allMatch(window -> manager.getWindow(window.getId()) == window);
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

    private void refresh() {
        var ui = new UICommandBuilder();
        var events = new UIEventBuilder();
        Integer reset = gestures.takeResetGrid();
        if (reset != null) mount(ui, events, reset);
        render(ui);
        sendUpdate(ui, events, false);
    }

    private void render(UICommandBuilder ui) {
        gestures.capture();
        for (int key : sections.keySet()) {
            var items = gestures.displayed(key);
            if (Arrays.equals(items, mounted.get(key))) continue;
            var slots = new ItemGridSlot[items.length];
            for (int i = 0; i < items.length; i++) {
                var slot = new ItemGridSlot();
                if (!ItemStack.isEmpty(items[i])) slot.setItemStack(items[i].withMetadata((BsonDocument) null));
                slot.setActivatable(true);
                slots[i] = slot;
            }
            ui.set(grids.get(key).selector() + ".Slots", slots);
            mounted.put(key, items.clone());
        }
        var mold = stock.getItemStack(machine.moldSlot());
        long goal = ItemStack.isEmpty(mold) ? 0 : plugin.emcValues().get(mold.getItemId());
        long emc = machine.emc();
        int height = goal > 0 && emc > 0 ? (int) Math.clamp(Math.min(emc, goal) * 108 / goal, 1, 108) : 0;
        ui.set("#EnergyFill.Visible", height > 0);
        if (height > 0) {
            var fill = new Anchor();
            fill.setLeft(Value.of(2));
            fill.setRight(Value.of(2));
            fill.setBottom(Value.of(2));
            fill.setHeight(Value.of(height));
            ui.setObject("#EnergyFill.Anchor", fill);
        }
        ui.set("#Energy.Text", numbers.format(emc) + (goal > 0 ? " / " + numbers.format(goal) : "") + " EMC");
        ui.set("#Notice.Text", notice);
        renderTooltip(ui);
    }

    private void renderTooltip(UICommandBuilder ui) {
        ItemStack item = null;
        int x = 0, y = 0, size = 40;
        if (hovered != null && !gestures.holding()) {
            var items = gestures.displayed(hovered.grid());
            int index = hovered.index();
            if (index >= 0 && index < items.length) {
                var grid = grids.get(hovered.grid());
                item = items[index];
                size = grid.size;
                x = grid.x + index % grid.columns * 44;
                y = grid.y + index / grid.columns * 44;
            }
        }
        long emc = Transmutation.burnValue(item, plugin.emcValues());
        tooltip.render(ui, item, language, x, y, size, 1040, 674,
            emc > 0 ? numbers.format(emc) + " EMC " + tr("cada", "each") : tr("Sem EMC", "No EMC"));
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        active = false;
        OPEN.remove(playerRef.getUuid(), this);
        registrations.forEach(EventRegistration::unregister);
        registrations.clear();
        if (!ref.isValid()) return;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;
        var manager = player.getWindowManager();
        for (var window : windows.values()) {
            if (manager.getWindow(window.getId()) == window) manager.closeWindow(ref, window.getId(), store);
        }
    }

    private String tr(String pt, String en) { return portuguese ? pt : en; }

    private record Grid(String host, String element, String template, int columns, int x, int y, int size) {
        String selector() { return host + " " + element; }
    }
}
