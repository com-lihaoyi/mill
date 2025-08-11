package mill.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;

/**
 * The default data sent by the client on connection to the server.
 */
public class ClientInitData {
  public final boolean interactive;
  public final String clientMillVersion;
  public final String clientJavaVersion;
  public final String[] args;
  public final Map<String, String> env;
  public final Map<String, String> userSpecifiedProperties;

  public ClientInitData(
      boolean interactive,
      String clientMillVersion,
      String clientJavaVersion,
      String[] args,
      Map<String, String> env,
      Map<String, String> userSpecifiedProperties) {
    this.interactive = interactive;
    this.clientMillVersion = clientMillVersion;
    this.clientJavaVersion = clientJavaVersion;
    this.args = args;
    this.env = env;
    this.userSpecifiedProperties = userSpecifiedProperties;
  }

  @Override
  public String toString() {
    return "ClientInitData{" + "interactive="
        + interactive + ", clientMillVersion='"
        + clientMillVersion + '\'' + ", clientJavaVersion='"
        + clientJavaVersion + '\'' + ", args="
        + Arrays.toString(args) + ", env="
        + env + ", userSpecifiedProperties="
        + userSpecifiedProperties + '}';
  }

  public void write(OutputStream in) throws IOException {
    in.write(interactive ? 1 : 0);
    ClientUtil.writeString(in, clientMillVersion);
    ClientUtil.writeString(in, clientJavaVersion);
    ClientUtil.writeArgs(args, in);
    ClientUtil.writeMap(env, in);
    ClientUtil.writeMap(userSpecifiedProperties, in);
    in.flush();
  }

  public static ClientInitData read(InputStream socketIn) throws IOException {
    var interactive = socketIn.read() != 0;
    var clientMillVersion = ClientUtil.readString(socketIn);
    var clientJavaVersion = ClientUtil.readString(socketIn);
    var args = ClientUtil.parseArgs(socketIn);
    var env = ClientUtil.parseMap(socketIn);
    var userSpecifiedProperties = ClientUtil.parseMap(socketIn);
    return new ClientInitData(
        interactive, clientMillVersion, clientJavaVersion, args, env, userSpecifiedProperties);
  }
}
