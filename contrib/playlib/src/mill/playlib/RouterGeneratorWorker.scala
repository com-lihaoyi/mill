package mill
package playlib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.Path
import mill.eval.PathRef
import mill.scalalib.CompilationResult

import scala.collection.JavaConverters._

class RouterGeneratorWorker {

  private var routerGeneratorInstances = Option.empty[(Long, RouterGeneratorWorkerApi)]

  private def router(routerClasspath: Agg[Path]) = {
    val classloaderSig = routerClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    routerGeneratorInstances match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        val cl = new URLClassLoader(routerClasspath.map(_.toIO.toURI.toURL).toArray, null)
        val routerCompilerClass = cl.loadClass("play.routes.compiler.RoutesCompiler")
        val routesCompilerTaskClass = cl.loadClass("play.routes.compiler.RoutesCompiler$RoutesCompilerTask")
        val routerCompilerTaskConstructor = routesCompilerTaskClass.getConstructor(
          classOf[File],
          cl.loadClass("scala.collection.Seq"),
          classOf[Boolean],
          classOf[Boolean],
          classOf[Boolean])
        val staticRoutesGeneratorModule = cl.loadClass("play.routes.compiler.StaticRoutesGenerator$").getField("MODULE$")
        val injectedRoutesGeneratorModule = cl.loadClass("play.routes.compiler.InjectedRoutesGenerator$").getField("MODULE$")
        val compileMethod = routerCompilerClass.getMethod("compile",
          routesCompilerTaskClass,
          cl.loadClass("play.routes.compiler.RoutesGenerator"),
          classOf[java.io.File])
        val instance = new RouterGeneratorWorkerApi {
          override def compile(task: RoutesCompilerTask, generatorType: String = "injected", generatedDir: File): Either[Seq[CompilationResult], Seq[File]] = {
            // Since the classloader is isolated, we do not share any classes with the Mill classloader.
            // Thus both classloaders have different copies of "scala.collection.Seq" which are not compatible.
            val additionalImports = cl.loadClass("scala.collection.mutable.WrappedArray$ofRef")
              .getConstructors()(0)
              .newInstance(task.additionalImports.toArray)
              .asInstanceOf[AnyRef]
            val args = Array[AnyRef](task.file,
              additionalImports,
              Boolean.box(task.forwardsRouter),
              Boolean.box(task.reverseRouter),
              Boolean.box(task.namespaceReverseRouter))
            val routesCompilerTaskInstance = routerCompilerTaskConstructor.newInstance(args: _*).asInstanceOf[Object]
            val routesGeneratorInstance = generatorType match {
              case "injected" => injectedRoutesGeneratorModule.get(null)
              case "static" => staticRoutesGeneratorModule.get(null)
              case _ => throw new Exception(s"Unrecognized generator type: $generatorType. Use injected or static")
            }
            val result = compileMethod.invoke(null,
              routesCompilerTaskInstance,
              routesGeneratorInstance,
              generatedDir)
            // compile method returns an object of type Either[Seq[RoutesCompilationError], Seq[File]]
            result.getClass.getName match {
              case "scala.util.Right" =>
                val files = cl.loadClass("scala.util.Right")
                  .getMethod("value")
                  .invoke(result)
                val asJavaMethod = cl.loadClass("scala.collection.convert.DecorateAsJava")
                  .getMethod("seqAsJavaListConverter", cl.loadClass("scala.collection.Seq"))
                val javaConverters = cl.loadClass("scala.collection.JavaConverters$")
                val javaConvertersInstance = javaConverters.getField("MODULE$").get(javaConverters)
                val filesJava = cl.loadClass("scala.collection.convert.Decorators$AsJava")
                  .getMethod("asJava")
                  .invoke(asJavaMethod.invoke(javaConvertersInstance, files))
                  .asInstanceOf[java.util.List[File]]
                Right(filesJava.asScala)
              case "scala.util.Left" =>
                // TODO: convert the error of type RoutesCompilationError to a CompilationResult
                Left(Seq(CompilationResult(Path(""), PathRef(Path("")))))
            }
          }
        }
        routerGeneratorInstances = Some((classloaderSig, instance))
        instance
    }
  }

  def compile(routerClasspath: Agg[Path],
              file: Path,
              additionalImports: Seq[String],
              forwardsRouter: Boolean,
              reverseRouter: Boolean,
              namespaceReverseRouter: Boolean,
              dest: Path)
             (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compiler = router(routerClasspath)

    val result = compiler.compile(RoutesCompilerTask(file.toIO, additionalImports, forwardsRouter, reverseRouter, namespaceReverseRouter), generatedDir = dest.toIO)

    result match {
      case Right(_) =>
        val zincFile = ctx.dest / 'zinc
        mill.eval.Result.Success(CompilationResult(zincFile, PathRef(ctx.dest)))
      case Left(_) => mill.eval.Result.Failure("Unable to compile the routes") // FIXME: convert the error to a Failure
    }
  }
}

trait RouterGeneratorWorkerApi {

  def compile(task: RoutesCompilerTask, generatorType: String = "injected", generatedDir: File): Either[Seq[CompilationResult], Seq[File]]
}

case class RoutesCompilerTask(file: File, additionalImports: Seq[String], forwardsRouter: Boolean, reverseRouter: Boolean, namespaceReverseRouter: Boolean)

object RouterGeneratorWorkerApi {

  def routerGeneratorWorker = new RouterGeneratorWorker()
}
