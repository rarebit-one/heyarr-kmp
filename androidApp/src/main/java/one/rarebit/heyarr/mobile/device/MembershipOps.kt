package one.rarebit.heyarr.mobile.device

import one.rarebit.voidwhichbinds.Membership
import one.rarebit.voidwhichbinds.MembershipOp
import one.rarebit.voidwhichbinds.auth.DeviceCredential

/**
 * Which of the membership ops this device knows it **presents** beside its `Device`
 * credential — the `Voidbind-Membership` header on every request, and `ops` in the
 * `POST /enrol` body (voidbind-kmp ADR-0005 / heyarr-core ADR-0068).
 *
 * A relying party merges what a device presents with its own log before evaluating,
 * so a device admitted by a phone the node has never met still authenticates on
 * first contact — but it accepts at most [DeviceCredential.MAX_PRESENTED_OPS] (64);
 * more is a refusal, not a truncation. While the replica fits, everything is
 * presented (in hash order, the CRDT's canonical order). Once it does not, the ops
 * that **justify this device's own admission** come first — the admitting op and
 * its transitive `prev` ancestry, the causal chain the RP needs to find the admitter
 * a member — and the rest fill the remaining room in hash order.
 *
 * Pure: no I/O, nothing re-derived about the wire (the op parser is the library's).
 */
object MembershipOps {

    const val MAX = DeviceCredential.MAX_PRESENTED_OPS

    /** The ops to present, at most [MAX], the justifying closure of [admittingOp] first. */
    fun presentable(known: List<String>, admittingOp: String?, max: Int = MAX): List<String> {
        val all = Membership.merge(known, admittingOp?.let { listOf(it) } ?: emptyList())
        if (all.size <= max) return all
        val must = if (admittingOp == null) emptyList() else closure(all, admittingOp)
        val rest = all.filter { it !in must }
        return (must + rest).take(max)
    }

    /** The `Voidbind-Membership` value for what [presentable] returns; empty when there is nothing. */
    fun headerValue(known: List<String>, admittingOp: String?): String =
        presentable(known, admittingOp).joinToString(",")

    /**
     * [admittingOp] plus every op it transitively cites through `prev` that is in
     * [known], in hash order. An op that does not parse (a junk token in the replica)
     * is skipped, never fatal — the RP's evaluation reports it, not this.
     */
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
