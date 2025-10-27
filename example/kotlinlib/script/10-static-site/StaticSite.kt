//| mvnDeps:
//| - org.jetbrains.kotlinx:kotlinx-html-jvm:0.11.0
//| - com.atlassian.commonmark:commonmark:0.13.1

import java.io.File
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import kotlinx.html.*
import kotlinx.html.stream.appendHTML

fun main(args: Array<String>) {
    val outDir = File("site-out")
    val outPostDir = outDir.resolve("post")

    val postInfo = File("post").listFiles { f -> f.extension == "md" }!!
        .map { f ->
            val match = Regex("""(\d+)\s*-\s*(.+)\.md""").matchEntire(f.name)
                ?: error("Invalid post filename: ${f.name}")
            val (prefix, suffix) = match.destructured
            Triple(prefix.toInt(), suffix, f)
        }
        .sortedBy { it.first }

    if (outDir.exists()) outDir.deleteRecursively()
    outPostDir.mkdirs()

    fun writeHtml(path: File, pageTitle: String, bodyContent: FlowContent.() -> Unit) {
        path.writer().use { w ->
            w.appendLine("<!DOCTYPE html>")
            w.appendHTML().html {
                body {
                    h1 { +pageTitle }
                    bodyContent()
                }
            }
        }
    }

    val parser = Parser.builder().build()
    val renderer = HtmlRenderer.builder().build()

    for ((_, suffix, file) in postInfo) {
        val document = parser.parse(file.readText())
        val htmlContent = renderer.render(document)

        val slug = suffix.replace(" ", "-").lowercase()
        val outputFile = outPostDir.resolve("$slug.html")

        writeHtml(outputFile, "Blog / $suffix") {
            div{
                unsafe {
                    +htmlContent
                }
            }
        }
    }

    val indexFile = outDir.resolve("index.html")
    writeHtml(indexFile, "Blog") {
        for ((_, suffix, _) in postInfo) {  h2 { +suffix }  }
    }
}