package br.pedro.aee;

import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class EmcPermissions {
    static final String MODIFY = "advancedequivalentexchange.config";
    static final String READONLY = "advancedequivalentexchange.readonly";
    static final String MANAGE = "advancedequivalentexchange.permissions";

    enum Level { OFF, READONLY, MODIFY }
    enum CommandAccess { ADMIN_ONLY, ALL }

    private final Path file;
    private volatile CommandAccess commandAccess = CommandAccess.ADMIN_ONLY;

    EmcPermissions(Path file, Consumer<String> warn) {
        this.file = file;
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            var saved = new Properties();
            saved.load(reader);
            commandAccess = CommandAccess.valueOf(saved.getProperty("PermissionCommands", "ADMIN_ONLY"));
        } catch (IOException | IllegalArgumentException invalid) {
            warn.accept("Could not read EMC permission command settings. Using admin only.");
        }
    }

    static boolean isOwner(UUID uuid) {
        return uuid != null && Constants.SINGLEPLAYER && SingleplayerModule.isOwner(uuid);
    }

    static Level level(CommandSender sender) {
        if (isOwner(sender.getUuid()) || sender.hasPermission(MODIFY)) return Level.MODIFY;
        return sender.hasPermission(READONLY) ? Level.READONLY : Level.OFF;
    }

    static boolean canManage(CommandSender sender) {
        return isOwner(sender.getUuid()) || sender.hasPermission(MANAGE);
    }

    CommandAccess commandAccess() {
        return commandAccess;
    }

    boolean canUsePermissionCommand(CommandSender sender) {
        return commandAccess == CommandAccess.ALL || canManage(sender);
    }

    synchronized void saveCommandAccess(CommandAccess access) throws IOException {
        if (access == commandAccess) return;
        Files.createDirectories(file.getParent());
        var saved = new Properties();
        saved.setProperty("PermissionCommands", access.name());
        Path staged = Files.createTempFile(file.getParent(), "commands-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(staged)) {
                saved.store(writer, "Advanced Equivalency - permission command access");
            }
            try {
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        commandAccess = access;
    }

    synchronized boolean setLevel(CommandSender target, Level level) {
        if (isOwner(target.getUuid())) return false;
        var permissions = PermissionsModule.get();
        UUID uuid = target.getUuid();
        // Explicit denials also override permissions inherited from a group.
        permissions.addUserPermission(uuid, switch (level) {
            case MODIFY -> Set.of(MODIFY, "-" + READONLY);
            case READONLY -> Set.of("-" + MODIFY, READONLY);
            case OFF -> Set.of("-" + MODIFY, "-" + READONLY);
        });
        permissions.removeUserPermission(uuid, switch (level) {
            case MODIFY -> Set.of("-" + MODIFY, READONLY);
            case READONLY -> Set.of(MODIFY, "-" + READONLY);
            case OFF -> Set.of(MODIFY, READONLY);
        });
        return true;
    }

    synchronized boolean setChecked(CommandSender target, Level requested, boolean checked) {
        if (checked) return setLevel(target, requested);
        return level(target) != requested || setLevel(target, Level.OFF);
    }
}
