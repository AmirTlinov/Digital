package de.tobihxd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.neemann.digital.gui.LibrarySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Owns the saved shortcuts for inserting built-in components. */
public final class KeybindManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeybindManager.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("(Shift\\+)?([A-Z0-9]|F[1-9]|F1[0-2]|ENTER)");

    private static class InstanceHolder {
        private static final KeybindManager INSTANCE = new KeybindManager(localFile());
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path localFile;
    private final LinkedHashMap<String, String> defaults;
    private final Set<LibrarySelector> selectors = new LinkedHashSet<>();
    private LinkedHashMap<String, String> keyBinds;

    KeybindManager(Path localFile) {
        this.localFile = localFile;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("keybinds/kb.json")) {
            if (in == null)
                throw new IllegalStateException("Missing component shortcuts");
            defaults = mapper.readValue(in, new TypeReference<>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read component shortcuts", e);
        }
        load();
    }

    public static KeybindManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private static Path localFile() {
        String appData = System.getenv("APPDATA");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && appData != null)
            return Path.of(appData, "Digital", "keybinds", "kb.json");
        return Path.of(System.getProperty("user.home"), ".digital", "keybinds", "kb.json");
    }

    private void load() {
        keyBinds = new LinkedHashMap<>(defaults);
        if (!Files.exists(localFile))
            return;

        try (InputStream in = Files.newInputStream(localFile)) {
            Map<String, String> saved = mapper.readValue(in, new TypeReference<>() { });
            LinkedHashMap<String, String> loaded = new LinkedHashMap<>(defaults);
            if (saved != null) {
                for (String name : defaults.keySet()) {
                    if (saved.containsKey(name) && isValidKey(saved.get(name)))
                        loaded.put(name, normalizeKey(saved.get(name)));
                }
            }
            if (getInvalidKeyBinds(loaded).isEmpty())
                keyBinds = loaded;
            else
                LOGGER.warn("Ignoring conflicting component shortcuts in {}", localFile);
        } catch (IOException e) {
            LOGGER.warn("Could not read component shortcuts from {}; using defaults: {}", localFile, e.getMessage());
        }
    }

    /** Persists a complete valid settings snapshot before updating any open editor. */
    public void save(Map<String, String> newKeyBinds) throws IOException {
        if (newKeyBinds == null || !newKeyBinds.keySet().equals(defaults.keySet())
                || !getInvalidKeyBinds(newKeyBinds).isEmpty())
            throw new IllegalArgumentException("Invalid component shortcuts");

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String name : defaults.keySet())
            normalized.put(name, normalizeKey(newKeyBinds.get(name)));

        Files.createDirectories(localFile.getParent());
        Path pending = Files.createTempFile(localFile.getParent(), "kb-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(), normalized);
            Files.move(pending, localFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }

        keyBinds = normalized;
        for (LibrarySelector selector : selectors)
            selector.updateKeyBinds();
    }

    public LinkedHashMap<String, String> getKeyBinds() {
        return new LinkedHashMap<>(keyBinds);
    }

    /** Identifies invalid, duplicate, or reserved combinations by component identity. */
    public Set<String> getInvalidKeyBinds(Map<String, String> bindings) {
        Set<String> invalid = new LinkedHashSet<>();
        Map<KeyStroke, String> used = new HashMap<>();
        for (String name : defaults.keySet()) {
            String key = bindings.get(name);
            if (!isValidKey(key)) {
                invalid.add(name);
                continue;
            }
            KeyStroke stroke = parseKeyStroke(key);
            if (stroke != null) {
                String previous = used.putIfAbsent(stroke, name);
                if (previous != null) {
                    invalid.add(previous);
                    invalid.add(name);
                }
            }
        }
        for (LibrarySelector selector : selectors)
            invalid.addAll(selector.getConflictingKeyBinds(bindings));
        return invalid;
    }

    public boolean isValidKey(String key) {
        if (key == null)
            return false;
        String normalized = normalizeKey(key);
        return normalized.isEmpty() || KEY_PATTERN.matcher(normalized).matches();
    }

    private static String normalizeKey(String key) {
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SHIFT+"))
            return "Shift+" + normalized.substring(6);
        return normalized;
    }

    public static KeyStroke parseKeyStroke(String key) {
        if (key == null)
            return null;
        String normalized = normalizeKey(key);
        if (normalized.isEmpty())
            return null;
        boolean shift = normalized.startsWith("Shift+");
        KeyStroke stroke = KeyStroke.getKeyStroke(shift ? normalized.substring(6) : normalized);
        if (stroke == null || !KEY_PATTERN.matcher(normalized).matches())
            return null;
        return shift ? KeyStroke.getKeyStroke(stroke.getKeyCode(), InputEvent.SHIFT_DOWN_MASK) : stroke;
    }

    public void addSelector(LibrarySelector selector) {
        selectors.add(selector);
    }

    public void removeSelector(LibrarySelector selector) {
        selectors.remove(selector);
    }
}
