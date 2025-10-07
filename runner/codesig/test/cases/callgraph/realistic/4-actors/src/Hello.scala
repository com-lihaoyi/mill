package hello
// Taken from https://github.com/handsonscala/handsonscala/blob/ebc0367144513fc181281a024f8071a6153be424/examples/16.8%20-%20LoggingRearrangedPipeline2/LoggingPipeline.sc
import scalatags.Text.all._
import scala.concurrent._, duration.Duration.Inf
class DiskActor(logPath: os.Path, rotateSize: Int = 50)(using cc: castor.Context)
    extends castor.SimpleActor[String] {
  val oldPath = logPath / os.up / (logPath.last + "-old")
  def run(s: String) = {
    val newLogSize = logSize + s.length + 1
    if (newLogSize <= rotateSize) logSize = newLogSize
    else { // rotate log file by moving it to old path and starting again from empty
      logSize = s.length + 1
      os.move(logPath, oldPath, replaceExisting = true)
    }
    os.write.append(logPath, s + "\n", createFolders = true)
  }
  private var logSize = 0
}

class Base64Actor(dest: castor.Actor[String])(using cc: castor.Context)
    extends castor.SimpleActor[String] {
  def run(msg: String) = {
    dest.send(java.util.Base64.getEncoder.encodeToString(msg.getBytes))
  }
}

class UploadActor(url: String)(using cc: castor.Context) extends castor.SimpleActor[String] {
  def run(msg: String) = {
    val res = requests.post(url, data = msg)
    println(s"response ${res.statusCode} " + ujson.read(res)("data"))
  }
}
class SanitizeActor(dest: castor.Actor[String])(using cc: castor.Context)
    extends castor.SimpleActor[String] {
  def run(msg: String) = {
    dest.send(msg.replaceAll("([0-9]{4})[0-9]{8}([0-9]{4})", "<redacted>"))
  }
}

object Hello {
  def main() = {
    implicit val cc = new castor.Context.Test()

    val diskActor = new DiskActor(os.pwd / "log.txt")
    val uploadActor = new UploadActor("https://httpbin.org/post")

    val base64Actor = new Base64Actor(diskActor)
    val sanitizeActor = new SanitizeActor(uploadActor)

    val logger = new castor.SplitActor(base64Actor, sanitizeActor)
  }
}

/* expected-direct-call-graph
{
    "hello.Base64Actor#<init>(castor.Actor,castor.Context)void": [
        "hello.Base64Actor#run(java.lang.Object)void"
    ],
    "hello.Base64Actor#run(java.lang.Object)void": [
        "hello.Base64Actor#run(java.lang.String)void"
    ],
    "hello.DiskActor#<init>(os.Path,int,castor.Context)void": [
        "hello.DiskActor#run(java.lang.Object)void"
    ],
    "hello.DiskActor#run(java.lang.Object)void": [
        "hello.DiskActor#run(java.lang.String)void"
    ],
    "hello.DiskActor#run(java.lang.String)void": [
        "hello.DiskActor#oldPath()os.Path"
    ],
    "hello.DiskActor.$lessinit$greater$default$2()int": [
        "hello.DiskActor$#$lessinit$greater$default$2()int",
        "hello.DiskActor$#<init>()void"
    ],
    "hello.Hello$#main()void": [
        "hello.Base64Actor#<init>(castor.Actor,castor.Context)void",
        "hello.DiskActor#<init>(os.Path,int,castor.Context)void",
        "hello.DiskActor$#$lessinit$greater$default$2()int",
        "hello.DiskActor$#<init>()void",
        "hello.SanitizeActor#<init>(castor.Actor,castor.Context)void",
        "hello.UploadActor#<init>(java.lang.String,castor.Context)void"
    ],
    "hello.Hello.main()void": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#main()void"
    ],
    "hello.SanitizeActor#<init>(castor.Actor,castor.Context)void": [
        "hello.SanitizeActor#run(java.lang.Object)void"
    ],
    "hello.SanitizeActor#run(java.lang.Object)void": [
        "hello.SanitizeActor#run(java.lang.String)void"
    ],
    "hello.UploadActor#<init>(java.lang.String,castor.Context)void": [
        "hello.UploadActor#run(java.lang.Object)void"
    ],
    "hello.UploadActor#run(java.lang.Object)void": [
        "hello.UploadActor#run(java.lang.String)void"
    ]
}
 */
