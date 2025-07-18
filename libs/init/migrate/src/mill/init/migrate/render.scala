package mill.init.migrate

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.init.migrate.BuildDefaults.{moduleSupertype0, moduleSupertypeCross}
import mill.init.migrate.ModuleDefaults.scalaVersionTask
import mill.internal.Util.backtickWrap

def writeBuildFiles(modules: Seq[MetaModule[ModuleMetadata]]) =
  for
    module <- modules
    filename = if module.isRoot then rootBuildFileNames.get(0) else nestedBuildFileNames.get(0)
    sub = os.sub / module.moduleDir / filename
    contents = renderMetaModule(module)
  do
    os.write.over(os.pwd / sub, contents)

def renderMetaModule(module: MetaModule[ModuleMetadata]): String =
  import module.*
  val (pkgStmt, aliasWithArrow) =
    if isPackage then
      (
        "package " + (rootModuleAlias +: moduleDir.map(backtickWrap)).mkString("."),
        if submodules.isEmpty then "" else "outer =>"
      )
    else ("", "")
  import metadata.*
  val (extendsValue, footer) =
    baseTrait match
      case Some(base) =>
        import base.*
        (
          if crossVersions.length > 1 then
            s"$moduleSupertypeCross[$moduleName](${crossVersions.iterator.map(lit).mkString(",")})"
          else moduleName,
          renderBaseTrait(base)
        )
      case None =>
        (moduleSupertype0, "")
  val overrideModuleDir = if isEmbed then "override def moduleDir = outer.moduleDir" else ""
  // TODO remove hard-coded import for mvn interpolator
  val mvnImport = if isPackage then "import mill.scalalib._" else ""

  s"""$pkgStmt
     |
     |$mvnImport
     |
     |object ${backtickWrap(name)} extends $extendsValue { $aliasWithArrow
     |
     |$overrideModuleDir
     |
     |${renderModuleData(data)}
     |
     |${renderNonEmpty(submodules)(_.map(renderMetaModule).mkString(linebreak))}
     |
     |}
     |$footer""".stripMargin

def renderBaseTrait(base: BaseTrait) =
  import base.*
  val extendsValue = supertypes match
    case head +: tail => tail.iterator.map(" with " + _).mkString(head, "", "")
    case _ => moduleSupertype0
  s"""trait ${backtickWrap(moduleName)} extends $extendsValue {
     |${renderModuleData(data)}
     |}""".stripMargin

def renderModuleData(data: ModuleData) =
  import data.*
  s"""${coursierModule.fold("")(renderCoursierModule)}
     |${javaHomeModule.fold("")(renderJavaHomeModule)}
     |${runModule.fold("")(renderRunModule)}
     |${javaModule.fold("")(renderJavaModule)}
     |${publishModule.fold("")(renderPublishModule)}
     |${scalaModule.fold("")(renderScalaModule)}
     |${scalaJSModule.fold("")(renderScalaJSModule)}
     |${scalaNativeModule.fold("")(renderScalaNativeModule)}
     |""".stripMargin

def renderCoursierModule(data: CoursierModuleData) =
  import data.*
  s"""${renderRepositories(repositories)}
     |""".stripMargin

def renderRepositories(repositories: Seq[String]) =
  renderNonEmpty(repositories)(_.map(lit).mkString(
    "def repositories = super.repositories() ++ Seq(",
    ",",
    ")"
  ))

def renderJavaHomeModule(data: JavaHomeModuleData) =
  import data.*
  s"""${jvmId.fold("")(renderJvmId)}
     |""".stripMargin

def renderJvmId(jvmId: String) =
  s"def jvmId = ${lit(jvmId)}"

def renderRunModule(data: RunModuleData) =
  import data.*
  s"""${renderForkArgs(forkArgs)}
     |
     |${renderForkEnv(forkEnv)}
     |""".stripMargin

def renderForkArgs(forkArgs: Seq[String]) =
  renderNonEmpty(forkArgs)(_.map(lit).mkString(
    "def forkArgs = super.forkArgs() ++ Seq(",
    ",",
    ")"
  ))

def renderForkEnv(forkEnv: Map[String, String]) =
  renderNonEmpty(forkEnv)(_.map((k, v) => s"(${lit(k)}, ${lit(v)})").mkString(
    "def forkEnv = super.forkEnv() ++ Map(",
    ",",
    ")"
  ))

def renderJavaModule(data: JavaModuleData) =
  import data.*
  s"""${renderMvnDeps(mvnDeps)}
     |
     |${renderCompileMvnDeps(compileMvnDeps)}
     |
     |${renderRunMvnDeps(runMvnDeps)}
     |
     |${renderBomMvnDeps(bomMvnDeps)}
     |
     |${renderArtifactTypes(artifactTypes)}
     |
     |${renderJavacOptions(javacOptions)}
     |
     |${renderModuleDeps(moduleDeps)}
     |
     |${renderCompileModuleDeps(compileModuleDeps)}
     |
     |${renderRunModuleDeps(runModuleDeps)}
     |
     |${renderBomModuleDeps(bomModuleDeps)}
     |
     |${renderSourcesFolders(sourceFolders)}
     |
     |${renderResources(resources)}
     |
     |${renderJavaDocOptions(javadocOptions)}
     |""".stripMargin

