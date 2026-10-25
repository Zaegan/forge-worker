import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tweak prowessplan (user, 2026-10-12): a plan for Izzet Prowess (Slickshot Show-Off, Elusive Otter, Drake Hatcher,
 * Gandalf, Goblins' Bane; cheap cantrips and burn; Eddymurk Crab), which Forge's AI pilots at 14.6% vs the field.
 *
 * Every turn, in our main phase 1: CAN WE WIN THIS TURN? The search tries every affordable set of our cheap
 * noncreature spells, each burn spell aimed at the opponent or at a blocker it kills, with or without Eddymurk Crab
 * (cheaper by one for every instant/sorcery in the graveyard, so cast after the spells; its ETB taps two blockers).
 * Each spell pumps every attacker (prowess +1, Slickshot +2, Gandalf +1, doubled by Wizard's Staff) and Gandalf deals 1
 * to the opponent. The opponent blocks in the way that stops the most damage (any untapped creature that can legally
 * block; Elusive Otter can't be blocked by smaller creatures). If a line is lethal, its next step is cast (the search
 * runs again after each spell resolves) and every attacker is made to attack.
 *
 * Otherwise SET-UP: on our turn, instant cantrips are held for the opponent's end step (prowess on their turn is
 * worthless, but so is a cantrip cast now with no attack to pump; at their end step it also sees one more turn), burn
 * is never aimed at the opponent's face, and Eddymurk Crab is kept for the kill turn. Creatures, sorcery cantrips and
 * burn on creatures stay Forge's own choices. At the opponent's end step, held instant cantrips are cast.
 */
public class ProwessBrain {
    static final String SHOWOFF = "Slickshot Show-Off", GANDALF = "Gandalf, Goblins' Bane", HATCHER = "Drake Hatcher",
            CRAB = "Eddymurk Crab", STAFF = "Wizard's Staff", BURST = "Burst Lightning", SEAR = "Sear",
            LEYLINE = "Leyline of Resonance";

    /** "prowessplan" (every player) or "prowessplan@NAME" (only the deck named *NAME*: AiTweaks.on) */
    static boolean ON(Player me) { return AiTweaks.on("prowessplan", me) || V2(me) || V3(me); }
    /** prowessplan3 (2026-10-25, scan pz-s10 of Prowess vs Spellementals): prowessplan, plus (a) a held "instant cantrip"
     *  is one that draws or digs (Secret Identity, a charm whose modes only target, was held and cast at the opponent's
     *  end step with nothing to target: 2 of 10 games), and (b) no "discard your hand" spell (Stoke Genius, Hearth
     *  Elemental's adventure) that throws away 3+ other cards for its 2 (Forge's AI did it in 4 of 10 games per seat). */
    static boolean V3(Player me) { return AiTweaks.on("prowessplan3", me); }
    /* One switch per hypothesis (pilot/TUNE_TARGETS.md, 2026-10-25: tested one at a time). Each is an add-on to prowessplan
     * (or prowessplan2/3) and acts only with one of them on:
     *   prowessstoke@NAME   P1  no "discard your hand" spell (Stoke Genius) discarding 3+ other cards
     *   prowesscantrip@NAME P2  a held "instant cantrip" must draw/dig (not Secret Identity)
     *   prowesshaste@NAME   P4  no lethal line: cast a castable haste creature in main 1 when no untapped opposing
     *                           creature can block it
     *   prowessdefend@NAME  P5  opponent's declare-blockers step: if the unblocked attackers would kill us, remove one
     *                           with Unsummon or Burst Lightning (kicked or not, several if needed) so we survive
     * P3 is prowessplan2 (below). */
    static boolean P1(Player me) { return V3(me) || AiTweaks.on("prowessstoke", me); }
    static boolean P2(Player me) { return V3(me) || AiTweaks.on("prowesscantrip", me); }
    static boolean P4(Player me) { return AiTweaks.on("prowesshaste", me); }
    static boolean P5(Player me) { return AiTweaks.on("prowessdefend", me); }

    /** the spell (as chosen) draws, digs, scries or surveils */
    static boolean cantrip(SpellAbility sa) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            ApiType a = x.getApi();
            if (a == ApiType.Draw || a == ApiType.Dig || a == ApiType.Scry || a == ApiType.Surveil) return true;
        }
        return false;
    }

    /** a held instant: prowessplan's test (no targets), and with prowessplan3 it must also draw */
    static boolean held(Player me, SpellAbility sa) {
        return !AiTweaks.targets(sa) && (!P2(me) || cantrip(sa));
    }

    /** prowessplan3 (b): "discard your hand, then draw" that discards 3+ other cards */
    static boolean dumpsHand(Player me, SpellAbility sa) {
        if (!P1(me) || sa.getApi() != ApiType.Discard || !"Hand".equals(sa.getParam("Mode"))) return false;
        int other = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c != sa.getHostCard()) other++;
        return other >= 3;
    }
    /** prowessplan2 (2026-10-23, from the agent games vs the meta Izzet Prowess list): prowessplan, plus Unsummon on a
     *  blocker and Wild Ride (+3/+0 on one attacker) in the lethal search */
    static boolean V2(Player me) { return AiTweaks.on("prowessplan2", me); }
    static final String UNSUMMON = "Unsummon", WILDRIDE = "Wild Ride";

    static Game deckGame; static Player deckFor; static boolean deckIs;

    static boolean deck(Player me) {
        if (deckGame != me.getGame() || deckFor != me) {
            boolean show = false, other = false, ley = false;
            for (Card c : me.getAllCards()) {
                String n = c.getName();
                if (n.equals(SHOWOFF)) show = true;
                if (n.equals(GANDALF) || n.equals(HATCHER)) other = true;
                if (n.equals(LEYLINE)) ley = true;
            }
            deckIs = (other || show) && !ley;
            deckGame = me.getGame(); deckFor = me;
        }
        return deckIs;
    }

    static Player opponent(Player me) {
        for (Player p : me.getGame().getPlayers()) if (p != me) return p;
        return null;
    }

    static int mana(Player me) {
        return (int) me.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.isLand() && c.isUntapped() && !c.getManaAbilities().isEmpty()).count();
    }

    static int triggers(Card c) {
        int t = 1;
        for (Card a : c.getAttachedCards()) if (a.getName().equals(STAFF)) t = 2;
        return t;
    }

    /** power one noncreature spell adds to creature C this turn */
    static int perSpell(Card c) {
        int p = 0;
        if (c.hasKeyword(Keyword.PROWESS)) p += 1;
        if (c.getName().equals(SHOWOFF)) p += 2;
        if (c.getName().equals(GANDALF)) p += 1;
        return p * triggers(c);
    }

    static int pingPerSpell(Player me) {
        int d = 0;
        for (Card c : me.getCreaturesInPlay()) if (c.getName().equals(GANDALF)) d += triggers(c);
        return d;
    }

    static boolean canAttackNow(Card c) {
        return c.isUntapped() && (!c.isSick() || c.hasKeyword(Keyword.HASTE)) && CombatUtil.canAttack(c);
    }

    static boolean canBlock(Card a, long aPower, Card b) {
        boolean can;
        try { can = CombatUtil.canBlock(a, b); } catch (Exception e) { can = true; }
        if (!can) return false;
        if (a.getName().equals("Elusive Otter") && b.getNetPower() < aPower) return false;
        return true;
    }

    /** one castable spell of ours that the search may use */
    static class Sp {
        SpellAbility sa; int cost; int dmg; boolean face, creature; int pump;   // pump: +power on one of our attackers
        String name() { return sa.getHostCard().getName(); }
    }

    static List<Sp> spells(Player me) {
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if ((c.isInstant() || c.isSorcery()) && !c.isCreature()) hand.add(c);
        List<Sp> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell() || sa.isAdventure() || !seen.add(sa.getHostCard().getId())) continue;
            sa.setActivatingPlayer(me);
            try {
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
            } catch (Exception e) {
                continue;
            }
            Sp s = new Sp(); s.sa = sa; s.cost = sa.getHostCard().getCMC();
            String n = sa.getHostCard().getName();
            if (n.equals(BURST)) { s.dmg = 2; s.face = true; s.creature = true; }
            else if (n.equals(SEAR)) { s.dmg = 4; s.creature = true; }
            else if (V2(me) && n.equals(UNSUMMON)) { s.dmg = 99; s.creature = true; }   // bounces any blocker
            else if (V2(me) && n.equals(WILDRIDE)) { s.pump = 3; }
            else if (AiTweaks.targets(sa)) continue;   // other targeted spells (Spell Pierce, Heroes' Hangout): not in the search
            out.add(s);
        }
        return out;
    }

    static int gyInstSorc(Player me) {
        return (int) me.getCardsIn(ZoneType.Graveyard).stream().filter(c -> c.isInstant() || c.isSorcery()).count();
    }

    static SpellAbility crabSa(Player me) {
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.getName().equals(CRAB)) { hand.add(c); break; }
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me))
            if (sa.isSpell()) { sa.setActivatingPlayer(me); return sa; }
        return null;
    }

    /** a lethal line: the spells (with targets), whether Crab is cast, and the damage it deals in the worst case */
    static class Line {
        List<Sp> spells = new ArrayList<>(); List<GameObject> targets = new ArrayList<>(); boolean crab; int cost; long dmg;
    }

    /** worst-case damage of a line: the opponent blocks to stop the most damage */
    static long damage(Player me, List<Card> attackers, List<Card> blockers, int k, int burnFace, int crabTaps) {
        return damage(me, attackers, blockers, k, burnFace, crabTaps, new long[attackers.size()]);
    }

    static long damage(Player me, List<Card> attackers, List<Card> blockers, int k, int burnFace, int crabTaps, long[] extra) {
        List<long[]> att = new ArrayList<>();   // [index, power]
        for (int i = 0; i < attackers.size(); i++)
            att.add(new long[]{i, Math.max(0, attackers.get(i).getNetPower() + (long) k * perSpell(attackers.get(i)) + extra[i])});
        att.sort((x, y) -> Long.compare(y[1], x[1]));
        // Crab taps the two blockers that could stop the most damage: approximated by the blockers able to block the
        // most of our attackers
        List<Card> bl = new ArrayList<>(blockers);
        if (crabTaps > 0) {
            bl.sort((x, y) -> Integer.compare(blockable(y, attackers, k), blockable(x, attackers, k)));
            bl = bl.subList(Math.min(crabTaps, bl.size()), bl.size());
        }
        boolean[] used = new boolean[bl.size()];
        long dmg = 0;
        for (long[] a : att) {
            Card c = attackers.get((int) a[0]);
            boolean blocked = false;
            for (int j = 0; j < bl.size() && !blocked; j++)
                if (!used[j] && canBlock(c, a[1], bl.get(j))) { used[j] = true; blocked = true; }
            if (!blocked) dmg += a[1];
        }
        return dmg + (long) k * pingPerSpell(me) + burnFace;
    }

    static int blockable(Card b, List<Card> attackers, int k) {
        int n = 0;
        for (Card a : attackers) if (canBlock(a, a.getNetPower() + (long) k * perSpell(a), b)) n++;
        return n;
    }

    static Line lethal(Player me, Player op) {
        List<Card> attackers = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) if (canAttackNow(c)) attackers.add(c);
        List<Card> blockers = new ArrayList<>();
        for (Card c : op.getCreaturesInPlay()) if (c.isUntapped()) blockers.add(c);
        List<Sp> sp = spells(me);
        if (sp.size() > 7) sp = sp.subList(0, 7);
        int mana = mana(me), life = op.getLife(), gy = gyInstSorc(me);
        SpellAbility crab = crabSa(me);
        Line best = null;
        for (int mask = 0; mask < (1 << sp.size()); mask++) {
            List<Sp> set = new ArrayList<>();
            int cost = 0;
            for (int i = 0; i < sp.size(); i++) if ((mask & (1 << i)) != 0) { set.add(sp.get(i)); cost += sp.get(i).cost; }
            if (cost > mana) continue;
            List<Sp> burns = new ArrayList<>();
            for (Sp s : set) if (s.dmg > 0) burns.add(s);
            // each burn: face (if it can) or one blocker it kills; small enumeration
            List<List<GameObject>> assigns = new ArrayList<>();
            assign(burns, 0, new ArrayList<>(), blockers, op, assigns);
            for (int crabUse = 0; crabUse <= (crab != null && attackers.size() > 0 ? 1 : 0); crabUse++) {
                int crabCost = crabUse == 1 ? Math.max(2, 7 - gy - set.size()) : 0;
                if (cost + crabCost > mana) continue;
                for (List<GameObject> tg : assigns) {
                    int face = 0;
                    List<Card> left = new ArrayList<>(blockers);
                    for (int i = 0; i < burns.size(); i++) {
                        if (tg.get(i) == op) face += burns.get(i).dmg;
                        else left.remove((Card) tg.get(i));
                    }
                    List<Sp> pumps = new ArrayList<>();
                    for (Sp s : set) if (s.pump > 0) pumps.add(s);
                    if (!pumps.isEmpty() && attackers.isEmpty()) continue;
                    int combos = 1;
                    for (int q = 0; q < pumps.size(); q++) combos *= attackers.size();
                    long dmg = -1; int bestCombo = 0;
                    for (int cmb = 0; cmb < Math.min(combos, 500); cmb++) {
                        long[] extra = new long[attackers.size()];
                        int r = cmb;
                        for (Sp s : pumps) { extra[r % attackers.size()] += s.pump; r /= attackers.size(); }
                        long d = damage(me, attackers, left, set.size(), face, crabUse * 2, extra);
                        if (d > dmg) { dmg = d; bestCombo = cmb; }
                    }
                    if (dmg < life) continue;
                    int total = cost + crabCost;
                    if (best == null || total < best.cost || (total == best.cost && set.size() < best.spells.size())) {
                        best = new Line(); best.spells = set; best.crab = crabUse == 1; best.cost = total; best.dmg = dmg;
                        best.targets = new ArrayList<>();
                        int bi = 0;
                        int r = bestCombo;
                        for (Sp s : set) {
                            if (s.dmg > 0) best.targets.add(tg.get(bi++));
                            else if (s.pump > 0) { best.targets.add(attackers.get(r % attackers.size())); r /= attackers.size(); }
                            else best.targets.add(null);
                        }
                    }
                }
            }
        }
        return best;
    }

    static void assign(List<Sp> burns, int i, List<GameObject> cur, List<Card> blockers, Player op, List<List<GameObject>> out) {
        if (out.size() > 200) return;
        if (i == burns.size()) { out.add(new ArrayList<>(cur)); return; }
        Sp s = burns.get(i);
        if (s.face && s.sa.canTarget(op)) { cur.add(op); assign(burns, i + 1, cur, blockers, op, out); cur.remove(cur.size() - 1); }
        for (Card b : blockers) {
            if (cur.contains(b) || b.getNetToughness() - b.getDamage() > s.dmg || !s.sa.canTarget(b)) continue;
            cur.add(b); assign(burns, i + 1, cur, blockers, op, out); cur.remove(cur.size() - 1);
        }
    }

    static final Set<Card> planned = new HashSet<>();
    static Game plannedGame; static int plannedTurn;

    static SpellAbility choose(Player me) {
        Game g = me.getGame();
        if (!ON(me) || !g.getStack().isEmpty() || !deck(me)) return null;
        Player op = opponent(me);
        if (op == null) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (!g.getPhaseHandler().isPlayerTurn(me)) {
            if (ph == PhaseType.COMBAT_DECLARE_BLOCKERS && P5(me)) return guard("prowessdefend", () -> defend(me, op));
            return ph == PhaseType.END_OF_TURN ? endStep(me) : null;
        }
        if (ph != PhaseType.MAIN1) return null;
        Line l = lethal(me, op);
        if (l == null) return P4(me) ? guard("prowesshaste", () -> haste(me, op)) : null;
        // the attack happens: every creature that can attack does
        if (plannedGame != g || plannedTurn != g.getPhaseHandler().getTurn()) planned.clear();
        plannedGame = g; plannedTurn = g.getPhaseHandler().getTurn();
        for (Card c : me.getCreaturesInPlay()) if (canAttackNow(c)) planned.add(c);
        // next step: burn on blockers first, then the other spells (cheapest first), then Crab
        Sp next = null; GameObject nt = null;
        for (int i = 0; i < l.spells.size(); i++) {
            Sp s = l.spells.get(i); GameObject t = l.targets.get(i);
            boolean onBlocker = t instanceof Card && ((Card) t).getController() != me;
            boolean ntBlocker = nt instanceof Card && ((Card) nt).getController() != me;
            if (next == null || (onBlocker && !ntBlocker) || (onBlocker == ntBlocker && s.cost < next.cost)) {
                next = s; nt = t;
            }
        }
        if (next != null) {
            ControlBrain.count("prowessplan_lethal_spell");
            if (nt != null) { next.sa.resetTargets(); next.sa.getTargets().add(nt); }
            return next.sa;
        }
        if (l.crab) {
            SpellAbility c = crabSa(me);
            if (c != null) {
                try {
                    if (c.canPlay() && ComputerUtilCost.canPayCost(c, me, false)) {
                        ControlBrain.count("prowessplan_lethal_crab");
                        return c;
                    }
                } catch (Exception e) {
                    return null;
                }
            }
        }
        ControlBrain.count("prowessplan_lethal_attack");
        return null;   // lethal on board: attack (forceAttack)
    }

    /** a new rule's error is counted (RULE_error_TYPE) and the decision left to Forge, never a crashed game */
    static SpellAbility guard(String rule, java.util.function.Supplier<SpellAbility> f) {
        try {
            return f.get();
        } catch (RuntimeException e) {
            ControlBrain.count(rule + "_error_" + e.getClass().getSimpleName());
            return null;
        }
    }

    static SpellAbility playable(Player me, SpellAbility sa) {
        sa.setActivatingPlayer(me);
        try {
            return sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false) ? sa : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** P4: a haste creature that can attack unblocked this turn goes down before combat */
    static SpellAbility haste(Player me, Player op) {
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isCreature() && c.hasKeyword(Keyword.HASTE)) hand.add(c);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell()) continue;
            Card h = sa.getHostCard();
            boolean blockable = false;
            for (Card b : op.getCreaturesInPlay()) if (b.isUntapped() && canBlock(h, h.getNetPower(), b)) blockable = true;
            if (blockable || playable(me, sa) == null) continue;
            ControlBrain.count("prowesshaste_precombat");
            return sa;
        }
        return null;
    }

    /** P5: the unblocked attackers would kill us: remove one (the biggest whose removal saves us) with the cheapest set of
     *  our removal that can (Unsummon, or Burst Lightning kicked/unkicked); cast its biggest spell, the rule runs again
     *  after it resolves */
    static SpellAbility defend(Player me, Player op) {
        Game g = me.getGame();
        Combat combat = g.getCombat();
        if (combat == null || !g.getStack().isEmpty()) return null;
        List<Card> unblocked = new ArrayList<>();
        long dmg = 0;
        for (Card a : combat.getAttackers())
            if (combat.getDefenderPlayerByAttacker(a) == me && combat.isUnblocked(a)) { unblocked.add(a); dmg += Math.max(0, a.getNetPower()); }
        if (dmg < me.getLife()) return null;
        unblocked.sort((x, y) -> Integer.compare(y.getNetPower(), x.getNetPower()));
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.getName().equals(BURST) || c.getName().equals(UNSUMMON)) hand.add(c);
        List<SpellAbility[]> opts = new ArrayList<>();   // per card: {plain, kicked or null}
        List<Integer> dmgOf = new ArrayList<>();
        for (Card c : hand) {
            SpellAbility plain = null;
            for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(c), me)) if (sa.isSpell()) { plain = sa; break; }
            if (plain == null) continue;
            SpellAbility kicked = null;
            if (c.getName().equals(BURST)) {
                try {
                    List<forge.game.spellability.OptionalCostValue> v = forge.game.GameActionUtil.getOptionalCostValues(plain);
                    if (!v.isEmpty()) kicked = forge.game.GameActionUtil.addOptionalCosts(plain, v);
                } catch (Exception e) { kicked = null; }
            }
            opts.add(new SpellAbility[]{plain, kicked});
            dmgOf.add(c.getName().equals(UNSUMMON) ? 99 : 2);
        }
        int mana = mana(me), n = opts.size();
        if (n == 0) return null;
        for (Card a : unblocked) {
            if (dmg - Math.max(0, a.getNetPower()) >= me.getLife()) continue;   // removing it alone does not save us
            int need = a.getNetToughness() - a.getDamage();
            int bestCost = Integer.MAX_VALUE; SpellAbility bestFirst = null;
            int combos = 1; for (int i = 0; i < n; i++) combos *= 3;   // per card: unused, plain, kicked
            for (int m = 1; m < combos; m++) {
                int x = m, cost = 0, d = 0, bigD = -1; SpellAbility big = null; boolean ok = true;
                for (int i = 0; i < n && ok; i++, x /= 3) {
                    int pick = x % 3;
                    if (pick == 0) continue;
                    SpellAbility sa = opts.get(i)[pick - 1];
                    if (sa == null) { ok = false; break; }
                    int dd = pick == 2 ? 4 : dmgOf.get(i), cc = pick == 2 ? 5 : 1;
                    if (!sa.canTarget(a)) { ok = false; break; }
                    cost += cc; d += dd;
                    if (dd > bigD) { bigD = dd; big = sa; }
                }
                if (!ok || cost > mana || d < need || cost >= bestCost) continue;
                bestCost = cost; bestFirst = big;
            }
            if (bestFirst != null && playable(me, bestFirst) != null) {
                bestFirst.resetTargets(); bestFirst.getTargets().add(a);
                ControlBrain.count("prowessdefend_removal");
                return bestFirst;
            }
        }
        return null;
    }

    /** opponent's end step: cast held instant cantrips */
    static SpellAbility endStep(Player me) {
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isInstant() && !c.isCreature()) hand.add(c);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell() || !held(me, sa)) continue;
            sa.setActivatingPlayer(me);
            try {
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) {
                    ControlBrain.count("prowessplan_eot_cantrip");
                    return sa;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    /** set-up mode on our turn: veto instant cantrips (held for their end step), burn at the face, Eddymurk Crab */
    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (!ON(me) || ai == null || ai.isEmpty() || !deck(me)) return ai;
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me)) return ai;
        for (SpellAbility sa : ai) {
            Card h = sa.getHostCard();
            if (h == null || h.getController() != me || !sa.isSpell()) continue;
            if (h.getName().equals(CRAB)) { ControlBrain.count("prowessplan_hold_crab"); return null; }
            if (h.isInstant() && !h.isCreature() && held(me, sa)) { ControlBrain.count("prowessplan_hold_cantrip"); return null; }
            if (dumpsHand(me, sa)) { ControlBrain.count("prowessstoke_no_hand_dump"); return null; }
            if (h.getName().equals(BURST) && sa.getTargets() != null)
                for (Object t : sa.getTargets()) if (t instanceof Player) { ControlBrain.count("prowessplan_no_face_burn"); return null; }
        }
        return ai;
    }

    static void forceAttack(Player me, Combat combat) {
        Game g = me.getGame();
        if (!ON(me) || plannedGame != g || plannedTurn != g.getPhaseHandler().getTurn()) return;
        Player op = opponent(me);
        for (Card c : planned)
            if (c.isInPlay() && c.getController() == me && !combat.isAttacking(c) && CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("prowessplan_forced_attacker");
            }
    }
}
