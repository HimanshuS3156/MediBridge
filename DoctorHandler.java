package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/doctors                                  -> list all doctors (public directory)
 * GET /api/doctors?hospitalId=1                      -> doctors in a specific hospital
 * GET /api/doctors?specialization=Cardiac%20Surgery   -> exact specialization filter
 * GET /api/doctors?q=cardiac                          -> free-text search (name or specialization)
 * GET /api/doctors/specializations                    -> distinct specialization values
 */
public class DoctorHandler implements HttpHandler {

    private static final String BASE_SELECT =
            "SELECT d.id, d.name, d.specialization, d.experience_years, d.image_url, d.consultation_fee_inr, " +
            "h.id AS hospital_id, h.name AS hospital_name " +
            "FROM doctors d JOIN hospitals h ON d.hospital_id = h.id";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
            }
            List<String> segments = Http.pathSegmentsAfter(ex, "/api/doctors");
            if (!segments.isEmpty() && segments.get(0).equals("specializations")) {
                Http.sendObject(ex, 200, listSpecializations());
                return;
            }
            Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
            List<Object> results = search(Http.parseIntOrNull(q.get("hospitalId")), q.get("specialization"), q.get("q"));
            Http.sendObject(ex, 200, results);
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    static List<Object> search(Integer hospitalId, String specialization, String q) {
        String specFilter = blankToNull(specialization);
        String search = blankToNull(q);

        StringBuilder sql = new StringBuilder(BASE_SELECT).append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (hospitalId != null) {
            sql.append(" AND h.id = ?");
            params.add(hospitalId);
        }
        if (specFilter != null) {
            sql.append(" AND d.specialization = ?");
            params.add(specFilter);
        }
        if (search != null) {
            sql.append(" AND (LOWER(d.name) LIKE LOWER(?) OR LOWER(d.specialization) LIKE LOWER(?))");
            params.add("%" + search + "%");
            params.add("%" + search + "%");
        }
        sql.append(" ORDER BY d.experience_years DESC");

        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toMap(rs));
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    static List<Object> listSpecializations() {
        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT specialization FROM doctors ORDER BY specialization ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString("specialization"));
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    static Map<String, Object> toMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("name", rs.getString("name"));
        m.put("specialization", rs.getString("specialization"));
        m.put("experienceYears", rs.getInt("experience_years"));
        m.put("imageUrl", rs.getString("image_url"));
        BigDecimal fee = rs.getBigDecimal("consultation_fee_inr");
        m.put("consultationFeeInr", fee != null ? fee : BigDecimal.ZERO);
        m.put("hospitalId", rs.getInt("hospital_id"));
        m.put("hospitalName", rs.getString("hospital_name"));
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
