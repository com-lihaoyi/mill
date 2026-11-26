package com.example

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route

@Route("")
class MainView extends VerticalLayout {
  private val name = TextField("Your name")
  private val greet = Button("Say hello", _ => Notification.show(s"Hello, ${name.getValue}!"))
  add(H1("Hello Vaadin"), name, greet)
}
