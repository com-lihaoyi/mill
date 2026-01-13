package mill.daemon;

import mill.constants.MillException;

public class MillBspMain {
  public static void main(String[] args) {
    try {
      VersionCheck.check();
      MillNoDaemonMain0.main(args);
    } catch (MillException e) {
      // Print clean error message without stack trace for expected errors
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }
}
