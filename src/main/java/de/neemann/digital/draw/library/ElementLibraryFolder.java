/*
 * Copyright (c) 2017 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.library;

import de.neemann.digital.lang.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Handles a single folder
 */
public class ElementLibraryFolder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElementLibraryFolder.class);
    private static final int MAX_FILES_TO_SCAN = 5000;
    private static final int MAX_MENU_SIZE = 12;

    private final LibraryNode root;
    private final String menuTitle;
    private LibraryNode node;

    /**
     * Create a new folder manager.
     *
     * @param root      the root node
     * @param menuTitle string to show in menu
     */
    public ElementLibraryFolder(LibraryNode root, String menuTitle) {
        this.root = root;
        this.menuTitle = menuTitle;
    }

    /**
     * @return the managed node
     */
    public LibraryNode getNode() {
        return node;
    }

    /**
     * Scans the given folder
     *
     * @param path      the path to scan
     * @param isLibrary true if this is the library
     * @return the node which has changed
     * @throws IOException if the directory cannot be read
     */
    public LibraryNode scanFolder(File path, boolean isLibrary) throws IOException {
        return publish(scan(path, isLibrary, () -> false));
    }

    /** Reads a detached tree without changing the live library. */
    LibraryNode scan(File path, boolean isLibrary, BooleanSupplier cancelled) throws IOException {
        if (path == null)
            return null;
        LibraryNode snapshot = new LibraryNode(menuTitle);
        ScanCounter scanCounter = new ScanCounter();
        scanFolder(path, snapshot, scanCounter, isLibrary, cancelled);
        LOGGER.debug("found " + scanCounter.getCircuitCounter() + " files in " + path);
        return snapshot;
    }

    /** Publishes a completed scan on the library owner's thread. */
    LibraryNode publish(LibraryNode snapshot) {
        if (snapshot == null) {
            if (node == null)
                return null;
            root.remove(node);
            node = null;
            return root;
        }
        if (node == null) {
            node = snapshot;
            root.add(node);
            return root;
        }
        node.removeAll();
        for (LibraryNode child : snapshot)
            node.add(child);
        return node;
    }

    private static void scanFolder(File path, LibraryNode node, ScanCounter scanCounter,
                                   boolean isLibrary, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        if (scanCounter.getFileCounter() >= MAX_FILES_TO_SCAN)
            return;
        File[] list = path.listFiles();
        checkCancelled(cancelled);
        if (list == null)
            throw new IOException("Cannot read library folder: " + path);
        ArrayList<File> orderedList = new ArrayList<>(Arrays.asList(list));
        orderedList.sort((f1, f2) -> NumStringComparator.compareStr(f1.getName(), f2.getName()));
        ArrayList<File> fileList = new ArrayList<>();
        for (File f : orderedList) {
            checkCancelled(cancelled);
            if (scanCounter.getFileCounter() >= MAX_FILES_TO_SCAN)
                break;
            scanCounter.incFile();
            if (f.isDirectory() && !f.isHidden()) {
                LibraryNode n = new LibraryNode(f.getName());
                scanFolder(f, n, scanCounter, isLibrary, cancelled);
                if (!n.isEmpty())
                    node.add(n);
            } else if (f.isFile() && f.getName().endsWith(".dig")) {
                fileList.add(f);
                scanCounter.incCircuit();
            }
        }

        if (fileList.size() <= MAX_MENU_SIZE + 1) {
            for (File f : fileList)
                node.add(new LibraryNode(f, isLibrary));
        } else {
            for (int i = 0; i < MAX_MENU_SIZE; i++)
                node.add(new LibraryNode(fileList.get(i), isLibrary));

            final int size = fileList.size() - MAX_MENU_SIZE;
            int subMenus = (size - 1) / MAX_MENU_SIZE + 1;
            int delta = (size - 1) / subMenus + 1;

            int pos = MAX_MENU_SIZE;
            while (pos < fileList.size()) {
                int pos2 = pos + delta;
                if (pos2 > fileList.size())
                    pos2 = fileList.size();

                String name;
                if (subMenus > 1)
                    name = clean(fileList.get(pos)) + " - " + clean(fileList.get(pos2 - 1));
                else
                    name = Lang.get("lib_more");

                LibraryNode n = new LibraryNode(name);
                node.add(n);
                for (int p = pos; p < pos2; p++)
                    n.add(new LibraryNode(fileList.get(p), isLibrary));

                pos = pos2;
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new CancellationException("Library scan superseded");
    }

    private static String clean(File file) {
        String s = file.getName();
        if (s.endsWith(".dig"))
            s = s.substring(0, s.length() - 4);
        return s;
    }

    private static final class ScanCounter {
        private int fileCounter;
        private int circuitCounter;

        private void incFile() {
            fileCounter++;
        }

        private int getFileCounter() {
            return fileCounter;
        }

        private void incCircuit() {
            circuitCounter++;
        }

        private int getCircuitCounter() {
            return circuitCounter;
        }
    }
}
