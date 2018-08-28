package mill.scalalib.scalafmt

import ammonite.ops._
import mill.main.Tasks
import mill.scalalib.ScalaModule
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object ScalafmtTests extends TestSuite {

  trait TestBase extends TestUtil.BaseModule {
    def millSourcePath =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  object ScalafmtTestModule extends TestBase {
    object core extends ScalaModule with ScalafmtModule {
      def scalaVersion = "2.12.4"
    }
  }

  val resourcePath = pwd / 'scalalib / 'test / 'resources / 'scalafmt

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: Path = resourcePath)(t: TestEvaluator => T)(
      implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    'scalafmt - {
      def checkReformat(reformatCommand: mill.define.Command[Unit]) =
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
              "application.conf").modifyTime
          )

          // cached reformat
          val Right(_) = eval.apply(reformatCommand)

          val cached = getProjectFiles(ScalafmtTestModule.core, eval)

          assert(
            cached("Main.scala").modifyTime == firstReformat("Main.scala").modifyTime,
            cached("Person.scala").modifyTime == firstReformat("Person.scala").modifyTime,
            cached("application.conf").modifyTime == firstReformat(
              "application.conf").modifyTime
          )

          // reformat after change
          write.over(cached("Main.scala").path,
                     cached("Main.scala").content + "\n object Foo")

          val Right(_) = eval.apply(reformatCommand)

          val afterChange = getProjectFiles(ScalafmtTestModule.core, eval)

          assert(
            afterChange("Main.scala").modifyTime > cached("Main.scala").modifyTime,
            afterChange("Person.scala").modifyTime == cached("Person.scala").modifyTime,
            afterChange("application.conf").modifyTime == cached(
              "application.conf").modifyTime
          )
        }

      'reformat - checkReformat(ScalafmtTestModule.core.reformat())
      'reformatAll - checkReformat(
        ScalafmtModule.reformatAll(Tasks(Seq(ScalafmtTestModule.core.sources))))
    }
  }

  case class FileInfo(content: String, modifyTime: Long, path: Path)

  def getProjectFiles(m: ScalaModule, eval: TestEvaluator) = {
    val Right((sources, _)) = eval.apply(m.sources)
    val Right((resources, _)) = eval.apply(m.resources)

    val sourcesFiles = sources.flatMap(p => ls.rec(p.path))
    val resourcesFiles = resources.flatMap(p => ls.rec(p.path))
    (sourcesFiles ++ resourcesFiles).map { p =>
      p.name -> FileInfo(read(p), p.mtime.toMillis, p)
    }.toMap
  }

}
