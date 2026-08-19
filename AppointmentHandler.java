package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/appointments          -> patient (guest or logged-in) submits a booking request
 * GET  /api/appointments/estimate -> public, live pricing preview while filling out the form
 * GET  /api/appointments/lookup   -> public, "check my appointment status" (needs id + email)
 * GET  /api/appointments          -> ADMIN only, used by the admin dashboard
 * PUT  /api/appointments/{id}     -> ADMIN only, body: {"status":"Confirmed"}
 *
 * Also holds the doctor-scoped static helpers (listForDoctor, getForDoctor, updateStatusAsDoctor,
 * rescheduleAsDoctor) reused by DoctorDashboardHandler — every one of those takes doctorId from
 * the caller (already resolved from the JWT), never from client input.
 */
public class AppointmentHandler implements HttpHandler {

    private static final String BASE_SELECT =
            "SELECT a.*, h.name AS hospital_name, t.name AS treatment_name, d.name AS doctor_name, " +
            "d.specialization AS doctor_specialization " +
            "FROM appointments a " +
            "LEFT JOIN hospitals h ON a.hospital_id = h.id " +
            "LEFT JOIN treatments t ON a.treatment_id = t.id " +
            "LEFT JOIN doctors d ON a.doctor_id = d.id ";

    /** Status changes a doctor is allowed to make themselves (admins aren't bound by this). */
    private static final Map<String, Set<String>> DOCTOR_ALLOWED_TRANSITIONS = Map.of(
            "Pending", Set.of("Confirmed", "Rejected"),
            "Confirmed", Set.of("Completed", "Rejected"),
            "Rejected", Set.of(),
            "Completed", Set.of()
    );

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            String method = ex.getRequestMethod();
            List<String> segments = Http.pathSegmentsAfter(ex, "/api/appointments");

            if (method.equalsIgnoreCase("GET") && !segments.isEmpty() && segments.get(0).equals("estimate")) {
                Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
                Http.sendObject(ex, 200, estimate(Http.parseIntOrNull(q.get("doctorId")),
                        Validate.parseBool(q.get("airportPickup")), Validate.parseBool(q.get("travelAssistance"))));
                return;
            }
            if (method.equalsIgnoreCase("GET") && !segments.isEmpty() && segments.get(0).equals("lookup")) {
                Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
                Integer referenceId = Http.parseIntOrNull(q.get("referenceId"));
                String email = q.get("email");
                if (referenceId == null || email == null || email.isBlank()) {
                    throw new ApiException(400, "Missing required parameter: referenceId or email.");
                }
                Http.sendObject(ex, 200, lookupForPatient(referenceId, email));
                return;
            }
            if (method.equalsIgnoreCase("POST") && segments.isEmpty()) {
                Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                AuthContext.CurrentUser currentUser = AuthContext.currentUserOrNull(ex); // optional (guest booking allowed)
                Http.sendObject(ex, 201, book(body, currentUser));
                return;
            }
            if (method.equalsIgnoreCase("GET") && segments.isEmpty()) {
                AuthContext.requireRole(ex, "ADMIN");
                Http.sendObject(ex, 200, listAll());
                return;
            }
            if (method.equalsIgnoreCase("PUT") && segments.size() == 1) {
                AuthContext.requireRole(ex, "ADMIN");
                int id = Http.requirePathInt(segments.get(0), "id");
                Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                String status = body.get("status");
                if (status == null || status.isBlank()) {
                    throw ApiException.validation("status", "status is required (Pending, Confirmed, Rejected, Completed).");
                }
                Http.sendObject(ex, 200, updateStatus(id, status));
                return;
            }
            throw new ApiException(404, "Not found.");
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    // ---------------------------------------------------------------- Pricing

    private static BigDecimal[] computePricing(Map<String, Object> doctor, boolean airportPickup, boolean travelAssistance) {
        BigDecimal consultation = doctor != null ? (BigDecimal) doctor.get("consultationFeeInr") : BigDecimal.ZERO;
        BigDecimal pickup = airportPickup ? Config.AIRPORT_PICKUP_FEE_INR : BigDecimal.ZERO;
        BigDecimal travel = travelAssistance ? Config.TRAVEL_ASSISTANCE_FEE_INR : BigDecimal.ZERO;
        BigDecimal total = consultation.add(pickup).add(travel);
        return new BigDecimal[]{consultation, pickup, travel, total};
    }

