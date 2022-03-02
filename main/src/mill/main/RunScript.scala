package mill.main

import java.nio.file.NoSuchFileException
import ammonite.runtime.SpecialClassLoader
import ammonite.util.Util.CodeSource
import ammonite.util.{Name, Res, ScriptOutput, Util}
import mill.define
import mill.define._
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util.{EitherOps, PrintLogger, Watched}
import mill.define.SelectMode
import mill.define.ParseArgs
import mill.api.{Logger, PathRef, Result}
import mill.api.Strict.Agg
import mill.internal.AmmoniteUtils

import scala.collection.mutable
import scala.reflect.ClassTag
import mill.define.ParseArgs.TargetsWithParams

/**
 * Custom version of ammonite.main.Scripts, letting us run the build.sc script
 * directly without going through Ammonite's main-method/argument-parsing
 * subsystem
 */
object RunScript {
  def runScript(
      home: os.Path,
      wd: os.Path,
      path: os.Path,
      instantiateInterpreter: => Either[
        (Res.Failing, Seq[(ammonite.interp.Watchable, Long)]),
        ammonite.interp.Interpreter
      ],
      scriptArgs: Seq[String],
      stateCache: Option[EvaluatorState],
      log: PrintLogger,
      env: Map[String, String],
      keepGoing: Boolean,
      systemProperties: Map[String, String],
      threadCount: Option[Int],
      initialSystemProperties: Map[String, String]
  ): (
      Res[(Evaluator, Seq[PathRef], Either[String, Seq[ujson.Value]])],
      Seq[(ammonite.interp.Watchable, Long)]
  ) = {

    for ((k, v) <- systemProperties) System.setProperty(k, v)
    val systemPropertiesToUnset =
      stateCache.map(_.setSystemProperties).getOrElse(Set()) -- systemProperties.keySet

    for (k <- systemPropertiesToUnset) {
      initialSystemProperties.get(k) match {
        case None => System.clearProperty(k)
        case Some(original) => System.setProperty(k, original)
      }
    }

    val (evalState, interpWatched) = stateCache match {
      case Some(s) if watchedSigUnchanged(s.watched) => Res.Success(s) -> s.watched
      case _ =>
        instantiateInterpreter match {
          case Left((res, watched)) => (res, watched)
          case Right(interp) =>
            interp.watch(path)
            val eval =
              for (rootModule <- evaluateRootModule(wd, path, interp, log))
                yield {
                  EvaluatorState(
                    rootModule,
                    rootModule.getClass.getClassLoader.asInstanceOf[
                      SpecialClassLoader
                    ].classpathSignature,
                    mutable.Map.empty[Segments, (Int, Any)],
                    interp.watchedValues.toSeq,
                    systemProperties.keySet,
                    importTree(interp.alreadyLoadedFiles)
                  )
                }
            (eval, interp.watchedValues)
        }
    }

    val evalRes =
      for (s <- evalState)
        yield Evaluator(
          home,
          wd / "out",
          wd / "out",
          s.rootModule,
          log
        ).withClassLoaderSig(s.classLoaderSig)
          .withWorkerCache(s.workerCache)
          .withEnv(env)
          .withFailFast(!keepGoing)
          .withThreadCount(threadCount)
          .withImportTree(s.importTree)

    val evaluated = for {
      evaluator <- evalRes
      (evalWatches, res) <- Res(evaluateTasks(evaluator, scriptArgs, SelectMode.Separated))
    } yield {
      (evaluator, evalWatches, res.map(_.flatMap(_._2)))
    }
    (evaluated, interpWatched.toSeq)
  }

  def watchedSigUnchanged(sig: Seq[(ammonite.interp.Watchable, Long)]) = {
    sig.forall { case (p, l) => p.poll() == l }
  }

  private def importTree(alreadyLoadedFiles: collection.Map[CodeSource, ScriptOutput.Metadata])
      : Seq[ScriptNode] = {
    val importTreeMap = mutable.Map.empty[String, Seq[String]]
    alreadyLoadedFiles.foreach { case (a, b) =>
      val filePath = AmmoniteUtils.normalizeAmmoniteImportPath(a.filePathPrefix)
      val importPaths = b.blockInfo.flatMap { b =>
        val relativePath = b.hookInfo.trees.map { t =>
          val prefix = t.prefix
          val mappings = t.mappings.toSeq.flatMap(_.map(_._1))
          prefix ++ mappings
        }
        relativePath.collect {
          case "$file" :: tail =>
            val concatenated = filePath.init ++ tail
            AmmoniteUtils.normalizeAmmoniteImportPath(concatenated)
        }
      }
      def toCls(segments: Seq[String]): String = segments.mkString(".")
      val key = toCls(filePath)
      val toAppend = importPaths.map(toCls)
      importTreeMap(key) = importTreeMap.getOrElse(key, Seq.empty) ++ toAppend
    }

    GraphUtils.linksToScriptNodeGraph(importTreeMap)
  }

