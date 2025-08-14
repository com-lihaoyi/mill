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
/// | header |         frame |
/// ```
///
/// Where header is a single byte of the form:
///
///   - [#HEADER_STREAM_OUT]/[#HEADER_STREAM_ERR] respectively indicating that this packet is for the `OUT`/`ERR`
///     stream, and it will be followed by 4 bytes for the length of the body and then the body.
///   - [#HEADER_STREAM_OUT_SINGLE_BYTE]/[#HEADER_STREAM_ERR_SINGLE_BYTE] respectively indicating that this packet is
///     for the `OUT`/`ERR` stream, and it will be followed by a single byte for the body
///   - [#HEADER_HEARTBEAT] indicating that this packet is a heartbeat and will be ignored
///   - [#HEADER_END] indicating the end of the stream
///
///
/// Writes to either of the two `Output`s are synchronized on the shared
/// `destination` stream, ensuring that they always arrive complete and without
/// interleaving. On the other side, a `Pumper` reads from the combined
/// stream, forwards each packet to its respective destination stream, or terminates
/// when it hits a packet with [#HEADER_END].
public class ProxyStream {
  public static final int MAX_CHUNK_SIZE = 126; // 32 * 1024; // 32kb

  // The values are picked to make it a bit easier to spot when debugging the hex dump.

  /** The header for the output stream */
  private static final byte HEADER_STREAM_OUT = 26; // 0x1A

  /** The header for the output stream when a single byte is sent. */
  private static final byte HEADER_STREAM_OUT_SINGLE_BYTE = 27; // 0x1B, B as in BYTE

  /** The header for the error stream */
  private static final byte HEADER_STREAM_ERR = 42; // 0x2A

  /** The header for the error stream when a single byte is sent. */
  private static final byte HEADER_STREAM_ERR_SINGLE_BYTE = 43; // 0x2B, B as in BYTE

  /** A heartbeat packet to keep the connection alive. */
  private static final byte HEADER_HEARTBEAT = 123; // 0x7B, B as in BEAT

  /** Indicates the end of the connection. */
  private static final byte HEADER_END = 126; // 0x7E, E as in END

  public enum StreamType {
    /** The output stream */
    OUT(ProxyStream.HEADER_STREAM_OUT, ProxyStream.HEADER_STREAM_OUT_SINGLE_BYTE),
    /** The error stream */
    ERR(ProxyStream.HEADER_STREAM_ERR, ProxyStream.HEADER_STREAM_ERR_SINGLE_BYTE);

    public final byte header, headerSingleByte;

    StreamType(byte header, byte headerSingleByte) {
      this.header = header;
      this.headerSingleByte = headerSingleByte;
    }
  }

  public static boolean clientHasClosedConnection(SocketException e) {
    var message = e.getMessage();
    return message != null && (
      message.contains("Broken pipe")
        || message.contains("Socket closed")
        || message.contains("Connection reset by peer")
    );
  }

  public static void sendEnd(OutputStream out, int exitCode) throws IOException {
    synchronized (out) {
      try {
        var buffer = new byte[5];
        ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
          .put(ProxyStream.HEADER_END)
          .putInt(exitCode);
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
    /**
     * Object used for synchronization so that our writes wouldn't interleave.
     * <p>
     * We can't use {@link #destination} because it's a private object that we create here and {@link #sendEnd}
     * and {@link #sendHeartbeat} use a different object.
     **/
    private final java.io.OutputStream synchronizer;

    private final DataOutputStream destination;
    private final StreamType streamType;

    public Output(java.io.OutputStream out, StreamType streamType) {
      this.synchronizer = out;
      this.destination = new DataOutputStream(out);
      this.streamType = streamType;
    }

    @Override
    public void write(int b) throws IOException {
      synchronized (synchronizer) {
        destination.write(streamType.headerSingleByte);
        destination.write(b);
      }
    }

    @Override
    public void write(byte[] b) throws IOException {
      switch (b.length) {
        case 0:
          return;
        case 1:
          write(b[0]);
          break;
        default:
          write(b, 0, b.length);
          break;
      }
    }

    @Override
    public void write(byte[] sourceBuffer, int offset, int len) throws IOException {
      // Validate arguments once at the beginning, which is cleaner
      // and standard practice for public methods.
      if (sourceBuffer == null) throw new NullPointerException("byte array is null");
      if (offset < 0 || offset > sourceBuffer.length) throw new IndexOutOfBoundsException("Write offset out of range: " + offset);
      if (len < 0) throw new IndexOutOfBoundsException("Write length is negative: " + len);
      if (offset + len > sourceBuffer.length) throw new IndexOutOfBoundsException(
        "Write goes beyond end of buffer: offset=" + offset + ", len=" + len + ", end=" + (offset + len) + " > " + sourceBuffer.length
      );

      synchronized (synchronizer) {
        var bytesRemaining = len;
        var currentOffset = offset;

        while (bytesRemaining > 0) {
          var chunkSize = Math.min(bytesRemaining, MAX_CHUNK_SIZE);

          destination.writeByte(streamType.header);
          destination.writeInt(chunkSize);
          destination.write(sourceBuffer, currentOffset, chunkSize);

          bytesRemaining -= chunkSize;
          currentOffset += chunkSize;
        }
      }
    }

    @Override
    public void flush() throws IOException {
      synchronized (synchronizer) {
        destination.flush();
      }
    }

    @Override
    public void close() throws IOException {
      synchronized (synchronizer) {
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

    public void write(OutputStream dest, byte[] buffer, int length) throws IOException {
      dest.write(buffer, 0, length);
    }

    @Override
    public void run() {
      var buffer = new byte[MAX_CHUNK_SIZE];
      try {
        readLoop:
        while (true) {
          this.preRead(src);
          var header = src.readByte();

          switch (header) {
            case HEADER_END:
              exitCode = src.readInt();
              break readLoop;
            case HEADER_HEARTBEAT:
              continue;
            case HEADER_STREAM_OUT:
              pumpData(buffer, false, destOut);
              break;
            case HEADER_STREAM_OUT_SINGLE_BYTE:
              pumpData(buffer, true, destOut);
              break;
            case HEADER_STREAM_ERR:
              pumpData(buffer, false, destErr);
              break;
            case HEADER_STREAM_ERR_SINGLE_BYTE:
              pumpData(buffer, true, destErr);
              break;
            default:
              throw new IllegalStateException("Unexpected header: " + header);
          }
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

    private void pumpData(byte[] buffer, boolean singleByte, OutputStream stream) throws IOException {
      var quantity = singleByte ? 1 : src.readInt();

      if (quantity > buffer.length) {
        // Handle error: received chunk is larger than buffer
        throw new IOException("Received chunk of size " + quantity +
          " is larger than buffer of size " + buffer.length);
      }

      var totalBytesRead = 0;
      var bytesReadThisIteration = -1;
      while (totalBytesRead < quantity) {
        this.preRead(src);
        bytesReadThisIteration = src.read(buffer, totalBytesRead, quantity - totalBytesRead);
        if (bytesReadThisIteration == -1) {
          break;
        } else {
          totalBytesRead += bytesReadThisIteration;
        }
      }

      if (bytesReadThisIteration != -1) {
        synchronized (synchronizer) {
          this.write(stream, buffer, totalBytesRead);
        }
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
