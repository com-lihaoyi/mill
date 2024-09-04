package mill.twirllib

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

trait HelloWorldTests extends TestSuite {
  val testTwirlVersion: String

  trait HelloWorldModule extends mill.twirllib.TwirlModule {
    def twirlVersion = testTwirlVersion
  }

  object HelloWorld extends TestBaseModule {

    object core extends HelloWorldModule {
      override def twirlImports = super.twirlImports() ++ testAdditionalImports
      override def twirlFormats = super.twirlFormats() ++ Map("svg" -> "play.twirl.api.HtmlFormat")
      override def twirlConstructorAnnotations: Seq[String] = testConstructorAnnotations
    }

  }

  object HelloWorldWithInclusiveDot extends TestBaseModule {

    object core extends HelloWorldModule {
      override def twirlInclusiveDot: Boolean = true
      override def twirlFormats = super.twirlFormats() ++ Map("svg" -> "play.twirl.api.HtmlFormat")
    }

  }

  def resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  def compileClassfiles: Seq[os.RelPath] = Seq[os.RelPath](
    os.rel / "html" / "hello.template.scala",
    os.rel / "html" / "wrapper.template.scala",
    os.rel / "svg" / "test.template.scala"
  )

  def expectedDefaultImports: Seq[String] = Seq(
    "import _root_.play.twirl.api.TwirlFeatureImports._",
    "import _root_.play.twirl.api.TwirlHelperImports._",
    "import _root_.play.twirl.api.Html",
    "import _root_.play.twirl.api.JavaScript",
    "import _root_.play.twirl.api.Txt",
    "import _root_.play.twirl.api.Xml"
  )

  def testAdditionalImports: Seq[String] = Seq(
    "mill.twirl.test.AdditionalImport1._",
    "mill.twirl.test.AdditionalImport2._"
  )

  def testConstructorAnnotations = Seq(
    "@org.springframework.stereotype.Component()",
    "@something.else.Thing()"
  )

  def skipUnsupportedVersions(test: => Unit) = testTwirlVersion match {
    case s"1.$minor.$_" if minor.toIntOption.exists(_ < 6) => test
    case _ if scala.util.Properties.isJavaAtLeast(11) => test
    case _ => System.err.println(s"Skipping since twirl $testTwirlVersion doesn't support Java 8")
  }

  def tests: Tests = Tests {
    test("twirlVersion") {

      test("fromBuild") {
        val eval = UnitTester(HelloWorld, resourcePath / "hello-world")
        val Right(result) =
          eval.apply(HelloWorld.core.twirlVersion)

        assert(
          result.value == testTwirlVersion,
          result.evalCount > 0
        )
      }
    }
    test("compileTwirl") {
      skipUnsupportedVersions {
        val eval = UnitTester(HelloWorld, resourcePath / "hello-world", debugEnabled = true)
        val res = eval.apply(HelloWorld.core.compileTwirl)
        assert(res.isRight)
        val Right(result) = res

        val outputFiles = os.walk(result.value.classes.path).filter(_.last.endsWith(".scala"))
        val expectedClassfiles = compileClassfiles.map(
          eval.outPath / "core" / "compileTwirl.dest" / _
        )

        assert(
          result.value.classes.path == eval.outPath / "core" / "compileTwirl.dest",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          outputFiles.size == 3,
          result.evalCount > 0,
          outputFiles.forall { p =>
            val lines = os.read.lines(p).map(_.trim)
            (expectedDefaultImports ++ testAdditionalImports.map(s => s"import $s")).forall(
              lines.contains
            )
          },
          outputFiles.filter(_.toString().contains("hello.template.scala")).forall { p =>
            val lines = os.read.lines(p).map(_.trim)
            val expectedClassDeclaration = s"class hello ${testConstructorAnnotations.mkString}"
            lines.exists(_.startsWith(expectedClassDeclaration))
          }
        )

        // don't recompile if nothing changed
        val Right(result2) =
          eval.apply(HelloWorld.core.compileTwirl)

        assert(result2.evalCount == 0)
      }
    }

    test("compileTwirlInclusiveDot") {
      skipUnsupportedVersions {
        val eval = UnitTester(
          HelloWorldWithInclusiveDot,
          sourceRoot = resourcePath / "hello-world-inclusive-dot"
        )

        val Right(result) = eval.apply(HelloWorldWithInclusiveDot.core.compileTwirl)

        val outputFiles = os.walk(result.value.classes.path).filter(_.last.endsWith(".scala"))
        val expectedClassfiles = compileClassfiles.map(name =>
          eval.outPath / "core" / "compileTwirl.dest" / name / os.RelPath.up / name.last.replace(
            ".template.scala",
            "$$TwirlInclusiveDot.template.scala"
          )
        )

        println(s"outputFiles: $outputFiles")

        assert(
          result.value.classes.path == eval.outPath / "core" / "compileTwirl.dest",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          outputFiles.size == 3,
          result.evalCount > 0,
          outputFiles.filter(_.toString().contains("hello.template.scala")).forall { p =>
            val lines = os.read.lines(p).map(_.trim)
            lines.exists(_.contains("$$TwirlInclusiveDot"))
          }
        )

        // don't recompile if nothing changed
        val Right(result2) =
          eval.apply(HelloWorld.core.compileTwirl)

        assert(result2.evalCount == 0)
      }
    }
  }
}

object HelloWorldTests1_3 extends HelloWorldTests {
  override val testTwirlVersion = "1.3.16"
}
object HelloWorldTests1_5 extends HelloWorldTests {
  override val testTwirlVersion = "1.5.2"
}
object HelloWorldTests1_6 extends HelloWorldTests {
  override val testTwirlVersion = "1.6.2"
}
object HelloWorldTests2_0 extends HelloWorldTests {
  override val testTwirlVersion = "2.0.1"
}
