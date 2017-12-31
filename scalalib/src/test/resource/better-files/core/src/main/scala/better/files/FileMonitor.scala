package better.files

import java.nio.file._

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Implementation of File.Monitor
  *
  * @param root
  * @param maxDepth
  */
abstract class FileMonitor(val root: File, maxDepth: Int) extends File.Monitor {
  protected[this] val service = root.newWatchService

  def this(root: File, recursive: Boolean = true) = this(root, if (recursive) Int.MaxValue else 0)

  /**
    * If watching non-directory, don't react to siblings
    * @param target
    * @return
    */
  protected[this] def reactTo(target: File) = root.isDirectory || root.isSamePathAs(target)

  protected[this] def process(key: WatchKey) = {
    val path = key.watchable().asInstanceOf[Path]

    import scala.collection.JavaConverters._
    key.pollEvents().asScala foreach {
      case event: WatchEvent[Path] @unchecked =>
        val target: File = path.resolve(event.context())
        if (reactTo(target)) {
          if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
            val depth = root.relativize(target).getNameCount
            watch(target, (maxDepth - depth) max 0) // auto-watch new files in a directory
          }
          onEvent(event.kind(), target, event.count())
        }
      case event => if (reactTo(path)) onUnknownEvent(event, event.count())
    }
    key.reset()
  }

  protected[this] def watch(file: File, depth: Int): Unit = {
    def toWatch: Files = if (file.isDirectory) {
      file.walk(depth).filter(f => f.isDirectory && f.exists)
    } else {
      when(file.exists)(file.parent).iterator  // There is no way to watch a regular file; so watch its parent instead
    }
    try {
      toWatch.foreach(f => Try[Unit](f.register(service)).recover(PartialFunction(onException)).get)
    } catch {
      case NonFatal(e) => onException(e)
    }
  }

  override def start()(implicit executionContext: ExecutionContext) = {
    watch(root, maxDepth)
    executionContext.execute(() => Iterator.continually(service.take()).foreach(process))
  }

  override def close() = service.close()

  // Although this class is abstract, we give provide implementations so user can choose to implement a subset of these
  override def onCreate(file: File, count: Int) = {}
  override def onModify(file: File, count: Int) = {}
  override def onDelete(file: File, count: Int) = {}
  override def onUnknownEvent(event: WatchEvent[_], count: Int) = {}
  override def onException(exception: Throwable) = {}
}
