package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.sql.Connection;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = Config.PORT;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // ---- API routes ----
        server.createContext("/api/hospitals", new HospitalHandler());
        server.createContext("/api/doctors", new DoctorHandler());
        server.createContext("/api/treatments", new TreatmentHandler());
        server.createContext("/api/appointments", new AppointmentHandler());
        server.createContext("/api/auth", new AuthHandler());
        server.createContext("/api/feedback", new FeedbackHandler());
        server.createContext("/api/config", new ConfigHandler());
        server.createContext("/api/doctor", new DoctorDashboardHandler());
        server.createContext("/api/health", Main::health);

        // ---- Frontend static files (../frontend relative to where you run `java`) ----
        File frontendDir = new File("../frontend");
        if (!frontendDir.exists()) frontendDir = new File("frontend"); // fallback
        server.createContext("/", new StaticFileHandler(frontendDir));

        server.setExecutor(null);
        server.start();

        System.out.println("=======================================================");
        System.out.println(" MedTour India server running:  http://localhost:" + port);
        System.out.println(" API base:                      http://localhost:" + port + "/api");
        System.out.println(" Serving frontend from:          " + frontendDir.getAbsolutePath());
        testDbConnection();
        System.out.println("=======================================================");
    }

    private static void health(HttpExchange ex) throws java.io.IOException {
        if (Http.handleCors(ex)) return;
        Http.sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    private static void testDbConnection() {
        try (Connection c = Database.getConnection()) {
            System.out.println(" MySQL connection:               OK");
        } catch (Exception e) {
            System.out.println(" MySQL connection:               FAILED -> " + e.getMessage());
            System.out.println(" Fix backend/src/medtour/Database.java with your MySQL credentials.");
        }
    }
}
