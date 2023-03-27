package mill
package twirllib

import coursier.{Dependency, Repository}
import mill.define.{Sources, Task}
import mill.api.PathRef
import mill.scalalib._
import mill.api.Loose

import scala.io.Codec
import scala.util.Properties

trait TwirlModule extends mill.Module { twirlModule =>

  def twirlVersion: T[String]

  /**
   * The Scala version matching the twirl version.
   * @since Mill after 0.10.5
   */
  def twirlScalaVersion: T[String]

  def twirlSources: Sources = T.sources {
    millSourcePath / "views"
  }

  private def scalaParserCombinatorsVersion: T[String] = twirlScalaVersion.map {
    case v if v.startsWith("2.") => "1.1.2"
    case _ => "2.1.0"
  }

  /**
   * @since Mill after 0.10.5
   */
  def twirlIvyDeps: T[Agg[Dep]] = T {
    Agg(
      ivy"com.typesafe.play::twirl-compiler:${twirlVersion()}",
      ivy"org.scala-lang.modules::scala-parser-combinators:${scalaParserCombinatorsVersion()}"
    )
  }

  /**
   * Class instead of an object, to allow re-configuration.
   * @since Mill after 0.10.5
   */
  class TwirlResolver()(implicit ctx0: mill.define.Ctx) extends mill.Module()(ctx0)
      with CoursierModule {
    override def resolveCoursierDependency: Task[Dep => Dependency] = T.task { d: Dep =>
      Lib.depToDependency(d, twirlScalaVersion())
    }

    override def repositoriesTask: Task[Seq[Repository]] = twirlModule match {
      case m: CoursierModule => m.repositoriesTask
      case _ => super.repositoriesTask
    }
  }

  /**
   * @since Mill after 0.10.5
   */
  val twirlCoursierResolver = new TwirlResolver()

  def twirlClasspath: T[Loose.Agg[PathRef]] = T {
    twirlCoursierResolver.resolveDeps(T.task {
      val bind = twirlCoursierResolver.bindDependency()
      twirlIvyDeps().map(bind)
    })
  }

  def twirlImports: T[Seq[String]] = T {
    TwirlWorkerApi.twirlWorker.defaultImports(twirlClasspath())
  }

  def twirlFormats: T[Map[String, String]] = TwirlWorkerApi.twirlWorker.defaultFormats

  def twirlConstructorAnnotations: Seq[String] = Nil

  def twirlCodec: Codec = Codec(Properties.sourceEncoding)

  def twirlInclusiveDot: Boolean = false

  def compileTwirl: T[mill.scalalib.api.CompilationResult] = T.persistent {
    TwirlWorkerApi.twirlWorker
      .compile(
        twirlClasspath(),
        twirlSources().map(_.path),
        T.dest,
        twirlImports(),
        twirlFormats(),
        twirlConstructorAnnotations,
        twirlCodec,
        twirlInclusiveDot
      )
  }
}
