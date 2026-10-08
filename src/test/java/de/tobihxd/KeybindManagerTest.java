package de.tobihxd;

import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.gui.InsertAction;
import de.neemann.digital.gui.InsertHistory;
import de.neemann.digital.gui.LibrarySelector;
import de.neemann.digital.gui.components.CircuitComponent;
import junit.framework.TestCase;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JToolBar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Regression coverage for saving and applying component shortcuts. */
public class KeybindManagerTest extends TestCase {
    private Path directory;
    private Path settingsFile;

    @Override
    protected void setUp() throws IOException {
        directory = Files.createTempDirectory("digital-component-shortcuts-");
        settingsFile = directory.resolve("keybinds").resolve("kb.json");
    }

    @Override
    protected void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    public void testDefaultsAndPersistenceAfterRestart() throws IOException {
        KeybindManager manager = new KeybindManager(settingsFile);
        assertFalse(Files.exists(settingsFile));
        assertEquals("Shift+A", manager.getKeyBinds().get("NAnd"));
        assertFalse(manager.getKeyBinds().containsKey("Run"));
        manager.getKeyBinds().put("And", "Z");
        assertEquals("A", manager.getKeyBinds().get("And"));

        Map<String, String> bindings = manager.getKeyBinds();
        bindings.put("And", " n ");
        bindings.put("Not", "a");
        bindings.put("NAnd", "shift+e");
        bindings.put("LED", "");
        manager.save(bindings);

        assertEquals("N", manager.getKeyBinds().get("And"));
        assertEquals("Shift+E", manager.getKeyBinds().get("NAnd"));
        assertEquals(manager.getKeyBinds(), new KeybindManager(settingsFile).getKeyBinds());
    }

    public void testInvalidOrDuplicateKeysCannotReplaceSavedSettings() throws IOException {
        KeybindManager manager = new KeybindManager(settingsFile);
        manager.save(manager.getKeyBinds());
        String original = Files.readString(settingsFile);
        Map<String, String> bindings = manager.getKeyBinds();
        bindings.put("And", "Shift+O");
        bindings.put("LED", "Ctrl+A");
        bindings.put("Not", null);
        assertEquals(Set.of("And", "NOr", "LED", "Not"), manager.getInvalidKeyBinds(bindings));
        assertSaveRejected(manager, bindings);
        assertEquals(original, Files.readString(settingsFile));
        assertEquals("A", manager.getKeyBinds().get("And"));
        assertNull(KeybindManager.parseKeyStroke("Shift+NOT_A_KEY"));
        assertNull(KeybindManager.parseKeyStroke("F13"));
    }

    public void testExistingFilesUseCurrentDefaultsAndKnownIdentities() throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{\"And\":\"E\",\"Run\":\"Shift+ENTER\",\"Not\":null}");
        KeybindManager manager = new KeybindManager(settingsFile);
        assertEquals("E", manager.getKeyBinds().get("And"));
        assertEquals("N", manager.getKeyBinds().get("Not"));
        assertFalse(manager.getKeyBinds().containsKey("Run"));
        Files.writeString(settingsFile, "{\"And\":\"N\"}");
        assertEquals("A", new KeybindManager(settingsFile).getKeyBinds().get("And"));
        Files.writeString(settingsFile, "not json");
        assertEquals("A", new KeybindManager(settingsFile).getKeyBinds().get("And"));
    }

    public void testFailedWriteKeepsCurrentSettings() throws IOException {
        KeybindManager manager = new KeybindManager(settingsFile);
        Files.createDirectories(settingsFile);
        Map<String, String> bindings = manager.getKeyBinds();
        bindings.put("And", "E");
        try {
            manager.save(bindings);
            fail("Expected the unavailable settings path to reject the write");
        } catch (IOException expected) {
            assertEquals("A", manager.getKeyBinds().get("And"));
        }
        try (Stream<Path> files = Files.list(settingsFile.getParent())) {
            assertEquals(1, files.count());
        }
    }

    public void testLiveBindingsShiftSwapsDisableAndLibraryRebuild() throws IOException {
        KeybindManager manager = new KeybindManager(settingsFile);
        ElementLibrary library = new ElementLibrary();
        ShapeFactory shapes = new ShapeFactory(library);
        CircuitComponent canvas = new CircuitComponent(null, library, shapes);
        LibrarySelector selector = new LibrarySelector(library, shapes, null, manager);
        JMenu menu = selector.buildMenu(new InsertHistory(new JToolBar(), library), canvas);
        try {
            InsertAction and = action(canvas, "A");
            InsertAction nand = action(canvas, "Shift+A");
            InsertAction not = action(canvas, "N");
            assertEquals("And", and.getName());
            assertEquals("NAnd", nand.getName());
            assertEquals("Monoflop", action(canvas, "M").getName());
            assertFalse(canvas.getInputMap().get(KeybindManager.parseKeyStroke("R")) instanceof InsertAction);
            assertEquals(KeybindManager.parseKeyStroke("Shift+A"), menuItem(menu, "NAnd").getAccelerator());

            Map<String, String> bindings = manager.getKeyBinds();
            bindings.put("And", "N");
            bindings.put("Not", "A");
            bindings.put("NAnd", "Shift+E");
            manager.save(bindings);
            assertSame(and, action(canvas, "N"));
            assertSame(not, action(canvas, "A"));
            assertSame(nand, action(canvas, "Shift+E"));
            assertNull(canvas.getInputMap().get(KeybindManager.parseKeyStroke("Shift+A")));
            assertEquals(KeybindManager.parseKeyStroke("Shift+E"), menuItem(menu, "NAnd").getAccelerator());

            bindings.put("And", "R");
            assertTrue(manager.getInvalidKeyBinds(bindings).contains("And"));
            assertSaveRejected(manager, bindings);
            bindings.put("And", "N");
            bindings.put("NAnd", "");
            manager.save(bindings);
            assertNull(canvas.getInputMap().get(KeybindManager.parseKeyStroke("Shift+E")));
            assertNull(menuItem(menu, "NAnd").getAccelerator());

            int mapSize = canvas.getInputMap().size();
            selector.libraryChanged(null);
            selector.libraryChanged(null);
            assertEquals(mapSize, canvas.getInputMap().size());
            assertEquals("And", action(canvas, "N").getName());
            assertNull(canvas.getActionMap().get(and));
        } finally {
            selector.dispose();
        }
        assertNull(canvas.getInputMap().get(KeybindManager.parseKeyStroke("N")));
    }

    private void assertSaveRejected(KeybindManager manager, Map<String, String> bindings) throws IOException {
        try {
            manager.save(bindings);
            fail("Expected invalid shortcuts to be rejected");
        } catch (IllegalArgumentException expected) {
            // The settings and live editor must retain the last successful snapshot.
        }
    }

    private InsertAction action(CircuitComponent canvas, String key) {
        return (InsertAction) canvas.getInputMap().get(KeybindManager.parseKeyStroke(key));
    }

    private JMenuItem menuItem(JMenu menu, String name) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item instanceof JMenu) {
                JMenuItem found = menuItem((JMenu) item, name);
                if (found != null)
                    return found;
            } else if (item != null && item.getAction() instanceof InsertAction
                    && ((InsertAction) item.getAction()).getName().equals(name))
                return item;
        }
        return null;
    }
}
