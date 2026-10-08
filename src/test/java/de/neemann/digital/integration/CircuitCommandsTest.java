/*
 * Use of this source code is governed by the GPL v3 license in LICENSE.
 */
package de.neemann.digital.integration;

import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.Model;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.SyncAccess;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.model.ModelCreator;
import de.neemann.digital.draw.shapes.InputShape;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.gui.components.CircuitComponent;
import de.neemann.digital.gui.components.modification.ModifyAttribute;
import de.neemann.digital.gui.components.modification.ModifyMoveAndRotElement;
import de.neemann.digital.undo.UndoManager;
import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.awt.Point;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Exercises the native history owner, including its deterministic undo/redo replay. */
public class CircuitCommandsTest extends TestCase {
    private ElementLibrary library;

    @Override
    protected void setUp() {
        library = new ElementLibrary();
        new ShapeFactory(library);
    }

    public void testInvalidLateOperationLeavesCircuitAndHistoryUntouched() throws Exception {
        UndoManager<Circuit> history = new UndoManager<>(fixture());
        history.apply(new ModifyAttribute<>(history.getActual().getElements().get(3), Keys.LABEL, "Manual edit"));
        String before = CircuitCommands.revision(history.getActual());
        JSONArray operations = new JSONArray()
                .put(op("add_element").put("type", "Not").put("x", 300).put("y", 100))
                .put(op("move_element").put("id", "e:2").put("x", 400).put("y", 120))
                .put(op("set_attribute").put("id", "e:0").put("key", "Bits").put("value", 0));
        try {
            history.apply(CircuitCommands.prepareEdit(history.getActual(), library, request(before, operations)));
            fail("The invalid final operation must reject the entire batch");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Operation 2"));
        }
        assertEquals(before, CircuitCommands.revision(history.getActual()));
        assertEquals(4, history.getActual().getElements().size());
        assertEquals(new Vector(100, 0), history.getActual().getElements().get(2).getPos());
        assertEquals("Manual edit", history.getActual().getElements().get(3).getElementAttributes().getLabel());
        JSONArray overflow = new JSONArray().put(op("set_attribute").put("id", "e:0")
                .put("key", "InDefault").put("value", "18446744073709551616"));
        try {
            CircuitCommands.prepareEdit(history.getActual(), library, request(before, overflow));
            fail("Values beyond 64 bits must not silently wrap");
        } catch (IllegalArgumentException expected) {
            assertEquals(before, CircuitCommands.revision(history.getActual()));
        }
        history.undo();
        assertFalse(history.undoAvailable());
        assertEquals("Y", history.getActual().getElements().get(3).getElementAttributes().getLabel());
        history.redo();
        assertEquals(before, CircuitCommands.revision(history.getActual()));
    }

