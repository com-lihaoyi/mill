package mill.main.client;

import de.tobiasroeser.lambdatest.junit.FreeSpec;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static de.tobiasroeser.lambdatest.Expect.expectEquals;

public class MillEnvTests extends FreeSpec {

    @Override
    protected void initTests() {
        section("readOptsFileLines", () -> {
            test("without final newline", () -> {
                File file = new File(
                    getClass().getClassLoader().getResource("file-wo-final-newline.txt").toURI()
                );
                List<String> lines = MillEnv.readOptsFileLines(file);
                expectEquals(
                    lines,
                    Arrays.asList(
                        "-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file",
                        "-Xss120m"
                    ));
            });
        });
    }

}
