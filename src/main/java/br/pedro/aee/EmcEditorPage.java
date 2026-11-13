package br.pedro.aee;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class EmcEditorPage extends InteractiveCustomUIPage<EmcEditorPage.Data> {
    private static final String UI = "AdvancedEquivalency/EmcEditor.ui";
    private static final String CARD = "AdvancedEquivalency/EmcEditorCard.ui";
    private static final String PLAYER_ROW = "AdvancedEquivalency/EmcPlayerRow.ui";
    private static final int COLUMNS = 5;
    private static final int PAGE_SIZE = COLUMNS * 5;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WORDS = Pattern.compile("\\s+");

    private record Entry(String id, String name, String search) {}
    private record VisibleCard(Entry entry, String selector) {}
    private record PlayerEntry(UUID id, String name, boolean owner, boolean admin, EmcPermissions.Level level) {}

    private enum PlayerFilter {
        ALL, SELECTED, UNSELECTED, ADMINS, MODIFY, READONLY;

        boolean matches(PlayerEntry player) {
            return switch (this) {
                case ALL -> true;
                case SELECTED -> player.level() != EmcPermissions.Level.OFF;
                case UNSELECTED -> player.level() == EmcPermissions.Level.OFF;
                case ADMINS -> player.admin() || player.owner();
                case MODIFY -> player.level() == EmcPermissions.Level.MODIFY;
                case READONLY -> player.level() == EmcPermissions.Level.READONLY;
            };
        }
    }

    private final AdvancedEquivalencyPlugin plugin;
    private final EmcPermissions permissions;
    private final String language;
    private final NumberFormat numbers;
    private final List<Entry> catalog;
    private final Map<String, OptionalLong> pending = new HashMap<>();
    private final Map<String, VisibleCard> visible = new HashMap<>();
    private final Set<UUID> visiblePlayers = new HashSet<>();
    private String search = "";
    private String editorValue = "";
    private String status = "";
    private boolean unpricedOnly;
    private boolean playersMenu;
    private PlayerFilter playerFilter = PlayerFilter.ALL;
    private int page;
    private Entry selected;

    EmcEditorPage(PlayerRef player, AdvancedEquivalencyPlugin plugin, EmcPermissions permissions) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.plugin = plugin;
        this.permissions = permissions;
        language = I18nModule.get().resolveLanguage(player.getLanguage());
        numbers = NumberFormat.getIntegerInstance(Locale.forLanguageTag(language));
        catalog = Item.getAssetMap().getAssetMap().values().stream().filter(EmcValues::editable)
            .map(this::entry).sorted(Comparator.comparing(Entry::search).thenComparing(Entry::id)).toList();
    }

    private Entry entry(Item item) {
        String id = item.getId();
        String name = I18nModule.get().getMessage(language, item.getTranslationKey());
        if (name == null || name.isBlank()) name = id.replace('_', ' ');
        return new Entry(id, name, normalize(name + " " + id));
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        commands.append(UI);
        commands.set("#PageHeading.Text", "Advanced Equivalency");
        commands.set("#Search.Value", search);
        commands.set("#EditorHeading.Text", text("heading"));
        commands.set("#ChooseItem.Text", text("choose"));
        commands.set("#ValueLabel.Text", text("value"));
        commands.set("#Value.PlaceholderText", text("automatic"));
        commands.set("#ValueHint.Text", text("valueHint"));
        commands.set("#PermissionsHeading.Text", text("players"));
        commands.set("#PlayerNameColumn.Text", text("playerName"));
        commands.set("#ModifyColumn.Text", text("modify"));
        commands.set("#ReadonlyColumn.Text", text("readonly"));
        commands.set("#CommandAccessTitle.Text", text("commandAccess"));
        commands.set("#CommandAccessHint.Text", text("commandHint"));
        commands.set("#CommandAdminOnly.Text", text("commandAdmin"));
        commands.set("#CommandAll.Text", text("commandAll"));
        commands.set("#Close.Text", text("close"));
        commands.set("#Save.Text", text("save"));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Search",
            EventData.of("Action", "Search").append("@Search", "#Search.Value"), false);
        for (String action : List.of("Filter", "Previous", "Next", "Close", "CommandAdminOnly", "CommandAll")) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#" + action, EventData.of("Action", action));
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Players",
            EventData.of("Action", "Players").append("@Value", "#Value.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Save",
            EventData.of("Action", "Save").append("@Value", "#Value.Value"));
        renderCatalog(commands, events);
        renderEditor(commands);
        renderControls(commands);
        renderStatus(commands);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        var access = EmcPermissions.level(playerRef);
        if (access == EmcPermissions.Level.OFF || "Close".equals(data.action)) {
            close();
            return;
        }
        if (data.action == null) {
            sendUpdate();
            return;
        }
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        boolean canEdit = access == EmcPermissions.Level.MODIFY;
        if (playersMenu && !canEdit) {
            playersMenu = false;
            search = "";
            page = 0;
            commands.set("#Search.Value", "");
            renderCatalog(commands, events);
            renderEditor(commands);
        }
        switch (data.action) {
            case "Search" -> {
                search = data.search == null ? "" : data.search.substring(0, Math.min(128, data.search.length()));
                page = 0;
                renderCatalog(commands, events);
            }
            case "Filter" -> {
                if (playersMenu) {
                    var filters = PlayerFilter.values();
                    playerFilter = filters[(playerFilter.ordinal() + 1) % filters.length];
                } else {
                    unpricedOnly = !unpricedOnly;
                }
                page = 0;
                renderCatalog(commands, events);
            }
            case "Previous", "Next" -> {
                page += data.action.equals("Next") ? 1 : -1;
                renderCatalog(commands, events);
            }
            case "Select" -> {
                var card = visible.get(data.item);
                if (!playersMenu && card != null && !card.entry().equals(selected) && (!canEdit || stage(data.value))) {
                    Entry previous = selected;
                    selected = card.entry();
                    if (previous != null && visible.containsKey(previous.id())) {
                        renderCard(commands, visible.get(previous.id()));
                    }
                    renderCard(commands, card);
                    renderEditor(commands);
                }
            }
            case "Save" -> {
                if (!playersMenu && canEdit && stage(data.value)) {
                    try {
                        status = text(plugin.saveEmcOverrides(pending) ? "saved" : "noChanges");
                        pending.clear();
                        renderCatalog(commands, events);
                        renderEditor(commands);
                    } catch (IOException error) {
                        plugin.getLogger().atWarning().withCause(error).log("Could not save EMC overrides");
                        status = text("saveFailed");
                    }
                }
            }
            case "Players" -> {
                if (canEdit && (playersMenu || stage(data.value))) {
                    playersMenu = !playersMenu;
                    search = "";
                    page = 0;
                    status = "";
                    commands.set("#Search.Value", "");
                    renderCatalog(commands, events);
                    if (!playersMenu) renderEditor(commands);
                }
            }
            case "PlayerModify", "PlayerReadonly" -> {
                if (playersMenu && canEdit && EmcPermissions.canManage(playerRef) && data.checked != null) {
                    changePlayer(data);
                    renderCatalog(commands, events);
                }
            }
            case "CommandAdminOnly", "CommandAll" -> {
                if (playersMenu && canEdit && EmcPermissions.canManage(playerRef)) {
                    var mode = data.action.equals("CommandAll")
                        ? EmcPermissions.CommandAccess.ALL : EmcPermissions.CommandAccess.ADMIN_ONLY;
                    try {
                        permissions.saveCommandAccess(mode);
                        status = text("permissionSaved");
                    } catch (IOException error) {
                        plugin.getLogger().atWarning().withCause(error).log("Could not save permission command access");
                        status = text("permissionSaveFailed");
                    }
                }
            }
            default -> { }
        }
        if (EmcPermissions.level(playerRef) == EmcPermissions.Level.OFF) {
            close();
            return;
        }
        renderControls(commands);
        renderStatus(commands);
        sendUpdate(commands, events, false);
    }

    private boolean stage(String input) {
        if (selected == null || input == null) return true;
        String value = input.strip();
        if (value.equals(editorValue)) return true;
        try {
            OptionalLong change = value.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
            if (change.isPresent() && (change.getAsLong() < 0 || change.getAsLong() > EmcValues.MAX_UNIT)) {
                throw new NumberFormatException("EMC out of range");
            }
            String id = selected.id();
            if (!EmcValues.editable(Item.getAssetMap().getAsset(id))) {
                status = text("unavailable");
                return false;
            }
            Long override = change.isPresent() ? change.getAsLong() : null;
            if (Objects.equals(override, plugin.emcOverrides().get(id))) pending.remove(id);
            else pending.put(id, change);
            editorValue = value;
            status = "";
            return true;
        } catch (NumberFormatException invalid) {
            status = text("invalid") + " " + numbers.format(EmcValues.MAX_UNIT) + ".";
            return false;
        }
    }

    private void renderCatalog(UICommandBuilder commands, UIEventBuilder events) {
        visible.clear();
        visiblePlayers.clear();
        commands.clear("#Grid");
        String[] words = WORDS.split(normalize(search));
        if (playersMenu) {
            renderPlayers(commands, events, words);
            return;
        }
        var values = plugin.emcValues();
        var results = catalog.stream().filter(entry -> matches(entry, words))
            .filter(entry -> !unpricedOnly || displayedValue(entry.id(), values) == 0).toList();
        commands.set("#CatalogTitle.Text", text("items") + "  ·  " + numbers.format(results.size()));
        commands.set("#Filter.Text", text(unpricedOnly ? "unpriced" : "all"));
        int start = paginate(commands, results.size(), PAGE_SIZE);
        for (int index = start; index < Math.min(start + PAGE_SIZE, results.size()); index++) {
            int offset = index - start;
            String row = "#Row" + offset / COLUMNS;
            if (offset % COLUMNS == 0) {
                commands.appendInline("#Grid", "Group " + row + " { LayoutMode: Left; Anchor: (Height: 156); }");
            }
            var entry = results.get(index);
            var card = new VisibleCard(entry, row + "[" + offset % COLUMNS + "]");
            visible.put(entry.id(), card);
            commands.append(row, CARD);
            commands.set(card.selector() + " #Preview.ItemId", entry.id());
            commands.set(card.selector() + " #Name.Text", entry.name());
            commands.set(card.selector() + " #Id.Text", entry.id());
            renderCard(commands, card);
            events.addEventBinding(CustomUIEventBindingType.Activating, card.selector() + " #Toggle",
                EventData.of("Action", "Select").append("Item", entry.id()).append("@Value", "#Value.Value"));
        }
    }

    private void renderPlayers(UICommandBuilder commands, UIEventBuilder events, String[] words) {
        final int playersPerPage = 25;
        var results = Universe.get().getPlayers().stream().map(this::playerEntry)
            .filter(player -> playerFilter.matches(player) && matches(normalize(player.name()), words))
            .sorted(Comparator.comparing(PlayerEntry::owner).reversed()
                .thenComparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(PlayerEntry::id))
            .toList();
        commands.set("#CatalogTitle.Text", text("players") + "  ·  " + numbers.format(results.size()));
        commands.set("#Filter.Text", text("playerFilter." + playerFilter.name()));
        int start = paginate(commands, results.size(), playersPerPage);
        boolean canManage = EmcPermissions.canManage(playerRef);
        for (int index = start; index < Math.min(start + playersPerPage, results.size()); index++) {
            var player = results.get(index);
            String row = "#Grid[" + (index - start) + "]";
            visiblePlayers.add(player.id());
            commands.append("#Grid", PLAYER_ROW);
            String suffix = player.owner() ? " (" + text("owner") + ")" : player.admin() ? " (" + text("admin") + ")" : "";
            commands.set(row + " #PlayerName.Text", player.name() + suffix);
            boolean locked = player.owner() || !canManage;
            renderPlayerCheck(commands, events, row, "Modify", player, locked, player.level() == EmcPermissions.Level.MODIFY);
            renderPlayerCheck(commands, events, row, "Readonly", player, locked, player.level() == EmcPermissions.Level.READONLY);
        }
    }

    private PlayerEntry playerEntry(PlayerRef player) {
        UUID id = player.getUuid();
        boolean admin = player.hasPermission("*") || PermissionsModule.get().getGroupsForUser(id).contains("hytale:Admin");
        return new PlayerEntry(id, player.getUsername(), EmcPermissions.isOwner(id), admin, EmcPermissions.level(player));
    }

    private static void renderPlayerCheck(UICommandBuilder commands, UIEventBuilder events, String row, String control,
                                          PlayerEntry player, boolean locked, boolean checked) {
        String check = row + " #" + control;
        commands.set(check + ".Visible", !locked);
        commands.set(check + "Locked.Visible", locked);
        commands.set(check + "Mark.Visible", checked);
        if (!locked) {
            commands.set(check + ".Value", checked);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, check,
                EventData.of("Action", "Player" + control).append("Item", player.id().toString())
                    .append("@Checked", check + ".Value"));
        }
    }

    private void changePlayer(Data data) {
        if (data.item == null) return;
        UUID id;
        try {
            id = UUID.fromString(data.item);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (!visiblePlayers.contains(id)) return;
        var target = Universe.get().getPlayer(id);
        if (target == null) return;
        var level = data.action.equals("PlayerModify") ? EmcPermissions.Level.MODIFY : EmcPermissions.Level.READONLY;
        status = text(permissions.setChecked(target, level, data.checked) ? "permissionSaved" : "ownerProtected");
    }

    private int paginate(UICommandBuilder commands, int count, int pageSize) {
        int pages = Math.max(1, (count + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pages - 1);
        commands.set("#PageNumber.Text", (page + 1) + " / " + pages);
        commands.set("#Previous.Disabled", page == 0);
        commands.set("#Next.Disabled", page + 1 == pages);
        commands.set("#Empty.Text", text(playersMenu ? "playersEmpty" : "empty"));
        commands.set("#Empty.Visible", count == 0);
        return page * pageSize;
    }

    private void renderControls(UICommandBuilder commands) {
        boolean canEdit = EmcPermissions.level(playerRef) == EmcPermissions.Level.MODIFY;
        boolean canManage = canEdit && EmcPermissions.canManage(playerRef);
        commands.set("#Search.PlaceholderText", text(playersMenu ? "playersSearch" : "search"));
        commands.set("#PlayerColumns.Visible", playersMenu);
        commands.set("#EditorPanel.Visible", !playersMenu);
        commands.set("#PermissionsHelp.Visible", playersMenu);
        commands.set("#PermissionsHint.Text", text(canManage ? "playersHint" : "playersViewHint"));
        commands.set("#Players.Text", text(playersMenu ? "backToItems" : "players"));
        commands.set("#Players.Visible", canEdit);
        commands.set("#ValueEditor.Visible", canEdit);
        commands.set("#Advice.Text", text(canEdit ? "advice" : "readonlyHint"));
        commands.set("#Advice.Visible", !playersMenu);
        commands.set("#Save.Disabled", playersMenu || !canEdit);
        commands.set("#CommandAdminOnly.Disabled", !canManage);
        commands.set("#CommandAll.Disabled", !canManage);
        var mode = permissions.commandAccess();
        renderCommandStyle(commands, "#CommandAdminOnly", mode == EmcPermissions.CommandAccess.ADMIN_ONLY);
        renderCommandStyle(commands, "#CommandAll", mode == EmcPermissions.CommandAccess.ALL);
    }

    private static void renderCommandStyle(UICommandBuilder commands, String button, boolean selected) {
        commands.set(button + ".Style", selected
            ? Value.ref(UI, "SelectedButtonStyle") : Value.ref("Common.ui", "SmallSecondaryTextButtonStyle"));
    }

    private void renderCard(UICommandBuilder commands, VisibleCard card) {
        var entry = card.entry();
        commands.set(card.selector() + " #Toggle.Style",
            Value.ref(UI, entry.equals(selected) ? "SelectedCardButtonStyle" : "CardButtonStyle"));
        commands.set(card.selector() + " #CardValue.Text", formatEmc(displayedValue(entry.id(), plugin.emcValues())));
    }

    private long displayedValue(String id, EmcValues values) {
        var change = pending.get(id);
        return change == null ? values.get(id) : change.orElse(values.get(id));
    }

    private void renderEditor(UICommandBuilder commands) {
        commands.set("#Editor.Visible", selected != null);
        commands.set("#ChooseItem.Visible", selected == null);
        if (selected == null) return;
        String id = selected.id();
        Long saved = plugin.emcOverrides().get(id);
        var change = pending.get(id);
        editorValue = change != null
            ? change.isPresent() ? Long.toString(change.getAsLong()) : ""
            : saved == null ? "" : Long.toString(saved);
        commands.set("#EditorPreview.ItemId", id);
        commands.set("#EditorName.Text", selected.name());
        commands.set("#EditorId.Text", id);
        commands.set("#CurrentValue.Text", text("current") + " " + formatEmc(plugin.emcValues().get(id)));
        commands.set("#Value.Value", editorValue);
    }

    private void renderStatus(UICommandBuilder commands) {
        String message = status;
        if (message.isEmpty() && !playersMenu && !pending.isEmpty()) message = text("pending") + " " + numbers.format(pending.size());
        commands.set("#Status.Text", message);
    }

    private String formatEmc(long value) {
        return numbers.format(value) + " EMC";
    }

    private String text(String key) {
        return I18nModule.get().getMessage(language, "server.aee.editor." + key);
    }

    private static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
            .toLowerCase(Locale.ROOT).replace('_', ' ').strip();
    }

    private static boolean matches(Entry entry, String[] words) {
        return matches(entry.search(), words);
    }

    private static boolean matches(String normalized, String[] words) {
        for (String word : words) if (!normalized.contains(word)) return false;
        return true;
    }

    static final class Data {
        private static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("Item", Codec.STRING), (d, v) -> d.item = v, d -> d.item).add()
            .append(new KeyedCodec<>("@Search", Codec.STRING), (d, v) -> d.search = v, d -> d.search).add()
            .append(new KeyedCodec<>("@Value", Codec.STRING), (d, v) -> d.value = v, d -> d.value).add()
            .append(new KeyedCodec<>("@Checked", Codec.BOOLEAN), (d, v) -> d.checked = v, d -> d.checked).add()
            .build();
        private String action = "";
        private String item;
        private String search;
        private String value;
        private Boolean checked;
    }
}
