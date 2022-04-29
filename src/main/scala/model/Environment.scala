package ai.newmap.model

import scala.collection.mutable.StringBuilder
import scala.collection.immutable.ListMap
import ai.newmap.interpreter._
import ai.newmap.util.{Outcome, Success, Failure}
import java.util.UUID

sealed abstract class EnvironmentCommand

case class FullEnvironmentCommand(
  id: String,
  nObject: NewMapObject
) extends EnvironmentCommand {
  override def toString: String = {
    s"val $id = ${nObject}"
  }
}

case class NewVersionedStatementCommand(
  id: String,
  nType: NewMapObject
) extends EnvironmentCommand {
  override def toString: String = {
    s"ver $id = new ${nType}"
  }
}

case class ApplyIndividualCommand(
  id: String,
  nObject: NewMapObject
) extends EnvironmentCommand {
  override def toString: String = {
    "" //s"update $id $nObject"
  }
}

case class ForkEnvironmentCommand(
  id: String,
  vObject: VersionedObjectLink
) extends EnvironmentCommand {
  override def toString: String = {
    s"ver $id = fork ${vObject}"
  }
}

case class ParameterEnvironmentCommand(
  id: String,
  nType: NewMapObject
) extends EnvironmentCommand {
  override def toString: String = {
    s"parameter $id: ${nType}"
  }
}

case class ExpOnlyEnvironmentCommand(
  nObject: NewMapObject
) extends EnvironmentCommand {
  override def toString: String = nObject.toString
}

sealed abstract class EnvironmentValueStatus

// This means that the identifier is bound to a specific object
case object BoundStatus extends EnvironmentValueStatus

// This means that the identifier is a parameter, with a type given
case object ParameterStatus extends EnvironmentValueStatus

case class EnvironmentValue(
  nObject: NewMapObject,
  status: EnvironmentValueStatus
)

// Additional things to keep track of: latest versions of all versioned objects??
case class Environment(
  commands: Vector[EnvironmentCommand] = Vector.empty,
  idToObject: ListMap[String, EnvironmentValue] = ListMap.empty,

  latestVersionNumber: Map[UUID, Long] = ListMap.empty,
  storedVersionedTypes: Map[VersionedObjectKey, NewMapObject] = ListMap.empty

  // TODO - add use-defined types as a special case of latest version number?
) {
  def lookup(identifier: String): Option[EnvironmentValue] = {
    idToObject.get(identifier)
  }

  override def toString: String = {
    val builder: StringBuilder = new StringBuilder()
    for ((id, envValue) <- idToObject) {
      val command = envValue.status match {
        case BoundStatus => FullEnvironmentCommand(id, envValue.nObject)
        case ParameterStatus => ParameterEnvironmentCommand(id, envValue.nObject)
      }
      builder.append(command.toString)
      builder.append("\n")
    }
    builder.toString
  }

  def print(): Unit = {
    println(this.toString())
  }

  def newCommand(command: EnvironmentCommand): Environment = {
    val newCommands = commands :+ command

    command match {
      case FullEnvironmentCommand(s, nObject) => {
        this.copy(
          commands = newCommands,
          idToObject = idToObject + (s -> EnvironmentValue(nObject, BoundStatus))
        )
      }
      case NewVersionedStatementCommand(s, nType) => {
        val uuid = java.util.UUID.randomUUID

        val defaultOutcome = CommandMaps.getDefaultValueOfCommandType(nType, this)

        defaultOutcome match {
          case Success(_) => ()
          case Failure(f) => throw new Exception(f)
        }

        val initValue = defaultOutcome.toOption.get
        val key = VersionedObjectKey(0L, uuid)
        val versionedObject = VersionedObjectLink(key, KeepUpToDate)
        val envValue = EnvironmentValue(versionedObject, BoundStatus)

        this.copy(
          commands = newCommands,
          idToObject = idToObject + (s -> envValue),
          latestVersionNumber = latestVersionNumber + (uuid -> 0L),
          storedVersionedTypes = storedVersionedTypes + (VersionedObjectKey(0L, uuid) -> initValue)
        )
      }
      case ApplyIndividualCommand(s, nObject) => {
        val updated = CommandMaps.updateVersionedObject(s, nObject, this)

        val result = updated match {
          case Success(s) => s
          case Failure(f) => throw new Exception(f)
        }

        val newKey = VersionedObjectKey(result.newVersion, result.uuid)

        // TODO - during this, versions that are no longer in use can be destroyed
        val newStoredVTypes = storedVersionedTypes + (newKey -> result.newValue)

        this.copy(
          commands = newCommands,
          idToObject = idToObject + (s -> EnvironmentValue(VersionedObjectLink(newKey, KeepUpToDate), BoundStatus)),
          latestVersionNumber = latestVersionNumber + (result.uuid -> result.newVersion),
          storedVersionedTypes = newStoredVTypes
        )
      }
      case ForkEnvironmentCommand(s, vObject) => {
        val uuid = java.util.UUID.randomUUID
        val version = Evaluator.latestVersion(vObject.key.uuid, this).toOption.get
        val current = Evaluator.currentState(vObject.key.uuid, this).toOption.get
        val key = VersionedObjectKey(version, uuid)
        val versionedObject = VersionedObjectLink(key, KeepUpToDate)
        val envValue = EnvironmentValue(versionedObject, BoundStatus)

        this.copy(
          commands = newCommands,
          idToObject = idToObject + (s -> envValue),
          latestVersionNumber = latestVersionNumber + (uuid -> version),
          storedVersionedTypes = storedVersionedTypes + (key -> current)
        )
      }
      case ParameterEnvironmentCommand(s, nType) => {
        this.copy(
          commands = newCommands,
          idToObject = idToObject + (s -> EnvironmentValue(nType, ParameterStatus))
        )
      }
      case ExpOnlyEnvironmentCommand(nObject) => {
        // TODO: save this in the result list
        this
      }
    }
  }

  def newCommands(newCommands: Vector[EnvironmentCommand]): Environment = {
    var env = this
    for (com <- newCommands) {
      env = env.newCommand(com)
    }
    env
  }

  // TODO - should we ensure that nType is actually a type?
  def newParam(id: String, nType: NewMapObject): Environment = {
    newCommand(ParameterEnvironmentCommand(id, nType))
  }

  def newParams(xs: Vector[(String, NewMapObject)]) = {
    newCommands(xs.map(x => ParameterEnvironmentCommand(x._1, x._2)))
  }
}

