package mill.main

import mill.eval.Evaluator
import scala.annotation.tailrec
import mill.api.*
import mill.define.*
import scala.collection.mutable
import pprint.{Renderer, Tree, Truncated}
import mill.moduledefs.Scaladoc
import scala.reflect.NameTransformer.decode

private object Inspect {
  private lazy val inspectItemIndent = "    "

  private def cleanupScaladoc(v: String): Array[String] = {
    v.linesIterator.map(
      _.dropWhile(_.isWhitespace)
        .stripPrefix("/**")
        .stripPrefix("*/")
        .stripPrefix("*")
        .stripSuffix("**/")
        .stripSuffix("*/")
        .dropWhile(_.isWhitespace)
        .reverse
        .dropWhile(_.isWhitespace)
        .reverse
    ).toArray
      .dropWhile(_.isEmpty)
      .reverse
      .dropWhile(_.isEmpty)
      .reverse
  }

  def inspect(evaluator: Evaluator, tasks: Seq[String]) = {

    /** Find a parent classes of the given class queue. */
    @tailrec
    def resolveParents(queue: List[Class[?]], seen: Seq[Class[?]] = Seq()): Seq[Class[?]] = {
      queue match {
        case Nil => seen
        case cand :: rest if seen.contains(cand) => resolveParents(rest, seen)
        case cand :: rest =>
          val sups = Option(cand.getSuperclass).toList ++ cand.getInterfaces.toList
          resolveParents(sups ::: rest, seen ++ Seq(cand))
      }
    }

    def renderFileName(t: NamedTask[?]) = {
      // handle both Windows or Unix separators
      val fullFileName = t.ctx.fileName.replaceAll(raw"\\", "/")
      val basePath = WorkspaceRoot.workspaceRoot.toString.replaceAll(raw"\\", "/") + "/"
      val name =
        if (fullFileName.startsWith(basePath)) {
          fullFileName.drop(basePath.length)
        } else {
          fullFileName.split('/').last
        }
      s"${name}:${t.ctx.lineNum}"
    }

    def pprintTask(t: NamedTask[?], evaluator: Evaluator): Tree.Lazy = {
      val seen = mutable.Set.empty[Task[?]]

      def rec(t: Task[?]): Seq[Segments] = {
        if (seen(t)) Nil // do nothing
        else t match {
          case t: mill.define.Target[_]
              if evaluator.rootModule.moduleInternal.targets.contains(t) =>
            Seq(t.ctx.segments)
          case _ =>
            seen.add(t)
            t.inputs.flatMap(rec)
        }
      }

      val annots = for {
        c <- resolveParents(List(t.ctx.enclosingCls))
        m <- c.getMethods
        if m.getName == t.ctx.segments.last.pathSegments.head
        a = m.getAnnotation(classOf[mill.moduledefs.Scaladoc])
        if a != null
      } yield a

      val allDocs =
        for (a <- annots.distinct)
          yield cleanupScaladoc(a.value).map("\n" + inspectItemIndent + _).mkString

      pprint.Tree.Lazy { ctx =>
        val mainMethodSig =
          if (t.asCommand.isEmpty) List()
          else {
            val mainDataOpt = evaluator
              .rootModule
              .moduleCtx
              .discover
              .resolveEntrypoint(t.ctx.enclosingCls, t.ctx.segments.last.value)

            mainDataOpt match {
              case Some(mainData) if mainData.renderedArgSigs.nonEmpty =>
                val rendered = mainargs.Renderer.formatMainMethodSignature(
                  mainData,
                  leftIndent = 2,
                  totalWidth = 100,
                  leftColWidth = mainargs.Renderer.getLeftColWidth(mainData.renderedArgSigs),
                  docsOnNewLine = false,
                  customName = None,
                  customDoc = None,
                  sorted = true,
                  nameMapper = mainargs.Util.kebabCaseNameMapper
                )

                // trim first line containing command name, since we already render
                // the command name below with the filename and line num
                val trimmedRendered = rendered
                  .linesIterator
                  .drop(1)
                  .mkString("\n")

                List("\n", trimmedRendered, "\n")

              case _ => List()
            }
          }

        Iterator(
          ctx.applyPrefixColor(t.toString).toString,
          "(",
          renderFileName(t),
          ")",
          allDocs.mkString("\n"),
          "\n"
        ) ++
          mainMethodSig.iterator ++
          Iterator(
            "\n",
            ctx.applyPrefixColor("Inputs").toString,
            ":"
          ) ++ t.inputs.iterator.flatMap(rec).map("\n" + inspectItemIndent + _.render).distinct
      }
    }

    def pprintModule(t: mill.resolve.Resolve.ModuleTask[?], evaluator: Evaluator): Tree.Lazy = {
      val cls = t.module.getClass
      val annotation = cls.getAnnotation(classOf[Scaladoc])
      val scaladocOpt = Option(annotation).map(annotation =>
        cleanupScaladoc(annotation.value).map("\n" + inspectItemIndent + _).mkString
      )

      def parentFilter(parent: Class[?]) =
        classOf[Module].isAssignableFrom(parent) && classOf[Module] != parent

      val parents = (Option(cls.getSuperclass).toSeq ++ cls.getInterfaces).distinct

      val inheritedModules = parents.filter(parentFilter)

      def getModuleDeps(methodName: String): Seq[Module] = cls
        .getMethods
        .find(m => decode(m.getName) == methodName)
        .toSeq
        .map(_.invoke(t.module).asInstanceOf[Seq[Module]])
        .flatten

      val javaModuleDeps = getModuleDeps("moduleDeps")
      val javaCompileModuleDeps = getModuleDeps("compileModuleDeps")
      val javaRunModuleDeps = getModuleDeps("runModuleDeps")
      val hasModuleDeps =
        javaModuleDeps.nonEmpty || javaCompileModuleDeps.nonEmpty || javaRunModuleDeps.nonEmpty

      val defaultTaskOpt = t.module match {
        case taskMod: TaskModule => Some(s"${t.module}.${taskMod.defaultCommandName()}")
        case _ => None
      }

      val methodMap = evaluator.rootModule.moduleCtx.discover.classInfo
      val tasks = methodMap
        .get(cls)
        .map { node => node.declaredTasks.map(task => s"${t.module}.${task.name}") }
        .toSeq.flatten
      pprint.Tree.Lazy { ctx =>
        Iterator(
          // module name(module/file:line)
          Iterator(
            ctx.applyPrefixColor(t.module.toString).toString,
            s"(${renderFileName(t)})"
          ),
          // Scaladoc
          Iterator(scaladocOpt).flatten,
          // Inherited Modules:
          Iterator(
            "\n\n",
            ctx.applyPrefixColor("Inherited Modules").toString,
            ":"
          ),
          inheritedModules.map("\n" + inspectItemIndent + _.getName),
          // Module Dependencies: (JavaModule)
          if (hasModuleDeps) Iterator(
            "\n\n",
            ctx.applyPrefixColor("Module Dependencies").toString,
            ":"
          )
          else Iterator.empty[String],
          javaModuleDeps.map("\n" + inspectItemIndent + _.toString),
          javaCompileModuleDeps.map("\n" + inspectItemIndent + _.toString + " (compile)"),
          javaRunModuleDeps.map("\n" + inspectItemIndent + _.toString + " (runtime)"),
          // Default Task:
          defaultTaskOpt.fold(Iterator.empty[String])(task =>
            Iterator("\n\n", ctx.applyPrefixColor("Default Task").toString, ": ", task)
          ),
          // Tasks (re-/defined):
          if (tasks.isEmpty) Iterator.empty[String]
          else Iterator(
            "\n\n",
            ctx.applyPrefixColor("Tasks (re-/defined)").toString,
            ":\n",
            inspectItemIndent,
            tasks.mkString("\n" + inspectItemIndent)
          )
        ).flatten
      }
    }

    evaluator.resolveTasks(tasks, SelectMode.Multi, resolveToModuleTasks = true).map {
      tasks =>
        val output = (for {
          task <- tasks
          tree = task match {
            case t: mill.resolve.Resolve.ModuleTask[_] => pprintModule(t, evaluator)
            case t => pprintTask(t, evaluator)
          }
          defaults = pprint.PPrinter()
          renderer = new Renderer(
            defaults.defaultWidth,
            defaults.colorApplyPrefix,
            defaults.colorLiteral,
            defaults.defaultIndent
          )
          rendered = renderer.rec(tree, 0, 0).iter
          truncated = new Truncated(rendered, defaults.defaultWidth, defaults.defaultHeight)
        } yield {
          val sb = new StringBuilder()
          for { str <- truncated ++ Iterator("\n") } sb.append(str)
          sb.toString()
        }).mkString("\n")
        println(output)
        fansi.Str(output).plainText
    }
  }
}
