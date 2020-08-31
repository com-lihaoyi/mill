package mill.twirllib

import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

import scala.io.Codec

object HelloWorldTests extends TestSuite {

  trait HelloBase extends TestUtil.BaseModule {
    override def millSourcePath: os.Path = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloWorldModule extends mill.twirllib.TwirlModule {

    def twirlVersion = "1.3.15"

  }

  object HelloWorld extends HelloBase {

    object core extends HelloWorldModule {
      override def twirlImports = super.twirlImports() ++ testAdditionalImports
      override def twirlFormats = super.twirlFormats() ++ Map("svg" -> "play.twirl.api.HtmlFormat")
      override def twirlConstructorAnnotations: Seq[String] = testConstructorAnnotations
    }

  }

  object HelloWorldWithInclusiveDot extends HelloBase {

    object core extends HelloWorldModule {
      override def twirlInclusiveDot: Boolean = true
      override def twirlFormats = super.twirlFormats() ++ Map("svg" -> "play.twirl.api.HtmlFormat")
    }

  }

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePathSuffix: String
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(
      os.pwd / 'contrib / 'twirllib / 'test / 'resources / resourcePathSuffix,
      m.millSourcePath
    )
    t(eval)
  }

  def compileClassfiles: Seq[os.RelPath] = Seq[os.RelPath](
    os.rel / 'html / "hello.template.scala",
    os.rel / 'html / "wrapper.template.scala",
    os.rel / 'svg / "test.template.scala"
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

  def tests: Tests = Tests {
    'twirlVersion - {

      'fromBuild - workspaceTest(HelloWorld, "hello-world") { eval =>
        val Right((result, evalCount)) =
          eval.apply(HelloWorld.core.twirlVersion)

        assert(
          result == "1.3.15",
          evalCount > 0
        )
      }
    }
    'compileTwirl - workspaceTest(HelloWorld, "hello-world") { eval =>
      val Right((result, evalCount)) = eval.apply(HelloWorld.core.compileTwirl)

      val outputFiles = os.walk(result.classes.path).filter(_.last.endsWith(".scala"))
      val expectedClassfiles = compileClassfiles.map(
        eval.outPath / 'core / 'compileTwirl / 'dest / _
      )

      assert(
        result.classes.path == eval.outPath / 'core / 'compileTwirl / 'dest,
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 3,
        evalCount > 0,
        outputFiles.forall { p =>
          val lines = os.read.lines(p).map(_.trim)
          (expectedDefaultImports ++ testAdditionalImports.map(s => s"import $s")).forall(lines.contains)
        },
        outputFiles.filter(_.toString().contains("hello.template.scala")).forall { p =>
          val lines = os.read.lines(p).map(_.trim)
          val expectedClassDeclaration = s"class hello ${testConstructorAnnotations.mkString}"
          lines.exists(_.startsWith(expectedClassDeclaration))
        },

      )

      // don't recompile if nothing changed
      val Right((_, unchangedEvalCount)) =
        eval.apply(HelloWorld.core.compileTwirl)

      assert(unchangedEvalCount == 0)
    }
    'compileTwirlInclusiveDot - workspaceTest(HelloWorldWithInclusiveDot, "hello-world-inclusive-dot") { eval =>
      val Right((result, evalCount)) = eval.apply(HelloWorldWithInclusiveDot.core.compileTwirl)

      val outputFiles = os.walk(result.classes.path).filter(_.last.endsWith(".scala"))
      val expectedClassfiles = compileClassfiles.map( name =>
        eval.outPath / 'core / 'compileTwirl / 'dest / name / os.RelPath.up / name.last.replace(".template.scala", "$$TwirlInclusiveDot.template.scala")
      )

      println(s"outputFiles: $outputFiles")

      assert(
        result.classes.path == eval.outPath / 'core / 'compileTwirl / 'dest,
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 3,
        evalCount > 0,
        outputFiles.filter(_.toString().contains("hello.template.scala")).forall { p =>
          val lines = os.read.lines(p).map(_.trim)
          lines.exists(_.contains("$$TwirlInclusiveDot"))
        },

      )

      // don't recompile if nothing changed
      val Right((_, unchangedEvalCount)) =
        eval.apply(HelloWorld.core.compileTwirl)

      assert(unchangedEvalCount == 0)
    }
  }
}
