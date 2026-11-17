package com.example

import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.theme.Theme
import com.vaadin.flow.theme.lumo.Lumo
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@Theme(themeClass = classOf[Lumo])
class Application extends AppShellConfigurator

object Application {
  def main(args: Array[String]): Unit =
    SpringApplication.run(classOf[Application], args*)
}
