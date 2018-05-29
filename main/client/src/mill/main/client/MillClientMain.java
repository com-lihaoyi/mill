package mill.main.client;

import org.scalasbt.ipcsocket.*;

import java.io.*;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.*;

public class MillClientMain {
    static void initServer(String lockBase, boolean setJnaNoSys) throws IOException,URISyntaxException{
        String[] selfJars = System.getProperty("MILL_CLASSPATH").split(File.pathSeparator);

        ArrayList<String> l = new java.util.ArrayList<String>();
        l.add("java");
        Properties props = System.getProperties();
        Iterator<String> keys = props.stringPropertyNames().iterator();
        while(keys.hasNext()){
            String k = keys.next();
            if (k.startsWith("MILL_")) l.add("-D" + k + "=" + props.getProperty(k));
        }
        if (setJnaNoSys) {
            l.add("-Djna.nosys=true");
        }
        l.add("-cp");
        l.add(String.join(File.pathSeparator, selfJars));
        l.add("mill.main.MillServerMain");
        l.add(lockBase);

        new java.lang.ProcessBuilder()
                .command(l)
                .redirectOutput(new java.io.File(lockBase + "/logs"))
                .redirectError(new java.io.File(lockBase + "/logs"))
                .start();
    }
    public static void main(String[] args) throws Exception{
        System.exit(main0(args));
    }
    public static int main0(String[] args) throws Exception{
        boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
        Map<String, String> env = System.getenv();
        if (setJnaNoSys) {
            System.setProperty("jna.nosys", "true");
        }
        int index = 0;
        while (index < 5) {
            index += 1;
            String lockBase = "out/mill-worker-" + index;
            new java.io.File(lockBase).mkdirs();

            try(RandomAccessFile lockFile = new RandomAccessFile(lockBase + "/clientLock", "rw");
                FileChannel channel = lockFile.getChannel();
                java.nio.channels.FileLock tryLock = channel.tryLock();
                Locks locks = Locks.files(lockBase)){
                if (tryLock != null) {
                    int exitCode = MillClientMain.run(
                            lockBase,
                            new Runnable() {
                                @Override
                                public void run() {
                                    try{
                                        initServer(lockBase, setJnaNoSys);
                                    }catch(Exception e){
                                        throw new RuntimeException(e);
                                    }
                                }
                            },
                            locks,
                            System.in,
                            System.out,
                            System.err,
                            args,
                            env
                    );
                    return exitCode;
                }
            } finally{

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
                          String[] args,
                          Map<String, String> env) throws Exception{

        try(FileOutputStream f = new FileOutputStream(lockBase + "/run")){
            f.write(System.console() != null ? 1 : 0);
            Util.writeString(f, System.getProperty("MILL_VERSION"));
            Util.writeArgs(args, f);
            Util.writeMap(env, f);
        }

        boolean serverInit = false;
        if (locks.processLock.probe()) {
            serverInit = true;
            initServer.run();
        }
        while(locks.processLock.probe()) Thread.sleep(3);

        // Need to give sometime for Win32NamedPipeSocket to work
        // if the server is just initialized
        if (serverInit && Util.isWindows) Thread.sleep(1000);

        Socket ioSocket = null;

        long retryStart = System.currentTimeMillis();

        while(ioSocket == null && System.currentTimeMillis() - retryStart < 1000){
            try{
                ioSocket = Util.isWindows?
                        new Win32NamedPipeSocket(Util.WIN32_PIPE_PREFIX + new File(lockBase).getName())
                        : new UnixDomainSocket(lockBase + "/io");
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

        try(FileInputStream fos = new FileInputStream(lockBase + "/exitCode")){
            return Integer.parseInt(new BufferedReader(new InputStreamReader(fos)).readLine());
        } catch(Throwable e){
            return 1;
        } finally{
            ioSocket.close();
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

    public void run() {
        byte[] buffer = new byte[1024];
        int state = 0;
        boolean running = true;
        boolean first = true;
        while (running) {
            try {
                int n = src.read(buffer);
                first = false;
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
            } catch (IOException e) {
                // Win32NamedPipeSocket input stream somehow doesn't return -1,
                // instead it throws an IOException whose message contains "ReadFile()".
                // However, if it throws an IOException before ever reading some bytes,
                // it could not connect to the server, so exit.
                if (Util.isWindows && e.getMessage().contains("ReadFile()")) {
                    if (first) {
                        System.err.println("Failed to connect to server");
                        System.exit(1);
                    } else running = false;
                } else {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

}
