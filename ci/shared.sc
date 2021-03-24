/**
  * Utility code that is shared between our SBT build and our Mill build. SBT
  * calls this by shelling out to Ammonite in a subprocess, while Mill loads it
  * via import $file
  */

import $ivy.`org.scalaj::scalaj-http:2.4.2`
import ammonite.ops.{RelPath, mkdir, write}
import os.{Path,up}

def argNames(n: Int) = {
  val uppercases = (0 until n).map("T" + _)
  val lowercases = uppercases.map(_.toLowerCase)
  val typeArgs   = uppercases.mkString(", ")
  val zipArgs    = lowercases.mkString(", ")
  (lowercases, uppercases, typeArgs, zipArgs)
}
def generateApplyer(dir: Path) = {
  def generate(n: Int) = {
    val (lowercases, uppercases, typeArgs, zipArgs) = argNames(n)
    val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")

    val body   = s"mapCtx(zip($zipArgs)) { case (($zipArgs), z) => cb($zipArgs, z) }"
    val zipmap = s"def zipMap[$typeArgs, Res]($parameters)(cb: ($typeArgs, Ctx) => Z[Res]) = $body"
    val zip    = s"def zip[$typeArgs]($parameters): TT[($typeArgs)]"

    if (n < 22) List(zipmap, zip).mkString("\n") else zip
  }
  write(
    dir / "ApplicativeGenerated.scala",
    s"""package mill.define
      |import scala.language.higherKinds
      |trait ApplyerGenerated[TT[_], Z[_], Ctx] {
      |  def mapCtx[A, B](a: TT[A])(f: (A, Ctx) => Z[B]): TT[B]
      |  ${(2 to 22).map(generate).mkString("\n")}
      |}""".stripMargin
  )
}

def generateTarget(dir: Path) = {
  def generate(n: Int) = {
    val (lowercases, uppercases, typeArgs, zipArgs) = argNames(n)
    val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")
    val body       = uppercases.zipWithIndex.map { case (t, i) => s"args[$t]($i)" }.mkString(", ")

    s"def zip[$typeArgs]($parameters) = makeT[($typeArgs)](Seq($zipArgs), (args: mill.api.Ctx) => ($body))"
  }

  write(
    dir / "TaskGenerated.scala",
    s"""package mill.define
       |import scala.language.higherKinds
       |trait TargetGenerated {
       |  type TT[+X]
       |  def makeT[X](inputs: Seq[TT[_]], evaluate: mill.api.Ctx => mill.api.Result[X]): TT[X]
       |  ${(3 to 22).map(generate).mkString("\n")}
       |}""".stripMargin
  )
}

def generateEval(dir: Path) = {
  def generate(n: Int) = {
    val (lowercases, uppercases, typeArgs, zipArgs) = argNames(n)
    val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: TT[$upper]" }.mkString(", ")
    val extract    = uppercases.zipWithIndex.map { case (t, i) =>  s"result($i).asInstanceOf[$t]" }.mkString(", ")

    s"""def eval[$typeArgs]($parameters):($typeArgs) = {
       |  val result = evaluator.evaluate(Agg($zipArgs)).values
       |  (${extract})
       |}
     """.stripMargin
  }

  write(
    dir / "EvalGenerated.scala",
    s"""package mill.main
       |import mill.eval.Evaluator
       |import mill.define.Task
       |import mill.api.Strict.Agg
       |class EvalGenerated(evaluator: Evaluator) {
       |  type TT[+X] = Task[X]
       |  ${(1 to 22).map(generate).mkString("\n")}
       |}""".stripMargin
  )
}

