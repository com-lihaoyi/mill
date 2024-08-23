package bar;

import org.apache.commons.text.StringEscapeUtils;

public class Bar {
  public static String value() {
    return "<p>" + StringEscapeUtils.escapeHtml4("world") + "</p>";
  }
}