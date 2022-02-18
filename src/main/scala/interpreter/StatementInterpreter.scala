package ai.newmap.interpreter

import ai.newmap.model._
import ai.newmap.interpreter.TypeChecker._
import ai.newmap.util.{Outcome, Success, Failure}

object StatementInterpreter {
  /*
   * @param sParse The statement parse
   * @param env This is a map of identifiers which at this point are supposed to be subsituted.
   */
  def apply(
    sParse: EnvStatementParse,
    env: Environment
  ): Outcome[Vector[EnvironmentCommand], String] = {
    sParse match {
      case FullStatementParse(_, id, typeExpression, objExpression) => {
        for {
          nType <- TypeChecker.typeSpecificTypeChecker(typeExpression, env)
          tc <- TypeChecker.typeCheck(objExpression, nType, env)
          evaluatedObject <- Evaluator(tc, env)
        } yield {
          Vector(FullEnvironmentCommand(IdentifierInstance(id.s), evaluatedObject))
        }
      }
      case InferredTypeStatementParse(_, id, objExpression) => {
        for {
          tc <- TypeChecker.typeCheck(objExpression, AnyT, env)
          evaluatedObject <- Evaluator(tc, env)
        } yield {
          Vector(FullEnvironmentCommand(IdentifierInstance(id.s), evaluatedObject))
        }
      }
      case ExpressionOnlyStatementParse(exp) => {
        for {
          tc <- TypeChecker.typeCheck(exp, AnyT, env)
          evaluatedObject <- Evaluator(tc, env)
        } yield {
          Vector(ExpOnlyEnvironmentCommand(evaluatedObject))
        }
      }
    }
  }
}