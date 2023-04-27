package mill.contrib.buildinfo

import mill.{BuildInfo => _, _}
import mill.util.TestEvaluator
import mill.util.TestUtil
import os.Path
import utest._
import utest.framework.TestPath

object BuildInfoTests extends TestSuite {

  val scalaVersionString = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  trait BuildInfoModule extends TestUtil.BaseModule with BuildInfo {
    // override build root to test custom builds/modules
    override def millSourcePath: Path = TestUtil.getSrcPathStatic() / "scala"
  }

  object EmptyBuildInfo extends BuildInfoModule with scalalib.ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq.empty[BuildInfo.Value]
  }

  object BuildInfoPlain extends BuildInfoModule with scalalib.ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoComment extends BuildInfoModule with scalalib.ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value(
        "scalaVersion",
        scalaVersion(),
        comment = "a helpful comment explaining what scalaVersion is all about"
      ),
      BuildInfo.Value(
        "scalaVersion2",
        scalaVersion(),
        comment = "a helpful comment explaining what scalaVersion\nis all about"
      )
    )
  }

  object BuildInfoStatic extends BuildInfoModule with scalalib.ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    override def buildInfoStaticCompiled = true
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoSettings extends BuildInfoModule with scalalib.ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    override def buildInfoObjectName = "bar"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoJava extends BuildInfoModule {
    def buildInfoPackageName = "foo"
    override def millSourcePath: Path = TestUtil.getSrcPathStatic()
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", "not-provided-for-java-modules")
    )
  }

  object BuildInfoJavaStatic extends BuildInfoModule {
    def buildInfoPackageName = "foo"
    override def millSourcePath: Path = TestUtil.getSrcPathStatic()
    override def buildInfoStaticCompiled = true
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", "not-provided-for-java-modules")
    )
  }

  val testModuleSourcesPath: Path =
    os.pwd / "contrib" / "buildinfo" / "test" / "resources" / "buildinfo"

  def workspaceTest[T](m: TestUtil.BaseModule, suffix: String)(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(testModuleSourcesPath / suffix, m.millSourcePath)
    t(eval)
  }

  def buildInfoSourcePath(eval: TestEvaluator) =
    eval.outPath / "buildInfoSources.dest" / "foo" / "BuildInfo.scala"

  def buildInfoResourcePath(eval: TestEvaluator) =
    eval.outPath / "buildInfoResources.dest" / "foo" / "BuildInfo.buildinfo.properties"
  def tests: Tests = Tests {

    "notCreateEmptySourcefile" - workspaceTest(EmptyBuildInfo, "scala") { eval =>
      val Right((result, evalCount)) = eval.apply(EmptyBuildInfo.buildInfoSources)
      assert(!os.exists(buildInfoSourcePath(eval)))
    }

    "fileGeneration" - workspaceTest(BuildInfoComment, "scala") { eval =>
      val Right((result, evalCount)) =
        eval.apply(BuildInfoComment.compile)

      // Make sure that the buildinfo Scala file buildinfo is created and buildinfo
      // resource file is *not* created when we compile the Scala code
      assert(os.exists(buildInfoSourcePath(eval)))
      assert(!os.exists(buildInfoResourcePath(eval)))

      val expectedSource = Seq(
        """  /** a helpful comment explaining what scalaVersion is all about */
          |  val scalaVersion = buildInfoProperties.getProperty("scalaVersion")""".stripMargin,
        """  /**
          |    * a helpful comment explaining what scalaVersion
          |    * is all about
          |    */
          |  val scalaVersion2 = buildInfoProperties.getProperty("scalaVersion2")""".stripMargin
      )

      val buildInfoCode = os.read(buildInfoSourcePath(eval)).linesIterator.mkString("\n")
      for (e <- expectedSource) {
        assert(buildInfoCode.contains(e.linesIterator.mkString("\n")))
      }

      // But becomes created once we package the jar for running
      val Right((result2, evalCount2)) = eval.apply(BuildInfoComment.jar)

      val expectedResource = "mill.contrib.buildinfo.BuildInfo for foo."

      assert(os.exists(buildInfoResourcePath(eval)))
      val buildInfoResource = os.read(buildInfoResourcePath(eval))
      assert(buildInfoResource.contains(expectedResource))
    }

    "supportCustomSettings" - workspaceTest(BuildInfoSettings, "scala") { eval =>
      val Right((result, evalCount)) = eval.apply(BuildInfoSettings.buildInfoSources)
      val path = result.head.path

      assert(os.exists(path / "foo" / "bar.scala"))

      val found = os.read(path / "foo" / "bar.scala").replaceAll("(\r\n)|\r", "\n")
      assert(found.contains("object bar"))
    }

    "compile" - workspaceTest(BuildInfoPlain, "scala") { eval =>
      val Right((result, evalCount)) = eval.apply(BuildInfoPlain.compile)
      assert(true)
    }

    "run" - workspaceTest(BuildInfoPlain, "scala") { eval =>
      val runResult = eval.outPath / "hello-mill"
      val Right((result, evalCount)) =

        eval.apply(BuildInfoPlain.run(T.task(mainargs.Leftover(runResult.toString))))

      assert(
        os.exists(runResult),
        os.read(runResult) == scalaVersionString
      )
    }

    "static" - workspaceTest(BuildInfoStatic, "scala") { eval =>
      // When `buildInfoStaticCompiled = true`, make sure we always create the
      // buildinfo Scala file and never create the resource file
      val runResult = eval.outPath / "hello-mill"

      val Right((result2, evalCount2)) =
        eval.apply(BuildInfoStatic.run(T.task(mainargs.Leftover(runResult.toString))))

      assert(os.exists(buildInfoSourcePath(eval)))
      assert(!os.exists(buildInfoResourcePath(eval)))
      assert(os.exists(runResult))
      assert(os.read(runResult) == scalaVersionString)
    }

    "java" - workspaceTest(BuildInfoJava, "java") { eval =>
      val runResult = eval.outPath / "hello-mill"
      val Right((result, evalCount)) =
        eval.apply(BuildInfoJava.run(T.task(mainargs.Leftover(runResult.toString))))

      assert(
        os.exists(runResult),
        os.read(runResult) == "not-provided-for-java-modules"
      )
    }

    "generatedSources must be a folder" - workspaceTest(BuildInfoPlain, "scala") { eval =>
      val buildInfoGeneratedSourcesFolder = eval.outPath / "buildInfoSources.dest"
      val Right((result, evalCount)) = eval.apply(BuildInfoPlain.generatedSources)
      assert(
        result.size == 1,
        os.isDir(result.head.path),
        result.head.path == buildInfoGeneratedSourcesFolder
      )
    }
  }
}
