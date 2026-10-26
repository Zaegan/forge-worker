import forge.ai.ComputerUtilAbility;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/**
 * P14 (user, 2026-10-26): Izzet Prowess's damage-turn planner, for turns with no lethal line (prowessplan's search runs
 * first). Forge cannot see that a spell's worth depends on what attacks that turn; the deck wants to kill quickly, so
 * every card should become damage to the face, and the attack decision follows how many creatures that grow per spell
 * (prowess, Slickshot Show-Off) can attack:
 *   1 grower: attack and pump only when some affordable set of spells gets it through unblocked (already evasive,
 *             Elusive Otter pumped past every blocker's power, the lone blocker removed before blocks)
 *   2: when, against the opponent's best blocks, damage gets through and no block costs us more creatures than them
 *   3+: unless no set of spells gets damage through or kills a blocker
 * No defensive hold: the deck races. The opponent's best blocks: every legal assignment of its untapped creatures
 * (each blocks one attacker or none), scored as damage taken + 3 per creature it loses - 3 per creature of ours it
 * kills (lethal damage is never accepted). Our spells are chosen to maximise the same score under that response.
 * Timing: removal on blockers (Burst Lightning, Unsummon), Wild Ride and sorcery cantrips go before combat; instant
 * cantrips wait until after blocks (opponent blocks against unpumped sizes) unless the plan needs them for evasion.
 * Opponent's end step: Burst a blocker now only if next turn's attack is then lethal and holding it is not, or if the
 * opponent's board could kill us and removing it now gets more damage through next turn (mana is the limit and time
 * is short); otherwise the removal waits for the attack, where it also triggers prowess.
 * Switches (one tier each, cumulative): prowessdmg1@NAME (P14a), prowessdmg2@NAME (P14b), prowessdmg3@NAME (P14c).
 * Surplus mana only (ProwessBrain.surplus: the cheapest creature in hand is cast first).
 */
public class ProwessPlan {
    static final int W = 3;

    static int tier(Player me) {
        return AiTweaks.on("prowessdmg3", me) ? 3 : AiTweaks.on("prowessdmg2", me) ? 2 : AiTweaks.on("prowessdmg1", me) ? 1 : 0;
    }

    static class Att { Card c; long pow, tough; int per, perT; boolean grower; }
    static class Blk { Card c; long pow, tough; }
    static class S { SpellAbility sa; Card card; int cost, dmg; boolean removal, ride, instant; }
    static class Out { long dmg; int theirLost, ourLost, growersThrough; }
    static class Choice { List<S> set = new ArrayList<>(); List<Object> tg = new ArrayList<>(); Out out; boolean hold; double value; }

    static List<Att> atts(List<Card> cs) {
        List<Att> out = new ArrayList<>();
        for (Card c : cs) {
            Att a = new Att(); a.c = c; a.pow = c.getNetPower(); a.tough = c.getNetToughness() - c.getDamage();
            a.per = ProwessBrain.perSpell(c); a.perT = c.hasKeyword(Keyword.PROWESS) ? ProwessBrain.triggers(c) : 0;
            a.grower = a.per > 0;
            out.add(a);
        }
        return out;
    }

    static List<Blk> blks(Player op, Card except) {
        List<Blk> out = new ArrayList<>();
        for (Card c : op.getCreaturesInPlay())
            if (c.isUntapped() && c != except) { Blk b = new Blk(); b.c = c; b.pow = c.getNetPower(); b.tough = c.getNetToughness() - c.getDamage(); out.add(b); }
        return out;
    }

