package mill.main.sbt

import _root_.sbt.{Value => _, _}
import mill.main.buildgen.{ModuleSpec, PackageSpec}
import mill.main.buildgen.ModuleSpec._

import scala.collection.mutable
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
      def hasAutoPlugin(label: String) = project.autoPlugins.exists(_.label == label)
      val isScalaNativeModule = hasAutoPlugin("scala.scalanative.sbtplugin.ScalaNativePlugin")
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

      val mainSnippets = Seq.newBuilder[Snippet]
      val moduleDir = toModuleDir(baseDir0)
      val (mainRoot, mainName) = if (isBaseDirShared(project.id, project.base)) {
        mainSnippets += "def moduleDir = outerRoot.moduleDir"
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
          }) +: (Compile / Keys.unmanagedSourceDirectories).value.collectFirst {
            case dir if Seq('+', '-').contains(dir.name.last) => "CrossScalaVersionRanges"
          }.toSeq,
        crossKeys = if (isCrossVersion) Seq(scalaVersion) else Nil,
        repositories = Keys.resolvers.value
          .diff(Seq(Resolver.mavenCentral, Resolver.mavenLocal))
          .collect {
            case r: MavenRepository => r.root
          },
        mvnDeps = mvnDeps("compile", "protobuf"),
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
        scalacOptions = Opt.groups(Keys.scalacOptions.value.filterNot(s =>
          s.startsWith("-P") || s.startsWith("-Xplugin") || s.startsWith("-scalajs")
        )),
        scalacPluginMvnDeps = mvnDeps("plugin->default(compile)")
      )
      if (crossProjectBaseDir.exists(dir => os.isDir(dir / "shared"))) {
        mainSnippets += "def sourcesRootFolders = super.sourcesRootFolders ++ Seq(\"shared\")"
      }

      if (!(Keys.publish / Keys.skip).value && Keys.publishArtifact.value) {
        mainModule = mainModule.copy(
          imports =
            "mill.javalib.PublishModule" +: "mill.javalib.publish.*" +: mainModule.imports,
          supertypes = mainModule.supertypes :+ "PublishModule",
          pomSettings = Some {
            val moduleInfo = Keys.projectInfo.value
            import moduleInfo._
            PomSettings(
              description = description,
              organization = Keys.organization.value,
              url = homepage.fold("")(_.toExternalForm),
              licenses = licenses.map {
                case (name, url) =>
                  ModuleSpec.License(
                    name = name,
                    url = url.toExternalForm,
                    // Hardcoded by sbt
                    distribution = "repo"
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
            )
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
      val hasScalafixPlugin = hasAutoPlugin("scalafix.sbt.ScalafixPlugin")
      val hasScalafmtPlugin = hasAutoPlugin("org.scalafmt.sbt.ScalafmtPlugin")
      val hasTestUnmanagedSources = (Test / Keys.unmanagedSources).value.nonEmpty
      if ((Compile / Keys.unmanagedSources).value.nonEmpty) {
        if (hasAutoPlugin("pl.project13.scala.sbt.JmhPlugin")) {
          Keys.libraryDependencies.value.collectFirst {
            case dep if dep.organization == "org.openjdk.jmh" =>
              mainModule = mainModule.withJmhModule(value(Some(dep.revision)))
          }
        }
        if (hasScalafixPlugin) {
          mainModule = withScalafixModule(
            mainModule,
            (Compile / scalafixDependencies).get(structure.data).getOrElse(Nil).distinct
              .map(toMvnDep),
            mainSnippets
          )
        }
        if (hasScalafmtPlugin) {
          mainModule = withScalafmtModule(mainModule)
        }
        if (hasAutoPlugin("scoverage.ScoverageSbtPlugin") && hasTestUnmanagedSources) {
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
                  branchCoverageMin =
                    coverageMinimumBranchTotal.get(structure.data).filter(_ != 0.0),
                  statementCoverageMin =
                    coverageMinimumStmtTotal.get(structure.data).filter(_ != 0.0)
                )
                if (isCrossPlatform) {
                  mainSnippets += Snippet(
                    """override lazy val scoverage = new ScoverageData {
                      |  def platformSuffix = outer.platformSuffix()
                      |}""".stripMargin,
                    "ScoverageModule"
                  )
                }
            }
        }
      }

      if (hasTestUnmanagedSources) {
        val testMvnDeps = mvnDeps("test")
        val testMixin = ModuleSpec.testModuleMixin(testMvnDeps)
        var testModule = ModuleSpec(
          name = "test",
          supertypes = mainModule.supertypes.collect {
            case "CrossSbtPlatformModule" => "CrossSbtPlatformTests"
            case "CrossSbtModule" => "CrossSbtTests"
            case "SbtPlatformModule" => "SbtPlatformTests"
            case "SbtModule" => "SbtTests"
            case "ScalaJSModule" => "ScalaJSTests"
            case "ScalaNativeModule" => "ScalaNativeTests"
            case "ScoverageModule" => "ScoverageTests"
          } ++ testMixin,
          mvnDeps = values(testMvnDeps).copy(appendSuper = isScalaNativeModule),
          compileMvnDeps = mainModule.compileMvnDeps,
          runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
          moduleDeps = values(
            moduleDeps(_ == "test") ++
              moduleDeps(_ == "test->test", childSegment = Some("test"))
          ).copy(appendSuper = true),
          compileModuleDeps = mainModule.compileModuleDeps.base,
          runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
          testFramework = if (testMixin.isEmpty)
            testMvnDeps.collectFirst {
              case dep if dep.organization == "com.eed3si9n.verify" => "verify.runner.Framework"
            }.orElse(Some(""))
          else None
        )
        val testSnippets = Seq.newBuilder[Snippet]
        if (hasScalafixPlugin) {
          testModule = withScalafixModule(
            testModule,
            (Test / scalafixDependencies).get(structure.data).getOrElse(Nil).distinct.map(toMvnDep),
            testSnippets,
            isTest = true
          )
        }
        if (hasScalafmtPlugin) {
          testModule = withScalafmtModule(testModule, isTest = true)
        }
        if ((Test / Keys.fork).value) {
          testSnippets += "def forkWorkingDir = this.moduleDir"
        }
        testSnippets ++= Seq(
          "def testParallelism = false",
          "def testSandboxWorkingDir = false"
        )
        testModule = testModule.copy(snippets = testSnippets.result())
        mainModule = mainModule.copy(children = mainModule.children :+ testModule)
      }
      mainModule = mainModule.copy(snippets = mainSnippets.result()).withAlias()

      Using.resource(os.write.outputStream(outFile))(
        upickle.default.writeToOutputStream(SbtModuleSpec(mainRoot, mainModule), _)
      )
    })
  }

  def skipDep(dep: ModuleID) = {
    import dep.{name, organization => org}
    ((name.startsWith("scala-library") || name.startsWith("scala3-library"))
      && org == "org.scala-lang") ||
    (name.startsWith("dotty") && org == "ch.epfl.lamp") ||
    (!name.startsWith("scalajs-dom") && org == "org.scala-js") ||
    (org == "org.scala-native")
  }

  // Cleanup `CrossType.Pure` module names.
  private def toModuleName(dirName: String) = dirName.stripPrefix(".")

  private def toModuleDir(baseDir: os.Path) = baseDir.subRelativeTo(os.pwd).segments match {
    case Seq() => os.sub
    case init :+ last => os.sub / os.SubPath(init :+ toModuleName(last))
  }

  private def withScalafixModule(
      module: ModuleSpec,
      scalafixIvyDeps: Values[MvnDep],
      snippets: mutable.Builder[Snippet, Seq[Snippet]],
      isTest: Boolean = false
  ) = {
    var module0 = module
    module0 = module0.copy(
      imports = "com.goyeau.mill.scalafix.ScalafixModule" +: module0.imports,
      supertypes = if (isTest) "ScalafixModule" +: module0.supertypes
      else module0.supertypes :+ "ScalafixModule",
      scalafixIvyDeps = scalafixIvyDeps
    )
    if (os.exists(os.pwd / ".scalafix.conf")) {
      module0 = module0.copy(imports = "mill.api.*" +: module0.imports)
      snippets += Snippet(
        "def scalafixConfig = Some(BuildCtx.workspaceRoot / \".scalafix.conf\")",
        "ScalafixModule"
      )
    }
    module0
  }

  private def withScalafmtModule(module: ModuleSpec, isTest: Boolean = false) = {
    module.copy(
      imports = "mill.scalalib.scalafmt.ScalafmtModule" +: module.imports,
      supertypes = if (isTest) "ScalafmtModule" +: module.supertypes
      else module.supertypes :+ "ScalafmtModule"
    )
  }
}
