import com.fazecast.jSerialComm.SerialPort;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HospitalWardBridge
 * ------------------
 * Reads JSON from Arduino over serial, maintains state,
 * and exposes a lightweight HTTP API for the dashboard.
 *
 * Endpoints:
 *   GET /api/status      → latest ward state (JSON)
 *   GET /api/events      → last N events (JSON array)
 *   GET /api/ports       → available COM ports
 *   GET /sse             → Server-Sent Events stream
 */
public class HospitalWardBridge {

    // -------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------
    private static final int    HTTP_PORT       = 8765;
    private static final int    MAX_EVENTS      = 200;
    private static final String BAUD_RATE_KEY   = "baud";
    private static final int    DEFAULT_BAUD    = 9600;

    // -------------------------------------------------------
    //  Shared state
    // -------------------------------------------------------
    private static final AtomicReference<WardState> currentState =
            new AtomicReference<>(new WardState());

    private static final ConcurrentLinkedDeque<EventRecord> events =
            new ConcurrentLinkedDeque<>();

    // SSE subscribers
    private static final Set<SseClient> sseClients =
            Collections.synchronizedSet(new HashSet<>());

    private static SerialPort activePort;
    private static volatile boolean running = true;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // -------------------------------------------------------
    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  Hospital Ward Management System Bridge  ║");
        System.out.println("║  Version 1.0                             ║");
        System.out.println("╚══════════════════════════════════════════╝");

        // Find Arduino port
        String portName = findArduinoPort(args);
        int    baud     = parseBaud(args);

        if (portName == null) {
            System.err.println("[ERROR] No serial port found. Available ports:");
            for (SerialPort p : SerialPort.getCommPorts())
                System.err.println("  " + p.getSystemPortName() + " - " + p.getDescriptivePortName());
            System.err.println("\nUsage: java -jar bridge.jar [COM_PORT] [baud=9600]");
            System.exit(1);
        }

        // Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/events", new EventsHandler());
        server.createContext("/api/ports",  new PortsHandler());
        server.createContext("/sse",        new SseHandler());
        server.createContext("/",           new StaticHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("[HTTP]   Server started on http://localhost:" + HTTP_PORT);
        System.out.println("[HTTP]   Dashboard: http://localhost:" + HTTP_PORT + "/dashboard");

        // Start serial reader
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            if (activePort != null && activePort.isOpen()) activePort.closePort();
            System.out.println("[SYSTEM] Shutdown complete.");
        }));

        readSerial(portName, baud);
    }

    // -------------------------------------------------------
    //  Serial Reading Loop
    // -------------------------------------------------------
    private static void readSerial(String portName, int baud) {
        while (running) {
            try {
                activePort = SerialPort.getCommPort(portName);
                activePort.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
                activePort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 5000, 0);

                if (!activePort.openPort()) {
                    System.err.println("[SERIAL] Failed to open " + portName + ". Retrying in 3s...");
                    Thread.sleep(3000);
                    continue;
                }

                System.out.println("[SERIAL] Connected to " + portName + " @ " + baud + " baud");
                addEvent("SYSTEM", "Serial connected to " + portName);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(activePort.getInputStream(), StandardCharsets.UTF_8));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("{") && line.endsWith("}")) {
                        processPayload(line);
                    } else if (!line.isEmpty()) {
                        System.out.println("[SERIAL] Raw: " + line);
                    }
                }

            } catch (Exception e) {
                System.err.println("[SERIAL] Error: " + e.getMessage() + " — reconnecting in 3s...");
                addEvent("ERROR", "Serial disconnected: " + e.getMessage());
            }

            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        }
    }

    // -------------------------------------------------------
    //  JSON Payload Processor
    // -------------------------------------------------------
    private static void processPayload(String json) {
        try {
            Map<String, String> map = SimpleJSON.parse(json);

            WardState prev = currentState.get();
            WardState next = new WardState();

            next.state      = map.getOrDefault("state",     "ARMED");
            next.bed1       = map.getOrDefault("bed1",      "OK");
            next.bed2       = map.getOrDefault("bed2",      "OK");
            next.visitors   = parseInt(map.get("visitors"), prev.visitors);
            next.alerts     = parseInt(map.get("alerts"),   prev.alerts);
            next.ultraDist  = parseInt(map.get("ultraDist"), 999);
            next.uptime     = parseLong(map.get("uptime"), 0);
            next.timestamp  = LocalDateTime.now().format(TS_FMT);

            currentState.set(next);

            // Generate events on state changes
            if (!next.state.equals(prev.state)) {
                addEvent(next.state, "System state changed to " + next.state);
            }
            if ("ALERT".equals(next.bed1) && !"ALERT".equals(prev.bed1)) {
                addEvent("WARNING", "Bed 1 patient absent — possible emergency");
            }
            if ("OK".equals(next.bed1) && "ALERT".equals(prev.bed1)) {
                addEvent("RESOLVED", "Bed 1 patient returned");
            }
            if ("ALERT".equals(next.bed2) && !"ALERT".equals(prev.bed2)) {
                addEvent("WARNING", "Bed 2 patient absent — possible emergency");
            }
            if ("OK".equals(next.bed2) && "ALERT".equals(prev.bed2)) {
                addEvent("RESOLVED", "Bed 2 patient returned");
            }
            if (next.visitors > prev.visitors) {
                addEvent("VISITOR", "New visitor registered (total: " + next.visitors + ")");
            }

            // Push to SSE
            String sseData = "data:" + next.toJSON() + "\n\n";
            synchronized (sseClients) {
                Iterator<SseClient> it = sseClients.iterator();
                while (it.hasNext()) {
                    SseClient client = it.next();
                    try {
                        client.out.write(sseData.getBytes(StandardCharsets.UTF_8));
                        client.out.flush();
                    } catch (IOException e) {
                        it.remove();
                    }
                }
            }

            System.out.printf("[DATA]   State=%-8s Bed1=%-5s Bed2=%-5s Visitors=%d Alerts=%d Dist=%dcm%n",
                    next.state, next.bed1, next.bed2, next.visitors, next.alerts, next.ultraDist);

        } catch (Exception e) {
            System.err.println("[PARSE]  Error parsing JSON: " + e.getMessage() + " | Raw: " + json);
        }
    }

    // -------------------------------------------------------
    //  Event Log
    // -------------------------------------------------------
    static void addEvent(String type, String message) {
        EventRecord ev = new EventRecord();
        ev.type      = type;
        ev.message   = message;
        ev.timestamp = LocalDateTime.now().format(TS_FMT);
        events.addFirst(ev);
        while (events.size() > MAX_EVENTS) events.removeLast();
        System.out.println("[EVENT]  [" + type + "] " + message);
    }

    // -------------------------------------------------------
    //  Port Detection
    // -------------------------------------------------------
    private static String findArduinoPort(String[] args) {
        // 1. Use command-line arg if provided
        if (args.length > 0 && !args[0].startsWith("baud=")) return args[0];

        // 2. Auto-detect Arduino
        for (SerialPort p : SerialPort.getCommPorts()) {
            String desc = p.getDescriptivePortName().toLowerCase();
            if (desc.contains("arduino") || desc.contains("ch340") ||
                desc.contains("ftdi")    || desc.contains("usb serial") ||
                desc.contains("uart")    || desc.contains("cp210")) {
                System.out.println("[SERIAL] Auto-detected: " + p.getSystemPortName()
                        + " (" + p.getDescriptivePortName() + ")");
                return p.getSystemPortName();
            }
        }

        // 3. Fallback to first port
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length > 0) {
            System.out.println("[SERIAL] Using first available port: " + ports[0].getSystemPortName());
            return ports[0].getSystemPortName();
        }

        return null;
    }

    private static int parseBaud(String[] args) {
        for (String a : args) {
            if (a.startsWith("baud=")) {
                try { return Integer.parseInt(a.substring(5)); } catch (Exception ignored) {}
            }
        }
        return DEFAULT_BAUD;
    }

    private static int  parseInt(String s, int def)   { try { return Integer.parseInt(s);  } catch (Exception e) { return def; } }
    private static long parseLong(String s, long def) { try { return Long.parseLong(s);     } catch (Exception e) { return def; } }

    // ═══════════════════════════════════════════════════════
    //  HTTP HANDLERS
    // ═══════════════════════════════════════════════════════

    static class StatusHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            byte[] body = currentState.get().toJSON().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        }
    }

    static class EventsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (EventRecord ev : events) {
                if (!first) sb.append(",");
                sb.append(ev.toJSON());
                first = false;
            }
            sb.append("]");
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        }
    }

    static class PortsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (SerialPort p : SerialPort.getCommPorts()) {
                if (!first) sb.append(",");
                sb.append("{\"port\":\"").append(p.getSystemPortName())
                  .append("\",\"desc\":\"").append(p.getDescriptivePortName()).append("\"}");
                first = false;
            }
            sb.append("]");
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.getResponseBody().close();
        }
    }

    static class SseHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            addCORS(ex);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0);

            OutputStream out = ex.getResponseBody();
            SseClient client = new SseClient(out);
            sseClients.add(client);

            // Send current state immediately
            try {
                String init = "data:" + currentState.get().toJSON() + "\n\n";
                out.write(init.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {}

            // Keep alive — remove on disconnect
            while (running && sseClients.contains(client)) {
                try {
                    Thread.sleep(15000);
                    out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception e) {
                    break;
                }
            }
            sseClients.remove(client);
        }
    }

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/dashboard")) {
                // Serve dashboard from same directory
                File f = new File("dashboard/index.html");
                if (!f.exists()) f = new File("index.html");
                if (!f.exists()) {
                    byte[] msg = "Dashboard not found. Place index.html in dashboard/ folder.".getBytes();
                    ex.sendResponseHeaders(404, msg.length);
                    ex.getResponseBody().write(msg);
                    ex.getResponseBody().close();
                    return;
                }
                byte[] body = java.nio.file.Files.readAllBytes(f.toPath());
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.getResponseBody().close();
            } else {
                ex.sendResponseHeaders(404, 0);
                ex.getResponseBody().close();
            }
        }
    }

    static void addCORS(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    // ═══════════════════════════════════════════════════════
    //  DATA CLASSES
    // ═══════════════════════════════════════════════════════

    static class WardState {
        String state     = "ARMED";
        String bed1      = "OK";
        String bed2      = "OK";
        int    visitors  = 0;
        int    alerts    = 0;
        int    ultraDist = 999;
        long   uptime    = 0;
        String timestamp = "";

        String toJSON() {
            return String.format(
                "{\"state\":\"%s\",\"bed1\":\"%s\",\"bed2\":\"%s\"," +
                "\"visitors\":%d,\"alerts\":%d,\"ultraDist\":%d," +
                "\"uptime\":%d,\"timestamp\":\"%s\"}",
                state, bed1, bed2, visitors, alerts, ultraDist, uptime, timestamp);
        }
    }

    static class EventRecord {
        String type;
        String message;
        String timestamp;

        String toJSON() {
            return String.format("{\"type\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                type, message.replace("\"", "'"), timestamp);
        }
    }

    static class SseClient {
        final OutputStream out;
        SseClient(OutputStream out) { this.out = out; }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public boolean equals(Object o) { return this == o; }
    }
}
