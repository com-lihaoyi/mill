package mill.javalib.graalvm

import utest.*

object TestMetadataWorkerImpl extends TestSuite {

  val version = "0.3.32"

  val workDir = os.temp.dir()

  val metadata: GraalVMMetadataWorker = new GraalVMMetadataWorkerImpl()

  val rootDir = metadata.downloadRepo(workDir, version)

  val copyIntoDir = os.temp.dir()

  val tests = Tests {
    test("ch.qos.logback:logback-classic") {
      // logback available versions in 0.3.32 are 1.2.11, 1.4.1 and 1.4.9
      val logbackConfs = metadata.findConfigurations(
        MetadataQuery(
          rootPath = rootDir,
          deps = Set("ch.qos.logback:logback-classic:1.5.21"),
          useLatestConfigWhenVersionIsUntested = true
        )
      )
      assert(
        logbackConfs.size == 1
      )

      assert(
        logbackConfs.head.dependencyArtifactId == "logback-classic"
      )

      assert(
        logbackConfs.head.dependencyGroupId == "ch.qos.logback"
      )

      assert(
        metadata.findConfigurations(
          MetadataQuery(
            rootPath = rootDir,
            deps = Set("ch.qos.logback:logback-classic:1.5.21"),
            useLatestConfigWhenVersionIsUntested = false
          )
        ).isEmpty
      )

      assert(
        metadata.findConfigurations(
          MetadataQuery(
            rootPath = rootDir,
            deps = Set("ch.qos.logback:logback-classic:1.4.9"),
            useLatestConfigWhenVersionIsUntested = false
          )
        ).size == 1
      )
    }

    test("io.netty:netty-codec-http2:4.1.128.Final") {
      val nettyCodecMetadata = metadata.findConfigurations(
        MetadataQuery(
          rootPath = rootDir,
          deps = Set("io.netty:netty-codec-http2:4.1.128.Final"),
          useLatestConfigWhenVersionIsUntested = true
        )
      )
      assert(
        nettyCodecMetadata.size == 1
      )
      val copyTo = os.temp.dir()
      metadata.copyDirectoryConfiguration(nettyCodecMetadata, copyTo)
      assert(os.list(copyTo).size == 1)
    }
  }

}
