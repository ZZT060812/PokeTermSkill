package com.remoteterm;

import com.remoteterm.auth.AuthManager;
import com.remoteterm.terminal.PtyManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class App {

    private static String cliToken;
    private static String cliWorkspace;

    public static void main(String[] args) {
        cliToken = parseToken(args);
        cliWorkspace = parseWorkspace(args);
        SpringApplication.run(App.class, args);
    }

    @Bean
    public AuthManager authManager() {
        return new AuthManager(cliToken);
    }

    @Bean
    public PtyManager ptyManager() {
        return new PtyManager();
    }

    @Bean
    public CommandLineRunner startupBanner(AuthManager authManager) {
        return args -> {
            String port = System.getProperty("server.port", "8765");
            String token = authManager.getToken();
            String localUrl = "http://localhost:" + port;
            String ws = System.getProperty("remote-terminal.workspace-root",
                    System.getenv().getOrDefault("TERM_WORKSPACE",
                            System.getProperty("user.home")));

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════╗");
            System.out.println("  ║        Remote AI Terminal  v1.0.0              ║");
            System.out.println("  ╠══════════════════════════════════════════════════╣");
            System.out.println("  ║                                                ║");
            System.out.println("  ║   Local:   " + padRight(localUrl, 37) + "║");
            System.out.println("  ║   Token:   " + padRight(token, 37) + "║");
            System.out.println("  ║   Dir:     " + padRight(truncate(ws, 37), 37) + "║");
            String tmux = System.getenv("TERM_TMUX_SESSION");
            if (tmux != null && !tmux.isBlank()) {
                System.out.println("  ║   Tmux:    " + padRight(tmux, 37) + "║");
            }
            System.out.println("  ║                                                ║");
            System.out.println("  ║   Open the URL above in any browser.           ║");
            System.out.println("  ║   Enter the token when prompted.               ║");
            System.out.println("  ╚══════════════════════════════════════════════════╝");
            System.out.println();
        };
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private static String truncate(String s, int n) {
        if (s.length() <= n) return s;
        return "..." + s.substring(s.length() - n + 3);
    }

    private static String parseArg(String[] args, String flag, String envKey) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env;
        return null;
    }

    private static String parseToken(String[] args) {
        return parseArg(args, "--token", "TERM_TOKEN");
    }

    private static String parseWorkspace(String[] args) {
        String ws = parseArg(args, "--workspace", "TERM_WORKSPACE");
        if (ws != null) {
            System.setProperty("remote-terminal.workspace-root", ws);
        }
        return ws;
    }
}
