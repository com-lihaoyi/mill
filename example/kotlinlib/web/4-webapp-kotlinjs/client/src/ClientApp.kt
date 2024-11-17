package client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.get
import org.w3c.fetch.RequestInit

object ClientApp {
    private var state = "all"

    private val todoApp: Element
        get() = checkNotNull(document.getElementsByClassName("todoapp")[0])

    private fun postFetchUpdate(url: String) {
        window
            .fetch(url, RequestInit(method = "POST"))
            .then { it.text() }
            .then { text ->
                todoApp.innerHTML = text
                initListeners()
            }
    }

    private fun bindEvent(
        cls: String,
        url: String,
        endState: String? = null,
    ) {
        document
            .getElementsByClassName(cls)[0]
            ?.addEventListener(
                "click",
                {
                    postFetchUpdate(url)
                    if (endState != null) state = endState
                },
            )
    }

    private fun bindIndexedEvent(
        cls: String,
        block: (String) -> String,
    ) {
        for (elem in document.getElementsByClassName(cls).asList()) {
            elem.addEventListener(
                "click",
                { postFetchUpdate(block(elem.getAttribute("data-todo-index")!!)) },
            )
        }
    }

    fun initListeners() {
        bindIndexedEvent("destroy") {
            "/delete/$state/$it"
        }
        bindIndexedEvent("toggle") {
            "/toggle/$state/$it"
        }
        bindEvent("toggle-all", "/toggle-all/$state")
        bindEvent("todo-all", "/list/all", "all")
        bindEvent("todo-active", "/list/active", "active")
        bindEvent("todo-completed", "/list/completed", "completed")
        bindEvent("clear-completed", "/clear-completed/$state")

        val newTodoInput = document.getElementsByClassName("new-todo")[0] as HTMLInputElement
        newTodoInput.addEventListener(
            "keydown",
            {
                check(it is KeyboardEvent)
                if (it.keyCode == 13) {
                    window
                        .fetch("/add/$state", RequestInit(method = "POST", body = newTodoInput.value))
                        .then { it.text() }
                        .then { text ->
                            newTodoInput.value = ""
                            todoApp.innerHTML = text
                            initListeners()
                        }
                }
            },
        )
    }
}

fun main(args: Array<String>) = ClientApp.initListeners()
