package com.remoteterm.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.*;

public class PtyManager {

    private static final Logger log = LoggerFactory.getLogger(PtyManager.class);
    private static final int RING_SIZE = 500;

    private PtyProcess pty;
    private PtyOutputListener listener;
    private final byte[][] ringBuffer = new byte[RING_SIZE][];
    private int ringHead;
    private int ringCount;
    private volatile boolean alive;

    public void setListener(PtyOutputListener listener) {
        this.listener = listener;
    }

    public synchronized void spawn(String shell, Path workDir, int cols, int rows) throws IOException {
        if (alive) return;

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        String tmuxSession = System.getenv("TERM_TMUX_SESSION");
        String[] cmd;
        String cwd;

        if (tmuxSession != null && !tmuxSession.isBlank()) {
            // Attach to existing tmux session — shared terminal view
            String tmuxBin = findTmux();
            cmd = new String[]{tmuxBin, "attach", "-t", tmuxSession};
            cwd = workDir.toString();
            log.info("Tmux mode: attaching to session '{}'", tmuxSession);
        } else {
            // Spawn a new shell
            cmd = new String[]{shell};
            cwd = workDir.toString();
        }

        pty = PtyProcess.exec(cmd, env, cwd);
        pty.setWinSize(new WinSize(cols, rows));
        alive = true;

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
                        l.onOutput(Base64.getEncoder().encodeToString(chunk));
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
        if (pty == null || !alive) return;
        OutputStream out = pty.getOutputStream();
        out.write(data);
        out.flush();
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

    public boolean isAlive() {
        return alive;
    }

    public synchronized void destroy() {
        alive = false;
        if (pty != null) {
            pty.destroy();
        }
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
        return "tmux"; // fallback to PATH
    }
}
