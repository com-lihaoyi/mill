package mill.constants;

import java.net.SocketException;

public class SocketUtil {
  public static boolean clientHasClosedConnection(SocketException e) {
    var message = e.getMessage();
    return message != null
      && (message.contains("Broken pipe")
      || message.contains("Socket closed")
      || message.contains("Connection reset by peer"));
  }
}
