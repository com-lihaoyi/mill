package mill.main.buildgen

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.internal.Util.backtickWrap
import pprint.Util.literalize

import java.io.PrintStream
import scala.util.Using

trait BuildWriter {

  final def writeFiles(build: BuildRepr): Unit = {
    import build.*
    val root +: nested = packages.iterator.toSeq: @unchecked
    Using.resource(PrintStream(os.write.outputStream(os.pwd / rootBuildFileNames.get(0)))):
      writeRootPackage(_, root, depsObject)
    nested.foreach: pkg =>
      Using.resource(
        PrintStream(os.write.outputStream(os.pwd / pkg.root.segments / nestedBuildFileNames.get(0)))
      ):
        writeNestedPackage(_, pkg)
  }

  protected def writeRootPackage(
      ps: PrintStream,
      pkg: Tree[ModuleRepr],
      depsObject: Option[DepsObject]
  ): Unit = {
    ps.println(s"package $rootModuleAlias")
    writeImports(ps, pkg)
    writeModuleTree(
      ps,
      "package",
      pkg,
      () =>
        depsObject.foreach(writeDepsObject(ps, _))
    )
  }

  protected def writeNestedPackage(ps: PrintStream, pkg: Tree[ModuleRepr]): Unit = {
    ps.println(pkg.root.segments.map(backtickWrap)
      .mkString(s"package $rootModuleAlias.", ".", ""))
    writeImports(ps, pkg)
    writeModuleTree(ps, "package", pkg)
  }

  protected def writeImports(ps: PrintStream, pkg: Tree[ModuleRepr]): Unit

  protected def writeModuleTree(
      ps: PrintStream,
      rootModuleName: String,
      modules: Tree[ModuleRepr],
      embed: () => Unit = () => ()
  ): Unit = {
    import modules.*
    ps.println()
    writeModuleDeclaration(ps, rootModuleName, root)
    ps.println(" {")
    writeModuleConfigs(ps, root.configs, root.crossConfigs)
    root.testModule.foreach(writeTestModule(ps, _))
    children.foreach(subtree => writeModuleTree(ps, subtree.root.segments.last, subtree))
    embed()
    ps.println('}')
  }

  protected def writeModuleDeclaration(ps: PrintStream, name: String, module: ModuleRepr): Unit = {
    import module.*
    if (crossConfigs.nonEmpty) {
      val crossTraitName =
        segments.lastOption.getOrElse(os.pwd.last).split("\\W").map(_.capitalize)
          .mkString("", "", "Module")
      val crossTraitExtends = crossConfigs.map((v, _) => literalize(v))
        .mkString(s"extends Cross[$crossTraitName](", ", ", ")")
      ps.println(s"object ${backtickWrap(name)} $crossTraitExtends")
      ps.print(s"trait $crossTraitName ${renderExtendsClause(supertypes)}")
    } else {
      ps.print(s"object ${backtickWrap(name)} ${renderExtendsClause(supertypes)}")
    }
  }

  protected def renderExtendsClause(supertypes: Seq[String]): String = {
    supertypes.tail.map(" with " + _).mkString(s" extends ${supertypes.head}", "", "")
  }

  protected def writeTestModule(ps: PrintStream, module: TestModuleRepr): Unit = {
    import module.*
    ps.println()
    ps.println(s"object $name ${renderExtendsClause(supertypes)} {")
    writeModuleConfigs(ps, configs, crossConfigs)
    ps.println('}')
  }

  protected def writeModuleConfigs(
      ps: PrintStream,
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ): Unit = {
    if (crossConfigs.isEmpty) configs.foreach:
      case config: CoursierModuleConfig => writeCoursierModuleConfig(ps, config)
      case config: JavaModuleConfig => writeJavaModuleConfig(ps, config)
      case config: PublishModuleConfig => writePublishModuleConfig(ps, config)
      case config: ScalaModuleConfig => writeScalaModuleConfig(ps, config)
      case config: ScalaJSModuleConfig => writeScalaJSModuleConfig(ps, config)
      case config: ScalaNativeModuleConfig => writeScalaNativeModuleConfig(ps, config)
    else writeCrossModuleConfigs(ps, configs, crossConfigs)
  }

  protected def writeCrossModuleConfigs(
      ps: PrintStream,
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ): Unit

  protected def writeCoursierModuleConfig(ps: PrintStream, config: CoursierModuleConfig): Unit = {
    import config.*
    if (repositories.nonEmpty)
      ps.println()
      ps.println(repositories.map(literalize(_))
        .mkString("def repositories = super.repositories() ++ Seq(", ", ", ")"))
  }

