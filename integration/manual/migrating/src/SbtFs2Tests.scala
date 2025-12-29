package mill.integration
import utest.*
object SbtFs2Tests extends InitBuildTestSuite {
  def gitUrl = "https://github.com/typelevel/fs2"
  def gitRev = "v3.12.2"
  def initArgs = Seq("--mill-jvm-id", "17")
  def tests = Tests {
    test("compile") {
      checkPasses("core.js.2_12_20.compile", "compiling 49 Scala sources")
      checkPasses("core.js.2_12_20.test.compile", "compiling 53 Scala sources")
      checkPasses("core.js.2_13_16.compile", "compiling 50 Scala sources")
      checkPasses("core.js.2_13_16.test.compile", "compiling 56 Scala sources")
      checkPasses("core.js.3_3_5.compile", "compiling 50 Scala sources")
      checkPasses("core.js.3_3_5.test.compile", "compiling 54 Scala sources")
      checkPasses("core.jvm.2_12_20.compile", "compiling 51 Scala sources")
      checkPasses("core.jvm.2_12_20.test.compile", "compiling 55 Scala sources")
      checkPasses("core.jvm.2_13_16.compile", "compiling 52 Scala sources")
      checkPasses("core.jvm.2_13_16.test.compile", "compiling 58 Scala sources")
      checkPasses("core.jvm.3_3_5.compile", "compiling 52 Scala sources")
      checkPasses("core.jvm.3_3_5.test.compile", "compiling 56 Scala sources")
      checkPasses("core.native.2_12_20.compile", "compiling 49 Scala sources")
      checkPasses("core.native.2_12_20.test.compile", "compiling 53 Scala sources")
      checkPasses("core.native.2_13_16.compile", "compiling 50 Scala sources")
      checkPasses("core.native.2_13_16.test.compile", "compiling 56 Scala sources")
      checkPasses("core.native.3_3_5.compile", "compiling 50 Scala sources")
      checkPasses("core.native.3_3_5.test.compile", "compiling 54 Scala sources")
    }
    test("test") {
      checkPasses("scodec.js.2_12_20.test", "fs2.interop.scodec.ListOfNTest")
      checkPasses("scodec.js.2_13_16.test", "fs2.interop.scodec.ListOfNTest")
      checkPasses("scodec.js.3_3_5.test", "fs2.interop.scodec.ListOfNTest")
      checkPasses(
        "scodec.jvm.2_12_20.test",
        "Running Test Class fs2.interop.scodec.StreamCodecSuite"
      )
      checkPasses(
        "scodec.jvm.2_13_16.test",
        "Running Test Class fs2.interop.scodec.StreamCodecSuite"
      )
      checkPasses("scodec.jvm.3_3_5.test", "Running Test Class fs2.interop.scodec.StreamCodecSuite")
    }
    test("ScalafmtModule") {
      test("checkFormat") {
        checkPasses("core.js.2_12_20.checkFormat", "Checking format of 49 Scala sources")
        checkPasses("core.js.2_12_20.test.checkFormat", "Checking format of 53 Scala sources")
        checkPasses("core.js.2_13_16.checkFormat", "Checking format of 50 Scala sources")
        checkPasses("core.js.2_13_16.test.checkFormat", "Checking format of 56 Scala sources")
        checkPasses("core.js.3_3_5.checkFormat", "Checking format of 50 Scala sources")
        checkPasses("core.js.3_3_5.test.checkFormat", "Checking format of 54 Scala sources")
        checkPasses("core.jvm.2_12_20.checkFormat", "Checking format of 51 Scala sources")
        checkPasses("core.jvm.2_12_20.test.checkFormat", "Checking format of 55 Scala sources")
        checkPasses("core.jvm.2_13_16.checkFormat", "Checking format of 52 Scala sources")
        checkPasses("core.jvm.2_13_16.test.checkFormat", "Checking format of 58 Scala sources")
        checkPasses("core.jvm.3_3_5.checkFormat", "Checking format of 52 Scala sources")
        checkPasses("core.jvm.3_3_5.test.checkFormat", "Checking format of 56 Scala sources")
        checkPasses("core.native.2_12_20.checkFormat", "Checking format of 49 Scala sources")
        checkPasses("core.native.2_12_20.test.checkFormat", "Checking format of 53 Scala sources")
        checkPasses("core.native.2_13_16.checkFormat", "Checking format of 50 Scala sources")
        checkPasses("core.native.2_13_16.test.checkFormat", "Checking format of 56 Scala sources")
        checkPasses("core.native.3_3_5.checkFormat", "Checking format of 50 Scala sources")
        checkPasses("core.native.3_3_5.test.checkFormat", "Checking format of 54 Scala sources")
      }
      test("not extended by modules with no sources") {
        checkFails("mdoc.__.checkFormat", "Cannot resolve mdoc.__.checkFormat")
        checkFails("unidocs.__.checkFormat", "Cannot resolve unidocs.__.checkFormat")
      }
    }
    test("issues") {
      test("benchmark.__.compile fails due to scalacOptions --release 8") - checkFails(
        "benchmark[2.13.16].compile",
        "object Flow is not a member of package java.util.concurrent"
      )
      test("ScalaNative tests fail due to unsupported version") - checkFails(
        "scodec.native[2.13.16].test",
        "com.lihaoyi:mill-libs-scalanativelib-worker-0.4_3:SNAPSHOT"
      )
    }
  }
}
