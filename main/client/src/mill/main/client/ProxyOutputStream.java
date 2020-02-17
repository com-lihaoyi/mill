package mill.main.client;

import java.io.IOException;

public class ProxyOutputStream extends java.io.OutputStream {
    private java.io.OutputStream out;
    private int key;
    public ProxyOutputStream(java.io.OutputStream out, int key){
        this.out = out;
        this.key = key;
    }
    @Override synchronized public void write(int b) throws IOException {
        out.write(key);
        out.write(b);
    }
    @Override synchronized public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }
    @Override synchronized public void write(byte[] b, int off, int len) throws IOException {
        int i = 0;
        while(i < len && i + off < b.length){
            int chunkLength = Math.min(len - i, 127);
            out.write(chunkLength * key);
            out.write(b, off + i, Math.min(b.length - off - i, chunkLength));
            i += chunkLength;
        }
    }
    @Override public void flush() throws IOException {
        out.flush();
    }
    @Override public void close() throws IOException {
        out.close();
    }
}
