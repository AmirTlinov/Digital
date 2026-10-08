/*
 * Use of this source code is governed by the GPL v3 license in LICENSE.
 */
package de.neemann.digital.integration;

import de.neemann.digital.core.Model;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.Signal;
import de.neemann.digital.core.wiring.Clock;
import de.neemann.digital.gui.MainGui;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads and changes the GUI's live model under its existing synchronization boundary. */
final class SimulationCommands {
    private SimulationCommands() {
    }

    static JSONObject signals(MainGui gui) {
        JSONObject result = new JSONObject().put("running", false)
                .put("inputs", new JSONArray()).put("outputs", new JSONArray()).put("signals", new JSONArray());
        Model model = gui.getModel();
        if (model != null && model.isRunning()) {
            model.read(() -> {
                result.put("running", true);
                result.put("inputs", describe(model.getInputs()));
                result.put("outputs", describe(model.getOutputs()));
                result.put("signals", describe(model.getSignals()));
            });
        }
        return result;
    }

    private static JSONArray describe(List<Signal> signals) {
        JSONArray values = new JSONArray();
        for (Signal signal : signals) {
            ObservableValue value = signal.getValue();
            values.put(new JSONObject().put("name", signal.getName()).put("bits", value.getBits())
                    .put("value", Long.toUnsignedString(value.getValueHighZIsZero()))
                    .put("hex", "0x" + Long.toUnsignedString(value.getValueHighZIsZero(), 16))
                    .put("highZ", "0x" + Long.toUnsignedString(value.getHighZ(), 16)));
        }
        return values;
    }

    static void setInputs(MainGui gui, JSONArray values) {
        Model model = running(gui);
        if (values.isEmpty() || values.length() > 200)
            throw new IllegalArgumentException("Provide 1..200 input values");
        ArrayList<Signal> targets = new ArrayList<>();
        ArrayList<Long> numbers = new ArrayList<>();
        ArrayList<Long> highZ = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject input = values.getJSONObject(i);
            String name = input.getString("name");
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate input: " + name);
            List<Signal> matches = model.getInputs().stream().filter(s -> name.equals(s.getName())).toList();
            if (matches.size() != 1 || matches.get(0).getSetter() == null)
                throw new IllegalArgumentException("Input must have a unique writable name: " + name);
            Signal target = matches.get(0);
            String text = input.getString("value").trim();
            long value = 0;
            long z = 0;
            if (text.equalsIgnoreCase("Z")) {
                z = target.getValue().getBits() == 64 ? -1L : (1L << target.getValue().getBits()) - 1;
            } else {
                int radix = 10;
                if (text.startsWith("0x") || text.startsWith("0X")) { radix = 16; text = text.substring(2); }
                else if (text.startsWith("0b") || text.startsWith("0B")) { radix = 2; text = text.substring(2); }
                if (text.length() > 64 || !text.matches(radix == 16 ? "[0-9a-fA-F]+" : radix == 2 ? "[01]+" : "[0-9]+"))
                    throw new IllegalArgumentException("Use unsigned decimal, 0x hex, 0b binary or Z for " + name);
                BigInteger number = new BigInteger(text, radix);
                if (number.bitLength() > target.getValue().getBits())
                    throw new IllegalArgumentException("Value exceeds " + target.getValue().getBits() + " bits for " + name);
                value = number.longValue();
            }
            targets.add(target);
            numbers.add(value);
            highZ.add(z);
        }
        model.modify(() -> {
            for (int i = 0; i < targets.size(); i++) targets.get(i).getSetter().set(numbers.get(i), highZ.get(i));
        });
        gui.checkControlledSimulationError(model);
        gui.getCircuitComponent().graphicHasChanged();
    }

    static void clockCycle(MainGui gui) {
        Model model = running(gui);
        if (gui.isRealTimeClockRunning())
            throw new IllegalStateException("Stop and start the simulation from the plugin to use a manual clock");
        if (model.getClocks().size() != 1)
            throw new IllegalArgumentException("A clock cycle requires exactly one clock");
        Clock clock = model.getClocks().get(0);
        ObservableValue output = clock.getClockOutput();
        final boolean[] initialLevel = new boolean[1];
        model.read(() -> initialLevel[0] = output.getBool());
        model.modify(() -> output.setBool(!initialLevel[0]));
        gui.checkControlledSimulationError(model);
        if (model.isRunning()) model.modify(() -> output.setBool(initialLevel[0]));
        gui.checkControlledSimulationError(model);
        gui.getCircuitComponent().graphicHasChanged();
    }

    private static Model running(MainGui gui) {
        Model model = gui.getModel();
        if (model == null || !model.isRunning()) throw new IllegalStateException("Start the simulation first");
        return model;
    }
}
