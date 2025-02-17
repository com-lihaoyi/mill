package com.example.dagger

import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = arrayOf(NumberGeneratorModule::class))
interface NumberApp {
    fun numberService(): NumberService
}
