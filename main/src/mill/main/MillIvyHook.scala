package mill.main


import java.io.{ByteArrayOutputStream, File}
import java.net.URI

//import ammonite.interp.api.IvyConstructor
import ammonite.util.Util.CodeSource
import ammonite.util._
import coursierapi.{Dependency, IvyRepository, MavenRepository, Repository}

import scala.util.{Failure, Success, Try}

/**
 * Overrides the ivy hook to customize the `$ivy`-import with mill specifics:
 *
 * - interpret `$MILL_VERSION` as the mill version
 *
 * - interpret `$MILL_BIN_PLATFORM` as the mill binary platform version
 *
 * - supports the format `org::name::version` for mill plugins;
 *   which is equivalent to `org::name_mill$MILL_BIN_PLATFORM:version`
 *
 * - supports the format `org:::name::version` for mill plugins;
 *   which is equivalent to `org:::name_mill$MILL_BIN_PLATFORM:version`
 *
 * - replaces the empty version for scala dependencies as $MILL_VERSION
 */
object MillIvyHook extends ImportHook.BaseIvy(plugin = false) {
  override def resolve(
      interp: ImportHook.InterpreterInterface,
      signatures: Seq[String]
  ): Either[String, (Seq[coursierapi.Dependency], Seq[File])] = {
    val replaced = MillIvy.processMillIvyDepSignature(signatures)
    super.resolve(interp, replaced)
  }
}

object MillIvy {
  def processMillIvyDepSignature(signatures: Seq[String]): Seq[String] = {
    // replace platform notation and empty version
    val millSigs: Seq[String] = for (signature <- signatures) yield {

      if (signature.endsWith(":") && signature.count(_ == ":") == 4) signature + "$MILL_VERSION"
      //      else
      signature.split("[:]") match {
        case Array(org, "", pname, "", version)
            if org.length > 0 && pname.length > 0 && version.length > 0 =>
          s"${org}::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
        case Array(org, "", "", pname, "", version)
            if org.length > 0 && pname.length > 0 && version.length > 0 =>
          s"${org}:::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
        case Array(org, "", name) if org.length > 0 && name.length > 0 && signature.endsWith(":") =>
          s"${org}::${name}:$$MILL_VERSION"
        case _ => signature
      }
    }

    val replaced = millSigs.map(_
      .replace("$MILL_VERSION", mill.BuildInfo.millVersion)
      .replace("${MILL_VERSION}", mill.BuildInfo.millVersion)
      .replace("$MILL_BIN_PLATFORM", mill.BuildInfo.millBinPlatform)
      .replace("${MILL_BIN_PLATFORM}", mill.BuildInfo.millBinPlatform))

    replaced
  }
}



/**
 * An extensible hook into the Ammonite REPL's import system; allows the end
 * user to hook into `import $foo.bar.{baz, qux => qua}` syntax, and in
 * response load jars or process source files before the "current" compilation
 * unit is run. Can be used to load script files, ivy dependencies, jars, or
 * files from the web.
 */
trait ImportHook{
  /**
   * Handle a parsed import that this import hook was registered to be interested in
   *
   * Note that `source` is optional; not every piece of code has a source. Most *user*
   * code does, e.g. a repl session is based in their CWD, a script has a path, but
   * some things like hardcoded builtin predefs don't
   */
  def handle(source: CodeSource,
             tree: ImportTree,
             interp: ImportHook.InterpreterInterface,
             wrapperPath: Seq[Name])
  : Either[String, Seq[ImportHook.Result]]
}

object ImportHook{

  val defaults = Map[Seq[String], ImportHook](
    Seq("url") -> URL,
    Seq("file") -> File,
    Seq("exec") -> Exec,
    Seq("repo") -> Repo,
    Seq("ivy") -> Ivy,
    Seq("cp") -> Classpath,
    Seq("plugin", "ivy") -> PluginIvy,
    Seq("plugin", "cp") -> PluginClasspath
  )

  /**
   * The minimal interface that is exposed to the import hooks from the
   * Interpreter. Open for extension, if someone needs more stuff, but by
   * default this is what is available.
   */
  trait InterpreterInterface {
    def loadIvy(coordinates: Dependency*): Either[String, Seq[File]]
    def watch(p: os.Path): Unit
    def scalaVersion: String
  }

  /**
   * The result of processing an [[ImportHook]]. Can be either a source-file
   * to evaluate, or additional files/folders/jars to put on the classpath
   */
  sealed trait Result
  object Result{
    case class Source(code: String,
                      codeSource: CodeSource,
                      hookImports: Imports,
                      exec: Boolean) extends Result
    case class ClassPath(
                          origin: Option[Seq[coursierapi.Dependency]],
                          files: Seq[os.Path],
                          plugin: Boolean
                        ) extends Result
    case class Repo(repo: coursierapi.Repository) extends Result
  }

