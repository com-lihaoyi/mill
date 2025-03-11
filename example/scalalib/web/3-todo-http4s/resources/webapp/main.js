var state = "all";

var todoApp = document.getElementsByClassName("todoapp")[0];
function postFetchUpdate(url){
    fetch(url, {
        method: "POST",
    })
        .then(function(response){ return response.text()})
        .then(function (text) {
            todoApp.innerHTML = text;
            initListeners()
        })
}

function bindEvent(cls, url, endState){

    document.getElementsByClassName(cls)[0].addEventListener(
        "mousedown",
        function(evt){
            postFetchUpdate(url)
            if (endState) state = endState
        }
    );
}

function bindIndexedEvent(cls, func){
    Array.from(document.getElementsByClassName(cls)).forEach( function(elem) {
        elem.addEventListener(
            "mousedown",
            function(evt){
                postFetchUpdate(func(elem.getAttribute("data-todo-index")))
            }
        )
    });
}

function initListeners(){
    bindIndexedEvent(
        "destroy",
        function(index){return "/delete/" + state + "/" + index}
    );
    bindIndexedEvent(
        "toggle",
        function(index){return "/toggle/" + state + "/" + index}
    );
    bindEvent("toggle-all", "/toggle-all/" + state);
    bindEvent("todo-all", "/list/all", "all");
    bindEvent("todo-active",  "/list/active", "active");
    bindEvent("todo-completed", "/list/completed", "completed");
    bindEvent("clear-completed", "/clear-completed/" + state);
    var newTodoInput = document.getElementsByClassName("new-todo")[0];
    newTodoInput.addEventListener(
        "keydown",
        function(evt){
            if (evt.keyCode === 13) {
                fetch("/add/" + state, {
                    method: "POST",
                    body: newTodoInput.value
                })
                    .then(function(response){ return response.text()})
                    .then(function (text) {
                        newTodoInput.value = "";
                        todoApp.innerHTML = text;
                        initListeners()
                    })
            }
        }
    );
}
initListeners()