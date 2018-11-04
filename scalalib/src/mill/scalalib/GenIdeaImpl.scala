package mill.scalalib

import ammonite.runtime.SpecialClassLoader
import coursier.{Cache, CoursierPaths, Repository}
import mill.define._
import mill.eval.{Evaluator, PathRef, Result}
import mill.util.Ctx.{Home, Log}
import mill.util.Strict.Agg
import mill.util.{Loose, Strict}
import mill.{T, scalalib}

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

    for((relPath, xml) <- xmlFileLayout(evaluator, rootModule, jdkInfo)){
      os.write.over(os.pwd/relPath, pp.format(xml))
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
                    fetchMillModules: Boolean = true): Seq[(os.RelPath, scala.xml.Node)] = {

    val modules = rootModule.millInternal.segmentsToModules.values
      .collect{ case x: scalalib.JavaModule => (x.millModuleSegments, x)}
      .toSeq

    val buildLibraryPaths =
      if (!fetchMillModules) Nil
      else sys.props.get("MILL_BUILD_LIBRARIES") match {
        case Some(found) => found.split(',').map(os.Path(_)).distinct.toList
        case None =>
          val repos = modules.foldLeft(Set.empty[Repository]) { _ ++ _._2.repositories }
          val artifactNames = Seq("main-moduledefs", "main-core", "scalalib", "scalajslib")
          val Result.Success(res) = scalalib.Lib.resolveDependencies(
            repos.toList,
            Lib.depToDependency(_, "2.12.4", ""),
            for(name <- artifactNames)
            yield ivy"com.lihaoyi::mill-$name:${sys.props("MILL_VERSION")}"
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

    val resolved = for((path, mod) <- modules) yield {
      val scalaLibraryIvyDeps = mod match{
        case x: ScalaModule => x.scalaLibraryIvyDeps
        case _ => T.task{Nil}
      }
      val allIvyDeps = T.task{mod.transitiveIvyDeps() ++ scalaLibraryIvyDeps() ++ mod.compileIvyDeps()}
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

      val resolvedCp: Loose.Agg[PathRef] = evalOrElse(evaluator, externalDependencies, Loose.Agg.empty)
      val resolvedSrcs: Loose.Agg[PathRef] = evalOrElse(evaluator, externalSources, Loose.Agg.empty)
      val resolvedSp: Loose.Agg[PathRef] = evalOrElse(evaluator, scalacPluginDependencies, Loose.Agg.empty)
      val scalacOpts: Seq[String] = evalOrElse(evaluator, scalacOptions, Seq())

      (
        path,
        resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path),
        mod,
        resolvedSp.map(_.path).filter(_.ext == "jar"),
        scalacOpts
      )
    }
    val moduleLabels = modules.map(_.swap).toMap

    val allResolved = resolved.flatMap(_._2) ++ buildLibraryPaths ++ buildDepsPaths

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
      val inCoursierCache = path.startsWith(os.Path(CoursierPaths.cacheDirectory()))
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

      val groupId = (xml \ "groupId").text
      val artifactId = (xml \ "artifactId").text
      val version = (xml \ "version").text

      // The scala version here is non incidental
      s"SBT: $groupId:$artifactId:$version:jar"
    }

    def libraryName(resolvedJar: ResolvedLibrary) : String = resolvedJar match {
      case CoursierResolved(path, pom, _) if buildDepsPaths.contains(path) =>
        sbtLibraryNameFromPom(pom)
      case CoursierResolved(path, _, _) =>
        pathToLibName(path)
      case OtherResolved(path) =>
        pathToLibName(path)
    }

    def resolvedLibraries(resolved : Seq[os.Path]) : Seq[ResolvedLibrary] = resolved
      .map(toResolvedJar)
      .collect { case Some(r) => r}

    val compilerSettings = resolved
      .foldLeft(Map[(Loose.Agg[os.Path], Seq[String]), Vector[JavaModule]]()) {
        (r, q) =>
          val key = (q._4, q._5)
          r + (key -> (r.getOrElse(key, Vector()) :+ q._3))
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
        rootXmlTemplate(
          for(lib <- allBuildLibraries)
          yield libraryName(lib)
        )
      ),
      Tuple2(
        os.rel/".idea"/"scala_compiler.xml",
        scalaCompilerTemplate(compilerSettings)
      )
    )

    val libraries = resolvedLibraries(allResolved).map{ resolved =>
      import resolved.path
      val url = "jar://" + path + "!/"
      val name = libraryName(resolved)
      val sources = resolved match {
        case CoursierResolved(_, _, s) => s.map(p => "jar://" + p + "!/")
        case OtherResolved(_) => None
      }
      Tuple2(os.rel/".idea"/'libraries/s"$name.xml", libraryXmlTemplate(name, url, sources))
    }

    val moduleFiles = resolved.map{ case (path, resolvedDeps, mod, _, _) =>
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
  def libraryXmlTemplate(name: String, url: String, sources: Option[String]) = {
    <component name="libraryTable">
      <library name={name} type={if(name.contains("scala-library-")) "Scala" else null}>
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
