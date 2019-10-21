package eval

import java.lang

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm}
import ammonite.util.Colors
import cats.effect.{Blocker, IO}
import cats.effect.IO.contextShift
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.{EntityDecoder, Method, Request, Uri}
import ujson.{Arr, Obj, Str, Value}

import scala.util.Try
import scala.util.matching.Regex
import mill.define.Task
import cats.instances.list._
import cats.syntax.parallel._
import mill.eval.Logger
import mill.util.PrintLogger

import scala.concurrent.ExecutionContext

object RemoteCacher {
  var log: Logger = _ //sneakily injecting a log
//  var log = PrintLogger(
//    true,
//    true,
//    Colors.Default,
//    System.out,
//    System.err,
//    System.err,
//    System.in,
//    debugEnabled = false
//  )

  implicit val ec = scala.concurrent.ExecutionContext.global
  implicit val cs = contextShift(ec)
  val clientResource = BlazeClientBuilder[IO](ec).resource

  val outDir: Path = pwd / 'out
  val newOutDir: Path = pwd / 'tmpOut

  /**
    * Uploads tasks to specified remote caching server
    */
  def uploadTasks(tasks: Seq[Task[_]], evilLog: Logger): Unit = {
    log = evilLog
    log.info("Uploading attempt")
    rm(newOutDir)
    mkdir(newOutDir)
    //TODO use Tasks. Hardcoding for now
    uploadTask("foo")
    uploadTask("bar")
    rm(newOutDir)
  }

  private def uploadIO(compressedPath: Path, pathFrom: Path, hash: Int): IO[Unit] = {
//    fs2.io.readInputStream[IO](, 1000, Blocker.liftExecutionContext(ExecutionContext.global))
    log.info(s"upload attempt for $compressedPath with hash $hash")
    implicit val decoder = EntityDecoder.void[IO]
    clientResource.use[Unit](client => {
      val request = Request[IO](
        method = Method.PUT,
        uri = Uri.unsafeFromString(s"https:localhost:7000/cache/$pathFrom?hash=$hash"),
        body = fs2.io.file.readAll[IO](compressedPath.toNIO, Blocker.liftExecutionContext(ExecutionContext.global), 1000)
      )
      log.info(request.toString())
      client.expect(
        request
      )
    }
    )
  }

  val metaPathWithRef: Regex = "(q?ref:[0-9a-fA-F]+:)(.*)".r
  val maybePathRegex: Regex = "(/.*)".r //TODO better regex?
  private def convertIfPath(relativeTo: Path): Value => Option[String] = {
    case Str(metaPathWithRef(ref, path)) => Some(s"$ref${
      Path(path).relativeTo(relativeTo)
    } ")
    case Str(maybePathRegex(maybePath)) =>
      Try {
        Path(maybePath).relativeTo(relativeTo).toString()
      } toOption
    case _ => None
  }


  /**
    * Uploads the task. Anything associated with an input hash will be uploaded.
    * Some tasks like T {10} just have one directory to upload.
    * But a Task using ScalaModule would have allSources, compile, etc. Each of those directories would be uploaded with it's hash.
    *
    * @param taskName
    */
  private def uploadTask(taskName: String): Unit = {

    val taskDir: Path = outDir / taskName
    val newTaskDir: Path = newOutDir / taskName
    cp(taskDir, newTaskDir)

    val directoriesWithHash = if (ammonite.ops.exists(newTaskDir / "meta.json")) {
      List(newTaskDir)
    } else {
      ls ! newTaskDir toList
    }

    directoriesWithHash.map(p => {
      rewriteMeta(p)
      val compressedPath = Path(s"$p.tar.gz")
      ammonite.ops.%%('tar, "-zcvf", compressedPath, p)(p)

      uploadIO(compressedPath, p, 3) //TODO rewrite some of this to get hash above
    }).parSequence.unsafeRunAsyncAndForget()


    /**
      * Rewrite metaJson so if there are any absolute paths then convert them to relative paths.
      *
      * @param baseDir
      */
    def rewriteMeta(baseDir: Path): Unit = {
      val metaDir = baseDir / "meta.json"
      val metaJson = ujson.read(metaDir toIO)

      metaJson.obj.get("value").foreach {
        case Obj(obj) =>
          obj.foreach({
            case (k, v) =>
              convertIfPath(baseDir)(v).foreach(s => {
                println(s);
                obj.update(k, s)
              })
          })
        case Arr(arr) =>
          metaJson.obj.put("value",
            Arr(
              arr.map(v =>
                convertIfPath(baseDir)(v).map({
                  s => Str(s)
                }).getOrElse(v)
              )
            )
          )
        case Str(str) =>
          convertIfPath(baseDir)(str).foreach(s =>
            metaJson.obj.put("value", Str(s))
          )
        case _ => ()
      }

      ammonite.ops.write.over(baseDir / "meta.json", ujson.write(metaJson, 4))
    }
  }
}
