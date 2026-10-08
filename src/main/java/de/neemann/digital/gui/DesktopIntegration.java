/*
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui;

import de.neemann.gui.ClosingWindowListener;

import javax.swing.SwingUtilities;
import javax.swing.JFrame;
import java.awt.Desktop;
import java.awt.Frame;

/** Bridges native document and quit events to the existing editor owners. */
final class DesktopIntegration {
    private DesktopIntegration() {
    }

    static void install() {
        if (!Desktop.isDesktopSupported())
            return;
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler(event -> SwingUtilities.invokeLater(() -> {
                event.getFiles().forEach(MainGui::openDesktopFile);
                if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))
                    desktop.requestForeground(false);
            }));
        }
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler((event, response) -> SwingUtilities.invokeLater(() -> {
                for (Frame frame : Frame.getFrames()) {
                    if (frame.isDisplayable() && frame instanceof JFrame && frame instanceof ClosingWindowListener.ConfirmSave) {
                        if (!ClosingWindowListener.checkForSave((JFrame) frame,
                                (ClosingWindowListener.ConfirmSave) frame)) {
                            response.cancelQuit();
                            return;
                        }
                    }
                }
                response.performQuit();
            }));
        }
    }
}
