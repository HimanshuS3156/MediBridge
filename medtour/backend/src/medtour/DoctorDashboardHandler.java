package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything behind the Doctor Dashboard, under /api/doctor/**. Every route here requires a
 * ROLE_DOCTOR JWT (checked at the top of handle()) and then re-resolves the calling Doctor row
 * from that JWT's user id — never from a client-supplied id — so a doctor can only ever see or
 * change their own profile, appointments, patients, availability, and notifications. This mirrors
 * Spring Security's "/api/doctor/**" -> hasRole("DOCTOR") plus DoctorDashboardService's
 * currentDoctor() ownership boundary, both rolled into one handler since there's no filter chain
 * or DI container here.
 */
public class DoctorDashboardHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            AuthContext.CurrentUser user = AuthContext.requireRole(ex, "DOCTOR");
            Map<String, Object> doctor = resolveCurrentDoctor(user.id);

            String method = ex.getRequestMethod();
            List<String> segs = Http.pathSegmentsAfter(ex, "/api/doctor");
            if (segs.isEmpty()) throw new ApiException(404, "Not found.");

            String resource = segs.get(0);

            switch (resource) {
                case "dashboard" -> {
                    requireMethod(method, "GET");
                    Http.sendObject(ex, 200, getStats(doctor));
                }
                case "profile" -> {
                    if (method.equalsIgnoreCase("GET")) {
                        Http.sendObject(ex, 200, toProfileResponse(doctor));
                    } else if (method.equalsIgnoreCase("PUT")) {
                        Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                        Http.sendObject(ex, 200, updateProfile(doctor, body));
                    } else {
                        throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
                    }
                }
                case "appointments" -> handleAppointments(ex, method, segs, doctor);
                case "consultation-history" -> {
                    requireMethod(method, "GET");
                    Http.sendObject(ex, 200, AppointmentHandler.listForDoctor((Integer) doctor.get("id"), "Completed"));
                }
                case "patients" -> {
                    requireMethod(method, "GET");
                    Http.sendObject(ex, 200, listPatients(doctor));
                }
                case "notifications" -> handleNotifications(ex, method, segs, doctor);
                case "availability" -> handleAvailability(ex, method, segs, doctor);
                case "settings" -> handleSettings(ex, method, segs, user);
                default -> throw new ApiException(404, "Not found.");
            }
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equalsIgnoreCase(expected)) {
            throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
        }
    }

    // ---------------------------------------------------------------- Appointments

    private void handleAppointments(HttpExchange ex, String method, List<String> segs, Map<String, Object> doctor) throws IOException {
        int doctorId = (Integer) doctor.get("id");

        if (segs.size() == 1) {
            requireMethod(method, "GET");
            Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
            String status = q.get("status");
            if (status != null && !status.isBlank() && !Set.of("Pending", "Confirmed", "Rejected", "Completed").contains(status)) {
                throw new ApiException(400, "Invalid value for 'status'.");
            }
            Http.sendObject(ex, 200, AppointmentHandler.listForDoctor(doctorId, status == null || status.isBlank() ? null : status));
            return;
        }

        int id = Http.requirePathInt(segs.get(1), "id");

        if (segs.size() == 2) {
            requireMethod(method, "GET");
            Http.sendObject(ex, 200, AppointmentHandler.getForDoctor(id, doctorId));
            return;
        }

        if (segs.size() == 3 && segs.get(2).equals("status")) {
            requireMethod(method, "PUT");
            Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
            String newStatus = body.get("status");
            if (newStatus == null || newStatus.isBlank()) {
                throw ApiException.validation("status", "status is required (Pending, Confirmed, Rejected, Completed).");
            }
            Http.sendObject(ex, 200, AppointmentHandler.updateStatusAsDoctor(id, doctorId, newStatus));
            return;
        }

        if (segs.size() == 3 && segs.get(2).equals("reschedule")) {
            requireMethod(method, "PUT");
            Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
            String dateStr = body.get("preferredDate");
            if (dateStr == null || dateStr.isBlank()) {
                throw ApiException.validation("preferredDate", "A new preferred date is required.");
            }
            LocalDate newDate;
            try {
                newDate = LocalDate.parse(dateStr.trim());
            } catch (Exception e) {
                throw ApiException.validation("preferredDate", "The new date cannot be in the past.");
            }
            if (newDate.isBefore(LocalDate.now())) {
                throw ApiException.validation("preferredDate", "The new date cannot be in the past.");
            }
            Http.sendObject(ex, 200, AppointmentHandler.rescheduleAsDoctor(id, doctorId, newDate));
            return;
        }

        throw new ApiException(404, "Not found.");
    }

    // ---------------------------------------------------------------- Dashboard stats

    private static Map<String, Object> getStats(Map<String, Object> doctor) {
        List<Object> all = AppointmentHandler.listForDoctor((Integer) doctor.get("id"), null);
        LocalDate today = LocalDate.now();

        long todayCount = 0, upcoming = 0, pending = 0;
        Set<String> patientEmails = new java.util.HashSet<>();
        for (Object o : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            LocalDate preferredDate = (LocalDate) a.get("preferredDate");
            String status = (String) a.get("status");
            patientEmails.add((String) a.get("email"));
            if (preferredDate != null && preferredDate.isEqual(today) && !"Rejected".equals(status)) todayCount++;
            if (preferredDate != null && preferredDate.isAfter(today) && ("Pending".equals(status) || "Confirmed".equals(status))) upcoming++;
            if ("Pending".equals(status)) pending++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayAppointments", todayCount);
        out.put("upcomingAppointments", upcoming);
        out.put("totalPatients", (long) patientEmails.size());
        out.put("pendingRequests", pending);
        return out;
    }

    // ---------------------------------------------------------------- Profile

    private static Map<String, Object> toProfileResponse(Map<String, Object> doctor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doctor.get("id"));
        out.put("name", doctor.get("name"));
        out.put("specialization", doctor.get("specialization"));
        out.put("experienceYears", doctor.get("experienceYears"));
        out.put("imageUrl", doctor.get("imageUrl"));
        out.put("consultationFeeInr", doctor.get("consultationFeeInr"));
        out.put("hospitalId", doctor.get("hospitalId"));
        out.put("hospitalName", doctor.get("hospitalName"));
        return out;
    }

    private static Map<String, Object> updateProfile(Map<String, Object> doctor, Map<String, String> body) {
        String name = body.get("name");
        String specialization = body.get("specialization");
        Integer experienceYears = Validate.optionalInt(body.get("experienceYears"));
        BigDecimal consultationFeeInr = parseBigDecimalOrNull(body.get("consultationFeeInr"));
        String imageUrl = body.get("imageUrl");

        ValidationErrors errors = new ValidationErrors();
        errors.require(name, "name", "Name is required.");
        errors.maxLength(name, 150, "name", "Name is too long.");
        errors.require(specialization, "specialization", "Specialization is required.");
        errors.maxLength(specialization, 150, "specialization", "Specialization is too long.");
        errors.requiredInt(experienceYears, "experienceYears", "Experience (years) is required.");
        errors.range(experienceYears, 0, 80, "experienceYears", "Please enter a realistic number of years.");
        errors.custom(consultationFeeInr == null, "consultationFeeInr", "Consultation fee is required.");
        errors.custom(consultationFeeInr != null && consultationFeeInr.compareTo(BigDecimal.ZERO) < 0,
                "consultationFeeInr", "Consultation fee cannot be negative.");
        errors.maxLength(imageUrl, 300, "imageUrl", "Image URL is too long.");
        errors.throwIfInvalid();

        String cleanName = TextSanitizer.stripTags(name.trim());
        String cleanSpecialization = TextSanitizer.stripTags(specialization.trim());
        int doctorId = (Integer) doctor.get("id");
        Integer userId = (Integer) doctor.get("userId");

        try (Connection conn = Database.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE doctors SET name = ?, specialization = ?, experience_years = ?, consultation_fee_inr = ?" +
                            (imageUrl != null ? ", image_url = ?" : "") + " WHERE id = ?")) {
                int i = 1;
                ps.setString(i++, cleanName);
                ps.setString(i++, cleanSpecialization);
                ps.setInt(i++, experienceYears);
                ps.setBigDecimal(i++, consultationFeeInr);
                if (imageUrl != null) ps.setString(i++, imageUrl.trim());
                ps.setInt(i, doctorId);
                ps.executeUpdate();
            }
            if (userId != null) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?")) {
                    ps.setString(1, cleanName);
                    ps.setInt(2, userId);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }

        return toProfileResponse(resolveDoctorById(doctorId));
    }

    // ---------------------------------------------------------------- Patients

    private static List<Object> listPatients(Map<String, Object> doctor) {
        List<Object> all = AppointmentHandler.listForDoctor((Integer) doctor.get("id"), null);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object o : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            String email = (String) a.get("email");
            counts.merge(email, 1, Integer::sum);
        }

        Map<String, Object> byEmail = new LinkedHashMap<>();
        for (Object o : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) o;
            String email = (String) a.get("email");
            byEmail.computeIfAbsent(email, e -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", a.get("patientName"));
                p.put("email", email);
                p.put("phone", a.get("phone"));
                p.put("country", a.get("country"));
                p.put("lastAppointmentDate", a.get("preferredDate"));
                p.put("appointmentCount", counts.get(email));
                p.put("lastStatus", a.get("status"));
                return p;
            });
        }
        return new ArrayList<>(byEmail.values());
    }

    // ---------------------------------------------------------------- Notifications

    private void handleNotifications(HttpExchange ex, String method, List<String> segs, Map<String, Object> doctor) throws IOException {
        int doctorId = (Integer) doctor.get("id");

        if (segs.size() == 1) {
            requireMethod(method, "GET");
            Http.sendObject(ex, 200, listNotifications(doctorId));
            return;
        }
        if (segs.size() == 2 && segs.get(1).equals("read-all")) {
            requireMethod(method, "PUT");
            markAllNotificationsRead(doctorId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            Http.sendObject(ex, 200, out);
            return;
        }
        if (segs.size() == 3 && segs.get(2).equals("read")) {
            requireMethod(method, "PUT");
            int id = Http.requirePathInt(segs.get(1), "id");
            Http.sendObject(ex, 200, markNotificationRead(id, doctorId));
            return;
        }
        throw new ApiException(404, "Not found.");
    }

    private static List<Object> listNotifications(int doctorId) {
        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, title, message, appointment_id, is_read, created_at FROM notifications WHERE doctor_id = ? ORDER BY created_at DESC")) {
            ps.setInt(1, doctorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(notificationToMap(rs));
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    private static Map<String, Object> markNotificationRead(int id, int doctorId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement update = conn.prepareStatement(
                     "UPDATE notifications SET is_read = TRUE WHERE id = ? AND doctor_id = ?")) {
            update.setInt(1, id);
            update.setInt(2, doctorId);
            int updated = update.executeUpdate();
            if (updated == 0) throw new ApiException(404, "Notification not found.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, title, message, appointment_id, is_read, created_at FROM notifications WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return notificationToMap(rs);
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static void markAllNotificationsRead(int doctorId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE notifications SET is_read = TRUE WHERE doctor_id = ? AND is_read = FALSE")) {
            ps.setInt(1, doctorId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> notificationToMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("title", rs.getString("title"));
        m.put("message", rs.getString("message"));
        int apptId = rs.getInt("appointment_id");
        m.put("appointmentId", rs.wasNull() ? null : apptId);
        m.put("read", rs.getBoolean("is_read"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        m.put("createdAt", createdAt != null ? createdAt.toLocalDateTime() : null);
        return m;
    }

    // ---------------------------------------------------------------- Availability

    private void handleAvailability(HttpExchange ex, String method, List<String> segs, Map<String, Object> doctor) throws IOException {
        int doctorId = (Integer) doctor.get("id");

        if (segs.size() == 1) {
            if (method.equalsIgnoreCase("GET")) {
                Http.sendObject(ex, 200, listAvailability(doctorId));
            } else if (method.equalsIgnoreCase("POST")) {
                Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                Http.sendObject(ex, 201, addAvailability(doctorId, body));
            } else {
                throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
            }
            return;
        }

        int id = Http.requirePathInt(segs.get(1), "id");
        if (method.equalsIgnoreCase("PUT")) {
            Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
            Http.sendObject(ex, 200, updateAvailability(id, doctorId, body));
        } else if (method.equalsIgnoreCase("DELETE")) {
            deleteAvailability(id, doctorId);
            ex.sendResponseHeaders(204, -1); // no body, matches ResponseEntity.noContent()
        } else {
            throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
        }
    }

    private static List<Object> listAvailability(int doctorId) {
        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, day_of_week, start_time, end_time, active FROM doctor_availability " +
                             "WHERE doctor_id = ? ORDER BY FIELD(day_of_week,'MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'), start_time")) {
            ps.setInt(1, doctorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(availabilityToMap(rs));
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    private static Map<String, Object> addAvailability(int doctorId, Map<String, String> body) {
        AvailabilityInput in = parseAvailabilityInput(body);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO doctor_availability (doctor_id, day_of_week, start_time, end_time, active) VALUES (?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, doctorId);
            ps.setString(2, in.dayOfWeek.name());
            ps.setTime(3, java.sql.Time.valueOf(in.startTime));
            ps.setTime(4, java.sql.Time.valueOf(in.endTime));
            ps.setBoolean(5, in.active);
            ps.executeUpdate();
            int newId;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                newId = keys.getInt(1);
            }
            return findAvailability(newId, doctorId);
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> updateAvailability(int id, int doctorId, Map<String, String> body) {
        AvailabilityInput in = parseAvailabilityInput(body);
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE doctor_availability SET day_of_week = ?, start_time = ?, end_time = ?, active = ? WHERE id = ? AND doctor_id = ?")) {
            ps.setString(1, in.dayOfWeek.name());
            ps.setTime(2, java.sql.Time.valueOf(in.startTime));
            ps.setTime(3, java.sql.Time.valueOf(in.endTime));
            ps.setBoolean(4, in.active);
            ps.setInt(5, id);
            ps.setInt(6, doctorId);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new ApiException(404, "Availability slot not found.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return findAvailability(id, doctorId);
    }

    private static void deleteAvailability(int id, int doctorId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM doctor_availability WHERE id = ? AND doctor_id = ?")) {
            ps.setInt(1, id);
            ps.setInt(2, doctorId);
            int deleted = ps.executeUpdate();
            if (deleted == 0) throw new ApiException(404, "Availability slot not found.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> findAvailability(int id, int doctorId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, day_of_week, start_time, end_time, active FROM doctor_availability WHERE id = ? AND doctor_id = ?")) {
            ps.setInt(1, id);
            ps.setInt(2, doctorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Availability slot not found.");
                return availabilityToMap(rs);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> availabilityToMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("dayOfWeek", rs.getString("day_of_week"));
        m.put("startTime", rs.getTime("start_time").toLocalTime().toString());
        m.put("endTime", rs.getTime("end_time").toLocalTime().toString());
        m.put("active", rs.getBoolean("active"));
        return m;
    }

    private static class AvailabilityInput {
        DayOfWeek dayOfWeek;
        LocalTime startTime;
        LocalTime endTime;
        boolean active;
    }

    private static AvailabilityInput parseAvailabilityInput(Map<String, String> body) {
        ValidationErrors errors = new ValidationErrors();
        String dayStr = body.get("dayOfWeek");
        String startStr = body.get("startTime");
        String endStr = body.get("endTime");
        boolean active = body.containsKey("active") ? Validate.parseBool(body.get("active")) : true;

        errors.require(dayStr, "dayOfWeek", "Day of week is required.");
        errors.require(startStr, "startTime", "Start time is required.");
        errors.require(endStr, "endTime", "End time is required.");
        errors.throwIfInvalid();

        DayOfWeek dayOfWeek;
        try {
            dayOfWeek = DayOfWeek.valueOf(dayStr.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.validation("dayOfWeek", "Day of week is required.");
        }

        LocalTime startTime, endTime;
        try {
            startTime = LocalTime.parse(startStr.trim());
        } catch (Exception e) {
            throw ApiException.validation("startTime", "Start time is required.");
        }
        try {
            endTime = LocalTime.parse(endStr.trim());
        } catch (Exception e) {
            throw ApiException.validation("endTime", "End time is required.");
        }
        if (!endTime.isAfter(startTime)) {
            throw ApiException.validation("endTime", "End time must be after start time.");
        }

        AvailabilityInput in = new AvailabilityInput();
        in.dayOfWeek = dayOfWeek;
        in.startTime = startTime;
        in.endTime = endTime;
        in.active = active;
        return in;
    }

    // ---------------------------------------------------------------- Settings

    private void handleSettings(HttpExchange ex, String method, List<String> segs, AuthContext.CurrentUser user) throws IOException {
        if (segs.size() == 2 && segs.get(1).equals("password")) {
            requireMethod(method, "PUT");
            Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
            changePassword(user, body);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            Http.sendObject(ex, 200, out);
            return;
        }
        throw new ApiException(404, "Not found.");
    }

    private static void changePassword(AuthContext.CurrentUser user, Map<String, String> body) {
        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");

        ValidationErrors errors = new ValidationErrors();
        errors.require(currentPassword, "currentPassword", "Current password is required.");
        errors.password(newPassword, "newPassword");
        errors.throwIfInvalid();

        try (Connection conn = Database.getConnection()) {
            String currentHash;
            try (PreparedStatement ps = conn.prepareStatement("SELECT password_hash FROM users WHERE id = ?")) {
                ps.setInt(1, user.id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(401, "Please log in to continue.");
                    currentHash = rs.getString("password_hash");
                }
            }
            if (!PasswordUtil.matches(currentPassword, currentHash)) {
                throw new ApiException(400, "Current password is incorrect.");
            }
            String newHash = PasswordUtil.hash(newPassword);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?")) {
                ps.setString(1, newHash);
                ps.setInt(2, user.id);
                ps.executeUpdate();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    // ---------------------------------------------------------------- Shared: resolve current doctor

    private static Map<String, Object> resolveCurrentDoctor(int userId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT d.id, d.hospital_id, d.user_id, d.name, d.specialization, d.experience_years, " +
                             "d.image_url, d.consultation_fee_inr, h.name AS hospital_name " +
                             "FROM doctors d JOIN hospitals h ON d.hospital_id = h.id WHERE d.user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(403, "No doctor profile is linked to this account.");
                return doctorRowToMap(rs);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> resolveDoctorById(int doctorId) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT d.id, d.hospital_id, d.user_id, d.name, d.specialization, d.experience_years, " +
                             "d.image_url, d.consultation_fee_inr, h.name AS hospital_name " +
                             "FROM doctors d JOIN hospitals h ON d.hospital_id = h.id WHERE d.id = ?")) {
            ps.setInt(1, doctorId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Doctor not found.");
                return doctorRowToMap(rs);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> doctorRowToMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("hospitalId", rs.getInt("hospital_id"));
        int userId = rs.getInt("user_id");
        m.put("userId", rs.wasNull() ? null : userId);
        m.put("name", rs.getString("name"));
        m.put("specialization", rs.getString("specialization"));
        m.put("experienceYears", rs.getInt("experience_years"));
        m.put("imageUrl", rs.getString("image_url"));
        BigDecimal fee = rs.getBigDecimal("consultation_fee_inr");
        m.put("consultationFeeInr", fee != null ? fee : BigDecimal.ZERO);
        m.put("hospitalName", rs.getString("hospital_name"));
        return m;
    }

    private static BigDecimal parseBigDecimalOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
