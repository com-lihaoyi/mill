package mill.javalib.zinc

import xsbti.compile.{CompileAnalysis, ExternalHooks, FileHash}

import java.util.Optional

private[mill] case class MillExternalLookup(
    classpathHashes: Array[FileHash],
    lookupData: Option[IncrementalAnnotationProcessing.LookupData]
) extends ExternalHooks.Lookup {
  override def getChangedSources(
      previousAnalysis: CompileAnalysis
  ): Optional[xsbti.compile.Changes[xsbti.VirtualFileRef]] =
    lookupData.fold(Optional.empty[xsbti.compile.Changes[xsbti.VirtualFileRef]]())(
      _.changedSources
    )

  override def getChangedBinaries(
      previousAnalysis: CompileAnalysis
  ): Optional[java.util.Set[xsbti.VirtualFileRef]] =
    Optional.empty()

  override def getRemovedProducts(
      previousAnalysis: CompileAnalysis
  ): Optional[java.util.Set[xsbti.VirtualFileRef]] =
    lookupData.fold(Optional.empty[java.util.Set[xsbti.VirtualFileRef]]())(_.removedProducts)

  override def shouldDoIncrementalCompilation(
      changedClasses: java.util.Set[String],
      previousAnalysis: CompileAnalysis
  ): Boolean = true

  override def hashClasspath(
      classpath: Array[xsbti.VirtualFile]
  ): Optional[Array[FileHash]] =
    Optional.of(classpathHashes)
}