    static Map<String, Object> estimate(Integer doctorId, boolean airportPickup, boolean travelAssistance) {
        Map<String, Object> doctor = doctorId != null ? findDoctorRow(doctorId) : null; // silently ignore invalid id
        BigDecimal[] pricing = computePricing(doctor, airportPickup, travelAssistance);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doctorName", doctor != null ? doctor.get("name") : null);
        out.put("consultationFeeInr", pricing[0]);
        out.put("airportPickupFeeInr", pricing[1]);
        out.put("travelAssistanceFeeInr", pricing[2]);
        out.put("estimatedTotalInr", pricing[3]);
        return out;
    }

    // ---------------------------------------------------------------- Book

    static Map<String, Object> book(Map<String, String> body, AuthContext.CurrentUser currentUser) {
        ValidationErrors errors = new ValidationErrors();
        String patientName = body.get("patientName");
        String email = body.get("email");
        String phone = body.get("phone");
        String country = body.get("country");
        Integer hospitalId = Validate.optionalInt(body.get("hospitalId"));
        Integer treatmentId = Validate.optionalInt(body.get("treatmentId"));
        Integer doctorId = Validate.optionalInt(body.get("doctorId"));
        String preferredDateStr = body.get("preferredDate");
        String message = body.get("message");
        boolean airportPickup = Validate.parseBool(body.get("airportPickup"));
        boolean travelAssistance = Validate.parseBool(body.get("travelAssistance"));

        errors.require(patientName, "patientName", "Full name is required.");
        errors.maxLength(patientName, 150, "patientName", "Full name is too long.");
        errors.email(email, "email");
        errors.phone(phone, "phone");
        errors.maxLength(country, 80, "country", "Country name is too long.");
        errors.requiredInt(treatmentId, "treatmentId", "Please choose a treatment.");
        errors.require(preferredDateStr, "preferredDate", "Preferred date is required.");
        errors.maxLength(message, 2000, "message", "Additional information is too long — please keep it under 2000 characters.");
        errors.throwIfInvalid();

        LocalDate preferredDate;
        try {
            preferredDate = LocalDate.parse(preferredDateStr.trim());
        } catch (Exception e) {
            throw ApiException.validation("preferredDate", "Please select a valid appointment date.");
        }
        validateBookingDate(preferredDate);

        Map<String, Object> treatment = findTreatmentRow(treatmentId);
        if (treatment == null) throw new ApiException(400, "Please choose a valid treatment.");

        Map<String, Object> doctor = null;
        if (doctorId != null) {
            doctor = findDoctorRow(doctorId);
            if (doctor == null) throw new ApiException(400, "Selected doctor was not found.");
        }

        Map<String, Object> hospital;
        if (hospitalId != null) {
            hospital = findHospitalRow(hospitalId);
            if (hospital == null) throw new ApiException(400, "Selected hospital was not found.");
        } else {
            hospital = treatment.get("hospitalId") != null ? findHospitalRow((Integer) treatment.get("hospitalId")) : null;
        }

        if (hospital != null && treatment.get("hospitalId") != null
                && !treatment.get("hospitalId").equals(hospital.get("id"))) {
            throw new ApiException(400, "The selected treatment is not offered at the selected hospital.");
        }
        if (doctor != null && hospital != null && !doctor.get("hospitalId").equals(hospital.get("id"))) {
            throw new ApiException(400, "The selected doctor does not practice at the selected hospital.");
        }

        BigDecimal[] pricing = computePricing(doctor, airportPickup, travelAssistance);

        String cleanName = TextSanitizer.stripTags(patientName.trim());
        String cleanCountry = country == null ? "" : TextSanitizer.stripTags(country.trim());
        String cleanMessage = message == null ? null : TextSanitizer.stripTags(message.trim());

        int newId;
        try (Connection conn = Database.getConnection()) {
            String sql = "INSERT INTO appointments (patient_user_id, patient_name, email, phone, country, " +
                    "hospital_id, treatment_id, doctor_id, preferred_date, message, airport_pickup, travel_assistance, " +
                    "consultation_fee_inr, airport_pickup_fee_inr, travel_assistance_fee_inr, estimated_total_inr, " +
                    "status, confirmation_email_sent_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                if (currentUser != null) ps.setInt(1, currentUser.id); else ps.setNull(1, java.sql.Types.INTEGER);
                ps.setString(2, cleanName);
                ps.setString(3, email.trim());
                ps.setString(4, phone.trim());
                ps.setString(5, cleanCountry);
                if (hospital != null) ps.setInt(6, (Integer) hospital.get("id")); else ps.setNull(6, java.sql.Types.INTEGER);
                ps.setInt(7, treatmentId);
                if (doctor != null) ps.setInt(8, (Integer) doctor.get("id")); else ps.setNull(8, java.sql.Types.INTEGER);
                ps.setDate(9, java.sql.Date.valueOf(preferredDate));
                ps.setString(10, cleanMessage);
                ps.setBoolean(11, airportPickup);
                ps.setBoolean(12, travelAssistance);
                ps.setBigDecimal(13, pricing[0]);
                ps.setBigDecimal(14, pricing[1]);
                ps.setBigDecimal(15, pricing[2]);
                ps.setBigDecimal(16, pricing[3]);
                ps.setString(17, "Pending");
                ps.setTimestamp(18, Timestamp.valueOf(LocalDateTime.now()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    newId = keys.getInt(1);
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }

        Map<String, Object> saved = findAppointmentRow(newId);

        if (doctor != null) {
            notifyDoctor((Integer) doctor.get("id"), newId, "New appointment request",
                    "New request from " + saved.get("patientName") + " for " + saved.get("preferredDate") + ".");
        }

        return saved;
    }

    // ---------------------------------------------------------------- Admin

    static List<Object> listAll() {
        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(BASE_SELECT + "ORDER BY a.created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(toResponseMap(rs));
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    static Map<String, Object> updateStatus(int id, String status) {
        validateStatusValue(status);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE appointments SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, id);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new ApiException(404, "No appointment found with id " + id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        Map<String, Object> saved = findAppointmentRow(id);
        return saved;
    }

    // ---------------------------------------------------------------- Public lookup

    static Map<String, Object> lookupForPatient(int id, String email) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(BASE_SELECT + "WHERE a.id = ? AND LOWER(a.email) = LOWER(?)")) {
            ps.setInt(1, id);
            ps.setString(2, email.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "No appointment found matching that reference ID and email.");
                return toResponseMap(rs);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    // ---------------------------------------------------------------- Doctor-scoped (called from DoctorDashboardHandler)

    static List<Object> listForDoctor(int doctorId, String statusFilter) {
        List<Object> out = new ArrayList<>();
        String sql = BASE_SELECT + "WHERE a.doctor_id = ? ORDER BY a.preferred_date DESC, a.created_at DESC";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, doctorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = toResponseMap(rs);
                    if (statusFilter == null || statusFilter.equals(row.get("status"))) out.add(row);
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    static Map<String, Object> getForDoctor(int id, int doctorId) {
        Map<String, Object> row = findAppointmentRowForDoctor(id, doctorId);
        if (row == null) throw new ApiException(404, "Appointment not found.");
        return row;
    }

    static Map<String, Object> updateStatusAsDoctor(int id, int doctorId, String newStatus) {
        validateStatusValue(newStatus);
        Map<String, Object> current = findAppointmentRowForDoctor(id, doctorId);
        if (current == null) throw new ApiException(404, "Appointment not found.");

        String currentStatus = (String) current.get("status");
        Set<String> allowed = DOCTOR_ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new ApiException(400, "Cannot change an appointment from " + currentStatus + " to " + newStatus + ".");
        }

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE appointments SET status = ? WHERE id = ?")) {
            ps.setString(1, newStatus);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }

        Map<String, Object> saved = findAppointmentRow(id);
        notifyDoctor(doctorId, id, "Appointment " + newStatus,
                "Appointment #" + id + " with " + saved.get("patientName") + " is now " + newStatus + ".");
        return saved;
    }

    static Map<String, Object> rescheduleAsDoctor(int id, int doctorId, LocalDate newDate) {
        Map<String, Object> current = findAppointmentRowForDoctor(id, doctorId);
        if (current == null) throw new ApiException(404, "Appointment not found.");

        String status = (String) current.get("status");
        if (status.equals("Completed") || status.equals("Rejected")) {
            throw new ApiException(400, "A " + status + " appointment can no longer be rescheduled.");
        }
        if (newDate.isBefore(LocalDate.now()) || newDate.isAfter(LocalDate.now().plusDays(Config.MAX_BOOKING_DAYS_AHEAD))) {
            throw new ApiException(400, "Appointments can be booked only within the permitted booking period.");
        }

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE appointments SET preferred_date = ? WHERE id = ?")) {
            ps.setDate(1, java.sql.Date.valueOf(newDate));
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }

        Map<String, Object> saved = findAppointmentRow(id);
        notifyDoctor(doctorId, id, "Appointment rescheduled",
                "Appointment #" + id + " with " + saved.get("patientName") + " moved to " + newDate + ".");
        return saved;
    }

    // ---------------------------------------------------------------- Shared helpers

    private static void validateStatusValue(String status) {
        if (!Set.of("Pending", "Confirmed", "Rejected", "Completed").contains(status)) {
            throw ApiException.validation("status", "status is required (Pending, Confirmed, Rejected, Completed).");
        }
    }

    private static void validateBookingDate(LocalDate date) {
        LocalDate today = LocalDate.now();
        LocalDate latestAllowed = today.plusDays(Config.MAX_BOOKING_DAYS_AHEAD);
        if (date.isBefore(today)) {
            throw new ApiException(400, "Please select a valid appointment date.");
        }
        if (date.isAfter(latestAllowed)) {
            throw new ApiException(400, "Appointments can be booked only within the permitted booking period.");
        }
    }

    static void notifyDoctor(int doctorId, Integer appointmentId, String title, String message) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO notifications (doctor_id, appointment_id, title, message) VALUES (?,?,?,?)")) {
            ps.setInt(1, doctorId);
            if (appointmentId != null) ps.setInt(2, appointmentId); else ps.setNull(2, java.sql.Types.INTEGER);
            ps.setString(3, title);
            ps.setString(4, message.length() > 500 ? message.substring(0, 500) : message);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Failed to write notification: " + e.getMessage());
        }
    }

