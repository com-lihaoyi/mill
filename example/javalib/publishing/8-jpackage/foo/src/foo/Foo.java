package foo;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Foo {
  public static final Logger LOGGER = Logger.getLogger(Foo.class.getName());

  static {
    // Configure the logger to use a custom formatter
    for (Handler handler : LOGGER.getParent().getHandlers()) {
      handler.setFormatter(new SimpleFormatter() {
        @Override
        public String format(LogRecord record) {
          // Return the log level, message, but omit the timestamp
          return String.format("%s: %s%n", record.getLevel(), record.getMessage());
        }
      });
    }
  }

  public static String readConf() throws IOException {
    InputStream inputStream = Foo.class.getClassLoader().getResourceAsStream("application.conf");
    String conf = new String(inputStream.readAllBytes());
    LOGGER.info("Loaded application.conf from resources: " + conf);
    return conf;
  }
}
