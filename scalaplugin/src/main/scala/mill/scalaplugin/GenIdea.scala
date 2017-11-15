package mill.scalaplugin

import ammonite.ops.{Path, pwd, rm, write}
import mill.discover.{Discovered, Hierarchy}
import mill.eval.{Evaluator, PathRef}
import mill.util.OSet

object GenIdea {
  def apply[T: Discovered](obj: T) = {
    val pp = new scala.xml.PrettyPrinter(999, 4)
    val discovered = implicitly[Discovered[T]]
    def rec(x: Hierarchy[T]): Seq[(Seq[String], Module)] = {
      val node = x.node(obj)
      val self = node match{
        case m: Module => Seq(x.path -> m)
        case _ => Nil
      }

      self ++ x.children.flatMap(rec)
    }
    val mapping = Discovered.mapping(obj)(discovered)
    val workspacePath = pwd / 'out
    val evaluator = new Evaluator(workspacePath, mapping)

    val modules = rec(discovered.hierarchy)
    println(modules)

    rm! pwd/".idea"
    rm! pwd/".idea_modules"
    val moduleDir = pwd/".idea_modules"
    write(
      pwd/".idea"/"misc.xml",
      pp.format(
        <project version="4">
          <component name="ProjectRootManager" version="2" languageLevel="JDK_1_8" project-jdk-name="1.8 (1)" project-jdk-type="JavaSDK">
            <output url="file://$PROJECT_DIR$/target/idea_output" />
          </component>
        </project>
      )
    )
    val allModulesFile =
      <project version="4">
        <component name="ProjectModuleManager">
          <modules>
            <module fileurl="file://$PROJECT_DIR$/.idea_modules/root.iml" filepath="$PROJECT_DIR$/.idea_modules/root.iml" />
            {
            for((path, mod)  <- modules)
              yield {
                val selector = path.mkString(".")
                val filepath = "$PROJECT_DIR$/.idea_modules/" + selector.toLowerCase() + ".iml"
                val fileurl = "file://" + filepath
                  <module fileurl={fileurl} filepath={filepath} />
              }
            }
          </modules>
        </component>
      </project>

    write(pwd/".idea"/"modules.xml", pp.format(allModulesFile))
    val resolved = for((path, mod) <- modules) yield {
      val Seq(resolvedCp: Seq[PathRef], resolvedSrcs: Seq[PathRef]) =
        evaluator.evaluate(OSet(mod.externalCompileDepClasspath, mod.externalCompileDepSources))
          .values


      (path, resolvedCp.map(_.path).filter(_.ext == "jar") ++ resolvedSrcs.map(_.path), mod)
    }
    val allResolved = resolved.flatMap(_._2).distinct
    val minResolvedLength = allResolved.map(_.segments.length).min
    val commonPrefix = allResolved.map(_.segments.take(minResolvedLength))
      .transpose
      .takeWhile(_.distinct.length == 1)
      .length

    val libraryFiles = for(path <- allResolved) yield {
      val url = "jar://" + path + "!/"
      val name = path.segments.drop(commonPrefix).mkString("_")
      val elem = <component name="libraryTable">
        <library name={name}>
          <CLASSES>
            <root url={url}/>
          </CLASSES>
        </library>
      </component>
      write(pwd/".idea"/'libraries/s"$name.xml", pp.format(elem))
    }

    def relify(p: Path) = {
      val r = p.relativeTo(moduleDir)
      (Seq.fill(r.ups)("..") ++ r.segments).mkString("/")
    }
    val moduleFiles = for((path, resolvedDeps, mod) <- resolved) yield {
      val sourcePath = evaluator.evaluate(OSet(mod.sources)).values.head.asInstanceOf[PathRef].path
      val outputPath = evaluator.evaluate(OSet(mod.compile)).values.head.asInstanceOf[PathRef].path
      val base = mod.getClass.getName.stripSuffix("$").split('.').last.split('$').last.toLowerCase
      val elem = <module type="JAVA_MODULE" version="4">
        <component name="NewModuleRootManager">
          <output url={"file://$MODULE_DIR$/" + relify(outputPath)} />
          <exclude-output />
          <content url={"file://$MODULE_DIR$/" + relify(sourcePath)}>
            <sourceFolder url={"file://$MODULE_DIR$/" + relify(sourcePath)} isTestSource="false" />
          </content>
          <orderEntry type="inheritedJdk" />
          <orderEntry type="sourceFolder" forTests="false" />

          {
          for(r <- resolvedDeps) yield {
              <orderEntry type="library" name={r.segments.drop(commonPrefix).mkString("_")} level="project" />
          }
          }
          {
          for(m <- mod.projectDeps) yield {
            val depName = resolved.find(_._3 eq m).get._1.mkString(".").toLowerCase
              <orderEntry type="module" module-name={depName} exported="" />
          }
          }
        </component>
      </module>
      write(pwd/".idea_modules"/s"${path.mkString(".").toLowerCase}.iml", pp.format(elem))
    }
    val rootModule =
      <module type="JAVA_MODULE" version="4">
        <component name="NewModuleRootManager">
          <output url="file://$MODULE_DIR$/../out"/>
          <content url="file://$MODULE_DIR$/.." />
          <exclude-output/>
          <orderEntry type="inheritedJdk" />
          <orderEntry type="sourceFolder" forTests="false" />
        </component>
      </module>
    write(pwd/".idea_modules"/"root.iml", pp.format(rootModule))

  }

}
