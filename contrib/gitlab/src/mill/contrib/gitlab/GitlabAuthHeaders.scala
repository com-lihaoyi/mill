package mill.contrib.gitlab

case class GitlabAuthHeaders(headers: Seq[(String, String)])

object GitlabAuthHeaders {
  def apply(header: String, value: String): GitlabAuthHeaders = GitlabAuthHeaders(Seq(header -> value))

  def personalHeader(token: String): GitlabAuthHeaders = GitlabAuthHeaders("Private-Token", token)
  def deployHeader(token: String): GitlabAuthHeaders   = GitlabAuthHeaders("Deploy-Token", token)
  def jobHeader(token: String): GitlabAuthHeaders      = GitlabAuthHeaders("Job-Token", token)
}
