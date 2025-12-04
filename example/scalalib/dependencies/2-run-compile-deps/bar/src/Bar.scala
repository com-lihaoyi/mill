package bar

import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.io.IOException
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder

class BarServlet extends HttpServlet {
  override protected def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    response.setContentType("text/html")
    response.setStatus(HttpServletResponse.SC_OK)
    response.getWriter.println("<html><body>Hello World!</body></html>")
  }
}

object Bar {
  @throws[Exception]
  def main(args: Array[String]): Unit = {
    val server = Server(8079)
    val context = new ServletContextHandler
    context.setContextPath("/")
    server.setHandler(context)
    context.addServlet(ServletHolder(new BarServlet), "/*")
    server.start
    server.join
  }
}
