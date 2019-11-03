package eval

import java.io.{File, FileInputStream, FileOutputStream}
import java.net.URLEncoder

import ammonite.ops.{Path, cp, mkdir, pwd, rm}
import argonaut.CodecJson
import cats.effect.IO.contextShift
import cats.effect.{Blocker, IO}
import cats.instances.list._
import cats.syntax.parallel._
import mill.define.Target
import mill.eval.Logger
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.utils.IOUtils
import org.http4s.argonaut._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s._
import ujson.{Arr, Obj, Str, Value}

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.matching.Regex


/**
 * If we have mill use relative paths then a lot of the stuff here isn't needed
 */
object RemoteCacher {
  var log: Logger = _ //sneakily injecting a log because this won't work TODO do it?
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


  def getCached(evilLog: Logger): Cached = {
    log = evilLog

    clientResource.use[Cached](client => {
      val request = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString(s"http://localhost:7000/cached"),
        headers = Headers.of(
          Header("Accept-Encoding", "gzip")
        )
      )
      log.info(Option(request).toString)
      client.expect[Cached](
        request
      )(cachedEntityDecoder)
    }
    ).unsafeRunSync()
  }


  def fetchAndOverwriteTask(cached: Cached, hashCode: Int, path: Path) = {
    val key = path.relativeTo(outDir).toString()
    log.info(s"attempting fetch for $hashCode $key")
    if (cached.hashesAvailable.get(key).exists(_.contains(hashCode))) {
      val compressedBytes = getTaskBytes(key, hashCode).unsafeRunSync()

      log.info(s"Got ${compressedBytes.length} bytes for $key download")

      log.info(s"overwriting $path")
      rm(path)
      mkdir(path)

      val tmpFile = File.createTempFile(key, ".tar.gz")
      val tmpFOS = new FileOutputStream(tmpFile)
      tmpFOS.write(compressedBytes)
      tmpFOS.close()

      ammonite.ops.%%('tar, "xvzf", tmpFile.getPath.toString)(path)
      //        ammonite.ops.%%(s"tar -xvzf ${tmpFile.getPath}")(parentDir(path))

      true
    } else {
      false
    }
  }

  private def getTaskBytes(pathFrom: String, hash: Int): IO[Array[Byte]] = {
    clientResource.use(client => {
      val request = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString(s"http://localhost:7000/cached?path=${
          URLEncoder.encode(pathFrom, "UTF-8")
        }&hash=$hash"),
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
  def uploadTasks(tasks: Seq[Target[_]], evilLog: Logger): Unit = {
    log = evilLog
    log.info(s"Uploading attempt given ${
      tasks.flatMap(_.asTarget)
    }")
    rm(newOutDir)
    mkdir(newOutDir)

    Try {
      tasks.map(uploadTask).toList.parSequence.unsafeRunSync()
    }.fold(f => f.printStackTrace(log.outputStream), us => {
      log.info(s"Rewrote ${us.size} caches")
    })

    rm(newOutDir) TODO just keeping alive so I can inspect
  }

  private def uploadIO(compressedPath: Path, pathFrom: String, hash: Int): IO[Unit] = {
    log.info(s"upload attempt for $compressedPath with hash $hash")
    val decoder = EntityDecoder.void[IO]
    clientResource.use[Unit](client => {
      val request = Request[IO](
        method = Method.PUT,
        uri = Uri.unsafeFromString(s"http://localhost:7000/cached?path=$pathFrom&hash=$hash"),
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


  /**
   * Uploads the task. Anything associated with an input hash will be uploaded.
   * Some tasks like T {10} just have one directory to upload.
   * But a Task using ScalaModule would have allSources, compile, etc. Each of those directories would be uploaded with it's hash.
   *
   */
  private def uploadTask(task: Target[_]): IO[Unit] = {

    val partialTaskPath = task.ctx.segments.parts.mkString("/")
    val taskDir: Path = Path(s"$outDir/$partialTaskPath")
    val newTaskDir: Path = Path(s"$newOutDir/$partialTaskPath")
    mkdir(newTaskDir)
    cp.over(taskDir, newTaskDir)

    val metaJson = ujson.read(newTaskDir / "meta.json" toIO)
    val hashCode = metaJson.obj("inputsHash").num.toInt
    RelativePatherizer.rewriteMeta(newTaskDir, metaJson) //Not needed if
    val compressedPath = Path(s"$newTaskDir.tar.gz")
    //ammonite.ops.%%('tar, "-zcvf", compressedPath, taskDir.segments.toList.last)(newTaskDir / "..")

    val tos: TarArchiveOutputStream = new TarArchiveOutputStream(new GzipCompressorOutputStream(new FileOutputStream(compressedPath.toString())))
    os.walk(newTaskDir).foreach(p => {
      val file = p.toIO
      if (file.isFile) {
        val fis = new FileInputStream(file);
        tos.putArchiveEntry(new TarArchiveEntry(file, p.relativeTo(newTaskDir).toString()))
        IOUtils.copy(fis, tos)
        tos.closeArchiveEntry()
      }
    })
    tos.close()
    uploadIO(compressedPath, taskDir.relativeTo(outDir).toString, hashCode) //TODO rewrite some of this to get hash above

  }

}

/**
 * As an alternative to making mill works with alternative paths another method could be to convert the contents
 * to meta.json to relative paths.
 *
 * TODO this is pretty hacky clean it up if we do want to go with this approach
 */
object RelativePatherizer {
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
