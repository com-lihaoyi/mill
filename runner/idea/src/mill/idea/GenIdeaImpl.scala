package mill.idea

import scala.util.{Success, Try}
import scala.xml.{Elem, MetaData, Node, NodeSeq, Null, UnprefixedAttribute}
import scala.collection.mutable
import java.net.URL

import coursier.core.compatibility.xmlParseDom
import coursier.maven.Pom
import mill.api.{TaskCtx as _, *}
import mill.api.daemon.internal.{
  EvaluatorApi,
  ExecutionResultsApi,
  JavaModuleApi,
  MillBuildRootModuleApi,
  ModuleApi,
  ScalaJSModuleApi,
  ScalaModuleApi,
  ScalaNativeModuleApi,
  TaskApi,
  TestModuleApi
}
import mill.api.daemon.internal.idea.{Element, IdeaConfigFile, JavaFacet, ResolvedModule}
import mill.util.BuildInfo
import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}
import os.SubPath
import scala.jdk.CollectionConverters._

class GenIdeaImpl(
    private val evaluators: Seq[EvaluatorApi]
) {
  def transitiveModules(module: ModuleApi): Seq[ModuleApi] = {
    Seq(module) ++ module.moduleDirectChildren.flatMap(transitiveModules)
  }
  import GenIdeaImpl._

  private val workDir: os.Path = os.Path(evaluators.head.rootModule.moduleDirJava)
  private val ideaDir: os.Path = workDir / ".idea"
  val ideaConfigVersion = 4

  def run(): Unit = {
    val pp = new scala.xml.PrettyPrinter(999, 4)
    val jdkInfo = extractCurrentJdk(ideaDir / "misc.xml")
      .getOrElse(("JDK_1_8", "1.8 (1)"))

    println("Analyzing modules ...")
    val layout: Seq[(subPath: SubPath, xml: Node)] =
      xmlFileLayout(evaluators, jdkInfo)

    println("Cleaning obsolete IDEA project files ...")
    os.remove.all(ideaDir / "libraries")
    os.remove.all(ideaDir / "scala_compiler.xml")
    os.remove.all(ideaDir / "mill_modules")

    println(s"Writing ${layout.size} IDEA project files to ${ideaDir} ...")
    for ((subPath = subPath, xml = xml) <- layout) {
      println(s"Writing ${subPath} ...")
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

  /** Ensure, the additional configs don't collide. */
  private def collisionFreeExtraConfigs(
      confs: Seq[IdeaConfigFile]
  ): Map[os.SubPath, Seq[IdeaConfigFile]] = {

    var seen: Map[(subPath: os.SubPath, component: Option[String]), Seq[Element]] = Map()
    var result: Map[os.SubPath, Seq[IdeaConfigFile]] = Map()
    confs.foreach { conf =>
      val subPath = os.SubPath(conf.subPath)
      val key = (subPath = subPath, component = conf.component)
      seen.get(key) match {
        case None =>
          seen += (key, conf.config)
          result += (subPath, result.getOrElse(subPath, Seq()) ++ Seq(conf))
        case Some(existing) if conf.config == existing =>
        // identical, ignore
        case Some(existing) =>
          def details(elements: Seq[Element]) = {
            elements.map(
              ideaConfigElementTemplate(_).toString().replaceAll("\\n", "")
            )
          }

          val msg =
            s"Config collision in file `${conf.subPath}` and component `${conf.component}`: ${
                details(
                  conf.config
                )
              } vs. ${details(existing)}"
          println(msg)
      }
    }
    result
  }

  def xmlFileLayout(
      evaluators: Seq[EvaluatorApi],
      jdkInfo: (String, String)
  ): Seq[(subPath: os.SubPath, xml: scala.xml.Node)] = {

    val rootModules = evaluators.zipWithIndex.map { case (ev, idx) =>
      (rootModule = ev.rootModule, evaluator = ev, index = idx)
    }
    val transitive = rootModules.map { case (rootModule, ev, idx) =>
      (
        rootModule = rootModule,
        transitive = transitiveModules(rootModule),
        evaluator = ev,
        index = idx
      )
    }

    val foundModules = transitive
      .flatMap { t =>
        t.transitive.collect {
          case m: ModuleApi =>
            val rootSegs = os.Path(t.rootModule.moduleDirJava).relativeTo(workDir).segments
            val modSegs = m.moduleSegments
            val segments = Segments(rootSegs.map(Segment.Label.apply)) ++ modSegs
            (
              segments = segments,
              module = m,
              evaluator = t.evaluator
            )
        }
      }

    val modules: Seq[(segments: Segments, module: JavaModuleApi, evaluator: EvaluatorApi)] =
      foundModules
        .collect {
          case x @ (module = m: JavaModuleApi) if !m.skipIdea =>
            (segments = x.segments, module = m, evaluator = x.evaluator)
        }
        .distinct

    lazy val modulesByEvaluator
        : Map[EvaluatorApi, Seq[(segments: Segments, module: JavaModuleApi)]] =
      modules.groupMap(_.evaluator) { m => (segments = m.segments, module = m.module) }

    // is head the right one?
    val buildDepsPaths = GenIdeaImpl.allJars(evaluators.head.rootModule.getClass.getClassLoader)
      .map(url => os.Path(java.nio.file.Paths.get(url.toURI)))

    def resolveTasks: Map[EvaluatorApi, Seq[TaskApi[ResolvedModule]]] =
      modulesByEvaluator.map { case (ev, ms) =>
        (
          ev,
          ms.map { m =>
            m.module.genIdeaInternal().genIdeaResolvedModule(ideaConfigVersion, m.segments)
          }
        )
      }

    val resolvedModules: Seq[ResolvedModule] = {
      resolveTasks.toSeq.flatMap { case (evaluator, tasks) =>
        evaluator.executeApi(tasks).executionResults match {
          case r if r.transitiveFailingApi.nonEmpty =>
            throw GenIdeaException(
              s"Failure during resolving modules: ${ExecutionResultsApi.formatFailing(r)}"
            )
          case r => r.values.map(_.value).asInstanceOf[Seq[ResolvedModule]]
        }
      }
    }

    val moduleLabels = modules.map { case (module = m, segments = s) => (m, s) }.toMap

    val allResolved: Seq[os.Path] =
      (resolvedModules.flatMap(_.scopedCpEntries).map(s => os.Path(s.path)) ++ buildDepsPaths)
        .distinct
        .sorted

    val (wholeFileConfigs, configFileContributions) =
      resolvedModules
        .flatMap(_.configFileContributions)
        .partition(_.asWholeFile.isDefined)

    // whole file
    val ideaWholeConfigFiles: Seq[(os.SubPath, Elem)] =
      wholeFileConfigs.flatMap(_.asWholeFile).map { wf =>
        os.sub / os.SubPath(wf._1) -> ideaConfigElementTemplate(wf._2)
      }

    val fileComponentContributions: Seq[(os.SubPath, Elem)] =
      collisionFreeExtraConfigs(configFileContributions).toSeq.map {
        case (file, configs) =>
          val map: Map[Option[String], Seq[Element]] =
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
        path: os.Path
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

    // TODO: check if this still holds, since we have replaced import $ivy support.
    /**
     * We need to use a very specific library name format.
     * This is required in order IntelliJ IDEA can recognize `$ivy` imports in `build.mill` files and doesn't show red code.
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
        case CoursierResolved(path = path, pom = pom)
            if buildDepsPaths.contains(path) && pom.toIO.exists() =>
          Seq(sbtLibraryNameFromPom(pom), pathToLibName(path))
        case CoursierResolved(path = path) =>
          Seq(pathToLibName(path))
        case WithSourcesResolved(path = path) =>
          Seq(pathToLibName(path))
        case OtherResolved(path) =>
          Seq(pathToLibName(path))
      }

    def resolvedLibraries(resolved: Seq[os.Path]): Seq[ResolvedLibrary] =
      resolved
        .map(toResolvedJar)
        .collect { case Some(r) => r }

    val compilerSettings = resolvedModules
      .foldLeft(Map[(Seq[os.Path], Seq[String]), Vector[JavaModuleApi]]()) {
        (r, q) =>
          val key = (q.pluginClasspath.map(os.Path(_)), q.scalaOptions)
          r + (key -> (r.getOrElse(key, Vector()) :+ q.module))
      }

    // Get bspScriptIgnore rules (same as BSP integration)
    val bspScriptIgnore: Seq[String] = {
      if (evaluators.length > 1) {
        // look for this in the first meta-build frame, which would be the meta-build configured
        // by a `//|` build header in the main `build.mill` file in the project root folder
        val ev = evaluators(1)
        val bspScriptIgnoreTasks: Seq[TaskApi[Seq[String]]] =
          Seq(ev.rootModule).collect { case m: MillBuildRootModuleApi => m.bspScriptIgnoreAll }

        ev.executeApi(bspScriptIgnoreTasks)
          .values
          .get
          .flatMap { case sources: Seq[String] => sources }
      } else {
        Seq.empty
      }
    }

    // Create IgnoreNode from bspScriptIgnore patterns
    val ignoreRules = bspScriptIgnore
      .filter(l => !l.startsWith("#"))
      .map(pattern => (pattern, new FastIgnoreRule(pattern)))

    val ignoreNode = new IgnoreNode(ignoreRules.map(_._2).asJava)

    // Extract directory prefixes from negation patterns (patterns starting with !)
    // These directories need to be walked even if they're ignored, because they contain
    // negated (un-ignored) files
    val negationPatternDirs: Set[String] = bspScriptIgnore
      .filter(l => !l.startsWith("#") && l.startsWith("!"))
      .flatMap { pattern =>
        val withoutNegation = pattern.drop(1) // Remove the '!' prefix
        // Extract all parent directory paths
        val pathParts = withoutNegation.split('/').dropRight(1) // Remove filename
        if (pathParts.nonEmpty) {
          pathParts.indices.map { i =>
            pathParts.take(i + 1).mkString("/")
          }
        } else Nil
      }
      .toSet

    // Create filter function that checks both files and directories
    val skipPath: (String, Boolean) => Boolean = { (relativePath, isDirectory) =>
      // If this is a directory that contains negation patterns, don't skip it
      if (isDirectory && negationPatternDirs.contains(relativePath)) {
        false
      } else {
        val matchResult = ignoreNode.isIgnored(relativePath, isDirectory)
        matchResult match {
          case IgnoreNode.MatchResult.IGNORED => true
          case _ => false
        }
      }
    }

    // Discover script files
    val outDir = evaluators.headOption.map(e => os.Path(e.outPathJava)).getOrElse(workDir / "out")
    val scriptFiles = new mill.script.ScriptModuleInit().discoverScriptFiles(
      workDir,
      outDir,
      skipPath
    )

    val fixedFiles: Seq[(os.SubPath, Elem)] = Seq(
      Tuple2(os.sub / "misc.xml", miscXmlTemplate(jdkInfo)),
      Tuple2(os.sub / "scala_settings.xml", scalaSettingsTemplate()),
      Tuple2(
        os.sub / "modules.xml",
        allModulesXmlTemplate(
          (modules.map { case (segments = segments) => moduleName(segments) } ++
            scriptFiles.map(scriptModuleName)).sorted
        )
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
    def libraryNameToFileSystemPathPart(name: String, ext: String): os.SubPath = {
      os.sub / s"${name.replaceAll("""[-.:]""", "_")}.${ext}"
    }

    val libraries: Seq[(os.SubPath, Elem)] =
      resolvedLibraries(allResolved).flatMap { resolved =>
        val names = libraryNames(resolved)
        val sources = resolved match {
          case CoursierResolved(sources = s) => s
          case WithSourcesResolved(sources = s) => s
          case OtherResolved(_) => None
        }
        for (name <- names)
          yield {
            Tuple2(
              os.sub / "libraries" / libraryNameToFileSystemPathPart(name, "xml"),
              libraryXmlTemplate(
                name = name,
                path = resolved.path,
                sources = sources
              )
            )
          }
      }

    val moduleFiles: Seq[(os.SubPath, Elem)] = resolvedModules.flatMap { resolvedModule =>
//      case ResolvedModule(
//            path,
//            resolvedDeps,
//            mod,
//            _,
//            _,
//            compilerClasspath,
//            _,
//            facets,
//            _,
//            compilerOutput,
//            scalaVersion,
//            resources,
//            generatedSources,
//            allSources
//          ) =>

      val generatedSourcePaths = resolvedModule.generatedSources.map(os.Path(_))
      val normalSourcePaths = (
        resolvedModule.allSources.map(os.Path(_)).toSet -- generatedSourcePaths.toSet
      ).toSeq

      val sanizedDeps: Seq[ScopedOrd[String]] = {
        resolvedModule.scopedCpEntries
          .map(s => (lib = pathToLibName(os.Path(s.path)), scope = s.scope))
          .groupBy(_.lib)
          .view
          .mapValues(_.map(_.scope))
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

      val libNames = Seq.from(sanizedDeps).iterator.toSeq

      val depNames = {
        val allTransitive = resolvedModule.module.transitiveModuleCompileModuleDeps
        val recursive = resolvedModule.module.recursiveModuleDeps
        val provided = allTransitive.filterNot(recursive.contains)

        Seq
          .from(recursive.map((_, None)) ++
            provided.map((_, Some("PROVIDED"))))
          .filter(!_._1.skipIdea)
          .map { case (v, s) => ScopedOrd(moduleName(moduleLabels(v)), s) }
          .iterator
          .toSeq
          .distinct
      }

      val isTest = resolvedModule.module.isInstanceOf[TestModuleApi]

      val sdkName = (resolvedModule.module match {
        case _: ScalaJSModuleApi => Some("scala-js-SDK")
        case _: ScalaNativeModuleApi => Some("scala-native-SDK")
        case _: ScalaModuleApi => Some("scala-SDK")
        case _ => None
      })
        .map { name => s"${name}-${resolvedModule.scalaVersion.get}" }

      val moduleXml = moduleXmlTemplate(
        basePath = os.Path(resolvedModule.module.intellijModulePathJava),
        sdkOpt = sdkName,
        resourcePaths = Seq.from(resolvedModule.resources.map(os.Path(_))),
        normalSourcePaths = Seq.from(normalSourcePaths),
        generatedSourcePaths = Seq.from(generatedSourcePaths),
        compileOutputPath = os.Path(resolvedModule.compilerOutput),
        libNames = libNames,
        depNames = depNames,
        isTest = isTest,
        facets = resolvedModule.facets
      )

      val moduleFile = Tuple2(
        os.sub / "mill_modules" / s"${moduleName(resolvedModule.segments)}.iml",
        moduleXml
      )

      val scalaSdkFile = {
        sdkName.map { nameAndVersion =>
          val languageLevel =
            resolvedModule.scalaVersion.map(_.split("[.]", 3).take(2).mkString("Scala_", "_", ""))

          val cpFilter: os.Path => Boolean = resolvedModule.module match {
            case _: ScalaJSModuleApi => entry => !entry.last.startsWith("scala3-library_3")
            case _ => _ => true
          }

          Tuple2(
            os.sub / "libraries" / libraryNameToFileSystemPathPart(nameAndVersion, "xml"),
            scalaSdkTemplate(
              name = nameAndVersion,
              languageLevel = languageLevel,
              scalaCompilerClassPath =
                resolvedModule.scalaCompilerClasspath.map(os.Path(_)).filter(cpFilter),
              // FIXME: fill in these fields
              compilerBridgeJar = None,
              scaladocExtraClasspath = Nil
            )
          )
        }
      }

      Seq(moduleFile) ++ scalaSdkFile
    }

    {
      // file collision checks
      val map =
        (fixedFiles ++ ideaWholeConfigFiles ++ fileComponentContributions)
          .groupBy(_._1)
          .filter(_._2.size > 1)
      if (map.nonEmpty) {
        println(
          s"Config file collisions detected. Check you `ideaConfigFiles` tasks. Colliding files: ${map
              .map(_._1)}. All project files: ${map}"
        )
      }
    }

    // Generate module files for script files
    val scriptModuleFiles: Seq[(os.SubPath, Elem)] = scriptFiles.map { scriptPath =>
      val moduleXml = scriptModuleXmlTemplate(scriptPath)
      val moduleFile = Tuple2(
        os.sub / "mill_modules" / s"${scriptModuleName(scriptPath)}.iml",
        moduleXml
      )
      moduleFile
    }

    fixedFiles ++ ideaWholeConfigFiles ++ fileComponentContributions ++ libraries ++ moduleFiles ++ scriptModuleFiles
  }

  def relify(p: os.Path): String = {
    val r = p.relativeTo(ideaDir / "mill_modules")
    (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
  }

  def ideaConfigElementTemplate(element: Element): Elem = {

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
      child = element.childs.map(ideaConfigElementTemplate)*
    )
  }

  def ideaConfigFileTemplate(
      components: Map[Option[String], Seq[Element]]
  ): Elem = {
    <project version={"" + ideaConfigVersion}>
      {
      components.toSeq.map { case (name, config) =>
        <component name={name.getOrElse("")}>{config.map(ideaConfigElementTemplate)}</component>
      }
    }
    </project>
  }

  def scalaSettingsTemplate(): Elem = {
    <project version={"" + ideaConfigVersion}>
      <component name="ScalaProjectSettings">
        <option name="scFileMode" value="Ammonite" />
      </component>
    </project>
  }
  def miscXmlTemplate(jdkInfo: (String, String)): Elem = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectRootManager"
                 version="2"
                 languageLevel={jdkInfo._1}
                 project-jdk-name={jdkInfo._2}
                 project-jdk-type="JavaSDK">
        <output url="file://$PROJECT_DIR$/target/idea_output"/>
      </component>
    </project>
  }

  def allModulesXmlTemplate(selectors: Seq[String]): Elem = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectModuleManager">
        <modules>
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

  /** Try to make the file path a relative JAR URL (to PROJECT_DIR or HOME_DIR). */
  def relativeJarUrl(path: os.Path): String = {
    // When coursier cache dir is on different logical drive than project dir
    // we can not use a relative path. See issue: https://github.com/lihaoyi/mill/issues/905
    val relPath = relForwardPath(path)
    if (path.ext == "jar") "jar://" + relPath + "!/" else "file://" + relPath
  }

  /** Try to make the file path a relative URL (to PROJECT_DIR or HOME_DIR). */
  def relativeFileUrl(path: os.Path): String = {
    // When coursier cache dir is on different logical drive than project dir
    // we can not use a relative path. See issue: https://github.com/lihaoyi/mill/issues/905
    "file://" + relForwardPath(path)
  }

  private val projectDir = (workDir, "$PROJECT_DIR$/")
  private val homeDir = (os.home, "$USER_HOME$/")

  private def relForwardPath(path: os.Path): String = {

    def forward(p: os.FilePath): String = p.toString().replace("""\""", "/")

    val relToProjectDir = Try(projectDir._2 + forward(path.relativeTo(projectDir._1)))
    val relToHomeDir = Try(homeDir._2 + forward(path.relativeTo(homeDir._1)))

    (relToProjectDir, relToHomeDir) match {
      // We seem to be outside of project-dir but inside home dir, so use relative path to home dir
      case (Success(p1), Success(p2)) if p1.contains("..") && !p2.contains("..") => p2
      // default to project-dir-relative
      case (Success(p), _) => p
      // if that fails, use home-dir-relative, which might fix cases on Windows
      // where the home-dir is not on the same drive as the project-dir
      case (_, Success(p)) => p
      // Use the absolute path
      case _ => forward(path)
    }
  }

  def scalaSdkTemplate(
      name: String,
      languageLevel: Option[String],
      scalaCompilerClassPath: Seq[os.Path],
      compilerBridgeJar: Option[os.Path],
      scaladocExtraClasspath: Seq[os.Path]
  ): Elem = {
    val _ = scaladocExtraClasspath // unused for now

    <component name="libraryTable">
      <library name={name} type="Scala">
        <properties>
            {languageLevel.fold(NodeSeq.Empty)(ll => <language-level>{ll}</language-level>)}
            <compiler-classpath>
              {
      scalaCompilerClassPath.sortBy(_.wrapped).map(p => <root url={relativeFileUrl(p)}/>)
    }
            </compiler-classpath>
          {
      compilerBridgeJar.fold(NodeSeq.Empty) { j =>
        <compiler-bridge-binary-jar>{relativeFileUrl(j)}</compiler-bridge-binary-jar>
      }
    }
        </properties>
      </library>
    </component>
  }

  def libraryXmlTemplate(
      name: String,
      path: os.Path,
      sources: Option[os.Path]
  ): Elem = {
    <component name="libraryTable">
      <library name={name}>
        <CLASSES><root url={relativeJarUrl(path)}/></CLASSES>
        {sources.fold(NodeSeq.Empty)(s => <SOURCES><root url={relativeJarUrl(s)}/></SOURCES>)}
      </library>
    </component>
  }

  /**
   * @param libNames The library dependencies (external dependencies)
   * @param depNames The dependency modules (internal dependencies)
   */
  def moduleXmlTemplate(
      basePath: os.Path,
      sdkOpt: Option[String],
      resourcePaths: Seq[os.Path],
      normalSourcePaths: Seq[os.Path],
      generatedSourcePaths: Seq[os.Path],
      compileOutputPath: os.Path,
      libNames: Seq[ScopedOrd[String]],
      depNames: Seq[ScopedOrd[String]],
      isTest: Boolean,
      facets: Seq[JavaFacet]
  ): Elem = {
    val genSources = generatedSourcePaths.toSeq.distinct.sorted.partition(_.startsWith(basePath))
    val normSources = normalSourcePaths.iterator.toSeq.sorted.partition(_.startsWith(basePath))
    val resources = resourcePaths.iterator.toSeq.sorted.partition(_.startsWith(basePath))

    def relUrl(path: os.Path): String = "file://$MODULE_DIR$/" + relify(path)
    def genSourceFolder(path: os.Path): Elem = {
      <sourceFolder url={relUrl(path)} isTestSource={isTest.toString} generated="true"/>
    }
    def sourceFolder(path: os.Path): Elem = {
      <sourceFolder url={relUrl(path)} isTestSource={isTest.toString}/>
    }
    def resourcesFolder(path: os.Path): Elem = {
      val resourceType = if (isTest) "java-test-resource" else "java-resource"
      <sourceFolder url={relUrl(path)} type={resourceType} />
    }

    val outputUrl = relUrl(compileOutputPath)

    <module type="JAVA_MODULE" version={"" + ideaConfigVersion}>
      <component name="NewModuleRootManager">
        {
      if (isTest) <output-test url={outputUrl} />
      else <output url={outputUrl} />
    }
        <exclude-output />
        {
      for (generatedSourcePath <- genSources._2)
        yield <content url={relUrl(generatedSourcePath)}>
          {genSourceFolder(generatedSourcePath)}
        </content>
    }
        {
      // keep the "real" base path as last content, to ensure, Idea picks it up as "main" module dir
      for (normalSourcePath <- normSources._2)
        yield <content url={relUrl(normalSourcePath)}>{sourceFolder(normalSourcePath)}</content>
    }
        {
      for (resourcePath <- resources._2)
        yield <content url={relUrl(resourcePath)}>{resourcesFolder(resourcePath)}</content>
    }

      {
      // the (potentially empty) content root to denote where a module lives
      // this is to avoid some strange layout issues
      // see details at: https://github.com/com-lihaoyi/mill/pull/2638#issuecomment-1685229512
    }

      <content url={relUrl(basePath)}>
        {genSources._1.map(genSourceFolder(_))}
        {normSources._1.map(sourceFolder(_))}
        {resources._1.map(resourcesFolder(_))}
        </content>

        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
        {for (sdk <- sdkOpt.toSeq) yield <orderEntry type="library" name={sdk} level="project" />}

        {
      for (name <- libNames.sorted)
        yield name.scope match {
          case None => <orderEntry type="library" name={name.value} level="project" />
          case Some(scope) =>
            <orderEntry type="library" scope={scope} name={name.value} level="project" />
        }
    }
        {
      // we place the module dependencies after the library dependencies, as IJ is leaking the (transitive)
      // library dependencies of the module dependencies, even if they are not exported.
      // This can result in wrong classpath when lib dependencies are refined.
      for (dep <- depNames.sorted)
        yield dep.scope match {
          case None => <orderEntry type="module" module-name={dep.value} exported="" />
          case Some(scope) =>
            <orderEntry type="module" module-name={dep.value} exported="" scope={scope} />
        }
    }
      </component>
      {
      if (facets.isEmpty) NodeSeq.Empty
      else {
        <component name="FacetManager">
            {
          for (facet <- facets) yield <facet type={facet.`type`} name={facet.name}>
              {ideaConfigElementTemplate(facet.config)}
            </facet>
        }
          </component>
      }
    }
    </module>
  }

  def scalaCompilerTemplate(
      settings: Map[(Seq[os.Path], Seq[String]), Vector[JavaModuleApi]]
  ) = {
    def modulesString(mods: Seq[ModuleApi]) =
      mods.map(m => moduleName(m.moduleSegments)).mkString(",")

    val orderedSettings = settings.toSeq.map {
      case ((plugins, params), mods) => ((plugins, params), modulesString(mods))
    }.sortBy(_._2).zipWithIndex

    <project version={"" + ideaConfigVersion}>
      <component name="ScalaCompilerConfiguration">
        {
      for ((((plugins, params), modsString), i) <- orderedSettings)
        yield <profile name={s"mill ${i + 1}"} modules={modsString}>
          <parameters>{for (param <- params) yield <parameter value={param} />}</parameters>
          <plugins>{for (plugin <- plugins) yield <plugin path={plugin.toString} />}</plugins>
        </profile>
    }
      </component>
    </project>
  }

  /**
   * Generate module name for a script file.
   */
  def scriptModuleName(scriptPath: os.Path): String = {
    s"script-${scriptPath.baseName}".toLowerCase()
  }

  /**
   * Generate IntelliJ module XML for a script file.
   * The source root is the path of the file itself.
   */
  def scriptModuleXmlTemplate(scriptPath: os.Path): Elem = {
    val relUrl = "file://$MODULE_DIR$/" + relify(scriptPath)

    <module type="JAVA_MODULE" version={"" + ideaConfigVersion}>
      <component name="NewModuleRootManager">
        <output url="file://$MODULE_DIR$/../../out/script/{scriptPath.baseName}/compile.dest"/>
        <exclude-output />
        <content url={relUrl}>
          <sourceFolder url={relUrl} isTestSource="false"/>
        </content>
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
      </component>
    </module>
  }
}

object GenIdeaImpl {

  /**
   * Create the module name (to be used by Idea) for the module based on it segments.
   *
   * @see [[Module.moduleSegments]]
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

  def allJars(classloader: ClassLoader): Seq[URL] = {
    allClassloaders(classloader)
      .collect { case t: java.net.URLClassLoader => t.getURLs }
      .flatten
      .toSeq
  }

  def allClassloaders(classloader: ClassLoader): mutable.Buffer[ClassLoader] = {
    val all = mutable.Buffer.empty[ClassLoader]
    var current = classloader
    while (current != null && current != java.lang.ClassLoader.getSystemClassLoader) {
      all.append(current)
      current = current.getParent
    }
    all
  }

  sealed trait ResolvedLibrary { def path: os.Path }
  final case class CoursierResolved(path: os.Path, pom: os.Path, sources: Option[os.Path])
      extends ResolvedLibrary
  final case class OtherResolved(path: os.Path) extends ResolvedLibrary
  final case class WithSourcesResolved(path: os.Path, sources: Option[os.Path])
      extends ResolvedLibrary

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

  case class GenIdeaException(msg: String) extends RuntimeException

}
