package mill
package contrib.flyway

import java.net.URLClassLoader

import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.logging.LogFactory
import org.flywaydb.core.internal.configuration.{ConfigUtils => flyway}
import org.flywaydb.core.internal.info.MigrationInfoDumper
import org.flywaydb.core.internal.logging.console.ConsoleLog.Level
import org.flywaydb.core.internal.logging.console.ConsoleLogCreator
import upickle.default.writer

import scala.collection.JavaConverters._


trait FlywayModule extends JavaModule {

  def flywayUrl: T[String]
  def flywayUser: T[String] = T("")
  def flywayPassword: T[String] = T("")
  //def flywayClassLocationDeps: T[Agg[Dep]] = Agg.empty[Dep]
  //def flywayClassLocations: T[Seq[String]] = T(Nil)
  def flywayFileLocations: T[Seq[PathRef]] = T(resources().map(pr => PathRef(pr.path / "db" / "migration", pr.quick)))
  def flywayDriverDeps: T[Agg[Dep]]
  def jdbcClasspath = T ( resolveDependencies(
    repositories,
    Lib.depToDependencyJava(_),
    flywayDriverDeps()
  ))

  private def strToOptPair[A](key: String, v: String) =
    Option(v)
      .filter {
        case a: String => a.nonEmpty
        case _ => true
      }
      .map(key -> _)

  private implicit val flywayWriter = writer[String].comap[Flyway](_.toString)
  def flywayInstance = T.command {
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

  def flywayMigrate() = T.command(flywayInstance().migrate())
  def flywayClean() = T.command(flywayInstance().clean())
  def flywayBaseline() = T.command(flywayInstance().baseline())
  def flywayInfo() = T.command {
    val log = T.ctx().log
    val info = flywayInstance().info
    val current = info.current
    val currentSchemaVersion = if (current == null) MigrationVersion.EMPTY
    else current.getVersion
    log.info("Schema version: " + currentSchemaVersion)
    log.info(MigrationInfoDumper.dumpToAsciiTable(info.all))
  }
}
