package mill.integration

import utest._

class PlayJsonTests(fork: Boolean)
    extends IntegrationTestSuite("MILL_PLAY_JSON_REPO", "play-json", fork) {

  val scalaVersion = "2.12.3"

  override def buildFiles: Seq[os.Path] = {
    os.list(buildFilePath).filter(_.ext == "sc")
  }

  val tests = Tests {
    initWorkspace()

    "jvm" - {
      assert(eval(s"playJsonJvm[${scalaVersion}].{test-scalatest,test-specs2}"))
      val jvmMeta: Seq[String] = Seq(
        meta(s"playJsonJvm[${scalaVersion}].test-scalatest.test"),
        meta(s"playJsonJvm[${scalaVersion}].test-specs2.test")
      )

      assert(
        jvmMeta.exists(_.contains("play.api.libs.json.JsonSharedSpec")),
        jvmMeta.exists(_.contains("JSON should support basic array operations"))
      )

      assert(
        jvmMeta.exists(_.contains("play.api.libs.json.JsonValidSpec")),
        jvmMeta.exists(_.contains("JSON reads should::validate Dates"))
      )
    }
    "js" - {
      assert(eval(s"playJsonJs[${scalaVersion}].test"))
      val jsMeta = meta(s"playJsonJs[${scalaVersion}].test.test")

      assert(
        jsMeta.contains("play.api.libs.json.JsonSharedSpec"),
        jsMeta.contains("JSON should support basic array operations")
      )

      assert(
        jsMeta.contains("play.api.libs.json.JsonSpec"),
        jsMeta.contains(
          "Complete JSON should create full object when lose precision when parsing BigDecimals"
        )
      )
    }
    "playJoda" - {
      assert(eval(s"playJoda[${scalaVersion}].test"))
      val metaFile = meta(s"playJoda[${scalaVersion}].test.test")

      assert(
        metaFile.contains("play.api.libs.json.JsonJodaValidSpec"),
        metaFile.contains("JSON reads should::validate Dates")
      )
    }

    "benchmarks" - {
//      "benchmarks[2.12.4].runJmh" -i 1 -wi 1 -f1 -t1
    }
  }
}
