package mill.constants;

import java.net.SocketException;

public class SocketUtil {
  public static boolean clientHasClosedConnection(SocketException e) {
    var message = e.getMessage();
    return message != null
        && (message.contains("Broken pipe") // Unix/Linux
            || message.contains("Socket closed")
            || message.contains("Connection reset by peer") // Unix/Linux
            || message.contains("Connection reset") // Windows
            || message.contains("Software caused connection abort") // Windows
            || message.contains("forcibly closed") // Windows: "An existing connection was forcibly closed"
            || message.contains("connection was aborted")); // Windows: "An established connection was aborted"
  }
}
