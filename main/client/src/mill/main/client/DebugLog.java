package mill.main.client;
import java.io.IOException;
import java.nio.file.*;

/**
 * Used to add `println`s in scenarios where you can't figure out where on earth
 * your stdout/stderr/logs are going and so we just dump them in a file in your
 * home folder so you can find them
 */
public class DebugLog{
    synchronized public static void println(String s){
        try {
            Files.writeString(
                Paths.get(System.getProperty("user.home"), "mill-debug-log.txt"),
                s + "\n",
                StandardOpenOption.APPEND
            );
        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }
}
