package mill.main

import mill.api.Strict
import mill.define.{InputImpl, NamedTask, Task}
import mill.eval.{CodeSigUtils, Evaluator, Plan, Terminal}
import mill.resolve.{Resolve, SelectMode}

private[mill] object SelectiveExecution {
  case class Signatures(inputHashes: Map[String, Int], methodCodeHashSignatures: Map[String, Int])
  implicit val jsonify: upickle.default.ReadWriter[Signatures] = upickle.default.macroRW

  object Signatures {
    def apply(evaluator: Evaluator, targets: Seq[String]): Signatures = {
      val res = plan0(evaluator, targets).getOrElse(???)
      val inputTasksToLabels: Map[Task[_], String] = res
        .collect {
          case labelled if labelled.task.isInstanceOf[InputImpl[_]] =>
            labelled.task -> labelled.segments.render
        }
        .toMap

      val results = evaluator.evaluate(Strict.Agg.from(inputTasksToLabels.keys))

      new Signatures(
        inputHashes = results
          .results
          .flatMap { case (task, taskResult) =>
            inputTasksToLabels.get(task).map { l =>
              l -> taskResult.result.getOrThrow.value.hashCode
            }
          }
          .toMap,
        methodCodeHashSignatures = evaluator.methodCodeHashSignatures
      )
    }
  }

  def plan0(
      evaluator: Evaluator,
      targets: Seq[String]
  ): Either[String, Array[Terminal.Labelled[_]]] = {
    Resolve.Tasks.resolve(
      evaluator.rootModule,
      targets,
      SelectMode.Multi
    ) match {
      case Left(err) => Left(err)
      case Right(rs) =>
        val (sortedGroups, _) = evaluator.plan(rs)
        Right(sortedGroups.keys().collect { case r: Terminal.Labelled[_] => r }.toArray)
    }
  }

  def computeHashCodeSignatures(
      res: Array[Terminal.Labelled[_]],
      methodCodeHashSignatures: Map[String, Int]
  ): Map[String, Int] = {

    val (sortedGroups, transitive) = Plan.plan(res.map(_.task).toSeq)

    val (classToTransitiveClasses, allTransitiveClassMethods) =
      CodeSigUtils.precomputeMethodNamesPerClass(sortedGroups)

    lazy val constructorHashSignatures = CodeSigUtils
      .constructorHashSignatures(methodCodeHashSignatures)

    transitive
      .collect { case namedTask: NamedTask[_] =>
        namedTask.ctx.segments.render -> CodeSigUtils
          .codeSigForTask(
            namedTask,
            classToTransitiveClasses,
            allTransitiveClassMethods,
            methodCodeHashSignatures,
            constructorHashSignatures
          )
          .sum
      }
      .toMap
  }

  def computeDownstream(
      evaluator: Evaluator,
      targets: Seq[String],
      oldHashes: Signatures,
      newHashes: Signatures
  ): Seq[Task[Any]] = {
    val terminals = SelectiveExecution.plan0(evaluator, targets).getOrElse(???)
    val namesToTasks = terminals.map(t => (t.render -> t.task)).toMap

    def diffMap[K, V](lhs: Map[K, V], rhs: Map[K, V]) = {
      (lhs.keys ++ rhs.keys)
        .iterator
        .distinct
        .filter { k => lhs.get(k) != rhs.get(k) }
        .toSet
    }

    val changedInputNames = diffMap(oldHashes.inputHashes, newHashes.inputHashes)
    val changedCodeNames = diffMap(
      computeHashCodeSignatures(terminals, oldHashes.methodCodeHashSignatures),
      computeHashCodeSignatures(terminals, newHashes.methodCodeHashSignatures)
    )

    val changedRootTasks = (changedInputNames ++ changedCodeNames).map(namesToTasks(_): Task[_])

    val allNodes = breadthFirst(terminals.map(_.task: Task[_]))(_.inputs)
    val downstreamEdgeMap = allNodes
      .flatMap(t => t.inputs.map(_ -> t))
      .groupMap(_._1)(_._2)

    breadthFirst(changedRootTasks)(downstreamEdgeMap.getOrElse(_, Nil))
  }

  def breadthFirst[T](start: IterableOnce[T])(edges: T => IterableOnce[T]): Seq[T] = {
    val seen = collection.mutable.Set.empty[T]
    val seenList = collection.mutable.Buffer.empty[T]
    val queued = collection.mutable.Queue.from(start)

    while (queued.nonEmpty) {
      val current = queued.dequeue()
      seen.add(current)
      seenList.append(current)

      for (next <- edges(current).iterator) {
        if (!seen.contains(next)) queued.enqueue(next)
      }
    }
    seenList.toSeq
  }

}
