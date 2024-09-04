package mill.contrib.buildinfo

import mill._
import mill.scalalib.ScalaModule
import mill.scalajslib.ScalaJSModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import os.Path
import utest._

object BuildInfoTests extends TestSuite {

  val scalaVersionString = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val scalaJSVersionString = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)

  object EmptyBuildInfo extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq.empty[BuildInfo.Value]
  }

  object BuildInfoPlain extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoScalaJS extends TestBaseModule with BuildInfo with ScalaJSModule {
    def scalaVersion = scalaVersionString
    def scalaJSVersion = scalaJSVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoComment extends TestBaseModule with BuildInfo with ScalaModule {
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

  object BuildInfoStatic extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    override def buildInfoStaticCompiled = true
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoSettings extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    override def buildInfoObjectName = "bar"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )
  }

  object BuildInfoJava extends TestBaseModule with BuildInfo {
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", "not-provided-for-java-modules")
    )
  }

  object BuildInfoJavaStatic extends TestBaseModule with BuildInfo {
    def buildInfoPackageName = "foo"
    override def buildInfoStaticCompiled = true
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", "not-provided-for-java-modules")
    )
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "buildinfo"

  def buildInfoSourcePath(eval: UnitTester) =
    eval.outPath / "buildInfoSources.dest" / "foo" / "BuildInfo.scala"

  def buildInfoResourcePath(eval: UnitTester) =
    eval.outPath / "buildInfoResources.dest" / "foo" / "BuildInfo.buildinfo.properties"
  def tests: Tests = Tests {

    test("notCreateEmptySourcefile") {
      val eval = UnitTester(EmptyBuildInfo, testModuleSourcesPath / "scala")
      val Right(_) = eval.apply(EmptyBuildInfo.buildInfoSources)
      assert(!os.exists(buildInfoSourcePath(eval)))
    }

    test("fileGeneration") {
      val eval = UnitTester(BuildInfoComment, testModuleSourcesPath / "scala")
      val Right(_) = eval.apply(BuildInfoComment.compile)

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
      val Right(_) = eval.apply(BuildInfoComment.jar)

      val expectedResource = "mill.contrib.buildinfo.BuildInfo for foo."

      assert(os.exists(buildInfoResourcePath(eval)))
      val buildInfoResource = os.read(buildInfoResourcePath(eval))
      assert(buildInfoResource.contains(expectedResource))
    }

    test("supportCustomSettings") {
      val eval = UnitTester(BuildInfoSettings, testModuleSourcesPath / "scala")
      val Right(result) = eval.apply(BuildInfoSettings.buildInfoSources)
      val path = result.value.head.path

      assert(os.exists(path / "foo" / "bar.scala"))

      val found = os.read(path / "foo" / "bar.scala").replaceAll("(\r\n)|\r", "\n")
      assert(found.contains("object bar"))
    }

    test("compile") {
      val eval = UnitTester(BuildInfoPlain, testModuleSourcesPath / "scala")
      val Right(_) = eval.apply(BuildInfoPlain.compile)
      assert(true)
    }

    test("run") {
      val eval = UnitTester(BuildInfoPlain, testModuleSourcesPath / "scala")
      val runResult = eval.outPath / "hello-mill"
      val Right(_) =
        eval.apply(BuildInfoPlain.run(T.task(Args(runResult.toString))))

      assert(
        os.exists(runResult),
        os.read(runResult) == scalaVersionString
      )
    }

    test("scalajs") {
      val eval = UnitTester(BuildInfoScalaJS, testModuleSourcesPath / "scala-simple")
      val runResult = eval.outPath / "hello-mill"
      assert(eval.apply(BuildInfoScalaJS.fastLinkJS).isRight)
    }

    test("static") {
      val eval = UnitTester(BuildInfoStatic, testModuleSourcesPath / "scala")
      // When `buildInfoStaticCompiled = true`, make sure we always create the
      // buildinfo Scala file and never create the resource file
      val runResult = eval.outPath / "hello-mill"

      val Right(_) =
        eval.apply(BuildInfoStatic.run(T.task(Args(runResult.toString))))

      assert(os.exists(buildInfoSourcePath(eval)))
      assert(!os.exists(buildInfoResourcePath(eval)))
      assert(os.exists(runResult))
      assert(os.read(runResult) == scalaVersionString)
    }

    test("java") {
      val eval = UnitTester(BuildInfoJava, testModuleSourcesPath / "java")
      val runResult = eval.outPath / "hello-mill"
      val Right(_) =
        eval.apply(BuildInfoJava.run(T.task(Args(runResult.toString))))

      assert(
        os.exists(runResult),
        os.read(runResult) == "not-provided-for-java-modules"
      )
    }

    test("java-static") {
      val eval = UnitTester(BuildInfoJavaStatic, testModuleSourcesPath / "java")
      val runResult = eval.outPath / "hello-mill"
      val generatedSrc = eval.outPath / "buildInfoSources.dest" / "foo" / "BuildInfo.java"
      val Right(_) =
        eval.apply(BuildInfoJavaStatic.run(T.task(Args(runResult.toString))))

      assert(
        os.exists(runResult),
        os.exists(generatedSrc),
        os.read(runResult) == "not-provided-for-java-modules"
      )
    }

    test("generatedSources must be a folder") {
      val eval = UnitTester(BuildInfoPlain, testModuleSourcesPath / "scala")
      val buildInfoGeneratedSourcesFolder = eval.outPath / "buildInfoSources.dest"
      val Right(result) = eval.apply(BuildInfoPlain.generatedSources)
      assert(
        result.value.size == 1,
        os.isDir(result.value.head.path),
        result.value.head.path == buildInfoGeneratedSourcesFolder
      )
    }
  }
}
