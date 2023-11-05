package ai.newmap.interpreter

import ai.newmap.model._
import ai.newmap.util.{Outcome, Success, Failure}
import java.util.UUID

// Evaluates an expression that's already been type checked
object UpdateCommandCalculator {
  val pairT: NewMapType = IndexT(UIndex(2))
  val defaultUMap: UntaggedObject = UMap(Vector.empty)

  def getDefaultValueOfCommandType(nType: NewMapType, env: Environment): Outcome[UntaggedObject, String] = {
    getDefaultValueOfCommandTypeFromEnv(nType.asUntagged, env).rescue(_ => {
      getDefaultValueOfCommandTypeHardcoded(nType, env)
    })
  }

  /*
   * This is getDefaultValueOfCommandType being slowly written into newmap code
   * It is a map that takes certain types (called command types) and outputs their default, or initial value
   * The initial value of Count is 0, for example
   */
  def getDefaultValueOfCommandTypeFromEnv(uType: UntaggedObject, env: Environment): Outcome[UntaggedObject, String] = {
    for {
      fieldsToMap <- Evaluator.applyFunction(env.typeToFieldMapping, uType, env, TypeMatcher)
      result <- Evaluator.applyFunction(fieldsToMap, UIdentifier("init"), env)
      func <- Evaluator.applyFunction(result, UIndex(0), env)

      value <- func match {
        case UCase(typeOfFunc, valueOfFunc) => Success(valueOfFunc)
        case _ => Failure("unexpected object: " + func)
      }
    } yield value
  }

  def getDefaultValueOfCommandTypeHardcoded(nType: NewMapType, env: Environment): Outcome[UntaggedObject, String] = {
    nType match {
      // TODO - start removing these in favor of newmap code!
      case IndexT(UIndex(i)) if i > 0 => Success(UIndex(0)) //REmove?
      case MapT(typeTransform, MapConfig(completeness, _, _, _, _)) => completeness match {
        case RequireCompleteness if (!typeTransformHasEmptyKey(typeTransform)) => {
          Failure(s"Can't start off map with key in typeTransform $typeTransform")
        }
        case _ => Success(defaultUMap)
      }
      case StructT(_, _, CommandOutput, _) => {
        Success(defaultUMap)
      }
      case StructT(params, _, _, _) if (params.getMapBindings().toOption.exists(_.isEmpty)) => {
        Success(defaultUMap)
      }
      case FunctionalSystemT(functionTypes) if functionTypes.length == 0 => {
        Success(defaultUMap)
      }
      /*case TypeClassT(typeTransform, implementation) if (implementation.isEmpty) => {
        Success(defaultUMap)
      }*/
      case CharacterT => Success(UCharacter('\u0000'))
      case ArrayT(_) => Success(UCase(UIndex(0), UArray()))
      case CustomT(name, params, typeSystemId) => {
        for {
          underlying <- TypeChecker.getUnderlyingType(name, params, env, typeSystemId)
          result <- getDefaultValueOfCommandTypeHardcoded(underlying, env)
        } yield result
      }
      case SequenceT(parent, featureSet) => Success(UCase(UIndex(0), defaultUMap))
      case _ => {
        Failure(s"Type ${nType.displayString(env)} has no default value")
      }
    }
  }

  def typeTransformHasEmptyKey(typeTransform: TypeTransform): Boolean = {
    typeTransform.keyType match {
      case IndexT(UIndex(0)) | IndexT(UInit) => true
      case SubtypeT(m, _, _) => m.getMapBindings().toOption.exists(_.isEmpty)
      case _ => false // TODO - unimplemented
    }
  }

