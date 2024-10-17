package mill.init.maven

private[init] object format {

  /**
   * Contains utilities for handling line breaks.
   */
  object break {

    /** A line break literal */
    val sep: String =
      s"""
         |""".stripMargin

    /**
     * Contains utilities for handling comma separated line breaks.
     */
    object cs {

      /** A line break literal that starts with a comma */
      val sep: String =
        s""",
           |""".stripMargin

      /**
       * Combines and returns `lines` to a string separated by a comma and line break.
       * {{{
       * indent(2, "  Seq(", Seq(1, 2, 3), ")")
       * // is transformed to
       * |  Seq(
       * |    1,
       * |    2,
       * |    3
       * |  )
       * }}}
       * @param level (positive) indentation level
       * @param start the starting string
       * @param lines the lines to combine
       * @param end the ending string
       */
      def indent(level: Int, start: String, lines: IterableOnce[String], end: String): String = {
        val pad = "  ".repeat(level)
        val padEnd = "  ".repeat(level - 1)
        lines.iterator.mkString(
          s"$start${break.sep}$pad",
          s"$sep$pad",
          s"${break.sep}$padEnd$end"
        )
      }
    }
  }

  /**
   * Contains utilities for escaping string values.
   */
  object escape extends (String => String) {

    /** An empty string escaped with quotes. */
    val empty: String = apply("")

    /** Escapes `literal` with quotes. */
    def apply(literal: String): String =
      s"\"$literal\""

    /** Escapes `lines` with triple quotes. */
    def multi(lines: String): String =
      s"s\"\"\"$lines\"\"\".stripMargin"
  }
}
