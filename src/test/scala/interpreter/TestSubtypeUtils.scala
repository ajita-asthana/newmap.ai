package ai.newmap.interpreter

import org.scalatest._
import ai.newmap.model._
import ai.newmap.util.{Outcome, Success, Failure}

class TestSubtypeUtils extends FlatSpec {
  "Subtype " should " be convertible into the parent type" in {
    val isMember = TaggedObject(
      UMap(Vector(
        ObjectPattern(NewMapO.identifier("key")) -> ObjectExpression(IndexValue(1, Index(2))),
        ObjectPattern(NewMapO.identifier("value")) -> ObjectExpression(IndexValue(1, Index(2)))
      )),
      MapT(IdentifierT, Index(2), CommandOutput, BasicMap)
    )

    assert(RetrieveType.retrieveInputTypeFromFunction(ObjectExpression(isMember), Environment.Base) == IdentifierT)

    val result = SubtypeUtils.isTypeConvertible(SubtypeT(isMember), IdentifierT, Environment.Base)
    
    result match {
      case Success(result) => assert(result)
      case Failure(reason) => fail(reason)
    }
  }
}