package mill.scalalib.scalafmt

import mill.api.daemon.Result
import mill.api.{PathRef, TaskCtx}

private[scalafmt] trait ScalafmtWorker extends AutoCloseable {

  def reformat(
      input: Seq[PathRef],
      scalafmtConfig: PathRef
  )(using ctx: TaskCtx): Unit

  def checkFormat(
      input: Seq[PathRef],
      scalafmtConfig: PathRef
  )(using
      ctx: TaskCtx
  ): Result[Unit]
}
