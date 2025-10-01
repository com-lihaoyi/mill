package mill.kotlinlib.worker.impl

import mill.api.TaskCtx

trait Compiler {
  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(implicit
      ctx: TaskCtx
  ): (Int, String)

}
