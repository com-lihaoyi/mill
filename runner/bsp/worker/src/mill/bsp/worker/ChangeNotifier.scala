package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.{BuildClient, BuildTargetIdentifier}
import mill.api.daemon.internal.bsp.BspBuildTarget

import scala.jdk.CollectionConverters.*

/**
 * Handles sending buildTarget/didChange notifications to the BSP client
 * when build targets are created, modified, or deleted.
 */
private[worker] object ChangeNotifier {
  case class TargetSnapshot(
      id: BuildTargetIdentifier,
      buildTarget: BspBuildTarget,
      dependencyUris: Seq[String],
      classLoader: ClassLoader
  ) {
    def differsFrom(other: TargetSnapshot): Boolean =
      buildTarget != other.buildTarget ||
        dependencyUris != other.dependencyUris ||
        (classLoader ne other.classLoader)
  }

  /**
   * Computes the difference between previous and current build targets and sends
   * appropriate change notifications to the client.
   *
   * @param client The BSP client to notify
   * @param previousTargets Previous build target snapshots
   * @param newTargets Current build target snapshots
   */
  def notifyChanges(
      client: BuildClient,
      previousTargets: Seq[TargetSnapshot],
      newTargets: Seq[TargetSnapshot],
      forceMillBuildChanged: Boolean
  ): Unit = {
    val createdAndModifiedEvents = computeCreatedAndModified(previousTargets, newTargets)
    val deletedEvents = computeDeleted(previousTargets, newTargets)
    val millBuildEvent = computeMillBuildChanged(
      previousTargets,
      newTargets,
      deletedEvents ++ createdAndModifiedEvents,
      forceMillBuildChanged
    )
    val allEvents = deletedEvents ++ createdAndModifiedEvents ++ millBuildEvent

    if (allEvents.nonEmpty)
      client.onBuildTargetDidChange(new bsp4j.DidChangeBuildTarget(allEvents.asJava))
  }

  private def computeCreatedAndModified(
      previousTargets: Seq[TargetSnapshot],
      newTargets: Seq[TargetSnapshot]
  ): Seq[bsp4j.BuildTargetEvent] = {
    val previousTargetsByUri = previousTargets.iterator.map(t => t.id.getUri -> t).toMap
    newTargets.flatMap { target =>
      previousTargetsByUri.get(target.id.getUri) match {
        case None =>
          val event = new bsp4j.BuildTargetEvent(target.id)
          event.setKind(bsp4j.BuildTargetEventKind.CREATED)
          Seq(event)
        case Some(previous) if target.differsFrom(previous) =>
          val event = new bsp4j.BuildTargetEvent(target.id)
          event.setKind(bsp4j.BuildTargetEventKind.CHANGED)
          Seq(event)
        case Some(_) =>
          Nil
      }
    }
  }

  private def computeDeleted(
      previousTargets: Seq[TargetSnapshot],
      newTargets: Seq[TargetSnapshot]
  ): Seq[bsp4j.BuildTargetEvent] = {
    val newTargetUris = newTargets.iterator.map(_.id.getUri).toSet
    previousTargets.collect {
      case target if !newTargetUris.contains(target.id.getUri) =>
        val event = new bsp4j.BuildTargetEvent(target.id)
        event.setKind(bsp4j.BuildTargetEventKind.DELETED)
        event
    }
  }

  private def computeMillBuildChanged(
      previousTargets: Seq[TargetSnapshot],
      newTargets: Seq[TargetSnapshot],
      otherEvents: Seq[bsp4j.BuildTargetEvent],
      forceMillBuildChanged: Boolean
  ): Seq[bsp4j.BuildTargetEvent] = {
    def isMillBuild(id: BuildTargetIdentifier) = id.getUri.endsWith("/mill-build")

    if (
      (forceMillBuildChanged || otherEvents.nonEmpty) &&
      !otherEvents.exists(event => isMillBuild(event.getTarget)) &&
      previousTargets.exists(target => isMillBuild(target.id)) &&
      newTargets.exists(target => isMillBuild(target.id))
    ) {
      val event = new bsp4j.BuildTargetEvent(newTargets.collectFirst {
        case target if isMillBuild(target.id) => target.id
      }.get)
      event.setKind(bsp4j.BuildTargetEventKind.CHANGED)
      Seq(event)
    } else Nil
  }
}
