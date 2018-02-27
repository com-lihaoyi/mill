package mill.clientserver;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

class ClientServer {
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