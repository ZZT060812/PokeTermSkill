package com.remoteterm.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class PtyManager {

    private static final Logger log = LoggerFactory.getLogger(PtyManager.class);
    private static final int RING_SIZE = 500;

    // ANSI escape sequences: CSI, OSC, charset select, cursor save/restore, window title
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\[[0-9;]*[a-zA-Z]" +
            "|\\].*?(?:|\\\\)" +
            "|[()][0-9AB]" +
            "|\\[[?][0-9;]*[a-zA-Z]" +
            "|\\]0;.*??" +
            "|[78]" +       // DEC save/restore cursor
            "|=" +          // application keypad
            "|>" +          // normal keypad
            "|"             // BEL
    );

    private PtyProcess pty;
    private PtyOutputListener listener;
    private final byte[][] ringBuffer = new byte[RING_SIZE][];
    private int ringHead;
    private int ringCount;
    private volatile boolean alive;
    private Thread pollingThread;
    private String tmuxSession;
    // Pure debounce: only flush after pane stabilizes (no changes for SETTLE_MS)
    private String baseline = "";
    private String latest = "";
    private long lastChangeMs = 0;
    private static final long SETTLE_MS = 250;
    private static final int POLL_INTERVAL_MS = 200;

    public void setListener(PtyOutputListener listener) {
        this.listener = listener;
    }

    public synchronized void spawn(String shell, Path workDir, int cols, int rows) throws IOException {
        if (alive) return;

        tmuxSession = System.getenv("TERM_TMUX_SESSION");
        if (tmuxSession != null && tmuxSession.isBlank()) tmuxSession = null;

        if (tmuxSession != null) {
            spawnWithTmuxPolling(shell, workDir);
        } else {
            spawnWithPty(shell, workDir, cols, rows);
        }
        alive = true;
    }

    /** Tmux mode: poll capture-pane with debounce — only flush after output stabilizes. */
    private void spawnWithTmuxPolling(String shell, Path workDir) throws IOException {
        log.info("Tmux polling mode: session '{}'", tmuxSession);
        String init = capturePane();
        baseline = init != null ? stripAnsi(init.getBytes()) : "";
        latest = baseline;
        lastChangeMs = System.currentTimeMillis();

        pollingThread = Thread.ofVirtual().name("tmux-poller").start(() -> {
            while (alive) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                    String current = capturePane();
                    if (current == null) continue;
                    String clean = stripAnsi(current.getBytes());

                    if (!clean.equals(latest)) {
                        latest = clean;
                        lastChangeMs = System.currentTimeMillis();
                    }

                    // Flush only after pane has stabilized — skip intermediate redraws
                    long elapsed = System.currentTimeMillis() - lastChangeMs;
                    if (!latest.equals(baseline) && elapsed >= SETTLE_MS) {
                        String diff = diff(baseline, latest);
                        baseline = latest;
                        if (!diff.isEmpty()) {
                            PtyOutputListener l = listener;
                            if (l != null) l.onOutput(diff);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    if (alive) log.debug("Poll error: {}", e.getMessage());
                }
            }
        });
    }

    /** Flush any pending debounced output immediately (called before sending a command). */
    public synchronized void flushPending() {
        if (!latest.equals(baseline)) {
            String diff = diff(baseline, latest);
            baseline = latest;
            if (!diff.isEmpty()) {
                PtyOutputListener l = listener;
                if (l != null) l.onOutput(diff);
            }
        }
    }

    private String capturePane() {
        try {
            Process p = new ProcessBuilder(
                    findTmux(), "capture-pane", "-p", "-t", tmuxSession, "-e")
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("capture-pane failed: {}", e.getMessage());
            return null;
        }
    }

    /** Diff: extract only the truly new content between old and current.
     *  Uses longest-common-prefix + longest-common-suffix to handle mid-block insertions. */
    private static String diff(String old, String current) {
        if (current.startsWith(old)) {
            return current.substring(old.length());
        }

        int minLen = Math.min(old.length(), current.length());

        // Longest common prefix
        int prefix = 0;
        while (prefix < minLen && old.charAt(prefix) == current.charAt(prefix)) {
            prefix++;
        }

        // Longest common suffix (from the end, within un-matched region)
        int suffix = 0;
        int maxSuffix = minLen - prefix;
        while (suffix < maxSuffix
                && old.charAt(old.length() - 1 - suffix) == current.charAt(current.length() - 1 - suffix)) {
            suffix++;
        }

        return current.substring(prefix, current.length() - suffix);
    }

    /** Direct-shell mode: spawn PTY and read stream. */
    private void spawnWithPty(String shell, Path workDir, int cols, int rows) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        pty = PtyProcess.exec(new String[]{shell}, env, workDir.toString());
        pty.setWinSize(new WinSize(cols, rows));

        Thread.ofVirtual().name("pty-reader").start(() -> {
            try {
                byte[] buf = new byte[8192];
                InputStream in = pty.getInputStream();
                int n;
                while ((n = in.read(buf)) != -1) {
                    byte[] chunk = Arrays.copyOf(buf, n);
                    synchronized (PtyManager.this) {
                        ringBuffer[ringHead] = chunk;
                        ringHead = (ringHead + 1) % RING_SIZE;
                        if (ringCount < RING_SIZE) ringCount++;
                    }
                    PtyOutputListener l = listener;
                    if (l != null) {
                        String clean = stripAnsi(chunk);
                        if (!clean.isEmpty()) l.onOutput(clean);
                    }
                }
            } catch (IOException e) {
                if (alive) log.error("PTY read error", e);
            } finally {
                alive = false;
                PtyOutputListener l = listener;
                if (l != null) l.onExit();
            }
        });

        log.info("PTY spawned: shell={}, workDir={}", shell, workDir);
    }

    public synchronized void write(byte[] data) throws IOException {
        if (tmuxSession != null) {
            String cmd = new String(data, StandardCharsets.UTF_8).stripTrailing();
            if (!cmd.isEmpty()) sendCommand(cmd);
            return;
        }
        if (pty == null || !alive) return;
        pty.getOutputStream().write(data);
        pty.getOutputStream().flush();
    }

    /** Send a raw key press (for interactive TUIs). */
    public synchronized void sendKey(String key) throws IOException {
        if (!alive) return;
        flushPending();
        if (tmuxSession != null) {
            try {
                new ProcessBuilder(findTmux(), "send-keys", "-t", tmuxSession, key)
                        .start().waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (pty != null) {
            // Map key names to escape sequences for direct PTY
            switch (key) {
                case "Up" -> pty.getOutputStream().write("\033[A".getBytes(StandardCharsets.UTF_8));
                case "Down" -> pty.getOutputStream().write("\033[B".getBytes(StandardCharsets.UTF_8));
                case "Enter" -> pty.getOutputStream().write("\n".getBytes(StandardCharsets.UTF_8));
                case "Escape" -> pty.getOutputStream().write("\033".getBytes(StandardCharsets.UTF_8));
                case "C-c" -> pty.getOutputStream().write("\003".getBytes(StandardCharsets.UTF_8));
                default -> { /* unknown key */ }
            }
            pty.getOutputStream().flush();
        }
    }

    public synchronized void sendCommand(String cmd) throws IOException {
        if (!alive) return;
        // Flush any pending debounced output before the command runs
        flushPending();
        if (tmuxSession != null) {
            try {
                new ProcessBuilder(findTmux(), "send-keys", "-l", "-t", tmuxSession, cmd)
                        .start().waitFor();
                new ProcessBuilder(findTmux(), "send-keys", "-t", tmuxSession, "Enter")
                        .start().waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (pty != null) {
            pty.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            pty.getOutputStream().flush();
        }
    }

    public synchronized void resize(int cols, int rows) {
        if (pty != null && alive) {
            pty.setWinSize(new WinSize(cols, rows));
        }
    }

    public synchronized byte[] getReplayData() {
        if (ringCount == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int start = (ringCount < RING_SIZE) ? 0 : ringHead;
        for (int i = 0; i < ringCount; i++) {
            byte[] chunk = ringBuffer[(start + i) % RING_SIZE];
            if (chunk != null) {
                try { out.write(chunk); } catch (IOException ignored) {}
            }
        }
        return out.toByteArray();
    }

    /** Returns current pane content (ANSI stripped) for initial client state. */
    public synchronized String getCurrentState() {
        if (tmuxSession != null) {
            String pane = capturePane();
            return pane != null ? stripAnsi(pane.getBytes()) : "";
        }
        byte[] ring = getReplayData();
        return ring.length > 0 ? stripAnsi(ring) : "";
    }

    public boolean isAlive() {
        return alive;
    }

    public synchronized void destroy() {
        alive = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
        if (pty != null) {
            pty.destroy();
        }
    }

    static String stripAnsi(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    private static String findTmux() {
        for (String path : new String[]{
                "/opt/homebrew/bin/tmux",
                "/usr/local/bin/tmux",
                "/usr/bin/tmux"
        }) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
                return path;
            }
        }
        return "tmux";
    }
}
