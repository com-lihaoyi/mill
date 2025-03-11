var state = "all";

var todoApp = document.getElementsByClassName("todoapp")[0];

function getCookie(name) {
    let cookieValue = null;
    if (document.cookie && document.cookie !== '') {
        const cookies = document.cookie.split(';');
        for (let i = 0; i < cookies.length; i++) {
            const cookie = cookies[i].trim();
            // Does this cookie string begin with the name we want?
            if (cookie.substring(0, name.length + 1) === (name + '=')) {
                cookieValue = decodeURIComponent(cookie.substring(name.length + 1));
                break;
            }
        }
    }
    return cookieValue;
}

const csrftoken = getCookie('csrftoken');

function postFetchUpdate(url) {
    fetch(url, {
        method: "POST",
        headers: {
            'X-CSRFToken': csrftoken,
            'Content-Type': 'text/plain'
        }
    })
        .then(function (response) { return response.text(); })
        .then(function (text) {
            todoApp.innerHTML = text;
            initListeners();
        })
        .catch(function (error) {
            console.error('Error:', error);
        });
}

function bindEvent(cls, url, endState) {
    var element = document.getElementsByClassName(cls)[0];
    if (element) {
        element.addEventListener(
            "mousedown",
            function (evt) {
                postFetchUpdate(url + '/');
                if (endState) state = endState;
            }
        );
    }
}

function bindIndexedEvent(cls, func) {
    Array.from(document.getElementsByClassName(cls)).forEach(function (elem) {
        elem.addEventListener(
            "mousedown",
            function (evt) {
                postFetchUpdate(func(elem.getAttribute("data-todo-index")) + '/');
            }
        );
    });
}

function initListeners() {
    // Bind events for deleting and toggling todos
    bindIndexedEvent(
        "destroy",
        function (index) { return "/delete/" + state + "/" + index; }
    );
    bindIndexedEvent(
        "toggle",
        function (index) { return "/toggle/" + state + "/" + index; }
    );

    // Bind events for global actions
    bindEvent("toggle-all", "/toggle-all/" + state);
    bindEvent("todo-all", "/list/all", "all");
    bindEvent("todo-active", "/list/active", "active");
    bindEvent("todo-completed", "/list/completed", "completed");
    bindEvent("clear-completed", "/clear-completed/" + state);

    // Event for adding new todos
    var newTodoInput = document.getElementsByClassName("new-todo")[0];
    if (newTodoInput) {
        newTodoInput.addEventListener(
            "keydown",
            function (evt) {
                if (evt.keyCode === 13) { // Enter key
                    fetch("/add/" + state + '/', {
                        method: "POST",
                        body: newTodoInput.value,
                        headers: {
                            'X-CSRFToken': csrftoken,
                            'Content-Type': 'text/plain'
                        }
                    })
                        .then(function (response) { return response.text(); })
                        .then(function (text) {
                            newTodoInput.value = "";
                            todoApp.innerHTML = text;
                            initListeners();
                        })
                        .catch(function (error) {
                            console.error('Error:', error);
                        });
                }
            }
        );
    }

    // Add double-click event to labels for editing todos
    Array.from(document.querySelectorAll(".todo-list label")).forEach(function (label) {
        label.addEventListener("dblclick", function () {
            var li = label.closest("li");
            li.classList.add("editing");

            var editInput = li.querySelector(".edit");
            editInput.value = label.textContent;
            editInput.focus();

            // Save on blur or Enter key
            function saveEdit() {
                var index = editInput.closest("li").querySelector(".toggle").getAttribute("data-todo-index");
                var updatedText = editInput.value;

                fetch("/edit/" + state + "/" + index + '/', {
                    method: "POST",
                    body: updatedText,
                    headers: {
                        'X-CSRFToken': csrftoken,
                        'Content-Type': 'text/plain'
                    }
                })
                    .then(function (response) { return response.text(); })
                    .then(function (text) {
                        todoApp.innerHTML = text;
                        initListeners();
                    })
                    .catch(function (error) {
                        console.error('Error:', error);
                    });
            }

            editInput.addEventListener("blur", saveEdit);
            editInput.addEventListener("keydown", function (evt) {
                if (evt.keyCode === 13) { // Enter key
                    saveEdit();
                }
            });
        });
    });
}

// Initialize event listeners when the page loads
initListeners();