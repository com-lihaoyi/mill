package mill.clientserver;

import org.scalasbt.ipcsocket.*;

import java.io.*;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.*;

public class Client {
    static void initServer(String lockBase, boolean setJnaNoSys) throws IOException,URISyntaxException{
        ArrayList<String> selfJars = new ArrayList<String>();
        ClassLoader current = Client.class.getClassLoader();
        while(current != null){
            if (current instanceof java.net.URLClassLoader) {
                URL[] urls = ((java.net.URLClassLoader) current).getURLs();
                for (URL url: urls) {
                    selfJars.add(new File(url.toURI()).getCanonicalPath());
                }
            }
            current = current.getParent();
        }
        if (ClientServer.isJava9OrAbove) {
            selfJars.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        }
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
        l.add("mill.ServerMain");
        l.add(lockBase);
        new java.lang.ProcessBuilder()
                .command(l)
                .redirectOutput(new java.io.File(lockBase + "/logs"))
                .redirectError(new java.io.File(lockBase + "/logs"))
                .start();
    }
    public static void main(String[] args) throws Exception{
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
                                    initServer(lockBase, setJnaNoSys);
                                }catch(Exception e){
                                    throw new RuntimeException(e);
                                }
                            }
                        },
                        Locks.files(lockBase),
                        System.in,
                        System.out,
                        System.err,
                        args,
                        env
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
                          String[] args,
                          Map<String, String> env) throws Exception{

        FileOutputStream f = new FileOutputStream(lockBase + "/run");
        ClientServer.writeArgs(System.console() != null, args, f);
        ClientServer.writeMap(env, f);
        f.close();

        boolean serverInit = false;
        if (locks.processLock.probe()) {
            serverInit = true;
            initServer.run();
        }
        while(locks.processLock.probe()) Thread.sleep(3);

        // Need to give sometime for Win32NamedPipeSocket to work
        // if the server is just initialized
        if (serverInit && ClientServer.isWindows) Thread.sleep(1000);

        Socket ioSocket = null;

        long retryStart = System.currentTimeMillis();
        while(ioSocket == null && System.currentTimeMillis() - retryStart < 1000){
            try{
                ioSocket = ClientServer.isWindows?
                        new Win32NamedPipeSocket(ClientServer.WIN32_PIPE_PREFIX + new File(lockBase).getName())
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
                if (ClientServer.isWindows && e.getMessage().contains("ReadFile()")) {
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
