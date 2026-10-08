/*
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.integration;

import de.neemann.digital.core.Bits;
import de.neemann.digital.core.IntFormat;
import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.ElementTypeDescription;
import de.neemann.digital.core.element.Key;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.element.Rotation;
import de.neemann.digital.core.io.InValue;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Pin;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.library.LibraryNode;
import de.neemann.digital.draw.shapes.GenericShape;
import de.neemann.digital.draw.shapes.MissingShape;
import de.neemann.digital.gui.MainGui;
import de.neemann.digital.gui.components.CircuitComponent;
import de.neemann.digital.undo.Modification;
import de.neemann.digital.undo.ModifyException;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The JSON adapter for the circuit already owned by MainGui and its UndoManager.
 * IDs address the original revision throughout a batch; newly added IDs arrive in the result.
 * Rotation uses native quarter turns (0..3), coordinates native units and grid snapping.
 * Supported attribute keys are returned by describe/library; all other attributes are read-only.
 */
public final class CircuitCommands {
    private static final int COORDINATE_LIMIT = 1_000_000;
    private static final Map<String, Key<?>> EDITABLE_KEYS = editableKeys();
    private static final Set<Key<?>> COMMON_KEYS = Set.of(Keys.LABEL, Keys.ROTATE, Keys.MIRROR);

    private CircuitCommands() { }

    private static Map<String, Key<?>> editableKeys() {
        Map<String, Key<?>> keys = new LinkedHashMap<>();
        for (Key<?> key : List.of(Keys.LABEL, Keys.BITS, Keys.INPUT_COUNT, Keys.VALUE, Keys.INPUT_DEFAULT,
                Keys.DEFAULT, Keys.ROTATE, Keys.MIRROR, Keys.WIDE_SHAPE, Keys.DESCRIPTION,
                Keys.IN_OUT_SMALL, Keys.IS_HIGH_Z, Keys.INT_FORMAT))
            keys.put(key.getKey(), key);
        return keys;
    }

    public static JSONObject describe(MainGui gui) {
        requireEdt();
        return describe(gui.getCircuitComponent().getCircuit(), gui.getLibrary());
    }

    static JSONObject describe(Circuit circuit, ElementLibrary library) {
        JSONArray elements = new JSONArray();
        for (int i = 0; i < circuit.getElements().size(); i++) {
            VisualElement element = circuit.getElements().get(i);
            ElementAttributes attributes = element.getElementAttributes();
            JSONObject data = new JSONObject().put("id", "e:" + i).put("type", element.getElementName())
                    .put("x", element.getPos().x).put("y", element.getPos().y).put("rotation", element.getRotate())
                    .put("label", attributes.getLabel()).put("bits", attributes.getBits());
            LibraryNode node = library.getElementNodeOrNull(element.getElementName());
            if (node != null)
                data.put("name", node.getTranslatedName());
            try {
                ElementTypeDescription description = elementType(library, element.getElementName());
                JSONObject values = new JSONObject();
                for (Key<?> key : EDITABLE_KEYS.values()) {
                    if (supports(description, key))
                        values.put(key.getKey(), jsonValue(attributes.get(key)));
                }
                data.put("attributes", values);
                data.put("ports", ports(element));
            } catch (RuntimeException e) {
                data.put("ports", new JSONArray()).put("metadataError", e.getMessage());
            }
            elements.put(data);
        }
        JSONArray wires = new JSONArray();
        for (int i = 0; i < circuit.getWires().size(); i++) {
            Wire wire = circuit.getWires().get(i);
            wires.put(new JSONObject().put("id", "w:" + i).put("x1", wire.p1.x).put("y1", wire.p1.y)
                    .put("x2", wire.p2.x).put("y2", wire.p2.y));
        }
        return new JSONObject().put("revision", revision(circuit)).put("elements", elements).put("wires", wires)
                .put("grid", GenericShape.SIZE).put("rotationUnit", "quarterTurn")
                .put("supportedAttributeKeys", new JSONArray(EDITABLE_KEYS.keySet()));
    }

    /** SHA-256 of native XML, with lazy empty fields normalized on a temporary copy. */
    public static String revision(Circuit circuit) {
        return snapshotRevision(circuit.createDeepCopy());
    }

    private static String snapshotRevision(Circuit snapshot) {
        // Canvas painting initializes this field; reading an empty field is not an edit.
        // createDeepCopy also gives absent measurementOrdering its native empty representation.
        snapshot.getAttributes();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            snapshot.save(bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not calculate the circuit revision", e);
        }
    }

