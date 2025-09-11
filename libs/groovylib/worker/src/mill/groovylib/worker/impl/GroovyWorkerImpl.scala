package mill.groovylib.worker.impl

import groovy.lang.GroovyClassLoader
import mill.api.Result
import mill.api.TaskCtx
import mill.javalib.api.CompilationResult
import mill.groovylib.worker.api.GroovyWorker
import org.codehaus.groovy.control.{CompilationUnit, CompilerConfiguration, Phases}

import scala.jdk.CollectionConverters.*
import scala.util.Try

class GroovyWorkerImpl extends GroovyWorker {

  def compile(
      sourceFiles: Seq[os.Path],
      classpath: Seq[os.Path],
      outputDir: os.Path
  )(implicit
      ctx: TaskCtx
  ): Result[CompilationResult] = {

    val config = new CompilerConfiguration()
    config.setTargetDirectory(outputDir.toIO)
    config.setClasspathList(classpath.map(_.toIO.getAbsolutePath).asJava)
    // TODO
//    config.setDisabledGlobalASTTransformations()
//    config.setJointCompilationOptions()
//    config.setSourceEncoding()

    // we need to set the classloader for groovy to use the worker classloader
    val parentCl: ClassLoader = this.getClass.getClassLoader
    // config in the GroovyClassLoader is needed when the CL itself is compiling classes
    val gcl = new GroovyClassLoader(parentCl, config)
    // config for actual compilation
    val unit = new CompilationUnit(config, null, gcl)

    sourceFiles.foreach { sourceFile =>
      unit.addSource(sourceFile.toIO)
    }

    Try {
      unit.compile(Phases.OUTPUT)
      CompilationResult(outputDir, mill.api.PathRef(outputDir))
    }.fold(
      exception => Result.Failure(s"Groovy compilation failed: ${exception.getMessage}"),
      result => Result.Success(result)
    )
  }
}
