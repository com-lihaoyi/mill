package hello
import scalatags.Text.all._
import scala.concurrent._, duration.Duration.Inf
class DiskActor(logPath: os.Path, rotateSize: Int = 50)
               (implicit cc: castor.Context) extends castor.SimpleActor[String]{
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

class Base64Actor(dest: castor.Actor[String])
                 (implicit cc: castor.Context) extends castor.SimpleActor[String]{
  def run(msg: String) = {
    dest.send(java.util.Base64.getEncoder.encodeToString(msg.getBytes))
  }
}

class UploadActor(url: String)
                 (implicit cc: castor.Context) extends castor.SimpleActor[String]{
  def run(msg: String) = {
    val res = requests.post(url, data = msg)
    println(s"response ${res.statusCode} " + ujson.read(res)("data"))
  }
}
class SanitizeActor(dest: castor.Actor[String])
                   (implicit cc: castor.Context) extends castor.SimpleActor[String]{
  def run(msg: String) = {
    dest.send(msg.replaceAll("([0-9]{4})[0-9]{8}([0-9]{4})", "<redacted>"))
  }
}

object Hello{
  def main() = {
//    implicit val cc = new castor.Context.Test()
//
//    val diskActor = new DiskActor(os.pwd / "log.txt")
//    val uploadActor = new UploadActor("https://httpbin.org/post")
//
//    val base64Actor = new Base64Actor(diskActor)
//    val sanitizeActor = new SanitizeActor(uploadActor)
//
//    val logger = new castor.SplitActor(base64Actor, sanitizeActor)
  }
}

/* EXPECTED CALL GRAPH
{
}
*/
