import mill._, scalalib._

object app extends RootModule with ScalaModule{
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::cask:0.9.1",
    ivy"com.lihaoyi::scalatags:0.12.0",
    ivy"com.lihaoyi::os-lib:0.9.1"
  )

  def resources = T {
    val hashMapping = for {
      resourceRoot <- super.resources()
      path <- os.walk(resourceRoot.path)
      if os.isFile(path)
    } yield {
      val fileHash = String.format("%08x", java.util.Arrays.hashCode(os.read.bytes(path)))
      val relPath = path.relativeTo(resourceRoot.path)
      val extStr = if (relPath.ext == "") "" else s".${relPath.ext}"
      val hashedPath = relPath / os.up / s"${relPath.baseName}-$fileHash$extStr"
      os.copy(path, T.dest / hashedPath, createFolders = true)
      (relPath.toString(), hashedPath.toString())
    }

    os.write(
      T.dest / "hashed-resource-mapping.json",
      upickle.default.write(hashMapping.toMap, indent = 4)
    )

    Seq(PathRef(T.dest))
  }

  object test extends Tests{
    def testFramework = "utest.runner.Framework"
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest::0.7.10",
      ivy"com.lihaoyi::requests::0.6.9",
    )
  }
}

// This example demonstrates how to implement webapp "cache busting" in Mill,
// where we serve static files with a hash appended to the filename, and save
// a mapping of filename to hashed filename so that the web server can serve
// HTML that references the appropriately hashed file paths. This allows us to
// deploy the static files behind caches with long expiration times, while
// still having the web app immediately load updated static files after a
// deploy (since the HTML will reference new hashed paths that are not yet
// in the cache).
//
// We do this in an overrride of the `resources` target, that loads
// `super.resources()`, hashes the files within it using `Arrays.hashCode`, and
// copies the files to a new hashed path saving the overall mapping to a
// `hashed-resource-mapping.json`. The webapp then loads the mapping at runtime
// and uses it to serve HTML referencing the hashed paths, but without paying
// the cost of hashing the static resource files at runtime.

/* Example Usage

> ./mill test
+ webapp.WebAppTests.simpleRequest

> ./mill runBackground

> curl http://localhost:8080
What needs to be done

> curl http://localhost:8080/static/main-6da98e99.js
initListeners()

> ./mill clean runBackground

*/