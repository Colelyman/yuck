package yuck.flatzinc.compiler

import scala.collection._

import yuck.constraints._
import yuck.core._
import yuck.flatzinc.ast._
import yuck.util.arm.{Sigint, scoped}
import yuck.util.logging.LogScope

/**
 * Compiles FlatZinc constraints to Yuck constraints.
 *
 * Skips FlatZinc constraints that were marked as redundant in an earlier phase.
 *
 * The implementation is based on pattern matching.
 *
 * In case a FlatZinc constraint gets rewritten to another FlatZinc constraint,
 * this step will be visible from the log.
 *
 * Many reified FlatZinc constraints receive special treatment but there is a
 * final case (matching all constraint names ending with "_reif") that implements
 * reification in a generic way such that ALL FlatZinc constraints (including global
 * ones) can be reified.
 *
 * Potential functional dependencies (declared by defines_var annotations) are
 * exploited as far as possible; only those annotations are ignored the processing
 * of which would entail a cyclic constraint graph.
 *
 * @author Michael Marte
 */
final class ConstraintFactory
    (cc: CompilationContext, randomGenerator: RandomGenerator, sigint: Sigint)
    extends CompilationPhase(cc, randomGenerator)
{

    private val space = cc.space
    private val impliedConstraints = cc.impliedConstraints
    private val logger = cc.logger

    // Checks whether the given constraint is annotated with defined_var(a)
    // where a compiles to out.
    private def definesVar(
        constraint: yuck.flatzinc.ast.Constraint, out: AnyVariable): Boolean =
    {
        constraint
        .annotations
        .toIterator
        .map(_.term)
        .map(_ match {case Term("defines_var", List(a)) => Some(compileAnyExpr(a)); case _ => None})
        .contains(Some(out))
    }

    private val fakeConstraintId = new Id[yuck.core.Constraint](-1)

    // Checks whether there is a functional dependency that could be exploited without
    // introducing a cycle.
    // Notice that this method may be quite expensive!
    private def definesVar(
        constraint: yuck.flatzinc.ast.Constraint, in: Seq[AnyVariable], out: AnyVariable): Boolean =
    {
        ! out.domain.isSingleton &&
        ! cc.searchVars.contains(out) &&
        (definesVar(constraint, out) || ! cc.definedVars.contains(out)) &&
        space.definingConstraint(out).isEmpty &&
        ! space.wouldIntroduceCycle(new DummyConstraint(fakeConstraintId, in, List(out)))
    }

    private def definesVar(
        constraint: yuck.flatzinc.ast.Constraint, out: Expr): Boolean =
        definesVar(constraint, compileAnyExpr(out))

    private def definesVar(
        constraint: yuck.flatzinc.ast.Constraint, in: Seq[Expr], out: Expr): Boolean =
        definesVar(constraint, in.map(compileAnyExpr), compileAnyExpr(out))

    private def definesVar(
        constraint: yuck.flatzinc.ast.Constraint, in: Expr, out: Expr): Boolean =
        definesVar(constraint, compileAnyArray(in), compileAnyExpr(out))

    private def compileConstraint
        (constraint: yuck.flatzinc.ast.Constraint,
         in: Seq[AnyVariable],
         out: AnyVariable,
         withFunctionalDependency: => Iterable[Variable[BooleanValue]],
         withoutFunctionalDependency: => Iterable[Variable[BooleanValue]]):
        Iterable[Variable[BooleanValue]] =
    {
        if (! out.domain.isSingleton &&
            ! cc.searchVars.contains(out) &&
            (definesVar(constraint, out) || ! cc.definedVars.contains(out)) &&
            space.definingConstraint(out).isEmpty)
        {
            try {
                withFunctionalDependency
            }
            catch {
                case _: CyclicConstraintNetworkException => withoutFunctionalDependency
            }
        } else {
            withoutFunctionalDependency
        }
    }

    private def compileConstraint
        (constraint: yuck.flatzinc.ast.Constraint,
         in: Seq[Expr],
         out: Expr,
         withFunctionalDependency: => Iterable[Variable[BooleanValue]],
         withoutFunctionalDependency: => Iterable[Variable[BooleanValue]]):
        Iterable[Variable[BooleanValue]] =
        compileConstraint(constraint, in.map(compileAnyExpr), compileAnyExpr(out), withFunctionalDependency, withoutFunctionalDependency)

    private def compileConstraint
        (constraint: yuck.flatzinc.ast.Constraint,
         in: Expr,
         out: Expr,
         withFunctionalDependency: => Iterable[Variable[BooleanValue]],
         withoutFunctionalDependency: => Iterable[Variable[BooleanValue]]):
        Iterable[Variable[BooleanValue]] =
        compileConstraint(constraint, compileAnyArray(in), compileAnyExpr(out), withFunctionalDependency, withoutFunctionalDependency)

    private type UnaryConstraintFactory
        [InputValue <: AnyValue, OutputValue <: AnyValue] =
        (Id[yuck.core.Constraint], Goal, Variable[InputValue], Variable[OutputValue]) =>
         yuck.core.Constraint

     private type BinaryConstraintFactory
        [InputValue <: AnyValue, OutputValue <: AnyValue] =
        (Id[yuck.core.Constraint], Goal, Variable[InputValue], Variable[InputValue], Variable[OutputValue]) =>
         yuck.core.Constraint

    override def run {
        cc.costVars ++=
            cc.ast.constraints
            .toIterator
            .map(constraint => compileConstraint(new FlatZincGoal(constraint), constraint))
            .flatten
    }

    private val IntLin = "int_lin_(.*)".r
    private val Reif = "(.*)_reif".r

    // In Yuck, True < False, but in FlatZinc, false < true.
    private val booleanOrdering = BooleanValueTraits.valueOrdering.reverse
    private val booleanSequenceOrdering = createLexicographicOrderingForTraversableOnce(booleanOrdering)

    private def compileConstraint
        (goal: Goal, constraint: yuck.flatzinc.ast.Constraint):
        Iterable[Variable[BooleanValue]] =
    {
        if (sigint.isSet) {
            throw new FlatZincCompilerInterruptedException
        }
        if (impliedConstraints.contains(constraint)) {
            logger.logg("Skipping %s".format(constraint))
            Nil
        } else {
            logger.logg("Compiling %s".format(constraint))
            scoped(new LogScope(logger)) {
                // toList enforces constraint generation in this log scope
                compileNonImplicitConstraint(goal, constraint).toList
            }
        }
    }

    private def compileNonImplicitConstraint
        (goal: Goal, constraint: yuck.flatzinc.ast.Constraint):
        Iterable[Variable[BooleanValue]] =
        constraint match
    {
        // TODO Implement other direction!?
        case Constraint("bool2int", List(a, b), _) =>
            def withFunctionalDependency = {
                space.post(new Bool2Int1(nextConstraintId, goal, a, b))
                Nil
            }
            def withoutFunctionalDependency = {
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Bool2Int2(nextConstraintId, goal, a, b, costs))
                List(costs)
            }
            compileConstraint(constraint, List(a), b, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("bool_not", List(a, b), _) =>
            def withFunctionalDependency = {
                space.post(new Not(nextConstraintId, goal, a, b))
                Nil
            }
            def withoutFunctionalDependency = {
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Ne[BooleanValue](nextConstraintId, goal, a, b, costs))
                List(costs)
            }
            compileConstraint(constraint, List(a), b, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("bool_eq", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Eq[BooleanValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("bool_eq_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Eq[BooleanValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("bool_lt", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Lt[BooleanValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("bool_lt_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Lt[BooleanValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("bool_le", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Le[BooleanValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("bool_le_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Le[BooleanValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("bool_xor", _, _) =>
            compileBinaryConstraint[BooleanValue](new Ne[BooleanValue](_, _, _, _, _), goal, constraint)
        case Constraint("array_bool_and", List(as, b), _) =>
            val as1 = ArrayConst(getArrayElems(as).toIterator.filter(a => ! compilesToConst(a, True)).toList)
            val as2 = if (as1.value.isEmpty) ArrayConst(List(BoolConst(true))) else as1
            val xs = compileArray[BooleanValue](as2)
            val y = compileExpr[BooleanValue](b)
            def withFunctionalDependency = {
                val costs = y
                if (xs.size == 2) {
                    space.post(new Plus[BooleanValue](nextConstraintId, goal, xs(0), xs(1), costs))
                } else {
                    space.post(new Sum[BooleanValue](nextConstraintId, goal, xs, costs))
                }
                Nil
            }
            def withoutFunctionalDependency = {
                val costs = if (xs.size == 1) xs(0) else createNonNegativeChannel[BooleanValue]
                if (xs.size == 2) {
                    space.post(new Plus[BooleanValue](nextConstraintId, goal, xs(0), xs(1), costs))
                } else if (xs.size > 2) {
                    space.post(new Sum[BooleanValue](nextConstraintId, goal, xs, costs))
                }
                if (compilesToConst(b, True)) {
                    // exists clause
                    List(costs)
                } else {
                    val result = createNonNegativeChannel[BooleanValue]
                    space.post(new Eq[BooleanValue](nextConstraintId, goal, costs, y, result))
                    List(result)
                }
            }
            compileConstraint(constraint, xs, y, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("array_bool_or", List(as, b), _) =>
            val as1 = ArrayConst(getArrayElems(as).toIterator.filter(a => ! compilesToConst(a, False)).toList)
            val as2 = if (as1.value.isEmpty) ArrayConst(List(BoolConst(false))) else as1
            val xs = compileArray[BooleanValue](as2)
            val y = compileExpr[BooleanValue](b)
            def withFunctionalDependency = {
                space.post(new Disjunction(nextConstraintId, goal, xs, y))
                Nil
            }
            def withoutFunctionalDependency = {
                val costs = if (xs.size == 1) xs(0) else createNonNegativeChannel[BooleanValue]
                if (xs.size > 1) {
                    space.post(new Disjunction(nextConstraintId, goal, xs, costs))
                }
                if (compilesToConst(b, True)) {
                    // exists clause
                    List(costs)
                } else {
                    val result = createNonNegativeChannel[BooleanValue]
                    space.post(new Eq[BooleanValue](nextConstraintId, goal, costs, y, result))
                    List(result)
                }
            }
            compileConstraint(constraint, xs, y, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("array_bool_xor", List(as), _) =>
            val xs = compileArray[BooleanValue](as)
            val maybeY =
                xs
                .sortWith((x, y) => definesVar(constraint, x) && ! definesVar(constraint, y))
                .filter(y => definesVar(constraint, xs.filter(_ != y), y))
                .headOption
            if (maybeY.isDefined) {
                val y = maybeY.get
                val trueCount = createNonNegativeChannel[IntegerValue]
                space.post(new CountConst[BooleanValue](nextConstraintId, goal, xs.filter(_ != y), True, trueCount))
                space.post(new Even(nextConstraintId, goal, trueCount, y))
                Nil
            } else {
                val trueCount = createNonNegativeChannel[IntegerValue]
                space.post(new CountConst[BooleanValue](nextConstraintId, goal, xs, True, trueCount))
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Uneven[IntegerValue](nextConstraintId, goal, trueCount, costs))
                List(costs)
            }
        case Constraint("bool_clause", List(ArrayConst(List(a)), ArrayConst(List(b))), _) =>
            compileConstraint(goal, Constraint("bool_le", List(b, a), Nil))
        case Constraint("bool_clause", List(as, bs), _) =>
            // as are positive literals, bs are negative literals
            (getArrayElems(as).toIterator.filter(a => ! compilesToConst(a, False)).toList,
             getArrayElems(bs).toIterator.filter(b => ! compilesToConst(b, True)).toList) match {
                case (Nil, Nil) => throw new InconsistentConstraintException(constraint)
                case (Nil, _) => compileConstraint(goal, Constraint("array_bool_and", List(bs, BoolConst(false)), Nil))
                case (_, Nil) => compileConstraint(goal, Constraint("array_bool_or", List(as, BoolConst(true)), Nil))
                case _ =>
                    val List(costs0) = compileConstraint(goal, Constraint("array_bool_or", List(as, BoolConst(true)), Nil))
                    val List(costs1) = compileConstraint(goal, Constraint("array_bool_and", List(bs, BoolConst(true)), Nil))
                    val costs = createNonNegativeChannel[BooleanValue]
                    space.post(new Le[BooleanValue](nextConstraintId, goal, costs1, costs0, costs))
                    List(costs)
            }
        case Constraint("int_eq", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Eq[IntegerValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("int_eq_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Eq[IntegerValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_ne", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Ne[IntegerValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("int_ne_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Ne[IntegerValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_lt", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Lt[IntegerValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("int_lt_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Lt[IntegerValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_le", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Le[IntegerValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("int_le_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Le[IntegerValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_min", _, _) =>
            compileBinaryConstraint[IntegerValue](new Min(_, _, _, _, _), goal, constraint)
        case Constraint("int_max", _, _) =>
            compileBinaryConstraint[IntegerValue](new Max(_, _, _, _, _), goal, constraint)
        // TODO Perform this rewriting also for the other arithmetic operations!?
        case Constraint("int_plus", List(a, b, c), annotations) =>
            if (definesVar(constraint, a)) {
                compileConstraint(goal, Constraint("int_minus", List(c, b, a), annotations))
            } else if (definesVar(constraint, b)) {
                compileConstraint(goal, Constraint("int_minus", List(c, a, b), annotations))
            } else {
                compileBinaryConstraint[IntegerValue](new Plus(_, _, _, _, _), goal, constraint)
            }
        case Constraint("int_minus", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerValue](new Minus(_, _, _, _, _), goal, constraint)
        case Constraint("int_times", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerValue](new Times(_, _, _, _, _), goal, constraint)
        case Constraint("int_div", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerValue](new Div(_, _, _, _, _), goal, constraint)
        case Constraint("int_pow", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerValue](new Power(_, _, _, _, _), goal, constraint)
        case Constraint("int_mod", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerValue](new Mod(_, _, _, _, _), goal, constraint)
        case Constraint("int_abs", List(a, b), _) =>
            compileUnaryConstraint[IntegerValue, IntegerValue](new Abs(_, _, _, _), goal, constraint)
       // expansion of terms in parameters
        case Constraint(IntLin(name), (as @ Term(_, _)) :: t, _) =>
            compileConstraint(goal, constraint.copy(params = ArrayConst(getArrayElems(as).toList) :: t))
        case Constraint(IntLin(name), as :: (bs @ Term(_, _)) :: t, _) =>
            compileConstraint(goal, constraint.copy(params = as :: ArrayConst(getArrayElems(bs).toList) :: t))
        case Constraint(IntLin(name), as :: bs :: c :: t, _) if ! c.isConst && compilesToConst(c) =>
            compileConstraint(goal, constraint.copy(params = as :: bs :: IntConst(getConst[IntegerValue](c).value) :: t))
        // -1 * x <op> c -> 1 * x <op> -c where op in {==, !=}
        case Constraint(IntLin(name), ArrayConst(List(IntConst(-1))) :: bs :: IntConst(c) :: t, _)
        if name == "eq" || name == "ne" =>
            compileConstraint(goal, constraint.copy(params = ArrayConst(List(IntConst(1))) :: bs :: IntConst(-c) :: t))
        // 1 * x <op> c -> x <op> c
        case Constraint(IntLin(name), ArrayConst(List(IntConst(1))) :: ArrayConst(bs) :: c :: t, annotations) =>
            compileConstraint(goal, Constraint("int_" + name, bs.head :: c :: t, annotations))
        // -1 * x + 1 * y <op> 0 -> 1 * y + -1 * x <op> 0
        case Constraint(
            IntLin(name),
            ArrayConst(List(IntConst(-1), IntConst(1))) :: ArrayConst(List(x, y)) :: (c @ IntConst(0)) :: t, annotations) =>
            compileConstraint(
                goal,
                constraint.copy(params = ArrayConst(List(IntConst(1), IntConst(-1))) :: ArrayConst(List(y, x)) :: c :: t))
        // 1 * x + -1 * y <op> 0 -> x <op> y
        case Constraint(
            IntLin(name),
            ArrayConst(List(IntConst(1), IntConst(-1))) :: ArrayConst(List(x, y)) :: IntConst(0) :: t, annotations) =>
            compileConstraint(goal, Constraint("int_" + name, x :: y :: t, annotations))
        case Constraint(
            "int_lin_eq",
            List(ArrayConst(as), ArrayConst(bs), c), annotations)
            if (! definesVar(constraint, c) &&
                as.toIterator.zip(bs.toIterator).exists{
                    case ((IntConst(a), b)) => (a == -1 || a == 1) && definesVar(constraint, b)}) =>
            val abs = as.zip(bs)
            val (a, b) = abs.find{case ((IntConst(a), b)) => (a == -1 || a == 1) && definesVar(constraint, b)}.get
            a match {
                case IntConst(1) =>
                    // b1 + a2 b2 + ... = c
                    // b1               = c - a2 b2 - ...
                    val (as1, bs1) = (for ((IntConst(a), b1) <- abs if b1 != b) yield (IntConst(-a), b1)).unzip
                    compileConstraint(
                        goal,
                        Constraint("int_lin_eq", List(ArrayConst(IntConst(1) :: as1), ArrayConst(c :: bs1), b), annotations))
                case IntConst(-1) =>
                    // -1 b1 + a2 b2 + ... =    c
                    // -1 b1               =    c - a2 b2 - ...
                    //    b1               = -1 c + a2 b2 + ...
                    val bs1 = for (b1 <- bs) yield if (b1 == b) c else b1
                    compileConstraint(goal, Constraint("int_lin_eq", List(ArrayConst(as), ArrayConst(bs1), b), annotations))
            }
        case Constraint("int_lin_eq", List(as, bs, c), _) =>
            def withFunctionalDependency = {
                compileLinearCombination[IntegerValue](goal, as, bs, Some(c))
                Nil
            }
            def withoutFunctionalDependency = {
                val channel = compileLinearCombination[IntegerValue](goal, as, bs)
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Eq[IntegerValue](nextConstraintId, goal, channel, c, costs))
                List(costs)
            }
            compileConstraint(constraint, bs, c, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_lin_eq_reif", List(as, bs, c, r), _) =>
            def withFunctionalDependency = {
                val channel = compileLinearCombination[IntegerValue](goal, as, bs)
                space.post(new Eq[IntegerValue](nextConstraintId, goal, channel, c, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, bs, r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_lin_ne", List(as, bs, c), _) =>
            val channel = compileLinearCombination[IntegerValue](goal, as, bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Ne[IntegerValue](nextConstraintId, goal, channel, c, costs))
            List(costs)
        case Constraint("int_lin_ne_reif", List(as, bs, c, r), _) =>
            def withFunctionalDependency = {
                val channel = compileLinearCombination[IntegerValue](goal, as, bs)
                space.post(new Ne[IntegerValue](nextConstraintId, goal, channel, c, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, bs, r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("int_lin_le", List(as, bs, c), _) =>
            val channel = compileLinearCombination[IntegerValue](goal, as, bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Le[IntegerValue](nextConstraintId, goal, channel, c, costs))
            List(costs)
        case Constraint("int_lin_le_reif", List(as, bs, c, r), _) =>
            def withFunctionalDependency = {
                val channel = compileLinearCombination[IntegerValue](goal, as, bs)
                space.post(new Le[IntegerValue](nextConstraintId, goal, channel, c, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, bs, r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("array_int_maximum", List(b, as), _) =>
            val xs = compileArray[IntegerValue](as)
            val y = compileExpr[IntegerValue](b)
            def withFunctionalDependency = {
                space.post(new Maximum[IntegerValue](nextConstraintId, goal, xs, y))
                Nil
            }
            def withoutFunctionalDependency = {
                val max = createChannel[IntegerValue]
                space.post(new Maximum[IntegerValue](nextConstraintId, goal, xs, max))
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Eq[IntegerValue](nextConstraintId, goal, max, y, costs))
                List(costs)
            }
            compileConstraint(constraint, xs, y, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("array_int_minimum", List(b, as), _) =>
            val xs = compileArray[IntegerValue](as)
            val y = compileExpr[IntegerValue](b)
            def withFunctionalDependency = {
                space.post(new Minimum[IntegerValue](nextConstraintId, goal, xs, y))
                Nil
            }
            def withoutFunctionalDependency = {
                val max = createChannel[IntegerValue]
                space.post(new Minimum[IntegerValue](nextConstraintId, goal, xs, max))
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Eq[IntegerValue](nextConstraintId, goal, max, y, costs))
                List(costs)
            }
            compileConstraint(constraint, xs, y, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("array_var_bool_element", params, annotations) =>
            compileElementConstraint[BooleanValue](goal, constraint)
        case Constraint("array_bool_element", List(b, as, c), annotations) =>
            compileElementConstraint[BooleanValue](goal, constraint)
        case Constraint("array_var_int_element", params, annotations) =>
            compileElementConstraint[IntegerValue](goal, constraint)
        case Constraint("array_int_element", List(b, as, c), _) =>
            compileElementConstraint[IntegerValue](goal, constraint)
        case Constraint("array_var_set_element", params, annotations) =>
            compileElementConstraint[IntegerSetValue](goal, constraint)
        case Constraint("array_set_element", List(b, as, c), _) =>
            compileElementConstraint[IntegerSetValue](goal, constraint)
        case Constraint("set_eq", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Eq[IntegerSetValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("set_eq_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Eq[IntegerSetValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("set_ne", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Ne[IntegerSetValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("set_ne_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Ne[IntegerSetValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("set_lt", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Lt[IntegerSetValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("set_lt_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Lt[IntegerSetValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("set_le", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Le[IntegerSetValue](nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("set_le_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Le[IntegerSetValue](nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("set_card", List(a, b), _) =>
            compileUnaryConstraint[IntegerSetValue, IntegerValue](new SetCardinality(_, _, _, _), goal, constraint)
        case Constraint("set_in", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Contains(nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("set_in_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Contains(nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("set_subset", List(a, b), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Subset(nextConstraintId, goal, a, b, costs))
            List(costs)
        case Constraint("set_subset_reif", List(a, b, r), _) =>
            def withFunctionalDependency = {
                space.post(new Subset(nextConstraintId, goal, a, b, r))
                Nil
            }
            def withoutFunctionalDependency = {
                compileReifiedConstraint(goal, constraint)
            }
            compileConstraint(constraint, List(a, b), r, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("set_intersect", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerSetValue](new SetIntersection(_, _, _, _, _), goal, constraint)
        case Constraint("set_union", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerSetValue](new SetUnion(_, _, _, _, _), goal, constraint)
        case Constraint("set_diff", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerSetValue](new SetDifference(_, _, _, _, _), goal, constraint)
        case Constraint("set_symdiff", List(a, b, c), _) =>
            compileBinaryConstraint[IntegerSetValue](
                new SymmetricalSetDifference(_, _, _, _, _), goal, constraint)
        case Constraint("all_different_int", List(as), _) =>
            val xs = compileArray[IntegerValue](as)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Alldistinct(nextConstraintId, goal, xs, costs))
            List(costs)
        case Constraint("alldifferent_except_0", List(as), _) =>
            val xs = compileArray[IntegerValue](as)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new AlldistinctExceptZero(nextConstraintId, goal, xs, costs))
            List(costs)
        case Constraint("nvalue", List(n0, as), _) =>
            val xs = compileArray[IntegerValue](as)
            val n = compileExpr[IntegerValue](n0)
            def withFunctionalDependency = {
                space.post(new NumberOfDistinctValues[IntegerValue](nextConstraintId, goal, xs, n))
                Nil
            }
            def withoutFunctionalDependency = {
              val m = createNonNegativeChannel[IntegerValue]
              space.post(new NumberOfDistinctValues[IntegerValue](nextConstraintId, goal, xs, m))
              val costs = createNonNegativeChannel[BooleanValue]
              space.post(new Eq[IntegerValue](nextConstraintId, goal, m, n, costs))
              List(costs)
            }
            compileConstraint(constraint, xs, n, withFunctionalDependency, withoutFunctionalDependency)
        case Constraint("at_most_int", List(n, as, v), _) =>
            compileConstraint(goal, Constraint("count_geq", List(as, v, n), constraint.annotations))
        case Constraint("at_least_int", List(n, as, v), _) =>
            compileConstraint(goal, Constraint("count_leq", List(as, v, n), constraint.annotations))
        case Constraint("exactly_int", List(n, as, v), _) =>
            compileConstraint(goal, Constraint("count_eq", List(as, v, n), constraint.annotations))
        case Constraint("count_eq", _, _) =>
            compileCountConstraint[IntegerValue](goal, constraint, new Eq[IntegerValue](_, _, _, _, _))
        case Constraint("count_neq", _, _) =>
            compileCountConstraint[IntegerValue](goal, constraint, new Ne[IntegerValue](_, _, _, _, _))
        case Constraint("count_leq", _, _) =>
            compileCountConstraint[IntegerValue](
                goal, constraint, (id, goal, x, y, z) => new Le[IntegerValue](id, goal, y, x, z))
        case Constraint("count_lt", _, _) =>
            compileCountConstraint[IntegerValue](
                goal, constraint, (id, goal, x, y, z) => new Lt[IntegerValue](id, goal, y, x, z))
        case Constraint("count_geq", _, _) =>
            compileCountConstraint[IntegerValue](goal, constraint, new Le[IntegerValue](_, _, _, _, _))
        case Constraint("count_gt", _, _) =>
            compileCountConstraint[IntegerValue](goal, constraint, new Lt[IntegerValue](_, _, _, _, _))
        case Constraint("member_bool", _, _) =>
            compileMemberConstraint[BooleanValue](goal, constraint)
        case Constraint("member_int", _, _) =>
            compileMemberConstraint[IntegerValue](goal, constraint)
        case Constraint("member_set", _, _) =>
            compileMemberConstraint[IntegerSetValue](goal, constraint)
        case Constraint("yuck_cumulative", List(s, d, r, ub), _) =>
            val xs = compileArray[IntegerValue](s)
            val ys = compileArray[IntegerValue](d)
            val zs = compileArray[IntegerValue](r)
            assert(xs.size == ys.size)
            assert(ys.size == zs.size)
            val tasks = for (((x, y), z) <- xs.zip(ys).zip(zs)) yield new CumulativeTask(x, y, z)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Cumulative(nextConstraintId, goal, tasks, ub, costs))
            List(costs)
        case Constraint("yuck_table_int", List(as, flatT), _) =>
            val xs = compileArray[IntegerValue](as)
            val t = compileArray[IntegerValue](flatT)
            val n = xs.size
            require(t.size % n == 0)
            val costs = createNonNegativeChannel[BooleanValue]
            val rows = t.map(_.domain.singleValue.value).grouped(n).toIndexedSeq
            // IntegerTable is linear in the number of rows (m).
            // In the case of performance issues, there are options:
            // (1) Use the original FlatZinc decomposition (for big m only) which introduces
            // a search variable to choose a row from the matrix.
            // This approach could be implemented here or in table_int.mzn.
            // (2) Provide an implementation on the basis of checking membership in the given row set.
            // This approach is cheap and generic but comes at the price of a poor cost model.
            space.post(new IntegerTable(nextConstraintId, goal, xs, rows, costs))
            List(costs)
        case Constraint("yuck_regular", List(xs, IntConst(q), IntConst(s), flatDelta, IntConst(q0), f), _) =>
            val Q = q
            val S = s
            val delta = compileArray[IntegerValue](flatDelta).map(_.domain.singleValue.value).grouped(s).toIndexedSeq
            val F = compileExpr[IntegerSetValue](f).domain.singleValue.set
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Regular(nextConstraintId, goal, xs, Q, S, delta, q0, F, costs))
            List(costs)
        case Constraint("yuck_inverse", List(f, IntConst(fOffset), g, IntConst(gOffset)), _) =>
            val costs = createNonNegativeChannel[BooleanValue]
            val constraint = new Inverse(nextConstraintId, goal, new InverseFunction(f, fOffset), new InverseFunction(g, gOffset), costs)
            val constraints = constraint.decompose(space)
            constraints.foreach(space.post)
            constraints.map(_.outVariables).flatten
        case Constraint("yuck_bin_packing", List(loads0, bins0, weights0, IntConst(minLoadIndex)), _) =>
            val bins = compileArray[IntegerValue](bins0)
            val weights = getArrayElems(weights0).map(getConst[IntegerValue](_))
            require(bins.size == weights.size)
            val itemGenerator =
                for ((bin, weight) <- bins.toIterator.zip(weights.toIterator)) yield
                    new BinPackingItem(bin, weight)
            val items = itemGenerator.toIndexedSeq
            val loads1 = compileArray[IntegerValue](loads0)
            val loads = (minLoadIndex until minLoadIndex + loads1.size).toIterator.zip(loads1.toIterator).toMap
            compileBinPackingConstraint(goal, constraint, items, loads)
        case Constraint("yuck_global_cardinality", List(xs0, cover0, counts0), _) =>
            val xs = compileArray[IntegerValue](xs0)
            val items = xs.map(new BinPackingItem(_, One))
            val cover = getArrayElems(cover0).map(getConst[IntegerValue](_).value)
            val counts = compileArray[IntegerValue](counts0)
            require(cover.size == counts.size)
            val loads = cover.toIterator.zip(counts.toIterator).toMap
            compileBinPackingConstraint(goal, constraint, items, loads)
        case Constraint("lex_less_int", List(as, bs), _) =>
            val xs = compileArray[IntegerValue](as)
            val ys = compileArray[IntegerValue](bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new LexLess(nextConstraintId, goal, xs, ys, costs))
            List(costs)
        case Constraint("lex_less_bool", List(as, bs), _) =>
            val xs = compileArray[BooleanValue](as)
            val ys = compileArray[BooleanValue](bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new LexLess(nextConstraintId, goal, xs, ys, costs)(booleanSequenceOrdering))
            List(costs)
        case Constraint("lex_less_set", List(as, bs), _) =>
            val xs = compileArray[IntegerSetValue](as)
            val ys = compileArray[IntegerSetValue](bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new LexLess(nextConstraintId, goal, xs, ys, costs))
            List(costs)
        case Constraint("lex_lesseq_int", List(as, bs), _) =>
            val xs = compileArray[IntegerValue](as)
            val ys = compileArray[IntegerValue](bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new LexLessEq(nextConstraintId, goal, xs, ys, costs))
            List(costs)
        case Constraint("lex_lesseq_bool", List(as, bs), _) =>
            val xs = compileArray[BooleanValue](as)
            val ys = compileArray[BooleanValue](bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new LexLessEq(nextConstraintId, goal, xs, ys, costs)(booleanSequenceOrdering))
            List(costs)
        case Constraint("lex_lesseq_set", List(as, bs), _) =>
            val xs = compileArray[IntegerSetValue](as)
            val ys = compileArray[IntegerSetValue](bs)
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new LexLessEq(nextConstraintId, goal, xs, ys, costs))
            List(costs)
        case Constraint(Reif(name), _, _) =>
            compileReifiedConstraint(goal, constraint)
    }

    private def compileUnaryConstraint
        [InputValue <: OrderedValue[InputValue], OutputValue <: OrderedValue[OutputValue]]
        (operationFactory: UnaryConstraintFactory[InputValue, OutputValue],
         goal: Goal, constraint: yuck.flatzinc.ast.Constraint)
        (implicit inputValueTraits: AnyValueTraits[InputValue], outputValueTraits: AnyValueTraits[OutputValue]):
        Iterable[Variable[BooleanValue]] =
    {
        val List(a, b) = constraint.params
        def withFunctionalDependency = {
            space.post(operationFactory(nextConstraintId, goal, a, b))
            Nil
        }
        def withoutFunctionalDependency = {
            val channel = createChannel[OutputValue]
            space.post(operationFactory(nextConstraintId, goal, a, channel))
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Eq[OutputValue](nextConstraintId, goal, channel, b, costs))
            List(costs)
        }
        compileConstraint(constraint, List(a), b, withFunctionalDependency, withoutFunctionalDependency)
    }

    private def compileBinaryConstraint
        [Value <: OrderedValue[Value]]
        (operationFactory: BinaryConstraintFactory[Value, Value],
         goal: Goal, constraint: yuck.flatzinc.ast.Constraint)
        (implicit valueTraits: AnyValueTraits[Value]):
        Iterable[Variable[BooleanValue]] =
    {
        val List(a, b, c) = constraint.params
        def withFunctionalDependency = {
            space.post(operationFactory(nextConstraintId, goal, a, b, c))
            Nil
        }
        def withoutFunctionalDependency = {
            val channel = createChannel[Value]
            space.post(operationFactory(nextConstraintId, goal, a, b, channel))
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Eq[Value](nextConstraintId, goal, channel, c, costs))
            List(costs)
        }
        compileConstraint(constraint, List(a, b), c, withFunctionalDependency, withoutFunctionalDependency)
    }

    private def compileBinPackingConstraint
        [Load <: NumericalValue[Load]]
        (goal: Goal, constraint: yuck.flatzinc.ast.Constraint,
         items: immutable.Seq[BinPackingItem[Load]],
         loads: immutable.Map[Int, Variable[Load]]) // bin -> load
        (implicit valueTraits: NumericalValueTraits[Load]):
        Iterable[Variable[BooleanValue]] =
    {
        val bin2Weight = new mutable.AnyRefMap[Variable[IntegerValue], Load]
        for (item <- items) {
            require(item.weight >= valueTraits.zero)
            bin2Weight += item.bin -> (bin2Weight.getOrElse(item.bin, valueTraits.zero) + item.weight)
        }
        val itemGenerator =
            for ((bin, weight) <- bin2Weight.toIterator if weight > valueTraits.zero)
                yield new BinPackingItem(bin, weight)
        val items1 = itemGenerator.toIndexedSeq
        val loadGenerator = {
            val bins = items1.map(_.bin)
            val definedVars = new mutable.HashSet[Variable[Load]]
            for ((bin, load) <- loads) yield
                if (! definedVars.contains(load) && definesVar(constraint, bins, loads(bin))) {
                    definedVars += load
                    bin -> load
                }
                else bin -> createNonNegativeChannel[Load]
        }
        val loads1 = loadGenerator.toMap
        space.post(new BinPacking[Load](nextConstraintId, goal, items1, loads1))
        val deltaGenerator =
            for ((bin, load) <- loads if load != loads1(bin)) yield {
                val delta = createNonNegativeChannel[BooleanValue]
                space.post(new Eq[Load](nextConstraintId, goal, load, loads1(bin), delta))
                delta
            }
        deltaGenerator.toSeq
    }

    private def compileLinearCombination
        [Value <: NumericalValue[Value]]
        (goal: Goal,
         as: Expr, bs: Expr,
         maybeChannel: Option[Variable[Value]] = None)
        (implicit valueTraits: NumericalValueTraits[Value]):
        Variable[Value] =
    {
        val xs = compileArray[Value](as)
        val ys = compileArray[Value](bs)
        require(xs.size == ys.size)
        val axs =
          AX.compact(
              for ((x, y) <- xs.toIterator.zip(ys.toIterator)
              if x.domain.singleValue != valueTraits.zero && (! y.domain.isSingleton || y.domain.singleValue != valueTraits.zero))
                  yield new AX[Value](x.domain.singleValue, y))
        axs match {
            case List(AX(One, x)) if maybeChannel.isEmpty =>
                x
            case List(AX(One, x), AX(MinusOne, y)) =>
                val channel = maybeChannel.getOrElse(createChannel[Value])
                space.post(new Minus[Value](nextConstraintId, goal, x, y, channel))
                channel
            case List(AX(MinusOne, x), AX(One, y)) =>
                val channel = maybeChannel.getOrElse(createChannel[Value])
                space.post(new Minus[Value](nextConstraintId, goal, y, x, channel))
                channel
            case _ =>
                val channel = maybeChannel.getOrElse(createChannel[Value])
                if (axs.forall(ax => ax.a == One)) {
                   val xs = axs.toIterator.map(ax => ax.x).toIndexedSeq
                   space.post(new Sum(nextConstraintId, goal, xs /* not ys! */, channel))
                } else {
                   space.post(new LinearCombination(nextConstraintId, goal, axs.toIndexedSeq, channel))
                }
                channel
        }
    }

    private def compileCountConstraint
        [Value <: AnyValue]
        (goal: Goal, constraint: yuck.flatzinc.ast.Constraint,
         comparatorFactory: BinaryConstraintFactory[IntegerValue, BooleanValue])
        (implicit valueTraits: AnyValueTraits[Value]):
        Iterable[Variable[BooleanValue]] =
    {
        val Constraint(_, List(as, a, m), _) = constraint
        val xs = compileArray[Value](as)
        if (compilesToConst(a)) {
            val y = compileExpr[Value](a).domain.singleValue
            def withFunctionalDependency = {
                space.post(new CountConst[Value](nextConstraintId, goal, xs, y, m))
                Nil
            }
            def withoutFunctionalDependency = {
                val n = createNonNegativeChannel[IntegerValue]
                space.post(new CountConst[Value](nextConstraintId, goal, xs, y, n))
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(comparatorFactory(nextConstraintId, goal, n, m, costs))
                List(costs)
            }
            compileConstraint(constraint, xs, m, withFunctionalDependency, withoutFunctionalDependency)
        } else {
            val y = compileExpr[Value](a)
            def withFunctionalDependency = {
                space.post(new CountVar[Value](nextConstraintId, goal, xs, y, m))
                Nil
            }
            def withoutFunctionalDependency = {
                val n = createNonNegativeChannel[IntegerValue]
                space.post(new CountVar[Value](nextConstraintId, goal, xs, y, n))
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(comparatorFactory(nextConstraintId, goal, n, m, costs))
                List(costs)
            }
            compileConstraint(constraint, xs :+ y, m, withFunctionalDependency, withoutFunctionalDependency)
        }
    }

    private def compileMemberConstraint
        [Value <: AnyValue]
        (goal: Goal, constraint: yuck.flatzinc.ast.Constraint)
        (implicit valueTraits: AnyValueTraits[Value]):
        Iterable[Variable[BooleanValue]] =
        compileCountConstraint(
            goal,
            constraint.copy(id = "count_leq", params = constraint.params ++ List(IntConst(1))),
            (id, goal, n, m, costs) => new Le[IntegerValue](id, goal, m, n, costs))

    private def compileElementConstraint
        [Value <: OrderedValue[Value]]
        (goal: Goal, constraint: yuck.flatzinc.ast.Constraint)
        (implicit valueTraits: OrderedValueTraits[Value]):
        Iterable[Variable[BooleanValue]] =
    {
        val Constraint(_, List(b, as, c), annotations) = constraint
        val y = compileExpr[IntegerValue](b)
        val xs = compileArray[Value](as)
        val z = compileExpr[Value](c)
        val indexBase = annotations match {
            case List(Annotation(Term("indexBase", List(IntConst(b))))) => b
            case _ => 1
        }
        val indexRange = createIntegerDomain(indexBase, xs.size - indexBase + 1)
        val result = mutable.ArrayBuffer[Variable[BooleanValue]]()
        if (b.isConst) {
            if (! indexRange.contains(getConst[IntegerValue](b))) {
                throw new InconsistentConstraintException(constraint)
            }
        } else if (! y.domain.isSubsetOf(indexRange)) {
            // The index may be out of range, so we have to add a check, as required by the FlatZinc spec.
            val delta = createNonNegativeChannel[BooleanValue]
            space.post(new Contains(nextConstraintId, goal, y, indexRange, delta))
            result += delta
        }
        def withFunctionalDependency = {
            space.post(new Element[Value](nextConstraintId, goal, xs, y, z, indexBase))
            result
        }
        def withoutFunctionalDependency = {
            val channel = createChannel[Value]
            space.post(new Element[Value](nextConstraintId, goal, xs, y, channel, indexBase))
            val costs = createNonNegativeChannel[BooleanValue]
            space.post(new Eq[Value](nextConstraintId, goal, channel, z, costs))
            result += costs
            result
        }
        compileConstraint(constraint, xs :+ y, z, withFunctionalDependency, withoutFunctionalDependency)
    }

    private def compileReifiedConstraint
        (goal: Goal, reifiedConstraint: yuck.flatzinc.ast.Constraint):
        Iterable[Variable[BooleanValue]] =
    {
        val Constraint(Reif(name), params, annotations) = reifiedConstraint
        val constraint = Constraint(name, params.take(params.size - 1), annotations)
        val satisfied = compileExpr[BooleanValue](params.last)
        if (compilesToConst(params.last, True)) {
            if (impliedConstraints.contains(constraint)) Nil
            else compileConstraint(goal, constraint)
        } else if (impliedConstraints.contains(constraint)) {
            def withFunctionalDependency = {
                space.post(new Sum[BooleanValue](nextConstraintId, goal, Nil, satisfied))
                Nil
            }
            def withoutFunctionalDependency = {
                List(satisfied)
            }
            compileConstraint(reifiedConstraint, Nil, satisfied, withFunctionalDependency, withoutFunctionalDependency)
        } else {
            val costs0 = compileConstraint(goal, constraint).toList
            def withFunctionalDependency = {
                space.post(new Sum(nextConstraintId, goal, costs0, satisfied))
                Nil
            }
            def withoutFunctionalDependency = {
                val costs1 =
                    if (costs0.size == 1)
                        costs0.head
                    else {
                        val costs2 = createNonNegativeChannel[BooleanValue]
                        space.post(new Sum(nextConstraintId, goal, costs0, costs2))
                        costs2
                    }
                val costs = createNonNegativeChannel[BooleanValue]
                space.post(new Eq[BooleanValue](nextConstraintId, goal, costs1, satisfied, costs))
                List(costs)
            }
            compileConstraint(reifiedConstraint, costs0, satisfied, withFunctionalDependency, withoutFunctionalDependency)
        }
    }

}
