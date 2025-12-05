package mill.util

import scala.annotation.tailrec
import mill.api.{Evaluator, *}
import scala.collection.mutable
import pprint.{Renderer, Tree, Truncated}
import mill.moduledefs.Scaladoc
import scala.reflect.NameTransformer.decode

private[mill] object Inspect {
  private lazy val inspectItemIndent = "    "

  def cleanupScaladoc(v: String): Array[String] = {
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

  def scaladocForTask(segments: Segments, enclosingCls: Class[?]) = {
    val annots = for {
      c <- resolveParents(List(enclosingCls))
      m <- c.getMethods
      if m.getName == segments.last.pathSegments.head
      a = m.getAnnotation(classOf[mill.moduledefs.Scaladoc])
      if a != null
    } yield a

    for (a <- annots.distinct)
      yield cleanupScaladoc(a.value).map("\n" + inspectItemIndent + _).mkString
  }

  def scaladocForModule(cls: Class[?]) = {

    // For `RootModule`s named `package_`, the scaladoc annotation ends
    // up on the companion `class` rather than on the `object`.
    val companionClsName = cls.getName match {
      case s"$prefix.package_$$" => Some(s"$prefix.package_")
      case _ => None
    }

    val companionClsOpt = companionClsName.map(cls.getClassLoader.loadClass(_))

    val annotation = cls.getAnnotation(classOf[Scaladoc])
    val companionAnnotation =
      companionClsOpt.map(_.getAnnotation(classOf[Scaladoc])).flatMap(Option(_))
    val scaladocOpt = (Option(annotation) ++ companionAnnotation).map(annotation =>
      cleanupScaladoc(annotation.value).map("\n" + inspectItemIndent + _).mkString
    )

    scaladocOpt
  }
  def inspect(evaluator: Evaluator, tasks: Seq[String]) = {

    def renderFileName(ctx: mill.api.ModuleCtx) = {
      // handle both Windows or Unix separators
      val fullFileName = ctx.fileName.replaceAll(raw"\\", "/")
      val basePath = BuildCtx.workspaceRoot.toString.replaceAll(raw"\\", "/") + "/"
      val name =
        if (fullFileName.startsWith(basePath)) {
          fullFileName.drop(basePath.length)
        } else {
          fullFileName.split('/').last
        }
      s"${name}:${ctx.lineNum}"
    }

    def pprintTask(t: Task.Named[?], evaluator: Evaluator): Tree.Lazy = {
      val seen = mutable.Set.empty[Task[?]]

      def rec(t: Task[?]): Seq[Segments] = {
        if (seen(t)) Nil // do nothing
        else t match {
          case t: mill.api.Task.Simple[_]
              if evaluator.rootModule.moduleInternal.simpleTasks.contains(t) =>
            Seq(t.ctx.segments)
          case _ =>
            seen.add(t)
            t.inputs.flatMap(rec)
        }
      }

      val allDocs = scaladocForTask(t.ctx.segments, t.ctx.enclosingCls)

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
          renderFileName(t.ctx),
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

    def pprintModule(module: mill.Module, evaluator: Evaluator): Tree.Lazy = {
      val cls = module.getClass

      val scaladocOpt = scaladocForModule(cls)
      def parentFilter(parent: Class[?]) =
        classOf[Module].isAssignableFrom(parent) && classOf[Module] != parent

      val parents = (Option(cls.getSuperclass).toSeq ++ cls.getInterfaces).distinct

      val inheritedModules = parents.filter(parentFilter)

      def getModuleDeps(methodName: String): Seq[Module] = cls
        .getMethods
        .find(m => decode(m.getName) == methodName)
        .toSeq
        .map(_.invoke(module).asInstanceOf[Seq[Module]])
        .flatten

      val javaModuleDeps = getModuleDeps("moduleDeps")
      val javaCompileModuleDeps = getModuleDeps("compileModuleDeps")
      val javaRunModuleDeps = getModuleDeps("runModuleDeps")
      val hasModuleDeps =
        javaModuleDeps.nonEmpty || javaCompileModuleDeps.nonEmpty || javaRunModuleDeps.nonEmpty

      val defaultTaskOpt = module match {
        case taskMod: DefaultTaskModule => Some(s"${module}.${taskMod.defaultTask()}")
        case _ => None
      }

      val methodMap = evaluator.rootModule.moduleCtx.discover.classInfo
      val tasks = methodMap
        .get(cls)
        .map { node => node.declaredTasks.map(task => s"${module}.${task.name}") }
        .toSeq.flatten

      pprint.Tree.Lazy { ctx =>
        Iterator(
          // module name(module/file:line)
          Iterator(
            ctx.applyPrefixColor(module.toString).toString,
            s"(${renderFileName(module.moduleCtx)})"
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

    evaluator.resolveModulesOrTasks(tasks, SelectMode.Multi, resolveToModuleTasks = true).map {
      modulesOrTasks =>
        val output0 = for (moduleOrTask <- modulesOrTasks) yield {
          val tree = moduleOrTask match {
            case Left(module) => pprintModule(module, evaluator)
            case Right(task) => pprintTask(task, evaluator)
          }
          val defaults = pprint.PPrinter()
          val renderer = new Renderer(
            defaults.defaultWidth,
            defaults.colorApplyPrefix,
            defaults.colorLiteral,
            defaults.defaultIndent
          )
          val rendered = renderer.rec(tree, 0, 0).iter
          val truncated = new Truncated(rendered, defaults.defaultWidth, defaults.defaultHeight)
          (truncated ++ Iterator("\n")).mkString
        }
        val output = output0.mkString("\n")
        println(output)
        fansi.Str(output).plainText
    }
  }
}
