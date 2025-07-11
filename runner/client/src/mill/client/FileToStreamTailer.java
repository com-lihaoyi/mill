package mill.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Optional;

public class FileToStreamTailer extends Thread implements AutoCloseable {

  private final File file;
  private final PrintStream stream;
  private final int intervalMsec;

  // if true, we won't read the whole file, but only new lines
  private boolean ignoreHead = true;

  private volatile boolean keepReading = true;
  private volatile boolean flush = false;

  public FileToStreamTailer(File file, PrintStream stream, int intervalMsec) {
    super("FileToStreamTailerThread");
    this.intervalMsec = intervalMsec;
    setDaemon(true);
    this.file = file;
    this.stream = stream;
  }

  @Override
  public void run() {
    if (isInterrupted()) {
      keepReading = false;
    }
    Optional<BufferedReader> reader = Optional.empty();
    try {
      while (keepReading || flush) {
        flush = false;
        try {
          // Init reader, if not already done
          if (!reader.isPresent()) {
            try {
              reader = Optional.of(Files.newBufferedReader(file.toPath()));
            } catch (IOException e) {
              // nothing to ignore if file is initially missing
              ignoreHead = false;
            }
          }
          reader.ifPresent(r -> {
            // read lines
            try {
              String line;
              while ((line = r.readLine()) != null) {
                if (!ignoreHead) {
                  stream.println(line);
                }
              }
              // we ignored once
              this.ignoreHead = false;
            } catch (IOException e) {
              // could not read line or file vanished
            }
          });
        } finally {
          if (keepReading) {
            // wait
            try {
              Thread.sleep(intervalMsec);
            } catch (InterruptedException e) {
              // can't handle anyway
            }
          }
        }
      }
    } finally {
      reader.ifPresent(r -> {
        try {
          r.close();
        } catch (IOException e) {
          // could not close but also can't do anything about it
        }
      });
    }
  }

  @Override
  public void interrupt() {
    this.keepReading = false;
    super.interrupt();
  }

  /**
   * Force a next read, even if we interrupt the thread.
   */
  public void flush() {
    this.flush = true;
  }

  @Override
  public void close() throws Exception {
    flush();
    interrupt();
  }
}
