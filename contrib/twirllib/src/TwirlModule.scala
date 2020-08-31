package mill
package twirllib

import coursier.MavenRepository
import mill.define.Sources
import mill.api.PathRef
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.api.Loose

import scala.io.Codec
import scala.util.Properties

trait TwirlModule extends mill.Module {

  def twirlVersion: T[String]

  def twirlSources: Sources = T.sources {
    millSourcePath / 'views
  }

  def twirlClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(
        coursier.LocalRepositories.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2")
      ),
      Lib.depToDependency(_, "2.12.4"),
      Seq(
        ivy"com.typesafe.play::twirl-compiler:${twirlVersion()}",
        ivy"org.scala-lang.modules::scala-parser-combinators:1.1.0"
      )
    )
  }

  def twirlImports: T[Seq[String]] = T {
    TwirlWorkerApi.twirlWorker.defaultImports(twirlClasspath().map(_.path))
  }

  def twirlFormats: T[Map[String, String]] = TwirlWorkerApi.twirlWorker.defaultFormats

  def twirlConstructorAnnotations: Seq[String] = Nil

  def twirlCodec: Codec = Codec(Properties.sourceEncoding)

  def twirlInclusiveDot: Boolean = false

  def compileTwirl: T[mill.scalalib.api.CompilationResult] = T.persistent {
    TwirlWorkerApi.twirlWorker
      .compile(
        twirlClasspath().map(_.path),
        twirlSources().map(_.path),
        T.dest,
        twirlImports(),
        twirlFormats(),
        twirlConstructorAnnotations,
        twirlCodec,
        twirlInclusiveDot)
  }
}
