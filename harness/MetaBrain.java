import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.List;

/**
 * Rules for the stock meta decks (pilot/TUNE_TARGETS.md, from scan ad-s10 of Azorius Control vs Dimir Midrange,
 * 2026-10-25). One switch per hypothesis, each scoped to its deck with @NAME:
 *   azoriushold@NAME   A1  Three Steps Ahead is held as a counter: cast without its counter mode only at the
 *                          opponent's end step (Forge cast it for the draw in its own main phase, tapping low, 7 times in
 *                          5 games, and the opponent resolved Jace, Kaito, Beavers into the gap)
 *   dimirbetrayal@NAME D1  no Strategic Betrayal while the opponent controls no creature (it then exiles only their
 *                          graveyard; every Betrayal in the scan was cast that way, 6 games)
 *   dimirlethal@NAME   D2  attack with everything when the all-out attack is lethal even if the opponent blocks as well
 *                          as it can (one game went 4 turns without an attack, the opponent at 2 and no blockers)
 * Only public information is used: the board, life totals and our own hand.
 */
public class MetaBrain {
    static final String THREE_STEPS = "Three Steps Ahead", BETRAYAL = "Strategic Betrayal";

    static boolean a1(Player p) { return AiTweaks.on("azoriushold", p); }
    static boolean d1(Player p) { return AiTweaks.on("dimirbetrayal", p); }
    static boolean d2(Player p) { return AiTweaks.on("dimirlethal", p); }
    static boolean any(Player p) { return a1(p) || d1(p) || d2(p); }

    /** the modes chosen for a modal spell (spree/charm), plus its sub-abilities */
    static boolean hasMode(SpellAbility sa, ApiType api) {
        if (sa.getChosenList() != null)
            for (SpellAbility m : sa.getChosenList()) if (m.getApi() == api) return true;
        for (SpellAbility s = sa.getSubAbility(); s != null; s = s.getSubAbility()) if (s.getApi() == api) return true;
        return false;
    }

    /** vetoes on Forge's chosen play (null: pass instead) */
    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty()) return ai;
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        for (SpellAbility sa : ai) {
            Card h = sa.getHostCard();
            if (h == null || h.getController() != me || !sa.isSpell()) continue;
            if (a1(me) && h.getName().equals(THREE_STEPS) && !hasMode(sa, ApiType.Counter)) {
                boolean oppEnd = !g.getPhaseHandler().isPlayerTurn(me) && g.getPhaseHandler().getPhase() == PhaseType.END_OF_TURN;
                if (!oppEnd) { ControlBrain.count("azoriushold_held"); return null; }
            }
            if (d1(me) && h.getName().equals(BETRAYAL) && op != null && op.getCreaturesInPlay().isEmpty()) {
                ControlBrain.count("dimirbetrayal_held");
                return null;
            }
        }
        return ai;
    }

    /** D2: after Forge declares attackers, add every creature that can attack if the all-out attack is lethal */
    static void forceAttack(Player me, Combat combat) {
        if (!d2(me)) return;
        Player op = ProwessBrain.opponent(me);
        if (op == null || SpellBrain.unblocked(me, op, java.util.Collections.emptyList()) < op.getLife()) return;
        for (Card c : me.getCreaturesInPlay())
            if (!combat.isAttacking(c) && LandfallBrain.canAttackThisTurn(c) && CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("dimirlethal_attacker");
            }
    }
}
