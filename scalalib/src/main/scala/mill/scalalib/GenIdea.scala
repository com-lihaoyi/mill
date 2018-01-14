package mill.scalalib

import ammonite.ops._
import mill.define.{Segment, Segments, Target}
import mill.eval.{Evaluator, PathRef, RootModuleLoader}
import mill.scalalib
import mill.util.Ctx.{LoaderCtx, LogCtx}
import mill.util.{Loose, PrintLogger, Strict}
import mill.util.Strict.Agg

object GenIdea {

  def apply()(implicit ctx: LoaderCtx with LogCtx): Unit = {
    val rootModule = ctx.load(RootModuleLoader)
    val pp = new scala.xml.PrettyPrinter(999, 4)
    rm! pwd/".idea"
    rm! pwd/".idea_modules"


    val evaluator = new Evaluator(pwd / 'out, pwd, rootModule , ctx.log)

    for((relPath, xml) <- xmlFileLayout(evaluator, rootModule)){
      write.over(pwd/relPath, pp.format(xml))
    }
  }

  def xmlFileLayout[T](evaluator: Evaluator[T], rootModule: mill.Module): Seq[(RelPath, scala.xml.Node)] = {


    val modules = rootModule.modules.collect{case x: scalalib.Module => (x.millModuleSegments, x)}.toSeq

    val resolved = for((path, mod) <- modules) yield {
      val Seq(resolvedCp: Loose.Agg[PathRef], resolvedSrcs: Loose.Agg[PathRef]) =
        evaluator.evaluate(Agg(mod.externalCompileDepClasspath, mod.externalCompileDepSources))
          .values

      (path, resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path), mod)
    }
    val moduleLabels = modules.map(_.swap).toMap

    val fixedFiles = Seq(
      Tuple2(".idea"/"misc.xml", miscXmlTemplate()),
      Tuple2(
        ".idea"/"modules.xml",
        allModulesXmlTemplate(
          for((path, mod) <- modules)
          yield moduleName(path)
        )
      ),
      Tuple2(".idea_modules"/"root.iml", rootXmlTemplate())
    )

    val allResolved = resolved.flatMap(_._2).distinct
    val minResolvedLength = allResolved.map(_.segments.length).min
    val commonPrefix = allResolved.map(_.segments.take(minResolvedLength))
      .transpose
      .takeWhile(_.distinct.length == 1)
      .length

    val pathToLibName = allResolved
      .map{p => (p, p.segments.drop(commonPrefix).mkString("_"))}
      .toMap

    val libraries = allResolved.map{path =>
      val url = "jar://" + path + "!/"
      val name = pathToLibName(path)
      Tuple2(".idea"/'libraries/s"$name.xml", libraryXmlTemplate(name, url))
    }

    val moduleFiles = resolved.map{ case (path, resolvedDeps, mod) =>
      val Seq(
        sourcesPathRef: Loose.Agg[PathRef],
        generatedSourcePathRefs: Loose.Agg[PathRef],
        allSourcesPathRefs: Loose.Agg[PathRef]
      ) = evaluator.evaluate(Agg(mod.sources, mod.generatedSources, mod.allSources)).values

      val generatedSourcePaths = generatedSourcePathRefs.map(_.path)
      val normalSourcePaths = (allSourcesPathRefs.map(_.path).toSet -- generatedSourcePaths.toSet).toSeq

      val paths = Evaluator.resolveDestPaths(
        evaluator.workspacePath,
        mod.compile.ctx.segments
      )

      val elem = moduleXmlTemplate(
        Strict.Agg.from(normalSourcePaths),
        Strict.Agg.from(generatedSourcePaths),
        Strict.Agg(paths.out),
        Strict.Agg.from(resolvedDeps.map(pathToLibName)),
        Strict.Agg.from(mod.projectDeps.map{m => moduleName(moduleLabels(m))}.distinct)
      )
      Tuple2(".idea_modules"/s"${moduleName(path)}.iml", elem)
    }
    fixedFiles ++ libraries ++ moduleFiles
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

  def miscXmlTemplate() = {
    <project version="4">
      <component name="ProjectRootManager" version="2" languageLevel="JDK_1_8" project-jdk-name="1.8 (1)" project-jdk-type="JavaSDK">
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
  def rootXmlTemplate() = {
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager">
        <output url="file://$MODULE_DIR$/../out"/>
        <content url="file://$MODULE_DIR$/.." />
        <exclude-output/>
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />
      </component>
    </module>
  }
  def libraryXmlTemplate(name: String, url: String) = {
    <component name="libraryTable">
      <library name={name} type={if(name.contains("org_scala-lang_scala-library_")) "Scala" else null}>
        <CLASSES>
          <root url={url}/>
        </CLASSES>
      </library>
    </component>
  }
  def moduleXmlTemplate(normalSourcePaths: Strict.Agg[Path],
                        generatedSourcePaths: Strict.Agg[Path],
                        outputPaths: Strict.Agg[Path],
                        libNames: Strict.Agg[String],
                        depNames: Strict.Agg[String]) = {
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager">
        {
        for(outputPath <- outputPaths.toSeq)
        yield <output url={"file://$MODULE_DIR$/" + relify(outputPath) + "/dest/classes"} />
        }

        <exclude-output />
        {
        for (normalSourcePath <- normalSourcePaths.toSeq)
          yield
            <content url={"file://$MODULE_DIR$/" + relify(normalSourcePath)}>
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(normalSourcePath)} isTestSource="false" />
            </content>
        }
        {
        for (generatedSourcePath <- generatedSourcePaths.toSeq)
          yield
            <content url={"file://$MODULE_DIR$/" + relify(generatedSourcePath)}>
              <sourceFolder url={"file://$MODULE_DIR$/" + relify(generatedSourcePath)} isTestSource="false" generated="true" />
            </content>
        }
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />

        {
        for(name <- libNames.toSeq)
        yield <orderEntry type="library" name={name} level="project" />

        }
        {
        for(depName <- depNames.toSeq)
        yield <orderEntry type="module" module-name={depName} exported="" />
        }
      </component>
    </module>
  }
}
