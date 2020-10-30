package mill.contrib.proguard

import mill._
import mill.define.Sources
import mill.define.Target
import mill.scalalib.ScalaModule
import mill.util.TestEvaluator
import mill.util.TestUtil
import os.Path
import utest._
import utest.framework.TestPath

object ProguardTests extends TestSuite {

  object Proguard
    extends TestUtil.BaseModule
      with scalalib.ScalaModule
      with Proguard {
    // override build root to test custom builds/modules
    override def millSourcePath: Path = TestUtil.getSrcPathStatic()
    override def scalaVersion = "2.12.0"
  }

  val testModuleSourcesPath: Path = os.pwd / 'contrib / 'proguard / 'test / 'resources / "proguard"

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)(
    implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(testModuleSourcesPath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    'proguard - {
      "should download proguard jars" - workspaceTest(Proguard) { eval =>
        val Right((agg, _)) = eval.apply(Proguard.proguardClasspath)
        assert(!agg.isEmpty)
      }

      "create proguarded jar" - workspaceTest(Proguard) { eval =>
        val Right((path, _)) = eval.apply(Proguard.proguard)
        assert(os.exists(path.path))
      }

    }
  }
}
