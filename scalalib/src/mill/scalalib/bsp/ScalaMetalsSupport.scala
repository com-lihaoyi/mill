package mill.scalalib.bsp

import mill.api.{Result, experimental}
import mill.{Agg, T, Task}
import mill.scalalib.{Dep, DepSyntax, ScalaModule}
import mill.scalalib.api.JvmWorkerUtil

/*+ Enable some common settings required to properly support Metals Language Server (via BSP). */
@experimental
@deprecated(
  "No longer needed. Mill BSP now automatically supports SemanticDB. " +
    "If you rely on SemanticDB data, have a look at mill.scalalib.SemanticDbJavaModule.",
  "Mill 0.10.6"
)
trait ScalaMetalsSupport extends ScalaModule {

  override def scalacPluginIvyDeps: T[Agg[Dep]] = Task {
    val sv = scalaVersion()
    val semDbVersion = semanticDbVersion()
    val superRes = super.scalacPluginIvyDeps()
    if (!JvmWorkerUtil.isScala3(sv) && semDbVersion.isEmpty) {
      val msg =
        """|
           |When using ScalaMetalsSupport with Scala 2 you must provide a semanticDbVersion
           |
           |def semanticDbVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else if (JvmWorkerUtil.isScala3(sv)) {
      Result.Success(superRes)
    } else {
      Result.Success(
        superRes ++ Agg(
          mvn"org.scalameta:::semanticdb-scalac:${semDbVersion}"
        )
      )
    }
  }

  /** Adds some options and configures the semanticDB plugin. */
  override def mandatoryScalacOptions: T[Seq[String]] = Task {
    super.mandatoryScalacOptions() ++ {
      if (JvmWorkerUtil.isScala3(scalaVersion())) {
        Seq("-Xsemanticdb")
      } else {
        Seq("-Yrangepos", s"-P:semanticdb:sourceroot:${Task.workspace}")
      }
    }
  }

  /** Filters options unsupported by Metals. */
  override def allScalacOptions: T[Seq[String]] = Task {
    super.allScalacOptions().filterNot(_ == "-Xfatal-warnings")
  }
}
