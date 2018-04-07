package mill.client;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
            int n = argStream.read();
            byte[] arr = new byte[n];
            argStream.read(arr);
            args[i] = new String(arr);
        }
        return args;
    }
    public static void writeArgs(Boolean interactive,
                                 String[] args,
                                 OutputStream argStream) throws IOException{
    argStream.write(interactive ? 1 : 0);
    argStream.write(args.length);
    int i = 0;
    while (i < args.length){
      argStream.write(args[i].length());
      argStream.write(args[i].getBytes());
      i += 1;
    }
  }
}