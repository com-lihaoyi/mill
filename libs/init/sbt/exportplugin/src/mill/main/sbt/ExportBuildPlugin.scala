package mill.main.sbt

import _root_.sbt.{Value => _, _}
import _root_.sbt.dsl.LinterLevel.Ignore
import com.typesafe.tools.mima.core.ProblemFilter
import mill.main.buildgen._
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
  // Copies/proxies for third-party plugin settings/tasks
  val millScalaJSModuleKind = settingKey[Option[String]]("")
  val crossProjectBaseDirectory = settingKey[File]("")
  val mimaPreviousArtifacts = settingKey[Set[ModuleID]]("")
  val mimaCheckDirection = settingKey[String]("")
  val millMimaBinaryIssueFilters = taskKey[Seq[String]]("")
  val millMimaBackwardIssueFilters = taskKey[Seq[(String, Seq[String])]]("")
  val millMimaForwardIssueFilters = taskKey[Seq[(String, Seq[String])]]("")
  val mimaExcludeAnnotations = settingKey[Seq[String]]("")
  val mimaReportSignatureProblems = settingKey[Boolean]("")
  val coverageMinimumStmtTotal = settingKey[Double]("")
  val coverageMinimumBranchTotal = settingKey[Double]("")
  val scalafixDependencies = settingKey[Seq[ModuleID]]("")

  import autoImport._
  override def globalSettings = Seq(
    millInitExportBuild := exportBuild.value,
    millInitExportDir := os.Path(System.getProperty("millInitExportDir"))
  )
  override def projectSettings = Seq(
    millScalaJSModuleKind := scalaJSModuleKind.value,
    millMimaBinaryIssueFilters := mimaBinaryIssueFilters.value,
    millMimaBackwardIssueFilters := mimaBackwardIssueFilters.value,
    millMimaForwardIssueFilters := mimaForwardIssueFilters.value,
    millInitExportProject := exportProject.value
  )

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

  private def toMillProblemFilter(pf: ProblemFilter) = {
    "ProblemFilter.exclude" + pf.toString.stripPrefix("ExcludeByName")
  }

  private def mimaBinaryIssueFilters = Def.taskDyn[Seq[String]] {
    try {
      val mimaBinaryIssueFilters = taskKey[Seq[ProblemFilter]]("")
      Def.task {
        mimaBinaryIssueFilters.?.value.toSeq.flatten.map(toMillProblemFilter)
      }
    } catch {
      case _: NoClassDefFoundError => Def.task(Nil)
    }
  }

  private def mimaBackwardIssueFilters = Def.taskDyn[Seq[(String, Seq[String])]] {
    try {
      val mimaBackwardIssueFilters = taskKey[Map[String, Seq[ProblemFilter]]]("")
      Def.task {
        mimaBackwardIssueFilters.?.value.toSeq.flatten.map {
          case (k, v) => (k, v.map(toMillProblemFilter))
        }
      }
    } catch {
      case _: NoClassDefFoundError => Def.task(Nil)
    }
  }

  private def mimaForwardIssueFilters = Def.taskDyn[Seq[(String, Seq[String])]] {
    try {
      val mimaForwardIssueFilters = taskKey[Map[String, Seq[ProblemFilter]]]("")
      Def.task {
        mimaForwardIssueFilters.?.value.toSeq.flatten.map {
          case (k, v) => (k, v.map(toMillProblemFilter))
        }
      }
    } catch {
      case _: NoClassDefFoundError => Def.task(Nil)
    }
  }

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

  private def exportProject = Def.taskDyn {
    val project = Keys.thisProject.value
    val scalaVersion = Keys.scalaVersion.value
    val outDir = millInitExportDir.value
    val outFile = outDir / s"${project.id}-$scalaVersion.json"
    // Ignore duplicate invocations triggered by cross-build execution.
    Def.task(if (!os.exists(outFile)) {
      def hasAutoPlugin(label: String) = project.autoPlugins.exists(_.label == label)
      val structure = Project.structure(Keys.state.value)
      def isBaseDirShared(projectId: String, baseDir: File) =
        structure.allProjects.exists(p => p.id != projectId && p.base == baseDir)
      val baseDir = os.Path(project.base)
      val crossProjectBaseDir = crossProjectBaseDirectory.?.value.map(os.Path(_)).orElse(
        if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) Some(baseDir / os.up) else None
      )
      val isCrossPlatform = crossProjectBaseDir.nonEmpty
      val isCrossVersion = Keys.crossScalaVersions.value.length > 1
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
      val (mainName, mainModuleDir, mainRoot) = {
        val moduleDir = toModuleDir(crossProjectBaseDir.getOrElse(baseDir))
        if (isBaseDirShared(project.id, project.base))
          (
            project.id,
            Some("outer.moduleDir"),
            Right(PackageSpec(moduleDir, ModuleSpec(baseDir.last, alias = Some("outer"))))
          )
        else if (isCrossPlatform)
          (toModuleName(baseDir.last), None, Right(PackageSpec.root(moduleDir)))
        else (baseDir.last, None, Left(moduleDir))
      }
      val mainSupertypes =
        ((isCrossPlatform, isCrossVersion) match {
          case (true, true) => "CrossSbtPlatformModule"
          case (true, _) => "SbtPlatformModule"
          case (_, true) => "CrossSbtModule"
          case _ => "SbtModule"
        }) +: (Compile / Keys.unmanagedSourceDirectories).value.collectFirst {
          case dir if Seq('+', '-').contains(dir.name.last) => "CrossScalaVersionRanges"
        }.toSeq
      var mainModule = ModuleSpec(
        name = mainName,
        imports = Seq("mill.scalalib.*"),
        supertypes = mainSupertypes,
        moduleDir = mainModuleDir,
        crossKeys = if (isCrossVersion) Seq(scalaVersion) else Nil
      )

      val configDeps = Keys.libraryDependencies.value.collect {
        case dep if !skipDep(dep) => (dep, dep.configurations.getOrElse("compile").split(';').toSeq)
      }
      def toMvnDep(dep: ModuleID) = {
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
            case v: For2_13Use3 => if (scalaVersion.startsWith("2.13."))
                CV.Constant("_3", platformed = v.prefix.nonEmpty)
              else CV.Binary(platformed = v.prefix.nonEmpty)
            case v: For3Use2_13 => if (scalaVersion.startsWith("3."))
                CV.Constant("_2.13", platformed = v.prefix.nonEmpty)
              else CV.Binary(platformed = v.prefix.nonEmpty)
            case _ => CV.Constant("", platformed = false)
          }
        )
      }
      def mvnDeps(configs: String*) = configDeps.collect {
        case (dep, depConfigs) if configs.exists(depConfigs.contains) => toMvnDep(dep)
      }
      val configProjectDeps = project.dependencies
        .map(dep => dep -> dep.configuration.getOrElse("compile").split(";").toSeq)
      def moduleDeps(p: String => Boolean, childSegment: Option[String] = None): Seq[ModuleDep] =
        configProjectDeps.flatMap {
          case (dep, depConfigs) if depConfigs.exists(p) =>
            (dep.project / Keys.baseDirectory).get(structure.data).flatMap { depBaseDir =>
              var depModuleDir = toModuleDir(os.Path(depBaseDir))
              if (isBaseDirShared(dep.project.project, depBaseDir))
                depModuleDir = depModuleDir / os.RelPath(dep.project.project)
              val depCrossScalaVersions =
                (dep.project / Keys.crossScalaVersions).get(structure.data).getOrElse(Nil)
              if (depCrossScalaVersions.contains(scalaVersion)) {
                val crossSuffix = if (depCrossScalaVersions.length < 2) None
                else Some(if (isCrossVersion) "()" else s"""("$scalaVersion")""")
                Some(ModuleDep(depModuleDir.segments, crossSuffix, childSegment))
              } else None
            }
          case _ => None
        }
      mainModule = mainModule.copy(
        repositories = Keys.resolvers.value
          .diff(Seq(Resolver.mavenCentral, Resolver.mavenLocal))
          .collect {
            case r: MavenRepository => r.root
          },
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
        scalacPluginMvnDeps = mvnDeps("plugin->default(compile)"),
        sourcesRootFolders = values(
          if (crossProjectBaseDir.exists(dir => os.isDir(dir / "shared"))) Seq("shared")
          else Nil
        ).copy(appendSuper = true)
      )

      if (hasAutoPlugin("org.scalajs.sbtplugin.ScalaJSPlugin")) {
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
      if (hasAutoPlugin("scala.scalanative.sbtplugin.ScalaNativePlugin")) {
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
      if (!(Keys.publish / Keys.skip).value && Keys.publishArtifact.value) {
        mainModule = mainModule.copy(
          imports = "mill.javalib.PublishModule" +: "mill.javalib.publish.*" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "PublishModule",
          pomSettings = {
            val projectInfo = Keys.projectInfo.value
            import projectInfo._
            Some(PomSettings(
              description = description,
              organization = Keys.organization.value,
              url = homepage.fold("")(_.toExternalForm),
              licenses = licenses.map {
                case (name, url) => ModuleSpec.License(
                    name = name,
                    url = url.toExternalForm,
                    distribution = "repo" // hardcoded by sbt
                  )
              },
              versionControl = scmInfo.fold(VersionControl()) { scmInfo =>
                import scmInfo._
                VersionControl(
                  Some(browseUrl.toExternalForm),
                  Some(connection),
                  devConnection
                )
              },
              developers = developers.map { developer =>
                import developer._
                ModuleSpec.Developer(id, name, url.toExternalForm)
              }
            ))
          },
          publishVersion = Some(Keys.version.value),
          versionScheme = Keys.versionScheme.value.collect {
            case "early-semver" => "VersionScheme.EarlySemVer"
            case "pvp" => "VersionScheme.PVP"
            case "semver-spec" => "VersionScheme.SemVerSpec"
            case "strict" => "VersionScheme.Strict"
          }
        )
      }
      if (hasAutoPlugin("pl.project13.scala.sbt.JmhPlugin")) {
        Keys.libraryDependencies.value.collectFirst {
          case dep if dep.organization == "org.openjdk.jmh" =>
            mainModule = mainModule.withJmhModule(value(Some(dep.revision)))
        }
      }
      if (hasAutoPlugin("com.typesafe.tools.mima.plugin.MimaPlugin")) {
        val org = Keys.organization.value
        val name = Keys.moduleName.value
        val (versionedArtifacts, artifacts) =
          mimaPreviousArtifacts.?.value.toSeq.flatten.partition {
            dep => dep.organization == org && dep.name == name
          }
        mainModule = mainModule.copy(
          imports = "com.github.lolgab.mill.mima.*" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "Mima",
          mimaPreviousVersions = versionedArtifacts.map(_.revision),
          mimaPreviousArtifacts =
            values(artifacts.map(toMvnDep)).copy(appendSuper = versionedArtifacts.nonEmpty),
          mimaCheckDirection = mimaCheckDirection.?.value.collect {
            case "both" => "CheckDirection.Both"
            case "forward" => "CheckDirection.Forward"
          },
          mimaBinaryIssueFilters = millMimaBinaryIssueFilters.value,
          mimaBackwardIssueFilters = millMimaBackwardIssueFilters.value,
          mimaForwardIssueFilters = millMimaForwardIssueFilters.value,
          mimaExcludeAnnotations = mimaExcludeAnnotations.?.value.getOrElse[Seq[String]](Nil),
          mimaReportSignatureProblems = mimaReportSignatureProblems.?.value.filter(identity)
        )
      }
      if (hasAutoPlugin("scoverage.ScoverageSbtPlugin")) {
        val moduleIDAttrKey = AttributeKey[ModuleID]("moduleID")
        structure.units.get(Keys.thisProjectRef.value.build).iterator
          .flatMap(_.unit.plugins.pluginData.dependencyClasspath)
          .flatMap(_.get(moduleIDAttrKey))
          .collectFirst {
            case dep if
                  dep.name.startsWith("scalac-scoverage") &&
                    dep.organization == "org.scoverage" =>
              mainModule = mainModule.copy(
                imports = "mill.contrib.scoverage.ScoverageModule" +: mainModule.imports,
                supertypes = mainModule.supertypes :+ "ScoverageModule",
                scoverageVersion = Some(dep.revision),
                branchCoverageMin = coverageMinimumBranchTotal.?.value.filter(_ != 0.0),
                statementCoverageMin = coverageMinimumStmtTotal.?.value.filter(_ != 0.0)
              )
          }
      }
      val hasScalafixPlugin = hasAutoPlugin("scalafix.sbt.ScalafixPlugin")
      if (hasScalafixPlugin) {
        mainModule = withScalafixModule(
          mainModule,
          (Compile / scalafixDependencies).?.value.getOrElse(Nil).distinct.map(toMvnDep)
        )
      }
      val hasScalafmtPlugin = hasAutoPlugin("org.scalafmt.sbt.ScalafmtPlugin")
      if (hasScalafmtPlugin) {
        mainModule = withScalafmtModule(mainModule)
      }

      if ((Test / Keys.unmanagedSourceDirectories).value.exists(_.exists)) {
        val testMvnDeps = mvnDeps("test")
        val testMixin = ModuleSpec.testModuleMixin(testMvnDeps)
        val testSupertypes = mainModule.supertypes.collect {
          case "CrossSbtPlatformModule" => "CrossSbtPlatformTests"
          case "SbtPlatformModule" => "SbtPlatformTests"
          case "CrossSbtModule" => "CrossSbtTests"
          case "SbtModule" => "SbtTests"
          case "ScalaJSModule" => "ScalaJSTests"
          case "ScalaNativeModule" => "ScalaNativeTests"
          case "ScoverageModule" => "ScoverageTests"
        } ++ testMixin.toSeq
        var testModule = ModuleSpec(
          name = "test",
          supertypes = testSupertypes,
          forkWorkingDir = if ((Test / Keys.fork).value) Some("this.moduleDir") else None,
          mvnDeps =
            values(testMvnDeps).copy(appendSuper = testSupertypes.contains("ScalaNativeTests")),
          compileMvnDeps = mainModule.compileMvnDeps,
          runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
          moduleDeps = values(
            moduleDeps(_ == "test") ++
              moduleDeps(_ == "test->test", childSegment = Some("test"))
          ).copy(appendSuper = true),
          compileModuleDeps = mainModule.compileModuleDeps,
          runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
          testParallelism = Some(false),
          testSandboxWorkingDir = Some(false),
          testFramework =
            if (testMixin.isEmpty) testMvnDeps.collectFirst {
              case dep if dep.organization == "com.eed3si9n.verify" => "verify.runner.Framework"
            }.orElse(Some(""))
            else None
        )
        if (hasScalafixPlugin) {
          testModule = withScalafixModule(
            testModule,
            (Test / scalafixDependencies).?.value.getOrElse(Nil).distinct.map(toMvnDep)
          )
        }
        if (hasScalafmtPlugin) {
          testModule = withScalafmtModule(testModule)
        }
        mainModule = mainModule.copy(children = Seq(testModule))
      }

      Using.resource(os.write.outputStream(outFile))(
        upickle.default.writeToOutputStream(SbtModuleSpec(mainRoot, mainModule), _)
      )
    })
  }

  private def skipDep(dep: ModuleID) = {
    import dep.{name, organization => org}
    ((name.startsWith("scala-library") ||
      name.startsWith("scala3-library")) && org == "org.scala-lang") ||
    (name.startsWith("dotty") && org == "ch.epfl.lamp") ||
    (!name.startsWith("scalajs-dom") && org == "org.scala-js") ||
    (org == "org.scala-native")
  }

  private def skipScalacOption(s: String) =
    s.startsWith("-P") || s.startsWith("-Xplugin") || s.startsWith("-scalajs")

  // Cleanup `CrossType.Pure` module names.
  private def toModuleName(dirName: String) = dirName.stripPrefix(".")

  private def toModuleDir(baseDir: os.Path) = baseDir.subRelativeTo(os.pwd).segments match {
    case Seq() => os.sub
    case init :+ last => os.sub / os.SubPath(init :+ toModuleName(last))
  }

  private def withScalafixModule(module: ModuleSpec, scalafixIvyDeps: Values[MvnDep]) = {
    module.copy(
      imports = "com.goyeau.mill.scalafix.ScalafixModule" +: "mill.api.BuildCtx" +: module.imports,
      supertypes = module.supertypes :+ "ScalafixModule",
      scalafixConfig = if (!os.exists(os.pwd / ".scalafix.conf")) None
      else Some("""BuildCtx.workspaceRoot / ".scalafix.conf""""),
      scalafixIvyDeps = scalafixIvyDeps
    )
  }

  private def withScalafmtModule(module: ModuleSpec) = {
    module.copy(
      imports = "mill.scalalib.scalafmt.ScalafmtModule" +: module.imports,
      supertypes = module.supertypes :+ "ScalafmtModule"
    )
  }
}
