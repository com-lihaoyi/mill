package mill.androidlib

import java.util.{Collections, IdentityHashMap}

import mill.*
import mill.api.{Discover, ModuleRef}
import mill.testkit.TestRootModule
import utest.*

import scala.collection.mutable

object GenIdeaAndroidModuleTests extends TestSuite {

  object build extends TestRootModule {
    object sdk extends AndroidSdkModule {
      def buildToolsVersion = "35.0.0"
    }

    object app extends AndroidModule {
      def androidSdkModule = ModuleRef(sdk)
      def androidCompileSdk = 35
      def androidNamespace = "example"
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("idea tasks have unique output paths") {
      val terminal = build.app.genIdeaInternalExt().genIdeaResolvedModule(
        ideaConfigVersion = 4,
        build.app.moduleSegments
      )
      val transitive = mutable.ArrayBuffer.empty[Task[?]]
      val seen = Collections.newSetFromMap(new IdentityHashMap[Task[?], java.lang.Boolean])

      def visit(task: Task[?]): Unit =
        if (seen.add(task)) {
          transitive += task
          task.inputs.foreach(visit)
        }

      visit(terminal)

      val taskPaths = transitive
        .collect { case task: Task.Named[?] => task.ctx.segments.render }
      val duplicatePaths = taskPaths
        .groupMapReduce(identity)(_ => 1)(_ + _)
        .filter(_._2 > 1)

      assert(
        duplicatePaths.isEmpty,
        taskPaths.contains("app.internalGenIdea.extDependencies"),
        taskPaths.contains("app.internalGenIdea.androidExtDependencies"),
        taskPaths.contains("app.internalGenIdea.moduleGeneratedSources"),
        taskPaths.contains("app.internalGenIdea.androidModuleGeneratedSources")
      )
    }
  }
}
