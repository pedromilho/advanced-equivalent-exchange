package br.pedro.aee;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.PreventInventoryAccess;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class OpenEnergyCondenser extends SimpleInstantInteraction {
    private final AdvancedEquivalencyPlugin plugin;

    private OpenEnergyCondenser(AdvancedEquivalencyPlugin plugin) {
        this.plugin = plugin;
    }

    static BuilderCodec<OpenEnergyCondenser> codec(AdvancedEquivalencyPlugin plugin) {
        return BuilderCodec.builder(OpenEnergyCondenser.class, () -> new OpenEnergyCondenser(plugin),
            SimpleInstantInteraction.CODEC).build();
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldown) {
        var ref = context.getEntity();
        var target = context.getTargetBlock();
        if (target == null || !ref.isValid()) return;
        var store = ref.getStore();
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!ref.isValid() || store.getArchetype(ref).contains(PreventInventoryAccess.getComponentType())) return;
            var player = store.getComponent(ref, Player.getComponentType());
            var playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) return;
            var block = BlockModule.getBlockEntity(world, target.x, target.y, target.z);
            if (block == null || !block.isValid()) return;
            var machine = block.getStore().getComponent(block, plugin.condenserType());
            var container = block.getStore().getComponent(block, ItemContainerBlock.getComponentType());
            if (machine == null || container == null) return;
            var id = machine.advanced() ? EnergyCondenser.TIER_TWO : EnergyCondenser.TIER_ONE;
            if (!AdvancedEquivalencyPlugin.canAccess(ref, store, world, target, id)) return;
            var stock = container.getItemContainer();
            if (stock.getCapacity() != machine.moldSlot() + 1) return;
            machine.configure(stock, plugin);
            var page = new CondenserPage(playerRef, plugin, world, target, block, machine, stock);
            player.getPageManager().openCustomPageWithWindows(ref, store, page, page.windows());
        });
    }
}
