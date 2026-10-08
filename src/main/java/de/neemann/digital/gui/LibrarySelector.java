/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui;

import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.library.LibraryListener;
import de.neemann.digital.draw.library.LibraryNode;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.gui.components.CircuitComponent;
import de.neemann.digital.lang.Lang;
import de.neemann.gui.ErrorMessage;
import de.neemann.gui.ToolTipAction;
import de.tobihxd.KeybindManager;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The LibrarySelector is responsible for building the menu used to select items for adding them to the circuit.
 */
public class LibrarySelector implements LibraryListener {
    private final ElementLibrary library;
    private final ShapeFactory shapeFactory;
    private final KeybindManager keybinds;
    private final List<InsertAction> insertActions = new ArrayList<>();
    private JMenu componentsMenu;
    private InsertHistory insertHistory;
    private CircuitComponent circuitComponent;
    private final MainGui main;

    /**
     * Creates a new library selector.
     * the elementState is used to set the window to the elementEdit mode if a new element is added to the circuit.
     *
     * @param library      the library to select elements from
     * @param shapeFactory The shape factory
     * @param main         The main method creating this object
     * @param keybinds     The owner of the component shortcut settings
     */
    public LibrarySelector(ElementLibrary library, ShapeFactory shapeFactory, MainGui main, KeybindManager keybinds) {
        this.main = main;
        this.library = library;
        this.shapeFactory = shapeFactory;
        this.keybinds = keybinds;
    }

    /**
     * Builds the menu which is added to the menu bar.
     * If an item is selected the state is set to the edit element state and the new element is added
     * to the circuitComponent.
     *
     * @param insertHistory    the insert history is used to add selected parts to the tool bar
     * @param circuitComponent the used circuit component
     * @return the menu to ad to the menu bar
     */
    public JMenu buildMenu(InsertHistory insertHistory, CircuitComponent circuitComponent) {
        this.insertHistory = insertHistory;
        this.circuitComponent = circuitComponent;
        componentsMenu = new JMenu(Lang.get("menu_elements"));
        libraryChanged(null);
        keybinds.addSelector(this);

        return componentsMenu;
    }

    @Override
    public void libraryChanged(LibraryNode node) {
        for (InsertAction action : insertActions)
            action.setComponentShortcut(null);
        insertActions.clear();
        componentsMenu.removeAll();

        for (LibraryNode n : library.getRoot())
            addComponents(componentsMenu, n);

        updateKeyBinds();

        if (library.getCustomNode() != null) {
            JMenuItem m = componentsMenu.getItem(componentsMenu.getItemCount() - 1);
            if (m instanceof JMenu menu) {
                menu.addSeparator();
                menu.add(new ToolTipAction(Lang.get("menu_update")) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        try {
                            library.updateEntries();
                        } catch (IOException ex) {
                            SwingUtilities.invokeLater(new ErrorMessage(Lang.get("msg_errorUpdatingLibrary")).addCause(ex));
                        }
                    }
                }.setToolTip(Lang.get("menu_update_tt")).createJMenuItem());
            }
        }
    }

    private void addComponents(JMenu parts, LibraryNode node) {
        if (node.isLeaf()) {
            if (!node.isHidden()) {
                InsertAction insertAction = new InsertAction(node, insertHistory, circuitComponent, shapeFactory);

                JMenuItem jMenuItem = insertAction.createJMenuItem();

                insertActions.add(insertAction);
                parts.add(jMenuItem);
            }
        } else {
            JMenu subMenu = new JMenu(node.getName());
            for (LibraryNode child : node)
                addComponents(subMenu, child);
            parts.add(subMenu);
        }
    }

    /** Applies the current settings to the existing actions, using element identity. */
    public void updateKeyBinds() {
        for (InsertAction action : insertActions)
            action.setComponentShortcut(null);
        Map<String, String> bindings = keybinds.getKeyBinds();
        Set<String> conflicts = getConflictingKeyBinds(bindings);
        for (InsertAction action : insertActions) {
            if (!action.isCustom() && !conflicts.contains(action.getName()))
                action.setComponentShortcut(KeybindManager.parseKeyStroke(bindings.get(action.getName())));
        }
    }

    /** Finds collisions with shortcuts owned by the editor or its menus. */
    public Set<String> getConflictingKeyBinds(Map<String, String> bindings) {
        Set<KeyStroke> reserved = new LinkedHashSet<>();
        KeyStroke[] strokes = circuitComponent.getInputMap().allKeys();
        if (strokes != null) {
            for (KeyStroke stroke : strokes) {
                Object action = circuitComponent.getInputMap().get(stroke);
                if (!insertActions.contains(action))
                    reserved.add(stroke);
            }
        }
        if (main != null && main.getJMenuBar() != null) {
            for (int i = 0; i < main.getJMenuBar().getMenuCount(); i++)
                addReservedMenuShortcuts(main.getJMenuBar().getMenu(i), reserved);
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            if (reserved.contains(KeybindManager.parseKeyStroke(binding.getValue())))
                conflicts.add(binding.getKey());
        }
        return conflicts;
    }

    private void addReservedMenuShortcuts(JMenu menu, Set<KeyStroke> reserved) {
        if (menu == null || menu == componentsMenu)
            return;
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item instanceof JMenu)
                addReservedMenuShortcuts((JMenu) item, reserved);
            else if (item != null && item.getAccelerator() != null)
                reserved.add(item.getAccelerator());
        }
    }

    /** Releases the closed editor's shortcut registrations. */
    public void dispose() {
        keybinds.removeSelector(this);
        for (InsertAction action : insertActions)
            action.setComponentShortcut(null);
        insertActions.clear();
    }
}
