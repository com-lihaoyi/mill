package mill.client;

import java.io.IOException;
import java.io.OutputStream;

/// Writes data to an output stream.
///
/// Essentially a `Consumer<OutputStream>` but with a checked exception.
public interface OutputStreamWritable {
  void write(OutputStream in) throws IOException;
}
