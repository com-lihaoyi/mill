package mill
package contrib.flyway

import java.net.URLClassLoader

import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.logging.LogFactory
import org.flywaydb.core.internal.configuration.{ConfigUtils => flyway}
import org.flywaydb.core.internal.logging.console.ConsoleLog.Level
import org.flywaydb.core.internal.logging.console.ConsoleLogCreator

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

  private def tToOptPair[A](key: String, t: A) =
    Option(t)
      .filter {
        case a: String => a.nonEmpty
        case _ => true
      }
      .map(key -> _)

  def flywayInstance = T {
    val jdbcClassloader = new URLClassLoader(jdbcClasspath().map(_.path.toIO.toURI.toURL).toArray)

    val configProps = Map(flyway.URL -> flywayUrl()) ++
      tToOptPair(flyway.USER, flywayUser()) ++
      tToOptPair(flyway.PASSWORD, flywayPassword())

    LogFactory.setLogCreator(new ConsoleLogCreator(Level.INFO))

    Flyway
      .configure(jdbcClassloader)
      .locations(flywayFileLocations().map("filesystem:" + _.path):_*)
      .configuration(configProps.asJava)
      .load
  }

  def flywayMigrate() = T.command (
    flywayInstance().migrate()
  )
}
