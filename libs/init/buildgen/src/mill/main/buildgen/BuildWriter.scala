package mill.main.buildgen

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.constants.OutFiles.millBuild
import mill.init.Util.buildFiles
import mill.internal.Util.backtickWrap
import pprint.Util.literalize

import scala.collection.mutable
import scala.math.Ordered.orderingToOrdered
import scala.reflect.TypeTest

class BuildWriter(build: BuildRepr, renderCrossValueInTask: String = "crossValue") {

  private val refsByMvnDep: mutable.Map[ModuleConfig.MvnDep, String] = mutable.Map.empty

  def writeFiles(): Unit = {
    val toRemove = buildFiles(os.pwd)
    if (toRemove.nonEmpty) {
      println("removing existing Mill build files")
      toRemove.foreach(os.remove.apply)
    }

    import build.*
    val root +: nested = packages.iterator.toSeq: @unchecked
    val rootBuildFile = os.sub / rootBuildFileNames.get(0)
    println(s"writing Mill build file to $rootBuildFile")
    os.write(
      os.pwd / rootBuildFileNames.get(0),
      s"""$renderBuildHeader
         |${renderPackage(root)}""".stripMargin
    )
    nested.foreach { pkg =>
      val nestedBuildFile = os.sub / pkg.root.segments / nestedBuildFileNames.get(0)
      println(s"writing Mill build file to $nestedBuildFile")
      os.write(os.pwd / nestedBuildFile, renderPackage(pkg))
    }

    metaBuild.foreach { metaBuild =>
      val rootDir = os.sub / millBuild
      val srcDir = rootDir / "src"
      os.makeDir.all(os.pwd / srcDir)
      val rootBuildFile = rootDir / rootBuildFileNames.get(0)
      println(s"writing Mill meta-build file to $rootBuildFile")
      os.write(os.pwd / rootBuildFile, renderMetaBuildRoot)
      import metaBuild.*
      baseTraits.foreach { baseTrait =>
        val baseTraitFile = srcDir / s"${baseTrait.name}.scala"
        println(s"writing Mill meta-build file to $baseTraitFile")
        os.write(os.pwd / baseTraitFile, renderBaseTrait(packageName, baseTrait))
      }
      // must be written last due to mutable state
      val depsObjectFile = srcDir / s"$depsObjectName.scala"
      println(s"writing Mill meta-build file to $depsObjectFile")
      os.write(os.pwd / depsObjectFile, renderDepsObject(packageName, depsObjectName))
    }

    println(
      s"NOTE: It is recommended to set mill-jvm-version in the build header in $rootBuildFile."
    )
  }

  def renderBuildHeader = {
    import build.*
    s"""//| mill-version: $millVersion
       |//| mill-jvm-opts: [${millJvmOpts.iterator.map(literalize(_)).mkString(", ")}]""".stripMargin
  }

  def renderMetaBuildRoot = {
    s"""package $rootModuleAlias
       |
       |import mill.meta.MillBuildRootModule
       |
       |object `package` extends MillBuildRootModule
       |""".stripMargin
  }

