package mill.javalib.worker

import mill.api.daemon.internal.internal
import java.util.Optional
import sbt.internal.inc.Locate
import xsbti.VirtualFile
import xsbti.compile.{CompileAnalysis, DefinesClass, PerClasspathEntryLookup}

@internal
case class MockedLookup(am: VirtualFile => Optional[CompileAnalysis])
    extends PerClasspathEntryLookup {
  override def analysis(classpathEntry: VirtualFile): Optional[CompileAnalysis] =
    am(classpathEntry)

  override def definesClass(classpathEntry: VirtualFile): DefinesClass = {
    if (classpathEntry.name.toString != "rt.jar")
      Locate.definesClass(classpathEntry)
    else (_: String) => false
  }
}
