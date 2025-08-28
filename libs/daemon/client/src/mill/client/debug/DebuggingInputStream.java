package mill.client.debug;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDateTime;

/** Writes everything you read from the input stream to a file as well. */
public class DebuggingInputStream extends InputStream {

  private final InputStream in;
  private final OutputStream debugOutput;
  private final boolean writeSeparateOps;

  public DebuggingInputStream(
      InputStream in, Path workingDir, String name, boolean writeSeparateOps) {
    this.in = in;
    this.writeSeparateOps = writeSeparateOps;
    try {
      this.debugOutput =
          new FileOutputStream(workingDir.resolve(name.replaceAll("\\W", "_")).toFile());
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int read() throws IOException {
    int b = in.read();
    if (b != -1) {
      if (writeSeparateOps)
        debugOutput.write((LocalDateTime.now() + " read(): " + b + "\n").getBytes());
      else debugOutput.write(b);
    }
    return b;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int bytesRead = in.read(b, off, len);
    if (bytesRead != -1) {
      if (writeSeparateOps)
        debugOutput.write((LocalDateTime.now() + "readArray(off=" + off + ", len=" + len
                + ", bytesRead=" + bytesRead + "): " + new String(b, off, bytesRead) + "\n")
            .getBytes());
      else debugOutput.write(b, off, bytesRead);
    }
    return bytesRead;
  }

  @Override
  public void close() throws IOException {
    try {
      super.close();
    } finally {
      debugOutput.close();
    }
  }
}
