package ai.newmap.interpreter.parser.stateMachineConfig

import ai.newmap.interpreter.parser.stateMachine.{ParseState, ParseStateUtils}
import ai.newmap.interpreter.Lexer
import ai.newmap.interpreter.Lexer.{Identifier, Symbol}
import ai.newmap.model._
import ai.newmap.util.{Failure, Success, Outcome}
import scala.collection.mutable.ListBuffer

object VersionedPath {
  case class VersionedIdentifierEqualsNew(id: String, val expressionState: ParseState[ParseTree] = ExpressionPath.InitState()) extends ParseState[EnvStatementParse] {
    override def update(token: Lexer.Token): Outcome[ParseState[EnvStatementParse], String] = for {
      newExpressionState <- expressionState.update(token)
    } yield {
      this.copy(expressionState = newExpressionState)
    }

    override def generateOutput: Option[EnvStatementParse] = {
      for {
        parseTree <- expressionState.generateOutput
      } yield {
        NewVersionedStatementParse(IdentifierParse(id), parseTree)
      }
    }
  }

  case class VersionedIdentifierEquals(id: String) extends ParseState[EnvStatementParse] {
    override def update(token: Lexer.Token): Outcome[ParseState[EnvStatementParse], String] = token match {
      case Identifier("new") => Success(VersionedIdentifierEqualsNew(id))
      case _ => Failure("Expected new, got " + token.toString)
    }
  }

  case class VersionedIdentifier(id: String) extends ParseState[EnvStatementParse] {
    override def update(token: Lexer.Token): Outcome[ParseState[EnvStatementParse], String] = token match {
      case Symbol("=") => Success(VersionedIdentifierEquals(id))
      case _ => Failure("Expected =, got " + token.toString)
    }
  }

  case class InitState() extends ParseState[EnvStatementParse] {
    override def update(token: Lexer.Token): Outcome[ParseState[EnvStatementParse], String] = {
      ParseStateUtils.expectingIdentifier(token, id => VersionedIdentifier(id))
    }
  }
}