def renderDep(dep: DepData) =
  import dep.*
  val sep1 = crossVersion match
    case CrossVersionData.Full(true) => ":::"
    case CrossVersionData.Binary(true) => "::"
    case _ => ":"
  val suffix = version.fold(""): version =>
    val sep2 = crossVersion match
      case CrossVersionData.Full(true) => "::"
      case CrossVersionData.Binary(true) => "::"
      case _ => ":"
    val attr1 = `type`.fold("")(v => s";type=$v")
    val attr2 = classifier.fold("")(v => s";classifier=$v")
    val attr3 = excludes.iterator.map((org, name) => s";exclude=$org:$name").mkString
    val compat = if withDottyCompat then s"withDottyCompat($scalaVersionTask())" else ""
    s"$sep2$version$attr1$attr2$attr3$compat"
  s"""mvn"$organization$sep1$name$suffix""""

def renderMvnDeps(mvnDeps: Seq[DepData]) =
  renderNonEmpty(mvnDeps)(_.map(renderDep).mkString(
    "def mvnDeps = super.mvnDeps() ++ Seq(",
    ",",
    ")"
  ))

def renderCompileMvnDeps(compileMvnDeps: Seq[DepData]) =
  renderNonEmpty(compileMvnDeps)(_.map(renderDep).mkString(
    "def compileMvnDeps = super.compileMvnDeps() ++ Seq(",
    ",",
    ")"
  ))

def renderRunMvnDeps(runMvnDeps: Seq[DepData]) =
  renderNonEmpty(runMvnDeps)(_.map(renderDep).mkString(
    "def runMvnDeps = super.runMvnDeps() ++ Seq(",
    ",",
    ")"
  ))

def renderBomMvnDeps(bomMvnDeps: Seq[DepData]) =
  renderNonEmpty(bomMvnDeps)(_.map(renderDep).mkString(
    "def bomMvnDeps = super.bomMvnDeps() ++ Seq(",
    ",",
    ")"
  ))

def renderArtifactTypes(artifactTypes: Seq[String]) =
  renderNonEmpty(artifactTypes)(_.map(v => s"coursier.core.Type(${lit(v)})").mkString(
    "def artifactTypes = super.artifactTypes() ++ Set(",
    ",",
    ")"
  ))

def renderJavacOptions(javacOptions: Seq[String]) =
  renderNonEmpty(javacOptions)(_.map(lit).mkString(
    "def javacOptions = super.javacOptions() ++ Seq(",
    ",",
    ")"
  ))

def renderModuleDep(dep: ModuleDep) =
  import dep.*
  val wrapped = segments.iterator.map(backtickWrap)
  (Iterator(rootModuleAlias) ++ (
    if argsByIndex.isEmpty then wrapped
    else
      for (segment, i) <- wrapped.zipWithIndex
      yield argsByIndex.get(i).fold(segment)(_.mkString(s"$segment(", ",", ")"))
  )).mkString(".")

def renderModuleDeps(moduleDeps: Seq[ModuleDep]) =
  renderNonEmpty(moduleDeps)(_.map(renderModuleDep).mkString(
    "def moduleDeps = super.moduleDeps ++ Seq(",
    ",",
    ")"
  ))

def renderCompileModuleDeps(compileModuleDeps: Seq[ModuleDep]) =
  renderNonEmpty(compileModuleDeps)(_.map(renderModuleDep).mkString(
    "def compileModuleDeps = super.compileModuleDeps ++ Seq(",
    ",",
    ")"
  ))

def renderRunModuleDeps(runModuleDeps: Seq[ModuleDep]) =
  renderNonEmpty(runModuleDeps)(_.map(renderModuleDep).mkString(
    "def runModuleDeps = super.runModuleDeps ++ Seq(",
    ",",
    ")"
  ))

def renderBomModuleDeps(bomModuleDeps: Seq[ModuleDep]) =
  renderNonEmpty(bomModuleDeps)(_.map(renderModuleDep).mkString(
    "def bomModuleDeps = super.bomModuleDeps ++ Seq(",
    ",",
    ")"
  ))

def renderSourcesFolders(sourceFolders: Seq[Seq[String]]) =
  renderNonEmpty(sourceFolders)(_.map(_.mkString("/")).map(lit).mkString(
    "def sourcesFolders = super.sourcesFolders ++ Seq(",
    ",",
    ")"
  ))

def renderResources(resources: Seq[Seq[String]]) =
  renderNonEmpty(resources)(_.map(_.mkString("/")).map(lit).mkString(
    "def resources = super.resources() ++ Seq(",
    ",",
    ")"
  ))

def renderJavaDocOptions(javadocOptions: Seq[String]) =
  renderNonEmpty(javadocOptions)(_.map(lit).mkString(
    s"def javadocOptions = super.javadocOptions() ++ Seq(",
    ",",
    ")"
  ))