  def renderBaseTrait(packageName: String, baseTrait: MetaBuildRepr.BaseTrait) = {
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

  def renderImports(baseTrait: MetaBuildRepr.BaseTrait): String = {
    val wildcards = mutable.SortedSet.empty[String]
    import baseTrait.*
    configs.foreach(addImports(wildcards, _))

    renderLines(wildcards.iterator.map(s => s"import $s._"))
  }

  def renderDepsObject(packageName: String, name: String) = {
    s"""package $packageName
       |
       |import mill.javalib._
       |
       |object $name {
       |
       |${renderLines(refsByMvnDep.toSeq.sortBy(_._2).iterator.map((dep, ref) =>
        s"val ${backtickWrap(ref)} = ${renderMvnDep0(dep)}"
      ))}
       |}""".stripMargin
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
    val wildcards = mutable.SortedSet.empty[String]
    for module <- pkg.iterator do {
      import module.*
      if (supertypes.contains("Module") || crossConfigs.nonEmpty) wildcards += "mill"
      if (testModule.nonEmpty) wildcards += "mill.javalib"
      if (configs.nonEmpty) wildcards ++= build.metaBuild.map(_.packageName)
      configs.foreach(addImports(wildcards, _))
    }

    renderLines(wildcards.iterator.map(s => s"import $s._"))
  }

  private def addImports(wildcards: mutable.SortedSet[String], config: ModuleConfig): Unit = {
    config match {
      case _: ScalaModuleConfig => wildcards += "mill.scalalib"
      case _: SbtPlatformModuleConfig => wildcards += "mill.scalalib"
      case _: ScalaJSModuleConfig => wildcards += "mill.scalalib" += "mill.scalajslib"
      case _: ScalaNativeModuleConfig => wildcards += "mill.scalalib" += "mill.scalanativelib"
      case _: PublishModuleConfig => wildcards += "mill.javalib" += "mill.javalib.publish"
      case _: ErrorProneModuleConfig => wildcards += "mill.javalib" += "mill.javalib.errorprone"
      case _ => wildcards += "mill.javalib"
    }
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
      val crossModuleExtends = crossConfigs.map((v, _) => literalize(v))
        .mkString(s"extends Cross[$crossTraitName](", ", ", ")")
      s"""object ${backtickWrap(name)} $crossModuleExtends
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
       |
       |  ${if (testParallelism) "" else "def testParallelism = false"}
       |
       |  ${if (testSandboxWorkingDir) "" else "def testSandboxWorkingDir = false"}
       |}""".stripMargin
  }

  def renderModuleConfigs(
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ) = {
    def crossConfig[T <: ModuleConfig](using T: TypeTest[ModuleConfig, T]) =
      crossConfigs.flatMap((k, xs) =>
        xs.collectFirst {
          case t: T => (k, t)
        }
      )
    renderLines(configs.iterator.map {
      case config: CoursierModuleConfig => renderCoursierModuleConfig(config, crossConfig)
      case config: JavaHomeModuleConfig => renderJavaHomeModuleConfig(config, crossConfig)
      case config: RunModuleConfig => renderRunModuleConfig(config, crossConfig)
      case config: JavaModuleConfig => renderJavaModuleConfig(config, crossConfig)
      case config: PublishModuleConfig => renderPublishModuleConfig(config, crossConfig)
      case config: ErrorProneModuleConfig => renderErrorProneModuleConfig(config, crossConfig)
      case config: ScalaModuleConfig => renderScalaModuleConfig(config, crossConfig)
      case config: ScalaJSModuleConfig => renderScalaJSModuleConfig(config, crossConfig)
      case config: ScalaNativeModuleConfig => renderScalaNativeModuleConfig(config, crossConfig)
      case config: SbtPlatformModuleConfig => renderSbtPlatformModuleConfig(config, crossConfig)
    })
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

  def renderRunModuleConfig(
      config: RunModuleConfig,
      crossConfigs: Seq[(String, RunModuleConfig)]
  ) = {
    def get[A](f: RunModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderForkWorkingDir(forkWorkingDir, get(_.forkWorkingDir))}
       |""".stripMargin
  }

  def renderJavaModuleConfig(
      config: JavaModuleConfig,
      crossConfigs: Seq[(String, JavaModuleConfig)]
  ) = {
    def get[A](f: JavaModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderMvnDeps(mvnDeps, get(_.mvnDeps))}
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
       |
       |${renderArtifactName(artifactName, get(_.artifactName))}
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
       |${renderPublishProperties(publishProperties, get(_.publishProperties))}
       |""".stripMargin
  }

  def renderErrorProneModuleConfig(
      config: ErrorProneModuleConfig,
      crossConfigs: Seq[(String, ErrorProneModuleConfig)]
  ) = {
    def get[A](f: ErrorProneModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderErrorProneVersion(errorProneVersion, get(_.errorProneVersion))}
       |
       |${renderErrorProneOptions(errorProneOptions, get(_.errorProneOptions))}
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

  def renderSbtPlatformModuleConfig(
      config: SbtPlatformModuleConfig,
      crossConfigs: Seq[(String, SbtPlatformModuleConfig)]
  ) = {
    def get[A](f: SbtPlatformModuleConfig => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    s"""${renderSourcesRootFolders(sourcesRootFolders, get(_.sourcesRootFolders))}
       |""".stripMargin
  }

  def renderRepositories(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("repositories", values, crossValues, literalize(_))

  def renderJvmId(value: String, crossValues: Seq[(String, String)]) =
    renderTask("jvmId", value, crossValues, literalize(_))

  def renderForkWorkingDir(value: String, crossValues: Seq[(String, String)]) =
    renderTask("forkWorkingDir", value, crossValues, identity)

  def renderMvnDeps(
      values: Seq[ModuleConfig.MvnDep],
      crossValues: Seq[(String, Seq[ModuleConfig.MvnDep])]
  ) = renderTaskSeq("mvnDeps", values, crossValues, renderMvnDep)

  def renderCompileMvnDeps(
      values: Seq[ModuleConfig.MvnDep],
      crossValues: Seq[(String, Seq[ModuleConfig.MvnDep])]
  ) = renderTaskSeq("compileMvnDeps", values, crossValues, renderMvnDep)

  def renderRunMvnDeps(
      values: Seq[ModuleConfig.MvnDep],
      crossValues: Seq[(String, Seq[ModuleConfig.MvnDep])]
  ) = renderTaskSeq("runMvnDeps", values, crossValues, renderMvnDep)

  def renderBomMvnDeps(
      values: Seq[ModuleConfig.MvnDep],
      crossValues: Seq[(String, Seq[ModuleConfig.MvnDep])]
  ) = renderTaskSeq("bomMvnDeps", values, crossValues, renderMvnDep)

  def renderModuleDeps(
      values: Seq[ModuleConfig.ModuleDep],
      crossValues: Seq[(String, Seq[ModuleConfig.ModuleDep])]
  ) = renderMethodSeq("moduleDeps", values, crossValues, renderModuleDep)

  def renderCompileModuleDeps(
      values: Seq[ModuleConfig.ModuleDep],
      crossValues: Seq[(String, Seq[ModuleConfig.ModuleDep])]
  ) = renderMethodSeq("compileModuleDeps", values, crossValues, renderModuleDep)

  def renderRunModuleDeps(
      values: Seq[ModuleConfig.ModuleDep],
      crossValues: Seq[(String, Seq[ModuleConfig.ModuleDep])]
  ) = renderMethodSeq("runModuleDeps", values, crossValues, renderModuleDep)

  def renderJavacOptions(
      values: Seq[String],
      crossValues: Seq[(String, Seq[String])]
  ) = renderTaskSeq("javacOptions", values, crossValues, literalize(_))

  def renderArtifactName(
      value: String,
      crossValues: Seq[(String, String)]
  ) = renderTask("artifactName", value, crossValues, literalize(_))

  def renderPomPackagingType(value: String, crossValues: Seq[(String, String)]) =
    renderMethod("pomPackagingType", value, crossValues, literalize(_))

  def renderPomParentProject(
      value: Option[ModuleConfig.Artifact],
      crossValues: Seq[(String, Option[ModuleConfig.Artifact])]
  ) = renderTaskOption("pomParentProject", value, crossValues, renderArtifact)

  def renderPomSettings(
      value: ModuleConfig.PomSettings,
      crossValues: Seq[(String, ModuleConfig.PomSettings)]
  ) = renderTask("pomSettings", value, crossValues, renderPomSettings0)

  def renderPublishVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("publishVersion", value, crossValues, literalize(_))

  def renderVersionScheme(
      value: Option[String],
      crossValues: Seq[(String, Option[String])]
  ) = renderTaskOption("versionScheme", value, crossValues, renderVersionScheme0)

  def renderPublishProperties(
      values: Map[String, String],
      crossValues: Seq[(String, Map[String, String])]
  ) = renderTaskMap("publishProperties", values, crossValues, literalize(_), literalize(_))

  def renderErrorProneVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("errorProneVersion", value, crossValues, literalize(_))

  def renderErrorProneOptions(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("errorProneOptions", values, crossValues, literalize(_))

  def renderErrorProneJavacEnableOptions(
      values: Seq[String],
      crossValues: Seq[(String, Seq[String])]
  ) = renderTaskSeq("errorProneJavacEnableOptions", values, crossValues, literalize(_))

  def renderErrorProneDeps(
      values: Seq[ModuleConfig.MvnDep],
      crossValues: Seq[(String, Seq[ModuleConfig.MvnDep])]
  ) = renderTaskSeq("errorProneDeps", values, crossValues, renderMvnDep)

  def renderScalaVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("scalaVersion", value, crossValues, literalize(_))

  def renderScalacOptions(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderTaskSeq("scalacOptions", values, crossValues, literalize(_))

  def renderScalacPluginMvnDeps(
      values: Seq[ModuleConfig.MvnDep],
      crossValues: Seq[(String, Seq[ModuleConfig.MvnDep])]
  ) = renderTaskSeq("scalacPluginMvnDeps", values, crossValues, renderMvnDep)

  def renderScalaJSVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("scalaJSVersion", value, crossValues, literalize(_))

  def renderScalaNativeVersion(value: String, crossValues: Seq[(String, String)]) =
    renderTask("scalaNativeVersion", value, crossValues, literalize(_))

  def renderSourcesRootFolders(values: Seq[String], crossValues: Seq[(String, Seq[String])]) =
    renderMethodSeq("sourcesRootFolders", values, crossValues, s => s"os.sub / ${literalize(s)}")

  def renderMvnDep(dep: ModuleConfig.MvnDep) = build.metaBuild.fold(renderMvnDep0(dep)) { meta =>
    var ref = refsByMvnDep.getOrElse(dep, null)
    if (ref == null) {
      /*
        We use the dependency name as the seed for the reference. When a name collision occurs, a
        suffix is added that starts with the '#' character. This forces backticks in the final name,
        making the "duplicate" stand out visually in the output. The reference without backticks is
        saved so that it is grouped together with the "original", when sorted, on render.
        Example output:
          val catsCore = mvn"org.typelevel::cats-core:2.0.0"
          val `catsCore#0` = mvn"org.typelevel::cats-core:2.6.1"
       */
      ref = dep.name.split("\\W") match
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      if (refsByMvnDep.valuesIterator.contains(ref))
        ref += "#"
        ref += refsByMvnDep.valuesIterator.count(_.startsWith(ref))
      refsByMvnDep.put(dep, ref)
    }
    meta.depsObjectName + "." + backtickWrap(ref)
  }

  def renderMvnDep0(dep: ModuleConfig.MvnDep) = {
    import dep.*
    val sep = cross match {
      case _: ModuleConfig.CrossVersion.Full => ":::"
      case _: ModuleConfig.CrossVersion.Binary => "::"
      case _ => ":"
    }
    val nameSuffix = cross match {
      case v: ModuleConfig.CrossVersion.Constant => v.value
      case _ => ""
    }
    var suffix = version.getOrElse("") + classifier.fold("") {
      case "" => ""
      case attr => s";classifier=$attr"
    } + `type`.fold("") {
      case "" | "jar" => ""
      case attr => s";type=$attr"
    } + excludes.iterator.map {
      case (org, name) => s";exclude=$org:$name"
    }.mkString
    if (suffix.nonEmpty) {
      val sep = if (cross.platformed) "::" else ":"
      suffix = sep + suffix
    }
    s"""mvn"$organization$sep$name$nameSuffix$suffix""""
  }

  def renderModuleDep(dep: ModuleConfig.ModuleDep) = {
    import dep.*
    rootModuleAlias + crossArgs.getOrElse(-1, "") + segments.indices
      .map(i => "." + backtickWrap(segments(i)) + crossArgs.getOrElse(i, ""))
      .mkString
  }

  def renderArtifact(value: ModuleConfig.Artifact) = {
    import value.*
    s"Artifact(${literalize(group)}, ${literalize(id)}, ${literalize(version)})"
  }

  def renderPomSettings0(value: ModuleConfig.PomSettings) = {
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

  def renderLicense(value: ModuleConfig.License) = {
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

  def renderVersionControl(value: ModuleConfig.VersionControl) = {
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

  def renderDeveloper(value: ModuleConfig.Developer) = {
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

  def renderVersionScheme0(value: String) = value match {
    case "early-semver" => "VersionScheme.EarlySemVer"
    case "pvp" => "VersionScheme.PVP"
    case "semver-spec" => "VersionScheme.SemVerSpec"
    case "strict" => "VersionScheme.Strict"
  }

  def renderLines(values: IterableOnce[String]) = values.iterator.mkString(
    """
      |""".stripMargin
  )

  def renderMethod[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect:
      case (k, v) if v != null => (k, v)
    if (value == null && crossValues0.isEmpty) ""
    else {
      val value0 = if (value == null) s"super.$name" else renderValue(value)
      s"def $name = " +
        (if (crossValues0.isEmpty) value0
         else
           s"""crossValue match {
              |${
               renderLines(crossValues.groupMap(_._2)(_._1).toSeq.sortBy(_._2)
                 .map((v, crosses) =>
                   s"  case ${crosses.sorted.map(literalize(_)).mkString(" | ")} => ${renderValue(v)}"
                 ))
             }
              |  case _ => $value0
              |}""".stripMargin)
    }
  }

  def renderMethodSeq[A](
      name: String,
      values: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.filter(_._2.nonEmpty)
    if (values.isEmpty && crossValues0.isEmpty) ""
    else
      s"def $name = super.$name" +
        (if (values.isEmpty) ""
         else s" ++ Seq(${values.iterator.map(renderValue).mkString(", ")})") +
        (if (crossValues0.isEmpty) ""
         else
           s""" ++ (crossValue match {
              |${
               renderLines(crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2)
                 .map((v, crosses) =>
                   s"  case ${crosses.sorted.map(literalize(_)).mkString(" | ")} => Seq(${v.map(renderValue).mkString(", ")})"
                 ))
             }
              |  case _ => Nil
              |}""".stripMargin)
  }

  def renderTask[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect:
      case (k, v) if v != null => (k, renderValue(v))
    if (value == null && crossValues0.isEmpty) ""
    else {
      val value0 = if (value == null) s"super.$name()" else renderValue(value)
      s"def $name = " +
        (if (crossValues0.isEmpty) value0
         else
           s"""$renderCrossValueInTask match {
              |${
               renderLines(crossValues.groupMap(_._2)(_._1).toSeq.sortBy(_._2)
                 .map((v, crosses) =>
                   s"  case ${crosses.sorted.map(literalize(_)).mkString(" | ")} => $v"
                 ))
             }
              |  case _ => $value0
              |}""".stripMargin)
    }
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
    else {
      val value0 = value.fold("None")(value => s"Some(${renderValue(value)})")
      s"def $name = " +
        (if (crossValues0.isEmpty) value0
         else
           s"""$renderCrossValueInTask match {
              |${
               renderLines(crossValues.groupMap(_._2)(_._1).toSeq.sortBy(_._2)
                 .map((v, crosses) =>
                   s"  case ${crosses.sorted.map(literalize(_)).mkString(" | ")} => $v}"
                 ))
             }
              |  case _ => $value0
              |}""".stripMargin)
    }
  }

  def renderTaskMap[K, V](
      name: String,
      values: Map[K, V],
      crossValues: Seq[(String, Map[K, V])],
      renderKey: K => String,
      renderValue: V => String
  ) = {
    val crossValues0 = crossValues.filter(_._2.nonEmpty)
    if (values.isEmpty && crossValues0.isEmpty) ""
    else
      s"def $name = super.$name()" +
        (if (values.isEmpty) ""
         else
           s" ++ Map(${values.map((k, v) => s"(${renderKey(k)}, ${renderValue(v)})").mkString(", ")})") +
        (if (crossValues0.isEmpty) ""
         else
           s""" ++ ($renderCrossValueInTask match {
              |${
               renderLines(crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2)
                 .map((v, crosses) =>
                   s"  case ${crosses.sorted.map(literalize(_)).mkString(" | ")} => Map(${
                       v.toSeq.map((k, v) =>
                         s"(${renderKey(k)}, ${renderValue(v)})"
                       ).mkString(", ")
                     })"
                 ))
             }
              |  case _ => Map()
              |})""".stripMargin)
  }

  def renderTaskSeq[A](
      name: String,
      values: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.filter(_._2.nonEmpty)
    if (values.isEmpty && crossValues0.isEmpty) ""
    else
      s"def $name = super.$name()" +
        (if (values.isEmpty) ""
         else s" ++ Seq(${values.iterator.map(renderValue).mkString(", ")})") +
        (if (crossValues0.isEmpty) ""
         else
           s""" ++ ($renderCrossValueInTask match {
              |${
               renderLines(crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2)
                 .map((v, crosses) =>
                   s"  case ${crosses.sorted.map(literalize(_)).mkString(" | ")} => Seq(${v.map(renderValue).mkString(", ")})"
                 ))
             }
              |  case _ => Nil
              |})""".stripMargin)
  }
}
