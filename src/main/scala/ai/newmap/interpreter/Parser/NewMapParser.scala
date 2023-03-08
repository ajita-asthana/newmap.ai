package ai.newmap.interpreter

import ai.newmap.model.{EnvStatementParse, ParseTree}
import ai.newmap.util.{Failure, Outcome, Success}

object NewMapParser {
  def apply(
             tokens: Seq[Lexer.Token]
           ): Outcome[ParseTree, String] = {

    val result = NewMapStateMachineParser(tokens)

    result match {

      case Failure(v) =>
        if(v.equals("Unimplemented")) NewMapCombinatorParser(tokens)
        else ai.newmap.util.Failure(v)
      case Success(parseTree: ParseTree) => ai.newmap.util.Success(parseTree)
    }
  }

  def statementParse(
                      tokens: Seq[Lexer.Token]
                    ): Outcome[EnvStatementParse, String] = {
    val statementParse = NewMapStateMachineParser.statementParse(tokens)

    statementParse match {

      case Failure(v) =>
        if (v.equals("Unimplemented")) NewMapCombinatorParser.statementParse(tokens)
        else ai.newmap.util.Failure(v)
      case Success(envStatementParse: EnvStatementParse) => ai.newmap.util.Success(envStatementParse)
    }
  }
}
