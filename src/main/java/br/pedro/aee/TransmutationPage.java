package br.pedro.aee;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.modules.entity.component.PreventInventoryAccess;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;

final class TransmutationPage extends InteractiveCustomUIPage<InventoryInput> {
    private static final BuilderCodec<InventoryInput> CODEC = BuilderCodec.builder(
        InventoryInput.class, () -> InventoryInput.parse("{}")).build();
    private static final String UI = "AdvancedEquivalentExchange/";
    private static final int TRADE_ROWS = 4;
    private static final int VISIBLE_COLUMNS = 14;
    private static final int TRADE_PITCH = 45;
    private static final int TRADE_VIEWPORT = 634;
    private static final int CENTERED_STORAGE_LEFT = 276;
    private static final long QUICK_PAIR_NANOS = 250_000_000L;
    private static final Map<UUID, TransmutationPage> OPEN = new ConcurrentHashMap<>();
    private static final int BURN = InventoryGestures.BURN;
    private static final int TRADE = InventoryGestures.TRADE;
    private static final Map<Integer, Grid> GRIDS = Map.of(
        BURN, new Grid("#BurnHost", "BurnGrid.ui", 1, 125, 184, 1, 60),
        TRADE, new Grid("#TradeHost", "TradeGrid.ui", VISIBLE_COLUMNS, 318, 138, TRADE_ROWS, 42),
        InventoryGestures.STORAGE, new Grid("#InventoryHost", "ChestPlayerGrid.ui", 9, 64, 443, 4, 40),
        InventoryGestures.HOTBAR, new Grid("#HotbarHost", "ChestPlayerGrid.ui", 9, 64, 624, 1, 40),
        InventoryGestures.BACKPACK, new Grid("#BackpackHost", "ChestPlayerGrid.ui", 9, 520, 443, 4, 40)
    );

    private final EquivalentExchangePlugin plugin;
    private final World world;
    private final BlockPosition target;
    private final String session = UUID.randomUUID().toString();
    private final String language;
    private final NumberFormat numbers;
    private final boolean portuguese;
    private final AlchemyKnowledge empty = new AlchemyKnowledge();
    // The native windows supply IDs for drag events. Actual previews are never stored in those windows.
    private final ContainerWindow burnWindow = emptyWindow((short) 1);
    private final ContainerWindow tradeWindow;
    private final SimpleItemContainer burnView = new SimpleItemContainer((short) 1);
    private final SimpleItemContainer tradeView;
    private final Map<Integer, InventoryGestures.Section> sections = new LinkedHashMap<>();
    private final Map<Integer, ItemStack[]> mounted = new HashMap<>();
    private final Map<Integer, Integer> versions = new HashMap<>();
    private final Map<InventoryGestures.Slot, QuickClick> recentQuick = new HashMap<>();
    private final ArrayList<EventRegistration<?, ?>> registrations = new ArrayList<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final StorageTooltip tooltip = new StorageTooltip();

    private InventoryGestures gestures;
    private InventoryGestures.Slot hovered;
    private ItemContainer inventory;
    private int tradeColumns = VISIBLE_COLUMNS;
    private int shownTradeSlots;
    private long shownReturnCost;
    private String targetItem;
    private String search = "";
    private long priceLimit;
    private boolean affordable = true;
    private String notice = "";
    private boolean active;

