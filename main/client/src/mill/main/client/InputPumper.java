package mill.main.client;

import java.io.InputStream;
import java.io.OutputStream;

public class InputPumper implements Runnable{
    private InputStream src;
    private OutputStream dest;
    private Boolean checkAvailable;
    private java.util.function.BooleanSupplier runningCheck;
    private String name;
    public InputPumper(InputStream src,
                       OutputStream dest,
                       Boolean checkAvailable){
        this(src, dest, checkAvailable, () -> true, "");
    }
    public InputPumper(InputStream src,
                       OutputStream dest,
                       Boolean checkAvailable,
                       java.util.function.BooleanSupplier runningCheck,
                        String name){
        this.src = src;
        this.dest = dest;
        this.checkAvailable = checkAvailable;
        this.runningCheck = runningCheck;
        this.name = name;
    }

    boolean running = true;
    public void run() {
        byte[] buffer = new byte[1024];
        try{
            while(running){
                if (!runningCheck.getAsBoolean()) {
                    System.out.println("!runningCheck.getAsBoolean() " + name);
                    running = false;
                }
                else if (checkAvailable && src.available() == 0) Thread.sleep(2);
                else {
                    int n = src.read(buffer);
                    if (n == -1) {
                        System.out.println("n == -1 " + name);
                        running = false;
                    }
                    else {
                        try {
                            dest.write(buffer, 0, n);
                            dest.flush();
                        }catch(java.io.IOException e){
                            System.out.println("java.io.IOException " + name);
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
