package br.pedro.aee;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;

final class EmcConfiguration {
    record Snapshot(EmcValues values, Map<String, Long> overrides) {}

    private final Path overridesFile;
    private final Properties settings;
    private volatile Snapshot snapshot;

    EmcConfiguration(Path directory) throws IOException {
        var defaults = new Properties();
        try (var stream = EmcConfiguration.class.getResourceAsStream("/aee-emc.properties")) {
            defaults.load(stream);
        }
        Files.createDirectories(directory);
        Path baseFile = directory.resolve("emc.properties");
        if (Files.notExists(baseFile)) {
            try (var stream = EmcConfiguration.class.getResourceAsStream("/aee-emc.properties")) {
                Files.copy(stream, baseFile);
            }
        }
        settings = new Properties(defaults);
        try (var reader = Files.newBufferedReader(baseFile)) {
            settings.load(reader);
        }

        overridesFile = directory.resolve("emc-overrides.properties");
        var overrides = new HashMap<String, Long>();
        if (Files.exists(overridesFile)) {
            var saved = new Properties();
            try (var reader = Files.newBufferedReader(overridesFile)) {
                saved.load(reader);
            }
            for (String id : saved.stringPropertyNames()) {
                overrides.put(id, Long.parseLong(saved.getProperty(id).strip()));
            }
        }
        snapshot = snapshot(overrides);
    }

    Snapshot snapshot() {
        return snapshot;
    }

    synchronized boolean save(Map<String, OptionalLong> edits) throws IOException {
        var overrides = new HashMap<>(snapshot.overrides());
        edits.forEach((id, value) -> {
            if (value.isPresent()) overrides.put(id, value.getAsLong());
            else overrides.remove(id);
        });
        if (overrides.equals(snapshot.overrides())) return false;

        var next = snapshot(overrides);
        var properties = new Properties();
        overrides.forEach((id, value) -> properties.setProperty(id, Long.toString(value)));
        Path staged = Files.createTempFile(overridesFile.getParent(), "emc-overrides-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(staged)) {
                properties.store(writer, "Exact EMC values saved by the in-game editor. 0 disables exchange.");
            }
            try {
                Files.move(staged, overridesFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, overridesFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        snapshot = next;
        return true;
    }

    private Snapshot snapshot(Map<String, Long> overrides) {
        return new Snapshot(EmcValues.load(settings, overrides), Map.copyOf(overrides));
    }
}
