package mill.scalalib.scalafmt

import mill.*
import mill.define.Discover
import mill.util.Tasks
import mill.scalalib.ScalaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*

object ScalafmtTests extends TestSuite {

  val scalafmtTestVersion = mill.scalalib.api.Versions.scalafmtVersion

  trait BuildSrcModule {
    def buildSources: T[Seq[PathRef]]
  }

  object ScalafmtTestModule extends TestBaseModule {
    object core extends ScalaModule with ScalafmtModule with BuildSrcModule {
      def scalaVersion: T[String] = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

      def buildSources: T[Seq[PathRef]] = Task.Sources {
        moduleDir / "util.sc"
      }

    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "scalafmt"

  def tests: Tests = Tests {
    test("scalafmt") {
      def checkReformat(reformatCommand: mill.define.Command[Unit], buildSrcIncluded: Boolean) =
        UnitTester(ScalafmtTestModule, resourcePath).scoped { eval =>
          os.write(
            ScalafmtTestModule.moduleDir / ".scalafmt.conf",
            s"""version = $scalafmtTestVersion
               |runner.dialect = scala213
               |""".stripMargin
          )
          val before = getProjectFiles(ScalafmtTestModule.core, eval)

          // first reformat
          val Right(_) = eval.apply(reformatCommand): @unchecked

          val firstReformat = getProjectFiles(ScalafmtTestModule.core, eval)

          assert(
            firstReformat("Main.scala").modifyTime > before("Main.scala").modifyTime,
            firstReformat("Main.scala").content != before("Main.scala").content,
            firstReformat("Person.scala").modifyTime > before("Person.scala").modifyTime,
            firstReformat("Person.scala").content != before("Person.scala").content,
            // resources files aren't modified
            firstReformat("application.conf").modifyTime == before(
              "application.conf"
            ).modifyTime
          )

          if (buildSrcIncluded) {
            assert(
              firstReformat("util.sc").modifyTime > before("util.sc").modifyTime,
              firstReformat("util.sc").content != before("util.sc").content
            )
          } else {
            assert(
              firstReformat("util.sc").modifyTime == before("util.sc").modifyTime,
              firstReformat("util.sc").content == before("util.sc").content
            )
          }

          // cached reformat
          val Right(_) = eval.apply(reformatCommand): @unchecked

          val cached = getProjectFiles(ScalafmtTestModule.core, eval)

          assert(
            cached("Main.scala").modifyTime == firstReformat("Main.scala").modifyTime,
            cached("Person.scala").modifyTime == firstReformat("Person.scala").modifyTime,
            cached("util.sc").modifyTime == firstReformat("util.sc").modifyTime,
            cached("application.conf").modifyTime == firstReformat(
              "application.conf"
            ).modifyTime
          )

          // reformat after change
          os.write.over(cached("Main.scala").path, cached("Main.scala").content + "\n object Foo")

          val Right(_) = eval.apply(reformatCommand): @unchecked

          val afterChange = getProjectFiles(ScalafmtTestModule.core, eval)

          assert(
            afterChange("Main.scala").modifyTime > cached("Main.scala").modifyTime,
            afterChange("Person.scala").modifyTime == cached("Person.scala").modifyTime,
            afterChange("application.conf").modifyTime == cached(
              "application.conf"
            ).modifyTime
          )
        }

      test("reformat") - checkReformat(ScalafmtTestModule.core.reformat(), false)
      test("reformatAll") - checkReformat(
        ScalafmtModule.reformatAll(
          Tasks(Seq(ScalafmtTestModule.core.sources, ScalafmtTestModule.core.buildSources))
        ),
        true
      )
    }
  }

  case class FileInfo(content: String, modifyTime: Long, path: os.Path)

  def getProjectFiles(m: ScalaModule & BuildSrcModule, eval: UnitTester) = {
    val Right(sourcesRes) = eval.apply(m.sources): @unchecked
    val Right(resourcesRes) = eval.apply(m.resources): @unchecked
    val Right(buildSourcesRes) = eval.apply(m.buildSources): @unchecked

    val sourcesFiles = sourcesRes.value.flatMap(p => os.walk(p.path))
    val resourcesFiles = resourcesRes.value.flatMap(p => os.walk(p.path))
    val buildFiles = buildSourcesRes.value.map(_.path)
    (sourcesFiles ++ resourcesFiles ++ buildFiles).map { p =>
      p.last -> FileInfo(os.read(p), os.mtime(p), p)
    }.toMap
  }

}
