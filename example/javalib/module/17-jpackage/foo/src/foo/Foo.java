package foo;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

public class Foo {
  private static final Logger LOGGER = Logger.getLogger(Foo.class.getName());

  public static String readConf() throws IOException {
    InputStream inputStream = Foo.class.getClassLoader().getResourceAsStream("application.conf");
    String conf = new String(inputStream.readAllBytes());
    LOGGER.info("Loaded application.conf from resources: " + conf);
    return conf;
  }
}
