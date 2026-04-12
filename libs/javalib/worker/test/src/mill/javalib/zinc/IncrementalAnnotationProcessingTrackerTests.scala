package mill.javalib.zinc

import utest.*

import javax.tools.{FileObject, JavaFileObject, SimpleJavaFileObject}

object IncrementalAnnotationProcessingTrackerTests extends TestSuite {

  private def fileObject(path: os.Path, kind: JavaFileObject.Kind): FileObject =
    new SimpleJavaFileObject(path.toNIO.toUri, kind) {}

  val tests: Tests = Tests {
    test("explicitOwnershipWinsOverSiblingFallback") {
      val workDir = os.temp.dir()
      try {
        val source = workDir / "src/example/Mapper.java"
        val generatedSource = workDir / "generated/example/MapperImpl.java"
        val generatedClass = workDir / "classes/example/MapperImpl.class"
        val classesDir = workDir / "classes"

        val tracker =
          new IncrementalAnnotationProcessing.CompileTracker(
            trackingMode = IncrementalAnnotationProcessing.TrackingMode.Isolating,
            sources = Set(source),
            classesDir = classesDir
          )

        val generatedSourceFile = fileObject(generatedSource, JavaFileObject.Kind.SOURCE)
        val generatedClassFile = fileObject(generatedClass, JavaFileObject.Kind.CLASS)

        tracker.recordOwnedGenerated(generatedSourceFile, Set(source))
        tracker.recordSiblingGenerated(generatedSourceFile, None)
        tracker.recordSiblingGenerated(generatedClassFile, Some(generatedSourceFile))

        assert(
          tracker.snapshot.products == Map(
            generatedClass -> IncrementalAnnotationProcessing.ProductOwnership.Isolating(source)
          )
        )
      } finally os.remove.all(workDir)
    }
  }
}
