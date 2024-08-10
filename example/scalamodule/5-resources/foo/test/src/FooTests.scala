package foo
import utest._
object FooTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      // Reference app module's `Foo` class which reads `file.txt` from classpath
      val appClasspathResourceText = Foo.classpathResourceText
      assert(appClasspathResourceText == "Hello World Resource File")

      // Read `test-file-a.txt` from classpath
      val testClasspathResourceText = os.read(os.resource / "test-file-a.txt")
      assert(testClasspathResourceText == "Test Hello World Resource File A")

      // Use `MILL_TEST_RESOURCE_FOLDER` to read `test-file-b.txt` from filesystem
      val testFileResourceDir = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
      val testFileResourceText = os.read(testFileResourceDir / "test-file-b.txt")
      assert(testFileResourceText == "Test Hello World Resource File B")

      // Use `MILL_TEST_RESOURCE_FOLDER` to list files available in resource folder
      assert(
        os.list(testFileResourceDir).sorted ==
        Seq(testFileResourceDir / "test-file-a.txt", testFileResourceDir / "test-file-b.txt")
      )

      // Use the `OTHER_FILES_FOLDER` configured in your build to access the
      // files in `foo/test/other-files/`.
      val otherFileText = os.read(os.Path(sys.env("OTHER_FILES_FOLDER")) / "other-file.txt")
      assert(otherFileText == "Other Hello World File")
    }
  }
}
