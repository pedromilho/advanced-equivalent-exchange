package br.pedro.aee;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.SmartMoveType;
import com.hypixel.hytale.protocol.packets.inventory.SmartMoveItemStack;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;

public final class EquivalentExchangePlugin extends JavaPlugin {
    static final String TABLE = "AEE_Transmutation_Table";
    private ComponentType<EntityStore, AlchemyKnowledge> knowledgeType;
    private ComponentType<ChunkStore, EnergyCondenser> condenserType;
    private volatile EmcConfiguration configuration;
    private PacketFilter tooltipPackets;

    public EquivalentExchangePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        PermissionsModule.registerPermission(EmcPermissions.MODIFY);
        PermissionsModule.registerPermission(EmcPermissions.READONLY);
        PermissionsModule.registerPermission(EmcPermissions.MANAGE);
        var permissions = new EmcPermissions(getDataDirectory().resolve("commands.properties"),
            warning -> getLogger().atWarning().log(warning));
        getCommandRegistry().registerCommand(new EmcEditorCommand(this, permissions));
        knowledgeType = getEntityStoreRegistry()
            .registerComponent(AlchemyKnowledge.class, "AEE_AlchemyKnowledge", AlchemyKnowledge.CODEC);
        getCodecRegistry(Interaction.CODEC).register("AEE_OpenAlchemicalChest",
            OpenAlchemicalChest.class, OpenAlchemicalChest.codec(this));
        getCodecRegistry(Interaction.CODEC).register("AEE_OpenTransmutationTable",
            OpenTransmutationTable.class, OpenTransmutationTable.codec(this));
        var chunks = getChunkStoreRegistry();
        condenserType = chunks.registerComponent(EnergyCondenser.class, "AEE_EnergyCondenser", EnergyCondenser.CODEC);
        chunks.registerSystem(new EnergyCondenser.Ticking(this, condenserType));
        getCodecRegistry(Interaction.CODEC).register("AEE_OpenEnergyCondenser",
            OpenEnergyCondenser.class, OpenEnergyCondenser.codec(this));
        // Single-player connections dispatch directly to the game handler, bypassing PacketAdapters.
        ServerManager.get().registerSubPacketHandlers(handler -> () -> {
            if (!(handler instanceof GamePacketHandler game)) return;
            var inventory = game.getSubPacketHandler(InventoryPacketHandler.class);
            if (inventory == null) return;
            game.registerHandler(SmartMoveItemStack.PACKET_ID, packet -> {
                var move = (SmartMoveItemStack) packet;
                var player = game.getPlayerRef();
                if (player != null && move.moveType == SmartMoveType.PutInHotbarOrWindow
                    && (TransmutationPage.handleSmartMove(player, move.fromSectionId, move.fromSlotId)
                        || CondenserPage.handleSmartMove(player, move.fromSectionId, move.fromSlotId))) return;
                if (player != null) inventory.handle(move);
            });
        });
    }

    @Override
    protected void start() {
        try {
            configuration = new EmcConfiguration(getDataDirectory());
            var tooltips = new EmcTooltips(this::emcValues);
            tooltipPackets = PacketAdapters.registerOutbound((PacketWatcher) (handler, packet) -> tooltips.decorate(packet));
            getLogger().atInfo().log("Loaded %d transmutable item types.", emcValues().size());
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Cannot read EMC configuration at " + getDataDirectory(), error);
        }
    }

    @Override
    protected void shutdown() {
        if (tooltipPackets != null) PacketAdapters.deregisterOutbound(tooltipPackets);
    }

    ComponentType<EntityStore, AlchemyKnowledge> knowledgeType() {
        return knowledgeType;
    }

    EmcValues emcValues() {
        var current = configuration;
        return current == null ? EmcValues.EMPTY : current.snapshot().values();
    }

    Map<String, Long> emcOverrides() {
        return configuration.snapshot().overrides();
    }

    boolean saveEmcOverrides(Map<String, OptionalLong> edits) throws IOException {
        if (!configuration.save(edits)) return false;
        var i18n = I18nModule.get();
        for (var player : Universe.get().getPlayers()) {
            i18n.queueTranslations(player.getPacketHandler(), i18n.resolveLanguage(player.getLanguage()));
        }
        TransmutationPage.refreshPrices();
        CondenserPage.refreshPrices();
        return true;
    }

    ComponentType<ChunkStore, EnergyCondenser> condenserType() {
        return condenserType;
    }

    static boolean canAccess(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor,
                             World world, BlockPosition target) {
        return canAccess(ref, accessor, world, target, TABLE);
    }

    static boolean canAccess(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor,
                             World world, BlockPosition target, String blockId) {
        if (target == null || !ref.isValid() || ref.getStore().getExternalData().getWorld() != world) return false;
        var transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition().distanceSquared(
            target.x + 0.5, target.y + 0.5, target.z + 0.5) > 64) return false;

        var chunks = world.getChunkStore();
        var sectionRef = chunks.getChunkSectionReferenceAtBlock(target.x, target.y, target.z);
        if (sectionRef == null || !sectionRef.isValid()) return false;
        var section = chunks.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return false;
        var block = BlockType.getAssetMap().getAsset(section.get(target.x, target.y, target.z));
        return block != null && blockId.equals(block.getId());
    }
}
