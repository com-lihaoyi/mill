package mill.main.buildgen

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.internal.Util.backtickWrap
import pprint.Util.literalize

import scala.reflect.TypeTest
trait BuildWriter {

  final def writeFiles(build: BuildRepr): Unit = {
    import build.*
    val root +: nested = packages.iterator.toSeq: @unchecked
    os.write(os.pwd / rootBuildFileNames.get(0), renderRootPackage(root, baseTraits, depsObject))
    nested.foreach: pkg =>
      os.write(os.pwd / pkg.root.segments / nestedBuildFileNames.get(0), renderNestedPackage(pkg))
  }

  def renderRootPackage(
      pkg: Tree[ModuleRepr],
      baseTraits: Seq[BaseTrait],
      depsObject: Option[DepsObject]
  ) = {
    import pkg.*
    s"""package $rootModuleAlias
       |
       |${renderImports(pkg)}
       |
       |${renderModuleDeclaration("package", root)} {
       |
       |  ${renderModuleConfigs(root.configs, root.crossConfigs)}
       |
       |  ${root.testModule.fold("")(renderTestModule)}
       |
       |  ${renderLines(children.iterator.map(renderModuleSubtree))}
       |  
       |  ${renderLines(baseTraits.iterator.map(renderBaseTrait))}
       |
       |  ${depsObject.fold("")(renderDepsObject)}
       |}""".stripMargin
  }

  def renderNestedPackage(pkg: Tree[ModuleRepr]) = {
    import pkg.*
    s"""package $rootModuleAlias.${root.segments.map(backtickWrap).mkString(".")}
       |
       |${renderImports(pkg)}
       |
       |${renderModuleDeclaration("package", root)} {
       |
       |  ${renderModuleConfigs(root.configs, root.crossConfigs)}
       |
       |  ${root.testModule.fold("")(renderTestModule)}
       |
       |  ${renderLines(children.iterator.map(renderModuleSubtree))}
       |}""".stripMargin
  }

  def renderImports(pkg: Tree[ModuleRepr]): String

  def renderModuleSubtree(modules: Tree[ModuleRepr]): String = {
    import modules.*
    s"""${renderModuleDeclaration(root.segments.last, root)} {
       |
       |  ${renderModuleConfigs(root.configs, root.crossConfigs)}
       |
       |  ${root.testModule.fold("")(renderTestModule)}
       |
       |  ${renderLines(children.iterator.map(renderModuleSubtree))}
       |}""".stripMargin
  }

  def renderCrossModuleDeclaration(name: String, module: ModuleRepr): String

  def renderModuleDeclaration(name: String, module: ModuleRepr) = {
    import module.*
    if (crossConfigs.isEmpty)
      s"object ${backtickWrap(name)} ${renderExtendsClause(supertypes ++ mixins)}"
    else renderCrossModuleDeclaration(name, module)
  }

  def renderExtendsClause(supertypes: Seq[String]) = {
    if (supertypes.isEmpty) ""
    else
      val head +: tail = supertypes: @unchecked
      s" extends $head" + tail.map(" with " + _).mkString
  }

  def renderTestModule(module: TestModuleRepr) = {
    import module.*
    s"""object $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleConfigs(configs, crossConfigs)}
       |}""".stripMargin
  }

  def renderBaseTrait(baseTrait: BaseTrait) = {
    import baseTrait.*
    s"""trait $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleConfigs(configs, crossConfigs)}
       |}""".stripMargin
  }

  def renderDepsObject(depsObject: DepsObject) = {
    import depsObject.*
    s"""object $name {
       |
       |  ${renderLines(refsByDep.toSeq.sortBy(_._2).iterator.map((dep, ref) =>
        s"val ${backtickWrap(ref)} = $dep"
      ))}
       |}""".stripMargin
  }

