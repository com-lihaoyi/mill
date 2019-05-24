package mill.scalalib

import ammonite.runtime.SpecialClassLoader
import coursier.{LocalRepositories, Repositories}
import mill.api.Ctx.{Home, Log}
import mill.api.Strict.Agg
import mill.api.{Loose, Result, Strict}
import mill.define._
import mill.eval.{Evaluator, PathRef}
import mill.{T, scalalib}
import os.Path

import scala.util.Try


object GenIdea extends ExternalModule {

  def idea(ev: Evaluator) = T.command{
    mill.scalalib.GenIdeaImpl(
      implicitly,
      ev.rootModule,
      ev.rootModule.millDiscover
    )
  }

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover = Discover[this.type]
}

object GenIdeaImpl {

  def apply(ctx: Log with Home,
            rootModule: BaseModule,
            discover: Discover[_]): Unit = {
    val pp = new scala.xml.PrettyPrinter(999, 4)

    val jdkInfo = extractCurrentJdk(os.pwd / ".idea" / "misc.xml").getOrElse(("JDK_1_8", "1.8 (1)"))

    os.remove.all(os.pwd/".idea"/"libraries")
    os.remove.all(os.pwd/".idea"/"scala_compiler.xml")
    os.remove.all(os.pwd/".idea_modules")


    val evaluator = new Evaluator(ctx.home, os.pwd / 'out, os.pwd / 'out, rootModule, ctx.log)

    for((relPath, xml) <- xmlFileLayout(evaluator, rootModule, jdkInfo, Some(ctx))){
      os.write.over(os.pwd/relPath, pp.format(xml), createFolders = true)
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

    val modules = rootModule.millInternal.segmentsToModules.values
      .collect{ case x: scalalib.JavaModule => x }
      .flatMap(_.transitiveModuleDeps)
      .map(x => (x.millModuleSegments, x))
      .toSeq
      .distinct
    
    val buildLibraryPaths =
      if (!fetchMillModules) Nil
      else sys.props.get("MILL_BUILD_LIBRARIES") match {
        case Some(found) => found.split(',').map(os.Path(_)).distinct.toList
        case None =>
          val repos = Seq(LocalRepositories.ivy2Local, Repositories.central)
          val artifactNames = Seq("main-moduledefs", "main-api", "main-core", "scalalib", "scalajslib")
          val Result.Success(res) = scalalib.Lib.resolveDependencies(
            repos.toList,
            Lib.depToDependency(_, "2.12.4", ""),
            for(name <- artifactNames)
            yield ivy"com.lihaoyi::mill-$name:${sys.props("MILL_VERSION")}",
            false,
            None,
            ctx
          )
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
                             libraryClasspath: Loose.Agg[Path]
                             )

    val resolved = evalOrElse(evaluator, Task.sequence(for((path, mod) <- modules) yield {
      val scalaLibraryIvyDeps = mod match{
        case x: ScalaModule => x.scalaLibraryIvyDeps
        case _ => T.task{Loose.Agg.empty[Dep]}
      }
      val allIvyDeps = T.task{mod.transitiveIvyDeps() ++ scalaLibraryIvyDeps() ++ mod.compileIvyDeps()}

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

      T.task {
        val resolvedCp: Loose.Agg[PathRef] = externalDependencies()
        val resolvedSrcs: Loose.Agg[PathRef] = externalSources()
        val resolvedSp: Loose.Agg[PathRef] = scalacPluginDependencies()
        val resolvedCompilerCp: Loose.Agg[PathRef] = scalaCompilerClasspath()
        val resolvedLibraryCp: Loose.Agg[PathRef] = externalLibraryDependencies()
        val scalacOpts: Seq[String] = scalacOptions()

        ResolvedModule(
          path,
          resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path),
          mod,
          resolvedSp.map(_.path).filter(_.ext == "jar"),
          scalacOpts,
          resolvedCompilerCp.map(_.path),
          resolvedLibraryCp.map(_.path)
        )
      }
    }), Seq())

    val moduleLabels = modules.map(_.swap).toMap

    val allResolved = resolved.flatMap(_.classpath) ++ buildLibraryPaths ++ buildDepsPaths

    val librariesProperties = resolved.flatMap(x => x.libraryClasspath.map(_ -> x.compilerClasspath)).toMap

    val commonPrefix =
      if (allResolved.isEmpty) 0
      else {
        val minResolvedLength = allResolved.map(_.segmentCount).min
        allResolved.map(_.segments.take(minResolvedLength).toList)
          .transpose
          .takeWhile(_.distinct.length == 1)
          .length
      }

    // only resort to full long path names if the jar name is a duplicate
    val pathShortLibNameDuplicate = allResolved
      .distinct
      .map{p => p.last -> p}
      .groupBy(_._1)
      .filter(_._2.size > 1)
      .keySet

    val pathToLibName = allResolved
      .map{p =>
        if (pathShortLibNameDuplicate(p.last))
          (p, p.segments.drop(commonPrefix).mkString("_"))
        else
          (p, p.last)
      }
      .toMap

    sealed trait ResolvedLibrary { def path : os.Path }
    case class CoursierResolved(path : os.Path, pom : os.Path, sources : Option[os.Path])
      extends ResolvedLibrary
    case class OtherResolved(path : os.Path) extends ResolvedLibrary

    // Tries to group jars with their poms and sources.
    def toResolvedJar(path : os.Path) : Option[ResolvedLibrary] = {
      val inCoursierCache = path.startsWith(os.Path(coursier.paths.CoursierPaths.cacheDirectory()))
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
      } else Some(OtherResolved(path))
    }

