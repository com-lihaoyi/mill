package mill.javalib.zinc

import utest.*
import xsbti.compile.analysis.ReadWriteMappers

object ZincWorkerPathMappingTests extends TestSuite {

  def tests: Tests = Tests {
    test("compilerOptionsRoundTripAcrossWorkspaces") {
      val base = os.temp.dir()
      val original = base / "original workspace"
      val relocated = base / "relocated workspace"
      val originalEscaped = original.toString.replace(" ", "\\ ")
      val relocatedEscaped = relocated.toString.replace(" ", "\\ ")

      val write = ZincWorker
        .relativePassthroughMappers(ReadWriteMappers.getEmptyMappers, original)
        .getWriteMapper
      val read = ZincWorker
        .relativePassthroughMappers(ReadWriteMappers.getEmptyMappers, relocated)
        .getReadMapper

      val scalacOption =
        s"-Xplugin:semanticdb -sourceroot:$original -targetroot:$original/out/classes -build-tool:mill"
      val storedScalacOption = write.mapScalacOption(scalacOption)
      val relocatedScalacOption = read.mapScalacOption(storedScalacOption)

      val javacOption = s"-sourceroot:$originalEscaped"
      val storedJavacOption = write.mapJavacOption(javacOption)
      val relocatedJavacOption = read.mapJavacOption(storedJavacOption)

      val siblingOption = s"-sourceroot:${original}sibling"
      val spacedSiblingOption = s"-sourceroot:$original sibling"
      val embeddedOption = s"-Dvalue=prefix-sourceroot:$original"
      val literalPlaceholder = "-Droot=${MILL_WORKSPACE_ROOT}"

      assert(
        !storedScalacOption.contains(original.toString),
        relocatedScalacOption == scalacOption.replace(original.toString, relocated.toString),
        !storedJavacOption.contains(originalEscaped),
        relocatedJavacOption == s"-sourceroot:$relocatedEscaped",
        write.mapScalacOption(siblingOption) == siblingOption,
        write.mapScalacOption(spacedSiblingOption) == spacedSiblingOption,
        write.mapScalacOption(embeddedOption) == embeddedOption,
        read.mapScalacOption(literalPlaceholder) == literalPlaceholder
      )
    }
  }
}
