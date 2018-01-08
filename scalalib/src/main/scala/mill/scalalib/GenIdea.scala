package mill.scalalib

import ammonite.ops._
import mill.define.{Segment, Segments, Target}
import mill.discover.{Discovered, Mirror}
import mill.eval.{Evaluator, PathRef}
import mill.util.Ctx.{LoaderCtx, LogCtx}
import mill.util.{OSet, PrintLogger}

object GenIdea {

  def apply()(implicit ctx: LoaderCtx with LogCtx): Unit = {
    val mapping = ctx.load(mill.discover.Discovered.Mapping)
    val pp = new scala.xml.PrettyPrinter(999, 4)
    rm! pwd/".idea"
    rm! pwd/".idea_modules"


    val evaluator = new Evaluator(pwd / 'out, pwd, mapping, ctx.log)

    for((relPath, xml) <- xmlFileLayout(evaluator)){
      write.over(pwd/relPath, pp.format(xml))
    }
  }

  def xmlFileLayout[T](evaluator: Evaluator[T]): Seq[(RelPath, scala.xml.Node)] = {

    val modules = Mirror
      .traverse(evaluator.mapping.base, evaluator.mapping.mirror){ (h, p) =>
        h.node(evaluator.mapping.base, p.value.map{case Segment.Cross(vs) => vs.toList case _ => Nil}.toList) match {
          case m: Module => Seq(p -> m)
          case _ => Nil
        }
      }

    val resolved = for((path, mod) <- modules) yield {
      val Seq(resolvedCp: Seq[PathRef], resolvedSrcs: Seq[PathRef]) =
        evaluator.evaluate(OSet(mod.externalCompileDepClasspath, mod.externalCompileDepSources))
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
      val Seq(sourcePath: PathRef) =
        evaluator.evaluate(OSet(mod.sources)).values

      val paths = Evaluator.resolveDestPaths(
        evaluator.workspacePath,
        mod.compile.ctx.segments
      )

      val elem = moduleXmlTemplate(
        sourcePath.path,
        Seq(paths.out),
        resolvedDeps.map(pathToLibName),
        for(m <- mod.projectDeps)
        yield moduleName(moduleLabels(m))
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
  def moduleXmlTemplate(sourcePath: Path,
                        outputPaths: Seq[Path],
                        libNames: Seq[String],
                        depNames: Seq[String]) = {
    <module type="JAVA_MODULE" version="4">
      <component name="NewModuleRootManager">
        {
        for(outputPath <- outputPaths)
        yield <output url={"file://$MODULE_DIR$/" + relify(outputPath)} />
        }

        <exclude-output />
        <content url={"file://$MODULE_DIR$/" + relify(sourcePath)}>
          <sourceFolder url={"file://$MODULE_DIR$/" + relify(sourcePath)} isTestSource="false" />
        </content>
        <orderEntry type="inheritedJdk" />
        <orderEntry type="sourceFolder" forTests="false" />

        {
        for(name <- libNames)
        yield <orderEntry type="library" name={name} level="project" />

        }
        {
        for(depName <- depNames)
        yield <orderEntry type="module" module-name={depName} exported="" />
        }
      </component>
    </module>
  }
}
