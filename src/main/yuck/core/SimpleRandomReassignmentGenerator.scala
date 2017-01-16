package yuck.core

import scala.collection._

/**
 * Generates random moves involving one variable.
 *
 * @author Michael Marte
 */
final class SimpleRandomReassignmentGenerator
    (space: Space,
     xs: immutable.IndexedSeq[AnyVariable],
     randomGenerator: RandomGenerator)
    extends Neighbourhood
{
    require(! xs.isEmpty)
    require(xs.size == xs.toSet.size)
    require(xs.forall(_.domain.isFinite))
    require(xs.forall(! _.isParameter))
    override def searchVariables = xs
    override def nextMove =
        xs
        .apply(randomGenerator.nextInt(xs.length))
        .nextMove(space, randomGenerator)
}
