package br.pedro.aee;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

final class EnergyCondenser implements Component<ChunkStore> {
    static final String TIER_ONE = "AEE_Energy_Condenser";
    static final String TIER_TWO = "AEE_Energy_Condenser_MK2";
    static final BuilderCodec<EnergyCondenser> CODEC = BuilderCodec
        .builder(EnergyCondenser.class, EnergyCondenser::new)
        .append(new KeyedCodec<>("Tier", Codec.INTEGER),
            (machine, value) -> machine.tier = value == 2 ? 2 : 1, machine -> machine.tier).add()
        .append(new KeyedCodec<>("Emc", Codec.LONG),
            (machine, value) -> machine.emc = Math.max(0, value), machine -> machine.emc).add()
        .build();

    private int tier = 1;
    private long emc;
    private double cycleProgress;
    private SimpleItemContainer configured;
    private Thread moldEditor;

    boolean advanced() { return tier == 2; }
    int inputs() { return advanced() ? 40 : 65; }
    int outputStart() { return advanced() ? inputs() : 0; }
    int outputCount() { return advanced() ? 40 : inputs(); }
    short moldSlot() { return (short) (outputStart() + outputCount()); }
    long emc() { return emc; }

    @Override
    public EnergyCondenser clone() {
        var copy = new EnergyCondenser();
        copy.tier = tier;
        copy.emc = emc;
        return copy;
    }

    void configure(SimpleItemContainer stock, EquivalentExchangePlugin plugin) {
        if (configured == stock) return;
        for (short i = 0; i < inputs(); i++) {
            stock.setSlotFilter(FilterActionType.ADD, i,
                (action, container, slot, stack) -> acceptsFuel(stock, stack, plugin.emcValues()));
        }
        if (advanced()) {
            for (short i = (short) outputStart(); i < moldSlot(); i++) {
                stock.setSlotFilter(FilterActionType.ADD, i, SlotFilter.DENY);
            }
        }
        stock.setSlotFilter(FilterActionType.ADD, moldSlot(),
            (action, container, slot, stack) -> moldEditor == Thread.currentThread()
                && Transmutation.burnValue(stack, plugin.emcValues()) > 0 && !hasInput(stock, stack.getItemId()));
        stock.setSlotFilter(FilterActionType.REMOVE, moldSlot(),
            (action, container, slot, stack) -> moldEditor == Thread.currentThread());
        configured = stock;
    }

    boolean acceptsFuel(ItemContainer stock, ItemStack stack, EmcValues values) {
        var mold = stock.getItemStack(moldSlot());
        return Transmutation.burnValue(stack, values) > 0
            && (ItemStack.isEmpty(mold) || !mold.getItemId().equals(stack.getItemId()));
    }

    boolean hasInput(ItemContainer stock, String id) {
        for (short slot = 0; slot < inputs(); slot++) {
            var stack = stock.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && id.equals(stack.getItemId())) return true;
        }
        return false;
    }

    void moveMold(ItemContainer from, short source, ItemContainer to, Short destination) {
        moldEditor = Thread.currentThread();
        try {
            if (destination == null) from.moveItemStackFromSlot(source, 1, to);
            else from.moveItemStackFromSlotToSlot(source, 1, to, destination);
        } finally {
            moldEditor = null;
        }
    }

    boolean process(SimpleItemContainer stock, EmcValues values) {
        var mold = stock.getItemStack(moldSlot());
        if (ItemStack.isEmpty(mold)) return false;
        long price = values.get(mold.getItemId());
        if (price <= 0) return false;
        var product = new ItemStack(mold.getItemId());
        short output = findOutputSlot(stock, product);
        // A shared inventory may free a slot as its last piece of fuel is consumed.
        if (output < 0 && (advanced() || emc >= price)) return false;
        long before = emc;
        if (emc < price) emc += consumeOne(stock, values);
        boolean consumed = emc != before;
        if (emc < price) return consumed;
        if (consumed && !advanced()) output = findOutputSlot(stock, product);
        if (output < 0) return consumed;

        // Only production bypasses the input filter, and only in output slots.
        var result = stock.addItemStackToSlot(output, product, true, false);
        if (!result.succeeded() || !ItemStack.isEmpty(result.getRemainder())) return consumed;
        emc -= price;
        return true;
    }

    private long consumeOne(SimpleItemContainer stock, EmcValues values) {
        for (short slot = 0; slot < inputs(); slot++) {
            var stack = stock.getItemStack(slot);
            if (!acceptsFuel(stock, stack, values)) continue;
            long unit = Transmutation.burnValue(stack, values);
            var removal = stock.removeItemStackFromSlot(slot, stack, 1);
            if (removal.succeeded() && ItemStack.isEmpty(removal.getRemainder())) return unit;
        }
        return 0;
    }

    private short findOutputSlot(ItemContainer stock, ItemStack product) {
        short empty = -1;
        int max = product.getItem().getMaxStack();
        for (short slot = (short) outputStart(); slot < moldSlot(); slot++) {
            var stack = stock.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                if (empty < 0) empty = slot;
            } else if (stack.getQuantity() < max && stack.isStackableWith(product)) {
                return slot;
            }
        }
        return empty;
    }

    static final class Ticking extends EntityTickingSystem<ChunkStore> {
        private final EquivalentExchangePlugin plugin;
        private final ComponentType<ChunkStore, EnergyCondenser> type;

        Ticking(EquivalentExchangePlugin plugin, ComponentType<ChunkStore, EnergyCondenser> type) {
            this.plugin = plugin;
            this.type = type;
        }

        @Override
        public Query<ChunkStore> getQuery() {
            return Query.and(type, ItemContainerBlock.getComponentType(), BlockStateInfo.getComponentType());
        }

        @Override
        public void tick(float dt, int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
                         CommandBuffer<ChunkStore> commands) {
            var machine = chunk.getComponent(index, type);
            var stock = chunk.getComponent(index, ItemContainerBlock.getComponentType()).getItemContainer();
            if (stock.getCapacity() != machine.moldSlot() + 1) return;
            machine.configure(stock, plugin);
            int cyclesPerSecond = machine.advanced() ? 48 : 16;
            machine.cycleProgress += Math.min(dt, 1.0) * cyclesPerSecond;
            int cycles = (int) machine.cycleProgress;
            machine.cycleProgress -= cycles;

            // High rates can require several independent conversions in one server tick.
            boolean changed = false;
            for (int cycle = 0; cycle < cycles; cycle++) {
                if (!machine.process(stock, plugin.emcValues())) break;
                changed = true;
            }
            if (changed) {
                chunk.getComponent(index, BlockStateInfo.getComponentType()).markNeedsSaving(commands);
            }
        }
    }
}
