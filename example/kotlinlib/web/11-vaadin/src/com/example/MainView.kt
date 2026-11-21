package com.example

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route

@Route("")
class MainView : VerticalLayout() {

    init {
        val name = TextField("Your name")
        val greet = Button("Say hello") { Notification.show("Hello, ${name.value}!") }
        add(H1("Hello Vaadin"), name, greet)
    }
}