def generateApplicativeTest(dir: Path) = {
  def generate(n: Int): String = {
    val (lowercases, uppercases, typeArgs, zipArgs) = argNames(n)
    val parameters = lowercases.zip(uppercases).map { case (lower, upper) => s"$lower: Option[$upper]" }.mkString(", ")
    val forArgs = lowercases.map(i => s"$i <- $i").mkString("; ")
    s"def zip[$typeArgs]($parameters) = { for ($forArgs) yield ($zipArgs) }"
  }

  write(
    dir / "ApplicativeTestsGenerated.scala",
    s"""package mill.define
       |trait OptGenerated {
       |  ${(2 to 22).map(generate).mkString("\n")}
       |}
    """.stripMargin
  )
}

def unpackZip(zipDest: Path, url: String) = {
  println(s"Unpacking zip $url into $zipDest")
  mkdir(zipDest)

  val bytes = scalaj.http.Http.apply(url).option(scalaj.http.HttpOptions.followRedirects(true)).asBytes
  val byteStream = new java.io.ByteArrayInputStream(bytes.body)
  val zipStream = new java.util.zip.ZipInputStream(byteStream)
  while({
    zipStream.getNextEntry match{
      case null => false
      case entry =>
        if (!entry.isDirectory) {
          val dest = zipDest / RelPath(entry.getName)
          mkdir(dest / up)
          val fileOut = new java.io.FileOutputStream(dest.toString)
          val buffer = new Array[Byte](4096)
          while ( {
            zipStream.read(buffer) match {
              case -1 => false
              case n =>
                fileOut.write(buffer, 0, n)
                true
            }
          }) ()
          fileOut.close()
        }
        zipStream.closeEntry()
        true
    }
  })()
}

@main
def generateCoreSources(p: Path) = {
  generateApplyer(p)
  generateTarget(p)
  generateEval(p)
  p
}

@main
def generateCoreTestSources(p: Path) = {
  generateApplicativeTest(p)
  p
}


@main
def downloadTestRepo(label: String, commit: String, dest: Path) = {
  unpackZip(dest, s"https://github.com/$label/archive/$commit.zip")
  dest
}

/**
  * Copy of os-lib copy utility providing an additional `mergeFolders` option.
  * See pr https://github.com/com-lihaoyi/os-lib/pull/65
  */
object mycopy {
  import os._
  import java.nio.file
  import java.nio.file.{CopyOption, LinkOption, StandardCopyOption, Files}
  def apply(
             from: Path,
             to: Path,
             followLinks: Boolean = true,
             replaceExisting: Boolean = false,
             copyAttributes: Boolean = false,
             createFolders: Boolean = false,
             mergeFolders: Boolean = false
           ): Unit = {
    if (createFolders) makeDir.all(to / up)
    val opts1 =
      if (followLinks) Array[CopyOption]()
      else Array[CopyOption](LinkOption.NOFOLLOW_LINKS)
    val opts2 =
      if (replaceExisting) Array[CopyOption](StandardCopyOption.REPLACE_EXISTING)
      else Array[CopyOption]()
    val opts3 =
      if (copyAttributes) Array[CopyOption](StandardCopyOption.COPY_ATTRIBUTES)
      else Array[CopyOption]()
    require(
      !to.startsWith(from),
      s"Can't copy a directory into itself: $to is inside $from"
    )

    def copyOne(p: Path): file.Path = {
      val target = to / p.relativeTo(from)
      if (mergeFolders && isDir(p, followLinks) && isDir(target, followLinks)) {
        // nothing to do
        target.wrapped
      } else {
        Files.copy(p.wrapped, target.wrapped, opts1 ++ opts2 ++ opts3: _*)
      }
    }

    copyOne(from)
    if (stat(from, followLinks).isDir) walk(from).map(copyOne)
  }

  object into {
    def apply(
               from: Path,
               to: Path,
               followLinks: Boolean = true,
               replaceExisting: Boolean = false,
               copyAttributes: Boolean = false,
               createFolders: Boolean = false,
               mergeFolders: Boolean = false
             ): Unit = {
      mycopy(from, to / from.last, followLinks, replaceExisting, copyAttributes, createFolders, mergeFolders)
    }
  }
}