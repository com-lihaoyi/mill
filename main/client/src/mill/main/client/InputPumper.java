package mill.main.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

public class InputPumper implements Runnable {
  private Supplier<InputStream> src0;
  private Supplier<OutputStream> dest0;

  private Boolean checkAvailable;
  private java.util.function.BooleanSupplier runningCheck;

  public InputPumper(
      Supplier<InputStream> src, Supplier<OutputStream> dest, Boolean checkAvailable) {
    this(src, dest, checkAvailable, () -> true);
  }

  public InputPumper(
      Supplier<InputStream> src,
      Supplier<OutputStream> dest,
      Boolean checkAvailable,
      java.util.function.BooleanSupplier runningCheck) {
    this.src0 = src;
    this.dest0 = dest;
    this.checkAvailable = checkAvailable;
    this.runningCheck = runningCheck;
  }

  boolean running = true;

  public void run() {
    InputStream src = src0.get();
    OutputStream dest = dest0.get();

    byte[] buffer = new byte[1024];
    try {
      while (running) {
        if (!runningCheck.getAsBoolean()) {
          running = false;
        } else if (checkAvailable && src.available() == 0) Thread.sleep(2);
        else {
          int n;
          try {
            n = src.read(buffer);
          } catch (Exception e) {
            n = -1;
          }
          if (n == -1) {
            running = false;
          } else {
            try {
              dest.write(buffer, 0, n);
              dest.flush();
            } catch (java.io.IOException e) {
              running = false;
            }
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
