package mill.javalib.zinc

import mill.javalib.api.internal.ZincOp
import xsbt.api.Discovery
import mill.javalib.testrunner.Framework
import sbt.testing.SubclassFingerprint
import sbt.testing.AnnotatedFingerprint
import sbt.internal.inc.FileAnalysisStore
import sbt.internal.inc.Analysis
import xsbti.api.Definition
import mill.api.daemon.internal.NonFatal
import sbt.testing.Fingerprint
import xsbt.api.Discovered
import xsbti.api.ClassLike

object TestDiscovery {
  def apply(op: ZincOp.DiscoverTestsZinc): Seq[(String, Int)] = {
    import op.*
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      val frameworkInstance = Framework.framework(framework)(classLoader)
      val fingerprints = frameworkInstance.fingerprints()

      val analysis = FileAnalysisStore.binary(analysisFile.toIO)
        .get()
        .get()
        .getAnalysis
        .asInstanceOf[Analysis]

      discover(fingerprints, allDefs(analysis))
    }
  }

  // Initially adapted from https://github.com/sbt/sbt/blob/f64b5288ca0711c84a0345276441671d6f3b240e/main-actions/src/main/scala/sbt/Tests.scala#L547-L586
  private def discover(
      fingerprints: Seq[Fingerprint],
      definitions: Seq[Definition]
  ): Seq[(String, Int)] = {
    val subclasses = fingerprints
      .collect {
        case sub: SubclassFingerprint =>
          sub.superclassName
      }
      .toSet
    val annotations = fingerprints
      .collect {
        case ann: AnnotatedFingerprint =>
          ann.annotationName
      }
      .toSet

    def toFingerprintIndices(d: Discovered): Seq[Int] =
      fingerprints.zipWithIndex.collect {
        case (sub: SubclassFingerprint, idx)
            if sub.isModule == d.isModule && d.baseClasses.contains(sub.superclassName) =>
          idx
        case (ann: AnnotatedFingerprint, idx)
            if ann.isModule == d.isModule && d.annotations.contains(ann.annotationName) =>
          idx
      }

    val discovered = Discovery(subclasses, annotations)(
      definitions.filter {
        case c: ClassLike =>
          c.topLevel
        case _ => false
      }
    )

    for {
      (df, di) <- discovered
      fingerprintIdx <- toFingerprintIndices(di)
    } yield (df.name, fingerprintIdx)
  }

  // Copied and adapted from https://github.com/sbt/sbt/blob/f64b5288ca0711c84a0345276441671d6f3b240e/main-actions/src/main/scala/sbt/Tests.scala#L528-L546
  private def allDefs(analysis: Analysis): Seq[Definition] =
    analysis.apis.internal.values.toVector.flatMap { ac =>
      try
        Seq(ac.api.classApi, ac.api.objectApi) ++
          ac.api.classApi.structure.declared.toSeq ++
          ac.api.classApi.structure.inherited.toSeq ++
          ac.api.objectApi.structure.declared.toSeq ++
          ac.api.objectApi.structure.inherited.toSeq
      catch
        case NonFatal(e) if e.getMessage.startsWith("No companions") =>
          Nil
    }
}
