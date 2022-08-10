package mill.scalalib.bsp

import mill.api.experimental
import mill.{Agg, T}
import mill.define.Target
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.api.Result
import mill.scalalib.api.ZincWorkerUtil

/*+ Enable some common settings required to properly support Metals Language Server (via BSP). */
@experimental
trait ScalaMetalsSupport extends ScalaModule {

//  /** The semanticDB version to use. It needs to support your configured Scala 2 version. */
//  def semanticDbVersion: T[String] = T { "" }

  override def scalacPluginIvyDeps: Target[Agg[Dep]] = T {
    val sv = scalaVersion()
    val semDbVersion = semanticDbVersion()
    val superRes = super.scalacPluginIvyDeps()
    if (!ZincWorkerUtil.isScala3(sv) && semDbVersion.isEmpty) {
      val msg =
        """|
           |When using ScalaMetalsSupport with Scala 2 you must provide a semanticDbVersion
           |
           |def semanticDbVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else if (ZincWorkerUtil.isScala3(sv)) {
      Result.Success(superRes)
    } else {
      Result.Success(
        superRes ++ Agg(
          ivy"org.scalameta:::semanticdb-scalac:${semDbVersion}"
        )
      )
    }
  }

  /** Adds some options and configures the semanticDB plugin. */
  override def mandatoryScalacOptions: Target[Seq[String]] = T {
    super.mandatoryScalacOptions() ++ {
      if (ZincWorkerUtil.isScala3(scalaVersion())) {
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
