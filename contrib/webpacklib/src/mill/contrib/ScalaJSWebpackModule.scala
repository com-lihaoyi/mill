package mill
package contrib

import java.io._
import java.util.zip.{ZipEntry, ZipInputStream}

import ammonite.ops
import mill.define.{Target, Task}
import mill.scalajslib._
import mill.util.Ctx

object ScalaJSWebpackModule {

  case class JsDeps(
      dependencies: List[(String, String)] = Nil,
      devDependencies: List[(String, String)] = Nil,
      jsSources: Map[String, String] = Map.empty
  ) {

    def ++(that: JsDeps): JsDeps =
      JsDeps(
        dependencies ++ that.dependencies,
        devDependencies ++ that.devDependencies,
        jsSources ++ that.jsSources)
  }

  object JsDeps {
    val empty: JsDeps = JsDeps()

    implicit def rw: upickle.default.ReadWriter[JsDeps] =
      upickle.default.macroRW
  }

}

trait ScalaJSWebpackModule extends ScalaJSModule {
  import ScalaJSWebpackModule._

  def webpackVersion: Target[String] = "4.43.0"

  def webpackCliVersion: Target[String] = "3.3.11"

  def webpackDevServerVersion: Target[String] = "3.11.0"

  def sourceMapLoaderVersion: Target[String] = "1.0.0"

  def bundleFilename: Target[String] = "out-bundle.js"

  def webpackFilename: Target[String] = "webpack.config.js"

  def transitiveJsDeps: Task.Sequence[JsDeps] =
    T.sequence(recursiveModuleDeps.collect {
      case mod: ScalaJSWebpackModule => mod.jsDeps
    })

  def jsDeps: Target[JsDeps] = T {
    val jsDepsFromIvyDeps =
      resolveDeps(transitiveIvyDeps)().flatMap(pathRef =>
        jsDepsFromJar(pathRef.path.toIO))
    val allJsDeps = jsDepsFromIvyDeps ++ transitiveJsDeps()
    allJsDeps.iterator.foldLeft(JsDeps.empty)(_ ++ _)
  }

  def writePackageSpec: Task[(JsDeps, ops.Path) => Unit] = T.task {
    (jsDeps: JsDeps, dst: ops.Path) =>
      val compileDeps = jsDeps.dependencies
      val compileDevDeps =
        jsDeps.devDependencies ++ Seq(
          "webpack" -> webpackVersion(),
          "webpack-cli" -> webpackCliVersion(),
          "webpack-dev-server" -> webpackDevServerVersion(),
          "source-map-loader" -> sourceMapLoaderVersion()
        )

      ops.write(
        dst / "package.json",
        ujson
          .Obj(
            "dependencies" -> compileDeps,
            "devDependencies" -> compileDevDeps)
          .render(2) + "\n")
  }

  def writeBundleSources: Task[(JsDeps, ops.Path) => Unit] = T.task {
    (jsDeps: JsDeps, dst: ops.Path) =>
      jsDeps.jsSources foreach { case (n, s) => ops.write(dst / n, s) }
  }

  def writeWpConfig: Task[(ops.Path, String, String, String, Boolean) => Unit] =
    T.task {
      (dst: ops.Path, cfg: String, io: String, out: String, opt: Boolean) =>
        ops.write(
          dst / cfg,
          "module.exports = " + ujson
            .Obj(
              "mode" -> (if (opt) "production" else "development"),
              "devtool" -> "source-map",
              "entry" -> io,
              "output" -> ujson.Obj("path" -> dst.toString, "filename" -> out))
            .render(2) + ";\n"
        )
    }

  def runWebpack: Task[(ops.Path, String) => Unit] = T.task {
    (dst: ops.Path, cfg: String) =>
      print(ops.%%("npm", "install")(dst).out.string)
      print(
        ops
          .%%(
            "node",
            dst / "node_modules" / "webpack" / "bin" / "webpack",
            "--bail",
            "--profile",
            "--config",
            cfg)(dst)
          .out
          .string)
  }

  def webpack: Task[(ops.Path, ops.Path, Boolean) => Unit] = T.task {
    (src: ops.Path, dst: ops.Path, opt: Boolean) =>
      val outjs = dst / src.segments.toSeq.last
      val deps = jsDeps()
      val cfg = webpackFilename()
      ops.cp(src, outjs)
      writeBundleSources().apply(deps, dst)
      writeWpConfig().apply(dst, cfg, outjs.toString, bundleFilename(), opt)
      writePackageSpec().apply(deps, dst)
      runWebpack().apply(dst, cfg)
      ops.rm(outjs)
  }

  def fastOptWp: Target[PathRef] = T {
    val dst = Ctx.taskCtx.dest
    webpack().apply(fastOpt().path, dst, false)
    PathRef(dst)
  }

  def fullOptWp: Target[PathRef] = T {
    val dst = Ctx.taskCtx.dest
    webpack().apply(fullOpt().path, dst, true)
    PathRef(dst)
  }

  @scala.annotation.tailrec
  private def readStringFromInputStream(
      in: InputStream,
      buffer: Array[Byte] = new Array[Byte](8192),
      out: ByteArrayOutputStream = new ByteArrayOutputStream): String = {
    val byteCount = in.read(buffer)
    if (byteCount < 0) {
      out.toString
    } else {
      out.write(buffer, 0, byteCount)
      readStringFromInputStream(in, buffer, out)
    }
  }

  private def collectZipEntries[R](jar: File)(
      f: PartialFunction[(ZipEntry, ZipInputStream), R]): List[R] = {
    val stream = new ZipInputStream(
      new BufferedInputStream(new FileInputStream(jar)))
    try Iterator
      .continually(stream.getNextEntry)
      .takeWhile(_ != null)
      .map(_ -> stream)
      .collect(f)
      .toList
    finally stream.close()
  }

  private def jsDepsFromJar(jar: File): Seq[JsDeps] = {
    collectZipEntries(jar) {
      case (zipEntry, stream) if zipEntry.getName == "NPM_DEPENDENCIES" =>
        val contentsAsJson = ujson.read(readStringFromInputStream(stream)).obj

        def dependenciesOfType(key: String): List[(String, String)] =
          contentsAsJson
            .getOrElse(key, ujson.Arr())
            .arr
            .flatMap(_.obj.map {
              case (s: String, v: ujson.Value) => s -> v.str
            })
            .toList

        JsDeps(
          dependenciesOfType("compileDependencies") ++ dependenciesOfType(
            "compile-dependencies"),
          dependenciesOfType("compileDevDependencies") ++ dependenciesOfType(
            "compile-devDependencies")
        )
      case (zipEntry, stream)
          if zipEntry.getName.endsWith(".js") && !zipEntry.getName.startsWith(
            "scala/") =>
        JsDeps(jsSources =
          Map(zipEntry.getName -> readStringFromInputStream(stream)))
    }
  }

}
