package foo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectTest :
    FunSpec({
        test("simple") {
            val data = Project("kotlinx.serialization", "Kotlin")
            val string = Json.encodeToString(data)
            string shouldBe """{"name":"kotlinx.serialization","language":"Kotlin"}"""
            // Deserializing back into objects
            val obj = Json.decodeFromString<Project>(string)
            obj shouldBe data
        }
    })
