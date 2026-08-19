package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/** Serves the static HTML/CSS/JS frontend so the whole site runs from ONE server (port 8080). */
public class StaticFileHandler implements HttpHandler {

    private final File rootDir;

    public StaticFileHandler(File rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        File file = new File(rootDir, path).getCanonicalFile();

        // Security: never serve files outside the frontend folder
        if (!file.getPath().startsWith(rootDir.getCanonicalPath())) {
            ex.sendResponseHeaders(403, -1);
            return;
        }

        if (!file.exists() || file.isDirectory()) {
            byte[] notFound = "404 - Page not found".getBytes();
            ex.sendResponseHeaders(404, notFound.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(notFound); }
            return;
        }

        String contentType = guessContentType(file.getName());
        ex.getResponseHeaders().set("Content-Type", contentType);
        byte[] bytes = Files.readAllBytes(file.toPath());
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String guessContentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
