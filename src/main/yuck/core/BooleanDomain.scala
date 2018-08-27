package yuck.core

/**
 * Provides an interface for working with Boolean domains.
 *
 * @author Michael Marte
 */
abstract class BooleanDomain extends NumericalDomain[BooleanValue] {

    import BooleanDomain._

    final override def valueTraits = BooleanValueTraits

    final override def equals(that: Domain[BooleanValue]): Boolean = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) => lhs.equals(rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => false
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => true
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => false
        case _ => ???
    }

    final override def compare(that: Domain[BooleanValue]): Int = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) =>
            BooleanDecisionDomain.ordering.compare(lhs, rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => -1
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => 0
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => 1
        case _ => ???
    }

    override def hull: BooleanDomain = this

    final override def isSubsetOf(that: Domain[BooleanValue]): Boolean = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) => lhs.isSubsetOf(rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => true
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => true
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => false
        case _ => ???
    }
    final override def intersects(that: Domain[BooleanValue]): Boolean = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) => lhs.intersects(rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => true
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => true
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => true
        case _ => ???
    }
    final override def intersect(that: Domain[BooleanValue]): BooleanDomain = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) => lhs.intersect(rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => lhs
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => lhs
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => rhs
        case _ => ???
    }
    final override def union(that: Domain[BooleanValue]): BooleanDomain = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) => lhs.union(rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => rhs
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => lhs
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => lhs
        case _ => ???
    }
    final override def diff(that: Domain[BooleanValue]): BooleanDomain = (this, that) match {
        case (lhs: BooleanDecisionDomain, rhs: BooleanDecisionDomain) => lhs.diff(rhs)
        case (lhs: BooleanDecisionDomain, rhs: BooleanChannelDomain.type) => EmptyBooleanDomain
        case (lhs: BooleanChannelDomain.type, rhs: BooleanChannelDomain.type) => EmptyBooleanDomain
        case (lhs: BooleanChannelDomain.type, rhs: BooleanDecisionDomain) => if (rhs.isEmpty) lhs else !!!
        case _ => ???
    }

}

/**
 * Companion object to BooleanDomain.
 *
 * @author Michael Marte
 */
final object BooleanDomain {

    /** A total ordering on Boolean domains. */
    val ordering = new Ordering[BooleanDomain] {
        override def compare(lhs: BooleanDomain, rhs: BooleanDomain) = lhs.compare(rhs)
    }

}
