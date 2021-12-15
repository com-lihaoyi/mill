package mill.main.client;

import java.io.InputStream;
import java.io.OutputStream;

public class InputPumper implements Runnable{
    private InputStream src;
    private OutputStream dest;
    private Boolean checkAvailable;
    public InputPumper(InputStream src,
                       OutputStream dest,
                       Boolean checkAvailable){
        this.src = src;
        this.dest = dest;
        this.checkAvailable = checkAvailable;
    }

    boolean running = true;
    public void run() {
        byte[] buffer = new byte[1024];
        try{
            while(running){
                if (checkAvailable && src.available() == 0) Thread.sleep(2);
                else {
                    int n = src.read(buffer);
                    if (n == -1) running = false;
                    else {
                        dest.write(buffer, 0, n);
                        dest.flush();
                    }
                }
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
}
