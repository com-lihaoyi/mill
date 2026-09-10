package mill.javalib

import utest.*

object OsDetectorTests extends TestSuite {

  val tests = Tests {
    test("name") {
      assert(OsDetector.detect("Linux", "amd64")("os.detected.name") == "linux")
      assert(OsDetector.detect("Windows 11", "amd64")("os.detected.name") == "windows")
      assert(OsDetector.detect("Mac OS X", "aarch64")("os.detected.name") == "osx")
    }

    test("arch") {
      assert(OsDetector.detect("Linux", "amd64")("os.detected.arch") == "x86_64")
      assert(OsDetector.detect("Linux", "x86_64")("os.detected.arch") == "x86_64")
      assert(OsDetector.detect("Linux", "aarch64")("os.detected.arch") == "aarch_64")
      assert(OsDetector.detect("Mac OS X", "aarch64")("os.detected.arch") == "aarch_64")
      assert(OsDetector.detect("Linux", "i686")("os.detected.arch") == "x86_32")
      assert(OsDetector.detect("Linux", "ppc64le")("os.detected.arch") == "ppcle_64")
      assert(OsDetector.detect("Linux", "s390x")("os.detected.arch") == "s390_64")
    }

    test("bitness") {
      assert(OsDetector.detect("Linux", "amd64")("os.detected.bitness") == "64")
      assert(OsDetector.detect("Linux", "i686")("os.detected.bitness") == "32")
    }

    test("classifier") {
      assert(OsDetector.detect("Linux", "amd64")("os.detected.classifier") == "linux-x86_64")
      assert(OsDetector.detect("Windows 11", "amd64")("os.detected.classifier") == "windows-x86_64")
      assert(OsDetector.detect("Mac OS X", "aarch64")("os.detected.classifier") == "osx-aarch_64")
    }

    test("liveDetection") {
      // Sanity check the properties derived from the JVM actually running this test are populated
      val props = OsDetector.detect()
      assert(props("os.detected.name").nonEmpty)
      assert(props("os.detected.arch").nonEmpty)
      assert(props("os.detected.classifier") == s"${props("os.detected.name")}-${props("os.detected.arch")}")
    }
  }
}