    public void testMixedBatchHasOneUndoStepAndStableIdsAcrossDeletes() throws Exception {
        UndoManager<Circuit> history = new UndoManager<>(fixture());
        String before = CircuitCommands.revision(history.getActual());
        JSONArray operations = new JSONArray()
                .put(op("remove_element").put("id", "e:1"))
                .put(op("move_element").put("id", "e:2").put("x", 120).put("y", 60).put("rotation", 1))
                .put(op("set_attribute").put("id", "e:2").put("key", "Inputs").put("value", 3))
                .put(op("set_attribute").put("id", "e:0").put("key", "InDefault").put("value", "0x1"))
                .put(op("add_element").put("type", "NAnd").put("x", 300).put("y", 100)
                        .put("rotation", 2).put("label", "Next").put("bits", 2).put("inputCount", 3))
                .put(op("add_wire").put("x1", 100).put("y1", 20).put("x2", 200).put("y2", 20))
                .put(op("remove_wire").put("id", "w:0"))
                .put(op("remove_wire").put("id", "w:1"));
        history.apply(CircuitCommands.prepareEdit(history.getActual(), library, request(before, operations)));

        Circuit edited = history.getActual();
        assertEquals(4, edited.getElements().size());
        VisualElement survivor = edited.getElements().get(1);
        assertEquals("Second", survivor.getElementAttributes().getLabel());
        assertEquals(new Vector(120, 60), survivor.getPos());
        assertEquals(1, survivor.getRotate());
        assertEquals(3, survivor.getElementAttributes().get(Keys.INPUT_COUNT).intValue());
        assertEquals(1, edited.getElements().get(0).getElementAttributes().get(Keys.INPUT_DEFAULT).getValue());
        assertEquals("NAnd", edited.getElements().get(3).getElementName());
        assertEquals(1, edited.getWires().size());
        assertEquals(new Vector(100, 20), edited.getWires().get(0).p1);
        assertEquals(new Vector(200, 20), edited.getWires().get(0).p2);
        JSONObject snapshot = CircuitCommands.describe(edited, library);
        assertEquals(4, snapshot.getJSONArray("elements").getJSONObject(1).getJSONArray("ports").length());
        assertEquals(CircuitCommands.revision(edited), snapshot.getString("revision"));
        String after = snapshot.getString("revision");

        history.undo();
        assertEquals(before, CircuitCommands.revision(history.getActual()));
        assertFalse(history.undoAvailable());
        history.redo();
        assertEquals(after, CircuitCommands.revision(history.getActual()));
        // A second batch must also replay after the first during later undo operations.
        JSONArray next = new JSONArray().put(op("set_attribute").put("id", "e:0").put("key", "Label").put("value", "B"));
        history.apply(CircuitCommands.prepareEdit(history.getActual(), library, request(after, next)));
        history.undo();
        assertEquals(after, CircuitCommands.revision(history.getActual()));
        history.undo();
        assertEquals(before, CircuitCommands.revision(history.getActual()));
        history.redo();
        history.redo();
        assertEquals("B", history.getActual().getElements().get(0).getElementAttributes().getLabel());
    }

