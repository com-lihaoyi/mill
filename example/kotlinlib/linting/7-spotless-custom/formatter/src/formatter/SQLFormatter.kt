package formatter

import com.diffplug.spotless.FormatterStep
import com.github.vertical_blank.sqlformatter.SqlFormatter
import java.io.File

class SQLFormatter : FormatterStep {

    override fun getName(): String = "custom-sql-format"

    override fun format(raw: String, file: File): String = SqlFormatter.format(raw)

    override fun close() {
        // nothing to clean up
    }
}
