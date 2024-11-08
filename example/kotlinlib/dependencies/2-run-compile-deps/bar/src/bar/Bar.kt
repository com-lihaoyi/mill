package bar

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder

class BarServlet : HttpServlet() {
    protected override fun doGet(request: HttpServletRequest, response: HttpServletResponse) {
        response.setContentType("text/html")
        response.setStatus(HttpServletResponse.SC_OK)
        response.getWriter().println("<html><body>Hello World!</body></html>")
    }
}

fun main(args: Array<String>) {
    val server = Server(8079)
    val context = ServletContextHandler()
    context.setContextPath("/")
    server.setHandler(context)
    context.addServlet(ServletHolder(BarServlet()), "/*")
    server.start()
    server.join()
}
