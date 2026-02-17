package mill.contrib.flyway

import mill.contrib.flyway.ConsoleLog.Level
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.logging.LogFactory
import org.flywaydb.core.internal.configuration.ConfigUtils as flyway
import org.flywaydb.core.internal.info.MigrationInfoDumper

import scala.jdk.CollectionConverters.*
import mill.*
import mill.api.PathRef
import mill.api.daemon.MillURLClassLoader
import mill.javalib.{Dep, JavaModule}
import org.flywaydb.core.api.output.{BaselineResult, CleanResult, MigrateOutput, MigrateResult}

import scala.annotation.nowarn

trait FlywayModule extends JavaModule {
  import FlywayModule.*

  def flywayUrl: T[String]
  def flywayUser: T[String] = Task { "" }
  def flywayPassword: T[String] = Task { "" }
  def flywayFileLocations: T[Seq[PathRef]] = Task {
    resources().map(pr => PathRef(pr.path / "db/migration", pr.quick))
  }
  def flywayPlaceholders: T[Map[String, String]] = Task {
    Map.empty[String, String]
  }

  def flywayDriverDeps: T[Seq[Dep]]

  def jdbcClasspath = Task {
    defaultResolver().classpath(flywayDriverDeps())
  }

  private def strToOptPair[A](key: String, v: String) =
    Option(v)
      .filter(_.nonEmpty)
      .map(key -> _)

  def flywayClassloader: Worker[MillURLClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(jdbcClasspath().map(_.path))
  }

  @nowarn("msg=.*Workers should implement AutoCloseable.*")
  def flywayInstance: Worker[Flyway] = Task.Worker {
    val jdbcClassloader = flywayClassloader()

    val configProps = Map(flyway.URL -> flywayUrl()) ++
      strToOptPair(flyway.USER, flywayUser()) ++
      strToOptPair(flyway.PASSWORD, flywayPassword())

    LogFactory.setLogCreator(new ConsoleLogCreator(Level.INFO))

    Flyway
      .configure(jdbcClassloader)
      .locations(flywayFileLocations().map("filesystem:" + _.path)*)
      .placeholders(flywayPlaceholders().asJava)
      .configuration(configProps.asJava)
      .cleanDisabled(false)
      .load
  }

  def flywayMigrate(): Command[MigrateResult] = Task.Command {
    flywayInstance().migrate()
  }

  def flywayClean(): Command[CleanResult] = Task.Command {
    flywayInstance().clean()
  }

  def flywayBaseline(): Command[BaselineResult] = Task.Command {
    flywayInstance().baseline()
  }

  def flywayInfo(): Command[String] = Task.Command {
    val info = flywayInstance().info
    val current = info.current
    val currentSchemaVersion =
      if (current == null) MigrationVersion.EMPTY
      else current.getVersion
    val out =
      s"""Schema version: ${currentSchemaVersion}
         |${MigrationInfoDumper.dumpToAsciiTable(info.all)}""".stripMargin
    Task.log.streams.out.println(out)
    out
  }
}

object FlywayModule {

  implicit class JsonSupport(val string: String) extends AnyVal {
    def jsonify: ujson.Value = string match {
      case null => ujson.Null
      case x => ujson.Str(x)
    }
  }

  implicit def migrateOutputWriter: upickle.Writer[MigrateOutput] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "category" -> r.category.jsonify,
        "version" -> r.version.jsonify,
        "type" -> r.`type`.jsonify,
        "filepath" -> r.filepath.jsonify,
        "executionTime" -> ujson.Num(r.executionTime)
      )
    )

  implicit def cleanResultWriter: upickle.Writer[CleanResult] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.operation.jsonify,
        "warnings" -> ujson.Arr.from(r.warnings.asScala.toSeq.map(_.jsonify)),
        "schemasCleaned" -> ujson.Arr.from(r.schemasCleaned.asScala.toSeq.map(_.jsonify)),
        "schemasDropped" -> ujson.Arr.from(r.schemasDropped.asScala.toSeq.map(_.jsonify))
      )
    )
  implicit def migrateResultWriter: upickle.Writer[MigrateResult] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.getOperation.jsonify,
        "warnings" -> r.warnings.asScala.toSeq.map(_.jsonify),
        "initialSchemaVersion" -> r.initialSchemaVersion.jsonify,
        "targetSchemaVersion" -> r.targetSchemaVersion.jsonify,
        "schemaName" -> r.schemaName.jsonify,
        "migrations" -> ujson.Arr.from(r.migrations.asScala.toSeq.map(upickle.write(_))),
        "migrationsExecuted" -> ujson.Num(r.migrationsExecuted)
      )
    )
  implicit def baselineResultWriter: upickle.Writer[BaselineResult] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.operation.jsonify,
        "warnings" -> ujson.Arr.from(r.warnings.asScala.toSeq.map(_.jsonify)),
        "successfullyBaselined" -> ujson.Bool(r.successfullyBaselined),
        "baselineVersion" -> r.baselineVersion.jsonify
      )
    )
}