object Environment {
  def eCommand(id: String, nObject: NewMapObject): EnvironmentCommand = {
    FullEnvironmentCommand(id, nObject)
  }

  def simpleFuncT(inputType: NewMapObject, outputType: NewMapObject): NewMapObject = {
    MapT(inputType, outputType, MapConfig(RequireCompleteness, BasicMap))
  }

  def fullFuncT(inputType: NewMapObject, outputType: NewMapObject): NewMapObject = {
    MapT(inputType, outputType, MapConfig(RequireCompleteness, FullFunction))
  }

  def structTypeFromParams(params: Vector[(String, NewMapObject)]) = {
    val paramsToObject = {
      params.map(x => ObjectPattern(UIdentifier(x._1)) -> ObjectExpression(x._2))
    }

    val paramsList = {
      params.map(x => ObjectPattern(UIdentifier(x._1)) -> ObjectExpression(TaggedObject(UIndex(1), OrBooleanT)))
    }

    val keyType = SubtypeT(TaggedObject(
      UMap(paramsList),
      MapT(IdentifierT, OrBooleanT, MapConfig(CommandOutput, BasicMap))
    ))

    StructT(
      TaggedObject(
        UMap(paramsToObject),
        MapT(keyType, TypeT, MapConfig(RequireCompleteness, BasicMap))
      )
    )
  }

  def caseTypeFromParams(params: Vector[(String, NewMapObject)]) = {
    val paramsToObject = {
      params.map(x => ObjectPattern(UIdentifier(x._1)) -> ObjectExpression(x._2))
    }

    val paramsList = {
      params.map(x => ObjectPattern(UIdentifier(x._1)) -> ObjectExpression(TaggedObject(UIndex(1), OrBooleanT)))
    }

    val keyType = SubtypeT(TaggedObject(
      UMap(paramsList),
      MapT(IdentifierT, OrBooleanT, MapConfig(CommandOutput, BasicMap))
    ))

    CaseT(
      TaggedObject(
        UMap(paramsToObject),
        MapT(keyType, TypeT, MapConfig(RequireCompleteness, BasicMap))
      )
    )
  }

