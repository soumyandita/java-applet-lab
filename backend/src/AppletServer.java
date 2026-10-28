import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.applet.Applet;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import javax.tools.JavaCompiler;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Educational Java 8 Applet renderer prototype.
 *
 * SECURITY WARNING: this compiles and executes submitted Java code in-process.
 * Do not expose it to the public internet without a robust isolated sandbox.
 */
public class AppletServer {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<String, Session>();
    private static final int MAX_BODY = 100_000;
    private static final int WIDTH = 480, HEIGHT = 320;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().containsKey("PORT") ? System.getenv("PORT") : "8080");
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", exchange -> send(exchange, 200, "{\"status\":\"ok\",\"java\":\"" +
                escape(System.getProperty("java.version")) + "\"}"));
        server.createContext("/api/run", new RunHandler());
        server.createContext("/api/event", new EventHandler());
        server.createContext("/api/reset", new ResetHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Java Applet Lab backend listening on " + port);
    }

    private static class RunHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equals(ex.getRequestMethod())) { send(ex, 204, ""); return; }
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, error("POST required")); return; }
            Path dir = null;
            URLClassLoader loader = null;
            try {
                Map<String, String> body = readJson(ex);
                String source = body.get("code");
                if (source == null || source.trim().isEmpty()) { send(ex, 400, error("Java source is empty.")); return; }
                if (source.length() > MAX_BODY) { send(ex, 413, error("Source is too large.")); return; }
                if (!source.contains("class AppletProgram") || !source.contains("extends Applet")) {
                    send(ex, 400, error("Declare `public class AppletProgram extends Applet` and import java.applet.Applet."));
                    return;
                }
                dir = Files.createTempDirectory("applet-lab-");
                Path javaFile = dir.resolve("AppletProgram.java");
                Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) throw new IOException("A full JDK is required; a JRE alone is not enough.");
                DiagnosticCollector diagnostics = new DiagnosticCollector();
                StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
                Iterable units = fm.getJavaFileObjects(javaFile.toFile());
                List<String> options = Arrays.asList("-encoding", "UTF-8", "-d", dir.toString());
                Boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
                fm.close();
                if (!Boolean.TRUE.equals(ok)) {
                    StringBuilder errors = new StringBuilder("Compilation failed:\n");
                    for (Object item : diagnostics.getDiagnostics()) {
                        Diagnostic d = (Diagnostic)item;
                        errors.append("Line ").append(d.getLineNumber()).append(": ")
                              .append(d.getMessage(Locale.ENGLISH)).append("\n");
                    }
                    deleteTree(dir);
                    send(ex, 400, error(errors.toString()));
                    return;
                }
                loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, Applet.class.getClassLoader());
                final Class<?> clazz = loader.loadClass("AppletProgram");
                if (!Applet.class.isAssignableFrom(clazz)) throw new IOException("AppletProgram must extend java.applet.Applet.");
                final Applet applet = (Applet)clazz.newInstance();
                final Session session = new Session(UUID.randomUUID().toString(), applet, dir, loader);
                EventQueue.invokeAndWait(() -> {
                    applet.setSize(WIDTH, HEIGHT);
                    applet.addNotify();
                    applet.init();
                    applet.start();
                    applet.validate();
                });
                BufferedImage image = render(session);
                SESSIONS.put(session.id, session);
                send(ex, 200, "{\"sessionId\":\"" + session.id + "\",\"image\":\"data:image/png;base64," +
                        imageBase64(image) + "\",\"message\":\"Compiled and rendered with Java " +
                        escape(System.getProperty("java.version")) + ".\"}");
            } catch (Throwable t) {
                if (dir != null) try { deleteTree(dir); } catch (Exception ignored) {}
                send(ex, 500, error(rootMessage(t)));
            }
        }
    }

    private static class EventHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equals(ex.getRequestMethod())) { send(ex, 204, ""); return; }
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, error("POST required")); return; }
            try {
                Map<String, String> body = readJson(ex);
                Session s = SESSIONS.get(body.get("sessionId"));
                if (s == null) { send(ex, 404, error("Applet session expired. Run the program again.")); return; }
                String type = body.get("type");
                int x = integer(body.get("x"), 50), y = integer(body.get("y"), 50);
                int key = integer(body.get("keyCode"), KeyEvent.VK_A);
                EventQueue.invokeAndWait(() -> {
                    long now = System.currentTimeMillis();
                    if ("mouseClicked".equals(type) || "mousePressed".equals(type) || "mouseReleased".equals(type)
                            || "mouseMoved".equals(type) || "mouseDragged".equals(type)) {
                        int id = "mouseClicked".equals(type) ? MouseEvent.MOUSE_CLICKED :
                                 "mousePressed".equals(type) ? MouseEvent.MOUSE_PRESSED :
                                 "mouseReleased".equals(type) ? MouseEvent.MOUSE_RELEASED :
                                 "mouseMoved".equals(type) ? MouseEvent.MOUSE_MOVED : MouseEvent.MOUSE_DRAGGED;
                        s.applet.dispatchEvent(new MouseEvent(s.applet, id, now, 0, x, y, 1, false));
                    } else if ("keyPressed".equals(type) || "keyReleased".equals(type) || "keyTyped".equals(type)) {
                        int id = "keyPressed".equals(type) ? KeyEvent.KEY_PRESSED :
                                 "keyReleased".equals(type) ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_TYPED;
                        s.applet.dispatchEvent(new KeyEvent(s.applet, id, now, 0, key,
                                id == KeyEvent.KEY_TYPED ? (char)key : KeyEvent.CHAR_UNDEFINED));
                    } else {
                        throw new IllegalArgumentException("Unsupported event type: " + type);
                    }
                });
                BufferedImage image = render(s);
                send(ex, 200, "{\"sessionId\":\"" + s.id + "\",\"image\":\"data:image/png;base64," +
                        imageBase64(image) + "\",\"message\":\"Event sent: " + escape(type) + "\"}");
            } catch (Throwable t) { send(ex, 400, error(rootMessage(t))); }
        }
    }

    private static class ResetHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equals(ex.getRequestMethod())) { send(ex, 204, ""); return; }
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, error("POST required")); return; }
            try {
                Map<String, String> body = readJson(ex);
                Session s = SESSIONS.remove(body.get("sessionId"));
                if (s != null) {
                    try { EventQueue.invokeAndWait(() -> { s.applet.stop(); s.applet.destroy(); s.applet.removeNotify(); }); }
                    catch (Exception ignored) {}
                    try { s.loader.close(); } catch (Exception ignored) {}
                    deleteTree(s.dir);
                }
                send(ex, 200, "{\"status\":\"reset\"}");
            } catch (Throwable t) { send(ex, 400, error(rootMessage(t))); }
        }
    }

    private static BufferedImage render(Session s) throws Exception {
        final BufferedImage[] out = new BufferedImage[1];
        EventQueue.invokeAndWait(() -> {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics g = image.getGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            s.applet.paint(g);
            g.dispose();
            out[0] = image;
        });
        return out[0];
    }

    private static String imageBase64(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static class Session {
        final String id; final Applet applet; final Path dir; final URLClassLoader loader;
        Session(String id, Applet applet, Path dir, URLClassLoader loader) {
            this.id=id; this.applet=applet; this.dir=dir; this.loader=loader;
        }
    }

    private static Map<String,String> readJson(HttpExchange ex) throws IOException {
        byte[] bytes = readLimited(ex.getRequestBody(), MAX_BODY + 1000);
        String json = new String(bytes, StandardCharsets.UTF_8);
        Map<String,String> result = new HashMap<String,String>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"])*)\"|(-?\\d+))").matcher(json);
        while (m.find()) result.put(m.group(1), m.group(2) != null ? unescapeJson(m.group(2)) : m.group(3));
        return result;
    }
    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n, total=0;
        while ((n=in.read(b))!=-1) { total+=n; if(total>max) throw new IOException("Request body too large."); out.write(b,0,n); }
        return out.toByteArray();
    }
    private static int integer(String s, int fallback) { try { return Integer.parseInt(s); } catch(Exception e) { return fallback; } }
    private static String unescapeJson(String s) {
        return s.replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\");
    }
    private static String escape(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }
    private static String error(String s) { return "{\"error\":\"" + escape(s) + "\"}"; }
    private static String rootMessage(Throwable t) {
        while (t.getCause()!=null && (t instanceof java.lang.reflect.InvocationTargetException || t.getMessage()==null)) t=t.getCause();
        return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
    }
    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers h=ex.getResponseHeaders(); h.set("Content-Type","application/json; charset=utf-8");
        h.set("Access-Control-Allow-Origin","*"); h.set("Access-Control-Allow-Headers","Content-Type");
        h.set("Access-Control-Allow-Methods","GET,POST,OPTIONS");
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204,-1); return; }
        ex.sendResponseHeaders(status,bytes.length);
        OutputStream os=ex.getResponseBody(); os.write(bytes); os.close();
    }
    private static void deleteTree(Path path) throws IOException {
        if (path==null || !Files.exists(path)) return;
        try (java.util.stream.Stream<Path> stream=Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch(IOException ignored) {} });
        }
    }
}
