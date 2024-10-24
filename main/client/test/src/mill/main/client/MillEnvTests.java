package mill.main.client;


import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;


public class MillEnvTests {

    @Test
    public void readOptsFileLinesWithoutFInalNewline() throws Exception {
        File file = new File(
            getClass().getClassLoader().getResource("file-wo-final-newline.txt").toURI()
        );
        List<String> lines = Util.readOptsFileLines(file);
        assertEquals(
            lines,
            Arrays.asList(
                "-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file",
                "-Xss120m"
            ));
    }
}


