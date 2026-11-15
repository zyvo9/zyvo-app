package com.zyvo.code.adb;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Loopback TCP bridge (127.0.0.1:4777): the engine's `zyvo-adb` script sends
 * [token]
[command] and gets the shell-uid (wireless-debugging) result back.
 * Token-checked, loopback-only — a personal-device convenience, not an API.
 */
public final class AdbBridgeServer {
    private static final String TAG = "AdbBridgeServer";
    private static final int PORT = 4777;
    private static ServerSocket server;

    private AdbBridgeServer() {}

    public static synchronized void start(Context ctx) {
        if (server != null && !server.isClosed()) return;
        final String token = loadOrCreateToken(ctx);
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
                Log.i(TAG, "bridge listening on 127.0.0.1:" + PORT);
                while (!server.isClosed()) {
                    final Socket sock = server.accept();
                    handle(ctx, sock, token);
                }
            } catch (Throwable t2) {
                Log.w(TAG, "bridge stopped: " + t2.getMessage());
            }
        }, "adb-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Context ctx, Socket sock, String token) {
        try (Socket s = sock) {
            s.setSoTimeout(120_000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String tok = in.readLine();
            if (tok == null || !tok.equals(token)) return;
            String cmd = in.readLine();
            if (cmd == null || cmd.trim().isEmpty()) return;
            AdbShellController.Result r = AdbShellController.exec(cmd);
            OutputStream os = s.getOutputStream();
            String body = (r.ok ? "" : "[rejected] ") + (r.output == null ? "" : r.output);
            os.write((body + "
").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Throwable ignored) {}
    }

    private static String loadOrCreateToken(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "adb_bridge_token");
            if (f.exists() && f.length() > 0) {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                return new String(b, StandardCharsets.UTF_8).trim();
            }
            SecureRandom sr = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 24; i++) sb.append("abcdef0123456789".charAt(sr.nextInt(16)));
            String tok = sb.toString();
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(tok.getBytes(StandardCharsets.UTF_8));
            os.close();
            return tok;
        } catch (Throwable t) {
            return "zyvo-local";
        }
    }
}
