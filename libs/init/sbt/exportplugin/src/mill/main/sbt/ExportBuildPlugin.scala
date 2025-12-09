package mill.main.sbt

import _root_.sbt.{Value => _, _}
import mill.main.buildgen.ModuleSpec
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
      val isCrossVersion = Keys.crossScalaVersions.value.length > 1
      // Base values are duplicated for ease of processing when combining cross-version specs.
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

      def hasAutoPlugin(label: String) = project.autoPlugins.exists(_.label == label)
      val hasScalaJSPlugin = hasAutoPlugin("org.scalajs.sbtplugin.ScalaJSPlugin")
      val hasScalaNativePlugin = hasAutoPlugin("scala.scalanative.sbtplugin.ScalaNativePlugin")
      val structure = Project.structure(Keys.state.value)

      def isBaseDirShared(baseDir: File) =
        structure.allProjects.count(p => p.aggregate.isEmpty && p.base == baseDir) > 1
      val useOuterModuleDir = isBaseDirShared(project.base)
      val baseDir = os.Path(project.base)
      val crossProjectBaseDir = crossProjectBaseDirectory.?.value.map(os.Path(_)).orElse(
        if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) Some(baseDir / os.up) else None
      )
      val isCrossPlatform = crossProjectBaseDir.nonEmpty
      var mainModule = ModuleSpec(
        name = if (useOuterModuleDir) project.id else toModuleName(baseDir.last),
        imports = Seq("import mill.scalalib.*"),
        supertypes = Seq((isCrossPlatform, isCrossVersion) match {
          case (true, true) => "CrossSbtPlatformModule"
          case (true, _) => "SbtPlatformModule"
          case (_, true) => "CrossSbtModule"
          case _ => "SbtModule"
        }),
        mixins =
          if (
            (Compile / Keys.unmanagedSourceDirectories).value
              .exists(dir => Seq('+', '-').contains(dir.name.last))
          ) Seq("CrossScalaVersionRanges")
          else Nil,
        useOuterModuleDir = useOuterModuleDir,
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
        (hasScalaJSPlugin && !name.startsWith("scalajs-dom") && org == "org.scala-js") ||
        (hasScalaNativePlugin && org == "org.scala-native")
      }
      val configDeps = Keys.libraryDependencies.value.collect {
        case dep if !skipDep(dep) => (dep, dep.configurations.getOrElse("compile").split(';').toSeq)
      }
      def mvnDeps(configs: String*) = configDeps.collect {
        case (dep, configs0) if configs.exists(configs0.contains) => toMvnDep(dep)
      }
      val configProjectDeps = project.dependencies
        .map(dep => dep -> dep.configuration.getOrElse("compile").split(";").toSeq)
      def moduleDeps(p: String => Boolean, nestedSegment: Option[String] = None): Seq[ModuleDep] =
        configProjectDeps.flatMap {
          case (dep, configs) if configs.exists(p) =>
            (dep.project / Keys.baseDirectory).get(structure.data).flatMap { depBaseDir =>
              var depModuleDir = toModuleDir(os.Path(depBaseDir))
              if (isBaseDirShared(depBaseDir))
                depModuleDir = depModuleDir / os.RelPath(dep.project.project)
              val depCrossScalaVersions =
                (dep.project / Keys.crossScalaVersions).get(structure.data).getOrElse(Nil)
              if (depCrossScalaVersions.contains(scalaVersion)) {
                val crossSuffix = if (depCrossScalaVersions.length < 2) None
                else Some(if (isCrossVersion) "()" else s"""("$scalaVersion")""")
                Some(ModuleDep(depModuleDir.segments, crossSuffix, nestedSegment))
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
        scalacPluginMvnDeps = mvnDeps("plugin->default(compile)"),
        sourcesRootFolders = values(
          if (crossProjectBaseDir.exists(dir => os.isDir(dir / "shared"))) Seq(os.sub / "shared")
          else Nil
        ).copy(appendSuper = true)
      )

      if (!(Keys.publish / Keys.skip).value && Keys.publishArtifact.value) {
        mainModule = mainModule.copy(
          imports =
            "import mill.javalib.PublishModule" +: "import mill.javalib.publish.*" +: mainModule.imports,
          supertypes = "PublishModule" +: mainModule.supertypes,
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

      if (hasScalaJSPlugin) {
        Keys.libraryDependencies.value.collectFirst {
          case dep
              if dep.organization == "org.scala-js" && dep.name.startsWith("scalajs-library") =>
            mainModule = mainModule.copy(
              imports =
                "import mill.scalajslib.ScalaJSModule" +: "import mill.scalajslib.api.*" +: mainModule.imports,
              supertypes = "ScalaJSModule" +: mainModule.supertypes,
              scalaJSVersion = Some(dep.revision),
              moduleKind = millScalaJSModuleKind.value
            )
        }
      }
      if (hasScalaNativePlugin) {
        Keys.libraryDependencies.value.collectFirst {
          case dep
              if dep.organization == "org.scala-native" &&
                dep.configurations.contains("plugin->default(compile)") =>
            mainModule = mainModule.copy(
              imports =
                "import mill.scalanativelib.ScalaNativeModule" +: "import mill.scalanativelib.api.*" +: mainModule.imports,
              supertypes = "ScalaNativeModule" +: mainModule.supertypes,
              scalaNativeVersion = Some(dep.revision)
            )
        }
      }
      if ((Test / Keys.sourceDirectories).value.exists(_.exists)) {
        val testMvnDeps = mvnDeps("test")
        ModuleSpec.testModuleMixin(testMvnDeps).foreach { mixin =>
          val supertypes = mainModule.supertypes.collect {
            case "ScalaJSModule" => "ScalaJSTests"
            case "ScalaNativeModule" => "ScalaNativeTests"
            case "CrossSbtPlatformModule" => "CrossSbtPlatformTests"
            case "SbtPlatformModule" => "SbtPlatformTests"
            case "CrossSbtModule" => "CrossSbtTests"
            case "SbtModule" => "SbtTests"
          }
          val testModule = ModuleSpec(
            name = "test",
            supertypes = supertypes,
            mixins = Seq(mixin),
            mvnDeps = testMvnDeps,
            compileMvnDeps = mainModule.compileMvnDeps,
            runMvnDeps = mainModule.compileMvnDeps.base ++ mainModule.runMvnDeps.base,
            moduleDeps = values(
              moduleDeps(_ == "test") ++
                moduleDeps(_ == "test->test", nestedSegment = Some("test"))
            ).copy(appendSuper = true),
            compileModuleDeps = mainModule.compileModuleDeps.base,
            runModuleDeps = mainModule.compileModuleDeps.base ++ mainModule.runModuleDeps.base,
            testParallelism = Some(false),
            testSandboxWorkingDir = Some(false)
          )
          mainModule = mainModule.copy(test = Some(testModule))
        }
      }

      val moduleDir = toModuleDir(crossProjectBaseDir.getOrElse(baseDir))
      val exportedData = SbtModuleSpec(
        Either.cond(isCrossPlatform || useOuterModuleDir, moduleDir, moduleDir),
        mainModule
      )
      Using.resource(os.write.outputStream(outFile))(
        upickle.default.writeToOutputStream(exportedData, _)
      )
    })
  }

  private def skipScalacOption(s: String) =
    s.startsWith("-P") || s.startsWith("-Xplugin") || s.startsWith("-scalajs")

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
