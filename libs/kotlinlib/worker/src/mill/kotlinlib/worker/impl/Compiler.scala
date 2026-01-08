package mill.kotlinlib.worker.impl

import mill.api.TaskCtx

trait Compiler extends AutoCloseable {
  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(using
      ctx: TaskCtx
  ): (Int, String)

  override def close(): Unit = {
    // no-op
  }

}
