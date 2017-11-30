package better.files;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Hand built using a char buffer
 */
public class ArrayBufferScanner extends AbstractScanner {
  private char[] buffer = new char[1 << 4];
  private int pos = 1;

  private BufferedReader reader;

  public ArrayBufferScanner(BufferedReader reader) {
    super(reader);
    this.reader = reader;
  }

  @Override
  public boolean hasNext() {
    return pos > 0;
  }

  private void loadBuffer() {
    pos = 0;
    try {
      for (int i; (i = reader.read()) != -1; ) {
        char c = (char) i;
        if (c != ' ' && c != '\n' && c != '\t' && c != '\r' && c != '\f') {
          if (pos == buffer.length) buffer = Arrays.copyOf(buffer, 2 * pos);
          buffer[pos++] = c;
        } else if (pos != 0) break;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String current() {
    return String.copyValueOf(buffer, 0, pos);
  }

  @Override
  public String next() {
    loadBuffer();
    return current();
  }

  @Override
  public String nextLine() {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public int nextInt() {
    loadBuffer();
    final int radix = 10;
    int result = 0;
    int i = buffer[0] == '-' || buffer[0] == '+' ? 1 : 0;
    for (checkValidNumber(pos > i); i < pos; i++) {
      int digit = buffer[i] - '0';
      checkValidNumber(0 <= digit && digit <= 9);
      result = result * radix + digit;
    }
    return buffer[0] == '-' ? -result : result;
  }

  private void checkValidNumber(boolean condition) {
    if (!condition) throw new NumberFormatException(current());
  }
}
