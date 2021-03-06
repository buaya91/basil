package basil.parser

import basil.data.ParseOps.ParseOpsHFunctor
import basil.data._

object Parser {
  val initPath = Vector.empty[PPath]

  /**
    * The canonical entry point of this library, to extract certain
    * piece of data from a data source
    *
    * @param expr The parse operations tree that describe data
    *             user wanted
    * @param src Data source
    * @return
    */
  def parseSource[Source[_], I](expr: HFix[ParseOps, I], src: Source[Char])(
      implicit parse: JsonStreamParse[Source]): Source[(I, parse.CharSource)] = {
    parseG[Source[Char], Lambda[A => Source[(A, Source[Char])]], I](expr, src)
  }

  def parseG[Input, F[_], TargetType](expr: HFix[ParseOps, TargetType], src: Input)(
      implicit parse: JsonParse[Input, F]): F[TargetType] = {
    HFix.cata[ParseOps, TargetType, parse.Parse](expr, parse.parsing).apply(initPath)(src)
  }

  def parseString[F[_], I](expr: HFix[ParseOps, I], src: String)(
      implicit parse: JsonParse[(String, Int), F]): F[I] = {
    parseG(expr, src -> 0)
  }

  def parseArrayChar[F[_], I](expr: HFix[ParseOps, I], src: Array[Char])(
      implicit parse: JsonParse[(Array[Char], Int), F]): F[I] = {
    parseG(expr, src -> 0)
  }
}

object digit {
  private val digits: Set[Char] = (0 to 9).map(x => Character.forDigit(x, 10)).toSet

  def unapply(arg: Char): Option[Char] = {
    Some(arg).filter(digits.contains)
  }
  def notDigit(char: Char): Boolean = !digits.contains(char)
}
object sign {
  def unapply(arg: Char): Option[Char] = {
    if (arg == '-' || arg == '+') {
      Some(arg)
    } else {
      None
    }
  }
}
object exponent {
  def unapply(c: Char): Option[Char] = if (c == 'e' || c == 'E') { Some(c) } else None
}
object whitespace {
  private val ws: Set[Char] = Set(' ', '\n', '\t', '\r')

  def unapply(arg: Char): Option[Char] = {

    Some(arg).filter(ws.contains)
  }
}