  protected def writeJavaModuleConfig(ps: PrintStream, config: JavaModuleConfig): Unit = {
    import config.*
    if (mandatoryMvnDeps.nonEmpty)
      ps.println()
      ps.println(mandatoryMvnDeps
        .mkString("def mandatoryMvnDeps = super.mandatoryMvnDeps() ++ Seq(", ", ", ")"))
    if (mvnDeps.nonEmpty)
      ps.println()
      ps.println(mvnDeps.mkString("def mvnDeps = super.mvnDeps() ++ Seq(", ", ", ")"))
    if (compileMvnDeps.nonEmpty)
      ps.println()
      ps.println(compileMvnDeps
        .mkString("def compileMvnDeps = super.compileMvnDeps() ++ Seq(", ", ", ")"))
    if (runMvnDeps.nonEmpty)
      ps.println()
      ps.println(runMvnDeps.mkString("def runMvnDeps = super.runMvnDeps() ++ Seq(", ", ", ")"))
    if (moduleDeps.nonEmpty)
      ps.println()
      ps.println(moduleDeps.map(renderModuleDep)
        .mkString("def moduleDeps = super.moduleDeps ++ Seq(", ", ", ")"))
    if (compileModuleDeps.nonEmpty)
      ps.println()
      ps.println(compileModuleDeps.map(renderModuleDep)
        .mkString("def compileModuleDeps = super.compileModuleDeps ++ Seq(", ", ", ")"))
    if (runModuleDeps.nonEmpty)
      ps.println()
      ps.println(runModuleDeps.map(renderModuleDep)
        .mkString("def runModuleDeps = super.runModuleDeps ++ Seq(", ", ", ")"))
    if (javacOptions.nonEmpty)
      ps.println()
      ps.println(javacOptions.map(literalize(_))
        .mkString("def javacOptions = super.javacOptions() ++ Seq(", ", ", ")"))
  }

  protected def renderModuleDep(dep: JavaModuleConfig.ModuleDep): String = {
    import dep.*
    rootModuleAlias + crossArgs.getOrElse(-1, "") +
      segments.indices
        .map(i => "." + backtickWrap(segments(i)) + crossArgs.getOrElse(i, ""))
        .mkString
  }

  protected def writePublishModuleConfig(ps: PrintStream, config: PublishModuleConfig): Unit = {
    import config.*
    if (pomSettings != null)
      ps.println()
      ps.println(s"def pomSettings = ${renderPomSettings(pomSettings)}")
    if (publishVersion != null)
      ps.println()
      ps.println(s"def publishVersion = ${literalize(publishVersion)}")
    versionScheme.foreach: v =>
      ps.println()
      ps.println(s"def versionScheme = Some($v)")
  }

  protected def renderPomSettings(pomSettings: PublishModuleConfig.PomSettings): String = {
    import pomSettings.*
    s"PomSettings(${
        literalize(description)
      }, ${
        literalize(organization)
      }, ${
        literalize(url)
      }, ${
        licenses.map(renderLicense).mkString("Seq(", ", ", ")")
      }, ${
        renderVersionControl(versionControl)
      }, ${
        developers.map(renderDeveloper).mkString("Seq(", ", ", ")")
      })"
  }

  protected def renderLicense(license: PublishModuleConfig.License): String = {
    import license.*
    s"License(${
        literalize(id)
      }, ${
        literalize(name)
      }, ${
        literalize(url)
      }, ${
        isOsiApproved
      }, ${
        isFsfLibre
      }, ${
        literalize(distribution)
      })"
  }

  protected def renderVersionControl(versionControl: PublishModuleConfig.VersionControl): String = {
    import versionControl.*
    s"VersionControl(${
        browsableRepository.fold("None")(v => s"Some(${literalize(v)})")
      }, ${
        connection.fold("None")(v => s"Some(${literalize(v)})")
      }, ${
        developerConnection.fold("None")(v => s"Some(${literalize(v)})")
      }, ${
        tag.fold("None")(v => s"Some(${literalize(v)})")
      })"
  }

  protected def renderDeveloper(developer: PublishModuleConfig.Developer): String = {
    import developer.*
    s"Developer(${
        literalize(id)
      }, ${
        literalize(name)
      }, ${
        literalize(url)
      }, ${
        organization.fold("None")(v => s"Some(${literalize(v)})")
      }, ${
        organizationUrl.fold("None")(v => s"Some(${literalize(v)})")
      })"
  }

  protected def writeScalaModuleConfig(ps: PrintStream, config: ScalaModuleConfig): Unit = {
    import config.*
    if (scalaVersion != null)
      ps.println(s"def scalaVersion = ${literalize(scalaVersion)}")
    if (scalacOptions.nonEmpty)
      ps.println(scalacOptions.map(literalize(_))
        .mkString("def scalacOptions = super.scalacOptions() ++ Seq(", ", ", ")"))
    if (scalacPluginMvnDeps.nonEmpty)
      ps.println(scalacPluginMvnDeps
        .mkString("def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(", ", ", ")"))
  }

  protected def writeScalaJSModuleConfig(ps: PrintStream, config: ScalaJSModuleConfig): Unit = {
    import config.*
    if (scalaJSVersion != null)
      ps.println(s"def scalaJSVersion = ${literalize(scalaJSVersion)}")
  }

  protected def writeScalaNativeModuleConfig(
      ps: PrintStream,
      config: ScalaNativeModuleConfig
  ): Unit = {
    import config.*
    if (scalaNativeVersion != null)
      ps.println(s"def scalaNativeVersion = ${literalize(scalaNativeVersion)}")
  }

  protected def writeDepsObject(ps: PrintStream, deps: DepsObject): Unit = {
    import deps.*
    ps.println()
    ps.println(s"object $name {")
    depNames.toSeq.sortBy(_._2).foreach: (dep, name) =>
      ps.println(s"val $name = $dep")
    ps.println('}')
  }
}
