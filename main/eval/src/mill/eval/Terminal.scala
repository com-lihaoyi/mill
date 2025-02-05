package mill.eval

import mill.define.{NamedTask, Segments}

/**
 * A terminal or terminal target is some important work unit, that in most cases has a name (Right[Labelled])
 * or was directly called by the user (Left[Task]).
 * It's a Task, Task.Worker, Task.Input, Task.Source, Task.Sources, Task.Command
 */
sealed trait Terminal {
  def render: String
  def task: mill.define.Task[_]
}

object Terminal {
  case class Labelled[T](task: NamedTask[T]) extends Terminal {
    def render = task.ctx.segments.render
  }

  case class Task[T](task: mill.define.Task[_]) extends Terminal {
    def render = task.toString
  }

  @deprecated("User Terminal#render instead")
  def printTerm(term: Terminal): String = term.render
}
