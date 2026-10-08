package de.tobihxd;

import de.neemann.digital.lang.Lang;
import de.neemann.gui.ErrorMessage;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Edits the component shortcuts owned by KeybindManager. */
public final class KeyBindSettingsPanel extends JPanel {
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final Map<String, JCheckBox> shiftBoxes = new LinkedHashMap<>();
    private final KeybindManager manager = KeybindManager.getInstance();

    public KeyBindSettingsPanel() {
        setLayout(new BorderLayout(10, 10));
        JPanel list = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        for (Map.Entry<String, String> entry : manager.getKeyBinds().entrySet()) {
            String value = entry.getValue();
            boolean shift = value.startsWith("Shift+");
            JTextField field = new JTextField(shift ? value.substring(6) : value, 6);
            JLabel label = new JLabel(Lang.get("elem_" + entry.getKey()));
            label.setLabelFor(field);
            field.getAccessibleContext().setAccessibleName(label.getText());
            JCheckBox shiftBox = new JCheckBox("Shift", shift);
            shiftBox.getAccessibleContext().setAccessibleName(label.getText() + " + Shift");
            fields.put(entry.getKey(), field);
            shiftBoxes.put(entry.getKey(), shiftBox);

            gbc.gridy = row++;
            gbc.gridx = 0;
            gbc.weightx = 1;
            list.add(label, gbc);
            gbc.gridx = 1;
            gbc.weightx = 0;
            list.add(field, gbc);
            gbc.gridx = 2;
            list.add(shiftBox, gbc);
        }

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        scrollPane.setPreferredSize(new Dimension(460, 360));
        add(scrollPane, BorderLayout.CENTER);
        JLabel hint = new JLabel(Lang.get("msg_componentShortcutsHint"));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(hint, BorderLayout.NORTH);
        JButton save = new JButton(Lang.get("btn_save"));
        save.addActionListener(e -> saveKeybinds());
        add(save, BorderLayout.SOUTH);
    }

    private void saveKeybinds() {
        LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
            String key = entry.getValue().getText().trim();
            bindings.put(entry.getKey(), key.isEmpty() ? ""
                    : (shiftBoxes.get(entry.getKey()).isSelected() ? "Shift+" : "") + key);
        }
        Set<String> invalid = manager.getInvalidKeyBinds(bindings);
        JTextField firstInvalid = null;
        for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
            boolean error = invalid.contains(entry.getKey());
            entry.getValue().putClientProperty("JComponent.outline", error ? "error" : null);
            if (error && firstInvalid == null)
                firstInvalid = entry.getValue();
        }
        if (firstInvalid != null) {
            JOptionPane.showMessageDialog(this, Lang.get("msg_invalidComponentShortcuts"),
                    Lang.get("error"), JOptionPane.ERROR_MESSAGE);
            firstInvalid.requestFocusInWindow();
            firstInvalid.selectAll();
            return;
        }

        try {
            manager.save(bindings);
            JOptionPane.showMessageDialog(this, Lang.get("msg_componentShortcutsSaved"),
                    Lang.get("attr_panel_keybinds"), JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            new ErrorMessage(Lang.get("msg_errorWritingFile")).addCause(e).show(this);
        }
    }
}
