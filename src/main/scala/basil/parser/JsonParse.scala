package basil.parser

import basil.data._
import basil.typeclass.{Cons, JsRepr, TakeOne}
import basil.typeclass.TakeOne._
import cats.MonadError
import cats.instances.char._
import cats.kernel.Monoid
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

abstract class JsonParse[Source[_], JVal](implicit TakeOne: TakeOne[Source],
                                          ME: MonadError[Source, ParseFailure],
                                          Monoid: Monoid[Source[Char]],
                                          Cons: Cons[Source],
                                          jsReps: JsRepr[JVal]) {

  type CharSource = Source[Char]

  import jsReps._

  type Parse = Vector[PPath] => CharSource => Source[(JVal, CharSource)]

  type Pipe = CharSource => CharSource

  private def skipStr(implicit path: Vector[PPath]): Pipe = { s =>
    parseString(path)(s).flatMap[Char](_._2)
  }
  private def skipBool(implicit path: Vector[PPath]): Pipe =
    s => parseBoolean(path)(s).flatMap[Char](_._2)

  private def skipComma(implicit path: Vector[PPath]): Pipe = { s =>
    s.take1.flatMap {
      case (',', next) => next
      case (u, _)      => ME.raiseError(ParseFailure(",", u.toString, path))
    }
  }

  private def skipNum(expectedTerminator: ExpectedTerminator)(
      implicit path: Vector[PPath]): Pipe = { s =>
    parseNumber(expectedTerminator)(path)(s).flatMap[Char](_._2)
  }

  // Does not skip intermediate terminator, eg. `,`
  private def skipOne(term: ExpectedTerminator)(implicit path: Vector[PPath]): Pipe = { stream =>
    stream.peek1.flatMap {
      case ('"', next)      => skipStr(path)(next)
      case ('t', next)      => skipBool(path)(next)
      case ('f', next)      => skipBool(path)(next)
      case (digit(_), next) => skipNum(term)(path)(next)
      case (sign(_), next)  => skipNum(term)(path)(next)
      case ('[', next)      => skipArr(path)(next)
      case ('{', next)      => skipObject(path)(next)
      case (unexp, _)       => ME.raiseError(ParseFailure("One of(t, f, [, {)", unexp.toString, path))
    }
  }

  private def skipArr(implicit path: Vector[PPath]): Pipe = { stream =>
    stream.take1.flatMap {
      // expect arr to starts with [
      case ('[', next) =>
        next.peek1.flatMap[Char] {
          // check if it's empty array
          case (']', next) => next.drop1
          case (_, next)   => takeTilArrayEnd(path)(next)
        }
      case (ue, _) => ME.raiseError(ParseFailure("[", ue.toString, path))
    }
  }

  private def takeTilArrayEnd(implicit path: Vector[PPath]): Pipe = { s =>
    skipOne(OneOf(List(Bracket, Comma)))(path)(s).peek1.flatMap {
      case (']', next) => next.drop1
      case (',', next) => takeTilArrayEnd(path)(next.drop1)
      case (unexp, _)  => ME.raiseError(ParseFailure(",", unexp.toString, path))
    }
  }

  private def parseObjKey(stream: CharSource)(
      implicit path: Vector[PPath]): Source[(String, CharSource)] = {

    stream.take1.flatMap {
      case ('"', nextStream) =>
        val keyStr = nextStream.accUntil(_ == '"')

        keyStr.flatMap {
          case (key, afterKey) =>
            afterKey.take1.flatMap {
              case (':', next) => ME.pure(key.mkString -> next)
              case (o, _)      => ME.raiseError(ParseFailure(": for key finding", o.toString, path))
            }
        }

      case (uexp, _) => ME.raiseError(ParseFailure("\" to start a key", uexp.toString, path))
    }
  }

  private def skipKVPair(implicit path: Vector[PPath]): Pipe = { s =>
    parseObjKey(s).flatMap {
      case (_, next) =>
        skipOne(OneOf(List(Comma, CurlyBrace)))(path)(next).peek1.flatMap {
          case (',', next) => skipKVPair(path)(next.drop1)
          case ('}', next) => next
          case (uexp, _)   => ME.raiseError(ParseFailure(", or }", uexp.toString, path))
        }
    }
  }

  private def skipObject(implicit path: Vector[PPath]): Pipe = { s =>
    s.take1.flatMap {
      case ('{', next) =>
        skipKVPair(path)(next).take1.flatMap[Char] {
          case ('}', next) => next
          case (uexp, _)   => ME.raiseError(ParseFailure("}", uexp.toString, path))
        }
      case (uexp, _) => ME.raiseError[Char](ParseFailure("{", uexp.toString, path))
    }
  }

  def parseString: Parse = { implicit path => src =>
    src.take1
      .flatMap {
        case ('"', next) => next.accUntil(_ == '"')
        case (other, _) =>
          ME.raiseError[(Vector[Char], Source[Char])](
            ParseFailure(s"""String should starts with ", but found $other""", path))
      }
      .map {
        case (acc, next) => str(acc.mkString) -> next
      }
  }
  def parseBoolean: Parse = { implicit path => src =>
    src.isFollowedBy("true".toCharArray.toList).flatMap {
      case (isTrue, next) =>
        if (isTrue) {
          ME.pure(bool(true) -> next)
        } else {
          src.isFollowedBy("false".toCharArray.toList).flatMap {
            case (isFalse, next) =>
              if (isFalse) {
                ME.pure(bool(false) -> next)
              } else {
                ME.raiseError(ParseFailure("Expecting either `true` or `false`", path))
              }
          }
        }
    }
  }

  private def parseSign(s: CharSource): Source[(Option[Char], CharSource)] = {
    s.take1.flatMap {
      case (sign(sChar), next) => ME.pure(Some(sChar) -> next)
      case _                   => ME.pure(None        -> s)
    }
  }

  implicit class SourceOps[A](s: Source[(Option[A], CharSource)]) {
    def flatFold[B](f: (A, CharSource) => Source[B])(orElse: Source[B]): Source[B] = {
      s.flatMap {
        case (Some(a), next) => f(a, next)
        case (None, _)       => orElse
      }
    }
  }

  private case class Part1(part1: Vector[Char], sep: Option[Char], cont: CharSource)
  private case class Part2(part2: Vector[Char], sep: Option[Char], cont: CharSource)

  private def parseNum1(s: CharSource)(term: ExpectedTerminator)(
      implicit p: Vector[PPath]): Source[Part1] = {
    def recurse(acc: Vector[Char], s: CharSource): Source[Part1] = {
      s.take1Opt.flatFold {
        case (digit(d), next)            => recurse(acc :+ d, next)
        case (t, _) if term.matchChar(t) => ME.pure(Part1(acc, None, s))
        case ('.', next)                 => ME.pure(Part1(acc, Some('.'), next))
        case ('E', next)                 => ME.pure(Part1(acc, Some('e'), next))
        case ('e', next)                 => ME.pure(Part1(acc, Some('e'), next))
        case (u, _)                      => ME.raiseError[Part1](ParseFailure(s"Digit or $term", u.toString, p))
      } {
        if (term == End && acc.nonEmpty) {
          ME.pure(Part1(acc, None, Monoid.empty))
        } else {
          ME.raiseError(ParseFailure.termination)
        }
      }
    }
    parseSign(s).flatMap {
      case (maybe, next) => recurse(maybe.toVector, next)
    }
  }

  private def parseNum2(s: CharSource, p1Sep: Char)(term: ExpectedTerminator)(
      implicit p: Vector[PPath]): Source[Part2] = {
    def recurse(acc: Vector[Char], s: CharSource): Source[Part2] = {
      s.take1Opt.flatFold {
        case (digit(d), next)                    => recurse(acc :+ d, next)
        case (t, _) if term.matchChar(t)         => ME.pure(Part2(acc, None, s))
        case (exponent(e), next) if p1Sep == '.' => ME.pure(Part2(acc, Some(e), next))
        case (u, _)                              => ME.raiseError[Part2](ParseFailure(s"Digit or $term", u.toString, p))
      } {
        if (term == End && acc.nonEmpty) {
          ME.pure(Part2(acc, None, Monoid.empty))
        } else {
          ME.raiseError(ParseFailure.termination)
        }
      }
    }
    recurse(Vector.empty, s)
  }

  private def parseNum3(s: CharSource)(term: ExpectedTerminator)(
      implicit path: Vector[PPath]): Source[(Vector[Char], CharSource)] = {
    def recurse(acc: Vector[Char], s: CharSource): Source[(Vector[Char], CharSource)] = {
      s.take1Opt.flatFold {
        case (digit(d), next)            => recurse(acc :+ d, next)
        case (t, _) if term.matchChar(t) => ME.pure(acc -> s)
        case (u, _) =>
          ME.raiseError[(Vector[Char], CharSource)](
            ParseFailure(s"Digit or $term", u.toString, path))
      } {
        if (term == End && acc.nonEmpty) {
          ME.pure(acc -> s)
        } else {
          ME.raiseError(ParseFailure.termination)
        }
      }
    }
    parseSign(s).flatMap {
      case (maybe, next) => recurse(maybe.toVector, next)
    }
  }

  def parseNumber(terminator: ExpectedTerminator): Parse = { implicit path => s =>
    parseNum1(s)(terminator)
      .flatMap {
        case Part1(p1, Some(sep1), next) =>
          parseNum2(next, sep1)(terminator).flatMap {
            case Part2(p2, Some(sep2), next) =>
              parseNum3(next)(terminator).flatMap {
                case (p3, next) =>
                  val full = (p1 :+ sep1) ++ (p2 :+ sep2) ++ p3
                  ME.pure(full -> next)
              }
            case Part2(p2, None, next) =>
              val full = (p1 :+ sep1) ++ p2
              ME.pure(full -> next)
          }
        case Part1(p1, None, next) => ME.pure(p1 -> next)
      }
      .map {
        case (numChars, next) => num(numChars.mkString.toDouble) -> next
      }
  }
  def parseArrayItem(n: Int, next: Parse): Parse = { implicit path => s =>
    def recurse(left: Int): Parse = { implicit path => stream =>
      if (left == 0) {
        next(path)(stream)
      } else {
        val skip1        = skipOne(Comma)(path)(stream)
        val skippedComma = skipComma(path)(skip1)
        val end          = recurse(left - 1)(path)(skippedComma)
        end
      }
    }

    s.take1.flatMap {
      case ('[', next) => recurse(n)(path)(next)
      case (u, _)      => ME.raiseError(ParseFailure("[", u.toString, path \ n))
    }
  }

  def parseObj(k: String, nextOp: Parse): Parse = { implicit path => stream =>
    def skipUntilKey(implicit path: Vector[PPath]): Pipe = { s =>
      parseObjKey(s).take1.flatMap[Char] {
        case ((key, nextS), _) if key == k => nextS
        case ((_, nextS), _)               => skipUntilKey(path)(nextS)
      }
    }

    if (k.isEmpty) {
      ME.raiseError(ParseFailure("Key not found", "", path \ k))
    } else {
      nextOp(path \ k) {
        stream.take1.flatMap {
          case ('{', next) => skipUntilKey(path \ k)(next)
          case (c, _)      => ME.raiseError(ParseFailure("{", c.toString, path))
        }
      }
    }

  }

  def parsing: ParseOps[Parse] => Parse = {
    case GetString         => parseString
    case GetBool           => parseBoolean
    case GetNum(t)         => parseNumber(t)
    case GetN(n, next)     => parseArrayItem(n, next)
    case GetKey(key, next) => parseObj(key, next)
    case GetNullable(ops) =>
      path => in =>
        ops(path ?)(in).handleErrorWith { _ =>
          ME.pure(Null -> in)
        }
  }
}