    /** our instants/sorceries in hand the planner knows; STRICT: castable now (else: next turn, by mana value only) */
    static List<S> spells(Player me, boolean strict) {
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if ((c.isInstant() || c.isSorcery()) && !c.isCreature()) hand.add(c);
        List<S> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell() || sa.isAdventure() || !seen.add(sa.getHostCard().getId())) continue;
            if (strict && ProwessBrain.playable(me, sa) == null) continue;
            S s = new S(); s.sa = sa; s.card = sa.getHostCard(); s.cost = s.card.getCMC(); s.instant = s.card.isInstant();
            String n = s.card.getName();
            if (n.equals(ProwessBrain.BURST)) { s.removal = true; s.dmg = 2; }
            else if (n.equals(ProwessBrain.SEAR)) { s.removal = true; s.dmg = 4; }
            else if (n.equals(ProwessBrain.UNSUMMON)) { s.removal = true; s.dmg = 99; }
            else if (n.equals(ProwessBrain.WILDRIDE)) s.ride = true;
            else if (AiTweaks.targets(sa)) continue;   // other targeted spells: not planned
            out.add(s);
        }
        if (out.size() > 7) out = out.subList(0, 7);
        return out;
    }

    static boolean preBlock(S s) { return s.removal || s.ride || !s.instant; }

    /** the opponent's best blocks: KL spells count for blocking legality (Otter), KD for combat; EXTRA: Wild Ride */
    static Out response(List<Att> as, List<Blk> bs, int kl, int kd, long[] extra, long life) {
        int A = as.size(), B = bs.size();
        long[] pl = new long[A], pd = new long[A], td = new long[A];
        for (int i = 0; i < A; i++) {
            Att a = as.get(i);
            pl[i] = a.pow + (long) a.per * kl + extra[i]; pd[i] = a.pow + (long) a.per * kd + extra[i]; td[i] = a.tough + (long) a.perT * kd;
        }
        List<List<Integer>> opts = new ArrayList<>();
        long combos = 1;
        for (Blk b : bs) {
            List<Integer> o = new ArrayList<>(); o.add(-1);
            for (int i = 0; i < A; i++) if (ProwessBrain.canBlock(as.get(i).c, pl[i], b.c)) o.add(i);
            opts.add(o); combos *= o.size();
        }
        Out best = null; double bestScore = Double.MAX_VALUE;
        int[] pick = new int[B];
        long n = Math.min(combos, 4096);
        for (long m = 0; m < n; m++) {
            long r = m;
            for (int j = 0; j < B; j++) { pick[j] = opts.get(j).get((int) (r % opts.get(j).size())); r /= opts.get(j).size(); }
            Out o = outcome(as, bs, pick, pd, td);
            double sc = (o.dmg >= life ? 1e6 : 0) + o.dmg + W * (o.theirLost - o.ourLost);
            if (sc < bestScore) { bestScore = sc; best = o; }
        }
        if (combos > 4096) ControlBrain.count("prowessdmg_block_cap");
        return best;
    }

    static Out outcome(List<Att> as, List<Blk> bs, int[] pick, long[] pd, long[] td) {
        Out o = new Out();
        for (int i = 0; i < as.size(); i++) {
            List<Blk> on = new ArrayList<>();
            for (int j = 0; j < bs.size(); j++) if (pick[j] == i) on.add(bs.get(j));
            if (on.isEmpty()) { o.dmg += Math.max(0, pd[i]); if (as.get(i).grower) o.growersThrough++; continue; }
            on.sort((x, y) -> Long.compare(x.tough, y.tough));
            long rem = pd[i], hit = 0;
            for (Blk b : on) { hit += b.pow; if (rem >= b.tough) { o.theirLost++; rem -= b.tough; } else rem = 0; }
            if (hit >= td[i]) o.ourLost++;
        }
        return o;
    }

    static boolean meets(int g, Out o) {
        if (g == 1) return o.growersThrough >= 1 && o.ourLost == 0;
        if (g == 2) return o.dmg > 0 && o.ourLost <= o.theirLost;
        return o.dmg > 0 || o.theirLost > o.ourLost;
    }

    /** the best plan: G = the tier to apply (0: no criterion, maximise damage only) */
    static Choice best(List<Att> as, List<Blk> bs, List<S> sp, int mana, long life, int g, boolean strict) {
        Choice best = null;
        for (int mask = 0; mask < (1 << sp.size()); mask++) {
            List<S> set = new ArrayList<>(); int cost = 0;
            for (int i = 0; i < sp.size(); i++) if ((mask & (1 << i)) != 0) { set.add(sp.get(i)); cost += sp.get(i).cost; }
            if (cost > mana) continue;
            List<S> rem = new ArrayList<>(), rides = new ArrayList<>();
            for (S s : set) if (s.removal) rem.add(s); else if (s.ride) rides.add(s);
            if (!rides.isEmpty() && as.isEmpty()) continue;
            List<List<Blk>> assigns = new ArrayList<>();
            assign(rem, 0, new ArrayList<>(), bs, assigns, strict);
            int k = set.size(), kpre = 0;
            for (S s : set) if (preBlock(s)) kpre++;
            int rc = 1; for (int q = 0; q < rides.size(); q++) rc *= Math.max(1, as.size());
            for (List<Blk> tg : assigns) {
                List<Blk> left = new ArrayList<>(bs); left.removeAll(tg);
                for (int c = 0; c < Math.min(rc, 64); c++) {
                    long[] extra = new long[as.size()]; int[] rt = new int[rides.size()]; int r = c; boolean ok = true;
                    for (int q = 0; q < rides.size(); q++) {
                        rt[q] = r % as.size(); r /= as.size();
                        if (strict && !rides.get(q).sa.canTarget(as.get(rt[q]).c)) ok = false;
                        extra[rt[q]] += 3;
                    }
                    if (!ok) continue;
                    // instants after blocks when the plan still holds; else everything before blocks
                    Out split = response(as, left, kpre, k, extra, life), all = null, out = split;
                    boolean hold = kpre < k;
                    if (g > 0 && !meets(g, split) && hold) { all = response(as, left, k, k, extra, life); out = all; hold = false; }
                    if (g > 0 && !meets(g, out)) continue;
                    double v = out.dmg + W * (out.theirLost - out.ourLost);
                    if (best == null || v > best.value || v == best.value && set.size() < best.set.size()) {
                        best = new Choice(); best.set = set; best.out = out; best.hold = hold; best.value = v;
                        int ri = 0, bi = 0;
                        for (S s : set) best.tg.add(s.removal ? tg.get(bi++) : s.ride ? as.get(rt[ri++]) : null);
                    }
                }
            }
        }
        return best;
    }

    static void assign(List<S> rem, int i, List<Blk> cur, List<Blk> bs, List<List<Blk>> out, boolean strict) {
        if (out.size() > 200) return;
        if (i == rem.size()) { out.add(new ArrayList<>(cur)); return; }
        S s = rem.get(i);
        for (Blk b : bs) {
            if (cur.contains(b) || b.tough > s.dmg || strict && !s.sa.canTarget(b.c)) continue;
            cur.add(b); assign(rem, i + 1, cur, bs, out, strict); cur.remove(cur.size() - 1);
        }
    }

    /** main 1, no lethal line: the plan's next pre-block spell (null: none, or no plan) */
    static SpellAbility main1(Player me, Player op) {
        int t = tier(me);
        List<Card> can = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) if (ProwessBrain.canAttackNow(c)) can.add(c);
        List<Att> as = atts(can);
        int g = (int) as.stream().filter(a -> a.grower).count();
        if (g == 0 || Math.min(g, 3) > t) return null;
        Choice ch = best(as, blks(op, null), spells(me, true), ProwessBrain.surplus(me), op.getLife(), Math.min(g, 3), true);
        if (ch == null) { ControlBrain.count("prowessdmg_hold"); return null; }
        for (Att a : as) ProwessBrain.plan(me, a.c);   // the attack happens
        ControlBrain.count("prowessdmg_attack_g" + Math.min(g, 3));
        S next = null; Object nt = null;
        for (int i = 0; i < ch.set.size(); i++) {
            S s = ch.set.get(i);
            if (ch.hold && !preBlock(s)) continue;
            int rank = s.removal ? 0 : s.ride ? 1 : 2;
            int nrank = next == null ? 9 : next.removal ? 0 : next.ride ? 1 : 2;
            if (rank < nrank) { next = s; nt = ch.tg.get(i); }
        }
        if (next == null) return null;
        next.sa.resetTargets();
        if (nt instanceof Blk) next.sa.getTargets().add(((Blk) nt).c);
        else if (nt instanceof Att) next.sa.getTargets().add(((Att) nt).c);
        ControlBrain.count(next.removal ? "prowessdmg_removal" : next.ride ? "prowessdmg_ride" : "prowessdmg_precombat_spell");
        return next.sa;
    }

    /** after blocks on our attack: held instant cantrips while one of our growers is attacking */
    static SpellAbility afterBlocks(Player me) {
        Combat cb = me.getGame().getCombat();
        if (cb == null) return null;
        boolean grower = false;
        for (Card a : cb.getAttackers()) if (a.getController() == me && ProwessBrain.perSpell(a) > 0) grower = true;
        if (!grower) return null;
        for (SpellAbility sa : ProwessBrain.castable(me, c -> c.isInstant(), ProwessBrain.surplus(me)))
            if (ProwessBrain.held(me, sa)) { ControlBrain.count("prowessdmg_after_blocks"); return sa; }
        return null;
    }

    /** the opponent's end step: Burst a blocker now only under the two exceptions */
    static SpellAbility endStep(Player me, Player op) {
        List<SpellAbility> bursts = ProwessBrain.castable(me, c -> c.getName().equals(ProwessBrain.BURST), ProwessBrain.mana(me));
        if (bursts.isEmpty()) return null;
        List<Card> next = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) if (!c.hasKeyword(Keyword.DEFENDER)) next.add(c);
        List<Att> as = atts(next);
        if (as.stream().noneMatch(a -> a.grower)) return null;
        int mana = (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && !c.getManaAbilities().isEmpty()).count()
                + (me.getCardsIn(ZoneType.Hand).stream().anyMatch(Card::isLand) ? 1 : 0);
        long life = op.getLife();
        List<S> spA = spells(me, false);
        Choice a = best(as, blks(op, null), spA, mana, life, 0, false);
        long dA = a == null ? 0 : a.out.dmg;
        long threat = 0;
        for (Card c : op.getCreaturesInPlay()) threat += Math.max(0, c.getNetPower());
        boolean dying = threat >= me.getLife();
        for (SpellAbility sa : bursts)
            for (Card b : op.getCreaturesInPlay()) {
                if (!b.isUntapped() || b.getNetToughness() - b.getDamage() > 2 || !sa.canTarget(b)) continue;
                List<S> spB = new ArrayList<>();
                boolean dropped = false;
                for (S s : spA) { if (!dropped && s.card == sa.getHostCard()) { dropped = true; continue; } spB.add(s); }
                Choice bc = best(as, blks(op, b), spB, mana, life, 0, false);
                long dB = bc == null ? 0 : bc.out.dmg;
                if (dB >= life && dA < life || dying && dB > dA) {
                    ControlBrain.count(dB >= life && dA < life ? "prowessdmg_eot_lethal" : "prowessdmg_eot_time");
                    sa.resetTargets(); sa.getTargets().add(b);
                    return sa;
                }
            }
        return null;
    }
}
