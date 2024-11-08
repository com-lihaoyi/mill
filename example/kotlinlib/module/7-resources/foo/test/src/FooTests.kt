package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Paths

class FooTests :
    FunSpec({
        test("simple") {
            // Reference app module's `Foo` class which reads `file.txt` from classpath
            val appClasspathResourceText = Foo.classpathResourceText()
            appClasspathResourceText shouldBe "Hello World Resource File"

            // Read `test-file-a.txt` from classpath
            val testClasspathResourceText =
                Foo::class.java.classLoader.getResourceAsStream("test-file-a.txt").use {
                    it.readAllBytes().toString(Charsets.UTF_8)
                }
            testClasspathResourceText shouldBe "Test Hello World Resource File A"

            // Use `MILL_TEST_RESOURCE_DIR` to read `test-file-b.txt` from filesystem
            val testFileResourceDir = Paths.get(System.getenv("MILL_TEST_RESOURCE_DIR"))
            val testFileResourceText =
                Files.readString(testFileResourceDir.resolve("test-file-b.txt"))
            testFileResourceText shouldBe "Test Hello World Resource File B"

            // Use `MILL_TEST_RESOURCE_DIR` to list files available in resource folder
            val actualFiles = Files.list(testFileResourceDir).toList().sorted()
            val expectedFiles =
                listOf(
                    testFileResourceDir.resolve("test-file-a.txt"),
                    testFileResourceDir.resolve("test-file-b.txt"),
                )
            actualFiles shouldBe expectedFiles

            // Use the `OTHER_FILES_DIR` configured in your build to access the
            // files in `foo/test/other-files/`.
            val otherFileText =
                Files.readString(Paths.get(System.getenv("OTHER_FILES_DIR"), "other-file.txt"))
            otherFileText shouldBe "Other Hello World File"
        }
    })
