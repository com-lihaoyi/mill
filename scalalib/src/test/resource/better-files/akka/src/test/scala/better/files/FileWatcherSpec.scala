package better.files

import Dsl._

import scala.concurrent.duration._
import scala.collection.mutable
import scala.language.postfixOps

class FileWatcherSpec extends CommonSpec {
  "file watcher" should "watch directories" in {
    assume(isCI)
    File.usingTemporaryDirectory() {dir =>
      (dir / "a" / "b" / "c.txt").createIfNotExists(createParents = true)

      var actualEvents = List.empty[String]
      def output(file: File, event: String) = synchronized {
        val msg = s"${dir.path relativize file.path} got $event"
        println(msg)
        actualEvents = msg :: actualEvents
      }
      /***************************************************************************/
      import java.nio.file.{StandardWatchEventKinds => Events}
      import FileWatcher._

      import akka.actor.{ActorRef, ActorSystem}
      implicit val system = ActorSystem()

      val watcher: ActorRef = dir.newWatcher()

      watcher ! when(events = Events.ENTRY_CREATE, Events.ENTRY_MODIFY) {   // watch for multiple events
        case (Events.ENTRY_CREATE, file) => output(file, "created")
        case (Events.ENTRY_MODIFY, file) => output(file, "modified")
      }

      watcher ! on(Events.ENTRY_DELETE)(file => output(file, "deleted"))    // register partial function for single event
      /***************************************************************************/
      sleep(5 seconds)

      val expectedEvents = mutable.ListBuffer.empty[String]

      def doIO[U](logs: String*)(f: => U): Unit = {
        expectedEvents ++= logs
        f
        sleep()
      }

      doIO("a/b/c.txt got modified") {
        (dir / "a" / "b" / "c.txt").writeText("Hello world")
      }
      doIO("a/b got deleted", "a/b/c.txt got deleted") {
        rm(dir / "a" / "b")
      }
      doIO("d got created") {
        mkdir(dir / "d")
      }
      doIO("d/e.txt got created") {
        touch(dir / "d" / "e.txt")
      }
      doIO("d/f got created") {
        mkdirs(dir / "d" / "f" / "g")
      }
      doIO("d/f/g/e.txt got created") {
        touch(dir / "d" / "f" / "g" / "e.txt")
      }

      doIO("a/e.txt got created", "d/f/g/e.txt got deleted") {
        (dir / "d" / "f" / "g" / "e.txt") moveTo (dir / "a" / "e.txt")
      }

      sleep(10 seconds)

      println(
        s"""
           |Expected=${expectedEvents.sorted}
           |Actual=${actualEvents.sorted}
           |""".stripMargin)

      expectedEvents.diff(actualEvents) shouldBe empty

      def checkNotWatching[U](msg: String)(f: => U) = {
        val before = List(actualEvents : _*)
        f
        sleep()
        val after = List(actualEvents : _*)
        assert(before === after, msg)
      }

      system.stop(watcher)
      sleep()
      checkNotWatching("stop watching after actor is stopped") {
        mkdirs(dir / "e")
      }

      system.terminate()
      sleep()
      checkNotWatching("stop watching after actor-system is stopped") {
        mkdirs(dir / "f")
      }
    }
  }
}
