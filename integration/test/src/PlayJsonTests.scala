package mill.integration


import utest._

class PlayJsonTests(fork: Boolean) extends IntegrationTestSuite("MILL_PLAY_JSON_REPO", "play-json", fork) {

  override def buildFiles: Seq[os.Path] = {
    os.list(buildFilePath).filter(_.ext == "sc")
  }

  val tests = Tests{
    initWorkspace()

    'jvm - {
      assert(eval("playJsonJvm[2.12.4].test"))
      val jvmMeta = meta("playJsonJvm[2.12.4].test.test")

      assert(
        jvmMeta.contains("play.api.libs.json.JsonSharedSpec"),
        jvmMeta.contains("JSON should support basic array operations")
      )

      assert(
        jvmMeta.contains("play.api.libs.json.JsonValidSpec"),
        jvmMeta.contains("JSON reads should::validate Dates")
      )
    }
    'js - {
      assert(eval("playJsonJs[2.12.4].test"))
      val jsMeta = meta("playJsonJs[2.12.4].test.test")

      assert(
        jsMeta.contains("play.api.libs.json.JsonSharedSpec"),
        jsMeta.contains("JSON should support basic array operations")
      )

      assert(
        jsMeta.contains("play.api.libs.json.JsonSpec"),
        jsMeta.contains("Complete JSON should create full object when lose precision when parsing BigDecimals")
      )
    }
    'playJoda - {
      assert(eval("playJoda[2.12.4].test"))
      val metaFile = meta("playJoda[2.12.4].test.test")

      assert(
        metaFile.contains("play.api.libs.json.JsonJodaValidSpec"),
        metaFile.contains("JSON reads should::validate Dates")
      )
    }

    'benchmarks - {
//      "benchmarks[2.12.4].runJmh" -i 1 -wi 1 -f1 -t1
    }
  }
}
