package mill.scripts

import utest.*

object ExampleListTests extends TestSuite {
  def tests = Tests {
    test("exampleListUrls") {
      val exampleListPath = os.Path(sys.env("MILL_EXAMPLE_LIST_PATH"))
      val entries = upickle.default.read[Seq[(String, String)]](os.read(exampleListPath))
      assert(entries.nonEmpty)

      entries.foreach { case (examplePath, downloadUrl) =>
        assert(!examplePath.startsWith("/"))
        assert(!examplePath.contains("\\"))
        assert(!examplePath.contains(".."))
        assert(!downloadUrl.contains("SNAPSHOT"))

        val uri = new java.net.URI(downloadUrl)
        val segments = uri.getPath.split("/").filter(_.nonEmpty)
        assert(segments.length >= 2)

        val fileName = segments.last
        val version = segments(segments.length - 2)
        assert(!version.contains("SNAPSHOT"))
        assert(fileName.startsWith(s"mill-dist-$version-"))

        val normalizedExample = examplePath.replace("/", "-")
        assert(fileName.endsWith(s"example-$normalizedExample.zip"))
      }
    }
  }
}
