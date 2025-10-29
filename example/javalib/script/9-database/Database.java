//| mvnDeps:
//| - org.jooq:jooq:3.19.8
//| - org.xerial:sqlite-jdbc:3.43.0.0
import org.jooq.*;
import org.jooq.impl.DSL;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;

import static org.jooq.impl.DSL.*;

public class Database {
  public static void main(String[] args) throws Exception {
    // Initialize database
    try (var conn = DriverManager.getConnection("jdbc:sqlite:./file.db")) {
      var ctx = DSL.using(conn, SQLDialect.SQLITE);

      // Populate database from SQL file - execute using raw JDBC connection
      String sql = java.nio.file.Files.readString(
        java.nio.file.Path.of("sqlite-customers.sql"),
        java.nio.charset.StandardCharsets.UTF_8
      );
      try (java.sql.Statement stmt = conn.createStatement()) {
        for (String statement : sql.split(";")) {
          String trimmed = statement.trim();
          if (!trimmed.isEmpty()) stmt.execute(trimmed);
        }
      }

      var buyer = table("buyer");
      var buyerId = field(name("buyer", "id"), Integer.class);
      var buyerName = field(name("buyer", "name"), String.class);
      var buyerDob = field(name("buyer", "date_of_birth"), LocalDate.class);

      var shipping = table("shipping_info");
      var shipId = field(name("shipping_info", "id"), Integer.class);
      var shipBuyerId = field(name("shipping_info", "buyer_id"), Integer.class);
      var shipDate = field(name("shipping_info", "shipping_date"), LocalDate.class);

      // Find names of buyers whose shipping date is today or later
      var names = ctx
        .select(buyerName)
        .from(buyer)
        .join(shipping)
        .on(buyerId.eq(shipBuyerId))
        .where(shipDate.ge(LocalDate.parse(args[0])))
        .fetchInto(String.class);

      names.forEach(System.out::println);
    }
  }
}
