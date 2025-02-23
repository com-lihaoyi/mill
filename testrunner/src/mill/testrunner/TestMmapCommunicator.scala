package mill.testrunner

import java.nio.channels.FileChannel
import java.io.RandomAccessFile
import scala.util.Try
import scala.util.Using

private[mill] sealed abstract class TestMmapCommunicator extends AutoCloseable {

  def readSignal(): Int

  def writeSignal(signal: Int): Unit

  def readIndex(index: Int): Int

  def writeIndex(index: Int, value: Int): Unit

  def readAll(): Array[Int]

  def close(): Unit

}

private[mill] object TestMmapCommunicator {

  /**
   * first 4 bytes: communicate signal
   * next 4092 bytes: thread specific communication slots (4 bytes each)
   *
   * This mean that we can give back thread at most 1023 times to the parent process.
   * Other threads will be given back when test process terminates.
   */
  final val BufferSize = 4096

  private[testrunner] val emptyCommunicator = new TestMmapCommunicator {
    override def readSignal(): Int = -1
    override def writeSignal(signal: Int): Unit = ()
    override def readIndex(index: Int): Int = -1
    override def writeIndex(index: Int, value: Int): Unit = ()
    override def readAll(): Array[Int] = Array.empty
    override def close(): Unit = ()
  }

  def using[A](filename: String)(body: TestMmapCommunicator => A): A = {
    val communicator = Try {
      val file = new RandomAccessFile(filename, "rw")
      val channel = file.getChannel
      val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, BufferSize)

      new TestMmapCommunicator {
        override def readSignal(): Int = this.synchronized { buffer.getInt(0) }
        override def writeSignal(signal: Int): Unit = this.synchronized { buffer.putInt(0, signal) }
        override def readIndex(index: Int): Int = if (index < 0 || index >= 1023) {
          -1
        } else {
          this.synchronized { buffer.getInt(4 + index * 4) }
        }
        override def writeIndex(index: Int, value: Int): Unit = if (index < 0 || index >= 1023) {
          ()
        } else {
          this.synchronized { buffer.putInt(4 + index * 4, value) }
        }
        override def readAll(): Array[Int] = {
          val length = (BufferSize >> 2) - 1
          val array = new Array[Int](length)
          this.synchronized {
            for (i <- 0 until length) {
              array(i) = buffer.getInt(4 + i * 4)
            }
          }
          array
        }
        override def close(): Unit = {
          channel.close()
          file.close()
        }
      }
    }.getOrElse(emptyCommunicator)

    Using.resource(communicator)(body)
  }
}