    // Hack so that Intellij does not complain about unresolved magic
    // imports in build.sc when in fact they are resolved
    def sbtLibraryNameFromPom(pom : os.Path) : String = {
      val xml = scala.xml.XML.loadFile(pom.toIO)

      val parent = xml \ "parent"
      val artifactId = (xml \ "artifactId").text
      val groupId = Some(xml \ "groupId").filter(_.nonEmpty).getOrElse(parent \ "groupId").text
      val version = Some(xml \ "version").filter(_.nonEmpty).getOrElse(parent \ "version").text

      val artifactWithScalaVersion = artifactId.substring(artifactId.length - 5) match {
        case "_2.10" | "_2.11" | "_2.12" => artifactId
        case _ => artifactId + "_2.12"
      }
      s"SBT: $groupId:$artifactWithScalaVersion:$version:jar"
    }

    def libraryNames(resolvedJar: ResolvedLibrary) : Seq[String] = resolvedJar match {
      case CoursierResolved(path, pom, _) if buildDepsPaths.contains(path) =>
        Seq(sbtLibraryNameFromPom(pom), pathToLibName(path))
      case CoursierResolved(path, _, _) =>
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

    val fixedFiles = Seq(
      Tuple2(os.rel/".idea"/"misc.xml", miscXmlTemplate(jdkInfo)),
      Tuple2(os.rel/".idea"/"scala_settings.xml", scalaSettingsTemplate()),
      Tuple2(
        os.rel/".idea"/"modules.xml",
        allModulesXmlTemplate(
          modules
            .filter(!_._2.skipIdea)
            .map { case (path, mod) => moduleName(path) }
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

    val libraries = resolvedLibraries(allResolved).flatMap{ resolved =>
      import resolved.path
      val names = libraryNames(resolved)
      val sources = resolved match {
        case CoursierResolved(_, _, s) => s.map(p => "jar://" + p + "!/")
        case OtherResolved(_) => None
      }
      for(name <- names) yield Tuple2(os.rel/".idea"/'libraries/s"$name.xml", libraryXmlTemplate(name, path, sources, librariesProperties.getOrElse(path, Loose.Agg.empty)))
    }

    val moduleFiles = resolved.map{ case ResolvedModule(path, resolvedDeps, mod, _, _, _, _) =>
      val Seq(
        resourcesPathRefs: Seq[PathRef],
        sourcesPathRef: Seq[PathRef],
        generatedSourcePathRefs: Seq[PathRef],
        allSourcesPathRefs: Seq[PathRef]
      ) = evaluator.evaluate(Agg(mod.resources, mod.sources, mod.generatedSources, mod.allSources)).values

      val generatedSourcePaths = generatedSourcePathRefs.map(_.path)
      val normalSourcePaths = (allSourcesPathRefs.map(_.path).toSet -- generatedSourcePaths.toSet).toSeq

      val paths = Evaluator.resolveDestPaths(
        evaluator.outPath,
        mod.compile.ctx.segments
      )
      val scalaVersionOpt = mod match {
        case x: ScalaModule => Some(evaluator.evaluate(Agg(x.scalaVersion)).values.head.asInstanceOf[String])
        case _ => None
      }
      val generatedSourceOutPath = Evaluator.resolveDestPaths(
        evaluator.outPath,
        mod.generatedSources.ctx.segments
      )

      val isTest = mod.isInstanceOf[TestModule]

      val elem = moduleXmlTemplate(
        mod.intellijModulePath,
        scalaVersionOpt,
        Strict.Agg.from(resourcesPathRefs.map(_.path)),
        Strict.Agg.from(normalSourcePaths),
        Strict.Agg.from(generatedSourcePaths),
        paths.out,
        generatedSourceOutPath.dest,
        Strict.Agg.from(resolvedDeps.map(pathToLibName)),
        Strict.Agg.from(mod.moduleDeps.map{ m => moduleName(moduleLabels(m))}.distinct),
        isTest
      )
      Tuple2(os.rel/".idea_modules"/s"${moduleName(path)}.iml", elem)
    }

    fixedFiles ++ libraries ++ moduleFiles
  }

  def evalOrElse[T](evaluator: Evaluator, e: Task[T], default: => T): T = {
    evaluator.evaluate(Agg(e)).values match {
      case Seq() => default
      case Seq(e: T) => e
    }
  }

  def relify(p: os.Path) = {
    val r = p.relativeTo(os.pwd/".idea_modules")
    (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
  }

  def moduleName(p: Segments) = p.value.foldLeft(StringBuilder.newBuilder) {
    case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
    case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
    case (sb, Segment.Label(s)) => sb.append(".").append(s)
    case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
  }.mkString.toLowerCase()

  def scalaSettingsTemplate() = {

    <project version="4">
      <component name="ScalaProjectSettings">
        <option name="scFileMode" value="Ammonite" />
      </component>
    </project>
  }
  def miscXmlTemplate(jdkInfo: (String,String)) = {
    <project version="4">
      <component name="ProjectRootManager" version="2" languageLevel={jdkInfo._1} project-jdk-name={jdkInfo._2} project-jdk-type="JavaSDK">
        <output url="file://$PROJECT_DIR$/target/idea_output"/>
      </component>
    </project>
  }

  def allModulesXmlTemplate(selectors: Seq[String]) = {
    <project version="4">
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
  def rootXmlTemplate(libNames: Strict.Agg[String]) = {
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager">
        <output url="file://$MODULE_DIR$/../out"/>
        <content url="file://$MODULE_DIR$/..">
          <excludeFolder url="file://$MODULE_DIR$/../project" />
          <excludeFolder url="file://$MODULE_DIR$/../target" />
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
  def libraryXmlTemplate(name: String, path: os.Path, sources: Option[String], compilerClassPath: Loose.Agg[Path]) = {
    val url = if (path.ext == "jar") "jar://" + path + "!/" else "file://" + path
    val isScalaLibrary = compilerClassPath.nonEmpty
    <component name="libraryTable">
      <library name={name} type={if(isScalaLibrary) "Scala" else null}>
        { if(isScalaLibrary) {
        <properties>
          <compiler-classpath>
            {
            compilerClassPath.toList.sortBy(_.wrapped).map(p => <root url={"file://" + p}/>)
            }
          </compiler-classpath>
        </properties>
          }
        }
        <CLASSES>
          <root url={url}/>
        </CLASSES>
        { if (sources.isDefined) {
          <SOURCES>
            <root url={sources.get}/>
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
                        generatedSourceOutputPath: os.Path,
                        libNames: Strict.Agg[String],
                        depNames: Strict.Agg[String],
                        isTest: Boolean
                       ) = {
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager">
        {
          val outputUrl = "file://$MODULE_DIR$/" + relify(compileOutputPath) + "/dest/classes"
          if (isTest)
            <output-test url={outputUrl} />
          else
            <output url={outputUrl} />
        }
        <exclude-output />
        <content url={"file://$MODULE_DIR$/" + relify(generatedSourceOutputPath)} />
        <content url={"file://$MODULE_DIR$/" + relify(basePath)}>
          {
          for (normalSourcePath <- normalSourcePaths.toSeq.sorted)
            yield
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(normalSourcePath)} isTestSource={isTest.toString} />
          }
          {
          for (generatedSourcePath <- generatedSourcePaths.toSeq.sorted)
            yield
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(generatedSourcePath)} isTestSource={isTest.toString} generated="true" />
          }
          {
          val resourceType = if (isTest) "java-test-resource" else "java-resource"
          for (resourcePath <- resourcePaths.toSeq.sorted)
            yield
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(resourcePath)} type={resourceType} />
          }
          <excludeFolder url={"file://$MODULE_DIR$/" +  relify(basePath) + "/target"} />
        </content>
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
    </module>
  }
  def scalaCompilerTemplate(settings: Map[(Loose.Agg[os.Path], Seq[String]), Seq[JavaModule]]) = {

    <project version="4">
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
