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
        // We need to check `.available` and avoid calling `.read`, because if we call `.read`
        // and there is nothing to read, it can unnecessarily delay the JVM exit by 350ms
        // https://stackoverflow.com/questions/48951611/blocking-on-stdin-makes-java-process-take-350ms-more-to-exit
        if (checkAvailable && src.available() == 0) {
          // if `runningCheck` fails, we do not continue sleeping and waiting for input,
          // and instead exit the loop since there is nothing to read now and there will be
          // nothing to read going forward
          if (!runningCheck.getAsBoolean()) running = false;
          else Thread.sleep(1);
        } else {
          int n;
          try {
            n = src.read(buffer);
          } catch (Exception e) {
            n = -1;
          }
          if (n == -1) running = false;
          else if (n == 0) Thread.sleep(1);
          else {
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
