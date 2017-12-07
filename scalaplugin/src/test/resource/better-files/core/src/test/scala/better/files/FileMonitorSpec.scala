package better.files

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

class FileMonitorSpec extends CommonSpec {
  "file watcher" should "watch single files" in {
    assume(isCI)
    val file = File.newTemporaryFile(suffix = ".txt").writeText("Hello world")

    var log = List.empty[String]
    def output(msg: String) = synchronized {
      println(msg)
      log = msg :: log
    }
    /***************************************************************************/
    val watcher = new FileMonitor(file) {
      override def onCreate(file: File, count: Int) = output(s"$file got created $count time(s)")
      override def onModify(file: File, count: Int) = output(s"$file got modified $count time(s)")
      override def onDelete(file: File, count: Int) = output(s"$file got deleted $count time(s)")
    }
    watcher.start()
    /***************************************************************************/
    sleep(5 seconds)
    file.writeText("hello world"); sleep()
    file.clear(); sleep()
    file.writeText("howdy"); sleep()
    file.delete(); sleep()
    sleep(5 seconds)
    val sibling = (file.parent / "t1.txt").createIfNotExists(); sleep()
    sibling.writeText("hello world"); sleep()
    sleep(20 seconds)

    log.size should be >= 2
    log.exists(_ contains sibling.name) shouldBe false
    log.forall(_ contains file.name) shouldBe true
  }

  ignore should "watch directories to configurable depth" in {
    assume(isCI)
    val dir = File.newTemporaryDirectory()
    (dir/"a"/"b"/"c"/"d"/"e").createDirectories()
    var log = List.empty[String]
    def output(msg: String) = synchronized(log = msg :: log)

    val watcher = new FileMonitor(dir, maxDepth = 2) {
      override def onCreate(file: File, count: Int) = output(s"Create happened on ${file.name} $count times")
    }
    watcher.start()

    sleep(5 seconds)
    (dir/"a"/"b"/"t1").touch().writeText("hello world"); sleep()
    (dir/"a"/"b"/"c"/"d"/"t1").touch().writeText("hello world"); sleep()
    sleep(10 seconds)

    withClue(log) {
      log.size shouldEqual 1
    }
  }
}
