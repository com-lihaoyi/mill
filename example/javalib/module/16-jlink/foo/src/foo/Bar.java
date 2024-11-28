package foo;

import java.util.logging.Logger;

public class Bar {
  private static final Logger LOG = Logger.getLogger(Bar.class.getName());

  public static void main(String[] args) {
    LOG.info("Hello World!");
  }
}
