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

  /**
   * The ScalaPB version `String`, e.g. `"0.7.4"`
   */
  def scalaPBVersion: T[String]

  /**
   * The generators to use. Defaults to use [[Generator.ScalaGen]] which produces Scala files and was for a long time the only supported generator.
   */
  def scalaPBGenerators: T[Seq[Generator]] = Seq(Generator.ScalaGen)

  /**
   * A [[Boolean]] option which determines whether the `.proto` file name should be appended as the final segment of the package name in the generated sources.
   */
  def scalaPBFlatPackage: T[Boolean] = Task { false }

  /**
   * A [[Boolean]] option which determines whether methods for converting between the generated Scala classes and the Protocol Buffers Java API classes should be generated.
   */
  def scalaPBJavaConversions: T[Boolean] = Task { false }

  /**
   * A [[Boolean]] option which determines whether https://grpc.io[grpc] stubs should be generated.
   */
  def scalaPBGrpc: T[Boolean] = Task { true }

  /**
   * A [[Boolean]] option which determines whether the generated `.toString` methods should use a single line format.
   */
  def scalaPBSingleLineToProtoString: T[Boolean] = Task { false }

  /** ScalaPB enables lenses by default, this option allows you to disable it. */
  def scalaPBLenses: T[Boolean] = Task { true }

  /**
   * Generate the sources with scala 3 syntax (i. e. use `?` instead of `_` in types)
   */
  def scalaPBScala3Sources: T[Boolean] = Task { false }

  /**
   *  A [[Boolean]] option which determines whether to search for `.proto` files in dependencies and add them to [[scalaPBIncludePath]]. Defaults to `false`.
   */
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

  /**
   * A [[Option]] option which determines the protoc compiler to use. If `None`, a java embedded protoc will be used, if set to `Some` path, the given binary is used.
   */
  def scalaPBProtocPath: T[Option[String]] = Task { None }

  /**
   * Paths to search for `.proto` files and generate scala case classes for.
   * Defaults to `moduleDir / "protobuf"`
   */
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

  /**
   * Additional paths to include `.proto` files when compiling.
   */
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
      Using(ZipInputStream(ref.path.getInputStream)) { zip =>
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
