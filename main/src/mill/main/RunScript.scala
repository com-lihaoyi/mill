package mill.main

import java.nio.file.NoSuchFileException

import ammonite.interp.Interpreter
import ammonite.runtime.SpecialClassLoader
import ammonite.util.Util.CodeSource
import ammonite.util.{Name, Res, Util}
import mill.define
import mill.define._
import mill.eval.{Evaluator, PathRef, Result}
import mill.util.{EitherOps, Logger, ParseArgs, Watched}
import mill.util.Strict.Agg
import upickle.Js

import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Custom version of ammonite.main.Scripts, letting us run the build.sc script
  * directly without going through Ammonite's main-method/argument-parsing
  * subsystem
  */
object RunScript{
  def runScript(home: os.Path,
                wd: os.Path,
                path: os.Path,
                instantiateInterpreter: => Either[(Res.Failing, Seq[(os.Path, Long)]), ammonite.interp.Interpreter],
                scriptArgs: Seq[String],
                stateCache: Option[Evaluator.State],
                log: Logger,
                env : Map[String, String])
  : (Res[(Evaluator, Seq[PathRef], Either[String, Seq[Js.Value]])], Seq[(os.Path, Long)]) = {

    val (evalState, interpWatched) = stateCache match{
      case Some(s) if watchedSigUnchanged(s.watched) => Res.Success(s) -> s.watched
      case _ =>
        instantiateInterpreter match{
          case Left((res, watched)) => (res, watched)
          case Right(interp) =>
            interp.watch(path)
            val eval =
              for(rootModule <- evaluateRootModule(wd, path, interp, log))
              yield Evaluator.State(
                rootModule,
                rootModule.getClass.getClassLoader.asInstanceOf[SpecialClassLoader].classpathSignature,
                mutable.Map.empty[Segments, (Int, Any)],
                interp.watchedFiles
              )
            (eval, interp.watchedFiles)
        }
    }

    val evalRes =
      for(s <- evalState)
      yield new Evaluator(home, wd / 'out, wd / 'out, s.rootModule, log,
        s.classLoaderSig, s.workerCache, env)

    val evaluated = for{
      evaluator <- evalRes
      (evalWatches, res) <- Res(evaluateTasks(evaluator, scriptArgs, multiSelect = false))
    } yield {
      (evaluator, evalWatches, res.map(_.flatMap(_._2)))
    }
    (evaluated, interpWatched)
  }

  def watchedSigUnchanged(sig: Seq[(os.Path, Long)]) = {
    sig.forall{case (p, l) => Interpreter.pathSignature(p) == l}
  }

  def evaluateRootModule(wd: os.Path,
                         path: os.Path,
                         interp: ammonite.interp.Interpreter,
                         log: Logger
                        ): Res[mill.define.BaseModule] = {

    val (pkg, wrapper) = Util.pathToPackageWrapper(Seq(), path relativeTo wd)

    for {
      scriptTxt <-
        try Res.Success(Util.normalizeNewlines(os.read(path)))
        catch { case _: NoSuchFileException =>
          log.info("No build file found, you should create build.sc to do something useful")
          Res.Success("")
        }

      processed <- interp.processModule(
        scriptTxt,
        CodeSource(wrapper, pkg, Seq(Name("ammonite"), Name("$file")), Some(path)),
        autoImport = true,
        extraCode = "",
        hardcoded = true
      )

      buildClsName <- processed.blockInfo.lastOption match {
        case Some(meta) => Res.Success(meta.id.wrapperPath)
        case None => Res.Skip
      }

      buildCls = interp
        .evalClassloader
        .loadClass(buildClsName)

      module <- try {
        Util.withContextClassloader(interp.evalClassloader) {
          Res.Success(
            buildCls.getMethod("millSelf")
                    .invoke(null)
                    .asInstanceOf[Some[mill.define.BaseModule]]
                    .get
          )
        }
      } catch {
        case e: Throwable => Res.Exception(e, "")
      }
//      _ <- Res(consistencyCheck(mapping))
    } yield module
  }

