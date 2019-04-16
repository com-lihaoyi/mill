package mill.contrib.flyway

import mill._
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest.{TestSuite, Tests, assert, _}

object BuildTest extends TestSuite {
  object Build extends TestUtil.BaseModule {
    object build extends FlywayModule {

      def resources = T.sources(os.pwd / 'contrib / 'flyway / 'test / 'resources)

      def postgres = ivy"com.h2database:h2:1.4.199"

      def flywayUrl = "jdbc:h2:mem:test_db;DB_CLOSE_DELAY=-1"
      def flywayDriverDeps = Agg(postgres)
    }
  }

  def tests = Tests {
    'clean - {
      val eval = new TestEvaluator(Build)
      val Right((_, count)) = eval(Build.build.flywayClean())
      assert(count > 0)
    }

    'migrate - {
      val eval = new TestEvaluator(Build)
      val Right((res, count)) = eval(Build.build.flywayMigrate())
      assert(
        count > 0,
        res == 1
      )
      val Right((resAgain, countAgain)) = eval(Build.build.flywayMigrate())
      assert(
        countAgain > 0,
        resAgain == 0
      )
    }

    'info - {
      val eval = new TestEvaluator(Build)
      val Right((_, count)) = eval(Build.build.flywayInfo())
      assert(count > 0)
    }
  }
}