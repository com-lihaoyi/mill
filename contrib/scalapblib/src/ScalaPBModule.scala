package mill
package contrib.scalapblib

import java.net.URI
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystems, Files, Path, SimpleFileVisitor, StandardCopyOption}

import coursier.MavenRepository
import coursier.core.Version
import mill.define.Sources
import mill.api.PathRef
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.api.Loose

/** @see [[http://www.lihaoyi.com/mill/page/contrib-modules.html#scalapb ScalaPB Module]] */
trait ScalaPBModule extends ScalaModule {

  override def generatedSources = T { super.generatedSources() :+ compileScalaPB() }

  override def ivyDeps = T {
    super.ivyDeps() ++
      Agg(ivy"com.thesamet.scalapb::scalapb-runtime:${scalaPBVersion()}") ++
      (if (!scalaPBGrpc()) Agg() else Agg(ivy"com.thesamet.scalapb::scalapb-runtime-grpc:${scalaPBVersion()}"))
  }

  def scalaPBVersion: T[String]

  def scalaPBFlatPackage: T[Boolean] = T { false }

  def scalaPBJavaConversions: T[Boolean] = T { false }

  def scalaPBGrpc: T[Boolean] = T { true }

  def scalaPBSingleLineToProtoString: T[Boolean] = T { false }

  /** ScalaPB enables lenses by default, this option allows you to disable it. */
  def scalaPBLenses: T[Boolean] = T { true }

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

  def scalaPBSources: Sources = T.sources {
    millSourcePath / 'protobuf
  }

  def scalaPBOptions: T[String] = T {
    (
      (if (scalaPBFlatPackage()) Seq("flat_package") else Seq.empty) ++
      (if (scalaPBJavaConversions()) Seq("java_conversions") else Seq.empty) ++
      (if (!scalaPBLenses()) Seq("no_lenses") else Seq.empty) ++
      (if (scalaPBGrpc()) Seq("grpc") else Seq.empty) ++ (
        if (!scalaPBSingleLineToProtoString()) Seq.empty else {
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
      Lib.depToDependency(_, "2.13.1"),
      Seq(ivy"com.thesamet.scalapb::scalapbc:${scalaPBVersion()}")
    )
  }

  def scalaPBIncludePath: T[Seq[PathRef]] = T.sources { Seq.empty[PathRef] }

  def scalaPBProtoClasspath: T[Agg[PathRef]] = T {
    resolveDeps(T.task { transitiveCompileIvyDeps() ++ transitiveIvyDeps() })()
  }

  def scalaPBUnpackProto: T[PathRef] = T {
    val cp   = scalaPBProtoClasspath()
    val dest = T.dest
    cp.foreach { ref =>
      val baseUri = "jar:" + ref.path.toIO.getCanonicalFile.toURI.toASCIIString
      val jarFs =
        FileSystems.newFileSystem(URI.create(baseUri), new java.util.HashMap[String, String]())
      try {
        import scala.collection.JavaConverters._
        jarFs.getRootDirectories.asScala.foreach { r =>
          Files.walkFileTree(
            r,
            new SimpleFileVisitor[Path] {
              override def visitFile(f: Path, a: BasicFileAttributes) = {
                if (f.getFileName.toString.endsWith(".proto")) {
                  val protoDest = dest.toNIO.resolve(r.relativize(f).toString)
                  Files.createDirectories(protoDest.getParent)
                  Files.copy(
                    f,
                    protoDest,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING
                  )
                }
                super.visitFile(f, a)
              }
            }
          )
        }
      } finally jarFs.close()
    }

    PathRef(dest)
  }

  /*
   * options passing to ScalaPBC **except** `--scala_out=...`, `--proto_path=source_parent` and `source`
   */
  def scalaPBCompileOptions: T[Seq[String]] = T{
    ScalaPBWorkerApi.scalaPBWorker().compileOptions(
			scalaPBProtocPath(),
			scalaPBIncludePath().map(_.path),
			scalaPBAdditionalArgs()
    )
  }

  def compileScalaPB: T[PathRef] = T.persistent {
    ScalaPBWorkerApi.scalaPBWorker()
      .compile(
        scalaPBClasspath().map(_.path),
        scalaPBSources().map(_.path),
        scalaPBOptions(),
        T.dest,
        scalaPBCompileOptions())
  }
}
