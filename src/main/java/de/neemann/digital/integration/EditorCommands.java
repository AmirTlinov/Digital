/*
 * Use of this source code is governed by the GPL v3 license in LICENSE.
 */
package de.neemann.digital.integration;

import de.neemann.digital.core.Model;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.graphics.Export;
import de.neemann.digital.draw.graphics.GraphicSVG;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.gui.MainGui;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.WeakHashMap;

/** Routes commands to a displayed editor; contains no circuit, history or model of its own. */
final class EditorCommands {
    private final WeakHashMap<MainGui, String> windowIds = new WeakHashMap<>();

    JSONObject execute(String command, JSONObject args) throws IOException {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Editor commands must execute on the Swing event dispatch thread");
        switch (command) {
            case "windows": {
                JSONArray windows = new JSONArray();
                for (MainGui gui : editors()) windows.put(metadata(gui));
                return new JSONObject().put("windows", windows);
            }
            case "new": {
                MainGui gui = new MainGui.MainBuilder().setCircuit(new Circuit()).build();
                gui.setVisible(true);
                return inspect(gui);
            }
            case "open": return open(args.getString("path"));
            default: break;
        }
        MainGui gui = target(args);
        JSONObject domainArgs = new JSONObject(args.toString());
        domainArgs.remove("windowId");
        switch (command) {
            case "inspect": return inspect(gui);
            case "library": return CircuitCommands.library(gui, domainArgs).put("windowId", id(gui))
                    .put("library", libraryState(gui));
            case "edit": {
                CircuitCommands.edit(gui, domainArgs);
                return inspect(gui);
            }
            case "signals": return SimulationCommands.signals(gui).put("windowId", id(gui))
                    .put("revision", CircuitCommands.revision(gui.getCircuitComponent().getCircuit()));
            case "svg": return svg(gui);
            case "save": {
                checkRevision(gui, args);
                File current = gui.getCurrentFile();
                String requested = args.optString("path", "");
                File file = requested.isEmpty() ? current : absoluteFile(requested);
                if (file == null) throw new IllegalArgumentException("An unsaved circuit needs an absolute .dig path");
                if (!file.getName().endsWith(".dig")) throw new IllegalArgumentException("Use a .dig file path");
                if (file.exists() && (current == null || !current.isFile() || !Files.isSameFile(current.toPath(), file.toPath()))
                        && !args.optBoolean("overwrite", false))
                    throw new IllegalStateException("The file already exists; use another path or explicitly set overwrite=true");
                gui.saveCircuitTo(file, true);
                return inspect(gui);
            }
            case "start": {
                checkRevision(gui, args);
                if (gui.getModel() == null || !gui.getModel().isRunning()) gui.startControlledSimulation();
                return inspect(gui);
            }
            case "stop": {
                checkRevision(gui, args);
                gui.ensureModelIsStopped();
                return inspect(gui);
            }
            case "clock": {
                checkRevision(gui, args);
                SimulationCommands.clockCycle(gui);
                return inspect(gui);
            }
            case "inputs": {
                checkRevision(gui, args);
                SimulationCommands.setInputs(gui, args.getJSONArray("values"));
                return inspect(gui);
            }
            default: throw new IllegalArgumentException("Unknown Digital command: " + command);
        }
    }

    private JSONObject open(String filename) throws IOException {
        File file = absoluteFile(filename);
        if (!file.isFile() || !file.getName().endsWith(".dig"))
            throw new IllegalArgumentException("Open an existing .dig file");
        for (MainGui editor : editors()) {
            if (editor.getCurrentFile() != null && editor.getCurrentFile().isFile()
                    && Files.isSameFile(file.toPath(), editor.getCurrentFile().toPath())) {
                editor.toFront();
                return inspect(editor);
            }
        }
        MainGui editor = new MainGui.MainBuilder().setCircuit(new Circuit()).build();
        try {
            editor.loadCircuitFrom(file, true, true);
            editor.setVisible(true);
        } catch (IOException | RuntimeException e) {
            editor.dispose();
            throw e;
        }
        return inspect(editor);
    }

    private static File absoluteFile(String filename) throws IOException {
        Path path = Path.of(filename);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Provide an absolute file path");
        return path.normalize().toFile().getCanonicalFile();
    }

    private static void checkRevision(MainGui gui, JSONObject args) {
        String actual = CircuitCommands.revision(gui.getCircuitComponent().getCircuit());
        if (!actual.equals(args.getString("expectedRevision")))
            throw new IllegalStateException("The circuit changed; inspect it again and use the new revision");
    }

    private JSONObject metadata(MainGui gui) {
        return new JSONObject().put("windowId", id(gui)).put("title", gui.getTitle())
                .put("path", gui.getCurrentFile() == null ? JSONObject.NULL : gui.getCurrentFile().getAbsolutePath())
                .put("modified", gui.isStateChanged())
                .put("running", gui.getModel() != null && gui.getModel().isRunning());
    }

    private JSONObject inspect(MainGui gui) {
        JSONObject result = CircuitCommands.describe(gui);
        JSONObject metadata = metadata(gui);
        for (String key : metadata.keySet()) result.put(key, metadata.get(key));
        return result.put("signals", SimulationCommands.signals(gui)).put("library", libraryState(gui));
    }

    private static JSONObject libraryState(MainGui gui) {
        ElementLibrary library = gui.getLibrary();
        return new JSONObject().put("pending", library.isScanPending())
                .put("root", library.getRootFilePath() == null ? JSONObject.NULL : library.getRootFilePath().getAbsolutePath())
                .put("error", library.getScanError() == null ? JSONObject.NULL : library.getScanError().getMessage())
                .put("warning", library.getWarningMessage() == null ? JSONObject.NULL : library.getWarningMessage().toString());
    }

    private JSONObject svg(MainGui gui) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Circuit circuit = gui.getCircuitComponent().getCircuit();
        Model model = gui.getModel();
        final IOException[] failure = new IOException[1];
        Runnable render = () -> {
            try {
                new Export(circuit, GraphicSVG::new).export(out);
            } catch (IOException e) {
                failure[0] = e;
            }
        };
        if (model == null) render.run();
        else model.read(render);
        if (failure[0] != null) throw failure[0];
        return new JSONObject().put("windowId", id(gui)).put("revision", CircuitCommands.revision(circuit))
                .put("svg", out.toString(StandardCharsets.UTF_8));
    }

    private MainGui target(JSONObject args) {
        ArrayList<MainGui> editors = editors();
        String selected = args.optString("windowId", "");
        if (!selected.isEmpty()) {
            for (MainGui gui : editors) if (id(gui).equals(selected)) return gui;
            throw new IllegalStateException("The Digital window no longer exists; list the open windows again");
        }
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        while (active != null) {
            if (active instanceof MainGui && editors.contains(active)) return (MainGui) active;
            active = active.getOwner();
        }
        if (editors.size() == 1) return editors.get(0);
        if (editors.isEmpty()) throw new IllegalStateException("No circuit is open; create or open a circuit");
        throw new IllegalStateException("Several Digital windows are open; supply windowId from list_windows");
    }

    private String id(MainGui gui) {
        return windowIds.computeIfAbsent(gui, key -> UUID.randomUUID().toString());
    }

    private static ArrayList<MainGui> editors() {
        ArrayList<MainGui> editors = new ArrayList<>();
        for (Frame frame : Frame.getFrames())
            if (frame instanceof MainGui && frame.isDisplayable() && frame.isVisible()) editors.add((MainGui) frame);
        return editors;
    }
}
