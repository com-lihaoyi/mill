package mill.main.client;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ProxyStreamPumper implements Runnable{
    private InputStream src;
    private OutputStream dest1;
    private OutputStream dest2;
    public ProxyStreamPumper(InputStream src, OutputStream dest1, OutputStream dest2){
        this.src = src;
        this.dest1 = dest1;
        this.dest2 = dest2;
    }

    public void run() {
        byte[] buffer = new byte[1024];
        boolean running = true;
        boolean first = true;
        while (running) {
            try {
                int quantity0 = (byte)src.read();
                int quantity = Math.abs(quantity0);
                int offset = 0;
                int delta = -1;
                while(offset < quantity){
                    delta = src.read(buffer, offset, quantity - offset);
                    if (delta == -1) {
                        running = false;
                        break;
                    }else{
                        offset += delta;
                    }
                }

                if (delta != -1){
                    if (quantity0 > 0) dest1.write(buffer, 0, offset);
                    else dest2.write(buffer, 0, offset);
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

}
