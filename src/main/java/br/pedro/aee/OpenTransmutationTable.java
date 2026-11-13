package br.pedro.aee;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class OpenTransmutationTable extends SimpleInstantInteraction {
    static BuilderCodec<OpenTransmutationTable> codec(AdvancedEquivalencyPlugin plugin) {
        return BuilderCodec.builder(OpenTransmutationTable.class, () -> new OpenTransmutationTable(plugin),
            SimpleInstantInteraction.CODEC).build();
    }

    private final AdvancedEquivalencyPlugin plugin;

    private OpenTransmutationTable(AdvancedEquivalencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldown) {
        var ref = context.getEntity();
        if (!ref.isValid()) return;
        var store = ref.getStore();
        var world = store.getExternalData().getWorld();
        var target = context.getTargetBlock();
        world.execute(() -> {
            if (!AdvancedEquivalencyPlugin.canAccess(ref, store, world, target)) return;
            var player = store.getComponent(ref, Player.getComponentType());
            var playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) return;
            var page = new TransmutationPage(playerRef, plugin, world, target);
            player.getPageManager().openCustomPageWithWindows(ref, store, page, page.burnWindow(), page.tradeWindow());
        });
    }
}
