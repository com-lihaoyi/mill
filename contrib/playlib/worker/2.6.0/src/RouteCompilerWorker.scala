package mill
package playlib
package worker

import java.io.File

import ammonite.ops.Path
import mill.api.Result
import mill.eval.PathRef
import mill.playlib.api.{RouteCompilerType, RouteCompilerWorkerApi}
import mill.scalalib.api.CompilationResult
import play.routes.compiler.RoutesCompiler.RoutesCompilerTask
import play.routes.compiler.{InjectedRoutesGenerator, RoutesCompiler, StaticRoutesGenerator}


class RouteCompilerWorker extends RouteCompilerWorkerApi {

  override def compile(
                        file: Path,
                        additionalImports: Seq[String],
                        forwardsRouter: Boolean,
                        reverseRouter: Boolean,
                        namespaceReverseRouter: Boolean,
                        generatorType: RouteCompilerType,
                        dest: Path)
                      (implicit ctx: mill.api.Ctx): mill.api.Result[CompilationResult] = {
    val routesGenerator = generatorType match {
      case RouteCompilerType.InjectedGenerator => InjectedRoutesGenerator
      case RouteCompilerType.StaticGenerator =>
        ctx.log.error("Static generator was deprecated in 2.6.0 and will be removed in 2.7.0")
        StaticRoutesGenerator
      case _ => throw new Exception(s"Unrecognized generator type: $generatorType. Use injected or static")
    }
    ctx.log.debug(s"compiling file $file with generator $generatorType")
    val result =
      RoutesCompiler.compile(
        RoutesCompilerTask(file.toIO, additionalImports, forwardsRouter, reverseRouter,
          namespaceReverseRouter),
        generator = routesGenerator,
        generatedDir = dest.toIO
      )
    ctx.log.debug("compilation result:" + result)
    result match {
      case Right(_) =>
        val zincFile = ctx.dest / 'zinc
        Result.Success(CompilationResult(zincFile, PathRef(ctx.dest)))
      case Left(errors) =>
        val errorMsg = errors.map(error =>
          s"compilation error in ${error.source.getPath} at line ${error.line.getOrElse("?")}," +
            s" column ${error.column.getOrElse("?")}: ${error.message}".stripMargin)
          .mkString("\n")
        Result.Failure("Unable to compile play routes, " + errorMsg)
    }
  }
}


case class RoutesCompilationError(source: File, message: String, line: Option[Int], column: Option[Int])