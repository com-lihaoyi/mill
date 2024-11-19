package mill.pythonlib

import mill.Task
import mill.Command
import mill.TaskModule

trait TestModule extends TaskModule {

  // TODO: make this return something more interesting
  def test(args: String*): Command[Unit]

  override def defaultCommandName() = "test"
}

object TestModule {

  trait Unittest extends PythonModule with TestModule {
    def test(args: String*): Command[Unit] = Task.Command {
      val testArgs = if (args.isEmpty) {
        Seq("discover") ++ sources().flatMap(pr => Seq("-s", pr.path.toString))
      } else {
        args
      }

      os.call(
        (pythonExe().path, "-m", "unittest", testArgs),
        env = Map(
          "PYTHONPATH" -> transitiveSources().map(_.path).mkString(":"),
          "PYTHONPYCACHEPREFIX" -> (Task.dest / "cache").toString
        ),
        stdout = os.Inherit,
        cwd = Task.dest
      )
      ()
    }
  }

  trait Pytest extends PythonModule with TestModule {

    def pythonDeps = Seq("pytest")

    def test(args: String*): Command[Unit] = Task.Command {
      os.call(
        (pythonExe().path, "-m", "pytest", sources().map(_.path), args),
        env = Map(
          "PYTHONPATH" -> transitiveSources().map(_.path).mkString(":"),
          "PYTHONPYCACHEPREFIX" -> (Task.dest / "cache").toString
        ),
        stdout = os.Inherit,
        cwd = Task.dest
      )
      ()
    }
  }

}
