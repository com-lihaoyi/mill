package mill.scalalib

import ammonite.ops._
import coursier.Cache
import coursier.maven.MavenRepository
import mill.define._
import mill.eval.{Evaluator, PathRef, Result}
import mill.{T, scalalib}
import mill.util.Ctx.Log
import mill.util.{Loose, PrintLogger, Strict}
import mill.util.Strict.Agg
import scala.util.Try


object GenIdeaModule extends ExternalModule {

  def idea(ev: Evaluator[Any]) = T.command{
    mill.scalalib.GenIdea(
      implicitly,
      ev.rootModule,
      ev.rootModule.millDiscover
    )
  }

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover = Discover[this.type]
}
object GenIdea {

  def apply(ctx: Log,
            rootModule: BaseModule,
            discover: Discover[_]): Unit = {
    val pp = new scala.xml.PrettyPrinter(999, 4)

    val jdkInfo = extractCurrentJdk(pwd / ".idea" / "misc.xml").getOrElse(("JDK_1_8", "1.8 (1)"))

    rm! pwd/".idea"
    rm! pwd/".idea_modules"


    val evaluator = new Evaluator(pwd / 'out, pwd / 'out, rootModule, ctx.log)

    for((relPath, xml) <- xmlFileLayout(evaluator, rootModule, jdkInfo)){
      write.over(pwd/relPath, pp.format(xml))
    }
  }

  def extractCurrentJdk(ideaPath: Path): Option[(String,String)] = {
    import scala.xml.XML
    Try {
      val xml = XML.loadFile(ideaPath.toString)
      (xml \\ "component")
        .filter(x => x.attribute("project-jdk-type").map(_.text).contains("JavaSDK"))
        .map { n => (n.attribute("languageLevel"), n.attribute("project-jdk-name")) }
        .collectFirst{ case (Some(lang), Some(jdk)) => (lang.text, jdk.text) }
    }.getOrElse(None)
  }

