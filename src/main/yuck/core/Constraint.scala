package yuck.core

import scala.collection._

/**
 * Provides the constraint interface for local search.
 *
 * Regarding the input variables, there are two things to keep in mind
 * when implementing a constraint: They may get pruned between construction
 * and initialization and there may be channel variables among them with
 * infinite domains.
 *
 * @author Michael Marte
 */
abstract class Constraint
    (val id: Id[Constraint], val goal: Goal)
    extends Ordered[Constraint]
{

    @inline final override def hashCode = id.hashCode
    @inline final override def compare(that: Constraint) = this.id.compare(that.id)

    /** Returns the input variables. */
    def inVariables: TraversableOnce[AnyVariable]

    /** Returns the output variables. */
    def outVariables: TraversableOnce[AnyVariable]

    /**
     * Initializes the constraint's internal state according to the given search state
     * and returns values for all output variables by means of effects.
     */
    def initialize(now: SearchState): TraversableOnce[AnyEffect]

    /**
     * Assesses the impact of the given move on the constraint's output variables.
     * @param before is the search state before the move and the one the constraint's internal
     * state is in sync with.
     * @param after is the search state obtained by applying the move to before.
     * @param move is the move to be assessed and involves only input variables of the constraint.
     */
    def consult(before: SearchState, after: SearchState, move: Move): TraversableOnce[AnyEffect]
    /**
     * Performs the given move by adapting the constraint's internal state accordingly.
     *
     * Please see consult for the parameters' meaning and the expected return value.
     *
     * The default implementation forwards to consult.
     *
     * A call to commit will only happen after a call to consult.
     */
    def commit(before: SearchState, after: SearchState, move: Move): TraversableOnce[AnyEffect] =
        consult(before, after, move)

    /**
     * Returns true if this constraint is a candidate for implicit solving
     * with respect to the given space.
     *
     * The default implementation returns false.
     */
    def isCandidateForImplicitSolving(space: Space): Boolean = false

    /**
     * Prepares the grounds for solving this constraint implicitly.
     *
     * 1. Assigns values to the constraint's search variables such that the
     *    assignment satisfies the constraint.
     * 2. Assigns zero to the constraint's cost variable.
     * 3. Creates and returns a move generator that maintains feasibility.
     *
     * The implementation should not assume that this constraint has been posted.
     *
     * The default implementation does nothing and returns None.
     */
    def prepareForImplicitSolving(
        space: Space,
        randomGenerator: RandomGenerator,
        moveSizeDistribution: Distribution,
        hotSpotDistributionFactory: immutable.Seq[AnyVariable] => Option[Distribution],
        probabilityOfFairChoiceInPercent: Int):
        Option[MoveGenerator] =
        None

}
