package mill
package playlib
package worker

import java.io.File

import ammonite.ops.Path
import mill.api.{Ctx, Result}
import mill.eval.PathRef
import mill.playlib.api.{RouteCompilerType, RouteCompilerWorkerApi}
import mill.scalalib.api.CompilationResult
import play.routes.compiler
import play.routes.compiler.RoutesCompiler.RoutesCompilerTask
import play.routes.compiler.{InjectedRoutesGenerator, RoutesCompilationError, RoutesCompiler, RoutesGenerator}


private[playlib] class RouteCompilerWorker extends RouteCompilerWorkerApi {

  override def compile(files: Seq[Path],
                       additionalImports: Seq[String],
                       forwardsRouter: Boolean,
                       reverseRouter: Boolean,
                       namespaceReverseRouter: Boolean,
                       generatorType: RouteCompilerType,
                       dest: Path)
                      (implicit ctx: mill.api.Ctx): mill.api.Result[CompilationResult] = {
    generatorType match {
      case RouteCompilerType.InjectedGenerator =>
        val result = compileWithPlay(files, additionalImports, forwardsRouter, reverseRouter,
          namespaceReverseRouter, dest, ctx, InjectedRoutesGenerator)
        asMillResult(ctx, result)
      case RouteCompilerType.StaticGenerator =>
        Result.Failure("Static generator was deprecated in 2.6.0 then removed in 2.7.x, see https://www.playframework.com/documentation/2.7.x/Migration27#StaticRoutesGenerator-removed")
    }
  }

  // the following code is duplicated between play worker versions because it depends on play types
  // which are not guaranteed to stay the same between versions even though they are currently
  // identical
  private def compileWithPlay(files: Seq[Path],
                              additionalImports: Seq[String],
                              forwardsRouter: Boolean,
                              reverseRouter: Boolean,
                              namespaceReverseRouter: Boolean,
                              dest: Path,
                              ctx: Ctx,
                              routesGenerator: RoutesGenerator): Either[Seq[compiler.RoutesCompilationError], Seq[File]] = {
    val seed: Either[Seq[compiler.RoutesCompilationError], List[File]] = Right(List.empty[File])
    files.map(file => compileWithPlay(file, additionalImports, forwardsRouter, reverseRouter,
      namespaceReverseRouter, dest, ctx, routesGenerator)).foldLeft(seed) {
      case (Right(accFiles), Right(files)) => Right(accFiles ++ files)
      case (Right(accFiles), Left(errors)) => Left(errors)
      case (left@Left(errors), _) => left
    }
  }

  private def compileWithPlay(file: Path, additionalImports: Seq[String], forwardsRouter: Boolean, reverseRouter: Boolean, namespaceReverseRouter: Boolean, dest: Path, ctx: Ctx, routesGenerator: RoutesGenerator) = {
    ctx.log.debug(s"compiling file $file with play generator $routesGenerator")
    val result =
      RoutesCompiler.compile(
        RoutesCompilerTask(file.toIO, additionalImports, forwardsRouter, reverseRouter,
          namespaceReverseRouter),
        generator = routesGenerator,
        generatedDir = dest.toIO
      )
    ctx.log.debug(s"compilation result: $result")
    result
  }

  private def asMillResult(ctx: Ctx, result: Either[Seq[RoutesCompilationError], Seq[File]]): Result[CompilationResult] = {
    result match {
      case Right(_) =>
        val zincFile = ctx.dest / 'zinc
        Result.Success(CompilationResult(zincFile, PathRef(ctx.dest)))
      case Left(errors) =>
        val errorMsg = errors.map(error =>
          s"compilation error in ${error.source.getPath} at line ${error.line.getOrElse("?")}, " +
            s"column ${error.column.getOrElse("?")}: ${error.message}")
          .mkString("\n")
        Result.Failure("Unable to compile play routes, " + errorMsg)
    }
  }
}