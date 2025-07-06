package hello;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

public class Main{
    public static void main(String[] args) throws IOException {
        Path path = Paths.get(args[0]);
        String version = System.getProperty("java.version");
        Files.writeString(path, version.indent(2));
    }
}
