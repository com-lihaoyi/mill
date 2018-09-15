package mill.twirllib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object HelloWorldTests extends TestSuite {

  trait HelloBase extends TestUtil.BaseModule {
    override def millSourcePath: Path = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloWorldModule extends mill.twirllib.TwirlModule {
    def twirlVersion = "1.0.0"
  }

  object HelloWorld extends HelloBase {

    object core extends HelloWorldModule {
      override def twirlVersion = "1.3.15"
    }
  }

  val resourcePath: Path = pwd / 'contrib / 'twirllib / 'test / 'resources / "hello-world"

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: Path = resourcePath)
                      (t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def compileClassfiles: Seq[RelPath] = Seq[RelPath](
    "hello.template.scala"
  )

  def tests: Tests = Tests {
    'twirlVersion - {

      'fromBuild - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.twirlVersion)

        assert(
          result == "1.3.15",
          evalCount > 0
        )
      }
    }
    'compileTwirl - workspaceTest(HelloWorld) { eval =>
      val Right((result, evalCount)) = eval.apply(HelloWorld.core.compileTwirl)

      val outputFiles = ls.rec(result.classes.path)
      val expectedClassfiles = compileClassfiles.map(
        eval.outPath / 'core / 'compileTwirl / 'dest / 'html / _
      )
      assert(
        result.classes.path == eval.outPath / 'core / 'compileTwirl / 'dest / 'html,
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 1,
        evalCount > 0
      )

      // don't recompile if nothing changed
      val Right((_, unchangedEvalCount)) = eval.apply(HelloWorld.core.compileTwirl)

      assert(unchangedEvalCount == 0)
    }
  }
}
