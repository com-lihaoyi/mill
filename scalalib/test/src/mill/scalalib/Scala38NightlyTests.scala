package mill.scalalib

import mill.*
import mill.define.{Discover, ModuleRef}
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

// TODO: Once Scala 3.8.0 is out, we can change this test to use 3.8.0 and remove the extra repo
object Scala38NightlyTests extends TestSuite {

  val repo =
    coursier.maven.MavenRepository("https://repo.scala-lang.org/artifactory/maven-nightlies")

  object Scala38Nightly extends TestBaseModule {
    object JvmWorker extends JvmWorkerModule {
      override def repositoriesTask = Task.Anon {
        super.repositoriesTask() ++ Seq(repo)
      }
    }
    object foo extends ScalaModule {
      override def zincWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorker)
      override def repositoriesTask = Task.Anon {
        super.repositoriesTask() ++ Seq(repo)
      }
      override def scalaVersion = "3.8.0-RC1-bin-20250825-ee2f641-NIGHTLY"
      override def ivyDeps = Agg(
        ivy"org.scala-lang.modules::scala-xml:2.4.0"
      )
    }

    override lazy val millDiscover = Discover[this.type]
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
