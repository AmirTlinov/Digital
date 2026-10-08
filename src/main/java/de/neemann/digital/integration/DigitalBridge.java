/*
 * Use of this source code is governed by the GPL v3 license in LICENSE.
 */
package de.neemann.digital.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local authenticated transport. All editor commands execute on the existing Swing owner. */
public final class DigitalBridge {
    private static DigitalBridge instance;
    private final EditorCommands commands = new EditorCommands();
    private final Path descriptor;
    private final String token;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final HttpServer server;
    private final ExecutorService requests;

    private DigitalBridge() throws IOException {
        Path directory = Path.of(System.getProperty("user.home"), ".digital");
        Files.createDirectories(directory);
        descriptor = directory.resolve("codex-bridge.json");
        lockChannel = FileChannel.open(directory.resolve("codex-bridge.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            throw new IOException("Another Digital process owns the Codex bridge");
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        } catch (IOException | RuntimeException e) {
            try {
                lock.release();
                lockChannel.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        requests = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Digital Codex bridge");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(requests);
        server.createContext("/health", this::health);
        server.createContext("/command", this::command);
        try {
            server.start();
            JSONObject data = new JSONObject()
                    .put("protocol", 1).put("application", "Digital")
                    .put("pid", ProcessHandle.current().pid())
                    .put("port", server.getAddress().getPort()).put("token", token);
            Path temporary = Files.createTempFile(directory, ".codex-bridge-", ".tmp");
            try {
                if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(temporary, data.toString(), StandardCharsets.UTF_8);
                Files.move(temporary, descriptor, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "Digital Codex shutdown"));
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Called once after native startup; the bridge does not own a second application or model. */
    public static synchronized void start() {
        if (instance == null) {
            try {
                instance = new DigitalBridge();
            } catch (IOException | RuntimeException e) {
                System.err.println("Digital Codex bridge: " + e.getMessage());
            }
        }
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] actual = (authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            error(exchange, 401, "unauthorized", "Digital bridge authentication failed");
            return false;
        }
        return true;
    }

    private void health(HttpExchange exchange) throws IOException {
        try {
            if (!authorize(exchange)) return;
            if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/health")) {
                error(exchange, 405, "invalid_request", "Use GET /health");
                return;
            }
            respond(exchange, 200, new JSONObject().put("protocol", 1)
                    .put("application", "Digital").put("pid", ProcessHandle.current().pid()));
        } finally {
            exchange.close();
        }
    }

    private void command(HttpExchange exchange) throws IOException {
        try {
            if (!authorize(exchange)) return;
            if (!exchange.getRequestMethod().equals("POST") || !exchange.getRequestURI().getPath().equals("/command")) {
                error(exchange, 405, "invalid_request", "Use POST /command");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
            if (body.length > 1_048_576) {
                error(exchange, 413, "invalid_request", "Command exceeds 1 MiB");
                return;
            }
            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String name = request.getString("command");
            JSONObject args = request.optJSONObject("args");
            if (args == null) args = new JSONObject();
            final JSONObject arguments = args;
            FutureTask<JSONObject> task = new FutureTask<>(() -> commands.execute(name, arguments));
            SwingUtilities.invokeLater(task);
            try {
                respond(exchange, 200, task.get(15, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                task.cancel(false);
                error(exchange, 504, "execution_timeout", "Inspect the circuit before retrying: command completion is uncertain");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                String code = cause instanceof IllegalStateException ? "state_conflict"
                        : cause instanceof IllegalArgumentException ? "invalid_request"
                        : cause instanceof IOException ? "io_error" : "command_failed";
                error(exchange, 400, code, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            } catch (InterruptedException e) {
                task.cancel(false);
                Thread.currentThread().interrupt();
                error(exchange, 503, "execution_interrupted", "Inspect the circuit before issuing another command");
            }
        } catch (org.json.JSONException | IllegalArgumentException e) {
            error(exchange, 400, "invalid_request", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        respond(exchange, status, new JSONObject().put("error", new JSONObject().put("code", code).put("message", message)));
    }

    private static void respond(HttpExchange exchange, int status, JSONObject data) throws IOException {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void close() {
        server.stop(0);
        requests.shutdownNow();
        try {
            if (Files.exists(descriptor)) {
                JSONObject current = new JSONObject(Files.readString(descriptor));
                if (token.equals(current.optString("token"))) Files.deleteIfExists(descriptor);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Digital Codex bridge cleanup: " + e.getMessage());
        } finally {
            try {
                lock.release();
            } catch (IOException e) {
                System.err.println("Digital Codex lock cleanup: " + e.getMessage());
            }
            try {
                lockChannel.close();
            } catch (IOException e) {
                System.err.println("Digital Codex channel cleanup: " + e.getMessage());
            }
        }
    }
}
