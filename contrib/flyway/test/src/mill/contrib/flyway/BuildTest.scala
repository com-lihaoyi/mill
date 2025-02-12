package mill.contrib.flyway

import mill.*
import mill.define.Discover
import mill.scalalib.*
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.{TestSuite, Tests, assert, *}

object BuildTest extends TestSuite {

  object Build extends TestBaseModule {
    object build extends FlywayModule {

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      override def resources = Task.Sources(resourceFolder)

      def h2 = ivy"com.h2database:h2:2.1.214"

      def flywayUrl = "jdbc:h2:mem:test_db;DB_CLOSE_DELAY=-1"
      def flywayDriverDeps = Seq(h2)
    }

    val millDiscover: Discover = Discover[this.type]
  }

  def tests = Tests {
    test("clean") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.build.flywayClean()): @unchecked
      assert(result.evalCount > 0)
    }

    test("migrate") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.build.flywayMigrate()): @unchecked
      assert(
        result.evalCount > 0,
        result.value.migrationsExecuted == 1
      )
      val Right(resultAgain) = eval(Build.build.flywayMigrate()): @unchecked
      assert(
        resultAgain.evalCount > 0,
        resultAgain.value.migrationsExecuted == 0
      )
    }

    test("info") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.build.flywayInfo()): @unchecked
      assert(result.evalCount > 0)
    }
  }
}
