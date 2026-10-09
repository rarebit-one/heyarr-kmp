package one.rarebit.heyarr.desktop.device

import one.rarebit.voidwhichbinds.Membership
import one.rarebit.voidwhichbinds.MembershipOp
import one.rarebit.voidwhichbinds.auth.DeviceCredential

/**
 * Which membership ops this desktop presents beside its `Device` credential — the `ops`
 * in the `POST /enrol` body (heyarr-core ADR-0068). The desktop copy of heyarr-mobile's
 * `device/MembershipOps`, unchanged: the justifying closure of the admitting op first,
 * the rest in hash order, capped at [MAX].
 */
object MembershipOps {

    const val MAX = DeviceCredential.MAX_PRESENTED_OPS

    fun presentable(known: List<String>, admittingOp: String?, max: Int = MAX): List<String> {
        val all = Membership.merge(known, admittingOp?.let { listOf(it) } ?: emptyList())
        if (all.size <= max) return all
        val must = if (admittingOp == null) emptyList() else closure(all, admittingOp)
        val rest = all.filter { it !in must }
        return (must + rest).take(max)
    }

    fun closure(known: List<String>, admittingOp: String): List<String> {
        val byHash = HashMap<String, String>()
        for (tok in known) byHash[MembershipOp.hash(tok)] = tok
        byHash[MembershipOp.hash(admittingOp)] = admittingOp
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue += MembershipOp.hash(admittingOp)
        while (queue.isNotEmpty()) {
            val h = queue.removeFirst()
            if (!seen.add(h)) continue
            val tok = byHash[h] ?: continue
            val op = runCatching { MembershipOp.verify(tok) }.getOrNull() ?: continue
            for (p in op.prev) if (p !in seen) queue += p
        }
        return seen.sorted().mapNotNull { byHash[it] }
    }
}
