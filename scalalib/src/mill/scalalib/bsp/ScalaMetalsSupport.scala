package mill.scalalib.bsp

import mill.api.experimental
import mill.{Agg, T}
import mill.define.Target
import mill.scalalib.{Dep, DepSyntax, ScalaModule}

/*+ Enable some common settings required to properly support Metals Language Server (via BSP). */
@experimental
trait ScalaMetalsSupport extends ScalaModule {

  /** The semanticDB version to use. It needs to support your configured Scala versions. */
  def semanticDbVersion: T[String]
  override def scalacPluginIvyDeps: Target[Agg[Dep]] = T {
    super.scalacPluginIvyDeps() ++ Agg(
      ivy"org.scalameta:::semanticdb-scalac:${semanticDbVersion()}"
    )
  }
  /** Adds some options and configures the semanticDB plugin. */
  override def mandatoryScalacOptions: Target[Seq[String]] = T {
    super.mandatoryScalacOptions() ++ Seq("-Yrangepos", s"-P:semanticdb:sourceroot:${T.workspace}")
  }
  /** Filters options unsupported by Metals. */
  override def allScalacOptions: Target[Seq[String]] = T {
    super.allScalacOptions().filterNot(_ == "-Xfatal-warnings")
  }
}
