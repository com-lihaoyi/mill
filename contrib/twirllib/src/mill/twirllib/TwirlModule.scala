package mill
package twirllib

import coursier.Repository
import mill.api.PathRef
import mill.scalalib.*

import mill.define.Task
import mill.util.BuildInfo

import scala.io.Codec
import scala.util.Properties

trait TwirlModule extends mill.Module { twirlModule =>

  def twirlVersion: T[String]

  /**
   * The Scala version matching the twirl version.
   * @since Mill after 0.10.5
   */
  def twirlScalaVersion: T[String] = Task {
    twirlVersion() match {
      case s"1.$minor.$_" if minor.toIntOption.exists(_ < 6) =>
        if minor.toIntOption.exists(_ < 4) then BuildInfo.workerScalaVersion212
        else BuildInfo.workerScalaVersion213
      case _ => BuildInfo.scalaVersion
    }
  }

  def twirlSources: T[Seq[PathRef]] = Task.Sources {
    moduleDir / "views"
  }

  /**
   * Replicate the logic from twirl build,
   *      see: https://github.com/playframework/twirl/blob/2.0.1/build.sbt#L12-L17
   */
  private def scalaParserCombinatorsVersion: Task[String] = twirlScalaVersion.map {
    case v if v.startsWith("2.") => "1.1.2"
    case _ => "2.3.0"
  }

  /**
   * @since Mill after 0.10.5
   */
  def twirlIvyDeps: T[Seq[Dep]] = Task {
    Seq(
      if (twirlVersion().startsWith("1."))
        ivy"com.typesafe.play::twirl-compiler:${twirlVersion()}"
      else ivy"org.playframework.twirl::twirl-compiler:${twirlVersion()}"
    ) ++
      Seq(
        ivy"org.scala-lang.modules::scala-parser-combinators:${scalaParserCombinatorsVersion()}"
      )
  }

  /**
   * Class instead of an object, to allow re-configuration.
   * @since Mill after 0.10.5
   */
  trait TwirlResolver extends CoursierModule {
    def bindDependency: Task[Dep => BoundDep] = Task.Anon { (dep: Dep) =>
      BoundDep(Lib.depToDependency(dep, twirlScalaVersion()), dep.force)
    }

    override def repositoriesTask: Task[Seq[Repository]] = twirlModule match {
      case m: CoursierModule => m.repositoriesTask
      case _ => super.repositoriesTask
    }
  }

  /**
   * @since Mill after 0.10.5
   */
  lazy val twirlCoursierResolver: TwirlResolver = new TwirlResolver {}

  def twirlClasspath: T[Seq[PathRef]] = Task {
    twirlCoursierResolver.defaultResolver().classpath(twirlIvyDeps())
  }

  def twirlImports: T[Seq[String]] = Task {
    TwirlWorkerApi.twirlWorker.defaultImports(twirlClasspath())
  }

  def twirlFormats: T[Map[String, String]] = TwirlWorkerApi.twirlWorker.defaultFormats

  def twirlConstructorAnnotations: Seq[String] = Nil

  def twirlCodec: Codec = Codec(Properties.sourceEncoding)

  def twirlInclusiveDot: Boolean = false

  def compileTwirl: T[mill.scalalib.api.CompilationResult] = Task(persistent = true) {
    TwirlWorkerApi.twirlWorker
      .compile(
        twirlClasspath(),
        twirlSources().map(_.path),
        Task.dest,
        twirlImports(),
        twirlFormats(),
        twirlConstructorAnnotations,
        twirlCodec,
        twirlInclusiveDot
      )
  }
}
