package com.remoteterm.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remoteterm.auth.AuthManager;
import com.remoteterm.files.FileHandler;
import com.remoteterm.protocol.MessageType;
import com.remoteterm.terminal.PtyManager;
import com.remoteterm.terminal.PtyOutputListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Component
public class TerminalSocketHandler extends TextWebSocketHandler implements PtyOutputListener {

    private static final Logger log = LoggerFactory.getLogger(TerminalSocketHandler.class);

    private final AuthManager authManager;
    private final PtyManager ptyManager;
    private final FileHandler fileHandler;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());

    public TerminalSocketHandler(AuthManager authManager, PtyManager ptyManager) {
        this.authManager = authManager;
        this.ptyManager = ptyManager;
        String workspaceRoot = resolveWorkspace();
        this.fileHandler = new FileHandler(Path.of(workspaceRoot));
        this.ptyManager.setListener(this);
    }

    // ---- PtyOutputListener ----

    @Override
    public void onOutput(String base64Data) {
        broadcast(mapOf("type", MessageType.TERM_OUTPUT, "data", base64Data));
    }

    @Override
    public void onExit() {
        broadcast(mapOf("type", MessageType.TERM_OUTPUT, "data",
                Base64.getEncoder().encodeToString(
                        "\r\n[PTY process exited]\r\n".getBytes())));
    }

    // ---- WebSocket lifecycle ----

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
            String type = (String) msg.get("type");
            if (type == null) return;

            // Auth check
            boolean authed = Boolean.TRUE.equals(session.getAttributes().get("authed"));
            if (!authed && !MessageType.AUTH.equals(type)) {
                send(session, mapOf("type", MessageType.AUTH, "ok", false,
                        "error", "Authentication required"));
                return;
            }

            switch (type) {
                case MessageType.AUTH -> handleAuth(session, msg);
                case MessageType.TERM_INIT -> handleTermInit(session, msg);
                case MessageType.TERM_INPUT -> handleTermInput(msg);
                case MessageType.TERM_RESIZE -> handleTermResize(msg);
                case MessageType.FS_LIST -> handleFsList(session, msg);
                case MessageType.FS_READ -> handleFsRead(session, msg);
                case MessageType.FS_WRITE -> handleFsWrite(session, msg);
                case MessageType.FS_DELETE -> handleFsDelete(session, msg);
                case MessageType.FS_MKDIR -> handleFsMkdir(session, msg);
                default -> log.debug("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error handling message", e);
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            send(session, mapOf("type", "error", "message", errMsg));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WS disconnected: {} ({})", session.getId(), status);
    }

    // ---- Auth ----

    private void handleAuth(WebSocketSession session, Map<String, Object> msg) {
        String token = (String) msg.get("token");
        if (authManager.validate(token)) {
            session.getAttributes().put("authed", true);
            sessions.add(session);
            send(session, mapOf("type", MessageType.AUTH, "ok", true));
            log.info("Auth success: {}", session.getId());
        } else {
            send(session, mapOf("type", MessageType.AUTH, "ok", false,
                    "error", "Invalid token"));
        }
    }

    // ---- Terminal ----

    private void handleTermInit(WebSocketSession session, Map<String, Object> msg) {
        int cols = intVal(msg, "cols", 80);
        int rows = intVal(msg, "rows", 24);
        boolean fresh = Boolean.TRUE.equals(msg.get("fresh"));

        if (!ptyManager.isAlive()) {
            try {
                String shell = System.getenv().getOrDefault("SHELL", "/bin/zsh");
                String workspace = resolveWorkspace();
                ptyManager.spawn(shell, Path.of(workspace), cols, rows);
                // New PTY, no replay needed (fresh is implicit)
            } catch (IOException e) {
                send(session, mapOf("type", "error", "message",
                        "Failed to spawn PTY: " + e.getMessage()));
                return;
            }
        } else {
            ptyManager.resize(cols, rows);
        }

        // Always replay ring buffer content if available (both reconnect and new device join)
        if (ptyManager.isAlive()) {
            byte[] replay = ptyManager.getReplayData();
            if (replay.length > 0) {
                String b64 = Base64.getEncoder().encodeToString(replay);
                send(session, mapOf("type", MessageType.TERM_REPLAY, "data", b64));
            }
        }
    }

    private void handleTermInput(Map<String, Object> msg) {
        String data = (String) msg.get("data");
        if (data != null && ptyManager.isAlive()) {
            try {
                ptyManager.write(Base64.getDecoder().decode(data));
            } catch (IOException e) {
                log.error("PTY write error", e);
            }
        }
    }

    private void handleTermResize(Map<String, Object> msg) {
        int cols = intVal(msg, "cols", 80);
        int rows = intVal(msg, "rows", 24);
        ptyManager.resize(cols, rows);
    }

    // ---- File operations ----

    private void handleFsList(WebSocketSession session, Map<String, Object> msg) {
        String path = strVal(msg, "path", ".");
        List<Map<String, Object>> files = fileHandler.list(path);
        send(session, mapOf("type", MessageType.FS_LIST, "path", path,
                "files", files, "error", (Object) null));
    }

    private void handleFsRead(WebSocketSession session, Map<String, Object> msg) {
        String path = strVal(msg, "path", ".");
        send(session, fileHandler.read(path));
    }

    private void handleFsWrite(WebSocketSession session, Map<String, Object> msg) {
        String path = strVal(msg, "path", "");
        String content = strVal(msg, "content", "");
        send(session, fileHandler.write(path, content));
    }

    private void handleFsDelete(WebSocketSession session, Map<String, Object> msg) {
        String path = strVal(msg, "path", "");
        send(session, fileHandler.delete(path));
    }

    private void handleFsMkdir(WebSocketSession session, Map<String, Object> msg) {
        String path = strVal(msg, "path", "");
        send(session, fileHandler.mkdir(path));
    }

    // ---- Helpers ----

    private void send(WebSocketSession session, Object obj) {
        if (!session.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(obj);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Send error", e);
        }
    }

    private void broadcast(Object obj) {
        String json;
        try {
            json = mapper.writeValueAsString(obj);
        } catch (IOException e) {
            log.error("JSON error", e);
            return;
        }
        TextMessage tm = new TextMessage(json);
        synchronized (sessions) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    try {
                        synchronized (s) {
                            s.sendMessage(tm);
                        }
                    } catch (IOException e) {
                        log.debug("Broadcast to {} failed", s.getId());
                    }
                }
            }
        }
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    private static String strVal(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static String resolveWorkspace() {
        String ws = System.getProperty("remote-terminal.workspace-root");
        if (ws != null && !ws.isBlank()) return ws;
        ws = System.getenv("TERM_WORKSPACE");
        if (ws != null && !ws.isBlank()) return ws;
        return System.getProperty("user.home");
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
