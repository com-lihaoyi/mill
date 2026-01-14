package mill.javalib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

// Tests that JavaModules with the same jvmId reuse the same zinc worker process
object JvmWorkerReuseTests extends TestSuite {

  object JvmWorkerReuse extends TestRootModule {
    object foo extends JavaModule {
      override def jvmId = "18"
    }
    object bar extends JavaModule {
      override def jvmId = "18"
    }
    object qux extends JavaModule {
      override def jvmId = "19"
    }
    object baz extends JavaModule {
      override def jvmId = "19"
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "jvm-worker-reuse"

  def tests: Tests = Tests {
    test("workerReuse") - UnitTester(JvmWorkerReuse, sourceRoot = resourcePath).scoped { eval =>
      def getProcessIds(): Seq[String] = {
        val zincWorkerPath =
          eval.outPath / "mill.javalib.JvmWorkerModule/internalWorker.dest/zinc-worker"
        if (!os.exists(zincWorkerPath)) Seq.empty
        else {
          os.walk(zincWorkerPath)
            .filter(_.last == "server.log")
            .flatMap(os.read.lines(_))
            .collect { case s"pid:$num $_" => num }
            .distinct
        }
      }

      val Right(_) = eval.apply(JvmWorkerReuse.foo.compile): @unchecked
      assert(getProcessIds().size == 1)

      val Right(_) = eval.apply(JvmWorkerReuse.bar.compile): @unchecked
      // bar has same jvmId as foo, ZincWorkerMain should be reused
      assert(getProcessIds().size == 1)

      val Right(_) = eval.apply(JvmWorkerReuse.qux.compile): @unchecked
      // qux has different jvmId as bar, ZincWorkerMain should be re-created
      assert(getProcessIds().size == 2)

      val Right(_) = eval.apply(JvmWorkerReuse.baz.compile): @unchecked
      // baz has same jvmId as qux, ZincWorkerMain should be reused
      assert(getProcessIds().size == 2)
    }
  }
}
