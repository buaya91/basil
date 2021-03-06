package basil.derive

import basil.derive.DeriveParseOps._
import basil.parser.Parser
import basil.parser.implicits._
import basil.syntax.ParseOpsConstructor._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{pretty, render}
import org.scalatest.{MustMatchers, WordSpec}

class DeriveParseSpec extends WordSpec with MustMatchers {
  sealed trait Status
  case object Married extends Status
  case object Single  extends Status

  case class Person(name: String, age: Double, married: Status, life: Option[String])
  case class Order(id: String, size: String, belongsTo: Person)

  "Able to derive ParseOp for nested case class" in {
    val what = Start.getType[Order].eval
    val js = ("id" -> "hoho") ~
      ("size" -> "20") ~
      ("belongsTo" ->
        ("name"      -> "Qing") ~
          ("age"     -> 20) ~
          ("married" -> ("type" -> "Married")) ~
          ("life"    -> "hoho"))

    val jsString = pretty(render(js))
    val res      = Parser.parseString(what, jsString)

    res mustBe Right(Order("hoho", "20", Person("Qing", 20, Married, Some("hoho"))))
  }

  sealed trait Dir
  case class L(i: String)         extends Dir
  case class R(i: String)         extends Dir
  case class More(a: Dir, b: Dir) extends Dir

  "Able to derive recursive ADT" in {
    val tree = Start.getType[Dir].eval
    val js = ("type" -> "More") ~ ("a" -> (
      ("type" -> "L") ~
        ("i"  -> "left....")
    )) ~ ("b" -> (
      ("type" -> "R") ~
        ("i"  -> "left....")
    ))

    val str = pretty(render(js))
    val r   = Parser.parseString(tree, str)
    r mustBe Right(More(L("left...."), R("left....")))
  }
}
