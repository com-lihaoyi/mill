package mill.testrunner

import java.nio.channels.FileLock
import os.Path
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.MappedByteBuffer
import scala.annotation.switch

/**
 * TestClusterSignals is used to communicate between process and cluster server, and between processes
 * For each process, it will take 8 bytes in the mmap buffer:
 * - first 4 bytes: used to communicate between process and cluster server
 * - last 4 bytes: used to communicate between process and the current stealer
 * To implement the work stealing scheduler between processes without using any system lock, we need to
 * have a clear protocol that no 2 entity can transition from same one state.
 */
sealed abstract class TestClusterSignals {

  def getClusterState(processIndex: Int): TestClusterSignals.ClusterState
  def writeClusterState(processIndex: Int, clusterState: TestClusterSignals.ClusterState): Unit

  def getStealerState(processIndex: Int): TestClusterSignals.StealerState
  def writeStealerState(processIndex: Int, stealerState: TestClusterSignals.StealerState): Unit

  def createStealingFile(processIndex: Int): Unit
  def lockStealingFile(processIndex: Int): FileLock
  def tryLockStealingFile(processIndex: Int): Option[FileLock]
  def getStealingFile(processIndex: Int): Path
  def removeStealingFile(processIndex: Int): Unit

  def cleanup(): Unit

}

object TestClusterSignals {
  final val MaxTestProcessCount = 512

  // signal used to communicate between process and cluster server,
  // process X it will be at position X * 4 of the mmap buffer
  sealed trait ClusterState { self =>
    def toInt: Int = self match {
      case ClusterState.Running => 0
      case ClusterState.Blocking => 1
      case ClusterState.Stealing(victimProcessId) => 2 | (victimProcessId << 8)
      case ClusterState.Stop => 3
      case ClusterState.RequestingStealPermission => 4
      case ClusterState.StealPermissionApproved(victimProcessId) => 5 | (victimProcessId << 8)
      case ClusterState.StealPermissionDenied => 6
      case ClusterState.Unrecognize => 0xffffffff
    }
  }

  object ClusterState {
    def unapply(state: Int): Some[ClusterState] = {
      ((state & 0xff): @switch) match {
        case 0 => Some(Running)
        case 1 => Some(Blocking)
        case 2 => Some(Stealing((state >> 8) & 0xffffff))
        case 3 => Some(Stop)
        case 4 => Some(RequestingStealPermission)
        case 5 => Some(StealPermissionApproved((state >> 8) & 0xffffff))
        case 6 => Some(StealPermissionDenied)
        case _ => Some(Unrecognize)
      }
    }
    // process is running normally, only self can set
    case object Running extends ClusterState
    // process is blocked by big work, only self can set
    case object Blocking extends ClusterState
    // process is stealing work, only self can set
    final case class Stealing(victimProcessId: Int) extends ClusterState
    // process is stopping, only self can set
    case object Stop extends ClusterState
    // process is requesting steal permission, only self can set
    case object RequestingStealPermission extends ClusterState
    // steal permission approved for victim, only cluster can set
    final case class StealPermissionApproved(victimProcessId: Int) extends ClusterState
    // steal permission denied, only cluster can set
    case object StealPermissionDenied extends ClusterState
    // unrecognize
    case object Unrecognize extends ClusterState
  }

  sealed trait StealerState { self =>
    def toInt: Int = self match {
      case StealerState.Empty => 0
      case StealerState.RequestStealAttempt(stealerProcessId) => 1 | (stealerProcessId << 8)
      case StealerState.StealAttemptApproved(stealerProcessId) => 2 | (stealerProcessId << 8)
      case StealerState.StealAttemptDenied(stealerProcessId) => 3 | (stealerProcessId << 8)
      case StealerState.StealerAcknowledged => 4
      case StealerState.Unrecognize => 0xffffffff
    }
  }

