package mill.eval

import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference

import ammonite.ops._
import ammonite.runtime.SpecialClassLoader
import mill.define._
import mill.discover.{Discovered, Mirror}
import mill.discover.Mirror.Segment
import mill.util
import mill.util._

import scala.collection.mutable
case class Labelled[T](target: NamedTask[T],
                       segments: Seq[Segment]){
  def format = target match{
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
  def writer = target match{
    case t: mill.define.Command[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
}
class Evaluator[T](val workspacePath: Path,
                   val mapping: Discovered.Mapping[T],
                   log: Logger,
                   val classLoaderSig: Seq[(Path, Long)] = Evaluator.classLoaderSig){



  val workerCache = mutable.Map.empty[Ctx.Loader[_], Any]
  workerCache(Discovered.Mapping) = mapping
  def evaluate(goals: OSet[Task[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
      case t: NamedTask[Any] if mapping.modules.contains(t.owner)  =>
        val segments = mapping.modules(t.owner) :+ Segment.Label(t.name)
        val finalTaskOverrides = t match{
          case t: Target[_] => mapping.segmentsToTargets(segments).overrides
          case c: mill.define.Command[_] => mapping.segmentsToCommands(segments).overrides
        }
        val delta = finalTaskOverrides - t.overrides
        val additional =
          if (delta == 0) Nil
          else Seq(Segment.Label("override" + delta))

        Right(Labelled(t, segments ++ additional))
      case t if goals.contains(t) => Left(t)
    }

    val evaluated = new OSet.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    for ((terminal, group)<- sortedGroups.items()){
      val (newResults, newEvaluated) = evaluateGroupCached(terminal, group, results)
      for(ev <- newEvaluated){
        evaluated.append(ev)
      }
      for((k, v) <- newResults) results.put(k, v)

    }

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(k, vs.items.flatMap(results.get).collect{case f: Result.Failing => f})
    }
    Evaluator.Results(goals.indexed.map(results), evaluated, transitive, failing)
  }


  def evaluateGroupCached(terminal: Either[Task[_], Labelled[_]],
                          group: OSet[Task[_]],
                          results: collection.Map[Task[_], Result[Any]]): (collection.Map[Task[_], Result[Any]], Seq[Task[_]]) = {


    val externalInputs = group.items.flatMap(_.inputs).filter(!group.contains(_))

    val inputsHash =
      externalInputs.map(results).toVector.hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode() +
      classLoaderSig.hashCode()

    terminal match{
      case Left(task) =>
        evaluateGroup(group, results, paths = None, maybeTargetLabel = None)
      case Right(labelledTarget) =>
        val paths = Evaluator.resolveDestPaths(workspacePath, labelledTarget)
        mkdir(paths.base)
        val cached = for{
          json <- scala.util.Try(upickle.json.read(read(paths.meta))).toOption
          (cachedHash, terminalResult) <- scala.util.Try(upickle.default.readJs[(Int, upickle.Js.Value)](json)).toOption
          if cachedHash == inputsHash
          reader <- labelledTarget.format
          parsed <- reader.read.lift(terminalResult)
        } yield parsed

        cached match{
          case Some(parsed) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]
            newResults(labelledTarget.target) = parsed
            (newResults, Nil)

          case _ =>

            val Seq(first, rest @_*) = labelledTarget.segments
            val msgParts = Seq(first.asInstanceOf[Mirror.Segment.Label].value) ++ rest.map{
              case Mirror.Segment.Label(s) => "." + s
              case Mirror.Segment.Cross(s) => "[" + s.mkString(",") + "]"
            }

            if (labelledTarget.target.flushDest) rm(paths.dest)
            val (newResults, newEvaluated) = evaluateGroup(
              group,
              results,
              Some(paths),
              maybeTargetLabel = Some(msgParts.mkString))

            newResults(labelledTarget.target) match{
              case Result.Success(v) =>
                val terminalResult = labelledTarget
                  .writer
                  .asInstanceOf[Option[upickle.default.Writer[Any]]]
                  .map(_.write(v))

                for(t <- terminalResult){
                  write.over(paths.meta, upickle.default.write(inputsHash -> t, indent = 4))
                }
              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                rm(paths.meta)
            }



            (newResults, newEvaluated)
        }
    }
  }


  def evaluateGroup(group: OSet[Task[_]],
                    results: collection.Map[Task[_], Result[Any]],
                    paths: Option[Evaluator.Paths],
                    maybeTargetLabel: Option[String]) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if(logRun) { log.info("Running " + targetLabel) }
    }

    val multiLogger = resolveLogger(paths.map(_.log))

    lazy val lastTaskRef = new AtomicReference[Task[_]](null)
    for (target <- nonEvaluatedTargets) {

      newEvaluated.append(target)
      val targetInputValues = target.inputs
        .map(x => newResults.getOrElse(x, results(x)))
        .collect{ case Result.Success(v) => v }

      val res =
        if (targetInputValues.length != target.inputs.length) Result.Skipped
        else {
          val args = new Ctx(
            targetInputValues.toArray[Any],
            paths.map(_.dest).orNull,
            multiLogger,
            new Ctx.LoaderCtx{
              def load[T](x: Ctx.Loader[T]): T = {
                workerCache.getOrElseUpdate(x, x.make()).asInstanceOf[T]
              }
            }
          ){
            override def dest: Path = {
              lastTaskRef.get() match {
                case null =>
                  lastTaskRef.set(target)
                  super.dest
                case lastTask if lastTask == target =>
                  super.dest
                case lastTask =>
                  throw new IllegalAccessException(s"The dest should only be used by the same task,currently it's used by [$lastTask],your task [$target] should not used it at the sametime.")
              }
            }
          }

          val out = System.out
          val err = System.err
          try{
            System.setErr(multiLogger.outputStream)
            System.setOut(multiLogger.outputStream)
            Console.withOut(multiLogger.outputStream){
              Console.withErr(multiLogger.outputStream){
                target.evaluate(args)
              }
            }
          }finally{
            System.setErr(err)
            System.setOut(out)
          }
        }

      newResults(target) = res
    }

    multiLogger.close()

    (newResults, newEvaluated)
  }

  def resolveLogger(logPath: Option[Path]): Logger = logPath match{
    case None => log
    case Some(path) =>
      rm(path)
      MultiLogger(log, FileLogger(path))
  }
}


object Evaluator{
  case class Paths(base: Path, dest: Path, meta: Path, log: Path)
  def resolveDestPaths(workspacePath: Path, t: Labelled[_]): Paths = {
    resolveDestPaths(workspacePath, t.segments)
  }
  def resolveDestPaths(workspacePath: Path, segments: Seq[Segment]): Paths = {
    val segmentStrings = segments.flatMap{
      case Mirror.Segment.Label(s) => Seq(s)
      case Mirror.Segment.Cross(values) => values.map(_.toString)
    }
    val targetPath = workspacePath / segmentStrings
    Paths(targetPath, targetPath / 'dest, targetPath / "meta.json", targetPath / 'log)
  }

  // check if the build itself has changed
  def classLoaderSig = Thread.currentThread().getContextClassLoader match {
    case scl: SpecialClassLoader => scl.classpathSignature
    case ucl: URLClassLoader => SpecialClassLoader.initialClasspathSignature(ucl)
    case _ => Nil

  }
  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: OSet[Task[_]],
                     transitive: OSet[Task[_]],
                     failing: MultiBiMap[Either[Task[_], Labelled[_]], Result.Failing]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }
}
