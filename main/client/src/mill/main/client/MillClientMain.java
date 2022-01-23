package mill.main.client;

import mill.main.client.lock.Locked;
import mill.main.client.lock.Locks;
import org.scalasbt.ipcsocket.UnixDomainSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeSocket;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.Math;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

public class MillClientMain {

    // use methods instead of constants to avoid inlining by compiler
    public static final int ExitClientCodeCannotReadFromExitCodeFile() { return 1; }
	public static final int ExitServerCodeWhenIdle() { return 0; }
	public static final int ExitServerCodeWhenVersionMismatch() { return 101; }

    static void initServer(String lockBase, boolean setJnaNoSys) throws IOException, URISyntaxException {

        String selfJars = "";
        List<String> vmOptions = new ArrayList<>();
        String millOptionsPath = System.getProperty("MILL_OPTIONS_PATH");
        if (millOptionsPath != null) {
            // read MILL_CLASSPATH from file MILL_OPTIONS_PATH
            Properties millProps = new Properties();
            millProps.load(new FileInputStream(millOptionsPath));
            for(final String k: millProps.stringPropertyNames()){
                String propValue = millProps.getProperty(k);
                if ("MILL_CLASSPATH".equals(k)){
                    selfJars = propValue;
                }
            }
        } else {
            // read MILL_CLASSPATH from file sys props
            selfJars = System.getProperty("MILL_CLASSPATH");
        }

        final Properties sysProps = System.getProperties();
        for (final String k: sysProps.stringPropertyNames()){
            if (k.startsWith("MILL_") && !"MILL_CLASSPATH".equals(k)) {
                vmOptions.add("-D" + k + "=" + sysProps.getProperty(k));
            }
        }
        if (selfJars == null || selfJars.trim().isEmpty()) {
            throw new RuntimeException("MILL_CLASSPATH is empty!");
        }
        if (setJnaNoSys) {
            vmOptions.add("-Djna.nosys=true");
        }

        String millJvmOptsPath = System.getProperty("MILL_JVM_OPTS_PATH");
        if (millJvmOptsPath == null) {
            millJvmOptsPath = ".mill-jvm-opts";
        }

        File millJvmOptsFile =  new File(millJvmOptsPath);
        if (millJvmOptsFile.exists()) {
            try (Scanner sc = new Scanner(millJvmOptsFile)) {
                while (sc.hasNextLine()) {
                    String arg = sc.nextLine();
                    vmOptions.add(arg);
                }
            }
        }

        List<String> l = new ArrayList<>();
        l.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        l.addAll(vmOptions);
        l.add("-cp");
        l.add(String.join(File.pathSeparator, selfJars.split(",")));
        l.add("mill.main.MillServerMain");
        l.add(lockBase);

        File stdout = new java.io.File(lockBase + "/stdout");
        File stderr = new java.io.File(lockBase + "/stderr");

        new ProcessBuilder()
                .command(l)
                .redirectOutput(stdout)
                .redirectError(stderr)
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
        if (exitCode == ExitServerCodeWhenVersionMismatch()) {
            exitCode = main0(args);
        }
        System.exit(exitCode);
    }

