
import mill.vcs.version._
import mill._
import mill.define.Command

def baseDir = {
  println("build.millSourcePath " + millSourcePath)
  millSourcePath
}

def initVcs: T[Unit] = {
  T {
    if (!os.exists(baseDir / ".git")) {
      T.log.info("Initializing git repo... " + baseDir)
      Seq(
        os.proc("git", "init", "."),
        os.proc("git", "add", "build.sc"),
        os.proc("git", "commit", "-m", "first commit"),
        os.proc("git", "tag", "1.2.3"),
        os.proc("git", "add", "plugins.sc"),
        os.proc("git", "commit", "-m", "second commit")
      ) foreach (_.call(cwd = baseDir))
    }
    ()
  }
}

def verify1(): Command[Unit] =
  T.command {
    initVcs()
    println("A " + os.pwd)
    val vcsState = VcsVersion.vcsState()
    println("B")
    assert(vcsState.vcs == Some(Vcs.git))
    println("C")

    val version = vcsState.format()
    println("D")
    println(s"version=${version}")
    println("E")
    assert(version.startsWith("1.2.3-1-") && !version.contains("DIRTY"))
    println("F")
    ()
  }

def changeSomething(): Command[Unit] = T.command {
  os.write.append(baseDir / "plugins.sc", "\n// dummy text")
  ()
}

def verify2(): Command[Unit] =
  T.command {
    initVcs()
    val version = VcsVersion.vcsState().format()
    T.log.info(s"version=${version}")
    assert(version.startsWith("1.2.3-1-") && version.contains("DIRTY"), s"Version was: ${version}")
    ()
  }

/** Usage

> ./mill verify1

> ./mill mill.vcs.version.VcsVersion/vcsState

> ./mill changeSomething

> ./mill verify2

*/