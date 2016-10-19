package yuck.flatzinc.test

import org.junit._
import org.junit.experimental.categories._
import org.junit.experimental.categories.Categories._
import org.junit.runner.RunWith
import org.junit.runners.Suite.SuiteClasses

import yuck.flatzinc.test.util._

/**
 * The unification of the MiniZincChallenge* suites
 *
 * @author Michael Marte
 */
@Test
@RunWith(classOf[runners.Suite])
@SuiteClasses(
    Array(
        classOf[MiniZincChallenge2012],
        classOf[MiniZincChallenge2013],
        classOf[MiniZincChallenge2014],
        classOf[MiniZincChallenge2015]))
class MiniZincChallenges

/**
 * All satisfiability problems from MiniZincChallenges
 *
 * @author Michael Marte
 */
@Test
@RunWith(classOf[Categories])
@IncludeCategory(Array(classOf[SatisfiabilityProblem]))
@SuiteClasses(Array(classOf[MiniZincChallenges]))
class SatisfiabilityChallenges

/**
 * All minimization problems from MiniZincChallenges
 *
 * @author Michael Marte
 */
@Test
@RunWith(classOf[Categories])
@IncludeCategory(Array(classOf[MinimizationProblem]))
@SuiteClasses(Array(classOf[MiniZincChallenges]))
class MinimizationChallenges

/**
 * All maximization problems from MiniZincChallenges
 *
 * @author Michael Marte
 */
@Test
@RunWith(classOf[Categories])
@IncludeCategory(Array(classOf[MaximizationProblem]))
@SuiteClasses(Array(classOf[MiniZincChallenges]))
class MaximizationChallenges
