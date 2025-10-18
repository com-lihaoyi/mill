package mill.contrib.scalapblib

import coursier.core.Version
import mill.api.{PathRef, Task}
import mill.scalalib.*
import mill.T

import java.util.zip.ZipInputStream
import scala.util.Using

/** @see [[http://www.lihaoyi.com/mill/page/contrib-modules.html#scalapb ScalaPB Module]] */
trait ScalaPBModule extends ScalaModule {

  override def generatedSources = Task { super.generatedSources() :+ compileScalaPB() }

  override def mvnDeps = Task {
    super.mvnDeps() ++
      Seq(mvn"com.thesamet.scalapb::scalapb-runtime::${scalaPBVersion()}") ++
      (if (!scalaPBGrpc()) Seq()
       else Seq(mvn"com.thesamet.scalapb::scalapb-runtime-grpc:${scalaPBVersion()}"))
  }

  def scalaPBVersion: T[String]

  def scalaPBGenerators: T[Seq[Generator]] = Seq(Generator.ScalaGen)

  def scalaPBFlatPackage: T[Boolean] = Task { false }

  def scalaPBJavaConversions: T[Boolean] = Task { false }

  def scalaPBGrpc: T[Boolean] = Task { true }

  def scalaPBSingleLineToProtoString: T[Boolean] = Task { false }

  /** ScalaPB enables lenses by default, this option allows you to disable it. */
  def scalaPBLenses: T[Boolean] = Task { true }

  def scalaPBScala3Sources: T[Boolean] = Task { false }

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
  def scalaPBAdditionalArgs: T[Seq[String]] = Task { Seq.empty[String] }

  def scalaPBProtocPath: T[Option[String]] = Task { None }

  def scalaPBSources: T[Seq[PathRef]] = Task.Sources("protobuf")

  def scalaPBOptions: T[String] = Task {
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
        ) ++
        (if (scalaPBScala3Sources()) Seq("scala3_sources") else Seq.empty)
    ).mkString(",")
  }

  def scalaPBClasspath: T[Seq[PathRef]] = Task {
    val scalaPBScalaVersion = "2.13.1"
    defaultResolver().classpath(
      Seq(mvn"com.thesamet.scalapb::scalapbc:${scalaPBVersion()}")
        .map(Lib.depToBoundDep(_, scalaPBScalaVersion)),
      resolutionParamsMapOpt = Some(_.withScalaVersion(scalaPBScalaVersion))
    )
  }

  def scalaPBIncludePath: T[Seq[PathRef]] = Task.Sources()

  private def scalaDepsPBIncludePath: Task[Seq[PathRef]] = scalaPBSearchDeps match {
    case true => Task.Anon { Seq(scalaPBUnpackProto()) }
    case false => Task.Anon { Seq.empty[PathRef] }
  }

  def scalaPBProtoClasspath: T[Seq[PathRef]] = Task {
    millResolver().classpath(
      Seq(
        coursierDependencyTask().withConfiguration(coursier.core.Configuration.provided),
        coursierDependencyTask()
      )
    )
  }

  def scalaPBUnpackProto: T[PathRef] = Task {
    val cp = scalaPBProtoClasspath()
    val dest = Task.dest
    cp.iterator.foreach { ref =>
      Using(new ZipInputStream(ref.path.getInputStream)) { zip =>
        while ({
          Option(zip.getNextEntry) match {
            case None => false
            case Some(entry) =>
              if (entry.getName.endsWith(".proto")) {
                val protoDest = dest / os.SubPath(entry.getName)
                if (os.exists(protoDest))
                  Task.log.warn(s"Overwriting ${dest} / ${os.SubPath(entry.getName)} ...")
                Using.resource(os.write.over.outputStream(protoDest, createFolders = true)) { os =>
                  _root_.os.Internals.transfer(zip, os, close = false)
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
  def scalaPBCompileOptions: T[Seq[String]] = Task {
    ScalaPBWorkerApi.scalaPBWorker().compileOptions(
      scalaPBProtocPath(),
      (scalaPBIncludePath() ++ scalaDepsPBIncludePath()).map(_.path),
      scalaPBAdditionalArgs()
    )
  }

  def compileScalaPB: T[PathRef] = Task(persistent = true) {
    ScalaPBWorkerApi.scalaPBWorker()
      .compile(
        scalaPBClasspath(),
        scalaPBSources().map(_.path),
        scalaPBOptions(),
        Task.dest,
        scalaPBCompileOptions(),
        scalaPBGenerators()
      )
  }
}
