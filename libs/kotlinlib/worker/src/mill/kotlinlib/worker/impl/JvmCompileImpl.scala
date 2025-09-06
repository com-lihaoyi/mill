package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

class JvmCompileImpl() {

  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(implicit
      ctx: TaskCtx
  ): (Int, String) = {

    val allArgs = args ++ sources.map(_.toString)

    val compiler = new K2JVMCompiler()
    val exitCode = compiler.exec(ctx.log.streams.err, allArgs*)

    (exitCode.getCode(), exitCode.name())
  }

}