def renderPublishModule(data: PublishModuleData) =
  import data.*
  s"""${publishVersion.fold("")(renderPublishVersion)}
     |
     |${pomSettings.fold("")(renderPomSettings)}
     |
     |${pomPackagingType.fold("")(renderPomPackagingType)}
     |
     |${pomParentProject.fold("")(renderPomParentProject)}
     |
     |${versionScheme.fold("")(renderVersionScheme)}
     |
     |${renderPublishProperties(publishProperties)}
     |""".stripMargin

def renderPublishVersion(v: String) =
  s"def publishVersion = ${lit(v)}"

def renderLicense(data: LicenseData) =
  import data.*
  s"""mill.javalib.publish.License(
     |${lit(id.getOrElse(""))},
     |${lit(name.getOrElse(""))},
     |${lit(url.getOrElse(""))},
     |${isOsiApproved.getOrElse(false)},
     |${isFsfLibre.getOrElse(false)},
     |${lit(distribution.getOrElse(""))}
     |)""".stripMargin

def renderVersionControl(data: VersionControlData) =
  import data.*
  s"""mill.javalib.publish.VersionControl(
     |${browsableRepository.fold("None")(v => s"Some(${lit(v)})")},
     |${connection.fold("None")(v => s"Some(${lit(v)})")},
     |${developerConnection.fold("None")(v => s"Some(${lit(v)})")},
     |${tag.fold("None")(v => s"Some(${lit(v)})")},
     |)""".stripMargin

def renderDeveloper(data: DeveloperData) =
  import data.*
  s"""mill.javalib.publish.Developer(
     |${lit(id.getOrElse(""))},
     |${lit(name.getOrElse(""))},
     |${lit(url.getOrElse(""))},
     |${organization.fold("None")(v => s"Some(${lit(v)})")},
     |${organizationUrl.fold("None")(v => s"Some(${lit(v)})")}
     |)""".stripMargin

def renderPomSettings(data: PomSettingsData) =
  import data.*
  s"""def pomSettings = mill.javalib.publish.PomSettings(
     |${lit(description.getOrElse(""))},
     |${lit(organization.getOrElse(""))},
     |${lit(url.getOrElse(""))},
     |${renderNonEmpty(licenses)(_.map(renderLicense).mkString("Seq(", ",", ")"))},
     |${renderVersionControl(versionControl)},
     |${renderNonEmpty(developers)(_.map(renderDeveloper).mkString("Seq(", ",", ")"))}
     |)""".stripMargin

def renderPomPackagingType(v: String) =
  s"""def pomPackagingType = ${lit(v)}
     |""".stripMargin

def renderPomParentProject(data: ArtifactData) =
  import data.*
  s"""def pomParentProject = Some(mill.javalib.Artifact(
     |${lit(group)},
     |${lit(id)},
     |${lit(version)}
     |))
     |""".stripMargin

def renderVersionScheme(v: String) =
  val opt = Option(v match
    case "early-semver" => "EarlySemVer"
    case "pvp" => "PVP"
    case "semver-spec" => "SemVerSpec"
    case "strict" => "Strict"
    case _ => null)
  opt.fold(""): v =>
    s"def versionScheme = Some(mill.javalib.publish.VersionScheme.$v)"

def renderPublishProperties(vs: Map[String, String]) =
  renderNonEmpty(vs)(_.map((k, v) => s"(${lit(k)}, ${lit(v)})").mkString(
    "def publishProperties = super.publishProperties() ++ Map(",
    ",",
    ")"
  ))

def renderScalaModule(data: ScalaModuleData) =
  import data.*
  s"""${scalaVersion.fold("")(renderScalaVersion)}
     |
     |${renderScalacOptions(scalacOptions)}
     |
     |${renderScaladDocOptions(scalaDocOptions)}
     |""".stripMargin

def renderScalaVersion(v: String) =
  s"def scalaVersion = ${lit(v)}"

def renderScalacOptions(vs: Seq[String]) =
  renderNonEmpty(vs)(_.map(lit).mkString(
    "def scalacOptions = super.scalacOptions() ++ Seq(",
    ",",
    ")"
  ))

def renderScaladDocOptions(vs: Seq[String]) =
  renderNonEmpty(vs)(_.map(lit).mkString(
    "def scalaDocOptions = super.scalaDocOptions() ++ Seq(",
    ",",
    ")"
  ))

def renderScalaJSModule(data: ScalaJSModuleData) =
  import data.*
  s"""${scalaJSVersion.fold("")(renderScalaJSVersion)}
     |""".stripMargin

def renderScalaJSVersion(v: String) =
  s"def scalaJSVersion = ${lit(v)}"

def renderScalaNativeModule(data: ScalaNativeModuleData) =
  import data.*
  s"""${scalaNativeVersion.fold("")(renderScalaNativeVersion)}
     |""".stripMargin

def renderScalaNativeVersion(v: String) =
  s"def scalaNativeVersion = ${lit(v)}"

def renderNonEmpty[A](as: Iterable[A])(f: Iterator[A] => String) =
  if as.isEmpty then "" else f(as.iterator)

private def lit(s: String) = pprint.Util.literalize(s)

private def linebreak =
  s"""
     |""".stripMargin
