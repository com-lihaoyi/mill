package mill.main.client;

import static de.tobiasroeser.lambdatest.Expect.expectEquals;
import static de.tobiasroeser.lambdatest.Expect.expectTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

import de.tobiasroeser.lambdatest.generic.DefaultReporter;
import de.tobiasroeser.lambdatest.junit.FreeSpec;

public class FileToStreamTailerTest extends FreeSpec {

    public FileToStreamTailerTest() {
        setReporter(new DefaultReporter());

        test("Handle non-existing file", () -> {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            final PrintStream ps = new PrintStream(bas);

            final File file = File.createTempFile("tailer", "");
            expectTrue(file.delete());

            try (
                final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10);
            ) {
                tailer.start();
                Thread.sleep(200);
                expectEquals(bas.toString(), "");
            }
        });

        test("Handle non-existing file that appears later", () -> {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            final PrintStream ps = new PrintStream(bas);

            final File file = File.createTempFile("tailer", "");
            expectTrue(file.delete());

            try (
                final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10);
            ) {
                tailer.start();
                Thread.sleep(100);
                expectEquals(bas.toString(), "");

                try (
                    PrintStream out = new PrintStream(new FileOutputStream(file));
                ) {
                    out.println("log line");
                    expectTrue(file.exists());
                    Thread.sleep(100);
                    expectEquals(bas.toString(), "log line" + System.lineSeparator());
                }
            }
        });

        test("Handle existing and initially empty file", () -> {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            final PrintStream ps = new PrintStream(bas);

            final File file = File.createTempFile("tailer", "");
            expectTrue(file.exists());

            try (
                final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10);
            ) {
                tailer.start();
                Thread.sleep(100);

                expectEquals(bas.toString(), "");

                try (
                    PrintStream out = new PrintStream(new FileOutputStream(file));
                ) {
                    out.println("log line");
                    expectTrue(file.exists());
                    Thread.sleep(100);
                    expectEquals(bas.toString(), "log line" + System.lineSeparator());
                }
            }
        });

        test("Handle existing file with old content", () -> {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            final PrintStream ps = new PrintStream(bas);

            final File file = File.createTempFile("tailer", "");
            expectTrue(file.exists());

            try (
                PrintStream out = new PrintStream(new FileOutputStream(file));
            ) {
                out.println("old line 1");
                out.println("old line 2");
                try (
                    final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10);
                ) {
                    tailer.start();
                    Thread.sleep(100);
                    expectEquals(bas.toString(), "");
                    out.println("log line");
                    expectTrue(file.exists());
                    Thread.sleep(100);
                    expectEquals(bas.toString(), "log line" + System.lineSeparator());
                }
            }
        });

        test("Handle existing empty file which disappears later and comes back", () -> {
            pending("Not a requirement");

            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            final PrintStream ps = new PrintStream(bas);

            final File file = File.createTempFile("tailer", "");
            expectTrue(file.exists());

            try (
                final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10);
            ) {
                tailer.start();
                Thread.sleep(100);

                expectEquals(bas.toString(), "");

                try (
                    PrintStream out = new PrintStream(new FileOutputStream(file));
                ) {
                    out.println("log line 1");
                    out.println("log line 2");
                    expectTrue(file.exists());
                    Thread.sleep(100);
                    expectEquals(bas.toString(),
                        "log line 1" + System.lineSeparator() + "log line 2" + System.lineSeparator());
                }

                // Now delete file and give some time, then append new lines

                expectTrue(file.delete());
                Thread.sleep(100);

                try (
                    PrintStream out = new PrintStream(new FileOutputStream(file));
                ) {
                    out.println("new line");
                    expectTrue(file.exists());
                    Thread.sleep(100);
                    expectEquals(bas.toString(),
                        "log line 1" + System.lineSeparator() +
                            "log line 2" + System.lineSeparator() +
                            "new line" + System.lineSeparator());
                }

            }
        });


    }
}
