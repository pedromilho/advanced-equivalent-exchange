package br.pedro.aee;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Locale;

final class EmcPermissionCommand extends CommandBase {
    private final EmcPermissions permissions;
    private final RequiredArg<PlayerRef> player;
    private final RequiredArg<String> mode;
    private final RequiredArg<String> state;

    EmcPermissionCommand(EmcPermissions permissions) {
        super("server.aee.commands.permissions");
        this.permissions = permissions;
        player = withRequiredArg("nickname", "server.aee.commands.player", ArgTypes.PLAYER_REF);
        mode = withRequiredArg("mode", "server.aee.commands.mode", ArgTypes.STRING);
        state = withRequiredArg("state", "server.aee.commands.state", ArgTypes.STRING);
        requirePermission(EmcPermissions.MANAGE);
    }

    @Override
    public boolean hasPermission(CommandSender sender) {
        return permissions.canUsePermissionCommand(sender);
    }

    @Override
    protected void executeSync(CommandContext context) {
        if (!hasPermission(context.sender())) {
            context.sendMessage(Message.translation("server.aee.editor.manageDenied"));
            return;
        }
        var level = switch (state.get(context).toLowerCase(Locale.ROOT)) {
            case "on" -> EmcPermissions.Level.MODIFY;
            case "readonly" -> EmcPermissions.Level.READONLY;
            case "off" -> EmcPermissions.Level.OFF;
            default -> null;
        };
        if (level == null || !"modify".equalsIgnoreCase(mode.get(context))) {
            context.sendMessage(Message.translation("server.aee.editor.permissionUsage"));
            return;
        }
        context.sendMessage(Message.translation(permissions.setLevel(player.get(context), level)
            ? "server.aee.editor.permissionSaved" : "server.aee.editor.ownerProtected"));
    }
}
