import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/**
 * Izzet Spellementals pilot rules (2026-10-23, from pilot/reports/sp-stock-10-report.md: Forge's stock AI made a clear
 * misplay in 8 of 10 games). Each rule is a separate tweak, scoped "rule@NAME" (AiTweaks.on):
 *
 * spellplan1 — Sunderflock before combat: in our main 1 with an empty stack, if Sunderflock is castable, the opponent
 *   controls a creature that is not an Elemental, and we control an untapped creature that can attack, cast Sunderflock
 *   now (its enter trigger bounces every non-Elemental; then Forge attacks into the emptied board). If Sunderflock is
 *   not castable yet but Eddymurk Crab is and our untapped sources cover the Crab plus 2 more (Sunderflock then costs
 *   9 - 7 = UU), and the opponent has 2+ non-Elemental creatures, cast the Crab first.
 * spellplan2 — Eddymurk Crab on the opponent's turn: at the opponent's beginning of combat, if it has a creature that can
 *   attack, cast a castable Crab (its enter trigger taps two creatures; Forge picks the targets); else at the
 *   opponent's end step. In our own main phase Forge's Crab is vetoed unless we have a creature that can attack this
 *   turn and the opponent an untapped creature (main 1), or the Crab enables a castable Sunderflock this turn.
 * spellplan3 — Opt at the end of the opponent's turn: Forge's Opt is vetoed in upkeeps, draw steps and the opponent's
 *   main 1 (it waits for the opponent's end step), and whenever it would leave no untapped blue source while a
 *   counterspell (Spell Snare, Spell Pierce, Get Out) is in hand on the opponent's turn. At the opponent's end step a
 *   castable Opt is cast.
 * spellplan4 — Crab for lethal (2026-10-25, pilot/reports/sp12-10-report.md: 105 T14 missed lethal, 102 T13 main-phase
 *   Crab with no attack): in our main 1 with an empty stack, cast a castable Crab if tapping the opponent's two best
 *   untapped creatures leaves a lethal attack (worst-case blocks by the rest, no trample counted); then, if the attack
 *   is really lethal at declare-attackers (whatever the Crab tapped), attack with everything. With spellplan2 on, its
 *   main-phase exception "helps an attack" becomes this lethal check (the Sunderflock exception stays).
 * spellplan5 — removal at the opponent's end step (same report: 100 T10, 103 T11, 107 T15): Forge's Traumatic Critique,
 *   Sear or Burst Lightning in our own upkeep or draw step with an empty stack is vetoed when it would leave fewer
 *   untapped sources than a Crab or Sunderflock in hand costs; at the opponent's end step, one of them is cast if
 *   Forge's AI would play it there (its own target and X).
 */
public class SpellBrain {
    static final String SUNDER = "Sunderflock", CRAB = "Eddymurk Crab", OPT = "Opt";

    static boolean on1(Player p) { return AiTweaks.on("spellplan1", p); }
    static boolean on2(Player p) { return AiTweaks.on("spellplan2", p); }
    static boolean on3(Player p) { return AiTweaks.on("spellplan3", p); }
    static boolean on4(Player p) { return AiTweaks.on("spellplan4", p); }
    static boolean on5(Player p) { return AiTweaks.on("spellplan5", p); }
    static boolean any(Player p) { return on1(p) || on2(p) || on3(p) || on4(p) || on5(p); }
    static final java.util.Set<String> REMOVAL = java.util.Set.of("Traumatic Critique", "Sear", "Burst Lightning");