    public static JSONObject library(MainGui gui, JSONObject args) {
        requireEdt();
        return library(gui.getLibrary(), args);
    }

    static JSONObject library(ElementLibrary library, JSONObject args) {
        fields(args, "query", "limit");
        String query = args.has("query") ? string(args, "query", 256).toLowerCase(Locale.ROOT) : "";
        int limit = args.has("limit") ? integer(args.get("limit"), "limit", 1, 100) : 30;
        List<LibraryNode> nodes = new ArrayList<>();
        leaves(library.getRoot(), nodes);
        JSONArray elements = new JSONArray();
        int total = 0;
        for (LibraryNode node : nodes) {
            if (!node.getName().toLowerCase(Locale.ROOT).contains(query)
                    && !node.getTranslatedName().toLowerCase(Locale.ROOT).contains(query))
                continue;
            total++;
            if (elements.length() >= limit)
                continue;
            JSONObject data = new JSONObject().put("type", node.getName()).put("name", node.getTranslatedName())
                    .put("available", node.isUnique());
            try {
                ElementTypeDescription description = elementType(library, node.getName());
                VisualElement initial = newElement(library, node.getName());
                JSONArray attributes = new JSONArray();
                Set<String> listed = new java.util.HashSet<>();
                for (Key<?> key : description.getAttributeList()) {
                    Key<?> editable = EDITABLE_KEYS.get(key.getKey());
                    attributes.put(attributeSchema(key, editable != null && supports(description, editable))
                            .put("default", jsonValue(initial.getElementAttributes().get(key))));
                    listed.add(key.getKey());
                }
                for (Key<?> key : COMMON_KEYS) {
                    if (!listed.contains(key.getKey()))
                        attributes.put(attributeSchema(key, true));
                }
                data.put("attributes", attributes);
                data.put("ports", ports(initial));
            } catch (RuntimeException e) {
                data.put("ports", new JSONArray()).put("metadataError", e.getMessage());
            }
            elements.put(data);
        }
        return new JSONObject().put("elements", elements).put("total", total).put("limit", limit)
                .put("supportedAttributeKeys", new JSONArray(EDITABLE_KEYS.keySet()));
    }

    public static JSONObject edit(MainGui gui, JSONObject args) {
        requireEdt();
        if (gui.getModel() != null)
            throw new IllegalStateException("Stop the simulation before editing the circuit");
        CircuitComponent component = gui.getCircuitComponent();
        if (component.getCircuitOrShallowCopy() != component.getCircuit())
            throw new IllegalStateException("Finish the current canvas interaction before editing");
        Modification<Circuit> batch = prepareEdit(component.getCircuit(), gui.getLibrary(), args);
        component.modify(batch);
        component.removeHighLighted();
        return describe(gui);
    }

