package forge

import java.io.FileOutputStream
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.jar.JarEntry

import ammonite.ops.{Path, ls, pwd, read}
import forge.util.{Args, OSet, PathRef}
import utest._

object IntegrationTests extends TestSuite{
  def compileAll(sources: Target[Seq[PathRef]])  = {
    new Target.Subprocess(
      Seq(sources),
      args =>
        Seq("javac") ++
          args[Seq[PathRef]](0).map(_.path.toString) ++
          Seq("-d", args.dest.toString)
    ).map(_.dest)
  }

  def list(root: Target[PathRef]): Target[Seq[PathRef]] = {
    root.map(x => ls.rec(x.path).map(PathRef(_)))
  }
  case class jarUp(roots: Target[PathRef]*) extends Target[PathRef]{

    val inputs = roots
    def evaluate(args: Args): PathRef = {

      val output = new java.util.jar.JarOutputStream(new FileOutputStream(args.dest.toIO))
      for{
        root0 <- args.args
        root = root0.asInstanceOf[PathRef]

        path <- ls.rec(root.path)
        if path.isFile
      }{
        val relative = path.relativeTo(root.path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(read.bytes(path))
      }
      output.close()
      PathRef(args.dest)
    }


  }
  val tests = Tests{
    'javac{
      object Build{
        val sourceRootPath = pwd / 'src / 'test / 'examples / 'javac / 'src
        val resourceRootPath = pwd / 'src / 'test / 'examples / 'javac / 'resources
        val sourceRoot = Target.path(sourceRootPath)
        val resourceRoot = Target.path(resourceRootPath)
        val allSources = list(sourceRoot)
        val classFiles = compileAll(allSources)
        val jar = jarUp(resourceRoot, classFiles)
      }
      import Build._
      val mapping = Discovered.mapping(Build)

      def check(targets: OSet[Target[_]], expected: OSet[Target[_]]) = {
        val evaluator = new Evaluator(pwd / 'target / 'workspace / 'javac, mapping)
        val evaluated = evaluator.evaluate(targets).evaluated.filter(mapping.contains)
        pprint.log(evaluated.map(mapping))
        assert(evaluated == expected)
      }

      def touch(path: Path) = {
        java.nio.file.Files.setLastModifiedTime(path.toNIO, FileTime.from(Instant.now()))
      }

      check(
        targets = OSet(jar),
        expected = OSet(resourceRoot, sourceRoot, allSources, classFiles, jar)
      )

      check(targets = OSet(jar), expected = OSet())

      touch(Build.resourceRootPath / "hello.txt")

      check(
        targets = OSet(jar),
        expected = OSet(resourceRoot, jar)
      )

      check(targets = OSet(jar), expected = OSet())

      touch(Build.sourceRootPath / "Foo.java")

      check(
        targets = OSet(jar),
        expected = OSet(sourceRoot, allSources, classFiles)
      )

      touch(Build.sourceRootPath / "Bar.java")
      touch(Build.resourceRootPath / "hello.txt")

      check(
        targets = OSet(classFiles),
        expected = OSet(sourceRoot, allSources, classFiles)
      )
      check(
        targets = OSet(jar),
        expected = OSet(resourceRoot, jar)
      )
    }
  }
}
