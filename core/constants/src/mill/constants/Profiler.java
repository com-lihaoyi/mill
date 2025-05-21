package mill.constants;

/**
 * A tiny class useful for peppering throughout a codebase to
 * see where the time is being spent during interactive debugging
 */
public class Profiler {
  long prev = System.currentTimeMillis();

  public void tick(String s) {
    long next = System.currentTimeMillis();
    DebugLog.println(s + " " + (next - prev) + "ms");
    prev = next;
  }
}
