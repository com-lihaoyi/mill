package mill.javalib.graalvm

import utest.*

object TestMetadata extends TestSuite {

  val version = "0.3.32"
  val downloadedMetadataDir = os.temp.dir()
  val downloadedMetadata = downloadedMetadataDir / "graalvm-reachability-metadata.zip"

  val rootDir = os.temp.dir()

  os.write(
    downloadedMetadata,
    requests.get(
      s"https://github.com/oracle/graalvm-reachability-metadata/releases/download/$version/graalvm-reachability-metadata-$version.zip"
    )
  )

  os.unzip(downloadedMetadata, rootDir)


  val tests = Tests {
    test("ch.qos.logback:logback-classic:1.5.21") {
      assert(
        Metadata.findConfigurations(rootDir, Set("ch.qos.logback:logback-classic:1.5.21")).size == 1
      )
    }
  }

}
