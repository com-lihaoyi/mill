package mill.scalalib.spotless

import mill._
import mill.main.Tasks
import mill.scalalib.ScalaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import utest.framework.TestPath

object SpotlessTests extends TestSuite {

  val spotlessTestVersion = "2.28.0" // Replace with the actual Spotless version you're using

  trait BuildSrcModule {
    def buildSources: T[Seq[PathRef]]
  }

  object SpotlessTestModule extends TestBaseModule {
    object core extends ScalaModule with SpotlessModule with BuildSrcModule {
      def scalaVersion: T[String] = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

      def buildSources: T[Seq[PathRef]] = Task.Sources {
        millSourcePath / "util.sc"
      }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "spotless"

  def tests: Tests = Tests {
    test("spotless") {
      def checkReformat(reformatCommand: mill.define.Command[Unit], buildSrcIncluded: Boolean) =
        UnitTester(SpotlessTestModule, resourcePath).scoped { eval =>
          os.write(
            SpotlessTestModule.millSourcePath / ".spotless.conf",
            s"""
               |scala {
               |  scalafmt {
               |    version = "$spotlessTestVersion"
               |    configFile = ".scalafmt.conf"
               |  }
               |}
               |""".stripMargin
          )
          
          // Write .scalafmt.conf as well
          os.write(
            SpotlessTestModule.millSourcePath / ".scalafmt.conf",
            s"""version = $spotlessTestVersion
               |runner.dialect = scala213
               |""".stripMargin
          )
          
          val before = getProjectFiles(SpotlessTestModule.core, eval)

          // first reformat
          val Right(_) = eval.apply(reformatCommand)

          val firstReformat = getProjectFiles(SpotlessTestModule.core, eval)

          assert(
            firstReformat("Main.scala").modifyTime > before("Main.scala").modifyTime,
            firstReformat("Main.scala").content != before("Main.scala").content,
            firstReformat("Person.scala").modifyTime > before("Person.scala").modifyTime,
            firstReformat("Person.scala").content != before("Person.scala").content,
            // resources files aren't modified
            firstReformat("application.conf").modifyTime == before("application.conf").modifyTime
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

          val cached = getProjectFiles(SpotlessTestModule.core, eval)

          assert(
            cached("Main.scala").modifyTime == firstReformat("Main.scala").modifyTime,
            cached("Person.scala").modifyTime == firstReformat("Person.scala").modifyTime,
            cached("util.sc").modifyTime == firstReformat("util.sc").modifyTime,
            cached("application.conf").modifyTime == firstReformat("application.conf").modifyTime
          )

          // reformat after change
          os.write.over(cached("Main.scala").path, cached("Main.scala").content + "\n object Foo")

          val Right(_) = eval.apply(reformatCommand)

          val afterChange = getProjectFiles(SpotlessTestModule.core, eval)

          assert(
            afterChange("Main.scala").modifyTime > cached("Main.scala").modifyTime,
            afterChange("Person.scala").modifyTime == cached("Person.scala").modifyTime,
            afterChange("application.conf").modifyTime == cached("application.conf").modifyTime
          )
        }

      test("reformat") - checkReformat(SpotlessTestModule.core.reformat(), false)
      test("reformatAll") - checkReformat(
        SpotlessModule.reformatAll(
          Tasks(Seq(SpotlessTestModule.core.sources, SpotlessTestModule.core.buildSources))
        ),
        true
      )
    }
  }

  case class FileInfo(content: String, modifyTime: Long, path: os.Path)

  def getProjectFiles(m: ScalaModule with BuildSrcModule, eval: UnitTester) = {
    val Right(sourcesRes) = eval.apply(m.sources)
    val Right(resourcesRes) = eval.apply(m.resources)
    val Right(buildSourcesRes) = eval.apply(m.buildSources)

    val sourcesFiles = sourcesRes.value.flatMap(p => os.walk(p.path))
    val resourcesFiles = resourcesRes.value.flatMap(p => os.walk(p.path))
    val buildFiles = buildSourcesRes.value.map(_.path)
    (sourcesFiles ++ resourcesFiles ++ buildFiles).map { p =>
      p.last -> FileInfo(os.read(p), os.mtime(p), p)
    }.toMap
  }
}