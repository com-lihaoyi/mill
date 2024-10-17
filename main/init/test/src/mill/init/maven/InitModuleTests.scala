package mill.init.maven

import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

object InitModuleTests extends TestSuite {

  def tests: Tests = Tests {

    val sourceRoot: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "maven"

    object initmodule extends TestBaseModule with InitModule

    test("single") {

      val eval = UnitTester(initmodule, sourceRoot / "single-module")

      eval(initmodule.init()).isRight ==> true

      os.walk(initmodule.millSourcePath).count(_.ext == "mill") ==> 1
      os.exists(initmodule.millSourcePath / "build.mill") ==> true

      val buildContents = os.read(initmodule.millSourcePath / "build.mill")

      buildContents.contains(
        s"""  override def pomSettings = PomSettings(
           |    s\"\"\"Sample single module Maven project with a working, deployable site.\"\"\".stripMargin,
           |    "",
           |    "http://www.example.com",
           |    Seq(),
           |    VersionControl(
           |      Option("http://github.com/gabrielf/maven-samples"),
           |      Option("scm:git:git@github.com:gabrielf/maven-samples.git"),
           |      Option("scm:git:git@github.com:gabrielf/maven-samples.git"),
           |      Option("HEAD")
           |    ),
           |    Seq()
           |  )
           |""".stripMargin
      ) ==> true

      buildContents.contains(
        s"""  override def publishVersion = "1.0-SNAPSHOT"
           |""".stripMargin
      ) ==> true

      buildContents.contains(
        s"""  trait Tests extends TestModule.Junit4 with MavenTests {
           |
           |    override def defaultCommandName() = "test"
           |
           |    override def ivyDeps = super.ivyDeps() ++ Agg(
           |      ivy"com.novocode:junit-interface:0.11"
           |    )
           |  }
           |""".stripMargin
      ) ==> true

      buildContents.contains(
        s"""  override def artifactName = "single-module-project"
           |""".stripMargin
      ) ==> true

      buildContents.contains(
        s"""  override def javacOptions = Seq(
           |    "-source",
           |    "1.6",
           |    "-target",
           |    "1.6"
           |  )
           |""".stripMargin
      ) ==> true

      buildContents.contains(
        s"""  override def compileIvyDeps = Agg(
           |    ivy"javax.servlet.jsp:jsp-api:2.2",
           |    ivy"javax.servlet:servlet-api:2.5"
           |  )
           |""".stripMargin
      ) ==> true

      buildContents.contains(
        s"""  object test extends Tests {
           |
           |    override def ivyDeps = super.ivyDeps() ++ Agg(
           |      ivy"junit:junit-dep:4.10",
           |      ivy"org.hamcrest:hamcrest-core:1.2.1",
           |      ivy"org.hamcrest:hamcrest-library:1.2.1",
           |      ivy"org.mockito:mockito-core:1.8.5"
           |    )
           |  }
           |""".stripMargin
      ) ==> true
    }

    test("multi") {

      val eval = UnitTester(initmodule, sourceRoot / "multi-module")

      eval(initmodule.init()).isRight ==> true

      os.walk(initmodule.millSourcePath).count(_.ext == "mill") ==> 3
      os.exists(initmodule.millSourcePath / "build.mill") ==> true
      os.exists(initmodule.millSourcePath / "server" / "package.mill") ==> true
      os.exists(initmodule.millSourcePath / "webapp" / "package.mill") ==> true

      val buildContents = os.read(initmodule.millSourcePath / "build.mill")
      val serverContents = os.read(initmodule.millSourcePath / "server" / "package.mill")
      val webappContents = os.read(initmodule.millSourcePath / "webapp" / "package.mill")

      buildContents.contains(
        s"""  override def pomPackagingType = PackagingType.Pom
           |""".stripMargin
      ) ==> true

      serverContents.contains(
        s"""    override def ivyDeps = super.ivyDeps() ++ Agg(
           |      ivy"junit:junit-dep:4.10",
           |      ivy"org.hamcrest:hamcrest-core:1.2.1",
           |      ivy"org.hamcrest:hamcrest-library:1.2.1",
           |      ivy"org.mockito:mockito-core:1.8.5"
           |    )
           |""".stripMargin
      ) ==> true

      webappContents.contains(
        s"""  override def moduleDeps = Seq(
           |    build.`server`
           |  )
           |""".stripMargin
      ) ==> true
    }
  }
}
