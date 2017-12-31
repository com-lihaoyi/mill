Reproduction of [this Java Advent article](http://www.javaadvent.com/2015/12/reactive-file-system-monitoring-using-akka-actors.html)

-----

In this article, we will discuss:

0. File system monitoring using [Java NIO.2][nio2]
1. Common pitfalls of the default Java library
2. Design a simple thread-based file system monitor
3. Use the above to design a reactive file system monitor using the [actor][akka] [model][actorModel]

**Note**: Although all the code samples here are in Scala, it can be rewritten in simple Java too. To quickly familiarize yourself with Scala syntax, [here is a very short and nice Scala cheatsheet][cheatsheet]. For a more comprehensive guide to Scala for Java programmers, [consult this][cheatsheet2] (not needed to follow this article).

For the absolute shortest cheatsheet, the following Java code:

```java
public void foo(int x, int y) {
  int z = x + y
  if (z == 1) {
    System.out.println(x);
  } else {
    System.out.println(y);
  }
}
```

is equivalent to the following Scala code:

```scala
def foo(x: Int, y: Int): Unit = {
  val z: Int = x + y
  z match {
   case 1 => println(x)
   case _ => println(y)
  }
}
```


All the code presented here is available under MIT license as part of the [better-files][better-files-watcher]  library on [GitHub][better-files].

-----------

Let's say you are tasked to build a cross-platform desktop file-search engine. You quickly realize that after the initial indexing of all the files, you need to also quickly reindex any new files (or directories) that got created or updated. A naive way would be to simply rescan the entire file system every few minutes; but that would be incredibly inefficient since most operating systems expose file system notification APIs that allow the application programmer to register callbacks for changes e.g. [ionotify][ionotify-wiki] in Linux, [FSEvenets][fsevents-wiki] in Mac and [FindFirstChangeNotification][FindFirstChangeNotification] in Windows. 

But now you are stuck dealing with OS-specific APIs! Thankfully, beginning Java SE 7, we have a platform independent abstraction for watching file system changes via the [WatchService API][javadoc-watchservice]. The WatchService API was developed as part of [Java NIO.2][nio2-wiki], under [JSR-51][jsr-51] and here is a "hello world" example of using it to watch a given [Path][javadoc-path]:

```scala
import java.nio.file._
import java.nio.file.StandardWatchEventKinds._
import scala.collection.JavaConversions._

def watch(directory: Path): Unit = {
  // First create the service
  val service: WatchService = directory.getFileSystem.newWatchService()

  // Register the service to the path and also specify which events we want to be notified about
  directory.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)

  while (true) {
    val key: WatchKey = service.take()  // Wait for this key to be signalled
    for {event <- key.pollEvents()} {
      // event.context() is the path to the file that got changed  
      event.kind() match {
        case ENTRY_CREATE => println(s"${event.context()} got created")
        case ENTRY_MODIFY => println(s"${event.context()} got modified")
        case ENTRY_DELETE => println(s"${event.context()} got deleted")        
        case _ => 
          // This can happen when OS discards or loses an event. 
          // See: http://docs.oracle.com/javase/8/docs/api/java/nio/file/StandardWatchEventKinds.html#OVERFLOW
          println(s"Unknown event $event happened at ${event.context()}")
      }
    }
    key.reset()  // Do not forget to do this!! See: http://stackoverflow.com/questions/20180547/
  }
}
```

Although the above is a good first attempt, it lacks in several aspects:

0. **Bad Design**: The above code looks unnatural and you probably had to [look it up on StackOverflow][so-down] to get it right. Can we do better?
2. **Bad Design**: The code does not do a very good job of handling errors. What happens when we encounter a file we could not open?
3. **Gotcha**: The Java API only allows us to watch the directory for changes to its direct children; it [does not recursively watch a directory][so-recursive-watching] for you.
4. **Gotcha**: The Java API [does not allow us to watch a single file][so-only-watch-dirs], only a directory.
5. **Gotcha**: Even if we resolve the aformentioned issues, the Java API [does not automatically start watching a new child file][so-autowatch] or directory created under the root. 
6. **Bad Design**: The code as implemented above, exposes a blocking/polling, thread-based model. Can we use a better concurrency abstraction?

-----------


Let's start with each of the above concerns.

* **A better interface**: Here is what *my ideal* interface would look like:

```scala
abstract class FileMonitor(root: Path) {
  def start(): Unit
  def onCreate(path: Path): Unit
  def onModify(path: Path): Unit
  def onDelete(path: Path): Unit  
  def stop(): Unit
}
```

That way, I can simply write the example code as:

```scala
val watcher = new FileMonitor(myFile) {
  override def onCreate(path: Path) = println(s"$path got created")  
  override def onModify(path: Path) = println(s"$path got modified")    
  override def onDelete(path: Path) = println(s"$path got deleted")  
}
watcher.start()
```

Ok, let's try to adapt the first example using a Java `Thread` so that we can expose "my ideal interface":

```scala
trait FileMonitor {                               // My ideal interface
  val root: Path                                  // starting file  
  def start(): Unit                               // start the monitor 
  def onCreate(path: Path) = {}                   // on-create callback 
  def onModify(path: Path) = {}                   // on-modify callback 
  def onDelete(path: Path) = {}                   // on-delete callback 
  def onUnknownEvent(event: WatchEvent[_]) = {}   // handle lost/discarded events
  def onException(e: Throwable) = {}              // handle errors e.g. a read error
  def stop(): Unit                                // stop the monitor
}
```

And here is a very basic thread-based implementation:

```scala
class ThreadFileMonitor(val root: Path) extends Thread with FileMonitor {
  setDaemon(true)        // daemonize this thread
  setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
    override def uncaughtException(thread: Thread, exception: Throwable) = onException(exception)    
  })

  val service = root.getFileSystem.newWatchService()

  override def run() = Iterator.continually(service.take()).foreach(process)

  override def interrupt() = {
    service.close()
    super.interrupt()
  }

  override def start() = {
    watch(root)
    super.start()
  }

  protected[this] def watch(file: Path): Unit = {
    file.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
  }

  protected[this] def process(key: WatchKey) = {
    key.pollEvents() foreach {
      case event: WatchEvent[Path] => dispatch(event.kind(), event.context())      
      case event => onUnknownEvent(event)
    }
    key.reset()
  }

  def dispatch(eventType: WatchEvent.Kind[Path], file: Path): Unit = {
    eventType match {
      case ENTRY_CREATE => onCreate(file)
      case ENTRY_MODIFY => onModify(file)
      case ENTRY_DELETE => onDelete(file)
    }
  }
}
```

The above looks much cleaner! Now we can watch files to our heart's content without poring over the details of JavaDocs by simply implementing the `onCreate(path)`, `onModify(path)`, `onDelete(path)` etc.

* **Exception handling**: This is already done above. `onException` gets called whenever we encounter an exception and the invoker can decide what to do next by implementing it.

* **Recursive watching**: The Java API **does not allow recursive watching of directories**. We need to modify the `watch(file)` to recursively attach the watcher:

```scala
def watch(file: Path, recursive: Boolean = true): Unit = {
  if (Files.isDirectory(file)) {
    file.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)              
     // recursively call watch on children of this file  
     if (recursive) { 
       Files.list(file).iterator() foreach {f => watch(f, recursive)}
     } 
  }
}
```

* **Watching regular files**: As mentioned before, the Java API **can only watch directories**. One hack we can do to watch single files is to set a watcher on its parent directory and only react if the event trigerred on the file itself.

```scala
override def start() = {
  if (Files.isDirectory(root)) {
    watch(root, recursive = true) 
  } else {
    watch(root.getParent, recursive = false)
  }
  super.start()
}
```

And, now in `process(key)`, we make sure we react to either a directory or that file only:

```scala
def reactTo(target: Path) = Files.isDirectory(root) || (root == target)
```

And, we check before `dispatch` now:

```scala
case event: WatchEvent[Path] =>
  val target = event.context()
  if (reactTo(target)) {
    dispatch(event.kind(), target)
  }
```

* **Auto-watching new items**: The Java API, **does not auto-watch any new sub-files**. We can address this by attaching the watcher ourselves in `process(key)` when an `ENTRY_CREATE` event is fired: 

```scala
if (reactTo(target)) {
  if (Files.isDirectory(root) && event.kind() == ENTRY_CREATE) {
    watch(root.resolve(target))
  }
  dispatch(event.kind(), target)
}
```

Putting it all together, we have our final  [`FileMonitor.scala`][FileMonitor.scala]:

```scala
class ThreadFileMonitor(val root: Path) extends Thread with FileMonitor {
  setDaemon(true) // daemonize this thread
  setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
    override def uncaughtException(thread: Thread, exception: Throwable) = onException(exception)    
  })

  val service = root.getFileSystem.newWatchService()

  override def run() = Iterator.continually(service.take()).foreach(process)

  override def interrupt() = {
    service.close()
    super.interrupt()
  }

  override def start() = {
    if (Files.isDirectory(root)) {
      watch(root, recursive = true) 
    } else {
      watch(root.getParent, recursive = false)
    }
    super.start()
  }

  protected[this] def watch(file: Path, recursive: Boolean = true): Unit = {
    if (Files.isDirectory(file)) {
      file.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
      if (recursive) {
        Files.list(file).iterator() foreach {f => watch(f, recursive)}
      }  
    }
  }

  private[this] def reactTo(target: Path) = Files.isDirectory(root) || (root == target)

  protected[this] def process(key: WatchKey) = {
    key.pollEvents() foreach {
      case event: WatchEvent[Path] =>
        val target = event.context()
        if (reactTo(target)) {
          if (Files.isDirectory(root) && event.kind() == ENTRY_CREATE) {
            watch(root.resolve(target))
          }
          dispatch(event.kind(), target)
        }
      case event => onUnknownEvent(event)
    }
    key.reset()
  }

  def dispatch(eventType: WatchEvent.Kind[Path], file: Path): Unit = {
    eventType match {
      case ENTRY_CREATE => onCreate(file)
      case ENTRY_MODIFY => onModify(file)
      case ENTRY_DELETE => onDelete(file)
    }
  }
}
```

-----
Now, that we have addressed all the gotchas and distanced ourselves from the intricacies of the WatchService API, we are still tightly coupled to the thread-based API. 
We will use the above class to expose a different concurrency model, namely, the [actor model][actorModel2] instead to design a reactive, dynamic and resilient file-system watcher using [Akka][akka-docs]. Although the [construction of Akka actors][akka-actors] is beyond the scope of this article, we will present a very simple actor that uses the `ThreadFileMonitor`:

```scala
import java.nio.file.{Path, WatchEvent}

import akka.actor._

class FileWatcher(file: Path) extends ThreadFileMonitor(file) with Actor {
  import FileWatcher._

  // MultiMap from Events to registered callbacks
  protected[this] val callbacks = newMultiMap[Event, Callback]  

  // Override the dispatcher from ThreadFileMonitor to inform the actor of a new event
  override def dispatch(event: Event, file: Path) = self ! Message.NewEvent(event, file)  

  // Override the onException from the ThreadFileMonitor
  override def onException(exception: Throwable) = self ! Status.Failure(exception)

  // when actor starts, start the ThreadFileMonitor
  override def preStart() = super.start()   
  
  // before actor stops, stop the ThreadFileMonitor
  override def postStop() = super.interrupt()

  override def receive = {
    case Message.NewEvent(event, target) if callbacks contains event => 
       callbacks(event) foreach {f => f(event -> target)}

    case Message.RegisterCallback(events, callback) => 
       events foreach {event => callbacks.addBinding(event, callback)}

    case Message.RemoveCallback(event, callback) => 
       callbacks.removeBinding(event, callback)
  }
}

object FileWatcher {
  type Event = WatchEvent.Kind[Path]
  type Callback = PartialFunction[(Event, Path), Unit]

  sealed trait Message
  object Message {
    case class NewEvent(event: Event, file: Path) extends Message
    case class RegisterCallback(events: Seq[Event], callback: Callback) extends Message
    case class RemoveCallback(event: Event, callback: Callback) extends Message
  }
}
```

This allows us to dynamically register and remove callbacks to react to file system events:

```scala
// initialize the actor instance
val system = ActorSystem("mySystem") 
val watcher: ActorRef = system.actorOf(Props(new FileWatcher(Paths.get("/home/pathikrit"))))

// util to create a RegisterCallback message for the actor
def when(events: Event*)(callback: Callback): Message = {
  Message.RegisterCallback(events.distinct, callback)
}

// send the register callback message for create/modify events
watcher ! when(events = ENTRY_CREATE, ENTRY_MODIFY) {   
  case (ENTRY_CREATE, file) => println(s"$file got created")
  case (ENTRY_MODIFY, file) => println(s"$file got modified")
}
```

Full source: [`FileWatcher.scala`][FileWatcher.scala]

-----

[actorModel]: https://en.wikipedia.org/wiki/Actor_model
[actorModel2]: http://berb.github.io/diploma-thesis/original/054_actors.html
[akka]: http://akka.io
[akka-actors]: http://doc.akka.io/docs/akka/snapshot/scala/actors.html
[akka-docs]: http://doc.akka.io/docs/akka/2.4.1/java.html
[better-files]: https://github.com/pathikrit/better-files
[better-files-watcher]: https://github.com/pathikrit/better-files#akka-file-watcher
[cheatsheet]: http://learnxinyminutes.com/docs/scala/
[cheatsheet2]: http://techblog.realestate.com.au/java-to-scala-cheatsheet/
[FileWatcher.scala]: https://github.com/pathikrit/better-files/blob/2ea6bb694551f1fe6e9ce58dbd1b814391a02e5a/akka/src/main/scala/better/files/FileWatcher.scala
[FileMonitor.scala]: https://github.com/pathikrit/better-files/blob/2ea6bb694551f1fe6e9ce58dbd1b814391a02e5a/core/src/main/scala/better/files/FileMonitor.scala
[FindFirstChangeNotification]: https://msdn.microsoft.com/en-us/library/aa364417(VS.85).aspx
[fsevents-wiki]: https://en.wikipedia.org/wiki/FSEvents
[ionotify-wiki]: https://en.wikipedia.org/wiki/Inotify
[nio2]: https://docs.oracle.com/javase/tutorial/essential/io/fileio.html
[nio2-wiki]: https://en.wikipedia.org/wiki/Non-blocking_I/O_(Java)
[jsr-51]: https://www.jcp.org/en/jsr/detail?id=51
[javadoc-path]: https://docs.oracle.com/javase/8/docs/api/java/nio/file/Path.html
[javadoc-watchservice]: https://docs.oracle.com/javase/8/docs/api/java/nio/file/WatchService.html
[so-autowatch]: https://github.com/lloydmeta/schwatcher/issues/44
[so-down]: http://memecrunch.com/meme/YBHZ/stackoverflow-is-down/image.jpg
[so-recursive-watching]: http://stackoverflow.com/questions/18701242/how-to-watch-a-folder-and-subfolders-for-changes
[so-only-watch-dirs]: http://stackoverflow.com/questions/16251273/can-i-watch-for-single-file-change-with-watchservice-not-the-whole-directory
