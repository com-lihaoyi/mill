package mill.kotlinlib.worker.impl

import mill.api.{PathRef, TaskCtx}

trait Compiler {

  /**
   * @param args Compiler arguments.
   * @param sources Source files to compile.
   * @param classpath Compilation classpath, useful for incremental compilation.
   */
  def compile(
      args: Seq[String],
      sources: Seq[os.Path],
      classpath: Seq[PathRef]
  )(using
      ctx: TaskCtx
  ): (Int, String)

}
