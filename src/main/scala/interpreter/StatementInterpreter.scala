package ai.newmap.interpreter

import ai.newmap.model._
import ai.newmap.interpreter.TypeChecker._
import ai.newmap.util.{Outcome, Success, Failure}

object StatementInterpreter {
  case class Response(
    commands: Vector[EnvironmentCommand],
    output: String
  )

  /*
   * @param sParse The statement parse
   * @param env This is a map of identifiers which at this point are supposed to be subsituted.
   */
  def apply(
    sParse: EnvStatementParse,
    env: Environment
  ): Outcome[Response, String] = {
    sParse match {
      case FullStatementParse(_, id, typeExpression, objExpression) => {
        for {
          tcType <- TypeChecker.typeCheck(typeExpression, ObjectPattern(UType(TypeT)), env, FullFunction)
          nTypeObj <- Evaluator(tcType.nExpression, env)

          nType <- Evaluator.asType(nTypeObj, env)
          tc <- TypeChecker.typeCheck(objExpression, ObjectPattern(UType(nType)), env, FullFunction)
          evaluatedObject <- Evaluator(tc.nExpression, env)
          constantObject = Evaluator.stripVersioningU(evaluatedObject, env)

          nObject <- TypeChecker.tagAndNormalizeObject(constantObject, ObjectPattern(UType(nType)), env)
        } yield {
          val command = FullEnvironmentCommand(id.s, nObject)
          Response(Vector(command), command.toString)
        }
      }
      case NewVersionedStatementParse(id, typeExpression) => {
        for {
          tcType <- typeCheck(typeExpression, ObjectPattern(UType(TypeT)), env, FullFunction)
          nTypeObj <- Evaluator(tcType.nExpression, env)
          nType <- Evaluator.asType(nTypeObj, env)

          // TODO: Maybe a special error message if this is not a command type
          // - In fact, we have yet to build an actual command type checker
          initValue <- CommandMaps.getDefaultValueOfCommandType(nTypeObj, env)
        } yield {
          val command = NewVersionedStatementCommand(id.s, nType)
          Response(Vector(command), command.toString)
        }
      }
      case NewTypeStatementParse(id, typeExpression) => {
        for {
          tcType <- typeCheck(typeExpression, ObjectPattern(UType(TypeT)), env, FullFunction)
          nTypeObj <- Evaluator(tcType.nExpression, env)
          nType <- Evaluator.asType(nTypeObj, env)
        } yield {
          val command = NewTypeCommand(id.s, nType)
          Response(Vector(command), command.toString)
        }
      }
      case NewParamTypeStatementParse(id, params) => {
        val values = params match {
          case CommandList(vs) => vs
          case _ => Vector(params) 
        }

        for {
          mapValues <- TypeChecker.typeCheckMap(values, ObjectPattern(UType(IdentifierT)), ObjectPattern(UType(TypeT)), BasicMap, env, FullFunction)
          paramList <- convertMapValuesToParamList(mapValues, env)
        } yield {
          val paramType = TaggedObject(
            UParametrizedCaseT(
              paramList,
              CaseT(Vector.empty, IdentifierT)
            ),
            MapT(
              StructT(mapValues, IdentifierT), // TODO: if mapValues has length 1 - should we simplify to the single value?
              TypeT,
              MapConfig(RequireCompleteness, SimpleFunction)
            )
          )

          val command = NewParamTypeCommand(id.s, paramType)
          Response(Vector(command), command.toString)
        }
      }
      case ForkedVersionedStatementParse(id, forkId) => {
        for {
          vObject <- Evaluator.lookupVersionedObject(forkId.s, env)
        } yield {
          val command = ForkEnvironmentCommand(id.s, vObject)
          Response(Vector(command), command.toString)
        }
      }
      case ApplyCommandStatementParse(id, command) => {
        for {
          versionedObjectLink <- Evaluator.lookupVersionedObject(id.s, env)
          nType = RetrieveType.fromNewMapObject(versionedObjectLink, env)

          // TODO - this roundabout way of doing things suggests a refactor
          currentState = Evaluator.stripVersioning(versionedObjectLink, env)

          inputT <- if (nType == TypeT) {
            for {
              // TODO - this roundabout way of doing things suggests a refactor
              currentUntagged <- Evaluator.removeTypeTag(currentState)
              currentAsType <- Evaluator.asType(currentUntagged, env)
              customT = CustomT(versionedObjectLink.key.uuid, currentAsType)
              result <- CommandMaps.getTypeExpansionCommandInput(customT)
            } yield result
          } else {
            currentState match {
              case TaggedObject(upct@UParametrizedCaseT(_, _), _) => CommandMaps.expandParametrizedCaseTInput(upct, env)
              case _ => CommandMaps.getCommandInputOfCommandType(nType, env)
            }
          }

          newEnv = currentState match {
            case TaggedObject(UParametrizedCaseT(parameters, _), _) => {
              // Eventually - maybe some of these are type classes, or possible expressions? hmm
              env.newParams(parameters)
            }
            case _ => env
          }

          commandExp <- typeCheck(command, ObjectPattern(UType(inputT)), newEnv, FullFunction)

          commandObj <- Evaluator(commandExp.nExpression, newEnv)
        } yield {
          val command = ApplyIndividualCommand(id.s, commandObj)
          Response(Vector(command), command.toString)
        }
      }
      case ApplyCommandsStatementParse(id, commands) => {
        throw new Exception("Apply multiple commands not yet implemented")
      }
      case InferredTypeStatementParse(_, id, objExpression) => {
        for {
          // TODO - we need a type inference here!!
          tc <- TypeChecker.typeCheckUnknownType(objExpression, env)
          evaluatedObject <- Evaluator(tc.nExpression, env)
          nObject <- TypeChecker.tagAndNormalizeObject(evaluatedObject, tc.refinedTypeClass, env)
        } yield {
          val command = FullEnvironmentCommand(id.s, nObject)
          Response(Vector(command), command.toString)
        }
      }
      case ExpressionOnlyStatementParse(exp) => {
        for {
          // TODO - we need a type inference here!!
          tc <- TypeChecker.typeCheckUnknownType(exp, env)
          evaluatedObject <- Evaluator(tc.nExpression, env)
          constantObject = Evaluator.stripVersioningU(evaluatedObject, env)
          nObject <- TypeChecker.tagAndNormalizeObject(constantObject, tc.refinedTypeClass, env)
        } yield {
          val command = ExpOnlyEnvironmentCommand(nObject)
          Response(Vector(command), command.toString)
        }
      }
    }
  }

  def convertMapValuesToParamList(
    mapValues: Vector[(NewMapPattern, NewMapExpression)],
    env: Environment
  ): Outcome[Vector[(String, NewMapType)], String] = {
    mapValues match {
      case (pattern, expression) +: restOfMapValues => {
        for {
          k <- pattern match {
            case ObjectPattern(UIdentifier(s)) => Success(s)
            case _ => Failure(s"Pattern $pattern should have been an identifier")
          }

          uObject <- Evaluator(expression, env)
          v <- Evaluator.asType(uObject, env)

          restOfResult <- convertMapValuesToParamList(restOfMapValues, env)
        } yield (k -> v) +: restOfResult
      }
      case _ => Success(Vector.empty)
    }
  }
}