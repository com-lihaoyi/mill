package mill.launcher;

/**
 * Rudimentary String serialization code to safely put `String[]`s and `List<String>`s
 * into line-delimited files in a human-readable fashion and take them out again unchanged.
 */
public class Escaping {
  public static void escapeChar(char c, StringBuilder sb, boolean unicode) {
    switch (c) {
      case '"':
        sb.append("\\\"");
        break;
      case '\\':
        sb.append("\\\\");
        break;
      case '\b':
        sb.append("\\b");
        break;
      case '\f':
        sb.append("\\f");
        break;
      case '\n':
        sb.append("\\n");
        break;
      case '\r':
        sb.append("\\r");
        break;
      case '\t':
        sb.append("\\t");
        break;
      default:
        if (c < ' ' || (c > '~' && unicode)) {
          sb.append(String.format("\\u%04x", (int) c));
        } else {
          sb.append(c);
        }
        break;
    }
  }

  /**
   * Convert a string to a C&P-able literal. Basically
   * copied verbatim from the uPickle source code.
   */
  public static String literalize(CharSequence s, boolean unicode) {
    StringBuilder sb = new StringBuilder();
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      escapeChar(s.charAt(i), sb, unicode);
    }
    sb.append('"');
    return sb.toString();
  }

  // Optional overload with unicode = true by default
  public static String literalize(CharSequence s) {
    return literalize(s, true);
  }

  public static String unliteralize(String s) {
    if (s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"') {
      throw new IllegalArgumentException("Invalid input: not a properly quoted literalized string");
    }

    StringBuilder sb = new StringBuilder();
    int i = 1; // Skip the opening quote
    int end = s.length() - 1; // Skip the closing quote

    while (i < end) {
      char c = s.charAt(i);
      if (c == '\\') {
        if (i + 1 >= end) {
          throw new IllegalArgumentException("Invalid escape at end of string");
        }
        char next = s.charAt(i + 1);
        switch (next) {
          case '"':
            sb.append('"');
            i += 2;
            break;
          case '\\':
            sb.append('\\');
            i += 2;
            break;
          case 'b':
            sb.append('\b');
            i += 2;
            break;
          case 'f':
            sb.append('\f');
            i += 2;
            break;
          case 'n':
            sb.append('\n');
            i += 2;
            break;
          case 'r':
            sb.append('\r');
            i += 2;
            break;
          case 't':
            sb.append('\t');
            i += 2;
            break;
          case 'u':
            if (i + 5 >= end) {
              throw new IllegalArgumentException("Incomplete unicode escape");
            }
            String hex = s.substring(i + 2, i + 6);
            try {
              int codePoint = Integer.parseInt(hex, 16);
              sb.append((char) codePoint);
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("Invalid unicode escape: \\u" + hex);
            }
            i += 6;
            break;
          default:
            throw new IllegalArgumentException("Unknown escape sequence: \\" + next);
        }
      } else {
        sb.append(c);
        i++;
      }
    }

    return sb.toString();
  }
}
