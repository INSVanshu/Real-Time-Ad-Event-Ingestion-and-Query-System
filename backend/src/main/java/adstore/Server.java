package adstore;

import adstore.AdEventStore.Edge;
import adstore.AdEventStore.Related;
import adstore.AdEventStore.SegmentInfo;
import adstore.AdEventStore.Stats;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;


public class Server {

    private static AdEventStore store;

    public static void main(String[] args) throws IOException {
        String env = System.getenv("PORT");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : (env != null ? Integer.parseInt(env) : 8080);
        Path web = args.length > 1 ? Paths.get(args[1]).toAbsolutePath().normalize() : null;
        File data = args.length > 2 ? new File(args[2]) : null;

        store = new AdEventStore(data, 60);
        if (data != null) System.out.println("Recovered " + store.recover() + " events from " + data);

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/api/", Server::api);
        http.createContext("/", ex -> staticFile(ex, web));
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
        System.out.println("Ad Event Graph Store running on http://localhost:" + port);
    }

    
    private static void api(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");           
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Private-Network", "true");
        if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }

        int status = 200;
        String json;
        try {
            Map<String, String> p = params(ex.getRequestURI().getRawQuery());
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            json = route(ex.getRequestMethod(), ex.getRequestURI().getPath(), p, body);
        } catch (IllegalArgumentException e) {
            status = 400;
            json = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            json = "{\"error\":" + q(String.valueOf(e)) + "}";
        }
        send(ex, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static String route(String method, String path, Map<String, String> p, String body) {
        boolean post = method.equals("POST");
        return switch (path) {
            case "/api/event" -> {
                need(post, "POST");
                boolean ok = store.addEvent(num(p, "t"), str(p, "c"), str(p, "u"), type(str(p, "type")));
                yield "{\"accepted\":" + ok + "}";
            }
            case "/api/events" -> { need(post, "POST"); yield batch(body); }
            case "/api/campaign" -> edges(store.campaignEdges(str(p, "c"), num(p, "from"), num(p, "to")), num(p, "limit", 200));
            case "/api/user" -> edges(store.userEdges(str(p, "u"), num(p, "from"), num(p, "to")), num(p, "limit", 200));
            case "/api/reach" -> "{\"reach\":" + store.uniqueReach(str(p, "c"), num(p, "from"), num(p, "to")) + "}";
            case "/api/frequency" -> "{\"impressions\":" + store.frequency(str(p, "c"), str(p, "u"), num(p, "from"), num(p, "to")) + "}";
            case "/api/related" -> related(store.related(str(p, "c"), num(p, "from"), num(p, "to")));
            case "/api/sample" -> edges(store.sample(num(p, "from"), num(p, "to"), num(p, "max", 120)), Integer.MAX_VALUE);
            case "/api/stats" -> stats(store.stats());
            case "/api/drop" -> { need(post, "POST"); yield "{\"dropped\":" + store.dropBefore(num(p, "before")) + "}"; }
            case "/api/reset" -> { need(post, "POST"); store.reset(); yield "{\"ok\":true}"; }
            default -> throw new IllegalArgumentException("unknown endpoint " + path);
        };
    }

    
    private static String batch(String body) {
        int ok = 0, bad = 0;
        for (String line : body.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.trim().split(",");
            try {
                if (f.length == 4 && store.addEvent(Integer.parseInt(f[0]), f[1], f[2], AdEventStore.parseType(f[3]))) ok++; else bad++;
            } catch (NumberFormatException e) { bad++; }
        }
        return "{\"accepted\":" + ok + ",\"rejected\":" + bad + "}";
    }

    
    private static String edges(Edge[] e, int limit) {
        StringBuilder sb = new StringBuilder("{\"count\":").append(e.length).append(",\"edges\":[");
        for (int i = 0; i < Math.min(e.length, limit); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"t\":").append(e[i].time).append(",\"c\":").append(q(e[i].campaign))
              .append(",\"u\":").append(q(e[i].user)).append(",\"type\":").append(q(AdEventStore.TYPE_NAMES[e[i].type])).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String related(Related[] r) {
        StringBuilder sb = new StringBuilder("{\"related\":[");
        for (int i = 0; i < r.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"campaign\":").append(q(r[i].campaign)).append(",\"sharedUsers\":").append(r[i].sharedUsers).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String stats(Stats s) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"accepted\":").append(s.accepted).append(",\"rejected\":").append(s.rejected)
          .append(",\"edges\":").append(s.edges).append(",\"segments\":").append(s.segments)
          .append(",\"sealed\":").append(s.sealed).append(",\"campaigns\":").append(s.campaigns)
          .append(",\"users\":").append(s.users).append(",\"edgeBytes\":").append(s.edgeBytes).append(",\"segmentList\":[");
        for (int i = 0; i < s.segmentList.length; i++) {
            SegmentInfo g = s.segmentList[i];
            if (i > 0) sb.append(',');
            sb.append("{\"hour\":").append(g.hour).append(",\"edges\":").append(g.edges).append(",\"sealed\":").append(g.sealed).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    
    private static Map<String, String> params(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String str(Map<String, String> p, String k) {
        String v = p.get(k);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing parameter '" + k + "'");
        return v;
    }

    private static int num(Map<String, String> p, String k) {
        try { return Integer.parseInt(str(p, k)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("parameter '" + k + "' must be an integer"); }
    }

    private static int num(Map<String, String> p, String k, int def) { return p.containsKey(k) ? num(p, k) : def; }

    private static byte type(String s) {
        byte t = AdEventStore.parseType(s);
        if (t < 0) throw new IllegalArgumentException("type must be imp, click or conv");
        return t;
    }

    private static void need(boolean ok, String m) { if (!ok) throw new IllegalArgumentException("use " + m + " for this endpoint"); }

    
    private static void staticFile(HttpExchange ex, Path web) throws IOException {
        if (web == null) { send(ex, 404, "text/plain", "API only. Start with a frontend folder to serve the dashboard.".getBytes()); return; }
        String path = ex.getRequestURI().getPath();
        Path f = web.resolve((path.equals("/") ? "/index.html" : path).substring(1)).normalize();
        if (!f.startsWith(web) || !Files.isRegularFile(f)) { send(ex, 404, "text/plain", "Not found".getBytes()); return; }
        String n = f.toString();
        String type = n.endsWith(".html") ? "text/html" : n.endsWith(".css") ? "text/css" : n.endsWith(".js") ? "text/javascript" : "application/octet-stream";
        send(ex, 200, type + "; charset=utf-8", Files.readAllBytes(f));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
