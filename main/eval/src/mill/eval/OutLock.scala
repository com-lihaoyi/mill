package mill.eval

import mill.api.SystemStreams
import mill.main.client.OutFiles
import mill.main.client.lock.Lock

import scala.util.Using

object OutLock {

  def withLock[T](
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      out: os.Path,
      targetsAndParams: Seq[String],
      streams: SystemStreams
  )(t: => T): T = {
    if (noBuildLock) t
    else {
      val outLock = Lock.file((out / OutFiles.millLock).toString)

      def activeTaskString =
        try {
          os.read(out / OutFiles.millActiveCommand)
        } catch {
          case e => "<unknown>"
        }

      def activeTaskPrefix = s"Another Mill process is running '$activeTaskString',"
      Using.resource {
        val tryLocked = outLock.tryLock()
        if (tryLocked.isLocked()) tryLocked
        else if (noWaitForBuildLock) {
          throw new Exception(s"$activeTaskPrefix failing")
        } else {

          streams.err.println(
            s"$activeTaskPrefix waiting for it to be done..."
          )
          outLock.lock()
        }
      } { _ =>
        os.write.over(out / OutFiles.millActiveCommand, targetsAndParams.mkString(" "))
        try t
        finally os.remove.all(out / OutFiles.millActiveCommand)
      }
    }
  }

}