  def getCommandInputOfCommandType(
    nType: NewMapType,
    env: Environment
  ): Outcome[NewMapType, String] = {
    nType match {
      case CountT => Success(NewMapO.emptyStruct)
      case BooleanT => Success(pairT)
      case MapT(typeTransform, MapConfig(PartialMap, _, _, _, _)) => {
        // In this case, there must be a key expansion type
        // TODO: enforce this?

        // Key Expansion + requiredValue expansion
        // What if Key expansion is a case? (for now we don't allow this, only basic map)

        val keyT = typeTransform.keyType
        val requiredValuesT = typeTransform.valueType

        keyT match {
          case StructT(UMap(Vector()), _, _, _) => {
            // TODO - this is an ugly exception.. we need a better way to add fields to a struct
            // (particularly an empty struct like in this case)
            Success(requiredValuesT)
          }
          case _ => {
            Success(StructT(
              UArray(keyT.asUntagged, requiredValuesT.asUntagged),
              pairT
            ))
          }
        }
      }
      case MapT(typeTransform, _) => {
        // Now instead of giving the structT, we must give something else!!
        // we have typeTransform
        // we need a pair (a, b) that satisfies the type transform
        // This is a (pattern/expression) pairing
        // the type of the expression depends on the value of the pattern??
        // -- therefore, this is a binding!
        // -- should we make a binding a first class object, and should typeTransform just be a binding?
        for {
          outputCommandT <- getCommandInputOfCommandType(typeTransform.valueType, env)
        } yield {
          StructT(
            UArray(typeTransform.keyType.asUntagged, outputCommandT.asUntagged),
            pairT
          )
        }
      }
      case FunctionalSystemT(_) => {
        Success(StructT(
          UArray(IdentifierT.asUntagged, NewMapO.taggedObjectT.asUntagged),
          pairT
        ))
      }
      case StructT(_, parentFieldType, RequireCompleteness, _) => {
        // We may have the option to wanting to expand this struct!
        // TODO: This is one of the cases where the type CHANGES when you update the object
        // - should this be allowed? This may be a problem.

        // Expand the number of fields in this struct like so!
        val fieldExpansionCommandT = parentFieldType
        // We are freely adding to an object and changing the type of it's fields
        // This means that we need to give
        // A) A field expansion command
        // B) The tagged object that goes in there (so both type and object)
        Success(StructT(
          UArray(fieldExpansionCommandT.asUntagged, NewMapO.taggedObjectT.asUntagged),
          pairT
        ))
      }
      case StructT(parameterList, parentFieldType, CommandOutput, featureSet) => {
        // Change to CaseT because we are adding a single parameter!
        // Are we allowed to change an old parameter? Let's say sure.
        Success(CaseT(parameterList, parentFieldType, featureSet))
      }
      case ArrayT(nType) => Success(nType)
      case CustomT(typeName, params, typeSystemId) => {
        for {
          underlyingTypeInfo <- env.typeSystem.historicalUnderlyingType(typeName, typeSystemId)

          (underlyingPattern, underlyingExp) = underlyingTypeInfo

          patternMatchSubstitutions <- Evaluator.patternMatch(underlyingPattern, params, StandardMatcher, env)

          underlyingType = MakeSubstitution(underlyingExp.asUntagged, patternMatchSubstitutions)

          underlyingT <- underlyingType.asType
          result <- getCommandInputOfCommandType(underlyingT, env)
        } yield {
          result
        }
      }
      case SequenceT(parent, featureSet) => Success(parent)
      /*case CaseT(cases, _, _) => {
        Success(UndefinedT)
      }*/
      case _ => {
        Success(UndefinedT)
      }
    }
  }

