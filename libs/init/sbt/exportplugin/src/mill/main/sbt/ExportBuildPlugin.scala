package mill.main.sbt

import _root_.sbt.{Value => _, _}
import mill.main.buildgen.{ModuleSpec, PackageSpec}
import mill.main.buildgen.ModuleSpec._

import scala.language.implicitConversions
import scala.util.Using

object ExportBuildPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  object autoImport {
    val millInitExportBuild = taskKey[Unit]("")
  }

  val millInitExportDir = settingKey[os.Path]("")
  val millInitExportProject = taskKey[Unit]("")
  // Proxies for third-party plugin settings that are resolved using reflection.
  val millScalaJSModuleKind = settingKey[Option[String]]("")
  // Copies of third-party plugin settings that are resolved directly.
  val crossProjectBaseDirectory = settingKey[File]("")
  val scalafixDependencies = settingKey[Seq[ModuleID]]("")
  val coverageMinimumStmtTotal = settingKey[Double]("")
  val coverageMinimumBranchTotal = settingKey[Double]("")

  import autoImport._
  override def globalSettings = Seq(
    millInitExportBuild := exportBuild.value,
    millInitExportDir := os.Path(System.getProperty("millInitExportDir"))
  )
  override def projectSettings = Seq(
    millScalaJSModuleKind := scalaJSModuleKind.value,
    millInitExportProject := exportProject.value
  )

  // This is required to export projects that are not aggregated by the root project.
  private def exportBuild = Def.taskDyn {
    val structure = Project.structure(Keys.state.value)
    Def.task {
      val _ = std.TaskExtra.joinTasks(structure.allProjectPairs.flatMap {
        case (project, ref) =>
          if (project.aggregate.isEmpty) (ref / millInitExportProject).get(structure.data)
          else None
      }).join.value
    }
  }

  private def proxyForSetting(id: String, rt: Class[_]) = {
    val manifest = new Manifest[AnyRef] { def runtimeClass = rt }
    val anyRefWriter = implicitly[util.OptJsonWriter[AnyRef]]
    SettingKey(id)(manifest, anyRefWriter).?
  }

  private def scalaJSModuleKind = Def.settingDyn {
    try {
      val class0 = Class.forName("org.scalajs.linker.interface.StandardConfig")
      val method = class0.getMethod("moduleKind")
      Def.setting {
        proxyForSetting("scalaJSLinkerConfig", class0).value.map("ModuleKind." + method.invoke(_))
      }
    } catch {
      case _: ClassNotFoundException => Def.setting(None)
      case _: NoSuchMethodException => Def.setting(None)
    }
  }

  private def exportProject = Def.taskDyn {
    val project = Keys.thisProject.value
    val scalaVersion = Keys.scalaVersion.value
    val outDir = millInitExportDir.value
    val outFile = outDir / s"${project.id}-$scalaVersion.json"
    // Ignore duplicate invocations triggered by cross-build execution.
    Def.task(if (!os.exists(outFile)) {
      val structure = Project.structure(Keys.state.value)
      def isBaseDirShared(projectId: String, baseDir: File) = structure.allProjects.exists(p =>
        p.aggregate.isEmpty && p.id != projectId && p.base == baseDir
      )
      val baseDir = os.Path(project.base)
      val crossProjectBaseDir = crossProjectBaseDirectory.get(structure.data).map(os.Path(_))
        .orElse(if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) Some(baseDir / os.up)
        else None)
      val baseDir0 = crossProjectBaseDir.getOrElse(baseDir)
      val isCrossPlatform = crossProjectBaseDir.nonEmpty
      val isCrossVersion = Keys.crossScalaVersions.value.length > 1
      def hasAutoPlugin(label: String) = project.autoPlugins.exists(_.label == label)
      val isScalaJSModule = hasAutoPlugin("org.scalajs.sbtplugin.ScalaJSPlugin")
      val isScalaNativeModule = hasAutoPlugin("scala.scalanative.sbtplugin.ScalaNativePlugin")
      val hasMainSrcDirs = (Compile / Keys.unmanagedSourceDirectories).value.exists(_.exists())
      val hasTestSrcDirs = (Test / Keys.unmanagedSourceDirectories).value.exists(_.exists())
      val hasSrcDirs = hasMainSrcDirs || hasTestSrcDirs

      // Values are duplicated by cross version for ease of processing when combining cross specs.
      implicit def value[A](base: Option[A]): Value[A] = Value(
        base,
        base match {
          case Some(a) if isCrossVersion => Seq(scalaVersion -> a)
          case _ => Nil
        }
      )
      implicit def values[A](base: Seq[A]): Values[A] = Values(
        base = base,
        cross = if (isCrossVersion && base.nonEmpty) Seq(scalaVersion -> base) else Nil
      )
      val mainCodeBlocks = Seq.newBuilder[String]
      val moduleDir = toModuleDir(baseDir0)
      val (mainRoot, mainName) = if (isBaseDirShared(project.id, project.base)) {
        mainCodeBlocks += "def moduleDir = outerRoot.moduleDir"
        val root = PackageSpec.root(moduleDir)
        (Right(root.copy(module = root.module.copy(alias = Some("outerRoot")))), project.id)
      } else if (isCrossPlatform) (Right(PackageSpec.root(moduleDir)), toModuleName(baseDir.last))
      else (Left(moduleDir), baseDir.last)
      var mainModule = ModuleSpec(
        name = mainName,
        imports = Seq("mill.scalalib.*"),
        supertypes =
          ((isCrossPlatform, isCrossVersion) match {
            case (true, true) => "CrossSbtPlatformModule"
            case (_, true) => "CrossSbtModule"
            case (true, _) => "SbtPlatformModule"
            case _ => "SbtModule"
          }) +: (if (
                   (Compile / Keys.unmanagedSourceDirectories).value.exists { dir =>
                     val c = dir.name.last
                     c == '+' || c == '-'
                   }
                 ) Seq("CrossScalaVersionRanges")
                 else Nil),
        crossKeys = if (isCrossVersion) Seq(scalaVersion) else Nil,
        repositories = Keys.resolvers.value
          .diff(Seq(Resolver.mavenCentral, Resolver.mavenLocal))
          .collect {
            case r: MavenRepository => r.root
          }
      )

      def skipDep(dep: ModuleID) = {
        import dep.{name, organization => org}
        ((name.startsWith("scala-library") ||
          name.startsWith("scala3-library")) && org == "org.scala-lang") ||
        (name.startsWith("dotty") && org == "ch.epfl.lamp") ||
        (isScalaJSModule && !name.startsWith("scalajs-dom") && org == "org.scala-js") ||
        (isScalaNativeModule && org == "org.scala-native")
      }
      val configDeps = Keys.libraryDependencies.value.collect {
        case dep if !skipDep(dep) => (dep, dep.configurations.getOrElse("compile").split(';').toSeq)
      }
      def mvnDeps(configs: String*) = configDeps.collect {
        case (dep, depConfigs) if configs.exists(depConfigs.contains) => toMvnDep(dep)
      }
      val configProjectDeps = project.dependencies
        .map(dep => dep -> dep.configuration.getOrElse("compile").split(";").toSeq)
      def moduleDeps(p: String => Boolean, childSegment: Option[String] = None): Seq[ModuleDep] =
        configProjectDeps.flatMap {
          case (dep, configs) if configs.exists(p) =>
            (dep.project / Keys.baseDirectory).get(structure.data).flatMap { depBaseDir =>
              var depModuleDir = toModuleDir(os.Path(depBaseDir))
              if (isBaseDirShared(dep.project.project, depBaseDir)) {
                depModuleDir = depModuleDir / dep.project.project
              }
              val depCrossScalaVersions =
                (dep.project / Keys.crossScalaVersions).get(structure.data).getOrElse(Nil)
              if (depCrossScalaVersions.contains(scalaVersion)) {
                val crossSuffix = if (depCrossScalaVersions.length < 2) None
                else Some(if (isCrossVersion) "()" else s"""("$scalaVersion")""")
                Some(ModuleDep(depModuleDir, crossSuffix, childSegment))
              } else None
            }
          case _ => None
        }

      mainModule = mainModule.copy(
        mvnDeps = mvnDeps("compile"),
        compileMvnDeps = mvnDeps("provided", "optional"),
        runMvnDeps = mvnDeps("runtime"),
        javacOptions = Opt.groups(Keys.javacOptions.value),
        moduleDeps = moduleDeps(config => config == "compile" || config.startsWith("compile->")),
        compileModuleDeps = moduleDeps(config =>
          config == "provided" || config.startsWith("provided->") ||
            config == "optional" || config.startsWith("optional->")
        ),
        runModuleDeps = moduleDeps(config => config == "runtime" || config.startsWith("runtime->")),
        artifactName = Some(Keys.moduleName.value),
        scalaVersion = if (isCrossVersion) None else Some(scalaVersion),
        scalacOptions = Opt.groups(Keys.scalacOptions.value.filterNot(skipScalacOption)),
        scalacPluginMvnDeps = mvnDeps("plugin->default(compile)")
      )
      if (crossProjectBaseDir.exists(dir => os.isDir(dir / "shared"))) {
        mainCodeBlocks += "def sourcesRootFolders = super.sourcesRootFolders ++ Seq(\"shared\")"
      }

      if (!(Keys.publish / Keys.skip).value && Keys.publishArtifact.value) {
        mainModule = mainModule.copy(
          imports =
            "mill.javalib.PublishModule" +: "mill.javalib.publish.*" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "PublishModule",
          pomSettings = Some(toPomSettings(Keys.projectInfo.value, Keys.organization.value)),
          publishVersion = Some(Keys.version.value),
          versionScheme = Keys.versionScheme.value.collect {
            case "early-semver" => "VersionScheme.EarlySemVer"
            case "pvp" => "VersionScheme.PVP"
            case "semver-spec" => "VersionScheme.SemVerSpec"
            case "strict" => "VersionScheme.Strict"
          }
        )
      }

      if (isScalaJSModule) {
        Keys.libraryDependencies.value.collectFirst {
          case dep
              if dep.organization == "org.scala-js" && dep.name.startsWith("scalajs-library") =>
            mainModule = mainModule.copy(
              imports =
                "mill.scalajslib.ScalaJSModule" +: "mill.scalajslib.api.*" +: mainModule.imports,
              supertypes = mainModule.supertypes :+ "ScalaJSModule",
              scalaJSVersion = Some(dep.revision),
              moduleKind = millScalaJSModuleKind.value
            )
        }
      }
      if (isScalaNativeModule) {
        Keys.libraryDependencies.value.collectFirst {
          case dep
              if dep.organization == "org.scala-native" &&
                dep.configurations.contains("plugin->default(compile)") =>
            mainModule = mainModule.copy(
              imports =
                "mill.scalanativelib.ScalaNativeModule" +: "mill.scalanativelib.api.*" +: mainModule.imports,
              supertypes = mainModule.supertypes :+ "ScalaNativeModule",
              scalaNativeVersion = Some(dep.revision)
            )
        }
      }
      val hasScalafmtPlugin = hasAutoPlugin("org.scalafmt.sbt.ScalafmtPlugin")
      if (hasAutoPlugin("scalafix.sbt.ScalafixPlugin") && hasMainSrcDirs) {
        mainModule = mainModule.copy(
          imports = "com.goyeau.mill.scalafix.ScalafixModule" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "ScalafixModule",
          scalafixIvyDeps =
            scalafixDependencies.get(structure.data).toSeq.flatMap(_.distinct.map(toMvnDep))
        )
        if (os.exists(os.pwd / ".scalafix.conf")) {
          mainModule = mainModule.copy(imports = "mill.api.*" +: mainModule.imports)
          mainCodeBlocks += "def scalafixConfig = Some(BuildCtx.workspaceRoot / \".scalafix.conf\")"
        }
      }
      if (hasScalafmtPlugin && hasMainSrcDirs) {
        mainModule = mainModule.copy(
          imports = "mill.scalalib.scalafmt.ScalafmtModule" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "ScalafmtModule"
        )
      }
      if (hasAutoPlugin("scoverage.ScoverageSbtPlugin") && hasSrcDirs) {
        structure.units.get(Keys.thisProjectRef.value.build).iterator
          .flatMap(_.unit.plugins.pluginData.dependencyClasspath)
          .flatMap(_.metadata.entries.collectFirst {
            case entry if entry.key.label == "moduleID" => entry.value
          })
          .collectFirst {
            case dep: ModuleID if
                  dep.name.startsWith("scalac-scoverage") && dep.organization == "org.scoverage" =>
              mainModule = mainModule.copy(
                imports = "mill.contrib.scoverage.ScoverageModule" +: mainModule.imports,
                supertypes = mainModule.supertypes :+ "ScoverageModule",
                scoverageVersion = Some(dep.revision),
                branchCoverageMin = coverageMinimumBranchTotal.get(structure.data).filter(_ != 0.0),
                statementCoverageMin = coverageMinimumStmtTotal.get(structure.data).filter(_ != 0.0)
              )
              mainCodeBlocks +=
                """override lazy val scoverage = new ScoverageData {
                  |  def platformSuffix = outer.platformSuffix()
                  |}""".stripMargin
          }
      }
      if (hasTestSrcDirs) {
        val testMvnDeps = mvnDeps("test")
        val testMixin = ModuleSpec.testModuleMixin(testMvnDeps)
        val testSupertypes = mainModule.supertypes.collect {
          case "ScalafixModule" => "ScalafixModule"
        } ++ (if (hasScalafmtPlugin) Seq("ScalafmtModule") else Nil) ++
          mainModule.supertypes.collect {
            case "CrossSbtPlatformModule" => "CrossSbtPlatformTests"
            case "CrossSbtModule" => "CrossSbtTests"
            case "SbtPlatformModule" => "SbtPlatformTests"
            case "SbtModule" => "SbtTests"
            case "ScalaJSModule" => "ScalaJSTests"
            case "ScalaNativeModule" => "ScalaNativeTests"
            case "ScoverageModule" => "ScoverageTests"
          } ++ testMixin
        var testModule = ModuleSpec(
          name = "test",
          supertypes = testSupertypes,
          mvnDeps = values(testMvnDeps).copy(appendSuper = isScalaNativeModule),
          compileMvnDeps = mainModule.compileMvnDeps,
          runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
          moduleDeps = values(
            moduleDeps(_ == "test") ++
              moduleDeps(_ == "test->test", childSegment = Some("test"))
          ).copy(appendSuper = true),
          compileModuleDeps = mainModule.compileModuleDeps.base,
          runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
          testFramework =
            if (testMixin.isEmpty) testFramework(testMvnDeps).orElse(Some("")) else None
        )
        val testCodeBlocks = Seq.newBuilder[String]
        if (testSupertypes.contains("ScalafixModule")) {
          testCodeBlocks += "def scalafixConfig = outer.scalafixConfig()"
          if (mainModule.scalafixIvyDeps.base.nonEmpty) {
            testCodeBlocks += "def scalafixIvyDeps = outer.scalafixIvyDeps()"
          }
        }
        testCodeBlocks ++= Seq(
          "def testParallelism = false",
          "def testSandboxWorkingDir = false"
        )
        testModule = testModule.copy(codeBlocks = testCodeBlocks.result())
        mainModule = mainModule.copy(children = Seq(testModule)).withAlias()
      }
      mainModule = mainModule.copy(codeBlocks = mainCodeBlocks.result())

      Using.resource(os.write.outputStream(outFile))(
        upickle.default.writeToOutputStream(SbtModuleSpec(mainRoot, mainModule), _)
      )
    })
  }

  private def skipScalacOption(s: String) =
    s.startsWith("-P") || s.startsWith("-Xplugin") || s.startsWith("-scalajs")

  private def testFramework(deps: Seq[MvnDep]) = deps.collectFirst {
    case dep if dep.organization == "com.eed3si9n.verify" => "verify.runner.Framework"
  }

  // Cleanup `CrossType.Pure` module names.
  private def toModuleName(dirName: String) = dirName.stripPrefix(".")

  private def toModuleDir(baseDir: os.Path) = baseDir.subRelativeTo(os.pwd).segments match {
    case Seq() => os.sub
    case init :+ last => os.sub / os.SubPath(init :+ toModuleName(last))
  }

  private def toMvnDep(dep: ModuleID) = {
    import ModuleSpec.{CrossVersion => CV}
    import dep._
    import librarymanagement.{For2_13Use3, For3Use2_13, Patch}
    val artifact = explicitArtifacts.find(_.name == name)
    MvnDep(
      organization = organization,
      name = name,
      version = revision,
      classifier = artifact.flatMap(_.classifier),
      `type` = artifact.map(_.`type`),
      excludes = exclusions.map(x => (x.organization, x.name)),
      cross = crossVersion match {
        case v: Binary => CV.Binary(platformed = v.prefix.nonEmpty)
        case v: Full => CV.Full(platformed = v.prefix.nonEmpty)
        case _: Patch => CV.Full(platformed = false)
        case v: For2_13Use3 => CV.Constant("_3", platformed = v.prefix.nonEmpty)
        case v: For3Use2_13 => CV.Constant("_2.13", platformed = v.prefix.nonEmpty)
        case _ => CV.Constant("", platformed = false)
      }
    )
  }

  private def toPomSettings(moduleInfo: ModuleInfo, groupId: String) = {
    import moduleInfo._
    PomSettings(
      description = description,
      organization = groupId,
      url = homepage.fold("")(_.toExternalForm),
      licenses = licenses.map(toLicense),
      versionControl = toVersionControl(scmInfo),
      developers = developers.map(toDeveloper)
    )
  }

  private def toLicense(license: (String, URL)) = {
    val (name, url) = license
    ModuleSpec.License(
      name = name,
      url = url.toExternalForm,
      distribution = "repo" // hardcoded by sbt
    )
  }

  private def toVersionControl(scmInfo: Option[ScmInfo]) =
    scmInfo.fold(VersionControl()) { scmInfo =>
      import scmInfo._
      VersionControl(
        Some(browseUrl.toExternalForm),
        Some(connection),
        devConnection
      )
    }

  private def toDeveloper(developer: librarymanagement.Developer) = {
    import developer._
    ModuleSpec.Developer(id, name, url.toExternalForm)
  }
}
