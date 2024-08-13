package mill.main.client;

import mill.main.client.lock.Locks;
import mill.main.client.lock.TryLocked;
import static mill.main.client.OutFiles.*;

import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class MillServerLauncher {
    final static int tailerRefreshIntervalMillis = 2;
    final static int maxLockAttempts = 3;
    public static int runMain(String[] args) throws Exception {

        final boolean setJnaNoSys = System.getProperty("jna.nosys") == null;
        if (setJnaNoSys) {
            System.setProperty("jna.nosys", "true");
        }

        final String versionAndJvmHomeEncoding = Util.sha1Hash(BuildInfo.millVersion + System.getProperty("java.home"));
        final int serverProcessesLimit = getServerProcessesLimit(versionAndJvmHomeEncoding);

        int serverIndex = 0;
        while (serverIndex < serverProcessesLimit) { // Try each possible server process (-1 to -5)
            serverIndex++;
            final String lockBase = out + "/" + millWorker + versionAndJvmHomeEncoding + "-" + serverIndex;
            java.io.File lockBaseFile = new java.io.File(lockBase);
            lockBaseFile.mkdirs();

            int lockAttempts = 0;
            while (lockAttempts < maxLockAttempts) { // Try to lock a particular server
                try (
                        Locks locks = Locks.files(lockBase);
                        TryLocked clientLock = locks.clientLock.tryLock()
                ) {
                    if (clientLock != null) {
                        return runMillServer(args, lockBase, setJnaNoSys, locks);
                    }
                } catch (Exception e) {
                    for (File file : lockBaseFile.listFiles()) file.delete();
                } finally {
                    lockAttempts++;
                }
            }
        }
        throw new MillServerCouldNotBeStarted("Reached max server processes limit: " + serverProcessesLimit);
    }

    static int runMillServer(String[] args,
                             String lockBase,
                             boolean setJnaNoSys,
                             Locks locks) throws Exception {
        final File stdout = new java.io.File(lockBase + "/" + ServerFiles.stdout);
        final File stderr = new java.io.File(lockBase + "/" + ServerFiles.stderr);

        try(
                final FileToStreamTailer stdoutTailer = new FileToStreamTailer(stdout, System.out, tailerRefreshIntervalMillis);
                final FileToStreamTailer stderrTailer = new FileToStreamTailer(stderr, System.err, tailerRefreshIntervalMillis);
        ) {
            stdoutTailer.start();
            stderrTailer.start();
            final int exitCode = run(
                    lockBase,
                    () -> {
                        try {
                            MillLauncher.launchMillServer(lockBase, setJnaNoSys);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    locks,
                    System.in,
                    System.out,
                    System.err,
                    args,
                    System.getenv());

            // Here, we ensure we process the tails of the output files before interrupting
            // the threads
            stdoutTailer.flush();
            stderrTailer.flush();

            return exitCode;
        }
    }

    // 5 processes max
    private static int getServerProcessesLimit(String jvmHomeEncoding) {
        File outFolder = new File(out);
        String[] totalProcesses = outFolder.list((dir, name) -> name.startsWith(millWorker));
        String[] thisJdkProcesses = outFolder.list((dir, name) -> name.startsWith(millWorker + jvmHomeEncoding));

        int processLimit = 5;

        if (thisJdkProcesses != null) {
            processLimit -= Math.min(totalProcesses.length - thisJdkProcesses.length, 5);
        } else if (totalProcesses != null) {
            processLimit -= Math.min(totalProcesses.length, 5);
        }

        return processLimit;
    }
    public static int run(
            String lockBase,
            Runnable initServer,
            Locks locks,
            InputStream stdin,
            OutputStream stdout,
            OutputStream stderr,
            String[] args,
            Map<String, String> env) throws Exception {

        try (FileOutputStream f = new FileOutputStream(lockBase + "/" + ServerFiles.runArgs)) {
            f.write(System.console() != null ? 1 : 0);
            Util.writeString(f, BuildInfo.millVersion);
            Util.writeArgs(args, f);
            Util.writeMap(env, f);
        }

        boolean serverInit = false;
        if (locks.processLock.probe()) {
            serverInit = true;
            initServer.run();
        }

        while (locks.processLock.probe()) Thread.sleep(3);

        String socketName = ServerFiles.pipe(lockBase);
        AFUNIXSocketAddress addr = AFUNIXSocketAddress.of(new File(socketName));

        long retryStart = System.currentTimeMillis();
        Socket ioSocket = null;
        Throwable socketThrowable = null;
        while (ioSocket == null && System.currentTimeMillis() - retryStart < 1000) {
            try {
                ioSocket = AFUNIXSocket.connectTo(addr);
            } catch (Throwable e) {
                socketThrowable = e;
                Thread.sleep(1);
            }
        }

        if (ioSocket == null) {
            throw new Exception("Failed to connect to server", socketThrowable);
        }

        InputStream outErr = ioSocket.getInputStream();
        OutputStream in = ioSocket.getOutputStream();
        ProxyStream.Pumper outPump = new ProxyStream.Pumper(outErr, stdout, stderr);
        InputPumper inPump = new InputPumper(() -> stdin, () -> in, true);
        Thread outThread = new Thread(outPump, "outPump");
        outThread.setDaemon(true);
        Thread inThread = new Thread(inPump, "inPump");
        inThread.setDaemon(true);
        outThread.start();
        inThread.start();

        // Fallback mechanism to terminate ProxyStream.Pumper.
        //
        // We don't expect this to be used much, because the `ProxyStream` protocol
        // should provide a `0` packet to terminate the stream and stop the pumper.
        // However, in the event that this does not happen, we still want the pumper
        // to terminate eventually. So we wait for the `serverLock` to be released,
        // indicating the server is done, and wait 0.5 seconds for any data to arrive
        // before terminating the pumper.
        locks.serverLock.await();
        outThread.join();
        outPump.stop();

        try {
            return Integer.parseInt(Files.readAllLines(Paths.get(lockBase + "/" + ServerFiles.exitCode)).get(0));
        } catch (Throwable e) {
            return Util.ExitClientCodeCannotReadFromExitCodeFile();
        } finally {
            ioSocket.close();
        }
    }

}
