package mill.client.debug;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

public class DebuggingOutputStream extends FilterOutputStream {
  private final OutputStream debugOutput;
  private final boolean writeSeparateOps;

  public DebuggingOutputStream(OutputStream out, Path workingDir, String name, boolean writeSeparateOps) {
    super(out);

    this.writeSeparateOps = writeSeparateOps;
    try {
      this.debugOutput = new FileOutputStream(workingDir.resolve(name.replaceAll("\\W", "_")).toFile());
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (writeSeparateOps) debugOutput.write(
      (
        LocalDateTime.now() + " write(): " + b + "\n" /*+
        Arrays.stream(new Exception().getStackTrace()).map(StackTraceElement::toString).collect(Collectors.joining("\n"))*/
      ).getBytes()
    );
    else debugOutput.write(b);

    super.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (writeSeparateOps) debugOutput.write(
      (
        LocalDateTime.now() + " write(off=" + off + ", len=" + len + "): " + new String(b, off, len) + "\n"
      ).getBytes()
    );
    else debugOutput.write(b, off, len);

    super.write(b, off, len);
  }
}
