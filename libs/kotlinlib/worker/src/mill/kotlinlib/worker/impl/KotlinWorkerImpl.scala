/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib.worker.impl

import mill.api.daemon.Result
import mill.api.TaskCtx
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}
import scala.util.chaining.given

class KotlinWorkerImpl extends KotlinWorker {

  private var jvmCompileBtApi: Option[Compiler] = Option.empty
  private var jvmCompile: Option[Compiler] = Option.empty
  private var jsCompile: Option[Compiler] = Option.empty

  def compile(
      target: KotlinWorkerTarget,
      useBtApi: Boolean,
      args: Seq[String],
      sources: Seq[os.Path]
  )(using
      ctx: TaskCtx
  ): Result[Unit] = {
    ctx.log.debug(s"Using Kotlin compiler arguments: " +
      args.map(v => s"'${v}'").mkString(" "))

    ctx.log.debug(s"Using source files: ${sources.map(v => s"'${v}'").mkString(" ")}")

    // Use dedicated class to load implementation classes lazily
    val compiler = synchronized {
      (target = target, useBtApi = useBtApi) match {
        case (KotlinWorkerTarget.Jvm, true) => jvmCompileBtApi.getOrElse {
            JvmCompileBtApiImpl()
              .tap { c => jvmCompileBtApi = Some(c) }
          }
        case (KotlinWorkerTarget.Jvm, false) => jvmCompile.getOrElse {
            JvmCompileImpl()
              .tap { c => jvmCompile = Some(c) }
          }
        case (target = KotlinWorkerTarget.Js) => jsCompile.getOrElse {
            JsCompileImpl()
              .tap { c => jsCompile = Some(c) }
          }
      }
    }

    ctx.log.debug(s"Using compiler backend: ${compiler.getClass().getSimpleName()}")

    val (exitCode, exitCodeName) = compiler.compile(args, sources)

    if (exitCode != 0) {
      sys.error(s"Kotlin compiler failed with exit code ${exitCode} ($exitCodeName)")
    }
    ()

  }

  override def close(): Unit = {
    Seq(jvmCompileBtApi, jvmCompile, jsCompile).flatten.foreach(_.close())
  }

}
