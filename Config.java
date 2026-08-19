package medtour;

import java.math.BigDecimal;

/** App-wide configuration, overridable via environment variables. */
public class Config {
    private Config() {}

    public static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    public static final int MAX_BOOKING_DAYS_AHEAD =
            Integer.parseInt(System.getenv().getOrDefault("MEDTOUR_BOOKING_WINDOW_DAYS", "180"));

    public static final BigDecimal AIRPORT_PICKUP_FEE_INR =
            new BigDecimal(System.getenv().getOrDefault("MEDTOUR_FEE_AIRPORT_PICKUP", "1500"));

    public static final BigDecimal TRAVEL_ASSISTANCE_FEE_INR =
            new BigDecimal(System.getenv().getOrDefault("MEDTOUR_FEE_TRAVEL_ASSISTANCE", "3500"));

    public static final String FRONTEND_BASE_URL =
            System.getenv().getOrDefault("MEDTOUR_FRONTEND_URL", "http://localhost:8080");

    public static final long VERIFICATION_TOKEN_EXPIRY_HOURS =
            Long.parseLong(System.getenv().getOrDefault("MEDTOUR_VERIFY_TOKEN_HOURS", "24"));
}
