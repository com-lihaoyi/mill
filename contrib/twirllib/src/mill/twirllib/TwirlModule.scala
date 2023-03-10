package mill
package twirllib

import coursier.{Dependency, Repository}
import mill.define.{Sources, Task}
import mill.api.PathRef
import mill.scalalib._
import mill.api.Loose
import mill.scalalib.api.ZincWorkerUtil

import scala.io.Codec
import scala.util.Properties

trait TwirlModule extends CoursierModule { twirlModule =>

  def twirlVersion: T[String]

  /**
   * The Scala version matching the twirl version.
   * @since Mill after 0.10.5
   */
  def twirlScalaVersion: T[String]

  def twirlSources: Sources = T.sources {
    millSourcePath / "views"
  }

  /**
   * @since Mill after 0.10.5
   */
  def twirlIvyDeps: T[Agg[Dep]] = T {
    Agg(
      ivy"com.typesafe.play::twirl-compiler:${twirlVersion()}",
      ivy"org.scala-lang.modules::scala-parser-combinators:1.1.2"
    )
  }

  def twirlClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDeps(T.task {
      val sv = twirlScalaVersion()
      val bv = ZincWorkerUtil.scalaBinaryVersion(sv)
      twirlIvyDeps().map(_.bindDep(bv,sv, ""))
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