  object File extends SourceHook(false)
  object Exec extends SourceHook(true)

  def resolveFiles(tree: ImportTree, currentScriptPath: os.Path, extensions: Seq[String])
  : (Seq[(os.RelPath, Option[String])], Seq[os.Path], Seq[os.Path]) = {
    val relative =
      if (tree.prefix.isEmpty) os.rel
      else
        tree.prefix
          .map{case ammonite.util.Util.upPathSegment => os.up; case x => os.rel/x}
          .reduce(_/_)

    val relativeModules = tree.mappings match{
      case None => Seq(relative -> None)
      case Some(mappings) => for((k, v) <- mappings) yield relative/k -> v
    }

    def relToFile(relative: os.RelPath) = {
      val base = currentScriptPath/os.up/relative
      extensions.find(ext => os.exists(base/os.up/(relative.last + ext))) match{
        case Some(p) => Right(base/os.up/(relative.last + p): os.Path)
        case None => Left(base)
      }
    }

    val resolved = relativeModules.map(x => relToFile(x._1))
    val missing = resolved.collect{case Left(p) => p}
    val files = resolved.collect{case Right(p) => p}

    (relativeModules, files, missing)
  }
  class SourceHook(exec: Boolean) extends ImportHook {
    // import $file.foo.Bar, to import the file `foo/Bar.sc`
    def handle(source: CodeSource,
               tree: ImportTree,
               interp: InterpreterInterface,
               wrapperPath: Seq[Name]) = {

      source.path match{
        case None => Left("Cannot resolve $file import in code without source")
        case Some(currentScriptPath) =>

          val (relativeModules, files, missing) = resolveFiles(
            tree, currentScriptPath, Seq(".sc")
          )

          files.foreach(interp.watch)
          missing.foreach(x => interp.watch(x/os.up/(x.last + ".sc")))
          if (missing.nonEmpty) {
            Left("Cannot resolve $file import: " + missing.map(_ + ".sc").mkString(", "))
          } else {
            Right(
              for(((relativeModule, rename), filePath) <- relativeModules.zip(files)) yield {

                val (flexiblePkg, wrapper) = Util.pathToPackageWrapper(
                  source.flexiblePkgName, filePath relativeTo currentScriptPath/os.up
                )

                val fullPrefix = source.pkgRoot ++ flexiblePkg ++ Seq(wrapper) ++ wrapperPath

                val importData = Seq(ImportData(
                  fullPrefix.last, Name(rename.getOrElse(relativeModule.last)),
                  fullPrefix.dropRight(1), ImportData.TermType
                ))

                val codeSrc = CodeSource(
                  wrapper,
                  flexiblePkg,
                  source.pkgRoot,
                  Some(filePath)
                )

                Result.Source(
                  Util.normalizeNewlines(os.read(filePath)),
                  codeSrc,
                  Imports(importData),
                  exec
                )
              }
            )
          }
      }

    }
  }

  object Ivy extends BaseIvy(plugin = false)
  object PluginIvy extends BaseIvy(plugin = true)
  class BaseIvy(plugin: Boolean) extends ImportHook{
    def splitImportTree(tree: ImportTree): Either[String, Seq[String]] = {
      tree match{
        case ImportTree(Seq(part), None, _, _) => Right(Seq(part))
        case ImportTree(Nil, Some(mapping), _, _) if mapping.map(_._2).forall(_.isEmpty) =>
          Right(mapping.map(_._1))
        case _ => Left("Invalid $ivy import " + tree)
      }
    }
    def resolve(
                 interp: InterpreterInterface,
                 signatures: Seq[String]
               ): Either[String, (Seq[Dependency], Seq[File])] = {
      val splitted = for (signature <- signatures) yield {
        val (dottyCompat, coords) =
          if (signature.endsWith(" compat")) (true, signature.stripSuffix(" compat"))
          else (false, signature)
        coords.split(':') match{
          case Array(a, b, c) =>
            Right(Dependency.of(a, b, c))
          case Array(a, "", b, c) =>
            val sbv =
              if (dottyCompat && interp.scalaVersion.startsWith("3.")) "2.13"
              else IvyConstructor.scalaBinaryVersion(interp.scalaVersion)
            Right(Dependency.of(a, b + "_" + sbv, c))
          case Array(a, "", "", b, c) =>
            val sv =
            // FIXME We may need to bump that version from time to time, or
            // to use a different one, depending on the 3.x version.
              if (dottyCompat && interp.scalaVersion.startsWith("3."))
              // Should be the 2.13 version we want
                scala.util.Properties.versionNumberString
              else
                interp.scalaVersion
            Right(Dependency.of(a, b + "_" + sv, c))
          case _ => Left(signature)
        }
      }
      val errors = splitted.collect{case Left(error) => error}
      val successes = splitted.collect{case Right(v) => v}
      if (errors.nonEmpty)
        Left("Invalid $ivy imports: " + errors.map(Util.newLine + "  " + _).mkString)
      else
        interp.loadIvy(successes: _*).map((successes, _))
    }