    /** worst-case unblocked damage of an all-out attack: each untapped opponent creature not in TAPPED blocks one of
     *  our attackers it can block, biggest attackers first (greedy); trample is not counted */
    static long unblocked(Player me, Player op, java.util.Collection<Card> tapped) {
        List<Card> att = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) if (LandfallBrain.canAttackThisTurn(c) && c.getNetPower() > 0) att.add(c);
        att.sort((x, y) -> Integer.compare(y.getNetPower(), x.getNetPower()));
        List<Card> bl = new ArrayList<>();
        for (Card b : op.getCreaturesInPlay()) if (b.isUntapped() && !tapped.contains(b)) bl.add(b);
        long d = 0;
        for (Card a : att) {
            Card used = null;
            for (Card b : bl) {
                boolean can;
                try { can = forge.game.combat.CombatUtil.canBlock(a, b); } catch (Exception e) { can = true; }
                if (can) { used = b; break; }
            }
            if (used != null) bl.remove(used); else d += a.getNetPower();
        }
        return d;
    }

    /** the opponent's two best untapped creatures (the Crab's likely targets) */
    static List<Card> twoBest(Player op) {
        List<Card> u = new ArrayList<>();
        for (Card b : op.getCreaturesInPlay()) if (b.isUntapped()) u.add(b);
        u.sort((x, y) -> Integer.compare(forge.ai.ComputerUtilCard.evaluateCreature(y), forge.ai.ComputerUtilCard.evaluateCreature(x)));
        return u.subList(0, Math.min(2, u.size()));
    }

    static boolean crabLethal(Player me, Player op) {
        return unblocked(me, op, twoBest(op)) >= op.getLife();
    }

    static Game lethalGame; static int lethalTurn = -1;

    /** spellplan4 at declare-attackers: after a lethal Crab this turn, attack with everything if it is lethal now */
    static void forceAttack(Player me, forge.game.combat.Combat combat) {
        Game g = me.getGame();
        if (!on4(me) || lethalGame != g || lethalTurn != g.getPhaseHandler().getTurn()) return;
        Player op = ProwessBrain.opponent(me);
        if (op == null || unblocked(me, op, java.util.List.of()) < op.getLife()) { ControlBrain.count("spellplan4_not_lethal_after_crab"); return; }
        for (Card c : me.getCreaturesInPlay())
            if (!combat.isAttacking(c) && forge.game.combat.CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("spellplan4_forced_attacker");
            }
    }

    static int cheapestElemental(Player me) {
        int best = Integer.MAX_VALUE;
        for (String n : new String[] {CRAB, SUNDER})
            for (SpellAbility sa : LandfallBrain.castable(me, ZoneType.Hand, n))
                best = Math.min(best, sa.getPayCosts().getTotalMana().getCMC());
        return best;
    }

    static boolean elemental(Card c) { return c.getType().hasCreatureType("Elemental"); }

    static int nonElementals(Player op) {
        int n = 0;
        for (Card c : op.getCreaturesInPlay()) if (!elemental(c)) n++;
        return n;
    }

    static SpellAbility first(Player me, String name) {
        for (SpellAbility sa : LandfallBrain.castable(me, ZoneType.Hand, name)) return sa;
        return null;
    }

    static boolean ourAttacker(Player me) {
        for (Card c : me.getCreaturesInPlay()) if (LandfallBrain.canAttackThisTurn(c)) return true;
        return false;
    }

    /** our own choices, before Forge's */
    static SpellAbility choose(Player me) {
        if (!any(me)) return null;
        Game g = me.getGame();
        if (!g.getStack().isEmpty()) return null;
        Player op = ProwessBrain.opponent(me);
        if (op == null) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        boolean ours = g.getPhaseHandler().isPlayerTurn(me);
        if (on1(me) && ours && ph == PhaseType.MAIN1 && nonElementals(op) > 0 && ourAttacker(me)) {
            SpellAbility s = first(me, SUNDER);
            if (s != null) { ControlBrain.count("spellplan1_sunderflock_precombat"); return s; }
            boolean holdsSunder = false;
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (LandfallBrain.named(c, SUNDER)) holdsSunder = true;
            SpellAbility crab = first(me, CRAB);
            if (holdsSunder && crab != null && nonElementals(op) >= 2
                    && LandfallBrain.untappedSources(me) >= crab.getPayCosts().getTotalMana().getCMC() + 2) {
                ControlBrain.count("spellplan1_crab_for_sunderflock");
                return crab;
            }
        }
        if (on2(me) && !ours) {
            boolean attackers = false;
            for (Card c : op.getCreaturesInPlay()) if (LandfallBrain.canAttackThisTurn(c) && c.getNetPower() > 0) attackers = true;
            if ((ph == PhaseType.COMBAT_BEGIN && attackers) || ph == PhaseType.END_OF_TURN) {
                SpellAbility crab = first(me, CRAB);
                if (crab != null) { ControlBrain.count(ph == PhaseType.END_OF_TURN ? "spellplan2_crab_eot" : "spellplan2_crab_combat"); return crab; }
            }
        }
        if (on4(me) && ours && ph == PhaseType.MAIN1) {
            SpellAbility crab = first(me, CRAB);
            if (crab != null && crabLethal(me, op)) {
                lethalGame = g; lethalTurn = g.getPhaseHandler().getTurn();
                ControlBrain.count("spellplan4_crab_lethal");
                return crab;
            }
        }
        if (on5(me) && !ours && ph == PhaseType.END_OF_TURN) {
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (!REMOVAL.contains(c.getName())) continue;
                for (SpellAbility sa : LandfallBrain.castable(me, ZoneType.Hand, c.getName())) {
                    try {
                        forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
                        if (ai.canPlaySa(sa) == forge.ai.AiPlayDecision.WillPlay) { ControlBrain.count("spellplan5_removal_eot"); return sa; }
                    } catch (Exception e) { continue; }
                }
            }
        }
        if (on3(me) && !ours && ph == PhaseType.END_OF_TURN) {
            SpellAbility o = first(me, OPT);
            if (o != null) { ControlBrain.count("spellplan3_opt_eot"); return o; }
        }
        return null;
    }

    static int blueSources(Player me) {
        int n = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isLand() && c.isUntapped() && (c.getType().hasSubtype("Island") || c.getName().matches("Steam Vents|Spirebluff Canal|Riverpyre Verge|Stormcarved Coast|Thundering Falls")))
                n++;
        return n;
    }

    static boolean counterInHand(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (c.getName().equals("Spell Snare") || c.getName().equals("Spell Pierce") || c.getName().equals("Get Out")) return true;
        return false;
    }

    /** vetoes of Forge's choice */
    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty() || !any(me)) return ai;
        SpellAbility pick = ai.get(0);
        Card h = pick.getHostCard();
        if (h == null || !pick.isSpell()) return ai;
        Game g = me.getGame();
        PhaseType ph = g.getPhaseHandler().getPhase();
        boolean ours = g.getPhaseHandler().isPlayerTurn(me);
        Player op = ProwessBrain.opponent(me);
        if (on2(me) && ours && LandfallBrain.named(h, CRAB) && op != null) {
            boolean oppUntapped = false;
            for (Card c : op.getCreaturesInPlay()) if (c.isUntapped()) oppUntapped = true;
            boolean helpsAttack = on4(me) ? ph == PhaseType.MAIN1 && crabLethal(me, op)
                    : ph == PhaseType.MAIN1 && ourAttacker(me) && oppUntapped;
            boolean enablesSunder = false;
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (LandfallBrain.named(c, SUNDER)) enablesSunder = true;
            enablesSunder = enablesSunder && LandfallBrain.untappedSources(me) >= pick.getPayCosts().getTotalMana().getCMC() + 2;
            if (!helpsAttack && !enablesSunder) { ControlBrain.count("spellplan2_crab_held"); return null; }
        }
        if (on5(me) && ours && REMOVAL.contains(h.getName()) && g.getStack().isEmpty()
                && (ph == PhaseType.UPKEEP || ph == PhaseType.DRAW)) {
            int need = cheapestElemental(me);
            if (need != Integer.MAX_VALUE && LandfallBrain.untappedSources(me) - pick.getPayCosts().getTotalMana().getCMC() < need) {
                ControlBrain.count("spellplan5_removal_held"); return null;
            }
        }
        if (on3(me) && LandfallBrain.named(h, OPT)) {
            boolean badStep = ph == PhaseType.UPKEEP || ph == PhaseType.DRAW || (!ours && ph == PhaseType.MAIN1);
            boolean lastBlue = !ours && counterInHand(me) && blueSources(me) <= 1;
            if (badStep || lastBlue) { ControlBrain.count(badStep ? "spellplan3_opt_held" : "spellplan3_opt_keeps_counter_mana"); return null; }
        }
        return ai;
    }
}
