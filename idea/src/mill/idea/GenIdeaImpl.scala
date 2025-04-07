package mill.idea

import scala.collection.immutable
import scala.util.{Success, Try}
import scala.xml.{Elem, MetaData, Node, NodeSeq, Null, UnprefixedAttribute}
import coursier.core.compatibility.xmlParseDom
import coursier.maven.Pom
import mill.api.Ctx
import mill.api.PathRef
import mill.define.{Evaluator, Ctx as _, *}
import mill.runner.api.{
  BaseModuleApi,
  EvaluatorApi,
  ExecutionResultsApi,
  IdeaConfigFile,
  JavaFacet,
  JavaModuleApi,
  ModuleApi,
  ScalaJSModuleApi,
  ScalaModuleApi,
  ScalaNativeModuleApi,
  TestModuleApi
}
import mill.util.BuildInfo

import collection.mutable
import java.net.URL

class GenIdeaImpl(
    private val evaluators: Seq[EvaluatorApi]
)(implicit ctx: Ctx) {
  def transitiveModules(module: ModuleApi): Seq[ModuleApi] = {
    Seq(module) ++ module.moduleDirectChildren.flatMap(transitiveModules)
  }
  import GenIdeaImpl._

  val workDir: os.Path = os.Path(evaluators.head.rootModule.moduleDirJava)
  val ideaDir: os.Path = workDir / ".idea"
  val ideaConfigVersion = 4

  def run(): Unit = {
    val pp = new scala.xml.PrettyPrinter(999, 4)
    val jdkInfo = extractCurrentJdk(ideaDir / "misc.xml")
      .getOrElse(("JDK_1_8", "1.8 (1)"))

    ctx.log.info("Analyzing modules ...")
    val layout: Seq[(os.SubPath, Node)] =
      xmlFileLayout(evaluators, jdkInfo)

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
      evaluators: Seq[EvaluatorApi],
      jdkInfo: (String, String),
      fetchMillModules: Boolean = true
  ): Seq[(os.SubPath, scala.xml.Node)] = {

    val rootModules = evaluators.zipWithIndex.map { case (ev, idx) =>
      (ev.rootModule, ev, idx)
    }
    val transitive: Seq[(BaseModuleApi, Seq[ModuleApi], EvaluatorApi, Int)] = rootModules
      .map { case (rootModule, ev, idx) =>
        (rootModule, transitiveModules(rootModule), ev, idx)
      }

    val foundModules: Seq[(Segments, ModuleApi, EvaluatorApi)] = transitive
      .flatMap { case (rootMod, transModules, ev, idx) =>
        transModules.collect {
          case m: ModuleApi =>
            val rootSegs = os.Path(rootMod.moduleDirJava).relativeTo(workDir).segments
            val modSegs = m.moduleSegments.parts
            val segments: Seq[String] = rootSegs ++ modSegs
            (Segments(segments.map(Segment.Label(_))), m, ev)
        }
      }

    val modules: Seq[(Segments, JavaModuleApi, EvaluatorApi)] = foundModules
      .collect { case (s, x: JavaModuleApi, ev) => (s, x, ev) }
      .filterNot(_._2.skipIdea)
      .distinct

    lazy val modulesByEvaluator: Map[EvaluatorApi, Seq[(Segments, JavaModuleApi)]] = modules
      .groupMap { case (_, _, ev) => ev } { case (s, m, _) => (s, m) }

    val buildLibraryPaths: immutable.Seq[os.Path] = {
      if (!fetchMillModules) Nil
      else {
        modulesByEvaluator.toSeq
          .flatMap { case (ev, modules) =>
            ev.executeApi(modules.map(_._2.buildLibraryPaths))
              .values.get
          }
          .flatten
          .map(os.Path(_))
      }
    }

    // is head the right one?
    val buildDepsPaths = GenIdeaImpl.allJars(evaluators.head.rootModule.getClass.getClassLoader)
      .map(url => os.Path(java.nio.file.Paths.get(url.toURI)))

    def resolveTasks
        : Map[EvaluatorApi, Seq[mill.runner.api.TaskApi[mill.runner.api.ResolvedModule]]] =
      modulesByEvaluator.map { case (evaluator, m) =>
        evaluator -> m.map {
          case (path, mod) => mod.genIdeaMetadata(ideaConfigVersion, evaluator, path)

        }
      }

    val resolvedModules: Seq[mill.runner.api.ResolvedModule] = {
      resolveTasks.toSeq.flatMap { case (evaluator, tasks) =>
        evaluator.executeApi(tasks).executionResults match {
          case r if r.transitiveFailingApi.nonEmpty =>
            throw GenIdeaException(
              s"Failure during resolving modules: ${ExecutionResultsApi.formatFailing(r)}"
            )
          case r => r.values.map(_.value).asInstanceOf[Seq[mill.runner.api.ResolvedModule]]
        }
      }
    }

    val moduleLabels = modules.map { case (s, m, e) => (m, s) }.toMap

    val allResolved: Seq[os.Path] =
      (resolvedModules.flatMap(_.classpath).map(s =>
        os.Path(s.value)
      ) ++ buildLibraryPaths ++ buildDepsPaths)
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

    type FileComponent = (os.SubPath, Option[String])

    /** Ensure, the additional configs don't collide. */
    def collisionFreeExtraConfigs(
        confs: Seq[IdeaConfigFile]
    ): Map[os.SubPath, Seq[IdeaConfigFile]] = {

      var seen: Map[FileComponent, Seq[mill.runner.api.Element]] = Map()
      var result: Map[os.SubPath, Seq[IdeaConfigFile]] = Map()
      confs.foreach { conf =>
        val subPath = os.SubPath(conf.subPath)
        val key = subPath -> conf.component
        seen.get(key) match {
          case None =>
            seen += key -> conf.config
            result += subPath -> (result
              .get(subPath)
              .getOrElse(Seq()) ++ Seq(conf))
          case Some(existing) if conf.config == existing =>
          // identical, ignore
          case Some(existing) =>
            def details(elements: Seq[mill.runner.api.Element]) = {
              elements.map(
                ideaConfigElementTemplate(_).toString().replaceAll("\\n", "")
              )
            }
            val msg =
              s"Config collision in file `${conf.subPath}` and component `${conf.component}`: ${details(
                  conf.config
                )} vs. ${details(existing)}"
            ctx.log.error(msg)
        }
      }
      result
    }

    val fileComponentContributions: Seq[(os.SubPath, Elem)] =
      collisionFreeExtraConfigs(configFileContributions).toSeq.map {
        case (file, configs) =>
          val map: Map[Option[String], Seq[mill.runner.api.Element]] =
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
      .foldLeft(Map[(Seq[os.Path], Seq[String]), Vector[JavaModuleApi]]()) {
        (r, q) =>
          val key = (q.pluginClasspath.map(os.Path(_)), q.scalaOptions)
          r + (key -> (r.getOrElse(key, Vector()) :+ q.module))
      }

    val fixedFiles: Seq[(os.SubPath, Elem)] = Seq(
      Tuple2(os.sub / "misc.xml", miscXmlTemplate(jdkInfo)),
      Tuple2(os.sub / "scala_settings.xml", scalaSettingsTemplate()),
      Tuple2(
        os.sub / "modules.xml",
        allModulesXmlTemplate(
          modules.map { case (segments, mod, _) => moduleName(segments) }.sorted
        )
      ),
//      Tuple2(
//        os.sub / "mill_modules/mill-build.iml",
//        rootXmlTemplate(allBuildLibraries.flatMap(lib => libraryNames(lib)))
//      ),
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
          case CoursierResolved(_, _, s) => s
          case WithSourcesResolved(_, s) => s
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

    val moduleFiles: Seq[(os.SubPath, Elem)] = resolvedModules.flatMap {
      case mill.runner.api.ResolvedModule(
            path,
            resolvedDeps,
            mod,
            _,
            _,
            compilerClasspath,
            _,
            facets,
            _,
            compilerOutput,
            evaluator,
            scalaVersion,
            resources,
            generatedSources,
            allSources
          ) =>

        val generatedSourcePaths = generatedSources.map(os.Path(_))
        val normalSourcePaths = (allSources
          .map(os.Path(_))
          .toSet -- generatedSourcePaths.toSet).toSeq

        val sanizedDeps: Seq[ScopedOrd[String]] = {
          resolvedDeps
            .map((s: mill.runner.api.Scoped[java.nio.file.Path]) =>
              pathToLibName(os.Path(s.value)) -> s.scope
            )
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

        val libNames = Seq.from(sanizedDeps).iterator.toSeq

        val depNames = {
          val allTransitive = mod.transitiveModuleCompileModuleDeps
          val recursive = mod.recursiveModuleDeps
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

        val isTest = mod.isInstanceOf[TestModuleApi]

        val sdkName = (mod match {
          case _: ScalaJSModuleApi => Some("scala-js-SDK")
          case _: ScalaNativeModuleApi => Some("scala-native-SDK")
          case _: ScalaModuleApi => Some("scala-SDK")
          case _ => None
        })
          .map { name => s"${name}-${scalaVersion.get}" }

        val moduleXml = moduleXmlTemplate(
          basePath = os.Path(mod.intellijModulePathJava),
          sdkOpt = sdkName,
          resourcePaths = Seq.from(resources.map(os.Path(_))),
          normalSourcePaths = Seq.from(normalSourcePaths),
          generatedSourcePaths = Seq.from(generatedSourcePaths),
          compileOutputPath = os.Path(compilerOutput),
          libNames = libNames,
          depNames = depNames,
          isTest = isTest,
          facets = facets
        )

        val moduleFile = Tuple2(
          os.sub / "mill_modules" / s"${moduleName(path)}.iml",
          moduleXml
        )

        val scalaSdkFile = {
          sdkName.map { nameAndVersion =>
            val languageLevel =
              scalaVersion.map(_.split("[.]", 3).take(2).mkString("Scala_", "_", ""))

            val cpFilter: os.Path => Boolean = mod match {
              case _: ScalaJSModuleApi => entry => !entry.last.startsWith("scala3-library_3")
              case _ => _ => true
            }

            Tuple2(
              os.sub / "libraries" / libraryNameToFileSystemPathPart(nameAndVersion, "xml"),
              scalaSdkTemplate(
                name = nameAndVersion,
                languageLevel = languageLevel,
                scalaCompilerClassPath = compilerClasspath.map(os.Path(_)).filter(cpFilter),
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
        ctx.log.error(
          s"Config file collisions detected. Check you `ideaConfigFiles` targets. Colliding files: ${map
              .map(_._1)}. All project files: ${map}"
        )
      }
    }

    fixedFiles ++ ideaWholeConfigFiles ++ fileComponentContributions ++ libraries ++ moduleFiles
  }

  def relify(p: os.Path): String = {
    val r = p.relativeTo(ideaDir / "mill_modules")
    (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
  }

  def ideaConfigElementTemplate(element: mill.runner.api.Element): Elem = {

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
      components: Map[Option[String], Seq[mill.runner.api.Element]]
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
//    simpleIdeaConfigFileTemplate(Map("ScalaProjectSettings" -> Map("scFileMode" -> "Ammonite")))
    <project version={"" + ideaConfigVersion}>
      <component name="ScalaProjectSettings">
        <option name="scFileMode" value="Ammonite" />
      </component>
    </project>
  }
  def miscXmlTemplate(jdkInfo: (String, String)): Elem = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectRootManager" version="2" languageLevel={jdkInfo._1} project-jdk-name={
      jdkInfo._2
    } project-jdk-type="JavaSDK">
        <output url="file://$PROJECT_DIR$/target/idea_output"/>
      </component>
    </project>
  }

  def allModulesXmlTemplate(selectors: Seq[String]): Elem = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectModuleManager">
        <modules>
          {
//            <module
//          fileurl="file://$PROJECT_DIR$/.idea/mill_modules/mill-build.iml"
//          filepath="$PROJECT_DIR$/.idea/mill_modules/mill-build.iml"/>
    }
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
  def rootXmlTemplate(libNames: Seq[String]): scala.xml.Elem = {
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
    <component name="libraryTable">
      <library name={name} type="Scala">
        <properties>
            {
      if (languageLevel.isDefined)
        <language-level>{languageLevel.get}</language-level>
      else {
        // Scala 3: I assume there is some missing implicit conversion from `()` to NodeSeq,
        // so use an explicit seq.
        NodeSeq.Empty
      }
    }
            <compiler-classpath>
              {
      scalaCompilerClassPath.iterator.toSeq.sortBy(_.wrapped).map(p =>
        <root url={relativeFileUrl(p)}/>
      )
    }
            </compiler-classpath>
          {
      if (compilerBridgeJar.isDefined)
        <compiler-bridge-binary-jar>{
          relativeFileUrl(compilerBridgeJar.get)
        }</compiler-bridge-binary-jar>
      else {
        // Scala 3: I assume there is some missing implicit conversion from `()` to NodeSeq,
        // so use an explicit seq.
        NodeSeq.Empty
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
        <CLASSES>
          <root url={relativeJarUrl(path)}/>
        </CLASSES>
        {
      if (sources.isDefined) {
        <SOURCES>
              <root url={relativeJarUrl(sources.get)}/>
            </SOURCES>
      } else {
        // Scala 3: I assume there is some missing implicit conversion from `()` to NodeSeq,
        // so use an explicit seq.
        NodeSeq.Empty
      }
    }
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
      facets: Seq[mill.runner.api.JavaFacet]
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

    <module type="JAVA_MODULE" version={"" + ideaConfigVersion}>
      <component name="NewModuleRootManager">
        {
      val outputUrl = relUrl(compileOutputPath)
      if (isTest)
        <output-test url={outputUrl} />
      else
        <output url={outputUrl} />
    }
        <exclude-output />
        {
      for (generatedSourcePath <- genSources._2) yield {
        <content url={relUrl(generatedSourcePath)}>
          {genSourceFolder(generatedSourcePath)}
        </content>
      }
    }
        {
      // keep the "real" base path as last content, to ensure, Idea picks it up as "main" module dir
      for (normalSourcePath <- normSources._2) yield {
        <content url={relUrl(normalSourcePath)}>
            {sourceFolder(normalSourcePath)}
          </content>
      }
    }
        {
      for (resourcePath <- resources._2) yield {
        <content url={relUrl(resourcePath)}>
          {resourcesFolder(resourcePath)}
        </content>
      }
    }
        {
      // the (potentially empty) content root to denote where a module lives
      // this is to avoid some strange layout issues
      // see details at: https://github.com/com-lihaoyi/mill/pull/2638#issuecomment-1685229512
      <content url={relUrl(basePath)}>
        {
        for (generatedSourcePath <- genSources._1) yield {
          genSourceFolder(generatedSourcePath)
        }
      }
        {
        for (normalSourcePath <- normSources._1) yield {
          sourceFolder(normalSourcePath)
        }
      }
        {
        for (resourcePath <- resources._1) yield {
          resourcesFolder(resourcePath)
        }
      }
        </content>
    }
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
        {
      for {
        sdk <- sdkOpt.toSeq
      } yield <orderEntry type="library" name={sdk} level="project" />
    }

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
      settings: Map[(Seq[os.Path], Seq[String]), Seq[JavaModuleApi]]
  ) = {

    <project version={"" + ideaConfigVersion}>
      <component name="ScalaCompilerConfiguration">
        {
      for ((((plugins, params), mods), i) <- settings.toSeq.zip(1 to settings.size))
        yield <profile name={s"mill $i"} modules={
          mods.map(m => moduleName(m.moduleSegments)).mkString(",")
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
    while (current != null && current != ClassLoader.getSystemClassLoader) {
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
  object ScopedOrd {
    def apply[T <: Comparable[T]](scoped: mill.runner.api.Scoped[T]): ScopedOrd[T] =
      ScopedOrd(scoped.value, scoped.scope)
  }

  case class GenIdeaException(msg: String) extends RuntimeException

}