  def evaluateRootModule(
      wd: os.Path,
      path: os.Path,
      interp: ammonite.interp.Interpreter,
      log: Logger
  ): Res[mill.define.BaseModule] = {

    val (pkg, wrapper) = Util.pathToPackageWrapper(Seq(), path relativeTo wd)

    for {
      scriptTxt <-
        try Res.Success(Util.normalizeNewlines(os.read(path)))
        catch {
          case _: NoSuchFileException =>
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

      module <-
        try {
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
    } yield module
  }

  def resolveTasks[T, R: ClassTag](
      resolver: mill.main.Resolve[R],
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, List[R]] = {
    val parsedGroups: Either[String, Seq[TargetsWithParams]] = ParseArgs(scriptArgs, selectMode)
    val resolvedGroups = parsedGroups.flatMap { groups =>
      val resolved = groups.map { parsed: TargetsWithParams =>
        resolveTasks(resolver, evaluator, Right(parsed))
      }
      EitherOps.sequence(resolved)
    }
    resolvedGroups.map(_.flatten.toList)
  }

  @deprecated(
    "Use resolveTasks[T, R](Resolve[R], Evaluator, Seq[String], SelectMode) instead",
    "mill after 0.10.0-M3"
  )
  def resolveTasks[T, R: ClassTag](
      resolver: mill.main.Resolve[R],
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      multiSelect: Boolean
  ): Either[String, List[R]] = {
    val parsed: Either[String, TargetsWithParams] = ParseArgs(scriptArgs, multiSelect = multiSelect)
    resolveTasks(resolver, evaluator, parsed)
  }

  private def resolveTasks[T, R: ClassTag](
      resolver: mill.main.Resolve[R],
      evaluator: Evaluator,
      targetsWithParams: Either[String, TargetsWithParams]
  ): Either[String, List[R]] = {
    for {
      parsed <- targetsWithParams
      (selectors, args) = parsed
      taskss <- {
        val selected = selectors.map { case (scopedSel, sel) =>
          for (res <- prepareResolve(evaluator, scopedSel, sel))
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
                  sel.value.toList,
                  rootModule,
                  rootModule.millDiscover,
                  args,
                  crossSelectors.toList
                )
              } finally {
                mill.eval.Evaluator.currentEvaluator.set(null)
              }
            }
        }
        EitherOps.sequence(selected).map(_.toList)
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
            catch {
              case e: ClassNotFoundException =>
                Left("Cannot resolve external module " + scoping.render)
            }
          rootModule <- moduleCls.getField("MODULE$").get(moduleCls) match {
            case rootModule: ExternalModule => Right(rootModule)
            case _ => Left("Class " + scoping.render + " is not an external module")
          }
        } yield rootModule
    }
  }

  def prepareResolve[T](
      evaluator: Evaluator,
      scopedSel: Option[Segments],
      sel: Segments
  ): Either[String, (BaseModule, Seq[List[String]])] = {
    for (rootModule <- resolveRootModule(evaluator, scopedSel))
      yield {
        val crossSelectors = sel.value.map {
          case Segment.Cross(x) => x.toList.map(_.toString)
          case _ => Nil
        }
        (rootModule, crossSelectors)
      }
  }

  @deprecated(
    "Use evaluateTasks[T](Evaluator, Seq[String], SelectMode) instead",
    "mill after 0.10.0-M3"
  )
  def evaluateTasks[T](
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      multiSelect: Boolean
  ): Either[String, (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]])] =
    evaluateTasks(evaluator, scriptArgs, if (multiSelect) SelectMode.Multi else SelectMode.Single)

  def evaluateTasks[T](
      evaluator: Evaluator,
      scriptArgs: Seq[String],
      selectMode: SelectMode
  ): Either[String, (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]])] = {
    for (targets <- resolveTasks(mill.main.ResolveTasks, evaluator, scriptArgs, selectMode))
      yield {
        val (watched, res) = evaluate(evaluator, Agg.from(targets.distinct))

        val watched2 = for {
          x <- res.toSeq
          (Watched(_, extraWatched), _) <- x
          w <- extraWatched
        } yield w

        (watched ++ watched2, res)
      }
  }

  def evaluate(
      evaluator: Evaluator,
      targets: Agg[Task[Any]]
  ): (Seq[PathRef], Either[String, Seq[(Any, Option[ujson.Value])]]) = {
    val evaluated = evaluator.evaluate(targets)
    val watched = evaluated.results
      .iterator
      .collect {
        case (t: define.Sources, Result.Success(ps: Seq[PathRef])) => ps
        case (t: define.Source, Result.Success(p: PathRef)) => Seq(p)
      }
      .flatten
      .toSeq

    val errorStr = Evaluator.formatFailing(evaluated)

    evaluated.failing.keyCount match {
      case 0 =>
        val json = for (t <- targets.toSeq) yield {
          t match {
            case t: mill.define.NamedTask[_] =>
              val jsonFile = EvaluatorPaths.resolveDestPaths(evaluator.outPath, t).meta
              val metadata = upickle.default.read[Evaluator.Cached](ujson.read(jsonFile.toIO))
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
