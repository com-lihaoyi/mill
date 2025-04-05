package mill.scalalib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._
import mill.define.Discover
import mill.util.TokenReaders._
import HelloWorldTests._
object ScalaMultiModuleClasspathsTests extends TestSuite {

  object MultiModuleClasspaths extends TestBaseModule {
    trait FooModule extends ScalaModule {
      def scalaVersion = "2.13.12"

      def ivyDeps = Seq(ivy"com.lihaoyi::sourcecode:0.2.2")
      def compileIvyDeps = Seq(ivy"com.lihaoyi::geny:0.4.2")
      def runIvyDeps = Seq(ivy"com.lihaoyi::utest:0.8.5")
      def unmanagedClasspath = Task { Seq(PathRef(moduleDir / "unmanaged")) }
    }
    trait BarModule extends ScalaModule {
      def scalaVersion = "2.13.12"

      def ivyDeps = Seq(ivy"com.lihaoyi::sourcecode:0.2.1")
      def compileIvyDeps = Seq(ivy"com.lihaoyi::geny:0.4.1")
      def runIvyDeps = Seq(ivy"com.lihaoyi::utest:0.8.5")
      def unmanagedClasspath = Task { Seq(PathRef(moduleDir / "unmanaged")) }
    }
    trait QuxModule extends ScalaModule {
      def scalaVersion = "2.13.12"

