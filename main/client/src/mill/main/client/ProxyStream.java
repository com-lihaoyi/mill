package mill.main.client;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
        private OutputStream dest1;
        private OutputStream dest2;
        private long last = System.currentTimeMillis();
        private boolean running = true;
        public Pumper(InputStream src, OutputStream destOut, OutputStream destErr){
            this.src = src;
            this.dest1 = destOut;
            this.dest2 = destErr;
        }

        public void waitForSilence(int millis) throws InterruptedException {
            do {
                Thread.sleep(10);
            } while ((System.currentTimeMillis() - last) < millis);
        }

        public boolean isRunning() {
            return running;
        }

        public void run() {

            System.out.println("Pumper.run");
            byte[] buffer = new byte[1024];
            while (running) {
                try {
                    int quantity0 = (byte)src.read();
                    System.out.println("Pumper.run quantity0 " + quantity0);
                    if (quantity0 != 0) {
                        int quantity = Math.abs(quantity0);
                        int offset = 0;
                        int delta = -1;
                        while (offset < quantity) {
                            delta = src.read(buffer, offset, quantity - offset);
                            if (delta == -1) {
                                running = false;
                                break;
                            } else {
                                offset += delta;
                            }
                        }

                        if (delta != -1) {
                            if (quantity0 > 0) dest1.write(buffer, 0, offset);
                            else dest2.write(buffer, 0, offset);
                            flush();
                            this.last = System.currentTimeMillis();
                        }
                    }else {

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
            try {
                dest1.close();
                dest2.close();
            } catch(IOException e) {}
        }

        public void flush() throws IOException {
            dest1.flush();
            dest2.flush();
        }

        public void stop() {
            running = false;
        }
    }
}
