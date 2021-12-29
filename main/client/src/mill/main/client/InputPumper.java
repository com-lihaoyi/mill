package mill.main.client;

import java.io.InputStream;
import java.io.OutputStream;

public class InputPumper implements Runnable{
    private InputStream src;
    private OutputStream dest;
    private Boolean checkAvailable;
    private java.util.function.BooleanSupplier runningCheck;
    public InputPumper(InputStream src,
                       OutputStream dest,
                       Boolean checkAvailable){
        this(src, dest, checkAvailable, () -> true);
    }
    public InputPumper(InputStream src,
                       OutputStream dest,
                       Boolean checkAvailable,
                       java.util.function.BooleanSupplier runningCheck){
        this.src = src;
        this.dest = dest;
        this.checkAvailable = checkAvailable;
        this.runningCheck = runningCheck;
    }

    boolean running = true;
    public void run() {
        byte[] buffer = new byte[1024];
        try{
            while(running){
                if (!runningCheck.getAsBoolean()) {
                    running = false;
                }
                else if (checkAvailable && src.available() == 0) Thread.sleep(2);
                else {
                    int n = src.read(buffer);
                    if (n == -1) {
                        running = false;
                    }
                    else {
                        try {
                            dest.write(buffer, 0, n);
                            dest.flush();
                        }catch(java.io.IOException e){
                            running = false;
                        }
                    }
                }
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
}
