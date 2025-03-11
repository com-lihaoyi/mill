package foo;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
public class Foo {
    public static void main(String[] args) throws Exception{
        System.out.println("Hello World A!");
        RandomAccessFile raf = new RandomAccessFile(args[0], "rw");
        System.out.println("Hello World B!");
        FileChannel chan = raf.getChannel();
        System.out.println("Hello World C!");
        chan.lock();
        System.out.println("Hello World D!");
        while(true){
            if (!Files.exists(Paths.get(args[1]))) Thread.sleep(1);
            else break;
        }
        System.out.println("Hello World E!");
    }
}