    def handle(source: CodeSource,
               tree: ImportTree,
               interp: InterpreterInterface,
               wrapperPath: Seq[Name]): Either[String, Seq[Result.ClassPath]] = for{
      signatures <- splitImportTree(tree)
      depsResolved <- resolve(interp, signatures)
      (deps, resolved) = depsResolved
    } yield Seq(Result.ClassPath(Some(deps), resolved.map(os.Path(_)).toSeq, plugin))
  }
  object Classpath extends BaseClasspath(plugin = false)
  object PluginClasspath extends BaseClasspath(plugin = true)
  class BaseClasspath(plugin: Boolean) extends ImportHook{
    def handle(source: CodeSource,
               tree: ImportTree,
               interp: InterpreterInterface,
               wrapperPath: Seq[Name]): Either[String, Seq[Result]] = {
      source.path match{
        case None => Left("Cannot resolve $cp import in code without source")
        case Some(currentScriptPath) =>
          val (relativeModules, files, missing) = resolveFiles(
            tree, currentScriptPath, Seq(".jar", "")
          )

          if (missing.nonEmpty)
            Left("Cannot resolve $cp import: " + missing.mkString(", "))
          else
            Right(Seq(Result.ClassPath(None, files, plugin)))
      }

    }
  }

  object URL extends ImportHook {

    private def resolveURLs(tree: ImportTree): Seq[(URI, String)] = {
      tree.mappings match {
        case Some(mappings) if tree.prefix.isEmpty =>
          mappings.map {
            case (_, None) =>
              throw new IllegalArgumentException("$url import failed")
            case (key, Some(rename)) =>
              val uri = new URI(key)
              if (uri.isAbsolute) {
                (uri, rename)
              } else {
                throw new IllegalArgumentException("$url import failed")
              }
          }
        case _ =>
          throw new IllegalArgumentException("$url import failed")
      }
    }

    override def handle(source: CodeSource,
                        tree: ImportTree,
                        interp: InterpreterInterface,
                        wrapperPath: Seq[Name]): Either[String, Seq[Result]] = {
      Try(resolveURLs(tree)) match {
        case Failure(e) =>
          Left(e.getMessage)
        case Success(urlMappings) =>
          Right(urlMappings.map {
            case (uri, rename) =>
              val inputStream = uri.toURL.openStream()
              val code = try {
                val baos = new ByteArrayOutputStream()
                os.Internals.transfer(inputStream, baos)
                new String(baos.toByteArray)
              } finally{
                inputStream.close()
              }

              val codeSrc = CodeSource(
                Name(uri.toString),
                Nil,
                source.pkgRoot,
                None
              )

              val fullPrefix = source.pkgRoot ++ Seq(Name(uri.toString)) ++ wrapperPath
              val importData = Seq(ImportData(
                fullPrefix.last, Name(rename),
                fullPrefix.dropRight(1), ImportData.TermType
              ))
              Result.Source(
                Util.normalizeNewlines(code),
                codeSrc,
                Imports(importData),
                exec=false
              )
          })
      }
    }
  }

  object Repo extends ImportHook {
    override def handle(source: CodeSource,
                        tree: ImportTree,
                        interp: InterpreterInterface,
                        wrapperPath: Seq[Name]) = {
      val urlOpt = (tree.prefix.iterator ++ tree.mappings.iterator.flatMap(_.map(_._1).iterator))
        .toStream
        .headOption
      urlOpt match {
        case Some(url) if url.startsWith("ivy:") =>
          val repo = IvyRepository.of(url.drop(4)) // dropping `ivy:` prefix
          Right(Seq(Result.Repo(repo)))
        case Some(url) =>
          val repo = MavenRepository.of(url)
          Right(Seq(Result.Repo(repo)))
        case None =>
          throw new IllegalArgumentException("$repo import failed")
      }
    }
  }

}


import coursierapi.{Dependency, Module}

case class ScalaVersion(value: String)

object IvyConstructor extends IvyConstructor {

  def scalaBinaryVersion(sv: String) = {
    val retain = if (sv.startsWith("2")) 2 else 1
    sv.split('.').take(retain).mkString(".")
  }

}
trait IvyConstructor{
  implicit class GroupIdExt(groupId: String){
    def %(artifactId: String) = Module.of(groupId, artifactId)
    def %%(artifactId: String)(implicit sv: ScalaVersion) = Module.of(
      groupId,
      artifactId + "_" + IvyConstructor.scalaBinaryVersion(sv.value)
    )
  }
  implicit class ArtifactIdExt(t: Module){
    def %(version: String) = Dependency.of(t, version)
  }
}
