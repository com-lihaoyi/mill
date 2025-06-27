package mill.contrib.sonatypecentral

import com.lumidion.sonatype.central.client.core.SonatypeCredentials
import mill.api.Logger

@deprecated("Use mill.scalalib.SonatypeCentralPublisher instead", "Mill 0.12.15")
class SonatypeCentralPublisher(
    credentials: SonatypeCredentials,
    gpgArgs: Seq[String],
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int
) extends mill.scalalib.SonatypeCentralPublisher(
      credentials = credentials,
      gpgArgs = gpgArgs,
      readTimeout = readTimeout,
      connectTimeout = connectTimeout,
      log = log,
      workspace = workspace,
      env = env,
      awaitTimeout = awaitTimeout
    )
