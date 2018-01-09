package mill.main

import java.nio.file.NoSuchFileException

import ammonite.interp.Interpreter
import ammonite.ops.{Path, read}
import ammonite.util.Util.CodeSource
import ammonite.util.{Name, Res, Util}
import mill.{PathRef, define}
import mill.define.Task
import mill.define.Segment
import mill.discover.Discovered
import mill.eval.{Evaluator, Result}
import mill.util.{EitherOps, Logger, OSet}
import upickle.Js

/**
  * Custom version of ammonite.main.Scripts, letting us run the build.sc script
  * directly without going through Ammonite's main-method/argument-parsing
  * subsystem
  */
object RunScript{
  def runScript(wd: Path,
                path: Path,
                instantiateInterpreter: => Either[(Res.Failing, Seq[(Path, Long)]), ammonite.interp.Interpreter],
                scriptArgs: Seq[String],
                lastEvaluator: Option[(Seq[(Path, Long)], Evaluator[_])],
                log: Logger)
  : (Res[(Evaluator[_], Seq[(Path, Long)], Either[String, Seq[Js.Value]])], Seq[(Path, Long)]) = {

    val (evalRes, interpWatched) = lastEvaluator match{
      case Some((prevInterpWatchedSig, prevEvaluator))
        if watchedSigUnchanged(prevInterpWatchedSig) =>

        (Res.Success(prevEvaluator), prevInterpWatchedSig)

      case _ =>
        instantiateInterpreter match{
          case Left((res, watched)) => (res, watched)
          case Right(interp) =>
            interp.watch(path)
            val eval =
              for(mapping <- evaluateMapping(wd, path, interp))
              yield new Evaluator(wd / 'out, wd, mapping, log)
            (eval, interp.watchedFiles)
        }
    }

    val evaluated = for{
      evaluator <- evalRes
      (evalWatches, res) <- Res(evaluateTarget(evaluator, scriptArgs))
    } yield {
      val alreadyStale = evalWatches.exists(p => p.sig != new PathRef(p.path, p.quick).sig)
      // If the file changed between the creation of the original
      // `PathRef` and the current moment, use random junk .sig values
      // to force an immediate re-run. Otherwise calculate the
      // pathSignatures the same way Ammonite would and hand over the
      // values, so Ammonite can watch them and only re-run if they
      // subsequently change
      val evaluationWatches =
        if (alreadyStale) evalWatches.map(_.path -> util.Random.nextLong())
        else evalWatches.map(p => p.path -> Interpreter.pathSignature(p.path))

      (evaluator, evaluationWatches, res.map(_.flatMap(_._2)))
    }
    (evaluated, interpWatched)
  }

  def watchedSigUnchanged(sig: Seq[(Path, Long)]) = {
    sig.forall{case (p, l) => Interpreter.pathSignature(p) == l}
  }

  def evaluateMapping(wd: Path,
                      path: Path,
                      interp: ammonite.interp.Interpreter): Res[Discovered.Mapping[_]] = {

    val (pkg, wrapper) = Util.pathToPackageWrapper(Seq(), path relativeTo wd)

    for {
      scriptTxt <-
        try Res.Success(Util.normalizeNewlines(read(path)))
        catch { case e: NoSuchFileException => Res.Failure("Script file not found: " + path) }

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

      mapping <- try {
        Util.withContextClassloader(interp.evalClassloader) {
          Res.Success(
            buildCls.getDeclaredMethod("mapping")
              .invoke(null)
              .asInstanceOf[Discovered.Mapping[_]]
          )
        }
      } catch {
        case e: Throwable => Res.Exception(e, "")
      }
      _ <- Res(consistencyCheck(mapping))
    } yield mapping
  }

  def evaluateTarget[T](evaluator: Evaluator[_],
                        scriptArgs: Seq[String]) = {
    for {
      parsed <- ParseArgs(scriptArgs)
      (selectors, args) = parsed
      targets <- {
        val selected = selectors.map { sel =>
          val crossSelectors = sel.map {
            case Segment.Cross(x) => x.toList.map(_.toString)
            case _ => Nil
          }
          mill.main.Resolve.resolve(
            sel, evaluator.mapping.mirror, evaluator.mapping.base,
            args, crossSelectors, Nil
          )
        }
        EitherOps.sequence(selected)
      }
      (watched, res) = evaluate(evaluator, targets)
    } yield (watched, res)
  }

  def evaluate(evaluator: Evaluator[_],
               targets: Seq[Task[Any]]): (Seq[PathRef], Either[String, Seq[(Any, Option[upickle.Js.Value])]]) = {
    val evaluated = evaluator.evaluate(OSet.from(targets))
    val watched = evaluated.results
      .iterator
      .collect {
        case (t: define.Input[_], Result.Success(p: PathRef)) => p
      }
      .toSeq

    val errorStr =
      (for((k, fs) <- evaluated.failing.items()) yield {
        val ks = k match{
          case Left(t) => t.toString
          case Right(t) => t.segments.render
        }
        val fss = fs.map{
          case Result.Exception(t, outerStack) =>
            t.toString + t.getStackTrace.dropRight(outerStack.length).map("\n    " + _).mkString
          case Result.Failure(t) => t
        }
        s"$ks ${fss.mkString(", ")}"
      }).mkString("\n")

    evaluated.failing.keyCount match {
      case 0 =>
        val json = for(t <- targets) yield {
          t match {
            case t: mill.define.NamedTask[_] =>
              val jsonFile = Evaluator
                .resolveDestPaths(evaluator.workspacePath, t.ctx.segments)
                .meta
              val metadata = upickle.json.read(jsonFile.toIO)
              Some(metadata(1))

            case _ => None
          }
        }

        watched -> Right(evaluated.values.zip(json))
      case n => watched -> Left(s"$n targets failed\n$errorStr")
    }
  }

  def consistencyCheck[T](mapping: Discovered.Mapping[T]): Either[String, Unit] = {
    val consistencyErrors = Discovered.consistencyCheck(mapping)
    if (consistencyErrors.nonEmpty) {
      Left(s"Failed Discovered.consistencyCheck: ${consistencyErrors.map(_.render)}")
    } else {
      Right(())
    }
  }
}