  def resolveTasks[T, R: ClassTag](resolver: mill.main.Resolve[R],
                                   evaluator: Evaluator,
                                   scriptArgs: Seq[String],
                                   multiSelect: Boolean) = {
    for {
      parsed <- ParseArgs(scriptArgs, multiSelect = multiSelect)
      (selectors, args) = parsed
      taskss <- {
        val selected = selectors.map { case (scopedSel, sel) =>
          for(res <- prepareResolve(evaluator, scopedSel, sel))
          yield {
            val (rootModule, crossSelectors) = res


            try {
              // We inject the `evaluator.rootModule` into the TargetScopt, rather
              // than the `rootModule`, because even if you are running an external
              // module we still want you to be able to resolve targets from your
              // main build. Resolving targets from external builds as CLI arguments
              // is not currently supported
              mill.eval.Evaluator.currentEvaluator.set(evaluator)
              resolver.resolve(
                sel.value.toList, rootModule, rootModule.millDiscover,
                args, crossSelectors.toList, Nil
              )
            } finally {
              mill.eval.Evaluator.currentEvaluator.set(null)
            }
          }
        }
        EitherOps.sequence(selected)
      }
      res <- EitherOps.sequence(taskss)
    } yield res.flatten
  }

  def resolveRootModule[T](evaluator: Evaluator, scopedSel: Option[Segments]) = {
    scopedSel match {
      case None => Right(evaluator.rootModule)
      case Some(scoping) =>
        for {
          moduleCls <-
            try Right(evaluator.rootModule.getClass.getClassLoader.loadClass(scoping.render + "$"))
            catch {case e: ClassNotFoundException => Left ("Cannot resolve external module " + scoping.render)}
          rootModule <- moduleCls.getField("MODULE$").get(moduleCls) match {
            case rootModule: ExternalModule => Right(rootModule)
            case _ => Left("Class " + scoping.render + " is not an external module")
          }
        } yield rootModule
    }
  }

  def prepareResolve[T](evaluator: Evaluator, scopedSel: Option[Segments], sel: Segments) = {
    for (rootModule<- resolveRootModule(evaluator, scopedSel))
    yield {
      val crossSelectors = sel.value.map {
        case Segment.Cross(x) => x.toList.map(_.toString)
        case _ => Nil
      }
      (rootModule, crossSelectors)
    }
  }

  def evaluateTasks[T](evaluator: Evaluator,
                       scriptArgs: Seq[String],
                       multiSelect: Boolean) = {
    for (targets <- resolveTasks(mill.main.ResolveTasks, evaluator, scriptArgs, multiSelect)) yield {
      val (watched, res) = evaluate(evaluator, Agg.from(targets.distinct))

      val watched2 = for{
        x <- res.right.toSeq
        (Watched(_, extraWatched), _) <- x
        w <- extraWatched
      } yield w

      (watched ++ watched2, res)
    }
  }

  def evaluate(evaluator: Evaluator,
               targets: Agg[Task[Any]]): (Seq[PathRef], Either[String, Seq[(Any, Option[upickle.Js.Value])]]) = {
    val evaluated = evaluator.evaluate(targets)
    val watched = evaluated.results
      .iterator
      .collect {
        case (t: define.Sources, Result.Success(p: Seq[PathRef])) => p
      }
      .flatten
      .toSeq

    val errorStr =
      (for((k, fs) <- evaluated.failing.items()) yield {
        val ks = k match{
          case Left(t) => t.toString
          case Right(t) => t.segments.render
        }
        val fss = fs.map{
          case Result.Exception(t, outerStack) =>
            var current = List(t)
            while(current.head.getCause != null){
              current = current.head.getCause :: current
            }
            current.reverse
              .flatMap( ex =>
                Seq(ex.toString) ++
                ex.getStackTrace.dropRight(outerStack.value.length).map("    " + _)
              )
              .mkString("\n")
          case Result.Failure(t, _) => t
        }
        s"$ks ${fss.mkString(", ")}"
      }).mkString("\n")

    evaluated.failing.keyCount match {
      case 0 =>
        val json = for(t <- targets.toSeq) yield {
          t match {
            case t: mill.define.NamedTask[_] =>
              val jsonFile = Evaluator
                .resolveDestPaths(evaluator.outPath, t.ctx.segments)
                .meta
              val metadata = upickle.default.readJs[Evaluator.Cached](ujson.read(jsonFile.toIO))
              Some(metadata.value)

            case _ => None
          }
        }

        watched -> Right(evaluated.values.zip(json))
      case n => watched -> Left(s"$n targets failed\n$errorStr")
    }
  }

//  def consistencyCheck[T](mapping: Discovered.Mapping[T]): Either[String, Unit] = {
//    val consistencyErrors = Discovered.consistencyCheck(mapping)
//    if (consistencyErrors.nonEmpty) {
//      Left(s"Failed Discovered.consistencyCheck: ${consistencyErrors.map(_.render)}")
//    } else {
//      Right(())
//    }
//  }
}
