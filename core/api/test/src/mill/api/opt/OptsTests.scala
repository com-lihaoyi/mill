package mill.api.opt

import utest.*
import mill.api.opt.*

class OptsTests extends TestSuite {

  val homeDir = os.home
  val workDir = homeDir / "work"
  val outDir = workDir / "out"

  val srcDir1 = workDir / "src1"
  val srcDir2 = workDir / "src2"
  val sources1 = Seq(srcDir1, srcDir2)

  val srcDir3 = workDir / "src3"
  val srcDir4 = workDir / "src4"
  val sources2 = Seq(srcDir3, srcDir4)

  val plugin1 = homeDir / ".cache" / "plugin1"

  val opts1 = Opts(
    // single arg
    Opt("-deprecation"),
    // implicit single args
    "-verbose",
    // two args as group
    OptGroup("--release", "17"),
    // an option including a file via ArgParts
    Opt("-Xplugin=", plugin1),
    // an option including a file via arg string interpolator
    opt"-Xplugin:${plugin1}",
    // some files
    sources1,
    // some files as ArgGroup
    OptGroup(sources2*),
    // Mixed ArgGroup
    OptGroup(opt"--extra", opt"-Xplugin=${plugin1}") ++ OptGroup(sources1*)
  )

  val expectedOpts1 = Opts(
    Opt("-deprecation"),
    Opt("-verbose"),
    OptGroup(
      Opt("--release"),
      Opt("17")
    ),
    Opt("-Xplugin=", plugin1),
    Opt("-Xplugin:", plugin1),
    Opt(srcDir1),
    Opt(srcDir2),
    Opt(srcDir3),
    Opt(srcDir4),
    OptGroup(
      Opt("--extra"),
      Opt("-Xplugin=", plugin1),
      Opt(srcDir1),
      Opt(srcDir2)
    )
  )

  val expectedSeq1 = Seq(
    "-deprecation",
    "-verbose",
    "--release",
    "17",
    s"-Xplugin=${plugin1.toString()}",
    // an option including a file via arg string interpolator
    s"-Xplugin:${plugin1.toString()}"
  ) ++
    sources1.map(_.toString())
    ++
    sources2.map(_.toString())
    ++
    Seq(
      "--extra",
      s"-Xplugin=${plugin1.toString()}"
    ) ++
    sources1.map(_.toString())

  override def tests: Tests = Tests {
    test("structure") {
      assert(opts1 == expectedOpts1)
    }
    test("toStringSeq") {
      val str = opts1.toStringSeq
      assert(str == expectedSeq1)
    }
    test("jsonify") {
      test("without-mapping") {
        val json = upickle.write(opts1)
        assertGoldenLiteral(
          json,
          "{\"value\":[{\"value\":[[[\"-deprecation\",null]],[[\"-verbose\",null]],[[\"--release\",null]],[[\"17\",null]],[[\"-Xplugin=\",null],[null,\"/home/lefou/.cache/plugin1\"]],[[\"-Xplugin:\",null],[null,\"/home/lefou/.cache/plugin1\"]],[[null,\"/home/lefou/work/src1\"]],[[null,\"/home/lefou/work/src2\"]],[[null,\"/home/lefou/work/src3\"]],[[null,\"/home/lefou/work/src4\"]],[[\"--extra\",null]],[[\"-Xplugin=\",null],[null,\"/home/lefou/.cache/plugin1\"]],[[null,\"/home/lefou/work/src1\"]],[[null,\"/home/lefou/work/src2\"]]]}]}"
        )
        assert(json.split("\\Q$HOME\\E").size == 1)
        val back = upickle.read[Opts](json)
        assert(opts1 == back)
      }
//      test("with-mapping-home") {
//        val json = upickle.write(opts1)
//        assertGoldenLiteral(
//          json,
//          "{\"value\":[{\"value\":[[[\"-deprecation\",null]],[[\"-verbose\",null]],[[\"--release\",null]],[[\"17\",null]],[[\"-Xplugin=\",null],[null,\"/home/lefou/.cache/plugin1\"]],[[\"-Xplugin:\",null],[null,\"/home/lefou/.cache/plugin1\"]],[[null,\"/home/lefou/work/src1\"]],[[null,\"/home/lefou/work/src2\"]],[[null,\"/home/lefou/work/src3\"]],[[null,\"/home/lefou/work/src4\"]],[[\"--extra\",null]],[[\"-Xplugin=\",null],[null,\"/home/lefou/.cache/plugin1\"]],[[null,\"/home/lefou/work/src1\"]],[[null,\"/home/lefou/work/src2\"]]]}]}"
//        )
//        assert(json.split("\\Q$HOME\\E").size == 10)
//        val back = upickle.read[Opts](json)
//        assert(opts1 == back)
//      }
    }
  }
}
