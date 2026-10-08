package de.neemann.digital.draw.library;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.shapes.MissingShape;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ElementLibraryScanTest extends TestCase {
    private Path temporary;

    @Override
    protected void setUp() throws Exception {
        temporary = Files.createTempDirectory("digital-library-scan-");
    }

    @Override
    protected void tearDown() throws Exception {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    public void testSynchronousCallerStillResolvesCustomComponents() throws Exception {
        File folder = folder("sync", "and.dig");
        ElementLibrary library = library();
        library.setRootFilePath(folder);
        assertFalse(library.isScanPending());
        assertNull(library.getScanError());
        assertTrue(library.getElementType("and.dig") instanceof ElementTypeDescriptionCustom);
    }

    public void testPendingScanDoesNotBlockOrRepeatAndNestedShapeRecovers() throws Exception {
        ControlledFolder folder = new ControlledFolder(folder("nested", "and.dig"));
        ElementLibrary library = library();
        Circuit circuit = Circuit.loadCircuit(new File(Resources.getRoot(), "dig/nestedAnd.dig"), library.getShapeFactory());
        VisualElement nested = circuit.getElements(e -> e.getElementName().equals("and.dig")).get(0);
        AtomicBoolean offEdtNotification = new AtomicBoolean();
        library.addListener(node -> {
            offEdtNotification.compareAndSet(false, !SwingUtilities.isEventDispatchThread());
            circuit.clearState();
        });
        try {
            edt(() -> library.setRootFilePath(folder));
            assertTrue(folder.started.await(3, TimeUnit.SECONDS));
            edt(() -> {
                assertTrue(library.isScanPending());
                assertTrue(nested.getShape() instanceof MissingShape);
                for (int i = 0; i < 5; i++) {
                    try {
                        library.getElementType("unknown.dig");
                        fail("An unknown component was resolved");
                    } catch (ElementNotFoundException expected) {
                        // Pending lookups must never start another directory enumeration.
                    }
                    library.updateEntries();
                }
            });
            assertEquals(1, folder.calls.get());
            assertFalse(folder.ioOnEdt.get());
            folder.release.countDown();
            awaitScan(library);
            edt(() -> {
                assertNull(library.getScanError());
                assertTrue(library.getElementType("and.dig") instanceof ElementTypeDescriptionCustom);
                assertFalse(nested.getShape() instanceof MissingShape);
                assertEquals(3, nested.getPins().size());
            });
            assertFalse(offEdtNotification.get());
        } finally {
            folder.release.countDown();
            library.cancelPendingScan();
        }
    }

    public void testRootChangeAndClearDiscardOldNamesAndLateScan() throws Exception {
        ElementLibrary library = library();
        library.setRootFilePath(folder("initial", "initial.dig"));
        ControlledFolder old = new ControlledFolder(folder("old", "old.dig"));
        File current = folder("current", "current.dig");
        try {
            edt(() -> library.setRootFilePath(old));
            assertTrue(old.started.await(3, TimeUnit.SECONDS));
            edt(() -> {
                assertNull(library.getElementNodeOrNull("initial.dig"));
                library.setRootFilePath(null);
                assertFalse(library.isScanPending());
                assertNull(library.getCustomNode());
                library.setRootFilePath(current);
            });
            awaitScan(library);
            old.release.countDown();
            assertTrue(old.finished.await(3, TimeUnit.SECONDS));
            edt(() -> {
                assertFalse(library.isScanPending());
                assertEquals(current, library.getRootFilePath());
                assertNotNull(library.getElementNodeOrNull("current.dig"));
                assertNull(library.getElementNodeOrNull("old.dig"));
                assertNull(library.getElementNodeOrNull("initial.dig"));
                assertNull(library.getScanError());
            });
        } finally {
            old.release.countDown();
            library.cancelPendingScan();
        }
    }

    public void testReadFailureIsVisibleRetainsCurrentEntriesAndCanBeRetried() throws Exception {
        AtomicBoolean denied = new AtomicBoolean();
        File readable = folder("denied", "and.dig");
        File folder = new File(readable.getPath()) {
            @Override
            public File[] listFiles() {
                return denied.get() ? null : super.listFiles();
            }
        };
        ElementLibrary library = library();
        library.setRootFilePath(folder);
        denied.set(true);
        edt(library::updateEntries);
        awaitScan(library);
        edt(() -> {
            assertNotNull(library.getScanError());
            assertNotNull(library.getElementNodeOrNull("and.dig"));
            assertNotNull(library.getCustomNode());
        });
        denied.set(false);
        edt(library::updateEntries);
        awaitScan(library);
        edt(() -> {
            assertNull(library.getScanError());
            assertTrue(library.getElementType("and.dig") instanceof ElementTypeDescriptionCustom);
        });
    }

    public void testFileSavedDuringScanGetsOneCoalescedRescan() throws Exception {
        ControlledFolder folder = new ControlledFolder(folder("changed", "and.dig"));
        folder.captureBeforeRelease = true;
        ElementLibrary library = library();
        try {
            edt(() -> library.setRootFilePath(folder));
            assertTrue(folder.started.await(3, TimeUnit.SECONDS));
            Path added = folder.toPath().resolve("added.dig");
            Files.copy(new File(Resources.getRoot(), "dig/and.dig").toPath(), added);
            edt(() -> {
                library.invalidateElement(added.toFile());
                library.updateEntries();
                library.updateEntries();
            });
            assertEquals(1, folder.calls.get());
            folder.release.countDown();
            awaitScan(library);
            edt(() -> assertTrue(library.getElementType("added.dig") instanceof ElementTypeDescriptionCustom));
            assertEquals(2, folder.calls.get());
        } finally {
            folder.release.countDown();
            library.cancelPendingScan();
        }
    }

    private ElementLibrary library() {
        ElementLibrary library = new ElementLibrary();
        new ShapeFactory(library);
        return library;
    }

    private File folder(String name, String component) throws Exception {
        Path folder = Files.createDirectory(temporary.resolve(name));
        Files.copy(new File(Resources.getRoot(), "dig/and.dig").toPath(), folder.resolve(component));
        return folder.toFile();
    }

    private static void awaitScan(ElementLibrary library) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AtomicBoolean idle = new AtomicBoolean();
            edt(() -> idle.set(!library.isScanPending()));
            if (idle.get())
                return;
            Thread.sleep(5);
        }
        fail("Scan did not complete");
    }

    private static void edt(CheckedRunnable operation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                operation.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() instanceof Exception)
            throw (Exception) failure.get();
        if (failure.get() instanceof Error)
            throw (Error) failure.get();
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class ControlledFolder extends File {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean ioOnEdt = new AtomicBoolean();
        private boolean captureBeforeRelease;

        private ControlledFolder(File folder) {
            super(folder.getPath());
        }

        @Override
        public File[] listFiles() {
            calls.incrementAndGet();
            ioOnEdt.set(SwingUtilities.isEventDispatchThread());
            File[] snapshot = captureBeforeRelease ? super.listFiles() : null;
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Directory scan was not released");
                return captureBeforeRelease ? snapshot : super.listFiles();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                finished.countDown();
            }
        }
    }
}