    TransmutationPage(PlayerRef player, EquivalentExchangePlugin plugin, World world, BlockPosition target) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, CODEC);
        this.plugin = plugin;
        this.world = world;
        this.target = target;
        short tradeCapacity = (short) (TRADE_ROWS * catalogColumns(plugin.emcValues().size()));
        tradeWindow = emptyWindow(tradeCapacity);
        tradeView = new SimpleItemContainer(tradeCapacity);
        language = player.getLanguage() == null ? "en-US" : player.getLanguage().replace('_', '-');
        Locale locale = Locale.forLanguageTag(language);
        portuguese = "pt".equals(locale.getLanguage());
        numbers = NumberFormat.getIntegerInstance(locale);
    }

    ContainerWindow burnWindow() { return burnWindow; }
    ContainerWindow tradeWindow() { return tradeWindow; }

    static ContainerWindow emptyWindow(short size) {
        var container = new SimpleItemContainer(size);
        container.setGlobalFilter(FilterType.DENY_ALL);
        return new ContainerWindow(container);
    }

    static int catalogColumns(long entries) {
        return (int) Math.clamp((entries + TRADE_ROWS - 1) / TRADE_ROWS,
            VISIBLE_COLUMNS, Short.MAX_VALUE / TRADE_ROWS);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        inventory = InventoryComponent.getCombined(store, ref, InventoryComponent.STORAGE_HOTBAR_BACKPACK);
        addInventoryGrid(ref, store, InventoryGestures.STORAGE, InventoryComponent.STORAGE_SECTION_ID);
        addInventoryGrid(ref, store, InventoryGestures.HOTBAR, InventoryComponent.HOTBAR_SECTION_ID);
        addInventoryGrid(ref, store, InventoryGestures.BACKPACK, InventoryComponent.BACKPACK_SECTION_ID);
        sections.put(BURN, new InventoryGestures.Section(burnWindow.getId(), burnView, true));
        sections.put(TRADE, new InventoryGestures.Section(tradeWindow.getId(), tradeView, true));
        gestures = new InventoryGestures(sections, new InventoryGestures.Moves() {
            @Override
            public void move(InventoryGestures.Section from, int slot, int quantity,
                             InventoryGestures.Section to, int destination) {
                if (from.virtual() && !to.virtual()) {
                    withdraw(ref, store, from, slot, quantity, to.container(), (short) destination);
                } else if (!from.virtual() && to.container() == burnView) {
                    burn(ref, store, from.container(), (short) slot, quantity);
                } else if (!from.virtual() && !to.virtual()) {
                    InventoryUtils.moveItem(ref, from.nativeId(), slot, quantity, to.nativeId(), destination, store);
                }
            }

            @Override
            public void quick(InventoryGestures.Section from, int slot, int quantity) {
                if (from.virtual()) withdraw(ref, store, from, slot, quantity, inventory, null);
                else burn(ref, store, from.container(), (short) slot, quantity);
            }
        });

        ui.append(UI + "Transmutation.ui");
        ui.append("#PageTooltipHost", UI + "StorageTooltip.ui");
        ui.set("#Heading.Text", tr("Mesa de Transmutação", "Transmutation Table"));
        ui.set("#BurnTitle.Text", tr("QUEIMA", "BURN"));
        ui.set("#TradeTitle.Text", tr("TROCA", "TRANSMUTE"));
        ui.set("#InventoryTitle.Text", tr("Inventário", "Inventory"));
        ui.set("#HotbarTitle.Text", tr("Barra rápida", "Hotbar"));
        ui.set("#BackpackTitle.Text", tr("Mochila", "Backpack"));
        boolean backpack = sections.containsKey(InventoryGestures.BACKPACK);
        ui.set("#BackpackTitle.Visible", backpack);
        if (!backpack) {
            var anchor = new Anchor();
            anchor.setLeft(Value.of(CENTERED_STORAGE_LEFT));
            anchor.setTop(Value.of(0));
            anchor.setWidth(Value.of(480));
            anchor.setHeight(Value.of(268));
            ui.setObject("#PlayerStorage.Anchor", anchor);
        }
        ui.set("#Search.PlaceholderText", tr("Buscar itens aprendidos…", "Search learned items…"));
        ui.set("#Search.Value", search);
        ui.set("#Forget.Text", tr("Esquecer", "Forget"));
        for (int grid : sections.keySet()) mount(ui, events, grid);
        for (String name : new String[] {"Affordable", "Limit", "Forget"}) {
            bind(events, "#" + name, CustomUIEventBindingType.Activating, EventData.of("Action", name));
        }
        bind(events, "#Search", CustomUIEventBindingType.ValueChanged,
            EventData.of("Action", "search").append("@Search", "#Search.Value"));
        bind(events, "#TableRoot", CustomUIEventBindingType.Dismissing, EventData.of("Action", "Close"));
        render(ui, knowledge(ref, store));

        if (!active) {
            active = true;
            OPEN.put(playerRef.getUuid(), this);
            for (var section : sections.values()) {
                if (!section.virtual()) {
                    registrations.add(section.container().registerChangeEvent(event -> scheduleRefresh(ref, store)));
                }
            }
            for (var window : new ContainerWindow[] {burnWindow, tradeWindow}) {
                registrations.add(window.registerCloseEvent(event -> world.execute(() -> {
                    if (active) close();
                })));
            }
        }
    }

    private void addInventoryGrid(Ref<EntityStore> ref, Store<EntityStore> store, int grid, int nativeId) {
        var container = InventoryUtils.getSectionById(ref, nativeId, store);
        if (container != null && container.getCapacity() > 0) {
            sections.put(grid, new InventoryGestures.Section(nativeId, container));
        }
    }

    private void mount(UICommandBuilder ui, UIEventBuilder events, int key) {
        var grid = GRIDS.get(key);
        int version = versions.merge(key, 1, Integer::sum);
        String selector = grid.selector();
        ui.clear(grid.host);
        ui.append(grid.host, UI + grid.template);
        ui.set(selector + ".InventorySectionId", sections.get(key).nativeId());
        InventoryInput.bindGrid(events, selector, key, version, session);
        mounted.remove(key);
    }

    private void bind(UIEventBuilder events, String selector, CustomUIEventBindingType type, EventData data) {
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
        if ("Close".equals(input.action()) || !valid(ref, store)) {
            close();
            return;
        }
        var knowledge = knowledge(ref, store);
        if (input.grid() != null) {
            if (handleGrid(input)) return;
        } else {
            gestures.cancel();
            hovered = null;
            notice = "";
            switch (input.action()) {
                case "Affordable" -> affordable = !affordable;
                case "Limit" -> priceLimit = priceLimit == 0 ? limitSource(knowledge) : 0;
                case "search" -> {
                    String query = input.search();
                    search = query.substring(0, Math.min(100, query.length())).strip().toLowerCase(Locale.ROOT);
                }
                case "Forget" -> {
                    if (targetItem != null && knowledge.items.remove(targetItem)) markSave(ref, store);
                    targetItem = null;
                }
                default -> { return; }
            }
        }
        refresh(knowledge(ref, store));
    }

    private boolean handleGrid(InventoryInput input) {
        var key = input.grid();
        var index = input.slot();
        var slot = index == null ? null : new InventoryGestures.Slot(key, index);
        if ("enter".equals(input.action())) hovered = slot;
        else if (!"exit".equals(input.action()) || slot != null && slot.equals(hovered)) hovered = null;

        boolean pick = "pick".equals(input.action());
        if (pick && slot != null) selectTradeItem(slot);
        boolean pureHover = "enter".equals(input.action()) || "exit".equals(input.action());
        if (!pureHover) notice = "";
        var result = pick && slot != null && input.shift() && !input.right()
            ? quickClick(slot, input.itemId(), false)
            : gestures.handle(input);
        showResult(result);
        if (pureHover && result == InventoryGestures.Result.IGNORED) {
            var ui = new UICommandBuilder();
            renderTooltip(ui);
            flush(ui, null);
            return true;
        }
        return false;
    }

    private void showResult(InventoryGestures.Result result) {
        if (notice.isEmpty()) {
            if (result == InventoryGestures.Result.STALE) {
                notice = tr("O inventário mudou. Pegue a pilha de novo.", "Inventory changed. Pick up the stack again.");
            } else if (result == InventoryGestures.Result.REJECTED) {
                notice = tr("Não foi possível mover a pilha.", "That stack could not be moved.");
            }
        }
    }

    private void selectTradeItem(InventoryGestures.Slot slot) {
        if (slot.grid() != TRADE || slot.index() < 0 || slot.index() >= shownTradeSlots) return;
        var item = gestures.displayed(TRADE)[slot.index()];
        if (!ItemStack.isEmpty(item)) targetItem = item.getItemId();
    }

    private InventoryGestures.Result quickClick(InventoryGestures.Slot slot, String expectedId, boolean nativePacket) {
        long now = System.nanoTime();
        recentQuick.values().removeIf(click -> now - click.at > QUICK_PAIR_NANOS);
        var paired = recentQuick.get(slot);
        if (paired != null && paired.nativePacket != nativePacket) {
            if (paired.count == 1) recentQuick.remove(slot);
            else recentQuick.put(slot, new QuickClick(paired.nativePacket, paired.at, paired.count - 1));
            return InventoryGestures.Result.IGNORED;
        }
        var result = gestures.quick(slot, expectedId);
        if (result != InventoryGestures.Result.IGNORED) {
            int count = paired == null ? 1 : paired.count + 1;
            recentQuick.put(slot, new QuickClick(nativePacket, now, count));
        }
        return result;
    }

    static boolean handleSmartMove(PlayerRef player, int nativeSection, int index) {
        var page = OPEN.get(player.getUuid());
        if (page == null) return false;
        for (var section : page.sections.entrySet()) {
            if (section.getValue().nativeId() != nativeSection) continue;
            var slot = new InventoryGestures.Slot(section.getKey(), index);
            page.world.execute(() -> page.handleNativeQuick(slot));
            return true;
        }
        return false;
    }

    private void handleNativeQuick(InventoryGestures.Slot slot) {
        var ref = playerRef.getReference();
        if (!active || ref == null || !ref.isValid()) return;
        var store = ref.getStore();
        if (store.getArchetype(ref).contains(PreventInventoryAccess.getComponentType())) return;
        if (!valid(ref, store)) {
            close();
            return;
        }
        selectTradeItem(slot);
        hovered = null;
        var result = quickClick(slot, null, true);
        if (result == InventoryGestures.Result.IGNORED) return;
        showResult(result);
        refresh(knowledge(ref, store));
    }

    private void burn(Ref<EntityStore> ref, Store<EntityStore> store, ItemContainer from, short slot, int quantity) {
        var stack = from.getItemStack(slot);
        var values = plugin.emcValues();
        if (Transmutation.burnValue(stack, values) <= 0) {
            notice = tr("Este item não pode ser queimado.", "This item cannot be burned.");
            return;
        }
        var knowledge = knowledge(ref, store);
        if (knowledge == empty) knowledge = store.addComponent(ref, plugin.knowledgeType());
        long credit = Transmutation.burn(knowledge, values, from, slot, stack, quantity);
        if (credit == 0) return;
        burnView.setItemStackForSlot((short) 0, knowledge.lastBurn);
        notice = "+" + numbers.format(credit) + " EMC";
        markSave(ref, store);
    }

    private void withdraw(Ref<EntityStore> ref, Store<EntityStore> store, InventoryGestures.Section from,
                          int slot, int quantity, ItemContainer into, Short destination) {
        var knowledge = knowledge(ref, store);
        if (from.container() == burnView) {
            int returned = Transmutation.recover(knowledge, into, destination, quantity);
            if (returned == 0) {
                notice = tr("Confira o saldo e o espaço para recuperar o item.",
                    "Check your EMC and inventory space to reclaim the item.");
                return;
            }
            burnView.setItemStackForSlot((short) 0, knowledge.lastBurn);
            notice = tr("Itens recuperados", "Items reclaimed") + "  ·  ×" + returned;
            markSave(ref, store);
            return;
        }
        var preview = tradeView.getItemStack((short) slot);
        if (ItemStack.isEmpty(preview)) return;
        int created = purchase(ref, store, knowledge, into, destination, preview.getItemId(), quantity);
        if (created > 0) {
            int left = preview.getQuantity() - created;
            tradeView.setItemStackForSlot((short) slot, left == 0 ? null : preview.withQuantity(left));
        }
    }

    private int purchase(Ref<EntityStore> ref, Store<EntityStore> store, AlchemyKnowledge knowledge,
                         ItemContainer into, Short destination, String item, int quantity) {
        var values = plugin.emcValues();
        int created = Transmutation.create(knowledge, values, into, destination, item, quantity);
        if (created == 0) {
            notice = tr("Confira o saldo e o espaço no inventário.", "Check your EMC and inventory space.");
        } else {
            notice = "−" + numbers.format(values.get(item) * created) + " EMC  ·  ×" + created;
            markSave(ref, store);
        }
        return created;
    }

    private void scheduleRefresh(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!refreshPending.compareAndSet(false, true)) return;
        world.execute(() -> {
            refreshPending.set(false);
            if (!active || !ref.isValid()) return;
            if (valid(ref, store)) refresh(knowledge(ref, store));
            else close();
        });
    }

    private boolean valid(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!EquivalentExchangePlugin.canAccess(ref, store, world, target)) return false;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return false;
        var manager = player.getWindowManager();
        return manager.getWindow(burnWindow.getId()) == burnWindow && manager.getWindow(tradeWindow.getId()) == tradeWindow;
    }

    private void refresh(AlchemyKnowledge knowledge) {
        var ui = new UICommandBuilder();
        var events = new UIEventBuilder();
        Integer reset = gestures.takeResetGrid();
        if (reset != null) mount(ui, events, reset);
        render(ui, knowledge);
        flush(ui, events);
    }

    private void render(UICommandBuilder ui, AlchemyKnowledge knowledge) {
        shownReturnCost = knowledge.returnCost;
        if (!gestures.holding()) populate(knowledge);
        gestures.capture();
        for (int key : sections.keySet()) {
            var items = gestures.displayed(key);
            if (key == TRADE) items = Arrays.copyOf(items, shownTradeSlots);
            if (Arrays.equals(items, mounted.get(key))) continue;
            var slots = new ItemGridSlot[items.length];
            for (int i = 0; i < items.length; i++) {
                var stack = items[i];
                var slot = new ItemGridSlot();
                if (!ItemStack.isEmpty(stack)) slot.setItemStack(stack.withMetadata((BsonDocument) null));
                slot.setActivatable(true);
                slots[i] = slot;
            }
            String selector = GRIDS.get(key).selector();
            if (key == TRADE) {
                ui.set(selector + ".SlotsPerRow", tradeColumns);
                ui.set(selector + ".ContentWidth", Math.max(TRADE_VIEWPORT, tradeColumns * TRADE_PITCH));
                ui.set("#TradeTrack.Visible", tradeColumns == VISIBLE_COLUMNS);
            }
            ui.set(selector + ".Slots", slots);
            mounted.put(key, items.clone());
        }

        long unit = targetItem == null ? 0 : plugin.emcValues().get(targetItem);
        boolean known = unit > 0 && knowledge.items.contains(targetItem);
        ui.set("#Balance.Text", numbers.format(knowledge.emc) + " EMC");
        ui.set("#Affordable.Text", affordable ? tr("Mostrar todos", "Show all") : tr("Só acessíveis", "Affordable only"));
        ui.set("#Limit.Text", priceLimit == 0 ? tr("Fixar teto", "Set EMC cap") : "≤ " + numbers.format(priceLimit) + " EMC");
        ui.set("#Limit.Disabled", priceLimit == 0 && limitSource(knowledge) <= 0);
        String selection = known ? itemName(targetItem) + "  ·  " + valueLabel(unit) : "";
        ui.set("#Selection.Text", notice.isEmpty() ? selection : notice);
        ui.set("#Forget.Disabled", !known);
        renderTooltip(ui);
    }

    private void populate(AlchemyKnowledge knowledge) {
        burnView.setItemStackForSlot((short) 0, knowledge.lastBurn);
        var values = plugin.emcValues();
        long cap = priceLimit == 0 ? Long.MAX_VALUE : priceLimit;
        long ceiling = affordable ? Math.min(cap, knowledge.emc) : cap;
        var entries = knowledge.items.stream()
            .filter(id -> values.get(id) > 0 && values.get(id) <= ceiling && Item.getAssetMap().getAsset(id) != null)
            .map(id -> new Entry(id, itemName(id), values.get(id)))
            .filter(e -> e.name.toLowerCase(Locale.ROOT).contains(search) || e.id.toLowerCase(Locale.ROOT).contains(search))
            .sorted(Comparator.comparingLong(Entry::emc).reversed().thenComparing(Entry::name).thenComparing(Entry::id))
            .toList();
        tradeColumns = catalogColumns(entries.size());
        int size = tradeColumns * TRADE_ROWS;
        int previousSize = shownTradeSlots;
        shownTradeSlots = size;
        for (short i = 0; i < Math.max(size, previousSize); i++) {
            ItemStack preview = null;
            int entryIndex = (i % tradeColumns) * TRADE_ROWS + i / tradeColumns;
            if (i < size && entryIndex < entries.size()) {
                var entry = entries.get(entryIndex);
                int max = Item.getAssetMap().getAsset(entry.id).getMaxStack();
                int quantity = (int) Math.clamp(knowledge.emc / entry.emc, 1, max);
                preview = new ItemStack(entry.id, quantity);
            }
            tradeView.setItemStackForSlot(i, preview);
        }
    }

    private void renderTooltip(UICommandBuilder ui) {
        ItemStack item = null;
        int x = 0, y = 0, size = 40;
        if (hovered != null && !gestures.holding()) {
            var items = gestures.displayed(hovered.grid());
            int index = hovered.index();
            if (index >= 0 && index < items.length) {
                var grid = GRIDS.get(hovered.grid());
                item = items[index];
                size = grid.size;
                int columns = hovered.grid() == TRADE ? tradeColumns : grid.columns;
                int pitch = hovered.grid() == TRADE ? TRADE_PITCH : size + 4;
                int column = index % columns;
                if (hovered.grid() == TRADE && tradeColumns > VISIBLE_COLUMNS) column = VISIBLE_COLUMNS / 2;
                int left = grid.x;
                if ((hovered.grid() == InventoryGestures.STORAGE || hovered.grid() == InventoryGestures.HOTBAR)
                    && !sections.containsKey(InventoryGestures.BACKPACK)) left = 16 + CENTERED_STORAGE_LEFT;
                x = left + column * pitch;
                y = grid.y + Math.min(grid.rows - 1, index / columns) * pitch;
            }
        }
        String extra = "";
        if (!ItemStack.isEmpty(item)) {
            long value = switch (hovered.grid()) {
                case BURN -> shownReturnCost;
                case TRADE -> plugin.emcValues().get(item.getItemId());
                default -> Transmutation.burnValue(item, plugin.emcValues());
            };
            extra = value > 0 ? valueLabel(value) : tr("Sem EMC", "No EMC");
        }
        tooltip.render(ui, item, language, x, y, size, 980, 714, extra);
    }

    private void flush(UICommandBuilder ui, UIEventBuilder events) {
        if (ui.getCommands().length > 0 || (events != null && events.getEvents().length > 0)) {
            sendUpdate(ui, events, false);
        }
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
        for (var window : new ContainerWindow[] {burnWindow, tradeWindow}) {
            if (manager.getWindow(window.getId()) == window) manager.closeWindow(ref, window.getId(), store);
        }
    }

    private AlchemyKnowledge knowledge(Ref<EntityStore> ref, Store<EntityStore> store) {
        var knowledge = store.getComponent(ref, plugin.knowledgeType());
        return knowledge == null ? empty : knowledge;
    }

    private void markSave(Ref<EntityStore> ref, Store<EntityStore> store) {
        var player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.markNeedsSave();
    }

    private long limitSource(AlchemyKnowledge knowledge) {
        return targetItem == null ? knowledge.returnCost : plugin.emcValues().get(targetItem);
    }

    private String itemName(String id) {
        Item item = Item.getAssetMap().getAsset(id);
        if (item == null || item.getTranslationKey() == null) return id;
        String name = I18nModule.get().getMessage(language, item.getTranslationKey());
        if (name == null || name.isBlank()) name = I18nModule.get().getMessage("en-US", item.getTranslationKey());
        return name == null || name.isBlank() ? id : name;
    }

    private String valueLabel(long value) {
        return numbers.format(value) + " EMC " + tr("cada", "each");
    }

    private String tr(String pt, String en) { return portuguese ? pt : en; }

    private record Grid(String host, String template, int columns, int x, int y, int rows, int size) {
        String selector() {
            return host + (template.equals("ChestPlayerGrid.ui") ? " #PlayerGrid" : " #TableGrid");
        }
    }

    private record Entry(String id, String name, long emc) {}
    private record QuickClick(boolean nativePacket, long at, int count) {}
}
