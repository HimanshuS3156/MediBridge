package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/config -> { maxBookingDaysAhead } — a small set of public, non-secret config values
 * the frontend needs to mirror backend rules without hardcoding a second copy of them (e.g. the
 * date picker's max-selectable date). The backend remains the sole source of truth and
 * independently re-validates everything server-side (see AppointmentHandler.validateBookingDate).
 */
public class ConfigHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("maxBookingDaysAhead", Config.MAX_BOOKING_DAYS_AHEAD);
            Http.sendObject(ex, 200, out);
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }
}
