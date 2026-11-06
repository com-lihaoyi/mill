package mill.kotlinlib.worker.impl

import mill.api.{PathRef, TaskCtx}
import org.jetbrains.kotlin.cli.common.messages.{
  CompilerMessageSourceLocation,
  PlainTextMessageRenderer
}
import org.jetbrains.kotlin.cli.js.K2JSCompiler

class JsCompileImpl extends Compiler() {

  def compile(
      args: Seq[String],
      sources: Seq[os.Path],
      classpath: Seq[PathRef]
  )(using
      ctx: TaskCtx
  ): (Int, String) = {

    val compiler = K2JSCompiler()
    val allArgs = args ++ sources.map(_.toString)

    val messageRenderer = new PlainTextMessageRenderer( /*colorEnabled*/ true) {
      override protected def getPath(location: CompilerMessageSourceLocation): String = {
        val path = os.Path(location.getPath, os.pwd)
        path.relativeTo(ctx.workspace).toString()
      }

      override def getName = "WorkspaceRelativePath"
    }

    val exitCode = compiler.exec(ctx.log.streams.err, messageRenderer, allArgs*)

    (exitCode.getCode(), exitCode.name())

  }

}
