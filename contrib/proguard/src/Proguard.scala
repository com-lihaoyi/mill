package mill.contrib.proguard

import coursier.Repositories
import mill.T
import mill.Agg
import mill.api.{Logger, Loose, PathRef, Result}
import mill.define.{Sources, Target}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule}
import os.proc
import os.Path
import os.PathChunk

trait Proguard extends ScalaModule {
  def proguardVersion: T[String] = T { "7.0.0" }

  def shrink: T[Boolean] = T { true }
  def optimize: T[Boolean] = T { true }
  def obfuscate: T[Boolean] = T { true }
  def preverify: T[Boolean] = T { true }

  def inJar: T[PathRef] = T { assembly() }
  def outJar: T[PathRef] = T { PathRef(T.dest / "out.jar") }
  def libraryJars: T[Seq[PathRef]] = T {
    upstreamAssemblyClasspath().toSeq
  }

  def proguard: T[PathRef] = T {
    val cmd = os.proc(
        "java",
        "-cp",
        proguardClasspath().map(_.path).mkString(":"),
        "proguard.ProGuard",
        "-injars",
        inJar().path,
        "-outjars",
        outJar().path,
        "-libraryjars",
        libraryJars().map(_.path),
        steps(),
        additionalOptions()
      )
    System.out.println("Running command: " + cmd.command.flatMap(_.value).mkString(" "))
    cmd.call(stdout = T.dest / "stdout.txt", stderr = T.dest / "stderr.txt")

    // the call above already throws an exception on a non-zero exit code,
    // so if we reached this point we've succeeded!
    outJar()
  }

  def proguardClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(Repositories.jcenter),
      Lib.depToDependencyJava(_),
      Seq(ivy"com.guardsquare:proguard-base:${proguardVersion()}"))
  }

  def steps: T[Seq[String]] = T {
    (if (optimize()) Seq() else Seq("-dontoptimize")) ++
      (if (obfuscate()) Seq() else Seq("-dontobfuscate")) ++
      (if (shrink()) Seq() else Seq("-dontshrink")) ++
      (if (preverify()) Seq() else Seq("-dontpreverify"))
  }

  def additionalOptions: T[Seq[String]] = T { Seq[String]() }
}
