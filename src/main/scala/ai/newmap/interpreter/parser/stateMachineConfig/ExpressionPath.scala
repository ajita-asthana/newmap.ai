package ai.newmap.interpreter.parser.stateMachineConfig

import ai.newmap.interpreter.parser.stateMachine.ParseState
import ai.newmap.interpreter.Lexer
import ai.newmap.interpreter.Lexer._
import ai.newmap.model._
import ai.newmap.util.{Failure, Success, Outcome}
import scala.collection.mutable.ListBuffer

object ExpressionPath {

  case class ExpressionInBinaryOpNoRight(
    symbol: String,
    firstParameter: ParseState[ParseTree]
  ) extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = {
      val state = InitState()

      for {
        rightState <- state.update(token)
      } yield {
        ExpressionInBinaryOp(symbol, firstParameter, rightState)
      }
    }
  }

  case class ExpressionInBinaryOp(
    symbol: String,
    firstParameter: ParseState[ParseTree],
    secondParameter: ParseState[ParseTree]
  ) extends ParseState[ParseTree] {

    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = {
      // Test if the second parameter is complete
      val secondParameterComplete = secondParameter.generateOutput
      
      if (secondParameterComplete.isEmpty) {
        // If the second parameter is not complete, then the new token must be for the second parameter
        for {
          newSecondParameter <- secondParameter.update(token)
        } yield this.copy(secondParameter = newSecondParameter)
      } else {
        val connectiveSymbol = token match {
          case Symbol(s) => s
          case _ => ""
        }

        val thisPrecedence = symbolPrecedence(connectiveSymbol)
        val currPrecedence = symbolPrecedence(symbol)

        val thisSymbolAssociation = symbolAssociation(connectiveSymbol)

        val applyToSecondParam = (thisPrecedence > currPrecedence) || (
          (connectiveSymbol == symbol) && (thisSymbolAssociation == Right)
        )

        //println(s"connectiveSymbol: $connectiveSymbol -- thisPrecedence: $thisPrecedence")
        //println(s"symbol: $symbol -- currPrecedence: $currPrecedence -- thisSymbolAssociation: $thisSymbolAssociation")
        //println(s"applyToSecondParam: $applyToSecondParam")

        if (applyToSecondParam) {
          for {
            newSecondParameter <- secondParameter.update(token)
          } yield this.copy(secondParameter = newSecondParameter)
        } else {
          token match {
            case Symbol(s) => Success(ExpressionInBinaryOpNoRight(connectiveSymbol, this))
            case _ => {
              val initSecondParameter = InitState()
              for {
                newSecondParameter <- initSecondParameter.update(token)
              } yield ExpressionInBinaryOp(connectiveSymbol, this, newSecondParameter)
            }
          }
        }
      }
    }

    override def generateOutput: Option[ParseTree] = {
      for {
        firstExp <- firstParameter.generateOutput
        secondExp <- secondParameter.generateOutput
      } yield symbol match {
        case "." | "|" => ConstructCaseParse(firstExp, secondExp)
        case ":" => KeyValueBinding(firstExp, secondExp)
        case "," => {
          firstExp match {
            case LiteralListParse(items, MapType) => {
              LiteralListParse(items :+ secondExp, MapType)
            }
            case _ => LiteralListParse(Vector(firstExp, secondExp), MapType)
          }
        }
        case "=>" => LambdaParse(firstExp, secondExp)
        case "" => ApplyParse(firstExp, secondExp)
        case _ => {
          throw new Exception("Unexpected symbol: " + symbol)
        }
      }
    }
  }

  case class ExpressionStart(parseTree: ParseTree) extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = token match {
      // TODO - these items shouldn't be lexed as symbols!!! 
      case Symbol(s) if (s != "`" && s != "~") => {
        val thisPredecence = symbolPrecedence(s)
        Success(ExpressionInBinaryOpNoRight(s, this))
      }
      case _ => ExpressionInBinaryOpNoRight("", this).update(token)
    }

    override def generateOutput: Option[ParseTree] = {
      Some(parseTree)
    }
  }

  case class ExpressionInEnc(
    enc: EnclosureSymbol,
    exp: ParseState[ParseTree] = InitState()
  ) extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = {
      exp.update(token) match {
        case Success(result) => Success(ExpressionInEnc(enc, result))
        case Failure(reason) => token match {
          case Enc(encS, false) => {
            if (encS == enc) {
              exp.generateOutput match {
                case Some(parseTree) => {
                  val expression = enc match {
                    case Paren => parseTree match {
                      case EmptyParse => LiteralListParse(Vector.empty, MapType)
                      case _ => parseTree
                    }
                    case SquareBracket => parseTree match {
                      case EmptyParse => LiteralListParse(Vector.empty, ArrayType)
                      case LiteralListParse(values, _) => LiteralListParse(values, ArrayType) // Square brackets indicate an array
                      case _ => LiteralListParse(Vector(parseTree), ArrayType)
                    }
                    case _ => {
                      //TODO: handle this properly
                      throw new Exception("Curly Brace Alert")
                    }
                  }

                  Success(ExpressionStart(expression))
                }
                case None => Failure("Tried to close " + Lexer.closedFormOfEnclosure(enc) + " with unfinished expression " + exp.toString)
              }
            } else {
              Failure("Tried to close " + Lexer.closedFormOfEnclosure(encS) + " but it matched with " + Lexer.openFormOfEnclosure(enc))
            }
          }
          case _ => Failure(reason)
        }
      }
    }
  }

  case class UnaryExpression(s: String, internalExpression: ParseState[ParseTree]) extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = {
      internalExpression.update(token) match {
        case Success(newInternalExpression) => Success(this.copy(internalExpression = newInternalExpression))
        case Failure(reason) => {
          generateOutput match {
            case Some(expression) => ExpressionStart(expression).update(token)
            case None => Failure("Unary Expression Failure: " + s + " -- " + internalExpression + " -- " + token + " -- " + reason)
          }
        }
      }
    }

    override def generateOutput: Option[ParseTree] = {
      internalExpression.generateOutput.map(expression => {
        val symbolAsIdentifier = IdentifierParse(s, force = false)
        ApplyParse(symbolAsIdentifier, expression)
      })
    }
  }

  case class ExpressionForceId() extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = token match {
      case Identifier(id) => Success(ExpressionStart(IdentifierParse(id, true)))
      case _ => Failure("Expected identifier after ~")
    }
  }

  case class ExpressionTickMark() extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = token match {
      case Identifier(id) => Success(ExpressionStart(CharacterParse(id)))
      case Number(n) => Success(ExpressionStart(CharacterParse(n.toString)))
      case _ => Failure("Token " + token + " doesn't go after a tick mark")
    }
  }

  case class InitState() extends ParseState[ParseTree] {
    override def update(token: Lexer.Token): Outcome[ParseState[ParseTree], String] = token match {
      case Enc(enc, true) => enc match {
        case Paren => Success(ExpressionInEnc(Paren))
        case SquareBracket => Success(ExpressionInEnc(SquareBracket))
        case CurlyBrace => Failure("We do not currently support the curly brace")
      }
      case Enc(symbol, false) => {
        Failure("Unmatched enclosure symbol: " + Lexer.closedFormOfEnclosure(symbol))
      }
      case Identifier(id) => Success(ExpressionStart(IdentifierParse(id)))
      case Number(i) => Success(ExpressionStart(NaturalNumberParse(i)))
      case Symbol("~") => Success(ExpressionForceId())
      case Symbol("`") => Success(ExpressionTickMark())
      case Symbol(s) => Success(UnaryExpression(s, InitState()))
      case DQuote(s) => Success(ExpressionStart(StringParse(s)))
      case Comment(_) => Success(this) 
    }

    override def generateOutput: Option[ParseTree] = Some(EmptyParse)
  }

  def symbolPrecedence(symbol: String): Int = symbol match {
    case "|" => 11
    case "" => 10
    case "." => 9
    case "^" => 8
    case "*" | "/" => 7
    case "+" | "-" => 6
    case ":" => 5
    case "==" => 4
    case "," => 3
    case "=>" => 1
    case _ => {
      println("*** UNKNOWN SYMBOL: " + symbol)
      0
    }
  }

  sealed abstract class SymbolAssociation
  case object Right extends SymbolAssociation
  case object Left extends SymbolAssociation

  def symbolAssociation(symbol: String): SymbolAssociation = symbol match {
    case "=>" => Right
    case _ => Left
  }
}