    public void testStaleRevisionPreservesTheLaterManualEdit() throws Exception {
        UndoManager<Circuit> history = new UndoManager<>(fixture());
        String stale = CircuitCommands.revision(history.getActual());
        history.apply(new ModifyMoveAndRotElement(history.getActual().getElements().get(3), new Vector(240, 100), 1));
        String manual = CircuitCommands.revision(history.getActual());
        JSONArray operations = new JSONArray().put(op("remove_element").put("id", "e:3"));
        try {
            history.apply(CircuitCommands.prepareEdit(history.getActual(), library, request(stale, operations)));
            fail("A stale revision must reject the edit");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("revision changed"));
        }
        assertEquals(manual, CircuitCommands.revision(history.getActual()));
        assertEquals(new Vector(240, 100), history.getActual().getElements().get(3).getPos());
        history.undo();
        assertEquals(stale, CircuitCommands.revision(history.getActual()));
        assertFalse(history.undoAvailable());
    }

    public void testReadingOrPaintingAnEmptyCircuitDoesNotChangeRevision() {
        Circuit circuit = new Circuit();
        String empty = CircuitCommands.revision(circuit);
        circuit.getAttributes();
        assertEquals(empty, CircuitCommands.revision(circuit));
        assertEquals(empty, CircuitCommands.describe(circuit, library).getString("revision"));
        circuit.getAttributes().set(Keys.LABEL, "Real user edit");
        assertFalse(empty.equals(CircuitCommands.revision(circuit)));
    }

    public void testLoadedOffGridGeometrySurvivesCopyAndUnrelatedEdit() throws Exception {
        String xml = "<circuit><version>2</version><visualElements>"
                + "<visualElement><elementName>And</elementName><elementAttributes/><pos x=\"101\" y=\"203\"/></visualElement>"
                + "</visualElements><wires/></circuit>";
        Circuit loaded = Circuit.loadCircuit(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), library.getShapeFactory());
        Vector storedPosition = new Vector(101, 203);
        assertEquals(storedPosition, loaded.getElements().get(0).getPos());
        assertEquals(storedPosition, loaded.createDeepCopy().getElements().get(0).getPos());
        String before = CircuitCommands.revision(loaded);

        UndoManager<Circuit> history = new UndoManager<>(loaded);
        JSONArray operations = new JSONArray().put(op("add_element").put("type", "In")
                .put("x", 0).put("y", 0).put("label", "New input"));
        history.apply(CircuitCommands.prepareEdit(history.getActual(), library, request(before, operations)));
        assertEquals(storedPosition, history.getActual().getElements().get(0).getPos());
        history.undo();
        assertEquals(before, CircuitCommands.revision(history.getActual()));
        assertEquals(storedPosition, history.getActual().getElements().get(0).getPos());
        Circuit moved = loaded.createDeepCopy();
        moved.getElements().get(0).setPos(new Vector(109, 203));
        assertFalse(before.equals(CircuitCommands.revision(moved)));
    }

    public void testLibraryNotificationPreservesRunningInputUntilStop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CircuitComponent canvas = new CircuitComponent(null, library, library.getShapeFactory());
            Circuit circuit = new Circuit();
            circuit.add(element("In", "A", 0, 0));
            circuit.add(element("Out", "Y", 100, 0));
            circuit.add(new Wire(new Vector(0, 0), new Vector(100, 0)));
            canvas.setCircuit(circuit);
            library.addListener(canvas);
            try {
                ModelCreator creator = new ModelCreator(canvas.getCircuit(), library);
                Model model = creator.createModel(true);
                try {
                    model.init(false);
                    SyncAccess sync = model.createSync(false);
                    canvas.setModeAndReset(true, sync);
                    creator.connectToGui(canvas::modify);
                    VisualElement input = canvas.getCircuit().getElements().get(0);
                    Wire wire = canvas.getCircuit().getWires().get(0);
                    ObservableValue signal = model.getInput("A");
                    assertNotNull(signal);
                    assertTrue(input.isInteractive());
                    assertSame(signal, ((InputShape) input.getShape()).getObservableValue());
                    assertSame(signal, wire.getValue());

                    input.elementClicked(canvas, new Point(0, 0), input.getPos(), sync);
                    model.read(() -> assertEquals(1, model.getOutput("Y").getValue()));
                    // Custom-library publication uses this same native listener path.
                    library.fireLibraryChanged(library.getCustomNode());
                    assertTrue(input.isInteractive());
                    assertSame(signal, ((InputShape) input.getShape()).getObservableValue());
                    assertSame(signal, wire.getValue());
                    input.elementClicked(canvas, new Point(0, 0), input.getPos(), sync);
                    model.read(() -> {
                        assertEquals(0, model.getInput("A").getValue());
                        assertEquals(0, model.getOutput("Y").getValue());
                        assertEquals(0, wire.getValue().getValue());
                    });

                    model.close();
                    canvas.setModeAndReset(false, SyncAccess.NOSYNC);
                    assertFalse(input.isInteractive());
                    assertNull(((InputShape) input.getShape()).getObservableValue());
                    assertNull(wire.getValue());
                } finally {
                    model.close();
                    canvas.setModeAndReset(false, SyncAccess.NOSYNC);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                library.removeListener(canvas);
            }
        });
    }

    private Circuit fixture() {
        Circuit circuit = new Circuit();
        circuit.getAttributes().set(Keys.LABEL, "Test circuit");
        circuit.add(element("In", "A", 0, 0));
        // The native position/name matcher cannot distinguish these; revision IDs must.
        circuit.add(element("And", "First", 100, 0));
        circuit.add(element("And", "Second", 100, 0));
        circuit.add(element("Out", "Y", 200, 0));
        circuit.add(new Wire(new Vector(0, 20), new Vector(100, 20)));
        circuit.add(new Wire(new Vector(0, 40), new Vector(100, 40)));
        return circuit.createDeepCopy();
    }

    private VisualElement element(String type, String label, int x, int y) {
        return new VisualElement(type).setShapeFactory(library.getShapeFactory())
                .setAttribute(Keys.LABEL, label).setPos(new Vector(x, y));
    }

    private JSONObject op(String name) {
        return new JSONObject().put("op", name);
    }

    private JSONObject request(String revision, JSONArray operations) {
        return new JSONObject().put("expectedRevision", revision).put("operations", operations);
    }
}
