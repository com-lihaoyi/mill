package mill.main.buildgen

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.constants.OutFiles.millBuild
import mill.internal.Util.backtickWrap
import pprint.Util.literalize

import scala.reflect.TypeTest

class BuildWriter(build: BuildRepr, renderCrossValueInTask: String = "crossValue") {

  def writeFiles(): Unit = {
    import build.*

    meta.foreach: meta =>
      val sub = os.sub / millBuild / rootBuildFileNames.get(0)
      println(s"writing Mill meta-build file to $sub")
      os.write(os.pwd / sub, renderMetaBuildRoot, createFolders = true)
      import meta.*
      val sub1 = os.sub / millBuild / "src" / s"${depsObject.name}.scala"
      println(s"writing Mill meta-build file to $sub1")
      os.write(os.pwd / sub1, renderDepsObject(packageName, depsObject), createFolders = true)
      baseTraits.foreach: baseTrait =>
        val sub = os.sub / millBuild / "src" / s"${baseTrait.name}.scala"
        println(s"writing Mill meta-build file to $sub")
        os.write(os.pwd / sub, renderBaseTrait(packageName, baseTrait))

    val root +: nested = packages.iterator.toSeq: @unchecked
    val sub = os.sub / rootBuildFileNames.get(0)
    println(s"writing Mill build file to $sub")
    os.write(os.pwd / rootBuildFileNames.get(0), renderPackage(root))
    nested.foreach: pkg =>
      val sub = os.sub / pkg.root.segments / nestedBuildFileNames.get(0)
      println(s"writing Mill build file to $sub")
      os.write(os.pwd / sub, renderPackage(pkg))
  }

  def renderMetaBuildRoot = {
    s"""package $rootModuleAlias
       |
       |import mill.meta.MillBuildRootModule
       |
       |object `package` extends MillBuildRootModule
       |""".stripMargin
  }

  def renderDepsObject(packageName: String, depsObject: DepsObject) = {
    import depsObject.*
    s"""package $packageName
       |
       |import mill.javalib._
       |
       |object $name {
       |
       |${renderLines(refsByDep.toSeq.sortBy(_._2).iterator.map((dep, ref) =>
        s"val ${backtickWrap(ref)} = $dep"
      ))}
       |}""".stripMargin
  }

  def renderBaseTrait(packageName: String, baseTrait: BaseTrait) = {
    import baseTrait.*
    s"""package $packageName
       |
       |${renderImports(baseTrait)}
       |
       |trait $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleConfigs(configs, crossConfigs)}
       |}""".stripMargin
  }

  def renderImports(baseTrait: BaseTrait): String = {
    val b = Set.newBuilder[String]
    import baseTrait.*
    (supertypes ++ mixins).foreach:
      case "MavenModule" => b += "mill.javalib"
      case "PublishModule" => b += "mill.javalib" += "mill.javalib.publish"
      case "ErrorProneModule" => b += "mill.javalib" += "mill.javalib.errorprone"
      case "ScalaModule" | "SbtModule" | "CrossSbtModule" | "CrossSbtPlatformModule" =>
        b += "mill.scalalib"
      case s if s.startsWith("SbtPlatformModule") => b += "mill.scalalib"
      case "ScalaJSModule" => b += "mill.scalalib" += "mill.scalajslib"
      case "ScalaNativeModule" => b += "mill.scalalib" += "mill.scalanativelib"
      case _ =>

    configs.foreach:
      case _: CoursierModuleConfig => b += "mill.javalib"
      case _: JavaHomeModuleConfig => b += "mill.javalib"
      case _: JavaModuleConfig => b += "mill.javalib"
      case _: PublishModuleConfig => b += "mill.javalib" += "mill.javalib.publish"
      case _: ErrorProneModuleConfig => b += "mill.javalib" += "mill.javalib.errorprone"
      case _: ScalaModuleConfig => b += "mill.scalalib"
      case _: ScalaJSModuleConfig => b += "mill.scalalib" += "mill.scalajslib"
      case _: ScalaNativeModuleConfig => b += "mill.scalalib" += "mill.scalanativelib"

    renderLines(b.result().toSeq.sorted.iterator.map(s => s"import $s._"))
  }

