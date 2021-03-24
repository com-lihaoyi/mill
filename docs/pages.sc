import $ivy.`com.lihaoyi::scalatags:0.6.5`
import scalatags.Text.all._, scalatags.Text.tags2
import java.time.LocalDate
import $file.pageStyles, pageStyles._

case class PostInfo(name: String,
                    headers: Seq[(String, Int)],
                    rawHtmlContent: String)


def sanitize(s: String): String = {
  s.split(" |-", -1).map(_.filter(_.isLetterOrDigit)).mkString("-").toLowerCase
}
def pageChrome(titleText: Option[String],
               homePage: Boolean,
               contents: Frag,
               contentHeaders: Seq[(String, Int)],
               pageHeaders: Seq[String]): String = {
  val pageTitle = titleText.getOrElse("Mill")
  val sheets = Seq(
    "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.6/css/bootstrap.min.css",
    "https://maxcdn.bootstrapcdn.com/font-awesome/4.5.0/css/font-awesome.min.css",
    "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/9.1.0/styles/github-gist.min.css"
  )


  html(
    head(
      meta(charset := "utf-8"),
      for(sheet <- sheets)
        yield link(href := sheet, rel := "stylesheet", `type` := "text/css" ),
      tags2.title(pageTitle),
      tags2.style(s"@media (min-width: 60em) {${WideStyles.styleSheetText}}"),
      tags2.style(s"@media (max-width: 60em) {${NarrowStyles.styleSheetText}}"),
      tags2.style(Styles.styleSheetText),
      script(src:="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/9.1.0/highlight.min.js"),
      script(src:="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/9.1.0/languages/scala.min.js"),
      script(raw("hljs.initHighlightingOnLoad();")),
      // This makes media queries work on iphone (???)
      // https://stackoverflow.com/questions/13002731/responsive-design-media-query-not-working-on-iphone
      meta(name:="viewport", content:="initial-scale = 1.0,maximum-scale = 1.0")
    ),
    body(margin := 0, backgroundColor := "#f8f8f8")(
      navBar(homePage, contentHeaders, pageHeaders),
      div(
        WideStyles.content,
        NarrowStyles.content,
        maxWidth := 900,
        titleText.map(h1(_)),
        contents
      ),
      if (contentHeaders.nonEmpty) frag()
      else div(
        WideStyles.footer,
        Styles.footerStyle,
        "Last published ", currentTimeText
      )

    )
  ).render
}

def navBar(homePage: Boolean, contentHeaders: Seq[(String, Int)], pageHeaders: Seq[String]) = {
  def navList(navLabel: String, frags: Frag, narrowHide: Boolean) = {
    div(
      WideStyles.tableOfContents,
      if(narrowHide) NarrowStyles.disappear else frag(),
      color := "#f8f8f8"
    )(
      div(paddingLeft := 40, NarrowStyles.disappear)(
        b(navLabel)
      ),
      div(overflowY.auto, flexShrink := 1, minHeight := 0)(
        ul(
          overflow.hidden,
          textAlign.start,
          marginTop := 10,
          whiteSpace.nowrap,
          textOverflow.ellipsis,
          marginRight := 10
        )(
          frags
        )
      )
    )
  }
  val pageList = navList(
    "Pages",
    for((header, i) <- pageHeaders.zipWithIndex) yield li(
      WideStyles.marginLeftZero,
      NarrowStyles.floatLeft
    )(
      a(
        color := "#f8f8f8",
        WideStyles.tableOfContentsItem,
        href := {
          (homePage, i == 0) match {
            case (true, true) => s"index.html"
            case (true, false) => s"page/${sanitize(header)}.html"
            case (false, true) => s"../index.html"
            case (false, false) => s"${sanitize(header)}.html"
          }
        }
      )(
        header
      )
    ),
    narrowHide = false
  )

  val headerBox = div(
    NarrowStyles.headerContent,
    div(
      textAlign.center,
      a(
        img(
          src := {homePage match{
            case false => "../logo-white.svg"
            case true => "logo-white.svg"
          }},
          height := 30,
          marginTop := -5
        ),
        color := "#f8f8f8",
        " Mill",
        href := (if (homePage) "" else ".."),
        Styles.subtleLink,
        NarrowStyles.flexFont,
        fontWeight.bold
      ),
      padding := "30px 0",
      margin := 0,
      attr("font-size") := 36.px
    ),
    div(
      Styles.headerLinkBox,
      pageList
    )
  )


  val tableOfContents = navList(
    "Table of Contents",
    for {
      (header, indent) <- contentHeaders
      offset <- indent match{
        case 2 => Some(0)
        case 3 => Some(20)
        case _ => None
      }
    } yield li(marginLeft := offset)(
      a(
        color := "#f8f8f8",
        WideStyles.tableOfContentsItem,
        href := s"#${sanitize(header)}"
      )(
        header
      )
    ),
    narrowHide = true
  )

  div(
    WideStyles.header,
    NarrowStyles.header,
    Styles.headerStyle,
    headerBox,
    hr(NarrowStyles.disappear, backgroundColor := "#f8f8f8", width := "80%"),
    tableOfContents
  )
}


val currentTimeText = LocalDate.now.toString


def renderAdjacentLink(next: Boolean, name: String, url: String) = {
  a(href := url)(
    if(next) frag(name, " ", i(cls:="fa fa-arrow-right" , aria.hidden:=true))
    else frag(i(cls:="fa fa-arrow-left" , aria.hidden:=true), " ", name)
  )
}
def postContent(homePage: Boolean, post: PostInfo, adjacentLinks: Frag, posts: Seq[String]) = pageChrome(
  Some(post.name),
  homePage,
  Seq[Frag](
    div(adjacentLinks, marginBottom := 10),
    raw(post.rawHtmlContent),
    adjacentLinks
  ),
  post.headers,
  pageHeaders = posts
)
