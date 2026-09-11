package mill.javalib

import utest.*

object OsDetectorModuleTests extends TestSuite {

  val tests = Tests {
    test("name") {
      assert(OsDetectorModule.detect("Linux", "amd64")("os.detected.name") == "linux")
      assert(OsDetectorModule.detect("Windows 11", "amd64")("os.detected.name") == "windows")
      assert(OsDetectorModule.detect("Mac OS X", "aarch64")("os.detected.name") == "osx")
    }

    test("arch") {
      assert(OsDetectorModule.detect("Linux", "amd64")("os.detected.arch") == "x86_64")
      assert(OsDetectorModule.detect("Linux", "x86_64")("os.detected.arch") == "x86_64")
      assert(OsDetectorModule.detect("Linux", "aarch64")("os.detected.arch") == "aarch_64")
      assert(OsDetectorModule.detect("Mac OS X", "aarch64")("os.detected.arch") == "aarch_64")
      assert(OsDetectorModule.detect("Linux", "i686")("os.detected.arch") == "x86_32")
      assert(OsDetectorModule.detect("Linux", "ppc64le")("os.detected.arch") == "ppcle_64")
      assert(OsDetectorModule.detect("Linux", "s390x")("os.detected.arch") == "s390_64")
    }

    test("bitness") {
      assert(OsDetectorModule.detect("Linux", "amd64")("os.detected.bitness") == "64")
      assert(OsDetectorModule.detect("Linux", "i686")("os.detected.bitness") == "32")
    }

    test("classifier") {
      assert(OsDetectorModule.detect("Linux", "amd64")("os.detected.classifier") == "linux-x86_64")
      assert(OsDetectorModule.detect(
        "Windows 11",
        "amd64"
      )("os.detected.classifier") == "windows-x86_64")
      assert(OsDetectorModule.detect(
        "Mac OS X",
        "aarch64"
      )("os.detected.classifier") == "osx-aarch_64")
    }

    test("liveDetection") {
      // Sanity check the properties derived from the JVM actually running this test are populated
      val props = OsDetectorModule.detect()
      assert(props("os.detected.name").nonEmpty)
      assert(props("os.detected.arch").nonEmpty)
      assert(props(
        "os.detected.classifier"
      ) == s"${props("os.detected.name")}-${props("os.detected.arch")}")
    }
  }
}
