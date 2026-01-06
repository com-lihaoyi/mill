package mill.groovylib.worker.api

import mill.api.TaskCtx
import mill.api.daemon.Result
import mill.javalib.api.CompilationResult

/**
 * Runs the actual compilation.
 *
 * Supports 3-stage compilation for Java <-> Groovy
 * 1. compile Java stubs
 * 2. compile Java sources (done externally)
 * 3. compile Groovy sources
 */
@mill.api.experimental
trait GroovyWorker {

  /**
   * In a mixed setup this will compile the Groovy sources to Java stubs.
   */
  def compileGroovyStubs(
      sourceFiles: Seq[os.Path],
      classpath: Seq[os.Path],
      outputDir: os.Path,
      config: GroovyCompilerConfiguration
  )(implicit
      ctx: TaskCtx
  )
      : Result[Unit]

  /**
   * Compiles the Groovy sources. In a mixed setup this method assumes that the Java stubs
   * are already present in the outputDir.
   */
  def compile(
      sourceFiles: Seq[os.Path],
      classpath: Seq[os.Path],
      outputDir: os.Path,
      config: GroovyCompilerConfiguration
  )(implicit
      ctx: TaskCtx
  )
      : Result[CompilationResult]
}
