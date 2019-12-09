package mill.main.client;

import org.scalasbt.ipcsocket.*;

import java.io.*;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.lang.Math;

public class MillClientMain {

    // use methods instead of constants to avoid inlining by compiler
    public static final int ExitClientCodeCannotReadFromExitCodeFile() { return 1; }
	public static final int ExitServerCodeWhenIdle() { return 0; }
	public static final int ExitServerCodeWhenVersionMismatch() { return 101; }

    static void initServer(String lockBase, boolean setJnaNoSys) throws IOException,URISyntaxException{
        String[] selfJars = System.getProperty("MILL_CLASSPATH").split(",");

        List<String> l = new ArrayList<>();
        List<String> vmOptions = new ArrayList<>();
        l.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        final Properties props = System.getProperties();
        for(final String k: props.stringPropertyNames()){
            if (k.startsWith("MILL_") && !"MILL_CLASSPATH".equals(k)) {
                vmOptions.add("-D" + k + "=" + props.getProperty(k));
            }
        }
        if (setJnaNoSys) {
            vmOptions.add("-Djna.nosys=true");
        }
        if(!Util.isWindows){
            l.addAll(vmOptions);
        } else {
            final File vmOptionsFile = new File(lockBase, "vmoptions");
            try (PrintWriter out = new PrintWriter(vmOptionsFile)) {
                for(String opt: vmOptions)
                out.println(opt);
            }
            l.add("-XX:VMOptionsFile=" + vmOptionsFile.getCanonicalPath());
        }
        l.add("-cp");
        l.add(String.join(File.pathSeparator, selfJars));
        l.add("mill.main.MillServerMain");
        l.add(lockBase);

        new ProcessBuilder()
                .command(l)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static String sha1HashPath(String path) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest md = MessageDigest.getInstance("SHA1");
        md.reset();
        byte[] pathBytes = path.getBytes("UTF-8");
        md.update(pathBytes);
        byte[] digest = md.digest();
        return Base64.getEncoder().encodeToString(digest);
    }

    public static void main(String[] args) throws Exception{
        int exitCode = main0(args);
        if(exitCode == ExitServerCodeWhenVersionMismatch()) {
            exitCode = main0(args);
        }
        System.exit(exitCode);
    }
    public static int main0(String[] args) throws Exception{
        boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
        Map<String, String> env = System.getenv();
        if (setJnaNoSys) {
            System.setProperty("jna.nosys", "true");
        }
        int index = 0;
        String jvmHomeEncoding = sha1HashPath(System.getProperty("java.home"));
        File outFolder = new File("out");
        String[] totalProcesses = outFolder.list((dir,name) -> name.startsWith("mill-worker-"));
        String[] thisJdkProcesses = outFolder.list((dir,name) -> name.startsWith("mill-worker-" + jvmHomeEncoding));

        int processLimit = 5;
        if(totalProcesses != null) {
            if(thisJdkProcesses != null) {
                processLimit -= Math.min(totalProcesses.length - thisJdkProcesses.length, 5);
            } else {
                processLimit -= Math.min(totalProcesses.length, 5);
            }
        }
        while (index < processLimit) {
            index += 1;
            String lockBase = "out/mill-worker-" + jvmHomeEncoding + "-" + index;
            new java.io.File(lockBase).mkdirs();

            try(
                RandomAccessFile lockFile = new RandomAccessFile(lockBase + "/clientLock", "rw");
                FileChannel channel = lockFile.getChannel();
                java.nio.channels.FileLock tryLock = channel.tryLock();
                Locks locks = Locks.files(lockBase)
            ){
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
        throw new Exception("Reached max process limit: " + processLimit);
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
        ProxyStreamPumper outPump = new ProxyStreamPumper(outErr, stdout, stderr);
        InputPumper inPump = new InputPumper(stdin, in, true);
        Thread outThread = new Thread(outPump);
        outThread.setDaemon(true);
        Thread inThread = new Thread(inPump);
        inThread.setDaemon(true);
        outThread.start();
        inThread.start();

        locks.serverLock.await();

        try(FileInputStream fis = new FileInputStream(lockBase + "/exitCode")){
            return Integer.parseInt(new BufferedReader(new InputStreamReader(fis)).readLine());
        } catch(Throwable e){
            return ExitClientCodeCannotReadFromExitCodeFile();
        } finally{
            ioSocket.close();
        }
    }
}
