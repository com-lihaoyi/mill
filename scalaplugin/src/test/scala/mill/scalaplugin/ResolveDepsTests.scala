package mill.scalaplugin

import ammonite.ops._
import ammonite.ops.Path
import mill.define.{Target, Task}
import mill.discover.Mirror.LabelledTarget
import mill.discover.Discovered
import mill.eval.Result
import utest._

case class TestModule(deps: Dep*)(implicit path: utest.framework.TestPath) extends ScalaModule {
  def scalaVersion = "2.12.4"
  def basePath: Path = ResolveDepsTests.workspaceRoot / "resolve-deps"
  override def ivyDeps: Target[Seq[Dep]] = deps.toSeq
}

object ResolveDepsTests extends TestSuite {
  val workspaceRoot: Path = pwd / 'target / 'workspace
  def outputPath()(implicit path: utest.framework.TestPath): Path = workspaceRoot / path.value.last / 'out

  def eval[T](t: Task[T], mapping: Map[Target[_], LabelledTarget[_]])(implicit path: utest.framework.TestPath) =
    TestEvaluator.eval(mapping, outputPath)(t)

  val tests = Tests {
    'resolveValidDeps - {
      val module = TestModule(Dep("com.lihaoyi", "pprint", "0.5.3"))
      val testWithValidDeps = Discovered.mapping(module)
      val Right((result, evalCount)) =
        eval(module.compile, testWithValidDeps)
      assert(evalCount > 0)
    }

    'errOnInvalidOrgDeps - {
      val module = TestModule(Dep("xxx.yyy.invalid", "pprint", "0.5.3"))
      val mapping = Discovered.mapping(module)
      val Left(err) =
        eval(module.compile, mapping)
      assert(err.isInstanceOf[mill.eval.Result.Failing])
    }

    'errOnInvalidVersionDeps - {
      val module = TestModule(Dep("com.lihaoyi", "pprint", "invalid.version.num"))
      val mapping = Discovered.mapping(module)
      val Left(err) =
        eval(module.compile, mapping)
      assert(err.isInstanceOf[mill.eval.Result.Failing])
    }

    'errOnPartialSuccess - {
      val module = TestModule(Dep("com.lihaoyi", "pprint", "0.5.3"), Dep("fake", "fake", "fake"))
      val mapping = Discovered.mapping(module)
      val Left(err) =
        eval(module.compile, mapping)
      assert(err.isInstanceOf[mill.eval.Result.Failing])
    }
  }
}
