package mill.exec

import mill.testkit.UnitTester
import mill.testkit.UnitTester.Result
import mill.testkit.TestBaseModule
import mill.Task
import mill.define.Discover

import utest._

object TestExternalModule extends mill.define.ExternalModule with mill.define.TaskModule {
  def defaultCommandName() = "x"
  def x = Task { 13 }
  trait Trait extends mill.Module {
    def overridden = Task { 19 }
  }
  object inner extends Trait {
    def y = Task { 17 }
    override def overridden = Task { super.overridden() + 1 }
  }
  def myCommand(i: Int) = Task.Command { i + 1 }
  lazy val millDiscover = Discover[this.type]
}

object ModuleTests extends TestSuite {
  object Build extends TestBaseModule {
    def z = Task { TestExternalModule.x() + TestExternalModule.inner.y() }
    lazy val millDiscover = Discover[this.type]
  }
  val tests = Tests {
    test("externalModuleCalls") {
      val check = UnitTester(Build, null)
      val result = check.apply("mill.exec.TestExternalModule/x")
      assert(result == Right(Result(Vector(13), 0)))

      val result2 = check.apply("mill.exec.TestExternalModule/")
      assert(result2 == Right(Result(Vector(13), 0)))

      val result3 = check.apply("mill.exec.TestExternalModule/myCommand", "-i", "10")
      assert(result3 == Right(Result(Vector(11), 1)))

      val result4 = check.apply("mill.exec.TestExternalModule/inner.overridden")
      assert(result4 == Right(Result(Vector(20), 0)))
    }
    test("externalModuleTargetsAreNamespacedByModulePackagePath") {
      val check = UnitTester(Build, null)
      os.remove.all(check.outPath)
      val zresult = check.apply(Build.z)
      assert(
        zresult == Right(Result(30, 1)),
        os.read(check.evaluator.outPath / "z.json").contains("30"),
        os.read(
          check.outPath / "mill/exec/TestExternalModule/x.json"
        ).contains("13"),
        os.read(
          check.outPath / "mill/exec/TestExternalModule/inner/y.json"
        ).contains("17")
      )
    }
    test("externalModuleMustBeGlobalStatic") {

      object Build extends mill.define.ExternalModule {

        def z = Task { TestExternalModule.x() + TestExternalModule.inner.y() }
        lazy val millDiscover = Discover[this.type]
      }

      intercept[java.lang.AssertionError] { Build }
    }
  }
}
