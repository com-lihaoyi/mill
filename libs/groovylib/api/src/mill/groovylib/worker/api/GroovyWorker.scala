package mill.groovylib.worker.api

import mill.api.TaskCtx
import mill.api.Result
import mill.javalib.api.CompilationResult

trait GroovyWorker {

  def compile(sourceFiles: Seq[os.Path], classpath: Seq[os.Path], outputDir: os.Path)(implicit
      ctx: TaskCtx
  ): Result[CompilationResult]
}
