package com.example;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;

@Route("")
public class MainView extends VerticalLayout {

  public MainView() {
    TextField name = new TextField("Your name");
    Button greet =
        new Button("Say hello", event -> Notification.show("Hello, " + name.getValue() + "!"));
    add(new H1("Hello Vaadin"), name, greet);
  }
}
