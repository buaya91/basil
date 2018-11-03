package basil.data

import cats.syntax.show._

final case class ParseFailure(msg: String, path: Vector[PPath] = Vector.empty) extends Exception {
  override def getMessage: String =
    s"""$msg when parsing ${path.show}"""
}

object ParseFailure {

  def apply(expect: String, received: String, path: Vector[PPath]): ParseFailure =
    apply(s"Expect $expect, but got $received", path)

  def termination(implicit path: Vector[PPath]): ParseFailure =
    ParseFailure("Unexpected termination", path)
}
