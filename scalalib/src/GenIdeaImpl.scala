package mill.scalalib

import scala.collection.immutable
import ammonite.runtime.SpecialClassLoader
import coursier.core.compatibility.xmlParseDom
import coursier.maven.Pom
import coursier.{LocalRepositories, Repositories, Repository}
import mill.api.Ctx.{Home, Log}
import mill.api.Strict.Agg
import mill.api.{Loose, Result, Strict}
import mill.define._
import mill.eval.{Evaluator, PathRef}
import mill.modules.Util
import mill.{T, scalalib}
import os.{Path, RelPath}
import scala.util.Try
import scala.xml.{Elem, MetaData, NodeSeq, Null, UnprefixedAttribute}
import mill.scalalib.GenIdeaModule.{IdeaConfigFile, JavaFacet}

case class GenIdeaImpl(evaluator: Evaluator,
                       ctx: Log with Home,
                       rootModule: BaseModule,
                       discover: Discover[_]) {
  import GenIdeaImpl._

  val cwd: Path = rootModule.millSourcePath

  val ideaConfigVersion = 4

  def run(): Unit = {

    val pp = new scala.xml.PrettyPrinter(999, 4)
    val jdkInfo = extractCurrentJdk(cwd / ".idea" / "misc.xml").getOrElse(("JDK_1_8", "1.8 (1)"))

    ctx.log.info("Analyzing modules ...")
    val layout = xmlFileLayout(evaluator, rootModule, jdkInfo, Some(ctx))

    ctx.log.debug("Cleaning obsolete IDEA project files ...")
    os.remove.all(cwd/".idea"/"libraries")
    os.remove.all(cwd/".idea"/"scala_compiler.xml")
    os.remove.all(cwd/".idea_modules")

    ctx.log.info("Writing IDEA project files ...")
    for((relPath, xml) <- layout) {
      os.write.over(cwd/relPath, pp.format(xml), createFolders = true)
    }
  }

  def extractCurrentJdk(ideaPath: os.Path): Option[(String,String)] = {
    import scala.xml.XML
    Try {
      val xml = XML.loadFile(ideaPath.toString)
      (xml \\ "component")
        .filter(x => x.attribute("project-jdk-type").map(_.text).contains("JavaSDK"))
        .map { n => (n.attribute("languageLevel"), n.attribute("project-jdk-name")) }
        .collectFirst{ case (Some(lang), Some(jdk)) => (lang.text, jdk.text) }
    }.getOrElse(None)
  }

  def xmlFileLayout(evaluator: Evaluator,
                    rootModule: mill.Module,
                    jdkInfo: (String,String),
                    ctx: Option[Log],
                    fetchMillModules: Boolean = true): Seq[(os.RelPath, scala.xml.Node)] = {

    val modules: Seq[(Segments, JavaModule)] = rootModule.millInternal.segmentsToModules.values
      .collect{ case x: scalalib.JavaModule => x }
      .flatMap(_.transitiveModuleDeps)
      .filterNot(_.skipIdea)
      .map(x => (x.millModuleSegments, x))
      .toSeq
      .distinct

    val buildLibraryPaths: immutable.Seq[Path] =
      if (!fetchMillModules) Nil
      else Util.millProperty("MILL_BUILD_LIBRARIES") match {
        case Some(found) => found.split(',').map(os.Path(_)).distinct.toList
        case None =>
          val repos = modules.foldLeft(Set.empty[Repository]) { _ ++ _._2.repositories } ++ Set(LocalRepositories.ivy2Local, Repositories.central)
          val artifactNames = Seq("main-moduledefs", "main-api", "main-core", "scalalib", "scalajslib")
          val Result.Success(res) = scalalib.Lib.resolveDependencies(
            repos.toList,
            Lib.depToDependency(_, "2.13.2", ""),
            for(name <- artifactNames)
            yield ivy"com.lihaoyi::mill-$name:${sys.props("MILL_VERSION")}",
            false,
            None,
            ctx
          )

          // Also trigger resolve sources, but don't use them (will happen implicitly by Idea)
          {
            scalalib.Lib.resolveDependencies(
              repos.toList,
              Lib.depToDependency(_, "2.13.2", ""),
              for(name <- artifactNames)
              yield ivy"com.lihaoyi::mill-$name:${sys.props("MILL_VERSION")}",
              true,
              None,
              ctx
            )
          }

          res.items.toList.map(_.path)
      }

    val buildDepsPaths = Try(evaluator
      .rootModule
      .getClass
      .getClassLoader
      .asInstanceOf[SpecialClassLoader]
    ).map {
       _.allJars
        .map(url => os.Path(url.getFile))
        .filter(_.toIO.exists)
    }.getOrElse(Seq())

    case class ResolvedModule(
                             path: Segments,
                             classpath: Loose.Agg[Path],
                             module: JavaModule,
                             pluginClasspath: Loose.Agg[Path],
                             scalaOptions: Seq[String],
                             compilerClasspath: Loose.Agg[Path],
                             libraryClasspath: Loose.Agg[Path],
                             facets: Seq[JavaFacet],
                             configFileContributions: Seq[IdeaConfigFile],
                             compilerOutput: Path
                             )

    def resolveTasks: Seq[Task[ResolvedModule]] = for((path, mod) <- modules) yield {

      val scalaLibraryIvyDeps = mod match{
        case x: ScalaModule => x.scalaLibraryIvyDeps
        case _ => T.task{Loose.Agg.empty[Dep]}
      }
      val allIvyDeps = T.task{mod.transitiveIvyDeps() ++ scalaLibraryIvyDeps() ++ mod.transitiveCompileIvyDeps()}

      val scalaCompilerClasspath = mod match{
        case x: ScalaModule => x.scalaCompilerClasspath
        case _ => T.task{Loose.Agg.empty[PathRef]}
      }


      val externalLibraryDependencies = T.task{
        mod.resolveDeps(scalaLibraryIvyDeps)()
      }

      val externalDependencies = T.task{
        mod.resolveDeps(allIvyDeps)() ++
          Task.traverse(mod.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
      }

      val externalSources = T.task{
        mod.resolveDeps(allIvyDeps, sources = true)()
      }

      val (scalacPluginsIvyDeps, scalacOptions) = mod match{
        case mod: ScalaModule => T.task{mod.scalacPluginIvyDeps()} -> T.task{mod.scalacOptions()}
        case _ => T.task(Loose.Agg[Dep]()) -> T.task(Seq())
      }
      val scalacPluginDependencies = T.task{
        mod.resolveDeps(scalacPluginsIvyDeps)()
      }

      val facets = T.task{
        mod.ideaJavaModuleFacets(ideaConfigVersion)()
      }

      val configFileContributions = T.task{
        mod.ideaConfigFiles(ideaConfigVersion)()
      }

      val compilerOutput = T.task{
        mod.ideaCompileOutput()
      }

      T.task {
        val resolvedCp: Loose.Agg[PathRef] = externalDependencies()
        // unused, but we want to trigger sources, to have them available (automatically)
        // TODO: make this a separate eval to handle resolve errors
        val resolvedSrcs: Loose.Agg[PathRef] = externalSources()
        val resolvedSp: Loose.Agg[PathRef] = scalacPluginDependencies()
        val resolvedCompilerCp: Loose.Agg[PathRef] = scalaCompilerClasspath()
        val resolvedLibraryCp: Loose.Agg[PathRef] = externalLibraryDependencies()
        val scalacOpts: Seq[String] = scalacOptions()
        val resolvedFacets: Seq[JavaFacet] = facets()
        val resolvedConfigFileContributions: Seq[IdeaConfigFile] = configFileContributions()
        val resolvedCompilerOutput = compilerOutput()

        ResolvedModule(
          path = path,
          // FIXME: why do we need to sources in the classpath?
          // FIXED, was: classpath = resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path),
          classpath = resolvedCp.map(_.path).filter(_.ext == "jar"),
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

    val resolved = evalOrElse(evaluator, T.sequence(resolveTasks), Seq())

    val moduleLabels = modules.map(_.swap).toMap

    val allResolved = resolved.flatMap(_.classpath) ++ buildLibraryPaths ++ buildDepsPaths

    val librariesProperties = resolved.flatMap(x => x.libraryClasspath.map(_ -> x.compilerClasspath)).toMap

    val configFileContributions = resolved.flatMap(_.configFileContributions)

    type FileComponent = (String, String)
    def collisionFree(confs: Seq[IdeaConfigFile]): Map[String, Seq[IdeaConfigFile]] = {
      var seen: Map[FileComponent, Seq[GenIdeaModule.Element]] = Map()
      var result: Map[String, Seq[IdeaConfigFile]] = Map()
      confs.foreach { conf =>
        val key = conf.name -> conf.component
        seen.get(key) match {
          case None =>
            seen += key -> conf.config
            result += conf.name -> (result.get(conf.name).getOrElse(Seq()) ++ Seq(conf))
          case Some(existing) if conf.config == existing =>
          // identical, ignore
          case Some(existing) =>
            def details(elements: Seq[GenIdeaModule.Element]) = {
              elements.map(ideaConfigElementTemplate(_).toString().replaceAll("\\n", ""))
            }
            val msg = s"Config collision in file `${conf.name}` and component `${conf.component}`: ${details(conf.config)} vs. ${details(existing)}"
            ctx.map(_.log.error(msg))
        }
      }
      result
    }

    //TODO: also check against fixed files
    val fileContributions: Seq[(RelPath, Elem)] = collisionFree(configFileContributions).toSeq.map {
      case (file, configs) =>
        val map: Map[String, Seq[GenIdeaModule.Element]] =
          configs
            .groupBy(_.component)
            .mapValues(_.flatMap(_.config))
            .toMap
        (os.rel / ".idea" / file) -> ideaConfigFileTemplate(map)
    }

    val pathShortLibNameDuplicate = allResolved
      .distinct
      .groupBy(_.last)
      .filter(_._2.size > 1)
      .mapValues(_.zipWithIndex)
      .flatMap(y => y._2.map(x => x._1 -> s"${y._1} (${x._2})"))
      .toMap

    val pathToLibName = allResolved
      .map(p => p -> pathShortLibNameDuplicate.getOrElse(p, p.last))
      .toMap

    // Tries to group jars with their poms and sources.
    def toResolvedJar(path : os.Path) : Option[ResolvedLibrary] = {
      val inCoursierCache = path.startsWith(os.Path(coursier.paths.CoursierPaths.cacheDirectory()))
      val inIvyLikeLocal = (path / os.up).last == "jars"
      def inMavenLikeLocal = Try {
        val version = path / os.up
        val artifact = version / os.up
        path.last.startsWith(s"${artifact.last}-${version.last}")
      }.getOrElse(false)
      val isSource = path.last.endsWith("sources.jar")
      val isPom = path.ext == "pom"
      if (inCoursierCache && (isSource || isPom)) {
        // Remove sources and pom as they'll be recovered from the jar path
        None
      } else if (inCoursierCache && path.ext == "jar") {
        val withoutExt = path.last.dropRight(path.ext.length + 1)
        val pom = path / os.up / s"$withoutExt.pom"
        val sources = Some(path / os.up / s"$withoutExt-sources.jar")
          .filter(_.toIO.exists())
        Some(CoursierResolved(path, pom, sources))
      } else if (inIvyLikeLocal && path.ext == "jar") {
        // assume some jvy-like dir structure
        val sources = Some(path / os.up / os.up / "srcs" / s"${path.baseName}-sources.jar")
          .filter(_.toIO.exists())
        Some(WithSourcesResolved(path, sources))
      } else if (inMavenLikeLocal){
        // assume some maven-like dir structure
        val sources = Some(path / os.up / s"${path.baseName}-sources.jar")
          .filter(_.toIO.exists())
        Some(WithSourcesResolved(path, sources))
      } else Some(OtherResolved(path))
    }

    // Hack so that Intellij does not complain about unresolved magic
    // imports in build.sc when in fact they are resolved
    def sbtLibraryNameFromPom(pomPath : os.Path) : String = {
      val pom = xmlParseDom(os.read(pomPath)).flatMap(Pom.project).right.get

      val artifactId = pom.module.name.value
      val scalaArtifactRegex = ".*_[23]\\.[0-9]{1,2}".r
      val artifactWithScalaVersion = artifactId.substring(artifactId.length - math.min(5, artifactId.length)) match {
        case scalaArtifactRegex(_*) => artifactId
        case _ => artifactId + "_2.13"
      }
      s"SBT: ${pom.module.organization.value}:$artifactWithScalaVersion:${pom.version}:jar"
    }

    def libraryNames(resolvedJar: ResolvedLibrary) : Seq[String] = resolvedJar match {
      case CoursierResolved(path, pom, _) if buildDepsPaths.contains(path) =>
        Seq(sbtLibraryNameFromPom(pom), pathToLibName(path))
      case CoursierResolved(path, _, _) =>
        Seq(pathToLibName(path))
      case WithSourcesResolved(path, _) =>
        Seq(pathToLibName(path))
      case OtherResolved(path) =>
        Seq(pathToLibName(path))
    }

    def resolvedLibraries(resolved : Seq[os.Path]) : Seq[ResolvedLibrary] = resolved
      .map(toResolvedJar)
      .collect { case Some(r) => r}

    val compilerSettings = resolved
      .foldLeft(Map[(Loose.Agg[os.Path], Seq[String]), Vector[JavaModule]]()) {
        (r, q) =>
          val key = (q.pluginClasspath, q.scalaOptions)
          r + (key -> (r.getOrElse(key, Vector()) :+ q.module))
      }

    val allBuildLibraries : Set[ResolvedLibrary] =
      resolvedLibraries(buildLibraryPaths ++ buildDepsPaths).toSet

    val fixedFiles: Seq[(RelPath, Elem)] = Seq(
      Tuple2(os.rel/".idea"/"misc.xml", miscXmlTemplate(jdkInfo)),
      Tuple2(os.rel/".idea"/"scala_settings.xml", scalaSettingsTemplate()),
      Tuple2(
        os.rel/".idea"/"modules.xml",
        allModulesXmlTemplate(
          modules.map { case (segments, mod) => moduleName(segments) }.sorted
        )
      ),
      Tuple2(
        os.rel/".idea_modules"/"mill-build.iml",
        rootXmlTemplate(allBuildLibraries.flatMap(lib => libraryNames(lib))
        )
      ),
      Tuple2(
        os.rel/".idea"/"scala_compiler.xml",
        scalaCompilerTemplate(compilerSettings)
      )
    )

    def ideaifyLibraryName(name: String): String = {
      name.replaceAll("""[-.]""", "_")
    }

    val libraries = resolvedLibraries(allResolved).flatMap{ resolved =>
      import resolved.path
      val names = libraryNames(resolved)
      val sources = resolved match {
        case CoursierResolved(_, _, s) => s
        case WithSourcesResolved(_, s) => s
        case OtherResolved(_) => None
      }
      for(name <- names) yield Tuple2(
        os.rel/".idea"/'libraries/s"${ideaifyLibraryName(name)}.xml",
        libraryXmlTemplate(name, path, sources, librariesProperties.getOrElse(path, Loose.Agg.empty)))
    }

    val moduleFiles = resolved.map{ case ResolvedModule(path, resolvedDeps, mod, _, _, _, _, facets, _, compilerOutput) =>
      val Seq(
        resourcesPathRefs: Seq[PathRef],
        sourcesPathRef: Seq[PathRef],
        generatedSourcePathRefs: Seq[PathRef],
        allSourcesPathRefs: Seq[PathRef]
      ) = evaluator.evaluate(Agg(mod.resources, mod.sources, mod.generatedSources, mod.allSources)).values

      val generatedSourcePaths = generatedSourcePathRefs.map(_.path)
      val normalSourcePaths = (allSourcesPathRefs.map(_.path).toSet -- generatedSourcePaths.toSet).toSeq

      val scalaVersionOpt = mod match {
        case x: ScalaModule => Some(evaluator.evaluate(Agg(x.scalaVersion)).values.head.asInstanceOf[String])
        case _ => None
      }

      val isTest = mod.isInstanceOf[TestModule]

      val elem = moduleXmlTemplate(
        mod.intellijModulePath,
        scalaVersionOpt,
        Strict.Agg.from(resourcesPathRefs.map(_.path)),
        Strict.Agg.from(normalSourcePaths),
        Strict.Agg.from(generatedSourcePaths),
        compilerOutput,
        Strict.Agg.from(resolvedDeps.map(pathToLibName)),
        Strict.Agg.from(mod.moduleDeps.filterNot(_.skipIdea).map{ m => moduleName(moduleLabels(m))}.distinct),
        isTest,
        facets
      )
      Tuple2(os.rel/".idea_modules"/s"${moduleName(path)}.iml", elem)
    }

    fixedFiles ++ fileContributions ++ libraries ++ moduleFiles
  }

  def relify(p: os.Path) = {
    val r = p.relativeTo(cwd/".idea_modules")
    (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
  }

  def ideaConfigElementTemplate(element: GenIdeaModule.Element): Elem = {

    val example = <config/>

    val attribute1: MetaData =
      if (element.attributes.isEmpty) Null
      else element.attributes.toSeq.reverse.foldLeft(Null.asInstanceOf[MetaData]) {
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

  def ideaConfigFileTemplate(components: Map[String, Seq[GenIdeaModule.Element]]): Elem = {
    <project version={ "" + ideaConfigVersion }>
      {
        components.toSeq.map { case (name, config) =>
          <component name={ name }>{config.map(ideaConfigElementTemplate)}</component>
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
  def miscXmlTemplate(jdkInfo: (String,String)) = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectRootManager" version="2" languageLevel={jdkInfo._1} project-jdk-name={jdkInfo._2} project-jdk-type="JavaSDK">
        <output url="file://$PROJECT_DIR$/target/idea_output"/>
      </component>
    </project>
  }

  def allModulesXmlTemplate(selectors: Seq[String]) = {
    <project version={"" + ideaConfigVersion}>
      <component name="ProjectModuleManager">
        <modules>
          <module
            fileurl="file://$PROJECT_DIR$/.idea_modules/mill-build.iml"
            filepath="$PROJECT_DIR$/.idea_modules/mill-build.iml"
          />
          {
          for(selector  <- selectors)
          yield {
            val filepath = "$PROJECT_DIR$/.idea_modules/" + selector + ".iml"
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
        <output url="file://$MODULE_DIR$/../out/ideaOutputDir-mill-build"/>
        <content url="file://$MODULE_DIR$/..">
          <excludeFolder url="file://$MODULE_DIR$/../project" />
          <excludeFolder url="file://$MODULE_DIR$/../target" />
          <excludeFolder url="file://$MODULE_DIR$/../out" />
        </content>
        <exclude-output/>
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
        {
          for(name <- libNames.toSeq.sorted)
          yield <orderEntry type="library" name={name} level="project" />
        }
      </component>
    </module>
  }

  /** Try to make the file path a relative JAR URL (to PROJECT_DIR). */
  def relativeJarUrl(path: os.Path) = {
    // When coursier cache dir is on different logical drive than project dir
    // we can not use a relative path. See issue: https://github.com/lihaoyi/mill/issues/905
    val relPath = Try("$PROJECT_DIR$/" + path.relativeTo(cwd)).getOrElse(path)
    if (path.ext == "jar") "jar://" + relPath + "!/" else "file://" + relPath
  }

  /** Try to make the file path a relative URL (to PROJECT_DIR). */
  def relativeFileUrl(path: Path): String = {
    // When coursier cache dir is on different logical drive than project dir
    // we can not use a relative path. See issue: https://github.com/lihaoyi/mill/issues/905
    "file://" + Try("$PROJECT_DIR$/" + path.relativeTo(cwd)).getOrElse(path)
  }

  def libraryXmlTemplate(name: String, path: os.Path, sources: Option[os.Path], scalaCompilerClassPath: Loose.Agg[Path]): Elem = {
    val isScalaLibrary = scalaCompilerClassPath.nonEmpty
    <component name="libraryTable">
      <library name={name} type={if(isScalaLibrary) "Scala" else null}>
        { if(isScalaLibrary) {
        <properties>
          <compiler-classpath>
            {
            scalaCompilerClassPath.toList.sortBy(_.wrapped).map(p => <root url={relativeFileUrl(p)}/>)
            }
          </compiler-classpath>
        </properties>
          }
        }
        <CLASSES>
          <root url={relativeJarUrl(path)}/>
        </CLASSES>
        { if (sources.isDefined) {
          <SOURCES>
            <root url={relativeJarUrl(sources.get)}/>
          </SOURCES>
          }
        }
      </library>
    </component>
  }
  def moduleXmlTemplate(basePath: os.Path,
                        scalaVersionOpt: Option[String],
                        resourcePaths: Strict.Agg[os.Path],
                        normalSourcePaths: Strict.Agg[os.Path],
                        generatedSourcePaths: Strict.Agg[os.Path],
                        compileOutputPath: os.Path,
                        libNames: Strict.Agg[String],
                        depNames: Strict.Agg[String],
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
                <sourceFolder url={"file://$MODULE_DIR$/" + rel} isTestSource={isTest.toString} generated="true" />
              </content>
          }
        }

        {
          // keep the "real" base path as last content, to ensure, Idea picks it up as "main" module dir
          for (normalSourcePath <- normalSourcePaths.toSeq.sorted) yield {
            val rel = relify(normalSourcePath)
            <content url={"file://$MODULE_DIR$/" + rel}>
              <sourceFolder url={"file://$MODULE_DIR$/" + rel} isTestSource={isTest.toString} />
            </content>
          }
        }
        {
          val resourceType = if (isTest) "java-test-resource" else "java-resource"
          for (resourcePath <- resourcePaths.toSeq.sorted) yield {
              val rel = relify(resourcePath)
              <content url={"file://$MODULE_DIR$/" + rel}>
                <sourceFolder url={"file://$MODULE_DIR$/" + rel} type={resourceType} />
              </content>
          }
        }
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
        {
          for(scalaVersion <- scalaVersionOpt.toSeq)
          yield <orderEntry type="library" name={s"scala-sdk-$scalaVersion"} level="application" />
        }

        {
        for(name <- libNames.toSeq.sorted)
        yield <orderEntry type="library" name={name} level="project" />

        }
        {
        for(depName <- depNames.toSeq.sorted)
        yield <orderEntry type="module" module-name={depName} exported="" />
        }
      </component>
      { if (facets.isEmpty) NodeSeq.Empty else { <component name="FacetManager">
            { for (facet <- facets) yield { <facet type={ facet.`type` } name={ facet.name }>
                  { ideaConfigElementTemplate(facet.config) }
                </facet> } }
          </component> } }
    </module>
  }
  def scalaCompilerTemplate(settings: Map[(Loose.Agg[os.Path], Seq[String]), Seq[JavaModule]]) = {

    <project version={"" + ideaConfigVersion}>
      <component name="ScalaCompilerConfiguration">
        {
        for((((plugins, params), mods), i) <- settings.toSeq.zip(1 to settings.size))
        yield
          <profile name={s"mill $i"} modules={mods.map(m => moduleName(m.millModuleSegments)).mkString(",")}>
            <parameters>
              {
              for(param <- params)
              yield <parameter value={param} />
              }
            </parameters>
            <plugins>
              {
              for(plugin <- plugins.toSeq)
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
  def moduleName(p: Segments): String = p.value.foldLeft(new StringBuilder()) {
    case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
    case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
    case (sb, Segment.Label(s)) => sb.append(".").append(s)
    case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
  }.mkString.toLowerCase()

  /**
    * Evaluate the given task `e`. In case, the task has no successful result(s), return the `default` value instead.
    */
  def evalOrElse[T](evaluator: Evaluator, e: Task[T], default: => T): T = {
    evaluator.evaluate(Agg(e)).values match {
      case Seq() => default
      case Seq(e: T) => e
    }
  }

  sealed trait ResolvedLibrary { def path : os.Path }
  case class CoursierResolved(path : os.Path, pom : os.Path, sources : Option[os.Path]) extends ResolvedLibrary
  case class OtherResolved(path : os.Path) extends ResolvedLibrary
  case class WithSourcesResolved(path : os.Path, sources: Option[os.Path]) extends ResolvedLibrary


}
