package mill
package contrib.scalapblib

import coursier.MavenRepository
import coursier.core.Version
import mill.api.{IO, Loose, PathRef}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._

import java.util.zip.ZipInputStream
import scala.util.Using

/** @see [[http://www.lihaoyi.com/mill/page/contrib-modules.html#scalapb ScalaPB Module]] */
trait ScalaPBModule extends ScalaModule {

  override def generatedSources = T { super.generatedSources() :+ compileScalaPB() }

  override def ivyDeps = T {
    super.ivyDeps() ++
      Agg(ivy"com.thesamet.scalapb::scalapb-runtime::${scalaPBVersion()}") ++
      (if (!scalaPBGrpc()) Agg()
       else Agg(ivy"com.thesamet.scalapb::scalapb-runtime-grpc:${scalaPBVersion()}"))
  }

  def scalaPBVersion: T[String]

  def scalaPBFlatPackage: T[Boolean] = T { false }

  def scalaPBJavaConversions: T[Boolean] = T { false }

  def scalaPBGrpc: T[Boolean] = T { true }

  def scalaPBSingleLineToProtoString: T[Boolean] = T { false }

  /** ScalaPB enables lenses by default, this option allows you to disable it. */
  def scalaPBLenses: T[Boolean] = T { true }

  def scalaPBSearchDeps: Boolean = false

  /**
   * Additional arguments for scalaPBC.
   *
   *  If you'd like to pass additional arguments to the ScalaPB compiler directly,
   *  you can override this task.
   *
   *  @see See [[http://www.lihaoyi.com/mill/page/contrib-modules.html#scalapb Configuration Options]] to
   *       know more.
   *  @return a sequence of Strings representing the additional arguments to append
   *          (defaults to empty Seq[String]).
   */
  def scalaPBAdditionalArgs: T[Seq[String]] = T { Seq.empty[String] }

  def scalaPBProtocPath: T[Option[String]] = T { None }

  def scalaPBSources: T[Seq[PathRef]] = T.sources {
    millSourcePath / "protobuf"
  }

  def scalaPBOptions: T[String] = T {
    (
      (if (scalaPBFlatPackage()) Seq("flat_package") else Seq.empty) ++
        (if (scalaPBJavaConversions()) Seq("java_conversions") else Seq.empty) ++
        (if (!scalaPBLenses()) Seq("no_lenses") else Seq.empty) ++
        (if (scalaPBGrpc()) Seq("grpc") else Seq.empty) ++ (
          if (!scalaPBSingleLineToProtoString()) Seq.empty
          else {
            if (Version(scalaPBVersion()) >= Version("0.7.0"))
              Seq("single_line_to_proto_string")
            else
              Seq("single_line_to_string")
          }
        )
    ).mkString(",")
  }

  def scalaPBClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(
        coursier.LocalRepositories.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2")
      ),
      Seq(ivy"com.thesamet.scalapb::scalapbc:${scalaPBVersion()}")
        .map(Lib.depToBoundDep(_, "2.13.1"))
    )
  }

  def scalaPBIncludePath: T[Seq[PathRef]] = T.sources { Seq.empty[PathRef] }

  private def scalaDepsPBIncludePath = if (scalaPBSearchDeps) T { Seq(scalaPBUnpackProto()) }
  else T { Seq.empty[PathRef] }

  def scalaPBProtoClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task { transitiveCompileIvyDeps() ++ transitiveIvyDeps() })()
  }

  def scalaPBUnpackProto: T[PathRef] = T {
    val cp = scalaPBProtoClasspath()
    val dest = T.dest
    cp.iterator.foreach { ref =>
      Using(new ZipInputStream(ref.path.getInputStream)) { zip =>
        while ({
          Option(zip.getNextEntry) match {
            case None => false
            case Some(entry) =>
              if (entry.getName.endsWith(".proto")) {
                val protoDest = dest / os.SubPath(entry.getName)
                if (os.exists(protoDest))
                  T.log.error(s"Warning: Overwriting ${dest} / ${os.SubPath(entry.getName)} ...")
                Using.resource(os.write.over.outputStream(protoDest, createFolders = true)) { os =>
                  IO.stream(zip, os)
                }
              }
              zip.closeEntry()
              true
          }
        }) ()
      }
    }
    PathRef(dest)
  }

  /*
   * options passing to ScalaPBC **except** `--scala_out=...`, `--proto_path=source_parent` and `source`
   */
  def scalaPBCompileOptions: T[Seq[String]] = T {
    ScalaPBWorkerApi.scalaPBWorker().compileOptions(
      scalaPBProtocPath(),
      (scalaPBIncludePath() ++ scalaDepsPBIncludePath()).map(_.path),
      scalaPBAdditionalArgs()
    )
  }

  def compileScalaPB: T[PathRef] = T.persistent {
    ScalaPBWorkerApi.scalaPBWorker()
      .compile(
        scalaPBClasspath(),
        scalaPBSources().map(_.path),
        scalaPBOptions(),
        T.dest,
        scalaPBCompileOptions()
      )
  }
}
