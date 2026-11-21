package com.example

import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.theme.Theme
import com.vaadin.flow.theme.lumo.Lumo
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// `open`: Spring subclasses configuration classes, Kotlin classes are final by default
@SpringBootApplication
@Theme(themeClass = Lumo::class)
open class Application : AppShellConfigurator

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
