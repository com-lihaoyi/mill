package mill.scalalib.scalafmt

import mill.T
import mill.define.Sources
import mill.main.Tasks
import mill.scalalib.ScalaModule
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object ScalafmtTests extends TestSuite {

  val scalafmtTestVersion = sys.props.getOrElse("TEST_SCALAFMT_VERSION", ???)

  trait TestBase extends TestUtil.BaseModule {
    def millSourcePath =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait BuildSrcModule {
    def buildSources: Sources
  }

  object ScalafmtTestModule extends TestBase {
    object core extends ScalaModule with ScalafmtModule with BuildSrcModule {
      def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

      def buildSources: Sources = T.sources {
        millSourcePath / "util.sc"
      }

    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "scalafmt"

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    os.write(
      m.millSourcePath / ".scalafmt.conf",
      s"""version = $scalafmtTestVersion
         |runner.dialect = scala213
         |""".stripMargin
    )
    t(eval)
  }

  def tests: Tests = Tests {
    "scalafmt" - {
      def checkReformat(reformatCommand: mill.define.Command[Unit], buildSrcIncluded: Boolean) =
        workspaceTest(ScalafmtTestModule) { eval =>
          val before = getProjectFiles(ScalafmtTestModule.core, eval)

          // first reformat
          val Right(_) = eval.apply(reformatCommand)

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
          val Right(_) = eval.apply(reformatCommand)

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

          val Right(_) = eval.apply(reformatCommand)

          val afterChange = getProjectFiles(ScalafmtTestModule.core, eval)

          assert(
            afterChange("Main.scala").modifyTime > cached("Main.scala").modifyTime,
            afterChange("Person.scala").modifyTime == cached("Person.scala").modifyTime,
            afterChange("application.conf").modifyTime == cached(
              "application.conf"
            ).modifyTime
          )
        }

      "reformat" - checkReformat(ScalafmtTestModule.core.reformat(), false)
      "reformatAll" - checkReformat(
        ScalafmtModule.reformatAll(
          Tasks(Seq(ScalafmtTestModule.core.sources, ScalafmtTestModule.core.buildSources))
        ),
        true
      )
    }
  }

  case class FileInfo(content: String, modifyTime: Long, path: os.Path)

  def getProjectFiles(m: ScalaModule with BuildSrcModule, eval: TestEvaluator) = {
    val Right((sources, _)) = eval.apply(m.sources)
    val Right((resources, _)) = eval.apply(m.resources)
    val Right((buildSources, _)) = eval.apply(m.buildSources)

    val sourcesFiles = sources.flatMap(p => os.walk(p.path))
    val resourcesFiles = resources.flatMap(p => os.walk(p.path))
    val buildFiles = buildSources.map(_.path)
    (sourcesFiles ++ resourcesFiles ++ buildFiles).map { p =>
      p.last -> FileInfo(os.read(p), os.mtime(p), p)
    }.toMap
  }

}
