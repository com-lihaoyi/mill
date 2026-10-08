package mill
package contrib.docker

import com.google.cloud.tools.jib.event.events.ProgressEvent
import com.google.cloud.tools.jib.event.events.TimerEvent
import com.google.cloud.tools.jib.api.LogEvent
import com.google.cloud.tools.jib.api.JibEvent

object JibLogging {

  def eventLogger(log: mill.api.Logger): java.util.function.Consumer[LogEvent] =
    new java.util.function.Consumer[LogEvent] {
      def accept(e: LogEvent): Unit = log.info(s"LogEvent: ${e.getMessage}")
    }

  def logger(log: mill.api.Logger): java.util.function.Consumer[JibEvent] =
    new java.util.function.Consumer[JibEvent] {
      def accept(e: JibEvent): Unit = e match {
        case m: LogEvent =>
          log.info(s"LogEvent: ${m.getMessage}")
        case p: ProgressEvent =>
          log.ticker(
            s"ProgressEvent: ${p.getAllocation.getFractionOfRoot * p.getUnits} ${p.getAllocation.getDescription}"
          )
        case t: TimerEvent =>
          log.ticker(s"TimerEvent: ${t.getDescription} ${t.getElapsed}")
        case _ =>
          log.info(s"JibEvent: $e")
      }
    }
}