  // For Debugging
  def printEnvWithoutBase(env: Environment): Unit = {
    for ((id, envValue) <- env.idToObject) {
      if (!Base.idToObject.contains(id)) {
        val command = envValue.status match {
          case BoundStatus => FullEnvironmentCommand(id, envValue.nObject)
          case ParameterStatus => ParameterEnvironmentCommand(id, envValue.nObject)
        }
        println(command.toString)
      }
    }
  }

  // Somewhat complex for now, but this is how a pattern/function definition is built up!
  // In code, this should be done somewhat automatically
  def buildDefinitionWithParameters(
    inputs: Vector[(String, NewMapObject)], // A map from parameters and their type
    expression: NewMapExpression
  ): NewMapObject = {
    val structT = StructT(
      TaggedObject(
        UMap(inputs.zipWithIndex.map(x => ObjectPattern(UIndex(x._2)) -> ObjectExpression(x._1._2))),
        MapT(TaggedObject(UIndex(inputs.length), CountT), TypeT, MapConfig(RequireCompleteness, SimpleFunction))
      )
    )

    val structP = StructPattern(inputs.map(x => WildcardPattern(x._1)))

    TaggedObject(
      UMap(Vector(structP -> expression)),
      MapT(structT, TypeT, MapConfig(RequireCompleteness, SimpleFunction))
    )
  }

  var Base: Environment = Environment().newCommands(Vector(
    eCommand("Any", AnyT),
    eCommand("Type", TypeT),
    eCommand("DataType", DataTypeT(Vector.empty)),
    eCommand("Count", CountT),
    eCommand("Identifier", IdentifierT),
    eCommand("Increment", TaggedObject(IncrementFunc, MapT(CountT, CountT, MapConfig(RequireCompleteness, SimpleFunction)))),
    eCommand("IsCommand", TaggedObject(
      IsCommandFunc,
      MapT(TypeT, TaggedObject(UIndex(2), CountT), MapConfig(CommandOutput, SimpleFunction))
    )),
    eCommand("OrBoolean", OrBooleanT),
    eCommand("Sequence", TaggedObject(
      UMap(Vector(WildcardPattern("key") -> BuildTableT(ObjectExpression(CountT), ParamId("key")))),
      MapT(TypeT, TypeT, MapConfig(RequireCompleteness, SimpleFunction))
    )),
    eCommand("Map", buildDefinitionWithParameters(
      Vector("key" -> TypeT, "value" -> NewMapO.commandT),
      BuildMapT(ParamId("key"), ParamId("value"), MapConfig(CommandOutput, BasicMap))
    )),
    eCommand("ReqMap", buildDefinitionWithParameters(
      Vector("key" -> TypeT, "value" -> TypeT),
      BuildMapT(ParamId("key"), ParamId("value"), MapConfig(RequireCompleteness, SimpleFunction))
    )),
    eCommand("Table", buildDefinitionWithParameters(
      Vector("key" -> TypeT, "value" -> TypeT),
      BuildTableT(ParamId("key"), ParamId("value"))
    )),
    eCommand("ExpandingSubset", TaggedObject(
      UMap(Vector(WildcardPattern("parentType") -> BuildExpandingSubsetT(ParamId("parentType"), false))),
      MapT(TypeT, TypeT, MapConfig(RequireCompleteness, SimpleFunction))
    )),
    NewVersionedStatementCommand("TypeWithDefault", ExpandingSubsetT(TypeT, true)),
    eCommand("Subtype", TaggedObject(
      UMap(Vector(
        WildcardPattern("simpleFunction") -> BuildSubtypeT(ParamId("simpleFunction"))
      )),
      MapT(
        NewMapO.simpleFunctionT,
        TypeT,
        MapConfig(RequireCompleteness, SimpleFunction)
      )
    )),
  ))

  // What do I want???
  //GetDefault: StructT(TaggedObject(UMap(Vector.empty), MapT(TypeWithDefault, Type, MapConfig(RequireCompleteness, SimpleFunction)))) 
  val TypeWithDefault = Base.lookup("TypeWithDefault").get.nObject

  Base = Base.newCommands(Vector(
    eCommand("TypeClassStruct", TaggedObject(
      UMap(Vector(
        WildcardPattern("structParams") -> BuildStructT(ParamId("structParams"))
      )),
      MapT(
        // Problem: TypeWithDefault should not be in here!!
        MapT(TypeWithDefault, TypeT, MapConfig(RequireCompleteness, SimpleFunction)),
        TypeT,
        MapConfig(RequireCompleteness, BasicMap)
      )
    )),
  ))
}