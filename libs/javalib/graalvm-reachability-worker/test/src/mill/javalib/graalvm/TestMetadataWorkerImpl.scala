package mill.javalib.graalvm

import utest.*

object TestMetadataWorkerImpl extends TestSuite {

  val version = "0.3.32"

  val workDir = os.temp.dir()

  val metadata: GraalVMMetadataWorker = new GraalVMMetadataWorkerImpl()

  val rootDir = metadata.downloadRepo(workDir, version)

  val tests = Tests {
    test("ch.qos.logback:logback-classic") {
      // logback available versions in 0.3.32 are 1.2.11, 1.4.1 and 1.4.9
      assert(
        metadata.findConfigurations(
          MetadataQuery(
            rootPath = rootDir,
            deps = Set("ch.qos.logback:logback-classic:1.5.21"),
            useLatestConfigWhenVersionIsUntested = true
          )
        ).size == 1
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
  }

}
