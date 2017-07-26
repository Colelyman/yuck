package yuck.flatzinc.compiler

import scala.collection._

import yuck.constraints._
import yuck.core._

/**
 * Enforces domains of FlatZinc variables.
 *
 * Domains of search variables are enforced by reducing their domains.
 *
 * Domains of channel variables are enforced by posting appropriate constraints.
 *
 * @author Michael Marte
 */
final class DomainEnforcer
    (cc: CompilationContext, randomGenerator: RandomGenerator)
    extends CompilationPhase(cc, randomGenerator)
{

    override def run {
        enforceDomains
    }

    private def enforceDomains {
        val done = new mutable.HashSet[AnyVariable]
        for ((key, x) <- cc.vars if ! done.contains(x)) {
            val dx = cc.domains(key)
            val tx = dx.valueType
            if (tx == BooleanValueTraits.valueType) {
                enforceBooleanDomain(IntegerValueTraits.staticDowncast(x), dx.asInstanceOf[BooleanDomain])
            } else if (tx == IntegerValueTraits.valueType) {
                enforceIntegerDomain(IntegerValueTraits.staticDowncast(x), dx.asInstanceOf[IntegerDomain])
            } else if (tx == IntegerSetValueTraits.valueType) {
                enforceIntegerSetDomain(IntegerSetValueTraits.staticDowncast(x), dx.asInstanceOf[IntegerPowersetDomain])
            }
            cc.logger.logg("Domain of %s is %s".format(x, x.domain))
            done += x
        }
    }

    private def enforceBooleanDomain(x: Variable[IntegerValue], dx: BooleanDomain) {
        if (dx.isSingleton) {
            if (cc.space.isChannelVariable(x)) {
                x.turnIntoChannel(IntegerValueTraits)
                x.pruneDomain(NonNegativeIntegerDomain)
                if (dx.singleValue == True) {
                    cc.costVars += x
                } else {
                    val costs = createNonNegativeChannel[IntegerValue]
                    cc.space.post(new NumLe(nextConstraintId, null, One, x, costs))
                    cc.costVars += costs
                }
            } else {
                cc.space.setValue(x, if (dx.singleValue == True) Zero else One)
            }
        } else if (cc.space.isChannelVariable(x)) {
            x.turnIntoChannel(IntegerValueTraits)
            x.pruneDomain(NonNegativeIntegerDomain)
        }
    }

    private def enforceIntegerDomain(x: Variable[IntegerValue], dx: IntegerDomain) {
        if (dx.isBounded) {
            if (cc.space.isChannelVariable(x)) {
                x.turnIntoChannel(IntegerValueTraits)
                if (! cc.space.definingConstraint(x).get.isInstanceOf[Bool2Int1] || dx.isSingleton) {
                    val costs = createNonNegativeChannel[IntegerValue]
                    cc.space.post(new SetIn(nextConstraintId, null, x, dx, costs))
                    cc.costVars += costs
                }
            } else {
                if (dx.isSingleton) {
                    cc.space.setValue(x, dx.singleValue)
                }
            }
        }
    }

    private def enforceIntegerSetDomain(x: Variable[IntegerSetValue], dx: IntegerPowersetDomain) {
        if (dx.isBounded) {
            if (cc.space.isChannelVariable(x)) {
                x.turnIntoChannel(IntegerSetValueTraits)
                val costs = createNonNegativeChannel[IntegerValue]
                cc.space.post(new SetSubset(nextConstraintId, null, x, dx.base, costs))
                cc.costVars += costs
            } else {
                if (dx.isSingleton) {
                    cc.space.setValue(x, dx.singleValue)
                }
            }
        }
    }

}
