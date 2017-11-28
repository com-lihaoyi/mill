package mill.scalaplugin

import ammonite.ops._
import mill.discover.Mirror.Segment
import mill.discover.{Discovered, Mirror}
import mill.eval.{Evaluator, PathRef}
import mill.util.OSet

object GenIdea {

  def apply[T: Discovered](obj: T): Unit = {
    val pp = new scala.xml.PrettyPrinter(999, 4)
    rm! pwd/".idea"
    rm! pwd/".idea_modules"

    for((relPath, xml) <- xmlFileLayout(obj)){
      write(pwd/relPath, pp.format(xml))
    }
  }

  def xmlFileLayout[T: Discovered](obj: T): Seq[(RelPath, scala.xml.Node)] = {
    val discovered = implicitly[Discovered[T]]
    val mapping = Discovered.mapping(obj)(discovered)
    val workspacePath = pwd / 'out
    val evaluator = new Evaluator(workspacePath, mapping, _ => ())

    val modules = Mirror
      .traverse(obj, discovered.mirror){ (h, p) =>
        h.node(obj, p.reverse.map{case Mirror.Segment.Cross(vs) => vs.toList case _ => Nil}.toList) match {
          case m: ScalaModule => Seq(p -> m)
          case _ => Nil
        }
      }
      .map{case (p, v) => (p.reverse, v)}

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
          yield path.collect{case Segment.Label(v) => v}.mkString(".").toLowerCase
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
        mod.sources.map(s => evaluator.evaluate(OSet(s)).values).flatten

      val (destPath, jsonPath) = evaluator.resolveDestPaths(mapping(mod.compile))

      val elem = moduleXmlTemplate(
        sourcePath.path,
        Seq(destPath, jsonPath),
        resolvedDeps.map(pathToLibName),
        for(m <- mod.projectDeps)
        yield moduleLabels(m).collect{case Segment.Label(v) => v}.mkString(".").toLowerCase
      )
      Tuple2(".idea_modules"/s"${path.collect{case Segment.Label(v) => v}.mkString(".").toLowerCase}.iml", elem)
    }
    fixedFiles ++ libraries ++ moduleFiles
  }


  def relify(p: Path) = {
    val r = p.relativeTo(pwd/".idea_modules")
    (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
  }
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