    private static Map<String, Object> findAppointmentRow(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(BASE_SELECT + "WHERE a.id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "No appointment found with id " + id);
                return toResponseMap(rs);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> findAppointmentRowForDoctor(int id, int doctorId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(BASE_SELECT + "WHERE a.id = ? AND a.doctor_id = ?")) {
            ps.setInt(1, id);
            ps.setInt(2, doctorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return toResponseMap(rs);
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    static Map<String, Object> findDoctorRow(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, hospital_id, name, specialization, consultation_fee_inr FROM doctors WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("hospitalId", rs.getInt("hospital_id"));
                m.put("name", rs.getString("name"));
                m.put("specialization", rs.getString("specialization"));
                BigDecimal fee = rs.getBigDecimal("consultation_fee_inr");
                m.put("consultationFeeInr", fee != null ? fee : BigDecimal.ZERO);
                return m;
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> findTreatmentRow(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, hospital_id, name FROM treatments WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("hospitalId", rs.getInt("hospital_id"));
                m.put("name", rs.getString("name"));
                return m;
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> findHospitalRow(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM hospitals WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                return m;
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> toResponseMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("patientName", rs.getString("patient_name"));
        m.put("email", rs.getString("email"));
        m.put("phone", rs.getString("phone"));
        m.put("country", rs.getString("country"));
        m.put("hospitalName", rs.getString("hospital_name"));
        m.put("treatmentName", rs.getString("treatment_name"));
        m.put("doctorName", rs.getString("doctor_name"));
        java.sql.Date pd = rs.getDate("preferred_date");
        m.put("preferredDate", pd != null ? pd.toLocalDate() : null);
        m.put("message", rs.getString("message"));
        m.put("airportPickup", rs.getBoolean("airport_pickup"));
        m.put("travelAssistance", rs.getBoolean("travel_assistance"));
        m.put("consultationFeeInr", nullSafeDecimal(rs.getBigDecimal("consultation_fee_inr")));
        m.put("airportPickupFeeInr", nullSafeDecimal(rs.getBigDecimal("airport_pickup_fee_inr")));
        m.put("travelAssistanceFeeInr", nullSafeDecimal(rs.getBigDecimal("travel_assistance_fee_inr")));
        m.put("estimatedTotalInr", nullSafeDecimal(rs.getBigDecimal("estimated_total_inr")));
        m.put("status", rs.getString("status"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        m.put("createdAt", createdAt != null ? createdAt.toLocalDateTime() : null);
        return m;
    }

    private static BigDecimal nullSafeDecimal(BigDecimal d) {
        return d != null ? d : BigDecimal.ZERO;
    }
}
