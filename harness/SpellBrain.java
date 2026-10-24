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
 */
public class SpellBrain {
    static final String SUNDER = "Sunderflock", CRAB = "Eddymurk Crab", OPT = "Opt";

    static boolean on1(Player p) { return AiTweaks.on("spellplan1", p); }
    static boolean on2(Player p) { return AiTweaks.on("spellplan2", p); }
    static boolean on3(Player p) { return AiTweaks.on("spellplan3", p); }
    static boolean any(Player p) { return on1(p) || on2(p) || on3(p); }

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
            boolean helpsAttack = ph == PhaseType.MAIN1 && ourAttacker(me) && oppUntapped;
            boolean enablesSunder = false;
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (LandfallBrain.named(c, SUNDER)) enablesSunder = true;
            enablesSunder = enablesSunder && LandfallBrain.untappedSources(me) >= pick.getPayCosts().getTotalMana().getCMC() + 2;
            if (!helpsAttack && !enablesSunder) { ControlBrain.count("spellplan2_crab_held"); return null; }
        }
        if (on3(me) && LandfallBrain.named(h, OPT)) {
            boolean badStep = ph == PhaseType.UPKEEP || ph == PhaseType.DRAW || (!ours && ph == PhaseType.MAIN1);
            boolean lastBlue = !ours && counterInHand(me) && blueSources(me) <= 1;
            if (badStep || lastBlue) { ControlBrain.count(badStep ? "spellplan3_opt_held" : "spellplan3_opt_keeps_counter_mana"); return null; }
        }
        return ai;
    }
}
