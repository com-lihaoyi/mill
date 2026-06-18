package mill.kotlinlib.worker.impl

import mill.api.{PathRef, TaskCtx}

trait Compiler {
  def compile(
      args: Seq[String],
      sources: Seq[os.Path],
      classpath: Seq[PathRef] = Nil,
      classpathSnapshotCache: Option[os.Path] = None
  )(using
      ctx: TaskCtx
  ): (Int, String)

}