  def xmlFileLayout[T](evaluator: Evaluator[T],
                       rootModule: mill.Module,
                       jdkInfo: (String,String),
                       fetchMillModules: Boolean = true): Seq[(RelPath, scala.xml.Node)] = {

    val modules = rootModule.millInternal.segmentsToModules.values
      .collect{ case x: scalalib.ScalaModule => (x.millModuleSegments, x)}
      .toSeq

    val buildLibraryPaths =
      if (!fetchMillModules) Nil
      else sys.props.get("MILL_BUILD_LIBRARIES") match {
        case Some(found) => Agg.from(found.split(',').map(Path(_)).distinct)
        case None =>
          val artifactNames = Seq("moduledefs", "core", "scalalib", "scalajslib")
          val Result.Success(res) = scalalib.Lib.resolveDependencies(
            Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
            "2.12.4",
            for(name <- artifactNames)
            yield ivy"com.lihaoyi::mill-$name:${sys.props("MILL_VERSION")}"
          )
          res.items.toSeq.map(_.path)
      }

    val resolved = for((path, mod) <- modules) yield {
      val allIvyDeps = T.task{mod.transitiveIvyDeps() ++ mod.scalaLibraryIvyDeps() ++ mod.compileIvyDeps()}
      val externalDependencies = T.task{
        mod.resolveDeps(allIvyDeps)() ++
        Task.traverse(mod.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
      }

      val externalSources = T.task{
        mod.resolveDeps(allIvyDeps, sources = true)()
      }

      val scalacPluginsIvyDeps = T.task{mod.scalacPluginIvyDeps()}
      val scalacOptions = T.task{mod.scalacOptions()}
      val scalacPluginDependencies = T.task{
        mod.resolveDeps(scalacPluginsIvyDeps)()
      }

      val resolvedCp: Loose.Agg[PathRef] = evalOrElse(evaluator, externalDependencies, Loose.Agg.empty)
      val resolvedSrcs: Loose.Agg[PathRef] = evalOrElse(evaluator, externalSources, Loose.Agg.empty)
      val resolvedSp: Loose.Agg[PathRef] = evalOrElse(evaluator, scalacPluginDependencies, Loose.Agg.empty)
      val scalacOpts: Seq[String] = evalOrElse(evaluator, scalacOptions, Seq())

      (path, resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path), mod,
        resolvedSp.map(_.path).filter(_.ext == "jar"), scalacOpts)
    }
    val moduleLabels = modules.map(_.swap).toMap


    val allResolved = resolved.flatMap(_._2) ++ buildLibraryPaths
    val minResolvedLength = allResolved.map(_.segments.length).min
    val commonPrefix = allResolved.map(_.segments.take(minResolvedLength))
      .transpose
      .takeWhile(_.distinct.length == 1)
      .length

    // only resort to full long path names if the jar name is a duplicate
    val pathShortLibNameDuplicate = allResolved
      .distinct
      .map{p => p.segments.last -> p}
      .groupBy(_._1)
      .filter(_._2.size > 1)
      .keySet

    val pathToLibName = allResolved
      .map{p =>
        if (pathShortLibNameDuplicate(p.segments.last))
          (p, p.segments.drop(commonPrefix).mkString("_"))
        else
          (p, p.segments.last)
      }
      .toMap

    val compilerSettings = resolved
      .foldLeft(Map[(Loose.Agg[Path], Seq[String]), Vector[ScalaModule]]()) {
        (r, q) =>
          val key = (q._4, q._5)
          r + (key -> (r.getOrElse(key, Vector()) :+ q._3))
      }

    val fixedFiles = Seq(
      Tuple2(".idea"/"misc.xml", miscXmlTemplate(jdkInfo)),
      Tuple2(".idea"/"scala_settings.xml", scalaSettingsTemplate()),
      Tuple2(
        ".idea"/"modules.xml",
        allModulesXmlTemplate(
          for((path, mod) <- modules)
            yield moduleName(path)
        )
      ),
      Tuple2(
        ".idea_modules"/"root.iml",
        rootXmlTemplate(
          for(path <- buildLibraryPaths)
          yield pathToLibName(path)
        )
      ),
      Tuple2(
        ".idea"/"scala_compiler.xml",
        scalaCompilerTemplate(compilerSettings)
      )
    )

    val libraries = allResolved.map{path =>
      val url = "jar://" + path + "!/"
      val name = pathToLibName(path)
      Tuple2(".idea"/'libraries/s"$name.xml", libraryXmlTemplate(name, url))
    }

    val buildLibraries = buildLibraryPaths.map{path =>
      val url = "jar://" + path + "!/"
      val name = pathToLibName(path)
      Tuple2(".idea"/'libraries/s"$name.xml", libraryXmlTemplate(name, url))
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
      val Seq(scalaVersion: String) = evaluator.evaluate(Agg(mod.scalaVersion)).values
      val generatedSourceOutPath = Evaluator.resolveDestPaths(
        evaluator.outPath,
        mod.generatedSources.ctx.segments
      )

      val elem = moduleXmlTemplate(
        mod.millModuleBasePath.value,
        scalaVersion,
        Strict.Agg.from(resourcesPathRefs.map(_.path)),
        Strict.Agg.from(normalSourcePaths),
        Strict.Agg.from(generatedSourcePaths),
        paths.out,
        generatedSourceOutPath.dest,
        Strict.Agg.from(resolvedDeps.map(pathToLibName)),
        Strict.Agg.from(mod.moduleDeps.map{ m => moduleName(moduleLabels(m))}.distinct)
      )
      Tuple2(".idea_modules"/s"${moduleName(path)}.iml", elem)
    }

    fixedFiles ++ libraries ++ moduleFiles ++ buildLibraries
  }

  def evalOrElse[T](evaluator: Evaluator[_], e: Task[T], default: => T): T = {
    evaluator.evaluate(Agg(e)).values match {
      case Seq() => default
      case Seq(e: T) => e
    }
  }

  def relify(p: Path) = {
    val r = p.relativeTo(pwd/".idea_modules")
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
          <module fileurl="file://$PROJECT_DIR$/.idea_modules/root.iml" filepath="$PROJECT_DIR$/.idea_modules/root.iml" />
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
  def libraryXmlTemplate(name: String, url: String) = {
    <component name="libraryTable">
      <library name={name} type={if(name.contains("scala-library-")) "Scala" else null}>
        <CLASSES>
          <root url={url}/>
        </CLASSES>
      </library>
    </component>
  }
  def moduleXmlTemplate(basePath: Path,
                        scalaVersion: String,
                        resourcePaths: Strict.Agg[Path],
                        normalSourcePaths: Strict.Agg[Path],
                        generatedSourcePaths: Strict.Agg[Path],
                        compileOutputPath: Path,
                        generatedSourceOutputPath: Path,
                        libNames: Strict.Agg[String],
                        depNames: Strict.Agg[String]) = {
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager">
        <output url={"file://$MODULE_DIR$/" + relify(compileOutputPath) + "/dest/classes"} />
        <exclude-output />
        <content url={"file://$MODULE_DIR$/" + relify(generatedSourceOutputPath)} />
        <content url={"file://$MODULE_DIR$/" + relify(basePath)}>
          {
          for (normalSourcePath <- normalSourcePaths.toSeq.sorted)
            yield
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(normalSourcePath)} isTestSource="false" />
          }
          {
          for (generatedSourcePath <- generatedSourcePaths.toSeq.sorted)
            yield
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(generatedSourcePath)} isTestSource="false" generated="true" />
          }
          {
          for (resourcePath <- resourcePaths.toSeq.sorted)
            yield
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(resourcePath)} isTestSource="false"  type="java-resource" />
          }
          <excludeFolder url={"file://$MODULE_DIR$/" +  relify(basePath) + "/target"} />
        </content>
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
        <orderEntry type="library" name={s"scala-sdk-$scalaVersion"} level="application" />

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
  def scalaCompilerTemplate(settings: Map[(Loose.Agg[Path], Seq[String]), Seq[ScalaModule]]) = {

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
