package hello
// Taken from https://github.com/handsonscala/handsonscala/blob/ebc0367144513fc181281a024f8071a6153be424/examples/19.1%20-%20Phrases/Phrases.sc
import fastparse._, NoWhitespace._
sealed trait Phrase
class Word(s: String) extends Phrase
class Pair(lhs: Phrase, rhs: Phrase) extends Phrase

object Parser{
  def prefix[_: P] = P("hello" | "goodbye").!.map(new Word(_))

  def suffix[_: P] = P("world" | "seattle").!.map(new Word(_))

  def ws[_: P] = P(" ".rep(1))

  def parened[_: P] = P("(" ~ parser ~ ")")

  def parser[_: P]: P[Phrase] = P((parened | prefix) ~ ws ~ (parened | suffix)).map {
    case (lhs, rhs) => new Pair(lhs, rhs)
  }
}

/* expected-direct-call-graph
{
    "hello.Parser$#parened(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parser(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#parser(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Pair#<init>(hello.Phrase,hello.Phrase)void",
        "hello.Parser$#parened(fastparse.ParsingRun)fastparse.ParsingRun",
        "hello.Parser$#prefix(fastparse.ParsingRun)fastparse.ParsingRun",
        "hello.Parser$#suffix(fastparse.ParsingRun)fastparse.ParsingRun",
        "hello.Parser$#ws(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#prefix(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Word#<init>(java.lang.String)void"
    ],
    "hello.Parser$#suffix(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Word#<init>(java.lang.String)void"
    ],
    "hello.Parser.parened(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#<init>()void",
        "hello.Parser$#parened(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser.parser(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#<init>()void",
        "hello.Parser$#parser(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser.prefix(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#<init>()void",
        "hello.Parser$#prefix(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser.suffix(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#<init>()void",
        "hello.Parser$#suffix(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser.ws(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#<init>()void",
        "hello.Parser$#ws(fastparse.ParsingRun)fastparse.ParsingRun"
    ]
}
*/
