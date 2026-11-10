package br.pedro.aee;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class OpenAlchemicalChest extends SimpleInstantInteraction {
    static final String CHEST = "AEE_Alchemical_Chest";
    private final EquivalentExchangePlugin plugin;

    private OpenAlchemicalChest(EquivalentExchangePlugin plugin) {
        this.plugin = plugin;
    }

    static BuilderCodec<OpenAlchemicalChest> codec(EquivalentExchangePlugin plugin) {
        return BuilderCodec.builder(OpenAlchemicalChest.class, () -> new OpenAlchemicalChest(plugin),
            SimpleInstantInteraction.CODEC).build();
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldown) {
        var ref = context.getEntity();
        var position = context.getTargetBlock();
        if (position == null || !ref.isValid()) return;
        var world = ref.getStore().getExternalData().getWorld();
        world.execute(() -> open(ref, world, position));
    }

    private void open(Ref<EntityStore> ref, World world, BlockPosition position) {
        if (!ref.isValid() || ref.getStore().getExternalData().getWorld() != world) return;
        var store = ref.getStore();
        var player = store.getComponent(ref, Player.getComponentType());
        var playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) return;

        var chunks = world.getChunkStore();
        var sectionRef = chunks.getChunkSectionReferenceAtBlock(position.x, position.y, position.z);
        if (sectionRef == null || !sectionRef.isValid()) return;
        var chunkStore = chunks.getStore();
        var section = chunkStore.getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return;
        var type = BlockType.getAssetMap().getAsset(section.get(position.x, position.y, position.z));
        if (!isChest(type)) return;
        var blockRef = BlockModule.getBlockEntity(chunkStore, sectionRef, position.x, position.y, position.z);
        if (blockRef == null || !blockRef.isValid()) return;
        var chest = chunkStore.getComponent(blockRef, ItemContainerBlock.getComponentType());
        if (chest == null) return;

        int rotation = section.getRotationIndex(position.x, position.y, position.z);
        var window = new ContainerBlockWindow(position.x, position.y, position.z, rotation,
            type, chest.getItemContainer());
        var windows = chest.getWindows();
        var uuid = playerRef.getUuid();
        if (!window.validate(ref, store) || windows.putIfAbsent(uuid, window) != null) return;
        window.registerCloseEvent(event -> {
            if (!windows.remove(uuid, window) || !windows.isEmpty() || !blockRef.isValid()) return;
            animate(world, position, "CloseWindow");
        });
        var page = new AlchemicalChestPage(playerRef, world, blockRef, window, plugin);
        if (!player.getPageManager().openCustomPageWithWindows(ref, store, page, window)) {
            windows.remove(uuid, window);
            return;
        }
        if (windows.size() == 1) animate(world, position, "OpenWindow");
    }

    private static void animate(World world, BlockPosition position, String state) {
        var chunks = world.getChunkStore();
        var sectionRef = chunks.getChunkSectionReferenceAtBlock(position.x, position.y, position.z);
        if (sectionRef == null || !sectionRef.isValid()) return;
        var section = chunks.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return;
        var type = BlockType.getAssetMap().getAsset(section.get(position.x, position.y, position.z));
        if (!isChest(type)) return;
        BlockOperations.setBlockInteractionState(chunks, sectionRef,
            position.x, position.y, position.z, type, state, false);
        var stateType = type.getBlockForState(state);
        if (stateType != null && stateType.getInteractionSoundEventIndex() != 0) {
            SoundUtil.playSoundEvent3d(stateType.getInteractionSoundEventIndex(), SoundCategory.SFX,
                position.x + 0.5, position.y + 0.5, position.z + 0.5, world.getEntityStore().getStore());
        }
    }

    private static boolean isChest(BlockType type) {
        return type != null && type.getItem() != null && CHEST.equals(type.getItem().getId());
    }
}
