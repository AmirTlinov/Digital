/*
 * Copyright (c) 2018 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.elements;


import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.io.In;
import de.neemann.digital.core.io.InValue;
import de.neemann.digital.core.memory.DataField;
import de.neemann.digital.core.memory.ROM;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

public class CircuitTest extends TestCase {

    private static Circuit createCircuit() {
        Circuit c = new Circuit();

        // add a ROM
        final DataField data = new DataField(4);
        data.setData(0, 0xffff0000ffff0000L);
        data.setData(1, 0x8fff0000ffff0000L);
        final VisualElement rom = new VisualElement(ROM.DESCRIPTION.getName())
                .setAttribute(Keys.DATA, data);
        c.add(rom);

        // add an input
        final VisualElement in = new VisualElement(In.DESCRIPTION.getName())
                .setAttribute(Keys.INPUT_DEFAULT, new InValue(0x8fff0000ffff0000L));
        c.add(in);

        return c;
    }

    public void test64Bit() throws IOException {
        Circuit c = createCircuit();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.save(baos);

        // check ROM
        c = Circuit.loadCircuit(new ByteArrayInputStream(baos.toByteArray()), new ShapeFactory(new ElementLibrary()));
        VisualElement rom = c.getElements().get(0);
        DataField d = rom.getElementAttributes().get(Keys.DATA);
        assertEquals(0xffff0000ffff0000L, d.getDataWord(0));
        assertEquals(0x8fff0000ffff0000L, d.getDataWord(1));

        // check input
        VisualElement in = c.getElements().get(1);
        assertEquals(0x8fff0000ffff0000L, in.getElementAttributes().get(Keys.INPUT_DEFAULT).getValue());
    }

    public void testFileSaveRoundtripAndPermissions() throws IOException {
        Path directory = Files.createTempDirectory("digital-circuit-save-");
        Path file = directory.resolve("circuit.dig");
        try {
            Circuit circuit = createCircuit();
            circuit.save(file.toFile());
            Set<PosixFilePermission> permissions = null;
            if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
                permissions = PosixFilePermissions.fromString("rw-r-----");
                Files.setPosixFilePermissions(file, permissions);
            }

            circuit.getElements().get(1).setAttribute(Keys.INPUT_DEFAULT, new InValue(123));
            circuit.save(file.toFile());
            Circuit loaded = Circuit.loadCircuit(file.toFile(), new ShapeFactory(new ElementLibrary()));
            DataField data = loaded.getElements().get(0).getElementAttributes().get(Keys.DATA);
            assertEquals(0xffff0000ffff0000L, data.getDataWord(0));
            assertEquals(0x8fff0000ffff0000L, data.getDataWord(1));
            assertEquals(123, loaded.getElements().get(1).getElementAttributes().get(Keys.INPUT_DEFAULT).getValue());
            if (permissions != null)
                assertEquals(permissions, Files.getPosixFilePermissions(file));
            try (Stream<Path> entries = Files.list(directory)) {
                assertEquals(1L, entries.count());
            }
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    public void testFailedSaveKeepsPreviousFile() throws IOException {
        Path directory = Files.createTempDirectory("digital-circuit-save-");
        Path file = directory.resolve("circuit.dig");
        try {
            createCircuit().save(file.toFile());
            byte[] previous = Files.readAllBytes(file);
            IOException failure = new IOException("injected write failure");
            Circuit circuit = new Circuit() {
                @Override
                public void save(OutputStream out) throws IOException {
                    out.write("partial circuit".getBytes(StandardCharsets.UTF_8));
                    throw failure;
                }
            };

            try {
                circuit.save(file.toFile());
                fail("Save should fail");
            } catch (IOException e) {
                assertSame(failure, e);
            }
            assertTrue(Arrays.equals(previous, Files.readAllBytes(file)));
            try (Stream<Path> entries = Files.list(directory)) {
                assertEquals(1L, entries.count());
            }
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

}
