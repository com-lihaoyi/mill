package foo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        Path resultPath = Paths.get(args[0]);
        Files.createDirectories(resultPath.getParent());
        Files.write(resultPath, BuildInfo.scalaVersion.getBytes());
    }
}

