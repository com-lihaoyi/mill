package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.{BuildClient, BuildTargetIdentifier}
import mill.api.daemon.internal.EvaluatorApi

import scala.jdk.CollectionConverters.*

/**
 * Handles sending buildTarget/didChange notifications to the BSP client
 * when build targets are created, modified, or deleted.
 */
private[worker] object ChangeNotifier {

  /**
   * Computes the difference between previous and current build targets and sends
   * appropriate change notifications to the client.
   *
   * @param client The BSP client to notify
   * @param previousTargetIds Previous build target IDs with their evaluators
   * @param newTargetIds Current build target IDs with their evaluators
   */
  def notifyChanges(
      client: BuildClient,
      previousTargetIds: Seq[(BuildTargetIdentifier, EvaluatorApi)],
      newTargetIds: Seq[(BuildTargetIdentifier, EvaluatorApi)]
  ): Unit = {
    val createdAndModifiedEvents = computeCreatedAndModified(previousTargetIds, newTargetIds)
    val deletedEvents = computeDeleted(previousTargetIds, newTargetIds)
    val allEvents = deletedEvents ++ createdAndModifiedEvents

    if (allEvents.nonEmpty)
      client.onBuildTargetDidChange(new bsp4j.DidChangeBuildTarget(allEvents.asJava))
  }

  private def computeCreatedAndModified(
      previousTargetIds: Seq[(BuildTargetIdentifier, EvaluatorApi)],
      newTargetIds: Seq[(BuildTargetIdentifier, EvaluatorApi)]
  ): Seq[bsp4j.BuildTargetEvent] = {
    val previousTargetIdsMap = previousTargetIds.toMap
    newTargetIds.flatMap { case (id, ev) =>
      previousTargetIdsMap.get(id) match {
        case None =>
          val event = new bsp4j.BuildTargetEvent(id)
          event.setKind(bsp4j.BuildTargetEventKind.CREATED)
          Seq(event)
        case Some(prevEv) if prevEv != ev =>
          val event = new bsp4j.BuildTargetEvent(id)
          event.setKind(bsp4j.BuildTargetEventKind.CHANGED)
          Seq(event)
        case Some(_) =>
          Nil
      }
    }
  }

  private def computeDeleted(
      previousTargetIds: Seq[(BuildTargetIdentifier, EvaluatorApi)],
      newTargetIds: Seq[(BuildTargetIdentifier, EvaluatorApi)]
  ): Seq[bsp4j.BuildTargetEvent] = {
    val newTargetIdsMap = newTargetIds.toMap
    previousTargetIds.collect {
      case (id, _) if !newTargetIdsMap.contains(id) =>
        val event = new bsp4j.BuildTargetEvent(id)
        event.setKind(bsp4j.BuildTargetEventKind.DELETED)
        event
    }
  }
}
