package mill
package twirllib

import coursier.{Cache, MavenRepository}
import mill.define.Sources
import mill.eval.PathRef
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.util.Loose

trait TwirlModule extends mill.Module {

  def twirlVersion: T[String]

  def twirlSources: Sources = T.sources {
    millSourcePath / 'views
  }

  def twirlClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2")
      ),
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"com.typesafe.play::twirl-compiler:${twirlVersion()}")
    )
  }

  def compileTwirl: T[CompilationResult] = T.persistent {
    TwirlWorkerApi.twirlWorker
      .compile(
        twirlClasspath().map(_.path),
        twirlSources().map(_.path),
        T.ctx().dest)
  }
}

trait TestTwirlModule extends TwirlModule with TestModule {
}
