package client
import org.scalajs.dom
import shared.{Todo, Shared}
object ClientApp {
  var state = "all"
  var todoApp = dom.document.getElementsByClassName("todoapp")(0)

  def postFetchUpdate(url: String) = {
    dom.fetch(
      url,
      new dom.RequestInit {
        method = dom.HttpMethod.POST
      }
    ).`then`[String](response => response.text())
      .`then`[Unit] { text =>
        todoApp.innerHTML = Shared
          .renderBody(upickle.default.read[Seq[Todo]](text), state)
          .render

        initListeners()
      }
  }

  def bindEvent(cls: String, url: String, endState: Option[String]) = {
    dom.document.getElementsByClassName(cls)(0).addEventListener(
      "mousedown",
      (evt: dom.Event) => {
        postFetchUpdate(url)
        endState.foreach(state = _)
      }
    )
  }

  def bindIndexedEvent(cls: String, func: String => String) = {
    for (elem <- dom.document.getElementsByClassName(cls)) {
      elem.addEventListener(
        "mousedown",
        (evt: dom.Event) => postFetchUpdate(func(elem.getAttribute("data-todo-index")))
      )
    }
  }

  def initListeners(): Unit = {
    bindIndexedEvent("destroy", index => s"/delete/$state/$index")
    bindIndexedEvent("toggle", index => s"/toggle/$state/$index")
    bindEvent("toggle-all", s"/toggle-all/$state", None)
    bindEvent("todo-all", s"/list/all", Some("all"))
    bindEvent("todo-active", s"/list/all", Some("active"))
    bindEvent("todo-completed", s"/list/completed", Some("completed"))
    bindEvent("clear-completed", s"/clear-completed/$state", None)

    val newTodoInput =
      dom.document.getElementsByClassName("new-todo")(0).asInstanceOf[dom.HTMLInputElement]
    newTodoInput.addEventListener(
      "keydown",
      (evt: dom.KeyboardEvent) => {
        if (evt.keyCode == 13) {
          dom.fetch(
            s"/add/$state",
            new dom.RequestInit {
              method = dom.HttpMethod.POST
              body = newTodoInput.value
            }
          ).`then`[String](response => response.text())
            .`then`[Unit] { text =>
              newTodoInput.value = ""

              todoApp.innerHTML = Shared
                .renderBody(upickle.default.read[Seq[Todo]](text), state)
                .render

              initListeners()
            }
        }
      }
    )
  }

  def main(args: Array[String]): Unit = initListeners()
}