  object StealerState {
    def unapply(state: Int): Some[StealerState] = {
      ((state & 0xff): @switch) match {
        case 0 => Some(Empty)
        case 1 => Some(RequestStealAttempt((state >> 8) & 0xffffff))
        case 2 => Some(StealAttemptApproved((state >> 8) & 0xffffff))
        case 3 => Some(StealAttemptDenied((state >> 8) & 0xffffff))
        case 4 => Some(StealerAcknowledged)
        case _ => Some(Unrecognize)
      }
    }
    // no stealer signal, only self can set
    case object Empty extends StealerState
    // stealer requested to steal our work, only stealer can set
    final case class RequestStealAttempt(stealerProcessId: Int) extends StealerState
    // we approve the steal, only self can set
    final case class StealAttemptApproved(stealerProcessId: Int) extends StealerState
    // we deny the steal, only self can set
    final case class StealAttemptDenied(stealerProcessId: Int) extends StealerState
    // stealer acknowledged the response, only stealer can set
    case object StealerAcknowledged extends StealerState
    // unrecognize
    case object Unrecognize extends StealerState
  }

  def apply(
      base: os.Path,
      isClusterServer: Boolean = false
  ): TestClusterSignals = {

    val cluster = base / "cluster"
    val signalFile: Path = cluster / "main.ipc"

    if (isClusterServer) {
      os.makeDir.all(cluster)
      os.write.over(
        signalFile,
        Array.fill(TestClusterSignals.MaxTestProcessCount * 8)(0.toByte),
        createFolders = true
      )
    }

    new TestClusterSignals {

      private val buffer: MappedByteBuffer = {
        val raf = new RandomAccessFile(signalFile.toNIO.toFile(), "rw")
        raf.getChannel.map(FileChannel.MapMode.READ_WRITE, 0, MaxTestProcessCount * 8)
      }

      override def getClusterState(processIndex: Int): ClusterState = {
        val ClusterState(state) = buffer.getInt(processIndex * 8)
        state
      }

      override def writeClusterState(processIndex: Int, clusterState: ClusterState): Unit = {
        buffer.putInt(processIndex * 8, clusterState.toInt)
      }

      override def getStealerState(processIndex: Int): StealerState = {
        val StealerState(state) = buffer.getInt(processIndex * 8 + 4)
        state
      }

      override def writeStealerState(processIndex: Int, stealerState: StealerState): Unit = {
        buffer.putInt(processIndex * 8 + 4, stealerState.toInt)
      }

      override def createStealingFile(processIndex: Int): Unit = {
        val stealingFile = cluster / s"stealing-$processIndex.dat"
        val stealingLockFile = cluster / s"stealing-$processIndex.lock"
        os.write.over(stealingFile, Array.emptyByteArray, createFolders = true)
        os.write.over(stealingLockFile, Array.emptyByteArray, createFolders = true)
      }

      override def lockStealingFile(processIndex: Int): FileLock = {
        val stealingLockFile = cluster / s"stealing-$processIndex.lock"
        val fileChannel = FileChannel.open(
          stealingLockFile.toNIO,
          java.nio.file.StandardOpenOption.READ,
          java.nio.file.StandardOpenOption.WRITE
        )
        fileChannel.lock()
      }

      override def tryLockStealingFile(processIndex: Int): Option[FileLock] = {
        val stealingLockFile = cluster / s"stealing-$processIndex.lock"
        val fileChannel = FileChannel.open(
          stealingLockFile.toNIO,
          java.nio.file.StandardOpenOption.READ,
          java.nio.file.StandardOpenOption.WRITE
        )
        Option(fileChannel.tryLock())
      }

      override def getStealingFile(processIndex: Int): Path = {
        val stealingFile = cluster / s"stealing-$processIndex.dat"
        stealingFile
      }

      override def removeStealingFile(processIndex: Int): Unit = {
        val stealingFile = cluster / s"stealing-$processIndex.dat"
        val stealingLockFile = cluster / s"stealing-$processIndex.lock"
        os.remove(stealingFile, checkExists = false)
        os.remove(stealingLockFile, checkExists = false)
      }

      override def cleanup(): Unit = {
        if (isClusterServer) {
          os.remove.all(cluster, ignoreErrors = true)
        }
      }
    }
  }
}
