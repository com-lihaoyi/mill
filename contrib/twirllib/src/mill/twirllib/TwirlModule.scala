package mill
package twirllib

import coursier.{Dependency, Repository}
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

  def twirlSources: T[Seq[PathRef]] = T.sources {
    millSourcePath / "views"
  }

  /**
   * Replicate the logic from twirl build,
   *      see: https://github.com/playframework/twirl/blob/bdac9bb9470a7533a44b40c37fb3064737418768/build.sbt#L17-L22
   */
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
  trait TwirlResolver extends CoursierModule {
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
  object twirlCoursierResolver extends TwirlResolver

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
