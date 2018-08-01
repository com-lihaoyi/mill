package mill
package contrib.scalapblib

import coursier.{Cache, MavenRepository}
import coursier.core.Version
import mill.define.Sources
import mill.eval.PathRef
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib._
import mill.util.Loose

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

  def scalaPBSources: Sources = T.sources {
    millSourcePath / 'protobuf
  }

  def scalaPBOptions: T[String] = T {
    (
      (if (scalaPBFlatPackage()) Seq("flat_package") else Seq.empty) ++
      (if (scalaPBJavaConversions()) Seq("java_conversions") else Seq.empty) ++
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
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2")
      ),
      Lib.depToDependency(_, "2.12.4"),
      Seq(ivy"com.thesamet.scalapb::scalapbc:${scalaPBVersion()}")
    )
  }

  def compileScalaPB: T[PathRef] = T.persistent {
    ScalaPBWorkerApi.scalaPBWorker
      .compile(
        scalaPBClasspath().map(_.path),
        scalaPBSources().map(_.path),
        scalaPBOptions(),
        T.ctx().dest)
  }
}
