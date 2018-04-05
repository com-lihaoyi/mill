package mill.clientserver;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class ClientServer {
    public static boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
    public static boolean isJava9OrAbove = !System.getProperty("java.specification.version").startsWith("1.");

    // Windows named pipe prefix (see https://github.com/sbt/ipcsocket/blob/v1.0.0/README.md)
    // Win32NamedPipeServerSocket automatically adds this as a prefix (if it is not already is prefixed),
    // but Win32NamedPipeSocket does not
    // https://github.com/sbt/ipcsocket/blob/v1.0.0/src/main/java/org/scalasbt/ipcsocket/Win32NamedPipeServerSocket.java#L36
    public static String WIN32_PIPE_PREFIX = "\\\\.\\pipe\\";

    public static String[] parseArgs(InputStream argStream) throws IOException {

        int argsLength = argStream.read();
        String[] args = new String[argsLength];
        for (int i = 0; i < args.length; i++) {
            args[i] = readString(argStream);
        }
        return args;
    }
    public static void writeArgs(Boolean interactive,
                                 String[] args,
                                 OutputStream argStream) throws IOException {
        argStream.write(interactive ? 1 : 0);
        argStream.write(args.length);
        int i = 0;
        while (i < args.length) {
            writeString(argStream, args[i]);
            i += 1;
        }
    }

    /**
     * This allows the mill client to pass the environment as he sees it to the
     * server (as the server remains alive over the course of several runs and
     * does not see the environment changes the client would)
     */
    public static void writeMap(Map<String, String> map, OutputStream argStream) throws IOException {
        argStream.write(map.size());
        for (Map.Entry<String, String> kv : map.entrySet()) {
            writeString(argStream, kv.getKey());
            writeString(argStream, kv.getValue());
        }
    }

    public static Map<String, String> parseMap(InputStream argStream) throws IOException {
        Map<String, String> env = new HashMap<>();
        int mapLength = argStream.read();
        for (int i = 0; i < mapLength; i++) {
            String key = readString(argStream);
            String value = readString(argStream);
            env.put(key, value);
        }
        return env;
    }

    private static String readString(InputStream inputStream) throws IOException {
        // Result is between 0 and 255, hence the loop.
        int read = inputStream.read();
        int bytesToRead = read;
        while(read == 255){
            read = inputStream.read();
            bytesToRead += read;
        }
        byte[] arr = new byte[bytesToRead];
        int readTotal = 0;
        while (readTotal < bytesToRead) {
            read = inputStream.read(arr, readTotal, bytesToRead - readTotal);
            readTotal += read;
        }
        return new String(arr);
    }

    private static void writeString(OutputStream outputStream, String string) throws IOException {
        // When written, an int > 255 gets splitted. This logic performs the
        // split beforehand so that the reading side knows that there is still
        // more metadata to come before it's able to read the actual data.
        // Could do with rewriting using logical masks / shifts.
        byte[] bytes = string.getBytes();
        int toWrite = bytes.length;
        while(toWrite >= 255){
            outputStream.write(255);
            toWrite = toWrite - 255;
        }
        outputStream.write(toWrite);
        outputStream.write(bytes);
    }

}
