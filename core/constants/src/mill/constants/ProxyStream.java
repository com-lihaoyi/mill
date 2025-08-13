package mill.constants;

import java.io.*;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Logic to capture a pair of streams (typically stdout and stderr), combining
/// them into a single stream, and splitting it back into two streams later while
/// preserving ordering. This is useful for capturing stderr and stdout and forwarding
/// them to a terminal while strictly preserving the ordering, i.e. users won't see
/// exception stack traces and printlns arriving jumbled up and impossible to debug
///
/// This works by converting writes from either of the two streams into packets of
/// the form:
/// ```
///  1 byte         n bytes
/// | header |         body |
/// ```
///
/// Where header is a single byte of the form:
///
///   - header more than 0 indicating that this packet is for the `OUT` stream
///   - header less than 0 indicating that this packet is for the `ERR` stream
///   - abs(header) indicating the length of the packet body, in bytes
///   - header == 0 indicating the end of the stream
///
///
/// Writes to either of the two `Output`s are synchronized on the shared
/// `destination` stream, ensuring that they always arrive complete and without
/// interleaving. On the other side, a `Pumper` reads from the combined
/// stream, forwards each packet to its respective destination stream, or terminates
/// when it hits a packet with `header == 0`
public class ProxyStream {
  private static final int MAX_CHUNK_SIZE = 256 * 1024; // 256kb

  /** Indicates the end of the connection. */
  private static final byte HEADER_END = 0;

  /** The key for the output stream */
  private static final byte HEADER_STREAM_OUT = 1;

  /** The key for the error stream */
  private static final byte HEADER_STREAM_ERR = 2;

  /** A heartbeat packet to keep the connection alive. */
  private static final byte HEADER_HEARTBEAT = 127;

  public enum StreamType {
    /** The output stream */
    OUT(ProxyStream.HEADER_STREAM_OUT),
    /** The error stream */
    ERR(ProxyStream.HEADER_STREAM_ERR);

    public final byte header;

    StreamType(byte key) {
      this.header = key;
    }
  }

  private static boolean clientHasClosedConnection(SocketException e) {
    var message = e.getMessage();
    return message != null && message.contains("Broken pipe");
  }

  public static void sendEnd(OutputStream out, int exitCode) throws IOException {
    synchronized (out) {
      try {
        var buffer = new byte[5];
        ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).put(ProxyStream.HEADER_END).putInt(exitCode);
        out.write(buffer);
        out.flush();
      } catch (SocketException e) {
        // If the client has already closed the connection, we don't really care about sending the
        // exit code to it.
        if (!clientHasClosedConnection(e)) throw e;
      }
    }
  }

  public static void sendHeartbeat(OutputStream out) throws IOException {
    synchronized (out) {
      out.write(ProxyStream.HEADER_HEARTBEAT);
      out.flush();
    }
  }

  public static class Output extends java.io.OutputStream {
    private final DataOutputStream destination;
    private final StreamType streamType;

    public Output(java.io.OutputStream out, StreamType streamType) {
      this.destination = new DataOutputStream(out);
      this.streamType = streamType;
    }

    @Override
    public void write(int b) throws IOException {
      synchronized (destination) {
        destination.writeByte(streamType.header);
        destination.writeInt(1); // 1 byte
        destination.writeByte(b);
      }
    }

    @Override
    public void write(byte[] b) throws IOException {
      if (b.length > 0) {
        synchronized (destination) {
          write(b, 0, b.length);
        }
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      // Validate arguments once at the beginning, which is cleaner
      // and standard practice for public methods.
      if (b == null) throw new NullPointerException("byte array is null");
      else if (off < 0 || off > b.length || len < 0 || off + len > b.length || off + len < 0) {
        throw new IndexOutOfBoundsException(
          "Write indexes out of range: off=" + off + ", len=" + len + ", b.length=" + b.length
        );
      }

      synchronized (destination) {
        var bytesWritten = 0;

        while (bytesWritten < len) {
          var chunkLength = Math.min(len - bytesWritten, MAX_CHUNK_SIZE);

          if (chunkLength > 0) {
            destination.writeByte(streamType.header);
            destination.writeInt(chunkLength);
            destination.write(b, off + bytesWritten, chunkLength);
            bytesWritten += chunkLength;
          }
        }
      }
    }

    @Override
    public void flush() throws IOException {
      synchronized (destination) {
        destination.flush();
      }
    }

    @Override
    public void close() throws IOException {
      synchronized (destination) {
        destination.close();
      }
    }
  }

  public static class Pumper implements Runnable {
    private final DataInputStream src;
    private final OutputStream destOut;
    private final OutputStream destErr;
    private final Object synchronizer;
    public volatile int exitCode = 255;

    public Pumper(
        InputStream src, OutputStream destOut, OutputStream destErr, Object synchronizer) {
      this.src = new DataInputStream(src);
      this.destOut = destOut;
      this.destErr = destErr;
      this.synchronizer = synchronizer;
    }

    public Pumper(InputStream src, OutputStream destOut, OutputStream destErr) {
      this(src, destOut, destErr, new Object());
    }

    public void preRead(DataInputStream src) {}

    @Override
    public void run() {
      var buffer = new byte[MAX_CHUNK_SIZE];
      try {
        while (true) {
          this.preRead(src);
          var header = src.readByte();

          if (header == HEADER_END) {
            exitCode = src.readInt();
            break;
          }
          else if (header == HEADER_HEARTBEAT) continue;
          else if (header == HEADER_STREAM_OUT) pumpData(buffer, destOut);
          else if (header == HEADER_STREAM_ERR) pumpData(buffer, destErr);
          else throw new IllegalStateException("Unexpected header: " + header);
        }
      }
      catch (EOFException ignored) {
        // This is a normal and expected way for the loop to terminate
        // when the other side closes the connection.
      }
      catch (IOException ignored) {
        // This happens when the upstream pipe was closed
      }

      try {
        synchronized (synchronizer) {
          destOut.flush();
          destErr.flush();
        }
      } catch (IOException ignored) {
      }
    }

    private void pumpData(byte[] buffer, OutputStream stream) throws IOException {
      var quantity = src.readInt();

      if (quantity > buffer.length) {
        // Handle error: received chunk is larger than buffer
        throw new IOException("Received chunk of size " + quantity +
          " is larger than buffer of size " + buffer.length);
      }

      src.readFully(buffer, 0, quantity);

      synchronized (synchronizer) {
        stream.write(buffer, 0, quantity);
      }
    }

    public void flush() throws IOException {
      synchronized (synchronizer) {
        destOut.flush();
        destErr.flush();
      }
    }
  }
}
