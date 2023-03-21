package mill.scalalib

import scala.collection.immutable
import scala.util.Try
import scala.xml.{Elem, MetaData, Node, NodeSeq, Null, UnprefixedAttribute}
import mill.util.SpecialClassLoader
import coursier.core.compatibility.xmlParseDom
import coursier.maven.Pom
import coursier.{LocalRepositories, Repositories, Repository}

import java.nio.file.Paths
import mill.Agg
import mill.api.Ctx.{Home, Log}
import mill.api.{PathRef, Result, Strict}
import mill.define._
import mill.eval.Evaluator
import mill.modules.Util
import mill.scalalib.GenIdeaModule.{IdeaConfigFile, JavaFacet}
import mill.{BuildInfo, T, scalalib}
import os.{Path, SubPath}

case class GenIdeaImpl(
    evaluator: Evaluator,
    ctx: Log with Home,
    rootModule: BaseModule,
    discover: Discover[_]
) {
  import GenIdeaImpl._

  val workDir: Path = rootModule.millSourcePath
  val ideaDir: Path = workDir / ".idea"

  val ideaConfigVersion = 4

  def run(): Unit = {

    val pp = new scala.xml.PrettyPrinter(999, 4)
    val jdkInfo = extractCurrentJdk(ideaDir / "misc.xml")
      .getOrElse(("JDK_1_8", "1.8 (1)"))

    ctx.log.info("Analyzing modules ...")
    val layout: Seq[(SubPath, Node)] =
      xmlFileLayout(evaluator, rootModule, jdkInfo, Some(ctx))

    ctx.log.debug("Cleaning obsolete IDEA project files ...")
    os.remove.all(ideaDir / "libraries")
    os.remove.all(ideaDir / "scala_compiler.xml")
    os.remove.all(ideaDir / "mill_modules")

    ctx.log.info(s"Writing ${layout.size} IDEA project files to ${ideaDir} ...")
    for ((subPath, xml) <- layout) {
      ctx.log.debug(s"Writing ${subPath} ...")
      os.write.over(ideaDir / subPath, pp.format(xml), createFolders = true)
    }
  }

  def extractCurrentJdk(ideaPath: os.Path): Option[(String, String)] = {
    import scala.xml.XML
    Try {
      val xml = XML.loadFile(ideaPath.toString)
      (xml \\ "component")
        .filter(x => x.attribute("project-jdk-type").map(_.text).contains("JavaSDK"))
        .map { n =>
          (n.attribute("languageLevel"), n.attribute("project-jdk-name"))
        }
        .collectFirst { case (Some(lang), Some(jdk)) => (lang.text, jdk.text) }
    }.getOrElse(None)
  }

  def xmlFileLayout(
      evaluator: Evaluator,
      rootModule: mill.Module,
      jdkInfo: (String, String),
      ctx: Option[Log],
      fetchMillModules: Boolean = true
  ): Seq[(os.SubPath, scala.xml.Node)] = {

    val modules: Seq[(Segments, JavaModule)] =
      rootModule.millInternal.segmentsToModules.values
        .collect { case x: scalalib.JavaModule => x }
        .flatMap(_.transitiveModuleDeps)
        .filterNot(_.skipIdea)
        .map(x => (x.millModuleSegments, x))
        .toSeq
        .distinct

    val buildLibraryPaths: immutable.Seq[Path] =
      if (!fetchMillModules) Nil
      else
        Util.millProperty("MILL_BUILD_LIBRARIES") match {
          case Some(found) => found.split(',').map(os.Path(_)).distinct.toList
          case None =>
            val moduleRepos = Evaluator.evalOrThrow(
              evaluator = evaluator,
              exceptionFactory = r =>
                GenIdeaException(
                  s"Failure during resolving repositories: ${Evaluator.formatFailing(r)}"
                )
            )(modules.map(_._2.repositoriesTask))

            val repos = moduleRepos.foldLeft(Set.empty[Repository])(_ ++ _) ++ Set(
              LocalRepositories.ivy2Local,
              Repositories.central
            )
            val millDeps = BuildInfo.millEmbeddedDeps.map(d => ivy"$d").map(dep =>
              BoundDep(Lib.depToDependency(dep, BuildInfo.scalaVersion, ""), dep.force)
            )
            val Result.Success(res) = scalalib.Lib.resolveDependencies(
              repositories = repos.toList,
              deps = millDeps,
              sources = false,
              mapDependencies = None,
              customizer = None,
              coursierCacheCustomizer = None,
              ctx = ctx
            )

            // Also trigger resolve sources, but don't use them (will happen implicitly by Idea)
            {
              scalalib.Lib.resolveDependencies(
                repositories = repos.toList,
                deps = millDeps,
                sources = true,
                mapDependencies = None,
                customizer = None,
                coursierCacheCustomizer = None,
                ctx = ctx
              )
            }

            res.items.toList.map(_.path)
        }

    val buildDepsPaths =
      Try(evaluator.rootModule.getClass.getClassLoader.asInstanceOf[SpecialClassLoader])
        .map {
          _.allJars
            .map(url => os.Path(Paths.get(url.toURI)))
            .filter(_.toIO.exists)
        }
        .getOrElse(Seq())

    def resolveTasks: Seq[Task[ResolvedModule]] = modules.map {
      case (path, mod) => {

        // same as input of resolvedIvyDeps
        val allIvyDeps = T.task {
          mod.transitiveIvyDeps() ++ mod.transitiveCompileIvyDeps()
        }

        val scalaCompilerClasspath = mod match {
          case x: ScalaModule => x.scalaCompilerClasspath
          case _ =>
            T.task {
              Agg.empty[PathRef]
            }
        }

        val externalLibraryDependencies = T.task {
          mod.resolveDeps(T.task {
            val bind = mod.bindDependency()
            mod.mandatoryIvyDeps().map(bind)
          })()
        }

        val externalDependencies = T.task {
          mod.resolvedIvyDeps() ++
            T.traverse(mod.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
        }
        val extCompileIvyDeps =
          mod.resolveDeps(T.task {
            val bind = mod.bindDependency()
            mod.compileIvyDeps().map(bind)
          })
        val extRunIvyDeps = mod.resolvedRunIvyDeps

        val externalSources = T.task {
          mod.resolveDeps(allIvyDeps, sources = true)()
        }

        val (scalacPluginsIvyDeps, allScalacOptions) = mod match {
          case mod: ScalaModule => (
              T.task(mod.scalacPluginIvyDeps()),
              T.task(mod.allScalacOptions())
            )
          case _ => (T.task { Agg[Dep]() }, T.task { Seq() })
        }

        val scalacPluginDependencies = T.task {
          mod.resolveDeps(T.task {
            val bind = mod.bindDependency()
            scalacPluginsIvyDeps().map(bind)
          })()
        }

        val facets = T.task {
          mod.ideaJavaModuleFacets(ideaConfigVersion)()
        }

        val configFileContributions = T.task {
          mod.ideaConfigFiles(ideaConfigVersion)()
        }

        val compilerOutput = T.task {
          mod.ideaCompileOutput()
        }

        T.task {
          val resolvedCp: Agg[Scoped[Path]] =
            externalDependencies().map(_.path).map(Scoped(_, None)) ++
              extCompileIvyDeps()
                .map(_.path)
                .map(Scoped(_, Some("PROVIDED"))) ++
              extRunIvyDeps().map(_.path).map(Scoped(_, Some("RUNTIME")))
          // unused, but we want to trigger sources, to have them available (automatically)
          // TODO: make this a separate eval to handle resolve errors
          val resolvedSrcs: Agg[PathRef] = externalSources()
          val resolvedSp: Agg[PathRef] = scalacPluginDependencies()
          val resolvedCompilerCp: Agg[PathRef] =
            scalaCompilerClasspath()
          val resolvedLibraryCp: Agg[PathRef] =
            externalLibraryDependencies()
          val scalacOpts: Seq[String] = allScalacOptions()
          val resolvedFacets: Seq[JavaFacet] = facets()
          val resolvedConfigFileContributions: Seq[IdeaConfigFile] =
            configFileContributions()
          val resolvedCompilerOutput = compilerOutput()

          ResolvedModule(
            path = path,
            // FIXME: why do we need to sources in the classpath?
            // FIXED, was: classpath = resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path),
            classpath = resolvedCp.filter(_.value.ext == "jar"),
            module = mod,
            pluginClasspath = resolvedSp.map(_.path).filter(_.ext == "jar"),
            scalaOptions = scalacOpts,
            compilerClasspath = resolvedCompilerCp.map(_.path),
            libraryClasspath = resolvedLibraryCp.map(_.path),
            facets = resolvedFacets,
            configFileContributions = resolvedConfigFileContributions,
            compilerOutput = resolvedCompilerOutput.path
          )
        }
      }
    }

    val resolvedModules: Seq[ResolvedModule] =
      evaluator.evaluate(resolveTasks) match {
        case r if r.failing.items().nonEmpty =>
          throw GenIdeaException(s"Failure during resolving modules: ${Evaluator.formatFailing(r)}")
        case r => r.values.asInstanceOf[Seq[ResolvedModule]]
      }

    val moduleLabels = modules.map(_.swap).toMap

    val allResolved: Seq[Path] =
      (resolvedModules.flatMap(_.classpath).map(_.value) ++ buildLibraryPaths ++ buildDepsPaths)
        .distinct
        .sorted

    val librariesProperties: Map[Path, Agg[Path]] =
      resolvedModules
        .flatMap(x => x.libraryClasspath.map(_ -> x.compilerClasspath))
        .toMap

    val (wholeFileConfigs, configFileContributions) =
      resolvedModules
        .flatMap(_.configFileContributions)
        .partition(_.asWholeFile.isDefined)

    // whole file
    val ideaWholeConfigFiles: Seq[(SubPath, Elem)] =
      wholeFileConfigs.flatMap(_.asWholeFile).map { wf =>
        os.sub / wf._1 -> ideaConfigElementTemplate(wf._2)
      }

    type FileComponent = (SubPath, String)

    /** Ensure, the additional configs don't collide. */
    def collisionFreeExtraConfigs(
        confs: Seq[IdeaConfigFile]
    ): Map[SubPath, Seq[IdeaConfigFile]] = {

      var seen: Map[FileComponent, Seq[GenIdeaModule.Element]] = Map()
      var result: Map[SubPath, Seq[IdeaConfigFile]] = Map()
      confs.foreach { conf =>
        val key = conf.subPath -> conf.component
        seen.get(key) match {
          case None =>
            seen += key -> conf.config
            result += conf.subPath -> (result
              .get(conf.subPath)
              .getOrElse(Seq()) ++ Seq(conf))
          case Some(existing) if conf.config == existing =>
          // identical, ignore
          case Some(existing) =>
            def details(elements: Seq[GenIdeaModule.Element]) = {
              elements.map(
                ideaConfigElementTemplate(_).toString().replaceAll("\\n", "")
              )
            }
            val msg =
              s"Config collision in file `${conf.name}` and component `${conf.component}`: ${details(
                  conf.config
                )} vs. ${details(existing)}"
            ctx.map(_.log.error(msg))
        }
      }
      result
    }

    val fileComponentContributions: Seq[(SubPath, Elem)] =
      collisionFreeExtraConfigs(configFileContributions).toSeq.map {
        case (file, configs) =>
          val map: Map[String, Seq[GenIdeaModule.Element]] =
            configs
              .groupBy(_.component)
              .view
              .mapValues(_.flatMap(_.config))
              .toMap
          file -> ideaConfigFileTemplate(map)
      }

    val pathShortLibNameDuplicate = allResolved
      .groupBy(_.last)
      .filter(_._2.size > 1)
      .view
      .mapValues(_.sorted)
      .mapValues(_.zipWithIndex)
      .flatMap(y =>
        y._2.map {
          case (path, 0) => path -> y._1
          case (path, idx) => path -> s"${y._1} (${idx})"
        }
      )
      .toMap

    val pathToLibName = allResolved
      .map(p => p -> pathShortLibNameDuplicate.getOrElse(p, p.last))
      .toMap

    type ArtifactAndVersion = (String, String)

    def guessJarArtifactNameAndVersionFromPath(
        path: Path
    ): Option[ArtifactAndVersion] =
      Try {
        // in a local maven repo or a local Coursier repo,
        // the dir-layout reflects the jar coordinates
        val fileName = path.last
        val parentDir = (path / os.up).last
        val grandParentDir = (path / os.up / os.up).last
        if (fileName.startsWith(s"${grandParentDir}-${parentDir}")) {
          // could be a maven or coursier repo
          Some((grandParentDir, parentDir))
        } else {
          None
        }
      }.toOption.flatten

    // Tries to group jars with their poms and sources.
    def toResolvedJar(path: os.Path): Option[ResolvedLibrary] = {
      val guessedArtifactVersion = guessJarArtifactNameAndVersionFromPath(path)
      val inCoursierCache = path.startsWith(os.Path(coursier.paths.CoursierPaths.cacheDirectory()))
      val inIvyLikeLocal = (path / os.up).last == "jars"
      def inMavenLikeLocal = guessedArtifactVersion.isDefined
      val isSource = path.last.endsWith("sources.jar")
      val isPom = path.ext == "pom"
      val baseName = guessedArtifactVersion
        .map(av => s"${av._1}-${av._2}")
        .getOrElse(path.baseName)

      if (inCoursierCache && (isSource || isPom)) {
        // Remove sources and pom as they'll be recovered from the jar path
        None
      } else if (inCoursierCache && path.ext == "jar") {
        val pom = path / os.up / s"$baseName.pom"
        val sources = Some(path / os.up / s"$baseName-sources.jar")
          .filter(_.toIO.exists())
        Some(CoursierResolved(path, pom, sources))
      } else if (inIvyLikeLocal && path.ext == "jar") {
        // assume some jvy-like dir structure
        val sources =
          Some(path / os.up / os.up / "srcs" / s"${path.baseName}-sources.jar")
            .filter(_.toIO.exists())
        Some(WithSourcesResolved(path, sources))
      } else if (inMavenLikeLocal) {
        // assume some maven-like dir structure
        val sources = Some(path / os.up / s"${baseName}-sources.jar")
          .filter(_.toIO.exists())
        Some(WithSourcesResolved(path, sources))
      } else {
        Some(OtherResolved(path))
      }
    }

    /**
     * We need to use a very specific library name format.
     * This is required in order IntelliJ IDEA can recognize `$ivy` imports in `build.sc` files and doesn't show red code.
     * This is how currently Ammonite integration is done in Scala Plugin for IntelliJ IDEA.
     *
     * @see [[https://github.com/JetBrains/intellij-scala/blob/idea223.x/scala/worksheet/src/org/jetbrains/plugins/scala/worksheet/ammonite/AmmoniteUtil.scala#L240]]
     * @example {{{
     *   //SBT: com.lihaoyi:ammonite-ops_2.13:2.2.0:jar
     *   import $ivy.`com.lihaoyi::ammonite-ops:2.2.0
     * }}}
     */
    def sbtLibraryNameFromPom(pomPath: os.Path): String = {
      val pom = xmlParseDom(os.read(pomPath)).flatMap(Pom.project)
        .getOrElse(throw new RuntimeException(s"Could not parse pom file: ${pomPath}"))

      val artifactId = pom.module.name.value
      val scalaArtifactRegex = ".*_[23]\\.[0-9]{1,2}".r
      val artifactWithScalaVersion = artifactId.substring(
        artifactId.length - math.min(5, artifactId.length)
      ) match {
        case scalaArtifactRegex(_*) => artifactId
        case _ =>
          // Default to the scala binary version used by mill itself
          s"${artifactId}_${BuildInfo.scalaVersion.split("[.]").take(2).mkString(".")}"
      }
      s"SBT: ${pom.module.organization.value}:$artifactWithScalaVersion:${pom.version}:jar"
    }

    def libraryNames(resolvedJar: ResolvedLibrary): Seq[String] =
      resolvedJar match {
        case CoursierResolved(path, pom, _) if buildDepsPaths.contains(path) && pom.toIO.exists() =>
          Seq(sbtLibraryNameFromPom(pom), pathToLibName(path))
        case CoursierResolved(path, _, _) =>
          Seq(pathToLibName(path))
        case WithSourcesResolved(path, _) =>
          Seq(pathToLibName(path))
        case OtherResolved(path) =>
          Seq(pathToLibName(path))
      }

    def resolvedLibraries(resolved: Seq[os.Path]): Seq[ResolvedLibrary] =
      resolved
        .map(toResolvedJar)
        .collect { case Some(r) => r }

    val compilerSettings = resolvedModules
      .foldLeft(Map[(Agg[os.Path], Seq[String]), Vector[JavaModule]]()) {
        (r, q) =>
          val key = (q.pluginClasspath, q.scalaOptions)
          r + (key -> (r.getOrElse(key, Vector()) :+ q.module))
      }

    val allBuildLibraries: Set[ResolvedLibrary] =
      resolvedLibraries(buildLibraryPaths ++ buildDepsPaths).toSet

    val fixedFiles: Seq[(SubPath, Elem)] = Seq(
      Tuple2(os.sub / "misc.xml", miscXmlTemplate(jdkInfo)),
      Tuple2(os.sub / "scala_settings.xml", scalaSettingsTemplate()),
      Tuple2(
        os.sub / "modules.xml",
        allModulesXmlTemplate(
          modules.map { case (segments, mod) => moduleName(segments) }.sorted
        )
      ),
      Tuple2(
        os.sub / "mill_modules" / "mill-build.iml",
        rootXmlTemplate(allBuildLibraries.flatMap(lib => libraryNames(lib)))
      ),
      Tuple2(
        os.sub / "scala_compiler.xml",
        scalaCompilerTemplate(compilerSettings)
      )
    )

    /**
     * @note `:` in path isn't supported on Windows ~ https://github.com/com-lihaoyi/mill/issues/2243<br>
     *       It comes from [[sbtLibraryNameFromPom]]
     */
    def libraryNameToFileSystemPathPart(name: String): String = {
      name.replaceAll("""[-.:]""", "_")
    }

    val libraries: Seq[(SubPath, Elem)] =
      resolvedLibraries(allResolved).flatMap { resolved =>
        val names = libraryNames(resolved)
        val sources = resolved match {
          case CoursierResolved(_, _, s) => s
          case WithSourcesResolved(_, s) => s
          case OtherResolved(_) => None
        }
        for (name <- names)
          yield {
            val compilerCp: Agg[Path] = librariesProperties.getOrElse(resolved.path, Agg.empty)
            val languageLevel = name match {
              case _ if compilerCp.iterator.isEmpty => None
              case _ if name.startsWith("scala3-library_3-3.3.") => Some("Scala_3_3")
              case _ if name.startsWith("scala3-library_3-3.2.") => Some("Scala_3_2")
              case _ if name.startsWith("scala3-library_3-3.1.") => Some("Scala_3_1")
              case _ if name.startsWith("scala3-library_3-3.0.") => Some("Scala_3_0")
              case _ if name.startsWith("scala-library-2.13.") => Some("Scala_2_13")
              case _ if name.startsWith("scala-library-2.12.") => Some("Scala_2_12")
              case _ if name.startsWith("scala-library-2.11.") => Some("Scala_2_11")
              case _ if name.startsWith("scala-library-2.10.") => Some("Scala_2_10")
              case _ if name.startsWith("scala-library-2.9.") => Some("Scala_2_9")
              case _ if name.startsWith("dotty-library-0.27") => Some("Scala_0_27")
              case _ => None
            }
            Tuple2(
              os.sub / "libraries" / s"${libraryNameToFileSystemPathPart(name)}.xml",
              libraryXmlTemplate(
                name = name,
                path = resolved.path,
                sources = sources,
                scalaCompilerClassPath = compilerCp,
                languageLevel = languageLevel
              )
            )
          }
      }

    val moduleFiles: Seq[(SubPath, Elem)] = resolvedModules.map {
      case ResolvedModule(
            path,
            resolvedDeps,
            mod,
            _,
            _,
            _,
            _,
            facets,
            _,
            compilerOutput
          ) =>
        val Seq(
          resourcesPathRefs: Seq[PathRef],
          sourcesPathRef: Seq[PathRef],
          generatedSourcePathRefs: Seq[PathRef],
          allSourcesPathRefs: Seq[PathRef]
        ) = evaluator
          .evaluate(
            Agg(
              mod.resources,
              mod.sources,
              mod.generatedSources,
              mod.allSources
            )
          )
          .values

        val generatedSourcePaths = generatedSourcePathRefs.map(_.path)
        val normalSourcePaths = (allSourcesPathRefs
          .map(_.path)
          .toSet -- generatedSourcePaths.toSet).toSeq

        val scalaVersionOpt = mod match {
          case x: ScalaModule =>
            Some(
              evaluator
                .evaluate(Agg(x.scalaVersion))
                .values
                .head
                .asInstanceOf[String]
            )
          case _ => None
        }

        val sanizedDeps: Seq[ScopedOrd[String]] = {
          resolvedDeps
            .map((s: Scoped[Path]) => pathToLibName(s.value) -> s.scope)
            .iterator
            .toSeq
            .groupBy(_._1)
            .view
            .mapValues(_.map(_._2))
            .map {
              case (lib, scopes) =>
                val isCompile = scopes.contains(None)
                val isProvided = scopes.contains(Some("PROVIDED"))
                val isRuntime = scopes.contains(Some("RUNTIME"))

                val finalScope = (isCompile, isProvided, isRuntime) match {
                  case (_, true, false) => Some("PROVIDED")
                  case (false, false, true) => Some("RUNTIME")
                  case _ => None
                }

                ScopedOrd(lib, finalScope)
            }
            .toSeq
        }

        val libNames = Strict.Agg.from(sanizedDeps).iterator.toSeq

        val depNames = Strict.Agg
          .from(mod.moduleDeps.map((_, None)) ++
            mod.compileModuleDeps.map((_, Some("PROVIDED"))))
          .filter(!_._1.skipIdea)
          .map { case (v, s) => ScopedOrd(moduleName(moduleLabels(v)), s) }
          .iterator
          .toSeq
          .distinct

        val isTest = mod.isInstanceOf[TestModule]

        val elem = moduleXmlTemplate(
          basePath = mod.intellijModulePath,
          scalaVersionOpt = scalaVersionOpt,
          resourcePaths = Strict.Agg.from(resourcesPathRefs.map(_.path)),
          normalSourcePaths = Strict.Agg.from(normalSourcePaths),
          generatedSourcePaths = Strict.Agg.from(generatedSourcePaths),
          compileOutputPath = compilerOutput,
          libNames = libNames,
          depNames = depNames,
          isTest = isTest,
          facets = facets
        )

        Tuple2(
          os.sub / "mill_modules" / s"${moduleName(path)}.iml",
          elem
        )
    }

    {
      // file collision checks
      val map =
        (fixedFiles ++ ideaWholeConfigFiles ++ fileComponentContributions)
          .groupBy(_._1)
          .filter(_._2.size > 1)
      if (map.nonEmpty) {
        ctx.map(_.log.error(
          s"Config file collisions detected. Check you `ideaConfigFiles` targets. Colliding files: ${map
              .map(_._1)}. All project files: ${map}"
        ))
      }
    }

    fixedFiles ++ ideaWholeConfigFiles ++ fileComponentContributions ++ libraries ++ moduleFiles
  }

  def relify(p: os.Path) = {
    val r = p.relativeTo(ideaDir / "mill_modules")
    (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
  }

  def ideaConfigElementTemplate(element: GenIdeaModule.Element): Elem = {

    val example = <config/>

    val attribute1: MetaData =
      if (element.attributes.isEmpty) Null
      else
        element.attributes.toSeq.reverse.foldLeft(Null.asInstanceOf[MetaData]) {
          case (prevAttr, (k, v)) =>
            new UnprefixedAttribute(k, v, prevAttr)
        }

    new Elem(
      prefix = null,
      label = element.name,
      attributes1 = attribute1,
      example.scope,
      minimizeEmpty = true,
      child = element.childs.map(ideaConfigElementTemplate): _*
    )
  }

  def ideaConfigFileTemplate(
      components: Map[String, Seq[GenIdeaModule.Element]]
  ): Elem = {
    <project version={"" + ideaConfigVersion}>
      {
      components.toSeq.map { case (name, config) =>
        <component name={name}>{config.map(ideaConfigElementTemplate)}</component>
      }
    }
    </project>
  }

  def scalaSettingsTemplate() = {
//    simpleIdeaConfigFileTemplate(Map("ScalaProjectSettings" -> Map("scFileMode" -> "Ammonite")))
    <project version={"" + ideaConfigVersion}>
      <component name="ScalaProjectSettings">
        <option name="scFileMode" value="Ammonite" />
      </component>
    </project>
  }
  def miscXmlTemplate(jdkInfo: (String, String)) = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectRootManager" version="2" languageLevel={jdkInfo._1} project-jdk-name={
      jdkInfo._2
    } project-jdk-type="JavaSDK">
        <output url="file://$PROJECT_DIR$/target/idea_output"/>
      </component>
    </project>
  }

  def allModulesXmlTemplate(selectors: Seq[String]) = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectModuleManager">
        <modules>
          <module
            fileurl="file://$PROJECT_DIR$/.idea/mill_modules/mill-build.iml"
            filepath="$PROJECT_DIR$/.idea/mill_modules/mill-build.iml"
          />
          {
      for (selector <- selectors)
        yield {
          val filepath = "$PROJECT_DIR$/.idea/mill_modules/" + selector + ".iml"
          val fileurl = "file://" + filepath
          <module fileurl={fileurl} filepath={filepath} />
        }
    }
        </modules>
      </component>
    </project>
  }
  def rootXmlTemplate(libNames: Strict.Agg[String]): scala.xml.Elem = {
    <module type="JAVA_MODULE" version={"" + ideaConfigVersion}>
      <component name="NewModuleRootManager">
        <output url="file://$MODULE_DIR$/../../out/ideaOutputDir-mill-build"/>
        <content url="file://$MODULE_DIR$/../..">
          <excludeFolder url="file://$MODULE_DIR$/../../project" />
          <excludeFolder url="file://$MODULE_DIR$/../../target" />
          <excludeFolder url="file://$MODULE_DIR$/../../out" />
        </content>
        <exclude-output/>
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
        {
      for (name <- libNames.toSeq.sorted)
        yield <orderEntry type="library" name={name} level="project" />
    }
      </component>
    </module>
  }

  /** Try to make the file path a relative JAR URL (to PROJECT_DIR). */
  def relativeJarUrl(path: os.Path): String = {
    // When coursier cache dir is on different logical drive than project dir
    // we can not use a relative path. See issue: https://github.com/lihaoyi/mill/issues/905
    val relPath = relForwardPath(path, "$PROJECT_DIR$/")
    if (path.ext == "jar") "jar://" + relPath + "!/" else "file://" + relPath
  }

  /** Try to make the file path a relative URL (to PROJECT_DIR). */
  def relativeFileUrl(path: Path): String = {
    // When coursier cache dir is on different logical drive than project dir
    // we can not use a relative path. See issue: https://github.com/lihaoyi/mill/issues/905
    "file://" + relForwardPath(path, "$PROJECT_DIR$/")
  }

  private def relForwardPath(path: os.Path, prefix: String): String = {
    def forward(p: os.FilePath): String = p.toString().replace("""\""", "/")
    Try(prefix + forward(path.relativeTo(workDir))).getOrElse(forward(path))
  }

  def libraryXmlTemplate(
      name: String,
      path: os.Path,
      sources: Option[os.Path],
      scalaCompilerClassPath: Agg[Path],
      languageLevel: Option[String]
  ): Elem = {
    val isScalaLibrary = scalaCompilerClassPath.iterator.nonEmpty
    <component name="libraryTable">
      <library name={name} type={if (isScalaLibrary) "Scala" else null}>
        {
      if (isScalaLibrary) {
        <properties>
        {
          if (languageLevel.isDefined) <language-level>{languageLevel.get}</language-level>
        }
        <compiler-classpath>
            {
          scalaCompilerClassPath.iterator.toSeq.sortBy(_.wrapped).map(p =>
            <root url={relativeFileUrl(p)}/>
          )
        }
          </compiler-classpath>
        </properties>
      }
    }
        <CLASSES>
          <root url={relativeJarUrl(path)}/>
        </CLASSES>
        {
      if (sources.isDefined) {
        <SOURCES>
            <root url={relativeJarUrl(sources.get)}/>
          </SOURCES>
      }
    }
      </library>
    </component>
  }
  def moduleXmlTemplate(
      basePath: os.Path,
      scalaVersionOpt: Option[String],
      resourcePaths: Strict.Agg[os.Path],
      normalSourcePaths: Strict.Agg[os.Path],
      generatedSourcePaths: Strict.Agg[os.Path],
      compileOutputPath: os.Path,
      libNames: Seq[ScopedOrd[String]],
      depNames: Seq[ScopedOrd[String]],
      isTest: Boolean,
      facets: Seq[GenIdeaModule.JavaFacet]
  ): Elem = {
    <module type="JAVA_MODULE" version={"" + ideaConfigVersion}>
      <component name="NewModuleRootManager">
        {
      val outputUrl = "file://$MODULE_DIR$/" + relify(compileOutputPath)
      if (isTest)
        <output-test url={outputUrl} />
      else
        <output url={outputUrl} />
    }
        <exclude-output />
        {
      for (generatedSourcePath <- generatedSourcePaths.toSeq.distinct.sorted) yield {
        val rel = relify(generatedSourcePath)
        <content url={"file://$MODULE_DIR$/" + rel}>
                <sourceFolder url={"file://$MODULE_DIR$/" + rel} isTestSource={
          isTest.toString
        } generated="true" />
              </content>
      }
    }

        {
      // keep the "real" base path as last content, to ensure, Idea picks it up as "main" module dir
      for (normalSourcePath <- normalSourcePaths.iterator.toSeq.sorted) yield {
        val rel = relify(normalSourcePath)
        <content url={"file://$MODULE_DIR$/" + rel}>
              <sourceFolder url={"file://$MODULE_DIR$/" + rel} isTestSource={isTest.toString} />
            </content>
      }
    }
        {
      val resourceType = if (isTest) "java-test-resource" else "java-resource"
      for (resourcePath <- resourcePaths.iterator.toSeq.sorted) yield {
        val rel = relify(resourcePath)
        <content url={"file://$MODULE_DIR$/" + rel}>
                <sourceFolder url={"file://$MODULE_DIR$/" + rel} type={resourceType} />
              </content>
      }
    }
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />

        {
      for (dep <- depNames.sorted)
        yield dep.scope match {
          case None => <orderEntry type="module" module-name={dep.value} exported="" />
          case Some(scope) =>
            <orderEntry type="module" module-name={dep.value} exported="" scope={scope} />
        }
    }
        {
      for (name <- libNames.sorted)
        yield name.scope match {
          case None => <orderEntry type="library" name={name.value} level="project" />
          case Some(scope) =>
            <orderEntry type="library" scope={scope} name={name.value} level="project" />
        }

    }
      </component>
      {
      if (facets.isEmpty) NodeSeq.Empty
      else {
        <component name="FacetManager">
            {
          for (facet <- facets) yield {
            <facet type={facet.`type`} name={facet.name}>
                  {ideaConfigElementTemplate(facet.config)}
                </facet>
          }
        }
          </component>
      }
    }
    </module>
  }
  def scalaCompilerTemplate(
      settings: Map[(Agg[os.Path], Seq[String]), Seq[JavaModule]]
  ) = {

    <project version={"" + ideaConfigVersion}>
      <component name="ScalaCompilerConfiguration">
        {
      for ((((plugins, params), mods), i) <- settings.toSeq.zip(1 to settings.size))
        yield <profile name={s"mill $i"} modules={
          mods.map(m => moduleName(m.millModuleSegments)).mkString(",")
        }>
            <parameters>
              {
          for (param <- params)
            yield <parameter value={param} />
        }
            </parameters>
            <plugins>
              {
          for (plugin <- plugins.toSeq)
            yield <plugin path={plugin.toString} />
        }
            </plugins>
          </profile>
    }
      </component>
    </project>
  }
}

object GenIdeaImpl {

  /**
   * Create the module name (to be used by Idea) for the module based on it segments.
   * @see [[Module.millModuleSegments]]
   */
  def moduleName(p: Segments): String =
    p.value
      .foldLeft(new StringBuilder()) {
        case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
        case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
        case (sb, Segment.Label(s)) => sb.append(".").append(s)
        case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
      }
      .mkString
      .toLowerCase()

  sealed trait ResolvedLibrary { def path: os.Path }
  final case class CoursierResolved(path: os.Path, pom: os.Path, sources: Option[os.Path])
      extends ResolvedLibrary
  final case class OtherResolved(path: os.Path) extends ResolvedLibrary
  final case class WithSourcesResolved(path: os.Path, sources: Option[os.Path])
      extends ResolvedLibrary

  final case class Scoped[T](value: T, scope: Option[String])

  final case class ScopedOrd[T <: Comparable[T]](value: T, scope: Option[String])
      extends Ordered[ScopedOrd[T]] {
    override def compare(that: ScopedOrd[T]): Int =
      value.compareTo(that.value) match {
        case 0 =>
          (scope, that.scope) match {
            case (None, None) => 0
            case (Some(l), Some(r)) => l.compare(r)
            case (None, _) => -1
            case (_, None) => +1
          }
        case x => x
      }
  }
  object ScopedOrd {
    def apply[T <: Comparable[T]](scoped: Scoped[T]): ScopedOrd[T] =
      ScopedOrd(scoped.value, scoped.scope)
  }

  final case class ResolvedModule(
      path: Segments,
      classpath: Agg[Scoped[Path]],
      module: JavaModule,
      pluginClasspath: Agg[Path],
      scalaOptions: Seq[String],
      compilerClasspath: Agg[Path],
      libraryClasspath: Agg[Path],
      facets: Seq[JavaFacet],
      configFileContributions: Seq[IdeaConfigFile],
      compilerOutput: Path
  )

  case class GenIdeaException(msg: String) extends RuntimeException

}