    /** Compiles and completely preflights a single undo event without touching the live circuit. */
    static Modification<Circuit> prepareEdit(Circuit circuit, ElementLibrary library, JSONObject args) {
        fields(args, "expectedRevision", "operations");
        String expected = string(args, "expectedRevision", 64);
        if (!expected.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("expectedRevision must be a SHA-256 revision from describe");
        if (!expected.equals(revision(circuit)))
            throw new IllegalStateException("Circuit revision changed; read the current circuit before editing");
        Object rawOperations = args.get("operations");
        if (!(rawOperations instanceof JSONArray operations) || operations.isEmpty() || operations.length() > 200)
            throw new IllegalArgumentException("operations must contain between 1 and 200 operations");

        Circuit trial = circuit.createDeepCopy();
        // Native deep copies normalize a few optional XML fields; replay guards use this same form.
        ElementAttributes circuitAttributes = trial.getAttributes();
        String replayRevision = snapshotRevision(trial);
        if (circuitAttributes.get(Keys.LOCKED_MODE))
            throw new IllegalStateException("Unlock the circuit before editing");
        Replay replay = new Replay(trial);
        List<Consumer<Replay>> steps = new ArrayList<>();
        for (int i = 0; i < operations.length(); i++) {
            Object raw = operations.get(i);
            if (!(raw instanceof JSONObject operation))
                throw new IllegalArgumentException("Operation " + i + " must be an object");
            try {
                Consumer<Replay> step = compile(operation, replay, library);
                step.accept(replay);
                steps.add(step);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IllegalArgumentException("Operation " + i + ": " + e.getMessage(), e);
            }
        }
        trial.elementsMoved();
        // Shape preparation also belongs to preflight, before the existing UndoManager is called.
        for (VisualElement element : replay.changedElements)
            ports(element);
        return new AtomicBatch(replayRevision, List.copyOf(steps));
    }

    private static Consumer<Replay> compile(JSONObject operation, Replay replay, ElementLibrary library) {
        String op = string(operation, "op", 32);
        return switch (op) {
            case "add_element" -> {
                fields(operation, "op", "type", "x", "y", "rotation", "label", "bits", "inputCount", "value");
                String type = string(operation, "type", 512);
                ElementTypeDescription description = elementType(library, type);
                VisualElement element = newElement(library, type).setPos(position(operation, "x", "y"));
                if (operation.has("rotation"))
                    element.setRotation(integer(operation.get("rotation"), "rotation", 0, 3));
                if (operation.has("label"))
                    element.setAttribute(Keys.LABEL, string(operation, "label", 1024));
                if (operation.has("bits"))
                    setAttribute(element, description, Keys.BITS.getKey(), operation.get("bits"));
                if (operation.has("inputCount"))
                    setAttribute(element, description, Keys.INPUT_COUNT.getKey(), operation.get("inputCount"));
                if (operation.has("value")) {
                    Key<?> key = supports(description, Keys.INPUT_DEFAULT) ? Keys.INPUT_DEFAULT : Keys.VALUE;
                    setAttribute(element, description, key.getKey(), operation.get("value"));
                }
                ports(element);
                yield context -> {
                    VisualElement added = new VisualElement(element);
                    context.circuit.add(added);
                    context.changedElements.add(added);
                };
            }
            case "remove_element" -> {
                fields(operation, "op", "id");
                int index = id(operation, "e:", replay.elements.size());
                replay.element(index);
                yield context -> context.circuit.delete(context.element(index));
            }
            case "move_element" -> {
                fields(operation, "op", "id", "x", "y", "rotation");
                int index = id(operation, "e:", replay.elements.size());
                VisualElement element = replay.element(index);
                Vector position = position(operation, "x", "y");
                int rotation = operation.has("rotation")
                        ? integer(operation.get("rotation"), "rotation", 0, 3) : element.getRotate();
                yield context -> {
                    VisualElement moved = context.element(index);
                    moved.setPos(position);
                    moved.setRotation(rotation);
                    context.changedElements.add(moved);
                };
            }
            case "set_attribute" -> {
                fields(operation, "op", "id", "key", "value");
                int index = id(operation, "e:", replay.elements.size());
                VisualElement element = replay.element(index);
                ElementTypeDescription description = elementType(library, element.getElementName());
                String keyName = string(operation, "key", 64);
                Key<?> key = supportedKey(description, keyName);
                Object value = attributeValue(key, operation.get("value"));
                yield context -> {
                    VisualElement changed = context.element(index);
                    putAttribute(changed, key, value);
                    context.changedElements.add(changed);
                };
            }
            case "add_wire" -> {
                fields(operation, "op", "x1", "y1", "x2", "y2");
                Vector first = position(operation, "x1", "y1");
                Vector second = position(operation, "x2", "y2");
                if (first.equals(second))
                    throw new IllegalArgumentException("Wire endpoints must differ");
                yield context -> context.circuit.getWires().add(new Wire(first, second));
            }
            case "remove_wire" -> {
                fields(operation, "op", "id");
                int index = id(operation, "w:", replay.wires.size());
                replay.wire(index);
                // Normalize once after the batch, preserving the original wire IDs during its operations.
                yield context -> context.circuit.getWires().remove(context.wire(index));
            }
            default -> throw new IllegalArgumentException("Unsupported operation: " + op);
        };
    }

    private static void setAttribute(VisualElement element, ElementTypeDescription description, String name, Object raw) {
        Key<?> key = supportedKey(description, name);
        putAttribute(element, key, attributeValue(key, raw));
    }

    private static Key<?> supportedKey(ElementTypeDescription description, String name) {
        Key<?> key = EDITABLE_KEYS.get(name);
        if (key == null || !supports(description, key))
            throw new IllegalArgumentException("Unsupported attribute " + name + " for " + description.getName());
        if (COMMON_KEYS.contains(key))
            return key;
        return description.getAttributeList().stream()
                .filter(actual -> actual.getKey().equals(name)).findFirst().orElseThrow();
    }

    private static boolean supports(ElementTypeDescription description, Key<?> key) {
        return COMMON_KEYS.contains(key) || description.getAttributeList().stream()
                .anyMatch(actual -> actual.getKey().equals(key.getKey()) && actual.getValueClass() == key.getValueClass());
    }

    private static Object attributeValue(Key<?> key, Object raw) {
        String name = key.getKey();
        if (name.equals(Keys.ROTATE.getKey()))
            return new Rotation(integer(raw, key.getKey(), 0, 3));
        if (name.equals(Keys.BITS.getKey()) || name.equals(Keys.INPUT_COUNT.getKey())) {
            Key.KeyInteger count = (Key.KeyInteger) key;
            return integer(raw, name, Math.max(name.equals(Keys.INPUT_COUNT.getKey()) ? 2 : 1, count.getMin()),
                    Math.min(64, count.getMax()));
        }
        if (name.equals(Keys.VALUE.getKey()) || name.equals(Keys.DEFAULT.getKey()))
            return digitalValue(raw, false).getValue();
        if (name.equals(Keys.INPUT_DEFAULT.getKey()))
            return digitalValue(raw, true);
        if (name.equals(Keys.INT_FORMAT.getKey())) {
            if (!(raw instanceof String text))
                throw new IllegalArgumentException(key.getKey() + " must be an enum name");
            try {
                return IntFormat.valueOf(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid intFormat; use " + Arrays.toString(IntFormat.values()));
            }
        }
        if (key.getValueClass() == Boolean.class) {
            if (!(raw instanceof Boolean))
                throw new IllegalArgumentException(key.getKey() + " must be a boolean");
            return raw;
        }
        if (raw instanceof String text && text.length() <= (key == Keys.DESCRIPTION ? 8192 : 1024))
            return text;
        throw new IllegalArgumentException(key.getKey() + " must be a bounded string");
    }

    private static InValue digitalValue(Object raw, boolean allowHighZ) {
        String text;
        if (raw instanceof String string && !string.isBlank() && string.length() <= 128)
            text = string;
        else if (raw instanceof Number number) {
            try {
                text = new BigDecimal(number.toString()).toBigIntegerExact().toString();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("Value must be an integer or a Digital numeric string");
            }
        } else
            throw new IllegalArgumentException("Value must be an integer or a Digital numeric string");
        try {
            if (allowHighZ && text.trim().equalsIgnoreCase("Z"))
                return new InValue("Z");
            text = text.trim();
            int radix = 10;
            String digits = text;
            if (text.matches("0[xX][0-9a-fA-F]+")) {
                radix = 16;
                digits = text.substring(2);
            } else if (text.matches("0[bB][01]+")) {
                radix = 2;
                digits = text.substring(2);
            } else if (!text.matches("-?[0-9]+"))
                throw new NumberFormatException("Use decimal, 0x hex, 0b binary or Z for input defaults");
            BigInteger value = new BigInteger(digits, radix);
            if (value.compareTo(BigInteger.ONE.shiftLeft(63).negate()) < 0
                    || value.compareTo(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)) > 0)
                throw new NumberFormatException("Value exceeds the supported 64-bit range");
            return new InValue(value.longValue());
        } catch (Bits.NumberFormatException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Digital numeric value: " + text, e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putAttribute(VisualElement element, Key key, Object value) {
        element.getElementAttributes().set(key, value);
    }

    private static JSONObject attributeSchema(Key<?> key, boolean editable) {
        JSONObject schema = new JSONObject().put("key", key.getKey()).put("name", key.getName())
                .put("type", key.getValueClass().getSimpleName()).put("editable", editable)
                .put("default", jsonValue(key.getDefault()));
        if (key instanceof Key.KeyInteger integer) {
            schema.put("minimum", integer.getMin()).put("maximum", integer.getMax());
            if (key.getKey().equals(Keys.INPUT_COUNT.getKey()))
                schema.put("maximum", Math.min(64, integer.getMax()));
        }
        if (key == Keys.ROTATE)
            schema.put("minimum", 0).put("maximum", 3);
        if (key == Keys.INT_FORMAT)
            schema.put("values", new JSONArray(Arrays.stream(IntFormat.values()).map(Enum::name).toList()));
        return schema;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof Rotation rotation)
            return rotation.getRotation();
        if (value instanceof Enum<?> enumeration)
            return enumeration.name();
        // JSON clients use doubles; preserve full 64-bit component values as strings.
        if (value instanceof Long || value instanceof InValue)
            return value.toString();
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;
        return String.valueOf(value);
    }

    private static JSONArray ports(VisualElement element) {
        if (element.getShape() instanceof MissingShape)
            throw new IllegalArgumentException("Cannot prepare the shape for " + element.getElementName());
        JSONArray ports = new JSONArray();
        for (Pin pin : element.getPins()) {
            JSONObject data = new JSONObject().put("name", pin.getName()).put("x", pin.getPos().x).put("y", pin.getPos().y);
            if (pin.getDirection() != null)
                data.put("direction", pin.getDirection().name());
            ports.put(data);
        }
        return ports;
    }

    private static ElementTypeDescription elementType(ElementLibrary library, String name) {
        LibraryNode node = library.getElementNodeOrNull(name);
        if (node == null || !node.isUnique())
            throw new IllegalArgumentException("Unknown or ambiguous library type: " + name);
        try {
            return node.getDescription();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read library type: " + name, e);
        }
    }

    private static VisualElement newElement(ElementLibrary library, String type) {
        return library.getElementNodeOrNull(type).setWideShapeFlagTo(
                new VisualElement(type).setShapeFactory(library.getShapeFactory()));
    }

    private static void leaves(LibraryNode node, List<LibraryNode> nodes) {
        if (node.isLeaf()) {
            if (!node.isHidden())
                nodes.add(node);
        } else {
            for (LibraryNode child : node)
                leaves(child, nodes);
        }
    }

    private static void fields(JSONObject object, String... names) {
        Set<String> allowed = Set.of(names);
        for (String name : object.keySet()) {
            if (!allowed.contains(name))
                throw new IllegalArgumentException("Unknown field: " + name);
        }
    }

    private static String string(JSONObject object, String name, int limit) {
        Object raw = object.get(name);
        if (raw instanceof String value && value.length() <= limit)
            return value;
        throw new IllegalArgumentException(name + " must be a string with at most " + limit + " characters");
    }

    private static int integer(Object raw, String name, int minimum, int maximum) {
        if (raw instanceof Number number) {
            try {
                BigInteger value = new BigDecimal(number.toString()).toBigIntegerExact();
                if (value.compareTo(BigInteger.valueOf(minimum)) >= 0 && value.compareTo(BigInteger.valueOf(maximum)) <= 0)
                    return value.intValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                // The public schema accepts finite integer values only.
            }
        }
        throw new IllegalArgumentException(name + " must be an integer between " + minimum + " and " + maximum);
    }

    private static Vector position(JSONObject object, String x, String y) {
        return new Vector(integer(object.get(x), x, -COORDINATE_LIMIT, COORDINATE_LIMIT),
                integer(object.get(y), y, -COORDINATE_LIMIT, COORDINATE_LIMIT));
    }

    private static int id(JSONObject operation, String prefix, int count) {
        String id = string(operation, "id", 32);
        if (!id.matches(prefix + "(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Expected an ID beginning with " + prefix);
        try {
            int index = Integer.parseInt(id.substring(2));
            if (index < count)
                return index;
        } catch (NumberFormatException ignored) {
            // Also rejects IDs too large to represent an index.
        }
        throw new IllegalArgumentException("Unknown ID: " + id);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Circuit commands must run on the Swing event dispatch thread");
    }

    private static final class Replay {
        private final Circuit circuit;
        private final List<VisualElement> elements;
        private final List<Wire> wires;
        private final Set<VisualElement> changedElements = new java.util.HashSet<>();

        private Replay(Circuit circuit) {
            this.circuit = circuit;
            elements = new ArrayList<>(circuit.getElements());
            wires = new ArrayList<>(circuit.getWires());
        }

        private VisualElement element(int index) {
            VisualElement element = elements.get(index);
            if (!circuit.getElements().contains(element))
                throw new IllegalArgumentException("Element e:" + index + " was removed earlier in the batch");
            return element;
        }

        private Wire wire(int index) {
            Wire wire = wires.get(index);
            if (!circuit.getWires().contains(wire))
                throw new IllegalArgumentException("Wire w:" + index + " was removed earlier in the batch");
            return wire;
        }
    }

    /** Retains only validated operations; each replay prepares an atomic transition on a temporary copy. */
    private record AtomicBatch(String expectedReplayRevision, List<Consumer<Replay>> steps) implements Modification<Circuit> {
        @Override
        public void modify(Circuit circuit) throws ModifyException {
            Circuit trial = circuit.createDeepCopy();
            trial.getAttributes();
            if (!expectedReplayRevision.equals(snapshotRevision(trial)))
                throw new ModifyException("Circuit changed before the edit could be committed");
            Replay replay = new Replay(trial);
            for (Consumer<Replay> step : steps)
                step.accept(replay);
            trial.elementsMoved();
            for (VisualElement element : replay.changedElements)
                ports(element);
            // Validation and allocation finish before the live owner's collections change.
            circuit.getElements().clear();
            circuit.getElements().addAll(trial.getElements());
            circuit.getWires().clear();
            circuit.getWires().addAll(trial.getWires());
            circuit.elementsMoved();
        }

        @Override
        public String toString() {
            return "Codex (" + steps.size() + ")";
        }
    }
}