    public static int main0(String[] args) throws Exception {

        boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
        if (setJnaNoSys) {
            System.setProperty("jna.nosys", "true");
        }
        
        String jvmHomeEncoding = sha1HashPath(System.getProperty("java.home"));
        int serverProcessesLimit = getServerProcessesLimit(jvmHomeEncoding);

        int index = 0;
        while (index < serverProcessesLimit) {
            index += 1;
            String lockBase = "out/mill-worker-" + jvmHomeEncoding + "-" + index;
            new java.io.File(lockBase).mkdirs();

            File stdout = new java.io.File(lockBase + "/stdout");
            File stderr = new java.io.File(lockBase + "/stderr");
            int refeshIntervalMillis = 2;

            try (
                Locks locks = Locks.files(lockBase);
                FileToStreamTailer stdoutTailer = new FileToStreamTailer(stdout, System.out, refeshIntervalMillis);
                FileToStreamTailer stderrTailer = new FileToStreamTailer(stderr, System.err, refeshIntervalMillis);
            ) {
                Locked clientLock = locks.clientLock.tryLock();
                if (clientLock != null) {
                    stdoutTailer.start();
                    stderrTailer.start();

                    int exitCode = run(
                        lockBase,
                        () -> {
                            try {
                                initServer(lockBase, setJnaNoSys);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        locks,
                        System.in,
                        System.out,
                        System.err,
                        args,
                        System.getenv()
                    );

                    // Here, we ensure we process the tails of the output files before interrupting the threads
                    stdoutTailer.flush();
                    stderrTailer.flush();
                    clientLock.release();
                    return exitCode;
                }
            }
        }
        throw new Exception("Reached max server processes limit: " + serverProcessesLimit);
    }

    public static int run(String lockBase,
                          Runnable initServer,
                          Locks locks,
                          InputStream stdin,
                          OutputStream stdout,
                          OutputStream stderr,
                          String[] args,
                          Map<String, String> env) throws Exception{

        try (FileOutputStream f = new FileOutputStream(lockBase + "/run")) {
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
        while (locks.processLock.probe()) Thread.sleep(3);

        // Need to give sometime for Win32NamedPipeSocket to work
        // if the server is just initialized
        if (serverInit && Util.isWindows) Thread.sleep(1000);

        Socket ioSocket = null;
        Throwable socketThrowable = null;
        long retryStart = System.currentTimeMillis();

        while (ioSocket == null && System.currentTimeMillis() - retryStart < 5000) {
            try {
                String socketBaseName = "mill-" + md5hex(new File(lockBase).getCanonicalPath());
                ioSocket = Util.isWindows?
                        new Win32NamedPipeSocket(Util.WIN32_PIPE_PREFIX + socketBaseName)
                        : new UnixDomainSocket(lockBase + "/" + socketBaseName + "-io");
            } catch (Throwable e){
                socketThrowable = e;
                Thread.sleep(1);
            }
        }
        if (ioSocket == null) {
            throw new Exception("Failed to connect to server", socketThrowable);
        }

        InputStream outErr = ioSocket.getInputStream();
        OutputStream in = ioSocket.getOutputStream();
        ProxyStreamPumper outPump = new ProxyStreamPumper(outErr, stdout, stderr);
        InputPumper inPump = new InputPumper(stdin, in, true);
        Thread outThread = new Thread(outPump, "outPump");
        outThread.setDaemon(true);
        Thread inThread = new Thread(inPump, "inPump");
        inThread.setDaemon(true);
        outThread.start();
        inThread.start();

        locks.serverLock.await();

        try {
            return Integer.parseInt(Files.readAllLines(Paths.get(lockBase + "/exitCode")).get(0));
        } catch (Throwable e) {
            return ExitClientCodeCannotReadFromExitCodeFile();
        } finally {
            ioSocket.close();
        }
    }

    // 5 processes max
    private static int getServerProcessesLimit(String jvmHomeEncoding) {
        File outFolder = new File("out");
        String[] totalProcesses = outFolder.list((dir,name) -> name.startsWith("mill-worker-"));
        String[] thisJdkProcesses = outFolder.list((dir,name) -> name.startsWith("mill-worker-" + jvmHomeEncoding));

        int processLimit = 5;
        if (totalProcesses != null) {
            if (thisJdkProcesses != null) {
                processLimit -= Math.min(totalProcesses.length - thisJdkProcesses.length, 5);
            } else {
                processLimit -= Math.min(totalProcesses.length, 5);
            }
        }
        return processLimit;
    }

    /** @return Hex encoded MD5 hash of input string. */
    public static String md5hex(String str) throws NoSuchAlgorithmException {
        return hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexArray(byte[] arr) {
        return String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr));
    }
}
