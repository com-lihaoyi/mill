package mill.scalalib

import mill.*
import mill.api.{Discover, ModuleRef}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

// TODO: Once Scala 3.8.0 is out, we can change this test to use 3.8.0 and remove the extra repo
object Scala38NightlyTests extends TestSuite {

  object Scala38Nightly extends TestRootModule {
    object JvmWorker extends JvmWorkerModule {
      override def repositories =
        super.repositories() ++ Seq(CoursierModule.KnownRepositories.ScalaLangNightlies)
    }
    object foo extends ScalaModule {
      override def jvmWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorker)
      override def repositories =
        super.repositories() ++ Seq(CoursierModule.KnownRepositories.ScalaLangNightlies)
      override def scalaVersion = "3.8.0-RC1-bin-20250825-ee2f641-NIGHTLY"
      override def mvnDeps = Seq(
        mvn"org.scala-lang.modules::scala-xml:2.4.0"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("scala38nightly") - UnitTester(
      Scala38Nightly,
      sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "dotty213"
    ).scoped { eval =>
      val Right(result) = eval.apply(Scala38Nightly.foo.run()): @unchecked
      assert(result.evalCount > 0)
    }

  }
}
