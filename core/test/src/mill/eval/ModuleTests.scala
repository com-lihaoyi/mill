package mill.eval

import ammonite.ops._
import mill.util.{TestEvaluator, TestUtil}
import mill.T
import mill.util.TestEvaluator.implicitDisover
import utest._


object ModuleTests extends TestSuite{
  object ExternalModule extends mill.define.ExternalModule {
    def x = T{13}
    object inner extends mill.Module{
      def y = T{17}
    }
  }
  object Build extends TestUtil.BaseModule{
    def z = T{ ExternalModule.x() + ExternalModule.inner.y() }
  }
  val tests = Tests {
    'externalModuleTargetsAreNamespacedByModulePackagePath - {
      val check = new TestEvaluator(
        Build,
        pwd / 'target / 'workspace / "module-tests" / "externalModule",
        pwd
      )

      val Right((30, 1)) = check.apply(Build.z)
      val base = check.evaluator.workspacePath
      assert(
        read(base / 'z / "meta.json").contains("30"),
        read(base / 'mill / 'eval / 'ModuleTests / 'ExternalModule / 'x / "meta.json").contains("13"),
        read(base / 'mill / 'eval / 'ModuleTests / 'ExternalModule / 'inner / 'y / "meta.json").contains("17")
      )
    }
    'externalModuleMustBeGlobalStatic - {


      object Build extends mill.define.ExternalModule {

        def z = T{ ExternalModule.x() + ExternalModule.inner.y() }
      }

      intercept[java.lang.AssertionError]{ Build }
    }
  }
}
