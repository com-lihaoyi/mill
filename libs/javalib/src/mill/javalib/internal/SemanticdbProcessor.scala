// copied from https://github.com/VirtusLab/scala-cli/blob/26993dbd1e3da0714a7d704801642acb053ef518/modules/build/src/main/scala/scala/build/postprocessing/SemanticdbProcessor.scala
// that itself adapted it from https://github.com/com-lihaoyi/Ammonite/blob/2da846d2313f1e12e812802babf9c69005f5d44a/amm/interp/src/main/scala/ammonite/interp/script/SemanticdbProcessor.scala

package mill.javalib.internal

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable
import scala.meta.internal.semanticdb._

object SemanticdbProcessor {

  def postProcess(
      originalCode: String,
      originalPath: os.RelPath,
      adjust: Int => Option[Int],
      orig: os.Path
  ): Array[Byte] = {

    val mapRange = {
      (range: scala.meta.internal.semanticdb.Range) =>
        for {
          startLine <- adjust(range.startLine)
          endLine <- adjust(range.endLine)
        } yield range
          .withStartLine(startLine)
          .withEndLine(endLine)
    }

    def updateTrees(trees: Seq[Tree]): Option[Seq[Tree]] =
      trees
        .foldLeft(Option(new mutable.ListBuffer[Tree])) {
          (accOpt, t) =>
            for (acc <- accOpt; t0 <- updateTree(t)) yield acc += t0
        }
        .map(_.result())

    def updateTree(tree: Tree): Option[Tree] =
      tree match {
        case a: ApplyTree =>
          for {
            function <- updateTree(a.function)
            args <- updateTrees(a.arguments)
          } yield a.withFunction(function).withArguments(args)
        case Tree.Empty => Some(Tree.Empty)
        case f: FunctionTree =>
          for {
            body <- updateTree(f.body)
          } yield f.withBody(body)
        case i: IdTree => Some(i)
        case l: LiteralTree => Some(l)
        case m: MacroExpansionTree =>
          for {
            beforeExp <- updateTree(m.beforeExpansion)
          } yield m.withBeforeExpansion(beforeExp)
        case o: OriginalTree =>
          if (o.range.isEmpty) Some(o)
          else
            for {
              range <- o.range.flatMap(mapRange)
            } yield o.withRange(range)
        case s: SelectTree =>
          for {
            qual <- updateTree(s.qualifier)
          } yield s.withQualifier(qual)
        case t: TypeApplyTree =>
          for {
            fun <- updateTree(t.function)
          } yield t.withFunction(fun)
      }

    val docs = TextDocuments.parseFrom(os.read.bytes(orig))
    val updatedDocs = docs.withDocuments {
      docs.documents.map { doc =>
        doc
          .withText(originalCode)
          .withUri(originalPath.toString)
          .withMd5(md5(originalCode))
          .withDiagnostics {
            doc.diagnostics.flatMap { diag =>
              diag.range.fold(Option(diag)) { range =>
                mapRange(range).map(diag.withRange)
              }
            }
          }
          .withOccurrences {
            doc.occurrences.flatMap { occurrence =>
              occurrence.range.fold(Option(occurrence)) { range =>
                mapRange(range).map(occurrence.withRange)
              }
            }
          }
          .withSynthetics {
            doc.synthetics.flatMap { syn =>
              val synOpt = syn.range.fold(Option(syn)) { range =>
                mapRange(range).map(syn.withRange)
              }
              synOpt.flatMap { syn0 =>
                updateTree(syn0.tree)
                  .map(syn0.withTree)
              }
            }
          }
      }
    }
    updatedDocs.toByteArray
  }

  private def md5(content: String): String = {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(content.getBytes(StandardCharsets.UTF_8))
    val res = new BigInteger(1, digest).toString(16)
    if (res.length < 32)
      ("0" * (32 - res.length)) + res
    else
      res
  }

}
