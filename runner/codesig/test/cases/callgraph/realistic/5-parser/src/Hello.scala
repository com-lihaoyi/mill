package hello
// Taken from https://github.com/handsonscala/handsonscala/blob/ebc0367144513fc181281a024f8071a6153be424/examples/19.1%20-%20Phrases/Phrases.sc
import fastparse._, NoWhitespace._
sealed trait Phrase
class Word(s: String) extends Phrase
class Pair(lhs: Phrase, rhs: Phrase) extends Phrase

object Parser {
  def prefix[$: P] = P("hello" | "goodbye").!.map(Word(_))

  def suffix[$: P] = P("world" | "seattle").!.map(Word(_))

  def ws[$: P] = P(" ".rep(1))

  def parened[$: P] = P("(" ~ parser ~ ")")

  def parser[$: P]: P[Phrase] = P((parened | prefix) ~ ws ~ (parened | suffix)).map {
    case (lhs, rhs) => Pair(lhs, rhs)
  }
}

/* expected-direct-call-graph
{
    "hello.Parser$#parened(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parser(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#parse0$proxy8$1(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parse0$1$1(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#parse0$proxy9$1(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parse0$1$1(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#parse0$proxy9$2(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parse0$1$1(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#parse0$proxy9$3(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parse0$1$1(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#parse0$proxy9$4(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#parse0$1$1(fastparse.ParsingRun)fastparse.ParsingRun"
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
    "hello.Parser$#rec$1(fastparse.ParsingRun,int,fastparse.Implicits$Repeater,java.lang.Object,fastparse.ParsingRun,int,int,boolean,boolean,fastparse.internal.Msgs,fastparse.internal.Msgs)fastparse.ParsingRun": [
        "hello.Parser$#end$1(int,fastparse.ParsingRun,fastparse.Implicits$Repeater,java.lang.Object,int,int,int,boolean)fastparse.ParsingRun",
        "hello.Parser$#parse0$1$1(fastparse.ParsingRun)fastparse.ParsingRun"
    ],
    "hello.Parser$#suffix(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Word#<init>(java.lang.String)void"
    ],
    "hello.Parser$#ws(fastparse.ParsingRun)fastparse.ParsingRun": [
        "hello.Parser$#rec$1(fastparse.ParsingRun,int,fastparse.Implicits$Repeater,java.lang.Object,fastparse.ParsingRun,int,int,boolean,boolean,fastparse.internal.Msgs,fastparse.internal.Msgs)fastparse.ParsingRun"
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
