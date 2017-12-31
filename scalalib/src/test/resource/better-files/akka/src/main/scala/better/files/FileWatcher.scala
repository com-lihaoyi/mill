package better.files

import akka.actor._

/**
  * An actor that can watch a file or a directory
  * Instead of directly calling the constructor of this, call file.newWatcher to create the actor
  *
  * @param file     watch this file (or directory)
  * @param maxDepth In case of directories, how much depth should we watch
  */
class FileWatcher(file: File, maxDepth: Int) extends Actor {
  import FileWatcher._

  def this(file: File, recursive: Boolean = true) = this(file, if (recursive) Int.MaxValue else 0)

  protected[this] val callbacks = newMultiMap[Event, Callback]

  protected[this] val monitor: File.Monitor = new FileMonitor(file, maxDepth) {
    override def onEvent(event: Event, file: File, count: Int) = self ! Message.NewEvent(event, file, count)
    override def onException(exception: Throwable) = self ! Status.Failure(exception)
  }

  override def preStart() = monitor.start()(executionContext = context.dispatcher)

  override def receive = {
    case Message.NewEvent(event, target, count) if callbacks.contains(event) => callbacks(event).foreach(f => repeat(count)(f(event -> target)))
    case Message.RegisterCallback(events, callback) => events.foreach(event => callbacks.addBinding(event, callback))
    case Message.RemoveCallback(event, callback) => callbacks.removeBinding(event, callback)
  }

  override def postStop() = monitor.stop()
}

object FileWatcher {
  import java.nio.file.{Path, WatchEvent}

  type Event = WatchEvent.Kind[Path]
  type Callback = PartialFunction[(Event, File), Unit]

  sealed trait Message
  object Message {
    case class NewEvent(event: Event, file: File, count: Int) extends Message
    case class RegisterCallback(events: Traversable[Event], callback: Callback) extends Message
    case class RemoveCallback(event: Event, callback: Callback) extends Message
  }

  implicit val disposeActorSystem: Disposable[ActorSystem] =
    Disposable(_.terminate())

  implicit class FileWatcherOps(file: File) {
    def watcherProps(recursive: Boolean): Props =
      Props(new FileWatcher(file, recursive))

    def newWatcher(recursive: Boolean = true)(implicit system: ActorSystem): ActorRef =
      system.actorOf(watcherProps(recursive))
  }

  def when(events: Event*)(callback: Callback): Message =
    Message.RegisterCallback(events, callback)

  def on(event: Event)(callback: File => Unit): Message =
    when(event) { case (`event`, file) => callback(file) }

  def stop(event: Event, callback: Callback): Message =
    Message.RemoveCallback(event, callback)
}
