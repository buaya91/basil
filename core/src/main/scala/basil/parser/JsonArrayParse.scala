package basil.parser

import basil.data._
import basil.typeclass.Lazy
import cats.data.NonEmptyMap
import cats.implicits._
import cats.{Applicative, MonadError, ~>}

import JsonArrayParse._

/**
  * TODO: model Input as `(Array[Char], Int)`
  * this will avoid having to inject `Array[Char]` when using this
  *
  * We only need 3 abilities from the input
  * a) get by index
  * b) slice
  * c) isDefinedAtIndex
  */
abstract class JsonArrayParse[F[_]](
    implicit ME: MonadError[F, ParseFailure],
) extends JsonParse[Input, Output[F, ?]] {

  private val discriminatorField: String = "type"

  type Pipe = Int => Int

  private def wrap[A](a: => A)(implicit path: Vector[PPath]): F[A] = ME.fromEither {
    try {
      Right(a)
    } catch {
      case _: ArrayIndexOutOfBoundsException => Left(ParseFailure.termination)
      case e: ParseFailure                   => Left(e)
    }
  }

  private def unsafeParseString(src: Input)(implicit path: Vector[PPath]): (String, Int) = {
    val (full, pointer) = src
    val latestI         = skipWS(full, pointer)
    val firstC          = getChar(full, latestI)

    firstC match {
      case '"' => accJsString(full, latestI + 1)
      case other =>
        throw ParseFailure(s"""String should starts with ", but found $other""", path)
    }
  }

  val parseString: Parse[String] = { implicit path => src =>
    wrap {
      unsafeParseString(src)._1
    }
  }

  private def unsafeParseBool(src: Input)(implicit path: Vector[PPath]): (Boolean, Int) = {
    val (full, ptr)    = src
    val withoutWS      = skipWS(full, ptr)
    val (isTrue, next) = full.isFollowedBy(withoutWS)("true")

    if (isTrue) {
      true -> next
    } else {
      val (isFalse, next) = full.isFollowedBy(withoutWS)("false")
      if (isFalse) {
        false -> next
      } else {
        throw ParseFailure("Expecting either `true` or `false`", path)
      }
    }
  }

  val parseBoolean: Parse[Boolean] = { implicit path => src =>
    wrap {
      unsafeParseBool(src)._1
    }
  }

  /**
    * Number is broken down into 3 parts
    *
    * 20.6e10
    *
    * part 1 - 20 terminated by .
    * part 2 - 6 terminated by e
    * part 3 - 10 terminated by terminator
    *
    * @param terminator to indicate what character terminates the number
    * @return
    */
  private def unsafeParseNum(terminator: ExpectedTerminator, input: Input)(
      implicit path: Vector[PPath]): (Double, Int) = {
    val (fullSrc, i) = input
    val start        = skipWS(fullSrc, i)

    parseNum1(fullSrc, start)(terminator) match {
      case Part1(_, Some(sep1), next) =>
        parseNum2(fullSrc, next, sep1)(terminator) match {

          case Part2(_, Some(_), next) =>
            parseNum3(fullSrc, next)(terminator) match {

              case Part3(p3, next) =>
                val full = new String(fullSrc.slice(start, p3))

                try {
                  full.toDouble -> next
                } catch {
                  case e: NumberFormatException => throw e
                }

            }
          case Part2(p2End, None, next) =>
            val full = new String(fullSrc.slice(start, p2End))

            full.toDouble -> next
        }
      case Part1(p1End, None, next) =>
        val p1 = new String(fullSrc.slice(start, p1End))
        p1.toDouble -> next
    }
  }

  def parseNumber(terminator: ExpectedTerminator): Parse[Double] = { implicit path => s =>
    wrap {
      unsafeParseNum(terminator, s)._1
    }
  }

  def parseArrayItem[I](n: Int, next: Parse[I]): Parse[I] = { implicit path => input =>
    val (src, ptr) = input

    def recurse(left: Int, path: Vector[PPath], curr: Int): Output[F, I] = {

      if (left == 0) {
        next(path)(src -> skipWS(src, curr))
      } else {

        val skip1        = skipOne(src, Comma)(path)(curr)
        val skippedWS    = skipWS(src, skip1)
        val skippedComma = skipComma(src)(path)(skippedWS)
        recurse(left - 1, path, skippedComma)
      }
    }

    wrap {
      val skipped = skipWS(src, ptr)
      getChar(src, skipped) match {
        case '[' => recurse(n, path \ n, skipped + 1)
        case u   => ME.raiseError[I](ParseFailure("[", u.toString, path \ n))
      }
    }.flatten
  }

  def parseObj[I](k: String, nextOp: Parse[I]): Parse[I] = { implicit path => stream =>
    val (src, ptr) = stream
    def skipUntilKey(curr: Int)(implicit path: Vector[PPath]): Int = {
      parseObjKey(src, curr) match {
        case (key, nextS) if key == k => nextS
        case (_, nextS) =>
          val skippedOne = skipOne(src, Comma)(path)(nextS)
          val dropComma  = skipWS(src, skippedOne) + 1
          skipUntilKey(dropComma)
      }
    }

    wrap {
      val skipped = skipWS(src, ptr)
      getChar(src, skipped) match {
        case '{' => skipUntilKey(skipped + 1)(path \ k)
        case c   => throw ParseFailure("{", c.toString, path)
      }
    }.flatMap { afterKey =>
      nextOp(path \ k)(src -> afterKey)
    }

  }

  // todo: handle unicode ??? (maybe just dont support it)
  private def accJsString(full: Array[Char], i: Int)(
      implicit path: Vector[PPath]): (String, Int) = {

    // lastCharIsSpecial - we need to know if last
    // char is special or not, so that we can
    // know if the last `\\` starts a new escape
    // sequence, eg. acc = "\\\\", then
    // we should not treat the next char as part of
    // escape sequence

    def recurse(curPointer: Int, acc: StringBuilder, lastCharIsSpecial: Boolean): (String, Int) = {

      val wasEscaped = !lastCharIsSpecial && acc.lastOption.contains('\\')

      val next = curPointer + 1

      getChar(full, curPointer) match {
        case '"' if wasEscaped =>
          val updated = acc.deleteCharAt(acc.size - 1).append('"')
          recurse(next, updated, true)
        case '/' if wasEscaped =>
          val updated = acc.deleteCharAt(acc.size - 1).append('/')
          recurse(next, updated, true)
        case 'b' if wasEscaped =>
          recurse(next, acc.append('b'), true)
        case 'f' if wasEscaped =>
          recurse(next, acc.append('f'), true)
        case 'n' if wasEscaped =>
          recurse(next, acc.append('n'), true)
        case 'r' if wasEscaped =>
          recurse(next, acc.append('r'), true)
        case 't' if wasEscaped =>
          recurse(next, acc.append('t'), true)
        case '\\' if wasEscaped =>
          val updated = acc.deleteCharAt(acc.size - 1).append('\\')
          recurse(next, updated, true)
        case oops if wasEscaped =>
          throw ParseFailure(s"Illegal escape sequence \\$oops", path)
        case '"' => (acc.mkString, next)
        case c   => recurse(next, acc.append(c), false)
      }
    }

    // Avoid slow parsing logic that is only needed for string
    // with escape char, we do so by checking if there's
    // any escape char before we find the terminating char of string
    // which is '"'
    val arrSize                  = full.length
    var firstEscape: Option[Int] = None
    var end: Option[Int]         = None
    var x                        = i
    while (end.isEmpty && firstEscape.isEmpty && x < arrSize) {
      val curr = full(x)
      if (curr == '\\') {
        firstEscape = Some(x)
      } else if (curr == '"') {
        end = Some(x)
      }
      x += 1
    }

    end match {
      case Some(e) => (new String(full.slice(i, e)), e + 1)

      case None if firstEscape.isEmpty =>
        // not reach end and no escape char, invalid
        throw ParseFailure.termination

      case None =>
        val speculateSize = (firstEscape.get - i) * 2
        recurse(i, new StringBuilder(speculateSize), lastCharIsSpecial = false)
    }

  }

  private def skipWS(fullSource: Array[Char], i: Int): Int = {

    if (fullSource.isDefinedAt(i)) {
      fullSource(i) match {
        case whitespace(_) => skipWS(fullSource, i + 1)
        case _             => i
      }
    } else {
      i
    }
  }

  private def skipStr(src: Array[Char])(implicit path: Vector[PPath]): Pipe = { s =>
    unsafeParseString(src -> s)._2
  }
  private def skipBool(src: Array[Char])(implicit path: Vector[PPath]): Pipe =
    s => unsafeParseBool(src -> s)._2

  private def skipComma(src: Array[Char])(implicit path: Vector[PPath]): Pipe = { s =>
    getChar(src, s) match {
      case ',' => s + 1
      case u   => throw ParseFailure(",", u.toString, path)
    }
  }
  private def skipNum(src: Array[Char], expectedTerminator: ExpectedTerminator)(
      implicit path: Vector[PPath]): Pipe = { s =>
    unsafeParseNum(expectedTerminator, src -> s)._2
  }

  // Does not skip intermediate terminator, eg. `,`
  private def skipOne(src: Array[Char], term: ExpectedTerminator)(
      implicit path: Vector[PPath]): Pipe = { ptr =>
    val noWS = skipWS(src, ptr)
    getChar(src, noWS) match {
      case '"'      => skipStr(src)(path)(noWS)
      case 't'      => skipBool(src)(path)(noWS)
      case 'f'      => skipBool(src)(path)(noWS)
      case digit(_) => skipNum(src, term)(path)(noWS)
      case sign(_)  => skipNum(src, term)(path)(noWS)
      case '['      => skipArr(src)(path)(noWS)
      case '{'      => skipObject(src)(path)(noWS)
      case unexp =>
        throw ParseFailure("One of(t, f, [, {)", unexp.toString, path)
    }
  }

  private def skipArr(src: Array[Char])(implicit path: Vector[PPath]): Pipe = { ptr =>
    val next = ptr + 1
    getChar(src, ptr) match {
      // expect arr to starts with [
      case '[' =>
        getChar(src, next) match {
          case ']' => next
          case _   => takeTilArrayEnd(src, next)
        }
      case ue => throw ParseFailure("[", ue.toString, path)
    }
  }

  private def takeTilArrayEnd(src: Array[Char], ptr: Int)(implicit path: Vector[PPath]): Int = {
    val skippedSep = skipOne(src, ExpectedTerminator.arrayTerm)(path)(ptr)
    val skippedWs  = skipWS(src, skippedSep)

    getChar(src, skippedWs) match {
      case ']'   => skippedWs + 1
      case ','   => takeTilArrayEnd(src, skippedWs + 1)
      case unexp => throw ParseFailure(",", unexp.toString, path)
    }

  }

  private def parseObjKey(src: Array[Char], ptr: Int)(
      implicit path: Vector[PPath]): (String, Int) = {
    val skipped = skipWS(src, ptr)

    getChar(src, skipped) match {
      case '"' =>
        val keyStr = accJsString(src, skipped + 1)

        keyStr match {
          case (key, afterKey) =>
            val skipAfterKey = skipWS(src, afterKey)
            getChar(src, skipAfterKey) match {
              case ':' => (key, skipAfterKey + 1)
              case o =>
                throw ParseFailure(s": for key ${key.mkString} finding", o.toString, path)
            }
        }

      case uexp => throw ParseFailure("\" to start a key", uexp.toString, path)
    }
  }

  private def skipKVPairs(src: Array[Char], ptr: Int)(implicit path: Vector[PPath]): Int = {
    parseObjKey(src, ptr) match {
      case (_, next) =>
        val skippedOne = skipOne(src, ExpectedTerminator.objTerm)(path)(next)
        val skippedWS  = skipWS(src, skippedOne)
        getChar(src, skippedWS) match {
          case ','  => skipKVPairs(src, skippedWS + 1)
          case '}'  => skippedWS
          case uexp => throw ParseFailure(", or }", uexp.toString, path)
        }

    }
  }

  private def skipObject(src: Array[Char])(implicit path: Vector[PPath]): Pipe = { ptr =>
    getChar(src, ptr) match {
      case '{' =>
        val skipKVs = skipKVPairs(src, ptr + 1)
        getChar(src, skipKVs) match {
          case '}'  => skipKVs + 1
          case uexp => throw ParseFailure("}", uexp.toString, path)
        }

      case uexp =>
        throw ParseFailure("{", uexp.toString, path)
    }
  }
  private def parseSign(src: Array[Char], ptr: Int): (Option[Char], Int) = {
    getChar(src, ptr) match {
      case sign(sChar) => Some(sChar) -> (ptr + 1)
      case _           => None        -> ptr
    }
  }

  implicit class SourceOps[A](s: F[Option[A]]) {
    def flatFold[B](f: A => F[B])(orElse: () => F[B]): F[B] = {
      s.flatMap {
        case Some(a) => f(a)
        case None    => orElse()
      }
    }
  }

  private case class Part1(until: Int, sep: Option[Char], next: Int)
  private case class Part2(until: Int, sep: Option[Char], next: Int)
  private case class Part3(until: Int, next: Int)

  private def consumeTillTermination[A](src: Array[Char], ptr: Int)(
      term: ExpectedTerminator,
      f: Int => A)(implicit p: Vector[PPath]): A = {
    getChar(src, ptr) match {
      case c if term.matchChar(c) => f(ptr)
      case uexp                   => throw ParseFailure(term.toString, uexp.toString, p)
    }
  }

  private def parseNum1(src: Array[Char], ptr: Int)(term: ExpectedTerminator)(
      implicit p: Vector[PPath]): Part1 = {

    def recurse(acc: Int, curr: Int): Part1 = {
      val next = curr + 1

      getCharOpt(src, curr) match {
        case Some(c) =>
          c match {
            case digit(_)               => recurse(acc + 1, next)
            case t if term.matchChar(t) => Part1(acc, None, curr)
            case '.'                    => Part1(acc, Some('.'), next)
            case 'E'                    => Part1(acc, Some('e'), next)
            case 'e'                    => Part1(acc, Some('e'), next)
            case whitespace(_)          =>
              // is this correct? ie. can we allow ws in the middle of number?
              // todo: check the spec
              consumeTillTermination(src, next)(term, n => Part1(acc, None, n))
            case u =>
              throw ParseFailure(s"Digit or $term", u.toString, p)
          }
        case None =>
          // todo: Need to consider OneOF
          // might want to rethink if
          // it makes sense to use OneOf ot capture everything
          if (term.isEnd && acc > ptr) {
            Part1(acc, None, curr)
          } else {
            throw ParseFailure.termination
          }
      }
    }

    val (maybeSign, next) = parseSign(src, ptr)
    val acc               = maybeSign.fold(ptr)(_ => ptr + 1)
    recurse(acc, next)
  }

  private def parseNum2(src: Array[Char], ptr: Int, p1Sep: Char)(term: ExpectedTerminator)(
      implicit p: Vector[PPath]): Part2 = {
    def recurse(acc: Int, curr: Int): Part2 = {
      val next = curr + 1
      getCharOpt(src, curr) match {
        case Some(c) =>
          c match {
            case digit(_)                    => recurse(acc + 1, next)
            case t if term.matchChar(t)      => Part2(acc, None, curr)
            case exponent(e) if p1Sep == '.' => Part2(acc, Some(e), next)
            case whitespace(_) =>
              consumeTillTermination(src, next)(term, n => Part2(acc, None, n))
            case u =>
              throw ParseFailure(s"Digit or $term", u.toString, p)
          }
        case None =>
          if (term.isEnd && acc > ptr) {
            Part2(acc, None, curr)
          } else {
            throw ParseFailure.termination
          }
      }
    }

    recurse(ptr, ptr)
  }

  private def parseNum3(src: Array[Char], ptr: Int)(term: ExpectedTerminator)(
      implicit path: Vector[PPath]): Part3 = {

    def recurse(acc: Int, curr: Int): Part3 = {
      val next = curr + 1
      getCharOpt(src, curr) match {
        case Some(c) =>
          c match {
            case digit(_)               => recurse(acc + 1, next)
            case t if term.matchChar(t) => Part3(acc, curr)
            case whitespace(_) =>
              consumeTillTermination(src, next)(term, n => Part3(acc, n))
            case u =>
              throw ParseFailure(s"Digit or $term", u.toString, path)
          }
        case None =>
          if (term.isEnd && acc > ptr) {
            Part3(acc, curr)
          } else {
            throw ParseFailure.termination
          }
      }
    }

    val (maybeSign, next) = parseSign(src, ptr)
    val acc               = maybeSign.fold(ptr)(_ => ptr + 1)

    recurse(acc, next)
  }

  implicit val ParseApp: Applicative[Parse] = new Applicative[Parse] {
    override def pure[A](x: A): Parse[A] = { path => src =>
      ME.pure(x)
    }
    override def ap[A, B](ff: Parse[A => B])(fa: Parse[A]): Parse[B] = { path => src =>
      for {
        fn <- ff(path)(src): F[A => B]
        a  <- fa(path)(src): F[A]
      } yield {
        fn(a)
      }
    }
  }

  def parseOneOf[I](oneOf: NonEmptyMap[String, Lazy[Parse[I]]]): Parse[I] = { path => src =>
    (parseObj(discriminatorField, parseString)(path)(src): F[String]).flatMap { key =>
      oneOf(key) match {
        case Some(parseFn) => parseFn.value(path)(src)
        case None =>
          ME.raiseError[I](ParseFailure(s"Cannot parse object with type=$key"))
      }
    }
  }

  def parseOptional[I](parse: Parse[I]): Parse[Option[I]] = { path => src =>
    (parse(path)(src): F[I])
      .map[Option[I]] {

        // WARNING: runtime flattening of None
        // We dont flatten nested Some (eg. Some(Some(x)) => Some(x))
        // because that would break the type
        // ie. None <: Option[Option[Option[?]]] is generally true
        // but Some(x) <: Option[Option[Option[?]]] is not
        case None => None
        case i    => Some(i)
      }
      .recover {
        case _ => None
      }
  }

  val parsing: ParseOps[Parse, ?] ~> Parse = parsingM

}

object JsonArrayParse {

  type Input           = (Array[Char], Int)
  type Output[F[_], A] = F[A]

  implicit class StringOps(fullSource: Array[Char]) {
    def isFollowedBy(i: Int)(str: String): (Boolean, Int) = {
      val slice = fullSource.slice(i, i + str.length)
      slice.sameElements(str.toCharArray) -> (i + str.length)
    }
  }

  implicit class PointerOps(input: Input) {
    def isFollowedBy(str: String): (Boolean, Int) = {
      val (fullSource, i) = input
      val slice           = fullSource.slice(i, i + str.length)
      slice.sameElements(str.toCharArray) -> (i + str.length)
    }

    def next: Input         = move(1)
    def move(i: Int): Input = (input._1, input._2 + i)

    def pointTo(i: Int): Input = input._1 -> i
  }

  private def getChar(fullSource: Array[Char], i: Int): Char = {
    fullSource(i)
  }

  private def getCharOpt(fullSource: Array[Char], i: Int): Option[Char] = {
    try {
      Some(fullSource(i))
    } catch {
      case _: ArrayIndexOutOfBoundsException => None
    }
  }

}
