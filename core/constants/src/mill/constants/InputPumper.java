package mill.constants;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/** A `Runnable` that reads from `src` and writes to `dest`. */
public class InputPumper implements Runnable {
  private final Supplier<InputStream> src0;
  private final Supplier<OutputStream> dest0;

  /// Controls whether we check `.available` before calling `.read`.
  ///
  /// We need to do that because if we call `.read`
  /// and there is nothing to read, [it can unnecessarily delay the JVM exit by 350ms](
  ///
  // https://stackoverflow.com/questions/48951611/blocking-on-stdin-makes-java-process-take-350ms-more-to-exit)
  private final boolean checkAvailable;

  public InputPumper(
      Supplier<InputStream> src, Supplier<OutputStream> dest, Boolean checkAvailable) {
    this.src0 = src;
    this.dest0 = dest;
    this.checkAvailable = checkAvailable;
  }

  boolean running = true;

  @Override
  public void run() {
    var src = src0.get();
    var dest = dest0.get();

    var buffer = new byte[1024 /* 1kb */];
    try {
      while (running) {
        if (checkAvailable && src.available() == 0)
          //noinspection BusyWait
          Thread.sleep(1);
        else {
          int bytesRead;
          try {
            bytesRead = src.read(buffer);
          } catch (Exception e) {
            bytesRead = -1;
          }
          if (bytesRead == -1) running = false;
          else if (bytesRead == 0)
            //noinspection BusyWait
            Thread.sleep(1);
          else {
            try {
              dest.write(buffer, 0, bytesRead);
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
