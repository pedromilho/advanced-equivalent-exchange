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
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

public final class EquivalentExchangePlugin extends JavaPlugin {
    static final String TABLE = "AEE_Transmutation_Table";
    private ComponentType<EntityStore, AlchemyKnowledge> knowledgeType;
    private ComponentType<ChunkStore, EnergyCondenser> condenserType;
    private EmcValues emcValues = EmcValues.EMPTY;
    private PacketFilter tooltipPackets;

    public EquivalentExchangePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
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
        var file = getDataDirectory().resolve("emc.properties");
        try {
            var basePrices = new Properties();
            try (var defaults = getClass().getResourceAsStream("/aee-emc.properties")) {
                basePrices.load(defaults);
            }
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                try (var defaults = getClass().getResourceAsStream("/aee-emc.properties")) {
                    Files.copy(defaults, file);
                }
            }
            var settings = new Properties(basePrices);
            try (var reader = Files.newBufferedReader(file)) {
                settings.load(reader);
            }
            emcValues = EmcValues.load(settings);
            var tooltips = new EmcTooltips(emcValues);
            tooltipPackets = PacketAdapters.registerOutbound((PacketWatcher) (handler, packet) -> tooltips.decorate(packet));
            getLogger().atInfo().log("Loaded %d transmutable item types.", emcValues.size());
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Cannot read EMC configuration at " + file, error);
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
        return emcValues;
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
