package medtour;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
/**
 * Central place for the MySQL connection.
 * EDIT THESE THREE VALUES to match your local MySQL setup, or set the env vars below.
 */
public class Database {
    private static final String URL      = System.getenv().getOrDefault("MEDTOUR_DB_URL",
            "jdbc:mysql://btxvagdo6gi2no0xk2gt-mysql.services.clever-cloud.com:3306/btxvagdo6gi2no0xk2gt?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
    private static final String USER     = System.getenv().getOrDefault("MEDTOUR_DB_USER", "ugvha538e1ur62u0");
    private static final String PASSWORD = System.getenv().getOrDefault("MEDTOUR_DB_PASSWORD", "Eg13KYO1D2YHFbINkSGs"); // <-- change to your MySQL password
    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found on classpath. " +
                    "Download mysql-connector-j and put it in backend/lib, see README.", e);
        }
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