  def renderModuleConfigs(
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ) = {
    def collect[T <: ModuleConfig](using T: TypeTest[ModuleConfig, T]) = crossConfigs.collect:
      case (k, T(t)) => (k, t)
    renderLines(configs.iterator.map:
      case config: CoursierModuleConfig => renderCoursierModuleConfig(config, collect)
      case config: JavaModuleConfig => renderJavaModuleConfig(config, collect)
      case config: PublishModuleConfig => renderPublishModuleConfig(config, collect)
      case config: ScalaModuleConfig => renderScalaModuleConfig(config, collect)
      case config: ScalaJSModuleConfig => renderScalaJSModuleConfig(config, collect)
      case config: ScalaNativeModuleConfig => renderScalaNativeModuleConfig(config, collect))
  }

  def renderCoursierModuleConfig(
      config: CoursierModuleConfig,
      crossConfigs: Seq[(String, CoursierModuleConfig)]
  ) = {
    def get[A](f: CoursierModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderRepositories(repositories, get(_.repositories))}
       |""".stripMargin
  }

  def renderJavaModuleConfig(
      config: JavaModuleConfig,
      crossConfigs: Seq[(String, JavaModuleConfig)]
  ) = {
    def get[A](f: JavaModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderMandatoryMvnDeps(mandatoryMvnDeps, get(_.mandatoryMvnDeps))}
       |
       |${renderMvnDeps(mvnDeps, get(_.mvnDeps))}
       |
       |${renderCompileMvnDeps(compileMvnDeps, get(_.compileMvnDeps))}
       |
       |${renderRunMvnDeps(runMvnDeps, get(_.runMvnDeps))}
       |
       |${renderModuleDeps(moduleDeps, get(_.moduleDeps))}
       |
       |${renderCompileModuleDeps(compileModuleDeps, get(_.compileModuleDeps))}
       |
       |${renderRunModuleDeps(runModuleDeps, get(_.runModuleDeps))}
       |
       |${renderJavacOptions(javacOptions, get(_.javacOptions))}
       |""".stripMargin
  }

  def renderPublishModuleConfig(
      config: PublishModuleConfig,
      crossConfigs: Seq[(String, PublishModuleConfig)]
  ) = {
    def get[A](f: PublishModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderPomSettings(pomSettings, get(_.pomSettings))}
       |
       |${renderPublishVersion(publishVersion, get(_.publishVersion))}
       |
       |${renderVersionScheme(versionScheme, get(_.versionScheme))}
       |""".stripMargin
  }

  def renderScalaModuleConfig(
      config: ScalaModuleConfig,
      crossConfigs: Seq[(String, ScalaModuleConfig)]
  ) = {
    def get[A](f: ScalaModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderScalaVersion(scalaVersion, get(_.scalaVersion))}
       |
       |${renderScalacOptions(scalacOptions, get(_.scalacOptions))}
       |
       |${renderScalacPluginMvnDeps(scalacPluginMvnDeps, get(_.scalacPluginMvnDeps))}
       |""".stripMargin
  }

  def renderScalaJSModuleConfig(
      config: ScalaJSModuleConfig,
      crossConfigs: Seq[(String, ScalaJSModuleConfig)]
  ) = {
    def get[A](f: ScalaJSModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderScalaJSVersion(scalaJSVersion, get(_.scalaJSVersion))}
       |""".stripMargin
  }

  def renderScalaNativeModuleConfig(
      config: ScalaNativeModuleConfig,
      crossConfigs: Seq[(String, ScalaNativeModuleConfig)]
  ) = {
    def get[A](f: ScalaNativeModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderScalaNativeVersion(scalaNativeVersion, get(_.scalaNativeVersion))}
       |""".stripMargin
  }

  def renderTask[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String,
      defaultValue: String = "???"
  ): String = {
    val crossValues0 = crossValues.collect {
      case (k, v) if v != null => (k, v)
    }
    if (value == null && crossValues0.isEmpty) ""
    else
      val value0 = if (value == null) defaultValue else renderValue(value)
      s"def $name = " +
        (if (crossValues0.isEmpty) value0 else renderCrossValue(crossValues0, renderValue, value0))
  }

  def renderTaskOption[A](
      name: String,
      value: Option[A],
      crossValues: Seq[(String, Option[A])],
      renderValue: A => String,
      defaultValue: String = "None"
  ): String = {
    val crossValues0 = crossValues.collect {
      case (k, Some(v)) => (k, v)
    }
    if (value.isEmpty && crossValues0.isEmpty) ""
    else
      val value0 = value.fold(defaultValue)(renderValue)
      s"def $name = " +
        (if (crossValues0.isEmpty) value0 else renderCrossValue(crossValues0, renderValue, value0))
  }

