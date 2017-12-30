package mill.main

import java.nio.file.NoSuchFileException

import ammonite.interp.Interpreter
import ammonite.ops.{Path, pwd, read}
import ammonite.util.Util.CodeSource
import ammonite.util.{Name, Res, Util}
import mill.define
import mill.define.Task
import mill.discover.Mirror.Segment
import mill.discover.{Discovered, Mirror}
import mill.eval.{Evaluator, Result}
import mill.util.{OSet, PrintLogger}
import upickle.Js

import scala.collection.mutable

/**
  * Custom version of ammonite.main.Scripts, letting us run the build.sc script
  * directly without going through Ammonite's main-method/argument-parsing
  * subsystem
  */
object RunScript{

  def runScript(wd: Path,
                path: Path,
                interp: ammonite.interp.Interpreter,
                scriptArgs: Seq[String],
                lastEvaluator: Option[(Seq[(Path, Long)], Evaluator[_])])
  : Res[(Evaluator[_], Seq[(Path, Long)], Seq[(Any, Option[Js.Value])])] = {

    val log = new PrintLogger(true)
    for{
      evaluator <- lastEvaluator match{
        case Some((prevInterpWatchedSig, prevEvaluator))
          if watchedSigUnchanged(prevInterpWatchedSig) =>
          Res.Success(prevEvaluator)

        case _ =>
          interp.watch(path)
          for(mapping <- evaluateMapping(wd, path, interp))
          yield new Evaluator(pwd / 'out, mapping, log)
      }
      evaluationWatches = mutable.Buffer.empty[(Path, Long)]
      res <- Res(evaluateTarget(
        evaluator,
        scriptArgs,
        p => evaluationWatches.append((p, Interpreter.pathSignature(p)))
      ))
    } yield (evaluator, evaluationWatches, res)
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
                        scriptArgs: Seq[String],
                        watch: Path => Unit) = {

    val Seq(selectorString, rest @_*) = scriptArgs
    for {
      sel <- parseArgs(selectorString)
      crossSelectors = sel.map{
        case Mirror.Segment.Cross(x) => x.toList.map(_.toString)
        case _ => Nil
      }
      target <- mill.main.Resolve.resolve(
        sel, evaluator.mapping.mirror, evaluator.mapping.base,
        rest, crossSelectors, Nil
      )
      res <- evaluate(evaluator, target, watch)
    } yield res
  }
  def evaluate(evaluator: Evaluator[_],
               target: Task[Any],
               watch: Path => Unit): Either[String, Seq[(Any, Option[upickle.Js.Value])]] = {
    val evaluated = evaluator.evaluate(OSet(target))
    evaluated.transitive.foreach {
      case t: define.Source => watch(t.handle.path)
      case _ => // do nothing
    }

    val errorStr =
      (for((k, fs) <- evaluated.failing.items()) yield {
        val ks = k match{
          case Left(t) => t.toString
          case Right(t) => Mirror.renderSelector(t.segments.toList)
        }
        val fss = fs.map{
          case Result.Exception(t) => t.toString
          case Result.Failure(t) => t
        }
        s"$ks ${fss.mkString(", ")}"
      }).mkString("\n")

    evaluated.failing.keyCount match {
      case 0 =>
        val json = for(t <- Seq(target)) yield {
          t match {
            case t: mill.define.Target[_] =>
              for (segments <- evaluator.mapping.modules.get(t.owner)) yield {
                val jsonFile = Evaluator.resolveDestPaths(evaluator.workspacePath, segments :+ Segment.Label(t.name)).meta
                val metadata = upickle.json.read(jsonFile.toIO)
                metadata(1)
              }
            case _ => None
          }
        }

        Right(evaluated.values.zip(json))
      case n => Left(s"$n targets failed\n$errorStr")
    }
  }

  def parseSelector(input: String) = {
    import fastparse.all._
    val segment = P( CharsWhileIn(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).! ).map(
      Mirror.Segment.Label
    )
    val crossSegment = P( "[" ~ CharsWhile(c => c != ',' && c != ']').!.rep(1, sep=",") ~ "]" ).map(
      Mirror.Segment.Cross
    )
    val query = P( segment ~ ("." ~ segment | crossSegment).rep ~ End ).map{
      case (h, rest) => h :: rest.toList
    }
    query.parse(input)
  }



  def parseArgs(selectorString: String): Either[String, List[Mirror.Segment]] = {
    import fastparse.all.Parsed
    if (selectorString.isEmpty) Left("Selector cannot be empty")
    else parseSelector(selectorString) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Right(selector)
    }
  }


  def consistencyCheck[T](mapping: Discovered.Mapping[T]): Either[String, Unit] = {
    val consistencyErrors = Discovered.consistencyCheck(mapping)
    if (consistencyErrors.nonEmpty) {
      Left(s"Failed Discovered.consistencyCheck: ${consistencyErrors.map(Mirror.renderSelector)}")
    } else {
      Right(())
    }
  }
}