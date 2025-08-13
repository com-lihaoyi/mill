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
  public static final int MAX_CHUNK_SIZE = 256 * 1024; // 256kb

  /** Indicates the end of the connection. */
  private static final byte HEADER_END = 0;

  /** The header for the output stream */
  private static final byte HEADER_STREAM_OUT = 1;

  /** The header for the output stream when a single byte is sent. */
  private static final byte HEADER_STREAM_OUT_SINGLE_BYTE = 2;

  /** The header for the error stream */
  private static final byte HEADER_STREAM_ERR = 3;

  /** The header for the error stream when a single byte is sent. */
  private static final byte HEADER_STREAM_ERR_SINGLE_BYTE = 4;

  /** A heartbeat packet to keep the connection alive. */
  private static final byte HEADER_HEARTBEAT = 127;

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

  private static boolean clientHasClosedConnection(SocketException e) {
    var message = e.getMessage();
    return message != null && message.contains("Broken pipe");
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
    private final DataOutputStream destination;
    private final StreamType streamType;

    public Output(java.io.OutputStream out, StreamType streamType) {
      this.destination = new DataOutputStream(out);
      this.streamType = streamType;
    }

    @Override
    public void write(int b) throws IOException {
      synchronized (destination) {
        destination.write(streamType.headerSingleByte);
        destination.write(b);
      }
    }

    @Override
    public void write(byte[] b) throws IOException {
      if (b.length <= 0) return;

      if (b.length == 1) write(b[0]);
      else write(b, 0, b.length);
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

        while (bytesWritten < len && bytesWritten + off < b.length) {
          var chunkLength = Math.min(len - bytesWritten, MAX_CHUNK_SIZE);

          if (chunkLength > 0) {
            destination.write(streamType.header);
            destination.writeInt(chunkLength);
            var bytesToWrite = Math.min(b.length - off - bytesWritten, chunkLength);
            destination.write(b, off + bytesWritten, bytesToWrite);
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

      src.readFully(buffer, 0, quantity);

      synchronized (synchronizer) {
        write(stream, buffer, quantity);
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