  def renderMemberSeq[A](
      name: String,
      values: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String,
      invokeSuper: String = ""
  ): String =
    if (values.isEmpty && crossValues.isEmpty) ""
    else s"def $name = super.$name$invokeSuper" +
      (if (values.isEmpty) "" else s" ++ Seq(${values.iterator.map(renderValue).mkString(", ")})") +
      renderAppendedCrossValues(crossValues, renderValue)

  def renderTaskSeq[A](
      name: String,
      values: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ): String =
    renderMemberSeq(name, values, crossValues, renderValue, "()")

  def renderCrossValue[A](
      crossValues: Seq[(String, A)],
      renderValue: A => String,
      defaultValue: String
  ): String

  def renderAppendedCrossValues[A](
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ): String

  def renderRepositories(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("repositories", values, crossValues, literalize(_))

  def renderMandatoryMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("mandatoryMvnDeps", values, crossValues, identity)

  def renderMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("mvnDeps", values, crossValues, identity)

  def renderCompileMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("compileMvnDeps", values, crossValues, identity)

  def renderRunMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("runMvnDeps", values, crossValues, identity)

  def renderModuleDep(dep: JavaModuleConfig.ModuleDep) = {
    import dep.*
    rootModuleAlias + crossArgs.getOrElse(-1, "") + segments.indices
      .map(i => "." + backtickWrap(segments(i)) + crossArgs.getOrElse(i, ""))
      .mkString
  }

  def renderModuleDeps(
      values: Seq[JavaModuleConfig.ModuleDep],
      crossValues: Seq[(String, Seq[JavaModuleConfig.ModuleDep])]
  ) = renderMemberSeq("moduleDeps", values, crossValues, renderModuleDep)

  def renderCompileModuleDeps(
      values: Seq[JavaModuleConfig.ModuleDep],
      crossValues: Seq[(String, Seq[JavaModuleConfig.ModuleDep])]
  ) = renderMemberSeq("compileModuleDeps", values, crossValues, renderModuleDep)

  def renderRunModuleDeps(
      values: Seq[JavaModuleConfig.ModuleDep],
      crossValues: Seq[(String, Seq[JavaModuleConfig.ModuleDep])]
  ) = renderMemberSeq("runModuleDeps", values, crossValues, renderModuleDep)

  def renderJavacOptions(
      values: Seq[String],
      crossValues: Seq[(String, Seq[String])]
  ) = renderTaskSeq("javacOptions", values, crossValues, literalize(_))

  def renderPomSettings(
      value: PublishModuleConfig.PomSettings,
      crossValues: Seq[(String, PublishModuleConfig.PomSettings)]
  ): String = renderTask("pomSettings", value, crossValues, renderPomSettings)

  def renderPomSettings(value: PublishModuleConfig.PomSettings): String = {
    import value.*
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

  def renderLicense(value: PublishModuleConfig.License) = {
    import value.*
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

  def renderVersionControl(value: PublishModuleConfig.VersionControl) = {
    import value.*
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

  def renderDeveloper(value: PublishModuleConfig.Developer) = {
    import value.*
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

  def renderPublishVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("publishVersion", value, crossValues, literalize(_))

  def renderVersionScheme(value: Option[String], crossValues: Seq[(String, Option[String])]) =
    renderTaskOption("versionScheme", value, crossValues, v => s"Some($v)")

  def renderScalaVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("scalaVersion", value, crossValues, literalize(_))

  def renderScalacOptions(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("scalacOptions", values, crossValues, literalize(_))

  def renderScalacPluginMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("scalacPluginMvnDeps", values, crossValues, identity)

  def renderScalaJSVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("scalaJSVersion", value, crossValues, literalize(_))

  def renderScalaNativeVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("scalaNativeVersion", value, crossValues, literalize(_))

  def renderLines(values: IterableOnce[String]) = values.iterator.mkString(
    """
      |""".stripMargin
  )
}
