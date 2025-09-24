package mill.groovylib.worker.impl

import groovy.lang.GroovyClassLoader
import mill.api.Result
import mill.api.TaskCtx
import mill.javalib.api.CompilationResult
import mill.groovylib.worker.api.{GroovyCompilerConfiguration, GroovyWorker}
import org.codehaus.groovy.control.{CompilationUnit, CompilerConfiguration, Phases}
import org.codehaus.groovy.tools.javac.JavaStubCompilationUnit
import os.Path

import scala.jdk.CollectionConverters.*
import scala.util.Try

class GroovyWorkerImpl extends GroovyWorker {

  override def compileGroovyStubs(
      sourceFiles: Seq[Path],
      classpath: Seq[Path],
      outputDir: Path,
      config: GroovyCompilerConfiguration,
  )(implicit ctx: TaskCtx): Result[CompilationResult] = {
    val compilerConfig = new CompilerConfiguration()
    compilerConfig.setTargetDirectory(outputDir.toIO)
    compilerConfig.setClasspathList(classpath.map(_.toIO.getAbsolutePath).asJava)
    compilerConfig.setJointCompilationOptions(Map(
      "stubDir" -> outputDir.toIO,
      "keepStubs" -> false
    ).asJava)
    compilerConfig.setDisabledGlobalASTTransformations(config.disabledGlobalAstTransformations.asJava)
    compilerConfig.setPreviewFeatures(config.enablePreview)
    config.targetBytecode.foreach(compilerConfig.setTargetBytecode)

    // we need to set the classloader for groovy to use the worker classloader
    val parentCl: ClassLoader = this.getClass.getClassLoader
    // compilerConfig in the GroovyClassLoader is needed when the CL itself is compiling classes
    val gcl = new GroovyClassLoader(parentCl, compilerConfig)
    // compilerConfig for actual compilation
    val stubUnit = JavaStubCompilationUnit(compilerConfig, gcl)

    sourceFiles.foreach { sourceFile =>
      stubUnit.addSource(sourceFile.toIO)
    }

    Try {
      stubUnit.compile(Phases.CONVERSION)
      CompilationResult(outputDir, mill.api.PathRef(outputDir))
    }.fold(
      exception => Result.Failure(s"Groovy stub generation failed: ${exception.getMessage}"),
      result => Result.Success(result)
    )

  }

  def compile(
      sourceFiles: Seq[os.Path],
      classpath: Seq[os.Path],
      outputDir: os.Path,
      config: GroovyCompilerConfiguration,
  )(implicit
      ctx: TaskCtx
  ): Result[CompilationResult] = {

    val extendedClasspath = classpath :+ outputDir

    val compilerConfig = new CompilerConfiguration()
    compilerConfig.setTargetDirectory(outputDir.toIO)
    compilerConfig.setClasspathList(extendedClasspath.map(_.toIO.getAbsolutePath).asJava)
    compilerConfig.setDisabledGlobalASTTransformations(config.disabledGlobalAstTransformations.asJava)
    compilerConfig.setPreviewFeatures(config.enablePreview)
    config.targetBytecode.foreach(compilerConfig.setTargetBytecode)

    // we need to set the classloader for groovy to use the worker classloader
    val parentCl: ClassLoader = this.getClass.getClassLoader
    // compilerConfig in the GroovyClassLoader is needed when the CL itself is compiling classes
    val gcl = new GroovyClassLoader(parentCl, compilerConfig)

    // compilerConfig for actual compilation
    val unit = new CompilationUnit(compilerConfig, null, gcl)

    sourceFiles.foreach { sourceFile =>
      unit.addSource(sourceFile.toIO)
    }

    Try {
      unit.compile(Phases.OUTPUT)
      removeAllJavaFiles(outputDir)
      CompilationResult(outputDir, mill.api.PathRef(outputDir))
    }.fold(
      exception => Result.Failure(s"Groovy compilation failed: ${exception.getMessage}"),
      result => Result.Success(result)
    )
  }

  private def removeAllJavaFiles(outputDir: os.Path): Unit = {
    if (os.exists(outputDir)) {
      os.walk(outputDir)
        .filter(_.ext == "java")
        .foreach(os.remove)
    }
  }
}