  // Eventually, this will return an UntaggedObject, because we're not going to change the type
  def updateVersionedObject(
    current: NewMapObject,
    command: UntaggedObject,
    env: Environment
  ): Outcome[NewMapObject, String] = {
    current.nType match {
      case nType@CountT => {
        for {
          c <- current.uObject match {
            case UIndex(i) => Success(i)
            case UInit => Success(0L)
            case _ => Failure(s"Couldn't interpret count value: ${current.uObject}")
          }

          newState = UCase(UIdentifier("Inc"), UIndex(c))
          result <- TypeChecker.tagAndNormalizeObject(newState, nType, env)
        } yield result
      }
      case BooleanT => {
        for {
          currentValue <- TypeChecker.normalizeCount(current.uObject)
          j <- TypeChecker.normalizeCount(command)
        } yield {
          val result = if (currentValue == 1 || j == 1) 1 else 0
          NewMapObject(UIndex(result), BooleanT)
        }
      }
      case SequenceT(parentT, featureSet) => {
        for {
          result <- current.uObject match {
            case UCase(UIndex(i), uSeq) => {
              for {
                oldBindings <- uSeq.getMapBindings
              } yield {
                val newBindings = oldBindings :+ (UIndex(i) -> command)
                UCase(UIndex(i + 1), UMap(newBindings))
              }
            }
            case _ => Failure("Problem with sequence command: " + command)
          }
        } yield {
          NewMapObject(result, current.nType)
        }
      }
      case MapT(typeTransform, MapConfig(PartialMap, features, _, _, _)) => {
        val keyT = typeTransform.keyType
        val requiredValuesT = typeTransform.valueType

        for {
          result <- keyT match {
            case StructT(UMap(Vector()), _, _, _) => {
              // TODO - this is an ugly exception.. we need a better way to add fields to a struct
              // (particularly an empty struct like in this case)
              Success((NewMapObject(UMap(Vector.empty), keyT), command))
            }
            case _ => {

              for {
                commandPatterns <- command.getMapBindings()
                keyField <- Evaluator.applyFunction(command, UIndex(0), env)
                valueExpression <- Evaluator.patternMatchInOrder(commandPatterns, UIndex(1), env)
                valueExpressionEval<- Evaluator(valueExpression, env)
              } yield {
                (NewMapObject(keyField, keyT), valueExpressionEval)
              }
            }
          }

          (keyExpansionCommand, valueExpansionExpression) = result
          
          // Really we're updating the key?? [review soon]
          //updateKeyTypeResponse <- TypeExpander.expandType(keyT, keyExpansionCommand.uObject, env)

          // TODO- we need to do something with updateKeyTypeResponse.converter
                  
          mapValues <- current.uObject.getMapBindings()

          prepNewValues = for {
            value <- mapValues

            // Remove old value
            if (value._1 != keyExpansionCommand.uObject)
          } yield (value._1 -> value._2)

          newMapValues = (keyExpansionCommand.uObject -> valueExpansionExpression) +: prepNewValues
        } yield NewMapObject(UMap(newMapValues), current.nType)
      }
      case mapT@MapT(typeTransform, _) => {
        val outputType = typeTransform.valueType

        for {
          input <- Evaluator.applyFunction(command, UIndex(0), env)
          commandForInput <- Evaluator.applyFunction(command, UIndex(1), env)

          currentResultForInput <- Evaluator.applyFunction(current.uObject, input, env)

          newResultForInput <- updateVersionedObject(
            NewMapObject(currentResultForInput, outputType),
            commandForInput,
            env
          )

          mapValues <- current.uObject.getMapBindings()

          newMapValues = (input -> newResultForInput.uObject) +: mapValues.filter(x => x._1 != input)
        } yield {
          NewMapObject(UMap(newMapValues), mapT)
        }
      }
      case FunctionalSystemT(functionTypes) => {
        for {
          currentMapping <- current.uObject.getMapBindings()

          newFunctionNameObj <- Evaluator.applyFunction(command, UIndex(0), env)

          newFunctionObject <- Evaluator.applyFunction(command, UIndex(1), env)

          newFunctionObjectComponents <- newFunctionObject match {
            case UCase(t, UMap(pairs)) => Success(t -> pairs)
            case _ => Failure(s"Recieved Unexpected Object: $newFunctionObject") 
          }

          uNewFunctionType = newFunctionObjectComponents._1
          uNewFunctionMapping = newFunctionObjectComponents._2
        } yield {
          // Also upgrade the function itself
          // TODO: I think the composition between uNewFunctionMaping and currentMapping needs to be handled better
          NewMapObject(
            UMap((newFunctionNameObj -> UMap(uNewFunctionMapping)) +: currentMapping),
            FunctionalSystemT((newFunctionNameObj -> uNewFunctionType) +: functionTypes)
          )
        }
      }
      case StructT(parameterList, parentFieldType, RequireCompleteness, featureSet) => {
        for {
          mapValues <- current.uObject.getMapBindings()

          nameOfField <- Evaluator.applyFunction(command, UIndex(0), env)
          newValueAsNewMapObject <- Evaluator.applyFunction(command, UIndex(1), env)

          uCaseValue <- newValueAsNewMapObject match {
            case u@UCase(_, _) => Success(u)
            case _ => Failure(s"Wrong update for complete struct: $newValueAsNewMapObject")
          }

          parameterListValues <- parameterList.getMapBindings()

        } yield {
          val typeOfField = uCaseValue.constructor
          val valueOfField = uCaseValue.input

          val newMapValues = (nameOfField -> valueOfField) +: mapValues.filter(x => x._1 != nameOfField)

          val newParams = (nameOfField -> typeOfField) +: parameterListValues.filter(x => x._1 != nameOfField)

          NewMapObject(UMap(newMapValues), StructT(UMap(newParams), parentFieldType, RequireCompleteness, featureSet))
        }
      }
      case StructT(params, parentFieldType, CommandOutput, _) => {
        command match {
          case UCase(constructor, input) => {
            for {
              mapValues <- current.uObject.getMapBindings()

              // TODO - this part is currently only written for Initializable
              // Params need to be updated!!
              newParams = params
            } yield {
              val newMapValues = (constructor -> input) +: mapValues.filter(x => x._1 != constructor)
              NewMapObject(UMap(newMapValues), StructT(newParams, parentFieldType))
            }
          }
          case _ => {
            Failure(s"A) Structs as commands haven't been implemented yet -- $params -- $command")
          }
        }
      }
      case nType@ArrayT(_) => {
        for {
          untaggedResult <- current.uObject match {
            case UCase(UIndex(length), UArray(values)) => {
              Success(UCase(UIndex(length + 1), UArray(values :+ command)))
            }
            case UCase(UIndex(length), UMap(values)) => {
              Success(UCase(UIndex(length + 1), UMap(values :+ (UIndex(length), command))))
            }
            case _ => Failure(s"Unknown array data: $current")
          }

          result <- TypeChecker.tagAndNormalizeObject(untaggedResult, nType, env)
        } yield result
      }
      case nType@CustomT(typeName, params, typeSystemId) => {
        val customResultOutcome = {
          command match {
            case UCase(name, value) => {
              for {
                // TODO - take into account the typeSystemId
                // TODO - what if this intercepts a field that's not a command?
                func <- Evaluator(AccessField(current.uObject, current.nType.asUntagged, name), env)
                afterCommand <- Evaluator.applyFunction(func, value, env, StandardMatcher)
                resultResolved <- TypeChecker.tagAndNormalizeObject(afterCommand, nType, env)
              } yield resultResolved
            }
            case _ => Failure("command $command in the wrong format for custom result")
          }
        }

        customResultOutcome.rescue(f => {
          for {
            underlyingTypeInfo <- env.typeSystem.historicalUnderlyingType(typeName, typeSystemId)

            (underlyingPattern, underlyingExp) = underlyingTypeInfo

            patternMatchSubstitutions <- Evaluator.patternMatch(underlyingPattern, params, StandardMatcher, env)

            underlyingType = MakeSubstitution(underlyingExp.asUntagged, patternMatchSubstitutions)

            underlyingT <- underlyingType.asType
            currentResolved <- TypeChecker.tagAndNormalizeObject(current.uObject, underlyingT, env)

            result <- updateVersionedObject(currentResolved, command, env)
            resultResolved <- TypeChecker.tagAndNormalizeObject(result.uObject, nType, env)
          } yield resultResolved
        })
      }
      case _ => {
        Failure(s"C) ${current.displayString(env)} is not a command type, error in type checker -- ${current.nType}")
      }
    }
  }
}