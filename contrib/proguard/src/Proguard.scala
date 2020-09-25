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
  def proguardVersion: T[String] = T { "4.4" }

  def shrink: T[Boolean] = T { true }

  def optimize: T[Boolean] = T { true }

  def obfuscate: T[Boolean] = T { true }

  def preverify: T[Boolean] = T { true }

  // maybe this should be the assembly() target instead?
  def inJars: T[Seq[PathRef]] = T { localClasspath() }

  def outJar: T[PathRef] = T { PathRef(T.dest / "out.jar") }

  def libraryJars: T[Seq[PathRef]] = T { upstreamAssemblyClasspath().iterator.to(Seq) }

  def proguard: T[PathRef] = T {
    os
      .proc(
        "java -jar",
        proguardClasspath().indexed.head.path,
        "-injars",
        inJars().map(_.path),
        "-outjars",
        outJar().path,
        "-libraryjars",
        libraryJars().map(_.path),
        steps(),
        additionalOptions()
      )
      .call(stdout = T.dest / "stdout.txt", stderr = T.dest / "stderr.txt")

    // the call above already throws an exception on a non-zero exit code
    outJar()
  }

  def proguardClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(Repositories.central),
      Lib.depToDependencyJava(_),
      Seq(ivy"net.sf.proguard:proguard:${proguardVersion()}"))
  }

  def steps: T[Seq[String]] = T {
    (if (optimize()) Seq() else Seq("-dontoptimize")) ++
      (if (obfuscate()) Seq() else Seq("-dontobfuscate")) ++
      (if (shrink()) Seq() else Seq("-dontshrink")) ++
      (if (preverify()) Seq() else Seq("-dontpreverify"))
  }

  def additionalOptions: T[Seq[String]] = T { Seq[String]() }
}
