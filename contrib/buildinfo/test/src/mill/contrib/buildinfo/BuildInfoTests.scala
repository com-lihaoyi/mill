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

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  object BuildInfoPlain extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  object BuildInfoScalaJS extends TestBaseModule with BuildInfo with ScalaJSModule {
    def scalaVersion = scalaVersionString
    def scalaJSVersion = scalaJSVersionString
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )

    lazy val millDiscover = mill.define.Discover[this.type]
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

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  object BuildInfoStatic extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    override def buildInfoStaticCompiled = true
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  object BuildInfoSettings extends TestBaseModule with BuildInfo with ScalaModule {
    def scalaVersion = scalaVersionString
    def buildInfoPackageName = "foo"
    override def buildInfoObjectName = "bar"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", scalaVersion())
    )

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  object BuildInfoJava extends TestBaseModule with BuildInfo {
    def buildInfoPackageName = "foo"
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", "not-provided-for-java-modules")
    )

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  object BuildInfoJavaStatic extends TestBaseModule with BuildInfo {
    def buildInfoPackageName = "foo"
    override def buildInfoStaticCompiled = true
    def buildInfoMembers = Seq(
      BuildInfo.Value("scalaVersion", "not-provided-for-java-modules")
    )

    lazy val millDiscover = mill.define.Discover[this.type]
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "buildinfo"

  def buildInfoSourcePath(eval: UnitTester) =
    eval.outPath / "buildInfoSources.dest/foo/BuildInfo.scala"

  def buildInfoResourcePath(eval: UnitTester) =
    eval.outPath / "buildInfoResources.dest/foo/BuildInfo.buildinfo.properties"
  def tests: Tests = Tests {

    test("notCreateEmptySourcefile") - UnitTester(
      EmptyBuildInfo,
      testModuleSourcesPath / "scala"
    ).scoped { eval =>
      val Right(_) = eval.apply(EmptyBuildInfo.buildInfoSources)
      assert(!os.exists(buildInfoSourcePath(eval)))
    }

    test("fileGeneration") - UnitTester(BuildInfoComment, testModuleSourcesPath / "scala").scoped {
      eval =>
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

    test("supportCustomSettings") - UnitTester(
      BuildInfoSettings,
      testModuleSourcesPath / "scala"
    ).scoped { eval =>
      val Right(result) = eval.apply(BuildInfoSettings.buildInfoSources)
      val path = result.value.head.path

      assert(os.exists(path / "foo/bar.scala"))

      val found = os.read(path / "foo/bar.scala").replaceAll("(\r\n)|\r", "\n")
      assert(found.contains("object bar"))
    }

    test("compile") - UnitTester(BuildInfoPlain, testModuleSourcesPath / "scala").scoped { eval =>
      val Right(_) = eval.apply(BuildInfoPlain.compile)
      assert(true)
    }

    test("run") - UnitTester(BuildInfoPlain, testModuleSourcesPath / "scala").scoped { eval =>
      val runResult = eval.outPath / "hello-mill"
      val Right(_) =
        eval.apply(BuildInfoPlain.run(Task.Anon(Args(runResult.toString))))

      assert(
        os.exists(runResult),
        os.read(runResult) == scalaVersionString
      )
    }

    test("scalajs") - UnitTester(BuildInfoScalaJS, testModuleSourcesPath / "scala-simple").scoped {
      eval =>
        val runResult = eval.outPath / "hello-mill"
        assert(eval.apply(BuildInfoScalaJS.fastLinkJS).isRight)
    }

    test("static") - UnitTester(BuildInfoStatic, testModuleSourcesPath / "scala").scoped { eval =>
      // When `buildInfoStaticCompiled = true`, make sure we always create the
      // buildinfo Scala file and never create the resource file
      val runResult = eval.outPath / "hello-mill"

      val Right(_) =
        eval.apply(BuildInfoStatic.run(Task.Anon(Args(runResult.toString))))

      assert(os.exists(buildInfoSourcePath(eval)))
      assert(!os.exists(buildInfoResourcePath(eval)))
      assert(os.exists(runResult))
      assert(os.read(runResult) == scalaVersionString)
    }

    test("java") - UnitTester(BuildInfoJava, testModuleSourcesPath / "java").scoped { eval =>
      val runResult = eval.outPath / "hello-mill"
      val Right(_) =
        eval.apply(BuildInfoJava.run(Task.Anon(Args(runResult.toString))))

      assert(
        os.exists(runResult),
        os.read(runResult) == "not-provided-for-java-modules"
      )
    }

    test("java-static") - UnitTester(BuildInfoJavaStatic, testModuleSourcesPath / "java").scoped {
      eval =>
        val runResult = eval.outPath / "hello-mill"
        val generatedSrc = eval.outPath / "buildInfoSources.dest/foo/BuildInfo.java"
        val Right(_) =
          eval.apply(BuildInfoJavaStatic.run(Task.Anon(Args(runResult.toString))))

        assert(
          os.exists(runResult),
          os.exists(generatedSrc),
          os.read(runResult) == "not-provided-for-java-modules"
        )
    }

    test("generatedSources must be a folder") - UnitTester(
      BuildInfoPlain,
      testModuleSourcesPath / "scala"
    ).scoped { eval =>
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
