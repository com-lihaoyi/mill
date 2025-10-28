package mill.javalib.api.internal

import mill.api.PathRef
import mill.javalib.api.CompilationResult
import mill.api.JsonFormatters.*
import mill.api.PathRef.Revalidate
import upickle.ReadWriter

case class LocalPath private (path: String) derives upickle.ReadWriter {
  def toOsPath: os.Path = os.Path(path)
  def toIO: java.io.File = java.io.File(path)
  def toNIO: java.nio.file.Path = java.nio.file.Paths.get(path)
  override def toString(): String = path
}
object LocalPath {
  implicit def apply(path: os.Path): LocalPath = LocalPath(path.toString)
}

case class LocalPathRef(path: LocalPath, quick: Boolean, sig: Int, revalidate: PathRef.Revalidate)
    derives upickle.ReadWriter {
  def toPathRef: PathRef = PathRef(path.toOsPath, quick, sig, revalidate)
}
object LocalPathRef {
  implicit def apply(pr: PathRef): LocalPathRef =
    LocalPathRef(pr.path, pr.quick, pr.sig, pr.revalidate)
  implicit def rwRevalidate: upickle.ReadWriter[Revalidate] = upickle.readwriter[String].bimap(
    {
      case Revalidate.Never => "Never"
      case Revalidate.Once => "Once"
      case Revalidate.Always => "Always"
    },
    {
      case "Never" => Revalidate.Never
      case "Once" => Revalidate.Once
      case "Always" => Revalidate.Always
    }
  )
}

/** Compiles Java-only sources. */
case class ZincCompileJava(
    upstreamCompileOutput: Seq[CompilationResult],
    sources: Seq[LocalPath],
    compileClasspath: Seq[LocalPath],
    javacOptions: JavaCompilerOptions,
    incrementalCompilation: Boolean
) derives upickle.ReadWriter

/** Compiles Java and Scala sources. */
case class ZincCompileMixed(
    upstreamCompileOutput: Seq[CompilationResult],
    sources: Seq[LocalPath],
    compileClasspath: Seq[LocalPath],
    javacOptions: JavaCompilerOptions,
    scalaVersion: String,
    scalaOrganization: String,
    scalacOptions: Seq[String],
    compilerClasspath: Seq[LocalPathRef],
    scalacPluginClasspath: Seq[LocalPathRef],
    incrementalCompilation: Boolean,
    auxiliaryClassFileExtensions: Seq[String]
) derives upickle.ReadWriter

/** Creates a Scaladoc jar. */
case class ZincScaladocJar(
    scalaVersion: String,
    scalaOrganization: String,
    compilerClasspath: Seq[LocalPathRef],
    scalacPluginClasspath: Seq[LocalPathRef],
    args: Seq[String]
) derives upickle.ReadWriter
