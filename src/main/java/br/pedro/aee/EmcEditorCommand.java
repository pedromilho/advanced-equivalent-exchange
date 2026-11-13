package br.pedro.aee;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

final class EmcEditorCommand extends AbstractPlayerCommand {
    private final AdvancedEquivalencyPlugin plugin;
    private final EmcPermissions permissions;

    EmcEditorCommand(AdvancedEquivalencyPlugin plugin, EmcPermissions permissions) {
        super("advancedequivalency", "server.aee.commands.editor");
        this.plugin = plugin;
        this.permissions = permissions;
        addAliases("aee");
        requireNoPermission();
        addUsageVariant(new EmcPermissionCommand(permissions));
    }

    @Override
    public boolean hasPermission(CommandSender sender) {
        return EmcPermissions.level(sender) != EmcPermissions.Level.OFF || permissions.canUsePermissionCommand(sender);
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef playerRef, World world) {
        if (EmcPermissions.level(playerRef) == EmcPermissions.Level.OFF) {
            playerRef.sendMessage(Message.translation("server.aee.editor.denied"));
            return;
        }
        var player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new EmcEditorPage(playerRef, plugin, permissions));
        }
    }
}
