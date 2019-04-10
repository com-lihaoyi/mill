package mill.contrib.flyway

import mill._
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest.{TestSuite, Tests, assert, _}

object BuildTest extends TestSuite {
  object Build extends TestUtil.BaseModule {
    object build extends FlywayModule {

      def resources = T.sources(os.pwd / 'contrib / 'flyway / 'test / 'resources)

      def postgres = ivy"org.postgresql:postgresql:42.2.5"

      def flywayUrl = "jdbc:postgresql:test_db"
      def flywayUser = "postgres"
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
    }

    'migrateAgain - {
      val eval = new TestEvaluator(Build)
      val Right((res, count)) = eval(Build.build.flywayMigrate())
      assert(
        count > 0,
        res == 0
      )
    }

    'info - {
      val eval = new TestEvaluator(Build)
      val Right((_, count)) = eval(Build.build.flywayInfo())
      assert(count > 0)
    }
  }
}