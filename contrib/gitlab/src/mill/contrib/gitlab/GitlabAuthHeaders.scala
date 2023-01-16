package mill.contrib.gitlab

/**
 * Actual headers to inject to http requests to gitlab.
 *
 * @param headers header -> value pairs
 */
case class GitlabAuthHeaders(headers: Seq[(String, String)])

object GitlabAuthHeaders {
  def apply(header: String, value: String): GitlabAuthHeaders =
    GitlabAuthHeaders(Seq(header -> value))

  def privateToken(token: String): GitlabAuthHeaders = GitlabAuthHeaders("Private-Token", token)
  def deployToken(token: String): GitlabAuthHeaders = GitlabAuthHeaders("Deploy-Token", token)
  def jobToken(token: String): GitlabAuthHeaders = GitlabAuthHeaders("Job-Token", token)
}
