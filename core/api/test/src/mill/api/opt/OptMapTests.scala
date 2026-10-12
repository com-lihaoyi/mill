package mill.api.opt

import mill.api.opt.*
import utest.*

class OptMapTests extends TestSuite {

  val homeDir = os.root / "tmp/opts-test"
  val workDir = homeDir / "work"
  val outDir = workDir / "out"

  val srcDir1 = workDir / "src1"
  val srcDir2 = workDir / "src2"
  val sources1 = Seq(srcDir1, srcDir2)

  val srcDir3 = workDir / "src3"
  val srcDir4 = workDir / "src4"
  val sources2 = Seq(srcDir3, srcDir4)

  val plugin1 = homeDir / ".cache" / "plugin1"

  val opts1 = OptMap(
    "a" -> "AAA",
    "b" -> Opt("B", "BB"),
    ("c", "CCC"),
    "d" -> Opt("-Xplugin=", plugin1),
    "e" -> opt"-Xplugin:${plugin1}",
    "f" -> Opt.mkPath(sources1, sep = ":", prefix = "-special-files=")
  )

  val expectedMap1 = Map(
    "a" -> "AAA",
    "b" -> "BBB",
    "c" -> "CCC",
    "d" -> s"-Xplugin=${plugin1}",
    "e" -> s"-Xplugin:${plugin1}",
    "f" -> s"-special-files=$srcDir1:$srcDir2"
  )

  override def tests: Tests = Tests {
    test("toStringSeq") {
      val str = opts1.toStringMap
      assert(str == expectedMap1)
    }
    test("toString") {
      val a = OptMap("-arg" -> "1").toString()
      assertGoldenLiteral(a, "Map(-arg -> 1)")
    }
    test("jsonify") {
      test("without-mapping") {
        val json = upickle.write(opts1)

//        pprint.log(upickle.write(opts1, indent = 4))
//        pprint.log(opts1)
//        pprint.log(opts1.toString)

        assertGoldenLiteral(
          json,
          """{"e":["-Xplugin:",{"path":"/tmp/opts-test/.cache/plugin1"}],"f":["-special-files=",{"path":"/tmp/opts-test/work/src1"},":",{"path":"/tmp/opts-test/work/src2"}],"a":"AAA","b":"BBB","c":"CCC","d":["-Xplugin=",{"path":"/tmp/opts-test/.cache/plugin1"}]}"""
        )

        assert(json.split("\\Q$HOME\\E").size == 1 + 0)
        val back = upickle.read[OptMap](json)
        assert(opts1 == back)
      }
    }
  }
}
