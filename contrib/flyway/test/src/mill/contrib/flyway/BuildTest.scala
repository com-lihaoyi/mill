package mill.contrib.flyway

import mill.*
import mill.api.Discover
import mill.javalib.*
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.{TestSuite, Tests, assert, *}
import scala.annotation.nowarn

object BuildTest extends TestSuite {
  private val runDockerTests = sys.env.get("MILL_FLYWAY_DOCKER_IT").contains("1")

  object Build extends TestRootModule {
    val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val mysqlResourceFolder = resourceFolder / "mysql"
    val flywayVersion = Option(
      classOf[org.flywaydb.core.api.output.MigrateResult].getPackage.getImplementationVersion
    )
      .orElse {
        Option(classOf[org.flywaydb.core.api.output.MigrateResult].getProtectionDomain)
          .flatMap(pd => Option(pd.getCodeSource))
          .map(_.getLocation.getPath)
          .flatMap(path =>
            """.*flyway-core-([A-Za-z0-9+_.-]+)\.jar$""".r.findFirstMatchIn(path).map(_.group(1))
          )
      }
      .getOrElse("11.8.2")
    val flywayPostgresPlugin = mvn"org.flywaydb:flyway-database-postgresql:${flywayVersion}"
    val flywayMysqlPlugin = mvn"org.flywaydb:flyway-mysql:${flywayVersion}"
    def pg = mvn"org.postgresql:postgresql:42.7.7"
    def mysql = mvn"com.mysql:mysql-connector-j:8.4.0"

    object build extends FlywayModule {
      override def resources = Task.Sources(resourceFolder)

      def h2 = mvn"com.h2database:h2:2.1.214"

      def flywayUrl = "jdbc:h2:mem:test_db;DB_CLOSE_DELAY=-1"
      def flywayDriverDeps = Seq(h2)
    }

    object pgBuild extends FlywayModule {
      override def resources = Task.Sources(resourceFolder)
      def flywayUrl = "jdbc:postgresql://127.0.0.1:1/flywaydb"
      def flywayDriverDeps = Seq(pg)
      def flywayPluginDeps = Seq(flywayPostgresPlugin)
    }

    object mysqlBuild extends FlywayModule {
      override def resources = Task.Sources(resourceFolder)
      def flywayUrl = "jdbc:mysql://127.0.0.1:1/flywaydb"
      def flywayDriverDeps = Seq(mysql)
      def flywayPluginDeps = Seq(flywayMysqlPlugin)
    }

    object dockerPgBuild extends FlywayModule {
      override def resources = Task.Sources(resourceFolder)
      def flywayUrl = "jdbc:postgresql://127.0.0.1:55433/flywaydb"
      def flywayUser = "postgres"
      def flywayPassword = "postgres"
      def flywayDriverDeps = Seq(pg)
      def flywayPluginDeps = Seq(flywayPostgresPlugin)
    }

    object dockerMysqlBuild extends FlywayModule {
      override def resources = Task.Sources(mysqlResourceFolder)
      def flywayUrl = "jdbc:mysql://127.0.0.1:53306/flywaydb"
      def flywayUser = "flyway"
      def flywayPassword = "flyway"
      def flywayDriverDeps = Seq(mysql)
      def flywayPluginDeps = Seq(flywayMysqlPlugin)
    }

    val millDiscover: Discover = Discover[this.type]
  }

  @nowarn("msg=unused pattern variable")
  def tests = Tests {
    test("clean") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.build.flywayClean()).runtimeChecked
      assert(result.evalCount > 0)
    }

    test("migrate") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.build.flywayMigrate()).runtimeChecked
      assert(
        result.evalCount > 0,
        result.value.migrationsExecuted == 1
      )
      val Right(resultAgain) = eval(Build.build.flywayMigrate()).runtimeChecked
      assert(
        resultAgain.evalCount > 0,
        resultAgain.value.migrationsExecuted == 0
      )
    }

    test("info") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.build.flywayInfo()).runtimeChecked
      assert(result.evalCount > 0)
    }

    test("postgres_database_plugin") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.pgBuild.flywayDetectedDatabaseType()).runtimeChecked
      val dbType = result.value.toLowerCase
      assert(
        result.evalCount > 0,
        dbType.contains("postgres") || dbType.contains("cockroach")
      )
    }

    test("mysql_database_plugin") - UnitTester(Build, null).scoped { eval =>
      val Right(result) = eval(Build.mysqlBuild.flywayDetectedDatabaseType()).runtimeChecked
      assert(result.evalCount > 0, result.value.toLowerCase.contains("mysql"))
    }

    test("docker_smoke_postgres_mysql") - {
      if (!runDockerTests) ()
      else UnitTester(Build, null).scoped { eval =>
        val Right(pgClean) = eval(Build.dockerPgBuild.flywayClean()).runtimeChecked
        val Right(pgMigrate) = eval(Build.dockerPgBuild.flywayMigrate()).runtimeChecked
        val Right(mysqlClean) = eval(Build.dockerMysqlBuild.flywayClean()).runtimeChecked
        val Right(mysqlMigrate) = eval(Build.dockerMysqlBuild.flywayMigrate()).runtimeChecked
        assert(
          pgClean.evalCount > 0,
          pgMigrate.value.migrationsExecuted == 1,
          mysqlClean.evalCount > 0,
          mysqlMigrate.value.migrationsExecuted == 1
        )
      }
    }
  }
}
