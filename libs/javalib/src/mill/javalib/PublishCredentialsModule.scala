package mill.javalib

import com.lumidion.sonatype.central.client.core.SonatypeCredentials
import mill.api.*

/**
 * Internal module to Retrieve credentials for publishing to Maven repositories
 * (e.g. `central.sonatype.org` or a private Maven repository).
 */
private[mill] trait PublishCredentialsModule extends Module {
  def getPublishCredentials(
      envVariablePrefix: String,
      usernameParameterValue: String,
      passwordParameterValue: String
  ): Task[(username: String, password: String)] = Task.Anon {
    val username =
      getPublishCredential(usernameParameterValue, "username", s"${envVariablePrefix}_USERNAME")()
    val password =
      getPublishCredential(passwordParameterValue, "password", s"${envVariablePrefix}_PASSWORD")()
    Result.Success((username, password))
  }

  private def getPublishCredential(
      credentialParameterValue: String,
      credentialName: String,
      envVariableName: String
  ): Task[String] = Task.Anon {
    if (credentialParameterValue.nonEmpty) {
      Result.Success(credentialParameterValue)
    } else {
      (for {
        credential <- Task.env.get(envVariableName)
      } yield {
        Result.Success(credential)
      }).getOrElse(
        Result.Failure(
          s"No $credentialName set. Consider using the $envVariableName environment variable or passing `$credentialName` argument"
        )
      )
    }
  }
}
