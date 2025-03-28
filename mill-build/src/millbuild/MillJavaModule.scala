package millbuild

import build_.{package_ => build}
import coursier.MavenRepository
import mill.scalalib.{Dep, JavaModule}
import mill.{Agg, PathRef, T, Task}

trait MillJavaModule extends JavaModule {

  // Test setup
  def testDep = Task { (s"com.lihaoyi-${artifactId()}", testDepPaths().map(_.path).mkString("\n")) }

  // Workaround for Zinc/JNA bug
  // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
  def testArgs: T[Seq[String]] = Task { Seq("-Djna.nosys=true") }
  def testDepPaths = Task { upstreamAssemblyClasspath() ++ Seq(compile().classes) ++ resources() }

  def testTransitiveDeps: T[Map[String, String]] = Task {
    val upstream = Task.traverse(moduleDeps ++ compileModuleDeps) {
      case m: MillJavaModule => m.testTransitiveDeps.map(Some(_))
      case _ => Task.Anon(None)
    }().flatten.flatten
    val current = Seq(testDep())
    upstream.toMap ++ current
  }

  def testIvyDeps: T[Agg[Dep]] = Agg(Deps.TestDeps.utest)
  def testForkEnv: T[Map[String, String]] = forkEnv()
  def testModuleDeps: Seq[JavaModule] =
    if (this == build.main) Seq(build.main)
    else Seq(this, build.main.test)

  def writeLocalTestOverrides = Task.Anon {
    for ((k, v) <- testTransitiveDeps()) {
      os.write(Task.dest / "mill/local-test-overrides" / k, v, createFolders = true)
    }
    Seq(PathRef(Task.dest))
  }

  def runClasspath = super.runClasspath() ++ writeLocalTestOverrides()

  def repositoriesTask = Task.Anon {
    super.repositoriesTask() ++
      Seq(MavenRepository("https://oss.sonatype.org/content/repositories/releases"))
  }

  def mapDependencies: Task[coursier.Dependency => coursier.Dependency] = Task.Anon {
    super.mapDependencies().andThen { dep =>
      forcedVersions.find(f =>
        f.dep.module.organization.value == dep.module.organization.value &&
          f.dep.module.name.value == dep.module.name.value
      ).map { forced =>
        val newDep = dep.withVersion(forced.version)
        Task.log.debug(s"Forcing version of ${dep.module} from ${dep.version} to ${newDep.version}")
        newDep
      }.getOrElse(dep)
    }
  }
  val forcedVersions: Seq[Dep] = Deps.transitiveDeps ++ Seq(
    Deps.jline,
    Deps.jna
  )

  def javadocOptions = super.javadocOptions() ++ Seq(
    // Disable warnings for missing documentation comments or tags (for example,
    // a missing comment or class, or a missing @return tag or similar tag on a method).
    // We have many methods without JavaDoc comments, so those warnings are useless
    // and significantly clutter the output.
    "-Xdoclint:all,-missing"
  )

  def when[T](cond: Boolean)(args: T*): Seq[T] = if (cond) args else Seq()
}
