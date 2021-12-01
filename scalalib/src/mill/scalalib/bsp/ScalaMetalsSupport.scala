package mill.scalalib.bsp

import mill.api.experimental
import mill.{Agg, T}
import mill.define.Target
import mill.scalalib.api.ZincWorkerUtil.isScala3
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.api.Result

/*+ Enable some common settings required to properly support Metals Language Server (via BSP). */
@experimental
trait ScalaMetalsSupport extends ScalaModule {

  /** The semanticDB version to use. It needs to support your configured Scala 2 version. */
  def semanticDbVersion: T[String] = T { "" }

  override def scalacPluginIvyDeps: Target[Agg[Dep]] = T {
    if (!isScala3(scalaVersion()) && semanticDbVersion().isEmpty) {
      val msg =
        """|
           |When using ScalaMetalsSupport with Scala 2 you must provide a semanticDbVersion
           |
           |def semanticDbVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else if (isScala3(scalaVersion())) {
      Result.Success(super.scalacPluginIvyDeps())
    } else {
      Result.Success(
        super.scalacPluginIvyDeps() ++ Agg(
          ivy"org.scalameta:::semanticdb-scalac:${semanticDbVersion()}"
        )
      )
    }
  }

  /** Adds some options and configures the semanticDB plugin. */
  override def mandatoryScalacOptions: Target[Seq[String]] = T {
    super.mandatoryScalacOptions() ++ {
      if (isScala3(scalaVersion())) {
        Seq("-Xsemanticdb")
      } else {
        Seq("-Yrangepos", s"-P:semanticdb:sourceroot:${T.workspace}")
      }
    }
  }

  /** Filters options unsupported by Metals. */
  override def allScalacOptions: Target[Seq[String]] = T {
    super.allScalacOptions().filterNot(_ == "-Xfatal-warnings")
  }
}
