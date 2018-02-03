package mill
package twirllib

import java.io.File
import ammonite.ops.{Path, ls, mkdir, rm}
import mill.util.{Ctx, Loose}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.eval.PathRef
import coursier.{Cache, MavenRepository, Repository}


trait TwirlModule extends mill.Module {

  def twirlVersion: T[String]
  
  def repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )  
  def sources = T.input{ Agg(PathRef(basePath / 'src / 'main / 'twirl)) }
  
  def twirlClasspath : T[Loose.Agg[PathRef]] = T{
    resolveDependencies(
      repositories,
      "2.12.4",
      "2.12",    
      Seq(ivy"com.typesafe.play::twirl-compiler:${twirlVersion()}")
      )
  }
  
  def compile(worker: TwirlWorker,
              twirlClasspath: Agg[PathRef],
              input: Agg[Path])
              (implicit ctx: Ctx.DestCtx) : T[PathRef] = T.persistent{
    worker.compile(
      twirlClasspath.map(_.path),
      input.toSeq,
      ctx.dest)
    PathRef(ctx.dest)
  }
  
  def fullGen = T {
    compile(
        TwirlWorkerApi.twirlWorker(),
        twirlClasspath(),
        sources().map(_.path)
    )
  }

}

trait TestTwirlModule extends TwirlModule with TestModule {

}
