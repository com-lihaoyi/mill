package mill
package playlib
package api

import ammonite.ops.Path
import mill.api.Result
import mill.scalalib.api.CompilationResult


trait RouteCompilerWorkerApi {
  def compile(files: Seq[Path],
              additionalImports: Seq[String],
              forwardsRouter: Boolean,
              reverseRouter: Boolean,
              namespaceReverseRouter: Boolean,
              generatorType:RouteCompilerType,
              dest: Path)(implicit ctx: mill.api.Ctx):Result[CompilationResult]


}

sealed trait RouteCompilerType
object RouteCompilerType{
  case object InjectedGenerator extends RouteCompilerType
  case object StaticGenerator extends RouteCompilerType
}