package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.cli.js.K2JSCompiler


class JsCompileImpl() {

  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(implicit
      ctx: TaskCtx
  ): (Int, String) = {

    val compiler = new K2JSCompiler()
    val allArgs = args ++ sources.map(_.toString)

    val exitCode = compiler.exec(ctx.log.streams.err, allArgs*)

    (exitCode.getCode(), exitCode.name())

  }

}
