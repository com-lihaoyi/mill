package eval

import java.io.FileOutputStream
import java.lang
import java.net.URLEncoder

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm}
import ammonite.util.Colors
import argonaut.CodecJson
import cats.effect.{Blocker, IO}
import cats.effect.IO.contextShift
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.{EntityDecoder, Header, Headers, Method, Request, Uri}
import ujson.{Arr, Obj, Str, Value}

import scala.util.Try
import scala.util.matching.Regex
import mill.define.Task
import cats.instances.list._
import cats.syntax.parallel._
import mill.eval.Logger
import mill.util.PrintLogger
import org.http4s.argonaut._
import argonaut._

import scala.concurrent.ExecutionContext

/**
  * If we have mill use relative paths then a lot of the stuff here isn't needed
  */
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

  case class Cached(hashesAvailable: Map[String, List[Int]])

  implicit val cachedJsonCodec: CodecJson[Cached] = CodecJson.casecodec1(Cached.apply, Cached.unapply)("hashesAvailable")
  implicit val cachedEntityDecoder: EntityDecoder[IO, Cached] = jsonOf[IO, Cached]

  val outDir: Path = pwd / 'out
  val newOutDir: Path = pwd / 'tmpOut


  def getCached: Cached = {
    clientResource.use[Cached](client => {
      val request = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString(s"http://localhost:7000/cached"),
        headers = Headers.of(
          Header("Accept-Encoding", "gzip"),
        )
      )
      log.info(request.toString())
      client.expect[Cached](
        request
      )(cachedEntityDecoder)
    }
    ).unsafeRunSync()
  }


  def fetchAndOverwriteTask(cached: Cached, hashCode: Int, path: Path) = {
    val key = path.relativeTo(newOutDir).toString()
    log.info(s"attempting fetch for $hashCode $path")
    if (cached.hashesAvailable.get(key).exists(_.contains(hashCode))) {
      val bytes = getTaskBytes(key, hashCode).unsafeRunSync()
      val tmpFile = pwd / "tmp.tar.gz"

      log.info(s"Got ${bytes.length} bytes for $key download to $tmpFile")
      val tmpFOS = new FileOutputStream(tmpFile toIO)

      tmpFOS.write(bytes)
      tmpFOS.close()

      rm(path)
      ammonite.ops.%%('tar, "-xvzf", path)(pwd)
      log.info(s"overwrote $path")
      rm(tmpFile)

      true
    } else {
      false
    }
  }

  private def getTaskBytes(pathFrom: String, hash: Int): IO[Array[Byte]] = {
    clientResource.use(client => {
      val request = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString(s"http://localhost:7000/cached?path=${URLEncoder.encode(pathFrom, "UTF-8")}&hash=$hash"),
        headers = Headers.of(
          Header("Accept-Encoding", "gzip"),
        )
      )
      log.info(request.toString())
      client.expect(
        request
      )(EntityDecoder.byteArrayDecoder[IO])
    }
    )
  }
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
    //    uploadTask("bar") TODO
    //    rm(newOutDir) TODO
  }

  private def uploadIO(compressedPath: Path, pathFrom: String, hash: Int): IO[Unit] = {
    log.info(s"upload attempt for $compressedPath with hash $hash")
    val decoder = EntityDecoder.void[IO]
    clientResource.use[Unit](client => {
      val request = Request[IO](
        method = Method.PUT,
        uri = Uri.unsafeFromString(s"http://localhost:7000/cached?path=${URLEncoder.encode(pathFrom, "UTF-8")}&hash=$hash"),
        body = fs2.io.file.readAll[IO](compressedPath.toNIO, Blocker.liftExecutionContext(ExecutionContext.global), 1000),
        headers = Headers.of(
          Header("Content-Encoding", "gzip"),
          Header("Content-Type", "application/octet-stream")
        )
      )
      log.info(request.toString())
      client.expect(
        request
      )(decoder)
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

    /**
      * Rewrite metaJson so if there are any absolute paths then convert them to relative paths.
      *
      * @param baseDir
      */
    def rewriteMeta(baseDir: Path, metaJson: Value): Unit = {
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

    val uploadIOPar = directoriesWithHash.map(p => {
      val metaJson = ujson.read(p / "meta.json" toIO)
      rewriteMeta(p, metaJson)
      val compressedPath = Path(s"$p.tar.gz")
      ammonite.ops.%%('tar, "-zcvf", compressedPath, p)(p)
      uploadIO(compressedPath, p.relativeTo(newOutDir).toString, 3) //TODO rewrite some of this to get hash above
    }).parSequence
    Try {
      uploadIOPar.unsafeRunSync()
    }.fold(f => f.printStackTrace(log.outputStream), us => {
      log.info(s"Rewrote meta! $us")
    })

  }
}
