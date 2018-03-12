package mill.clientserver;

import io.github.retronym.java9rtexport.Export;
import org.scalasbt.ipcsocket.UnixDomainSocket;

import java.io.*;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;

public class Client {
    static void initServer(String lockBase) throws IOException{
        ArrayList<String> selfJars = new ArrayList<String>();
        ClassLoader current = Client.class.getClassLoader();
        while(current != null){
            if (current instanceof java.net.URLClassLoader) {
                URL[] urls = ((java.net.URLClassLoader) current).getURLs();
                for (URL url: urls) {
                    selfJars.add(url.toString());
                }
            }
            current = current.getParent();
        }
        if (!System.getProperty("java.specification.version").startsWith("1.")) {
            selfJars.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
            File rtFile = new File(lockBase + "/rt-" + System.getProperty("java.version") + ".jar");
            if (!rtFile.exists()) {
                Files.copy(Export.export().toPath(), rtFile.toPath());
            }
            selfJars.add(rtFile.getCanonicalPath());
        }
        ArrayList<String> l = new java.util.ArrayList<String>();
        l.add("java");
        Properties props = System.getProperties();
        Iterator<String> keys = props.stringPropertyNames().iterator();
        while(keys.hasNext()){
            String k = keys.next();
            if (k.startsWith("MILL_")) l.add("-D" + k + "=" + props.getProperty(k));
        }
        l.add("-cp");
        l.add(String.join(File.pathSeparator, selfJars));
        l.add("mill.ServerMain");
        l.add(lockBase);
        new java.lang.ProcessBuilder()
                .command(l)
                .redirectOutput(new java.io.File(lockBase + "/logs"))
                .redirectError(new java.io.File(lockBase + "/logs"))
                .start();
    }
    public static void main(String[] args) throws Exception{
        int index = 0;
        while (index < 5) {
            index += 1;
            String lockBase = "out/mill-worker-" + index;
            new java.io.File(lockBase).mkdirs();
            RandomAccessFile lockFile = new RandomAccessFile(lockBase + "/clientLock", "rw");
            FileChannel channel = lockFile.getChannel();
            java.nio.channels.FileLock tryLock = channel.tryLock();
            if (tryLock == null) {
                lockFile.close();
                channel.close();
            } else {
                int exitCode = Client.run(
                        lockBase,
                        new Runnable() {
                            @Override
                            public void run() {
                                try{
                                    initServer(lockBase);
                                }catch(IOException e){
                                    throw new RuntimeException(e);
                                }
                            }
                        },
                        Locks.files(lockBase),
                        System.in,
                        System.out,
                        System.err,
                        args
                );
                System.exit(exitCode);
            }
        }
        throw new Exception("Reached max process limit: " + 5);
    }


    public static int run(String lockBase,
                          Runnable initServer,
                          Locks locks,
                          InputStream stdin,
                          OutputStream stdout,
                          OutputStream stderr,
                          String[] args) throws Exception{

        FileOutputStream f = new FileOutputStream(lockBase + "/run");
        ClientServer.writeArgs(System.console() != null, args, f);
        f.close();
        if (locks.processLock.probe()) initServer.run();
        while(locks.processLock.probe()) Thread.sleep(3);


        UnixDomainSocket ioSocket = null;

        long retryStart = System.currentTimeMillis();
        while(ioSocket == null && System.currentTimeMillis() - retryStart < 1000){
            try{
                ioSocket = new UnixDomainSocket(lockBase + "/io");
            }catch(Throwable e){
                Thread.sleep(1);
            }
        }
        if (ioSocket == null){
            throw new Exception("Failed to connect to server");
        }
        InputStream outErr = ioSocket.getInputStream();
        OutputStream in = ioSocket.getOutputStream();
        ClientOutputPumper outPump = new ClientOutputPumper(outErr, stdout, stderr);
        InputPumper inPump = new InputPumper(stdin, in, true);
        Thread outThread = new Thread(outPump);
        outThread.setDaemon(true);
        Thread inThread = new Thread(inPump);
        inThread.setDaemon(true);
        outThread.start();
        inThread.start();

        locks.serverLock.await();

        try{
            return Integer.parseInt(
                new BufferedReader(
                    new InputStreamReader(
                        new FileInputStream(lockBase + "/exitCode")
                    )
                ).readLine()
            );
        } catch(Throwable e){
            return 1;
        }
    }
}

class ClientOutputPumper implements Runnable{
    private InputStream src;
    private OutputStream dest1;
    private OutputStream dest2;
    public ClientOutputPumper(InputStream src, OutputStream dest1, OutputStream dest2){
        this.src = src;
        this.dest1 = dest1;
        this.dest2 = dest2;
    }

    boolean running = true;
    public void run() {
        byte[] buffer = new byte[1024];
        int state = 0;
        try {
            while(running){

                int n = src.read(buffer);
                if (n == -1) running = false;
                else {
                    int i = 0;
                    while (i < n) {
                        switch (state) {
                            case 0:
                                state = buffer[i] + 1;
                                break;
                            case 1:
                                dest1.write(buffer[i]);
                                state = 0;
                                break;
                            case 2:
                                dest2.write(buffer[i]);
                                state = 0;
                                break;
                        }

                        i += 1;
                    }
                    dest1.flush();
                    dest2.flush();
                }
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

}
