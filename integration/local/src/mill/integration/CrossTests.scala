package mill.integration

import mill.api.PathRef
import utest._

class CrossTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("cross", fork, clientServer) {
  val tests = Tests {
    initWorkspace()
    "topCross" - {
      val res = eval("topCross[a].path")
      assert(res == true)
      val value = ujson.read(meta("topCross[a].path"))("value")
      println(s"value: ${value}")
      val topCrossAPath = upickle.default.read[PathRef](value)
      assert(
        topCrossAPath.path == wd / "topCross"
      )
    }
    "topCross2" - {
      val res = eval("topCross2[a,1].path")
      assert(res == true)
      val value = ujson.read(meta("topCross2[a,1].path"))("value")
      println(s"value: ${value}")
      val topCrossAPath = upickle.default.read[PathRef](value)
      assert(
        topCrossAPath.path == wd / "topCross2"
      )
    }
    "topCrossU" - {
      val res = eval("topCrossU[a].path")
      assert(res == true)
      val value = ujson.read(meta("topCrossU[a].path"))("value")
      println(s"value: ${value}")
      val topCrossAPath = upickle.default.read[PathRef](value)
      assert(
        topCrossAPath.path == wd / "topCrossU" / "a"
      )
    }
    "topCross2" - {
      val res = eval("topCross2U[a,1].path")
      assert(res == true)
      val value = ujson.read(meta("topCross2U[a,1].path"))("value")
      println(s"value: ${value}")
      val topCrossAPath = upickle.default.read[PathRef](value)
      assert(
        topCrossAPath.path == wd / "topCross2U" / "a" / "1"
      )
    }
  }
}
