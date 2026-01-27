package com.example.dagger

import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = arrayOf(NumberTestModule::class))
interface TestApp {
    fun numberService(): NumberService
}
