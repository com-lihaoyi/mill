package mill.main.client;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Logic to capture a pair of streams (typically stdout and stderr), combining
 * them into a single stream, and splitting it back into two streams later while
 * preserving ordering. This is useful for capturing stderr and stdout and forwarding
 * them to a terminal while strictly preserving the ordering, i.e. users won't see
 * exception stack traces and printlns arriving jumbled up and impossible to debug
 *
 * This works by converting writes from either of the two streams into packets of
 * the form:
 *
 *  1 byte         n bytes
 * | header |         body |
 *
 * Where header is a single byte of the form:
 *
 * - header more than 0 indicating that this packet is for the `OUT` stream
 * - header less than 0 indicating that this packet is for the `ERR` stream
 * - abs(header) indicating the length of the packet body, in bytes
 * - header == 0 indicating the end of the stream
 *
 * Writes to either of the two `Output`s are synchronized on the shared
 * `destination` stream, ensuring that they always arrive complete and without
 * interleaving. On the other side, a `Pumper` reads from the combined
 * stream, forwards each packet to its respective destination stream, or terminates
 * when it hits a packet with `header == 0`
 */
public class ProxyStream{

    public static final int OUT = 1;
    public static final int ERR = -1;
    public static final int END = 0;

    public static void sendEnd(OutputStream out) throws IOException {
        synchronized(out){
            out.write(ProxyStream.END);
            out.flush();
        }
    }

    public static class Output extends java.io.OutputStream {
        private java.io.OutputStream destination;
        private int key;

        public Output(java.io.OutputStream out, int key){
            this.destination = out;
            this.key = key;
        }

        @Override public void write(int b) throws IOException {
            synchronized (destination){
                destination.write(key);
                destination.write(b);
            }
        }

        @Override public void write(byte[] b) throws IOException {
            if (b.length > 0) {
                synchronized (destination) {
                    write(b, 0, b.length);
                }
            }
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {

            synchronized (destination) {
                int i = 0;
                while (i < len && i + off < b.length) {
                    int chunkLength = Math.min(len - i, 127);
                    if (chunkLength > 0) {
                        destination.write(chunkLength * key);
                        destination.write(b, off + i, Math.min(b.length - off - i, chunkLength));
                        i += chunkLength;
                    }
                }
            }
        }

        @Override public void flush() throws IOException {
            synchronized (destination) {
                destination.flush();
            }
        }

        @Override public void close() throws IOException {
            synchronized (destination) {
                destination.close();
            }
        }
    }

    public static class Pumper implements Runnable{
        private InputStream src;
        private OutputStream destOut;
        private OutputStream destErr;
        private Object synchronizer;
        public Pumper(InputStream src, OutputStream destOut, OutputStream destErr, Object synchronizer){
            this.src = src;
            this.destOut = destOut;
            this.destErr = destErr;
            this.synchronizer = synchronizer;
        }
        public Pumper(InputStream src, OutputStream destOut, OutputStream destErr){
            this(src, destOut, destErr, new Object());
        }

        public void preRead(InputStream src){}

        public void preWrite(byte[] buffer, int length){}

        public void run() {

            byte[] buffer = new byte[1024];
            while (true) {
                try {
                    this.preRead(src);
                    int header = src.read();
                    // -1 means socket was closed, 0 means a ProxyStream.END was sent. Note
                    // that only header values > 0 represent actual data to read:
                    // - sign((byte)header) represents which stream the data should be sent to
                    // - abs((byte)header) represents the length of the data to read and send
                    if (header == -1 || header == 0) break;
                    else {
                        int stream = (byte) header > 0 ? 1 : -1;
                        int quantity0 = (byte) header;
                        int quantity = Math.abs(quantity0);
                        int offset = 0;
                        int delta = -1;
                        while (offset < quantity) {
                            this.preRead(src);
                            delta = src.read(buffer, offset, quantity - offset);
                            if (delta == -1) {
                                break;
                            } else {
                                offset += delta;
                            }
                        }

                        if (delta != -1) {
                            synchronized (synchronizer) {
                                this.preWrite(buffer, offset);
                                switch(stream){
                                    case ProxyStream.OUT: destOut.write(buffer, 0, offset); break;
                                    case ProxyStream.ERR: destErr.write(buffer, 0, offset); break;
                                }
                            }
                        }
                    }
                } catch (org.newsclub.net.unix.ConnectionResetSocketException e) {
                    // This happens when you run mill shutdown and the server exits gracefully
                    break;
                } catch (IOException e) {
                    // This happens when the upstream pipe was closed
                    break;
                }
            }

            try {
                synchronized (synchronizer) {
                    destOut.flush();
                    destErr.flush();
                }
            } catch(IOException e) {}
        }

        public void flush() throws IOException {
            synchronized (synchronizer) {
                destOut.flush();
                destErr.flush();
            }
        }
    }
}
