package mill.init.migrate
package sbt

import mainargs.{Leftover, ParserForClass}

object SbtImportMain:

  def main(args0: Array[String]) =
    val args = ParserForClass[SbtImportArgs].constructOrExit(args0.toSeq)
    import args.*

    val extracted = SbtExtract(sbtCmd, sbtOptions.value)
      .extractModules(testModuleName = testModuleName)

    require(extracted.nonEmpty, "no modules were extracted from SBT project")

    def transform(module: MetaModule[SbtModuleMetadata]): MetaModule[ModuleMetadata] =
      module.transform: (module, _) =>
        import module.*
        import metadata.*
        val crossBase = Option.when(crossScalaVersions.length > 1)("mill.scalalib.CrossScalaModule")
        val baseTrait = BaseTrait(nameAlias, data).extendTypes(crossBase)
        val moduleMetadata = ModuleMetadata(Some(baseTrait), crossScalaVersions)
        MetaModule(moduleDir, moduleMetadata, name, ModuleData()) // TODO testModule
    end transform
    val transformed = extracted.groupBy(_.metadata.crossPlatformBaseDir).flatMap:
      case (Some(crossModuleDir), modules) =>
        Seq(MetaModule(
          moduleDir = crossModuleDir,
          metadata = ModuleMetadata(),
          submodules = modules.map(module =>
            transform(module.copy(moduleDir = Nil, name = module.moduleDir.last))
          )
        ))
      case (None, modules) => modules.map(transform)
    .toSeq

    val transformed0 =
      if transformed.exists(_.isRoot) then transformed
      else MetaModule(Nil, ModuleMetadata()) +: transformed

    writeBuildFiles(transformed0)

@mainargs.arg
case class SbtImportArgs(
    testModuleName: String = "test",
    sbtCmd: Option[String],
    sbtOptions: Leftover[String]
)
