package mill.contrib.flyway

import java.net.URLClassLoader

import mill.contrib.flyway.ConsoleLog.Level
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.logging.LogFactory
import org.flywaydb.core.internal.configuration.{ConfigUtils => flyway}
import org.flywaydb.core.internal.info.MigrationInfoDumper
import scala.jdk.CollectionConverters._

import mill.{Agg, T}
import mill.api.PathRef
import mill.define.Command
import mill.scalalib.{Dep, JavaModule}
import org.flywaydb.core.api.output.{BaselineResult, CleanResult, MigrateOutput, MigrateResult}

trait FlywayModule extends JavaModule {
  import FlywayModule._

  def flywayUrl: T[String]
  def flywayUser: T[String] = T("")
  def flywayPassword: T[String] = T("")
  def flywayFileLocations: T[Seq[PathRef]] = T {
    resources().map(pr => PathRef(pr.path / "db" / "migration", pr.quick))
  }

  def flywayDriverDeps: T[Agg[Dep]]

  def jdbcClasspath = T {
    resolveDeps(flywayDriverDeps)()
  }

  private def strToOptPair[A](key: String, v: String) =
    Option(v)
      .filter(_.nonEmpty)
      .map(key -> _)

  def flywayInstance = T.worker {
    val jdbcClassloader = new URLClassLoader(jdbcClasspath().map(_.path.toIO.toURI.toURL).toArray)

    val configProps = Map(flyway.URL -> flywayUrl()) ++
      strToOptPair(flyway.USER, flywayUser()) ++
      strToOptPair(flyway.PASSWORD, flywayPassword())

    LogFactory.setLogCreator(new ConsoleLogCreator(Level.INFO))

    Flyway
      .configure(jdbcClassloader)
      .locations(flywayFileLocations().map("filesystem:" + _.path): _*)
      .configuration(configProps.asJava)
      .load
  }

  def flywayMigrate(): Command[MigrateResult] = T.command {
    flywayInstance().migrate()
  }

  def flywayClean(): Command[CleanResult] = T.command {
    flywayInstance().clean()
  }

  def flywayBaseline(): Command[BaselineResult] = T.command {
    flywayInstance().baseline()
  }

  def flywayInfo(): Command[String] = T.command {
    val info = flywayInstance().info
    val current = info.current
    val currentSchemaVersion =
      if (current == null) MigrationVersion.EMPTY
      else current.getVersion
    val out =
      s"""Schema version: ${currentSchemaVersion}
         |${MigrationInfoDumper.dumpToAsciiTable(info.all)}""".stripMargin
    T.log.outputStream.println(out)
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

  implicit def migrateOutputWriter: upickle.default.Writer[MigrateOutput] =
    upickle.default.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "category" -> r.category.jsonify,
        "version" -> r.version.jsonify,
        "type" -> r.`type`.jsonify,
        "filepath" -> r.filepath.jsonify,
        "executionTime" -> ujson.Num(r.executionTime)
      )
    )

  implicit def cleanResultWriter: upickle.default.Writer[CleanResult] =
    upickle.default.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.operation.jsonify,
        "warnings" -> ujson.Arr.from(r.warnings.asScala.toSeq.map(_.jsonify)),
        "schemasCleaned" -> ujson.Arr.from(r.schemasCleaned.asScala.toSeq.map(_.jsonify)),
        "schemasDropped" -> ujson.Arr.from(r.schemasDropped.asScala.toSeq.map(_.jsonify))
      )
    )
  implicit def migrateResultWriter: upickle.default.Writer[MigrateResult] =
    upickle.default.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.operation.jsonify,
        "warnings" -> r.warnings.asScala.toSeq.map(_.jsonify),
        "initialSchemaVersion" -> r.initialSchemaVersion.jsonify,
        "targetSchemaVersion" -> r.targetSchemaVersion.jsonify,
        "schemaName" -> r.schemaName.jsonify,
        "migrations" -> ujson.Arr.from(r.migrations.asScala.toSeq.map(upickle.default.write(_))),
        "migrationsExecuted" -> ujson.Num(r.migrationsExecuted)
      )
    )
  implicit def baselineResultWriter: upickle.default.Writer[BaselineResult] =
    upickle.default.writer[ujson.Obj].comap(r =>
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
