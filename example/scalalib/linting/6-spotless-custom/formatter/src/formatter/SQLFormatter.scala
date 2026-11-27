package formatter

import com.diffplug.spotless.FormatterStep
import com.github.vertical_blank.sqlformatter.SqlFormatter

import java.io.File

class SQLFormatter extends FormatterStep {

  override def getName(): String = "custom-sql-format"

  override def format(raw: String, file: File): String = SqlFormatter.format(raw)

  override def close(): Unit = {
    // nothing to clean up
  }
}
