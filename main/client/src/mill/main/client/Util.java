package mill.main.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class Util {
    public static boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
    public static boolean isJava9OrAbove = !System.getProperty("java.specification.version").startsWith("1.");
    private static Charset utf8 = Charset.forName("UTF-8");

    // Windows named pipe prefix (see https://github.com/sbt/ipcsocket/blob/v1.0.0/README.md)
    // Win32NamedPipeServerSocket automatically adds this as a prefix (if it is not already is prefixed),
    // but Win32NamedPipeSocket does not
    // https://github.com/sbt/ipcsocket/blob/v1.0.0/src/main/java/org/scalasbt/ipcsocket/Win32NamedPipeServerSocket.java#L36
    public static String WIN32_PIPE_PREFIX = "\\\\.\\pipe\\";

    public static String[] parseArgs(InputStream argStream) throws IOException {
        int argsLength = readInt(argStream);
        String[] args = new String[argsLength];
        for (int i = 0; i < args.length; i++) {
            args[i] = readString(argStream);
        }
        return args;
    }
    public static void writeArgs(String[] args,
                                 OutputStream argStream) throws IOException {
        writeInt(argStream, args.length);
        for(String arg: args){
            writeString(argStream, arg);
        }
    }

    /**
     * This allows the mill client to pass the environment as it sees it to the
     * server (as the server remains alive over the course of several runs and
     * does not see the environment changes the client would)
     */
    public static void writeMap(Map<String, String> map, OutputStream argStream) throws IOException {
        writeInt(argStream, map.size());
        for (Map.Entry<String, String> kv : map.entrySet()) {
            writeString(argStream, kv.getKey());
            writeString(argStream, kv.getValue());
        }
    }

    public static Map<String, String> parseMap(InputStream argStream) throws IOException {
        Map<String, String> env = new HashMap<>();
        int mapLength = readInt(argStream);
        for (int i = 0; i < mapLength; i++) {
            String key = readString(argStream);
            String value = readString(argStream);
            env.put(key, value);
        }
        return env;
    }

    public static String readString(InputStream inputStream) throws IOException {
        // Result is between 0 and 255, hence the loop.
        final int length = readInt(inputStream);
        final byte[] arr = new byte[length];
        int total = 0;
        while(total < length){
            int res = inputStream.read(arr, total, length-total);
            if (res == -1) throw new IOException("Incomplete String");
            else{
                total += res;
            }
        }
        return new String(arr, utf8);
    }

    public static void writeString(OutputStream outputStream, String string) throws IOException {
        final byte[] bytes = string.getBytes(utf8);
        writeInt(outputStream, bytes.length);
        outputStream.write(bytes);
    }

    public static void writeInt(OutputStream out, int i) throws IOException{
        out.write((byte)(i >>> 24));
        out.write((byte)(i >>> 16));
        out.write((byte)(i >>> 8));
        out.write((byte)i);
    }
    public static int readInt(InputStream in) throws IOException{
        return ((in.read() & 0xFF) << 24) +
                ((in.read() & 0xFF) << 16) +
                ((in.read() & 0xFF) << 8) +
                (in.read() & 0xFF);
    }

}