  def renderPackage(pkg: Tree[ModuleRepr]) = {
    import pkg.*
    s"""package ${(rootModuleAlias +: root.segments.map(backtickWrap)).mkString(".")}
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

  def renderImports(pkg: Tree[ModuleRepr]): String = {
    val b = Set.newBuilder[String]
    b ++= build.meta.map(_.packageName)
    for module <- pkg.iterator do
      if (module.supertypes == Seq("Module")) b += "mill.api"
      if (module.testModule.exists(_.mixins.exists(_.startsWith("TestModule"))))
        b += "mill.javalib"
      module.configs.foreach:
        case _: CoursierModuleConfig => b += "mill.javalib"
        case _: JavaHomeModuleConfig => b += "mill.javalib"
        case _: JavaModuleConfig => b += "mill.javalib"
        case _: PublishModuleConfig => b += "mill.javalib" += "mill.javalib.publish"
        case _: ErrorProneModuleConfig => b += "mill.javalib" += "mill.javalib.errorprone"
        case _: ScalaModuleConfig => b += "mill.scalalib"
        case _: ScalaJSModuleConfig => b += "mill.scalalib" += "mill.scalajslib"
        case _: ScalaNativeModuleConfig => b += "mill.scalalib" += "mill.scalanativelib"

    renderLines(b.result().toSeq.sorted.iterator.map(s => s"import $s._"))
  }

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

  def renderModuleDeclaration(name: String, module: ModuleRepr) = {
    import module.*
    if (crossConfigs.isEmpty)
      s"object ${backtickWrap(name)} ${renderExtendsClause(supertypes ++ mixins)}"
    else
      val crossTraitName = segments.lastOption.getOrElse(os.pwd.last).split("\\W").map(_.capitalize)
        .mkString("", "", "Module")
      val crossTraitExtends = crossConfigs.map((v, _) => literalize(v))
        .mkString(s"extends Cross[$crossTraitName](", ", ", ")")
      s"""object ${backtickWrap(name)} $crossTraitExtends
         |trait $crossTraitName ${renderExtendsClause(supertypes ++ mixins)}""".stripMargin
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

  def renderModuleConfigs(
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ) = {
    def collect[T <: ModuleConfig](using T: TypeTest[ModuleConfig, T]) = crossConfigs.collect:
      case (k, T(t)) => (k, t)
    renderLines(configs.iterator.map:
      case config: CoursierModuleConfig => renderCoursierModuleConfig(config, collect)
      case config: JavaHomeModuleConfig => renderJavaHomeModuleConfig(config, collect)
      case config: JavaModuleConfig => renderJavaModuleConfig(config, collect)
      case config: PublishModuleConfig => renderPublishModuleConfig(config, collect)
      case config: ErrorProneModuleConfig => renderErrorProneModuleConfig(config, collect)
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

  def renderJavaHomeModuleConfig(
      config: JavaHomeModuleConfig,
      crossConfigs: Seq[(String, JavaHomeModuleConfig)]
  ) = {
    def get[A](f: JavaHomeModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderJvmId(jvmId, get(_.jvmId))}
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
       |${renderBomMvnDeps(bomMvnDeps, get(_.bomMvnDeps))}
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
    s"""${renderPomPackagingType(pomPackagingType, get(_.pomPackagingType))}
       |
       |${renderPomParentProject(pomParentProject, get(_.pomParentProject))}
       |
       |${renderPomSettings(pomSettings, get(_.pomSettings))}
       |
       |${renderPublishVersion(publishVersion, get(_.publishVersion))}
       |
       |${renderVersionScheme(versionScheme, get(_.versionScheme))}
       |
       |${renderArtifactMetadata(artifactMetadata, get(_.artifactMetadata))}
       |
       |${renderPublishProperties(publishProperties, get(_.publishProperties))}
       |""".stripMargin
  }

  def renderErrorProneModuleConfig(
      config: ErrorProneModuleConfig,
      crossConfigs: Seq[(String, ErrorProneModuleConfig)]
  ) = {
    def get[A](f: ErrorProneModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderErrorProneOptions(errorProneOptions, get(_.errorProneOptions))}
       |
       |${renderErrorProneJavacEnableOptions(
        errorProneJavacEnableOptions,
        get(_.errorProneJavacEnableOptions)
      )}
       |
       |${renderErrorProneDeps(errorProneDeps, get(_.errorProneDeps))}
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

  def renderRepositories(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("repositories", values, crossValues, literalize(_))

  def renderJvmId(value: String, crossValues: Seq[(String, String)]) =
    renderTask("jvmId", value, crossValues, literalize(_))

  def renderMandatoryMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("mandatoryMvnDeps", values, crossValues, identity)

  def renderMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("mvnDeps", values, crossValues, identity)

  def renderCompileMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("compileMvnDeps", values, crossValues, identity)

  def renderRunMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("runMvnDeps", values, crossValues, identity)

  def renderBomMvnDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("bomMvnDeps", values, crossValues, identity)

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

  def renderPomPackagingType(value: String, crossValues: Seq[(String, String)]) =
    renderMember("pomPackagingType", value, crossValues, literalize(_))

  def renderPomParentProject(
      value: Option[PublishModuleConfig.Artifact],
      crossValues: Seq[(String, Option[PublishModuleConfig.Artifact])]
  ) = renderTaskOption("pomParentProject", value, crossValues, renderArtifact)

  def renderPomSettings(
      value: PublishModuleConfig.PomSettings,
      crossValues: Seq[(String, PublishModuleConfig.PomSettings)]
  ): String = renderTask("pomSettings", value, crossValues, renderPomSettings)

  def renderPublishVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("publishVersion", value, crossValues, literalize(_))

  def renderVersionScheme(value: Option[String], crossValues: Seq[(String, Option[String])]) =
    renderTaskOption("versionScheme", value, crossValues, identity)

  def renderArtifactMetadata(
      value: PublishModuleConfig.Artifact,
      crossValues: Seq[(String, PublishModuleConfig.Artifact)]
  ) = renderTask("artifactMetadata", value, crossValues, renderArtifact)

  def renderPublishProperties(
      values: Map[String, String],
      crossValues: Seq[(String, Map[String, String])]
  ) = renderTaskMap("publishProperties", values, crossValues, literalize(_), literalize(_))

  def renderErrorProneOptions(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("errorProneOptions", values, crossValues, literalize(_))

  def renderErrorProneJavacEnableOptions(
      values: Seq[String],
      crossValues: Seq[(String, Seq[String])]
  ) =
    renderTaskSeq("errorProneJavacEnableOptions", values, crossValues, literalize(_))

  def renderErrorProneDeps(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("errorProneDeps", values, crossValues, identity)

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

  def renderModuleDep(dep: JavaModuleConfig.ModuleDep) = {
    import dep.*
    rootModuleAlias + crossArgs.getOrElse(-1, "") + segments.indices
      .map(i => "." + backtickWrap(segments(i)) + crossArgs.getOrElse(i, ""))
      .mkString
  }

  def renderPomSettings(value: PublishModuleConfig.PomSettings): String = {
    import value.*
    s"PomSettings(${
        literalize(if (description == null) "" else description)
      }, ${
        literalize(if (organization == null) "" else organization)
      }, ${
        literalize(if (url == null) "" else url)
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
        literalize(if (id == null) "" else id)
      }, ${
        literalize(if (name == null) "" else name)
      }, ${
        literalize(if (url == null) "" else url)
      }, ${
        isOsiApproved
      }, ${
        isFsfLibre
      }, ${
        literalize(if (distribution == null) "" else distribution)
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
        literalize(if (id == null) "" else id)
      }, ${
        literalize(if (name == null) "" else name)
      }, ${
        literalize(if (url == null) "" else url)
      }, ${
        organization.fold("None")(v => s"Some(${literalize(v)})")
      }, ${
        organizationUrl.fold("None")(v => s"Some(${literalize(v)})")
      })"
  }

  def renderArtifact(value: PublishModuleConfig.Artifact) = {
    import value.*
    s"Artifact(${literalize(group)}, ${literalize(id)}, ${literalize(version)})"
  }

  def renderLines(values: IterableOnce[String]) = values.iterator.mkString(
    """
      |""".stripMargin
  )

  def renderMember[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect:
      case (k, v) if v != null => (k, v)
    if (value == null && crossValues0.isEmpty) ""
    else
      val value0 = if (value == null) s"super.$name" else renderValue(value)
      s"def $name = " +
        (if (crossValues0.isEmpty) value0
         else
           s"""crossValue match {
              |${renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((v, crosses) =>
               s"  case ${crosses.map(literalize(_)).mkString(" | ")} => ${renderValue(v)}"
             ))}
              |  case _ => $value0
              |}""".stripMargin)
  }

  def renderMemberSeq[A](
      name: String,
      values: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ) =
    if (values.isEmpty && crossValues.isEmpty) ""
    else s"def $name = super.$name" +
      (if (values.isEmpty) "" else s" ++ Seq(${values.iterator.map(renderValue).mkString(", ")})") +
      (if (crossValues.isEmpty) ""
       else
         s""" ++ (crossValue match {
            |${
             renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((v, crosses) =>
               s"  case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(${v.map(renderValue).mkString(", ")})"
             ))
           }
            |  case _ => Nil
            |}""".stripMargin)

  def renderTask[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect:
      case (k, v) if v != null => (k, renderValue(v))
    if (value == null && crossValues0.isEmpty) ""
    else
      val value0 = if (value == null) s"super.$name()" else renderValue(value)
      s"def $name = " +
        (if (crossValues0.isEmpty) value0
         else
           s"""$renderCrossValueInTask match {
              |${renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((v, crosses) =>
               s"  case ${crosses.map(literalize(_)).mkString(" | ")} => $v"
             ))}
              |  case _ => $value0
              |}""".stripMargin)
  }

  def renderTaskOption[A](
      name: String,
      value: Option[A],
      crossValues: Seq[(String, Option[A])],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect:
      case (k, Some(v)) if v != null => (k, s"Some(${renderValue(v)})")
    if (value.isEmpty && crossValues0.isEmpty) ""
    else
      val value0 = value.fold("None")(value => s"Some(${renderValue(value)})")
      s"def $name = " +
        (if (crossValues0.isEmpty) value0
         else
           s"""$renderCrossValueInTask match {
              |${renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((v, crosses) =>
               s"  case ${crosses.map(literalize(_)).mkString(" | ")} => $v}"
             ))}
              |  case _ => $value0
              |}""".stripMargin)
  }

  def renderTaskMap[K, V](
      name: String,
      values: Map[K, V],
      crossValues: Seq[(String, Map[K, V])],
      renderKey: K => String,
      renderValue: V => String
  ) =
    if (values.isEmpty && crossValues.isEmpty) ""
    else s"def $name = super.$name()" +
      (if (values.isEmpty) ""
       else
         s" ++ Map(${values.map((k, v) => s"(${renderKey(k)}, ${renderValue(v)})").mkString(", ")})") +
      (if (crossValues.isEmpty) ""
       else
         s""" ++ ($renderCrossValueInTask match {
            |${renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((v, crosses) =>
             s"  case ${crosses.map(literalize(_)).mkString(" | ")} => Map(${v.map((k, v) =>
                 s"(${renderKey(k)}, ${renderValue(v)})"
               ).mkString(", ")})"
           ))}
            |  case _ => Map()
            |})""".stripMargin)

  def renderTaskSeq[A](
      name: String,
      values: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ) =
    if (values.isEmpty && crossValues.isEmpty) ""
    else s"def $name = super.$name()" +
      (if (values.isEmpty) "" else s" ++ Seq(${values.iterator.map(renderValue).mkString(", ")})") +
      (if (crossValues.isEmpty) ""
       else
         s""" ++ ($renderCrossValueInTask match {
            |${
             renderLines(crossValues.groupMap(_._2)(_._1).iterator.map((v, crosses) =>
               s"  case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(${v.map(renderValue).mkString(", ")})"
             ))
           }
            |  case _ => Nil
            |}""".stripMargin)
}