      def ivyDeps = Seq(ivy"com.lihaoyi::sourcecode:0.2.0")
      def compileIvyDeps = Seq(ivy"com.lihaoyi::geny:0.4.0")
      def runIvyDeps = Seq(ivy"com.lihaoyi::utest:0.8.5")
      def unmanagedClasspath = Task { Seq(PathRef(moduleDir / "unmanaged")) }
    }
    object ModMod extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def moduleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def moduleDeps = Seq(bar)
      }
    }
    object ModCompile extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def moduleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def compileModuleDeps = Seq(bar)
      }
    }
    object CompileMod extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def compileModuleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def moduleDeps = Seq(bar)
      }
    }

    object ModRun extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def moduleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def runModuleDeps = Seq(bar)
      }
    }
    object RunMod extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def runModuleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def moduleDeps = Seq(bar)
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    // Make sure that a bunch of modules dependent on each other has their various
    // {classpaths,moduleDeps,ivyDeps}x{run,compile,normal} properly aggregated
    def check(
        eval: UnitTester,
        mod: ScalaModule,
        expectedRunClasspath: Seq[String],
        expectedCompileClasspath: Seq[String],
        expectedLocalClasspath: Seq[String]
    ) = {
      val Right(runClasspathRes) = eval.apply(mod.runClasspath): @unchecked
      val Right(compileClasspathRes) = eval.apply(mod.compileClasspath): @unchecked
      val Right(upstreamAssemblyClasspathRes) =
        eval.apply(mod.upstreamAssemblyClasspath): @unchecked
      val Right(localClasspathRes) = eval.apply(mod.localClasspath): @unchecked

      val start = eval.evaluator.rootModule.moduleDir
      val startToken = Set("org", "com")
      def simplify(cp: Seq[PathRef]) = {
        cp.map(_.path).map { p =>
          if (p.startsWith(start)) p.relativeTo(start).toString()
          else p.segments.dropWhile(!startToken.contains(_)).mkString("/")
        }
      }

      val simplerRunClasspath = simplify(runClasspathRes.value)
      val simplerCompileClasspath = simplify(compileClasspathRes.value.toSeq)
      val simplerLocalClasspath = simplify(localClasspathRes.value)

      // pprint.log(simplerRunClasspath)
      assert(expectedRunClasspath == simplerRunClasspath)
      // pprint.log(simplerCompileClasspath)
      assert(expectedCompileClasspath == simplerCompileClasspath)
      // pprint.log(simplerLocalClasspath)
      assert(expectedLocalClasspath == simplerLocalClasspath)
      // invariant: the `upstreamAssemblyClasspath` used to make the `upstreamAssembly`
      // and the `localClasspath` used to complete it to make the final `assembly` must
      // have the same entries as the `runClasspath` used to execute things
      assert(
        runClasspathRes.value == upstreamAssemblyClasspathRes.value.toSeq ++ localClasspathRes.value
      )
    }

    test("modMod") - UnitTester(MultiModuleClasspaths, resourcePath).scoped { eval =>
      // Make sure that `compileClasspath` has all the same things as `runClasspath`,
      // but without the `/resources`
      check(
        eval,
        MultiModuleClasspaths.ModMod.qux,
        expectedRunClasspath = List(
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          // We pick up the newest version of sourcecode 0.2.4 from the upstream module, because
          // sourcecode is a `ivyDeps` and `runIvyDeps` and those are picked up transitively
          "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
          // We pick up the oldest version of utest 0.7.0 from the current module, because
          // utest is a `runIvyDeps` and not picked up transitively
          "com/lihaoyi/utest_2.13/0.8.5/utest_2.13-0.8.5.jar",
          "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
          "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
          "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
          //
          "ModMod/foo/compile-resources",
          "ModMod/foo/unmanaged",
          "ModMod/foo/resources",
          "out/ModMod/foo/compile.dest/classes",
          //
          "ModMod/bar/compile-resources",
          "ModMod/bar/unmanaged",
          "ModMod/bar/resources",
          "out/ModMod/bar/compile.dest/classes",
          //
          "ModMod/qux/compile-resources",
          "ModMod/qux/unmanaged",
          "ModMod/qux/resources",
          "out/ModMod/qux/compile.dest/classes"
        ),
        expectedCompileClasspath = List(
          // Make sure we only have geny 0.6.4 from the current module, and not newer
          // versions pulled in by the upstream modules, because as `compileIvyDeps` it
          // is not picked up transitively
          "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
          //
          "ModMod/foo/compile-resources",
          "ModMod/foo/unmanaged",
          "out/ModMod/foo/compile.dest/classes",
          //
          "ModMod/bar/compile-resources",
          "ModMod/bar/unmanaged",
          "out/ModMod/bar/compile.dest/classes",
          //
          "ModMod/qux/compile-resources",
          "ModMod/qux/unmanaged"
          // We do not include `qux/compile.dest/classes` here, because this is the input
          // that is required to compile `qux` in the first place
        ),
        expectedLocalClasspath = List(
          "ModMod/qux/compile-resources",
          "ModMod/qux/unmanaged",
          "ModMod/qux/resources",
          "out/ModMod/qux/compile.dest/classes"
        )
      )
    }

    test("modCompile") - UnitTester(MultiModuleClasspaths, resourcePath).scoped { eval =>
      // Mostly the same as `modMod` above, but with the dependency
      // from `qux` to `bar` being a `compileModuleDeps`
      check(
        eval,
        MultiModuleClasspaths.ModCompile.qux,
        expectedRunClasspath = List(
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          // Because `sourcecode` comes from `ivyDeps`, and the dependency from
          // `qux` to `bar` is a `compileModuleDeps`, we do not include its
          // dependencies for `qux`'s `runClasspath`
          "com/lihaoyi/sourcecode_2.13/0.2.0/sourcecode_2.13-0.2.0.jar",
          // `utest` is a `runIvyDeps` and not picked up transitively
          "com/lihaoyi/utest_2.13/0.8.5/utest_2.13-0.8.5.jar",
          //
          "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
          "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
          "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
          //
          "ModCompile/qux/compile-resources",
          "ModCompile/qux/unmanaged",
          "ModCompile/qux/resources",
          "out/ModCompile/qux/compile.dest/classes"
        ),
        expectedCompileClasspath = List(
          "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          // `sourcecode` is a `ivyDeps` from a `compileModuleDeps, which still
          // gets picked up transitively, but only for compilation. This is necessary
          // in order to make sure that we can correctly compile against the upstream
          // module's classes.
          "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
          //
          "ModCompile/foo/compile-resources",
          "ModCompile/foo/unmanaged",
          "out/ModCompile/foo/compile.dest/classes",
          //
          "ModCompile/bar/compile-resources",
          "ModCompile/bar/unmanaged",
          "out/ModCompile/bar/compile.dest/classes",
          //
          "ModCompile/qux/compile-resources",
          "ModCompile/qux/unmanaged"
        ),
        expectedLocalClasspath = List(
          "ModCompile/qux/compile-resources",
          "ModCompile/qux/unmanaged",
          "ModCompile/qux/resources",
          "out/ModCompile/qux/compile.dest/classes"
        )
      )
    }

    test("compileMod") - UnitTester(MultiModuleClasspaths, resourcePath).scoped { eval =>
      // Both the `runClasspath` and `compileClasspath` should not have `foo` on the
      // classpath, nor should it have the versions of libraries pulled in by `foo`
      // (e.g. `sourcecode-0.2.4`), because it is a `compileModuleDep` of an upstream
      // module and thus it is not transitive
      check(
        eval,
        MultiModuleClasspaths.CompileMod.qux,
        expectedRunClasspath = List(
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          // We pick up the version of `sourcecode` from `ivyDeps` from `bar` because
          // we have a normal `moduleDeps` from `qux` to `bar`, but do not pick it up
          // from `foo` because it's a `compileIvyDeps` from `bar` to `foo` and
          // `compileIvyDeps` are not transitive
          "com/lihaoyi/sourcecode_2.13/0.2.1/sourcecode_2.13-0.2.1.jar",
          "com/lihaoyi/utest_2.13/0.8.5/utest_2.13-0.8.5.jar",
          //
          "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
          "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
          "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
          //
          "CompileMod/bar/compile-resources",
          "CompileMod/bar/unmanaged",
          "CompileMod/bar/resources",
          "out/CompileMod/bar/compile.dest/classes",
          //
          "CompileMod/qux/compile-resources",
          "CompileMod/qux/unmanaged",
          "CompileMod/qux/resources",
          "out/CompileMod/qux/compile.dest/classes"
        ),
        expectedCompileClasspath = List(
          "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          "com/lihaoyi/sourcecode_2.13/0.2.1/sourcecode_2.13-0.2.1.jar",
          // We do not include `foo`s compile output here, because `foo` is a
          // `compileModuleDep` of `bar`, and `compileModuleDep`s are non-transitive
          //
          "CompileMod/bar/compile-resources",
          "CompileMod/bar/unmanaged",
          "out/CompileMod/bar/compile.dest/classes",
          //
          "CompileMod/qux/compile-resources",
          "CompileMod/qux/unmanaged"
        ),
        expectedLocalClasspath = List(
          "CompileMod/qux/compile-resources",
          "CompileMod/qux/unmanaged",
          "CompileMod/qux/resources",
          "out/CompileMod/qux/compile.dest/classes"
        )
      )
    }
    test("modRun") - UnitTester(MultiModuleClasspaths, resourcePath).scoped { eval =>
      check(
        eval,
        MultiModuleClasspaths.ModRun.qux,
        expectedRunClasspath = List(
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
          "com/lihaoyi/utest_2.13/0.8.5/utest_2.13-0.8.5.jar",
          "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
          "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
          "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
          //
          "ModRun/foo/compile-resources",
          "ModRun/foo/unmanaged",
          "ModRun/foo/resources",
          "out/ModRun/foo/compile.dest/classes",
          //
          "ModRun/bar/compile-resources",
          "ModRun/bar/unmanaged",
          "ModRun/bar/resources",
          "out/ModRun/bar/compile.dest/classes",
          //
          "ModRun/qux/compile-resources",
          "ModRun/qux/unmanaged",
          "ModRun/qux/resources",
          "out/ModRun/qux/compile.dest/classes"
        ),
        expectedCompileClasspath = List(
          "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          "com/lihaoyi/sourcecode_2.13/0.2.0/sourcecode_2.13-0.2.0.jar",
          "ModRun/qux/compile-resources",
          "ModRun/qux/unmanaged"
        ),
        expectedLocalClasspath = List(
          "ModRun/qux/compile-resources",
          "ModRun/qux/unmanaged",
          "ModRun/qux/resources",
          "out/ModRun/qux/compile.dest/classes"
        )
      )
    }

    test("runMod") - UnitTester(MultiModuleClasspaths, resourcePath).scoped { eval =>
      check(
        eval,
        MultiModuleClasspaths.RunMod.qux,
        expectedRunClasspath = List(
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
          "com/lihaoyi/utest_2.13/0.8.5/utest_2.13-0.8.5.jar",
          "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
          "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
          "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
          //
          "RunMod/foo/compile-resources",
          "RunMod/foo/unmanaged",
          "RunMod/foo/resources",
          "out/RunMod/foo/compile.dest/classes",
          //
          "RunMod/bar/compile-resources",
          "RunMod/bar/unmanaged",
          "RunMod/bar/resources",
          "out/RunMod/bar/compile.dest/classes",
          //
          "RunMod/qux/compile-resources",
          "RunMod/qux/unmanaged",
          "RunMod/qux/resources",
          "out/RunMod/qux/compile.dest/classes"
        ),
        expectedCompileClasspath = List(
          "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
          "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
          "com/lihaoyi/sourcecode_2.13/0.2.1/sourcecode_2.13-0.2.1.jar",
          // `bar` ends up here because it's a normal `moduleDep`, but not `foo` because
          // it's a `runtimeModuleDep
          "RunMod/bar/compile-resources",
          "RunMod/bar/unmanaged",
          "out/RunMod/bar/compile.dest/classes",
          //
          "RunMod/qux/compile-resources",
          "RunMod/qux/unmanaged"
        ),
        expectedLocalClasspath = List(
          "RunMod/qux/compile-resources",
          "RunMod/qux/unmanaged",
          "RunMod/qux/resources",
          "out/RunMod/qux/compile.dest/classes"
        )
      )
    }
  }
}
