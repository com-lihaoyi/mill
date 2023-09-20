// The following example demonstrates a use case: using cross modules to
// turn files on disk into blog posts. To begin with, we import two third-party
// libraries - Commonmark and Scalatags - to deal with Markdown parsing and
// HTML generation respectively:

import $ivy.`com.lihaoyi::scalatags:0.12.0`, scalatags.Text.all._
import $ivy.`com.atlassian.commonmark:commonmark:0.13.1`
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

// Next, we use `os.list` and `interp.watchValue` on the `post/` folder to
// build a `Cross[PostModule]` whose entries depend no the markdown files we
// find in that folder. Each post has a `source` pointing at the markdown file,
// and a `render` target that parses the file's markdown and generates a HTML
// output file

import mill._

def mdNameToHtml(s: String) = s.toLowerCase.replace(".md", ".html")
def mdNameToTitle(s: String) =
  s.split('-').drop(1).mkString(" ").stripSuffix(".md")

val posts = interp.watchValue {
  os.list(millSourcePath / "post").map(_.last).sorted
}

object post extends Cross[PostModule](posts)
trait PostModule extends Cross.Module[String]{
  def source = T.source(millSourcePath / crossValue)
  def render = T{
    val doc = Parser.builder().build().parse(os.read(source().path))
    val title = mdNameToTitle(crossValue)
    val rendered = doctype("html")(
      html(
        body(
          h1(a("Blog", href := "../index.html"), " / ", title),
          raw(HtmlRenderer.builder().build().render(doc))
        )
      )
    )

    os.write(T.dest /  mdNameToHtml(crossValue), rendered)
    PathRef(T.dest / mdNameToHtml(crossValue))
  }
}

// The last page we need to generate is the index page, listing out the various
// blog posts and providing links so we can navigate into them. To do this, we
// need to wrap the `posts` value in a `T.input`, as it can change depending on
// what `os.list` finds on disk. After that, it's straightforward to render the
// `index.html` file we want:

def postsInput = T.input{ posts }

def renderIndexEntry(mdName: String) = {
  h2(a(mdNameToTitle(mdName), href := ("post/" + mdNameToHtml(mdName))))
}

def index = T{
  val rendered = doctype("html")(
    html(body(h1("Blog"), postsInput().map(renderIndexEntry)))
  )
  os.write(T.dest / "index.html", rendered)
  PathRef(T.dest / "index.html")
}

// Lastly we copy the individual post HTML files and the `index.html` file
// into a single target's `.dest` folder, and return it:

def dist = T {
  for (post <- T.traverse(post.crossModules)(_.render)()) {
    os.copy(post.path, T.dest / "post" / post.path.last, createFolders = true)
  }
  os.copy(index().path, T.dest / "index.html")
  PathRef(T.dest)
}

// Now, you can run `mill dist` to generate the blog:

/** Usage

> ./mill dist

> cat out/dist.dest/index.html                    # root index page
...
...<a href="post/1-my-first-post.html">My First Post</a>...
...<a href="post/2-my-second-post.html">My Second Post</a>...
...<a href="post/3-my-third-post.html">My Third Post</a>...

> cat out/dist.dest/post/1-my-first-post.html     # blog post page
...
...<p>Text contents of My First Post</p>...

*/

// image::BlogIndex.png[BlogIndex.png]
// image::BlogPost.png[BlogPost.png]
//
// This static blog automatically picks up new blog posts you add to the
// `post/` folder, and when you edit your posts it only re-parses and
// re-renders the markdown files that you changed. You can use `-w` to watch
// the posts folder to automatically re-run the `dist` command if a post
// changes, or `-j` e.g. `./mill -j 4 dist` to enable parallelism if there are
// enough posts that the build is becoming noticeably slow.
//
// You can also build each individual post directly:

/** Usage

> ./mill show "post[1-My-First-Post.md].render"
".../out/post/1-My-First-Post.md/render.dest/1-my-first-post.html"

> cat out/post/1-My-First-Post.md/render.dest/1-my-first-post.html
...
...<p>Text contents of My First Post</p>...

*/

// All caching, incremental re-computation, and parallelism is done using the
// Mill target graph. For this simple example, the graph is as follows
//
// image::BlogGraph.svg[BlogGraph.svg]
//
// This example use case is taken from the following blog post, which contains
// some extensions and fun exercises to further familiarize yourself with Mill
//
// * http://www.lihaoyi.com/post/HowtocreateBuildPipelinesinScala.html[How to create Build Pipelines in Scala]