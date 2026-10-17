import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Holding mana open for an instant, which Forge's AI never plans: on its own turn it spends what it likes, and an
 * instant gets cast only from whatever is left over.
 *
 * Tweak "manahold": at the first priority of our main phase 1 the plan decides, for each held instant in hand (one
 * entry for now: Ferocity of the Hunt), whether to reserve the lands that pay for it. Reserved lands go into Forge's
 * HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK memory, which the AI's payment code skips, so the AI spends only the rest. Forge
 * itself releases that set at the opponent's declare-blockers step (or their cleanup if they don't attack); ProtectBrain
 * releases it before casting the held spell in response to removal, as a combat trick, or, failing any of those, at the
 * opponent's end step (the scheduled cast; tweak "manahold" implies "oozeeot").
 *
 * Whether to hold: the spell must have something to do (a creature that is not indestructible to protect, or a Predator
 * Ooze for the combat trick), and holding must not cost the turn's biggest play: the most expensive creature castable
 * with the remaining mana may be at most one less in mana value than with all of it.
 */
public class ManaHold {
    static final String FEROCITY = "Ferocity of the Hunt";
    static Game plannedGame;
    static int plannedTurn = -1;
    static Player plannedFor;

    static boolean named(Card c, String n) {
        return c.getName().equals(n);
    }

    static List<Card> untappedLands(Player me) {
        List<Card> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isLand() && c.isUntapped() && !c.getManaAbilities().isEmpty()) out.add(c);
        return out;
    }

    static boolean produces(Card land, String colour) {
        for (SpellAbility ma : land.getManaAbilities()) {
            if (ma.getManaPart() != null && (ma.getManaPart().isAnyMana() || ma.canProduce(colour))) return true;
        }
        return false;
    }

    static int maxCastableCreature(Player me, int mana) {
        int best = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (c.isCreature() && c.getCMC() <= mana) best = Math.max(best, (int) c.getCMC());
        return best;
    }

    /** From chooseSpellAbilityToPlay, every priority; acts once, at our first priority of main phase 1. */
    static void plan(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1) return;
        int turn = g.getPhaseHandler().getTurn();
        if (plannedGame == g && plannedTurn == turn && plannedFor == me) return;
        plannedGame = g;
        plannedTurn = turn;
        plannedFor = me;
        if (me.getCardsIn(ZoneType.Hand).stream().noneMatch(c -> named(c, FEROCITY))) return;
        boolean target = me.getCreaturesInPlay().stream().anyMatch(c ->
                !c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE) || named(c, "Predator Ooze"));
        if (!target) return;
        // lands untap at the start of our turn; count them as main phase 1 has them (none spent yet)
        List<Card> lands = untappedLands(me);
        int mana = lands.size(), hold = 2;  // Ferocity: {1}{B/G}
        if (mana < hold) return;
        if (maxCastableCreature(me, mana - hold) < maxCastableCreature(me, mana) - 1) {
            ControlBrain.count("hold_skipped_for_bigger_play");
            return;
        }
        Card coloured = null, generic = null;
        for (Card l : lands) if (coloured == null && (produces(l, "G") || produces(l, "B"))) coloured = l;
        if (coloured == null) return;
        for (Card l : lands)  // generic: prefer a land that cannot make green (it is worth least to our other spells)
            if (l != coloured && (generic == null || (produces(generic, "G") && !produces(l, "G")))) generic = l;
        if (generic == null) return;
        AiCardMemory.rememberCard(me, coloured, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
        AiCardMemory.rememberCard(me, generic, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
        ControlBrain.count("hold_ferocity");
    }

    static Game flashGame;
    static int flashTurn = -1;
    static Player flashFor;

    /** the most expensive flash creature in hand that LANDS can pay for (mana value and colours), or null */
    static Card flashCreature(Player me, List<Card> lands) {
        Card best = null;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (!c.isCreature() || !c.hasKeyword(forge.game.keyword.Keyword.FLASH) || c.getCMC() > lands.size()) continue;
            if (pick(c, lands) == null) continue;
            if (best == null || c.getCMC() > best.getCMC()) best = c;
        }
        return best;
    }

    /** lands that pay for C: one per coloured symbol (a land making that colour), the rest generic (preferring lands
     *  that make none of C's colours); null if they can't */
    static List<Card> pick(Card c, List<Card> lands) {
        List<Card> left = new ArrayList<>(lands), out = new ArrayList<>();
        String[] cols = {"W", "U", "B", "R", "G"};
        int[] need = c.getManaCost().getColorShardCounts();
        for (int i = 0; i < 5 && i < need.length; i++)
            for (int k = 0; k < need[i]; k++) {
                Card l = null;
                for (Card x : left) if (produces(x, cols[i])) { l = x; break; }
                if (l == null) return null;
                left.remove(l); out.add(l);
            }
        int generic = (int) c.getCMC() - out.size();
        left.sort((a, b) -> Boolean.compare(produces(a, "G"), produces(b, "G")));
        for (int k = 0; k < generic; k++) {
            if (left.isEmpty()) return null;
            out.add(left.remove(0));
        }
        return out;
    }

    /**
     * flashhold (2026-10-17; user: "reserve the mana required to cast him and then cast him if he's needed as a blocker
     * or at EOT, since he doesn't really do anything on his first turn"): at our first priority of main phase 1, reserve
     * the lands for the most expensive flash creature in hand, the way plan() does for Ferocity -- unless holding them
     * costs the turn's biggest other creature play more than 1 mana value. AiTweaks casts it in the opponent's declare
     * attackers step when it makes a good block, else at their end step (both release the lands first).
     * Motivation: Forge's AI cast Edgar, Moonlit Sovereign in 47% of the games it drew him (other 5+ drops 84-87%),
     * 97% of the time at flash speed from mana it happened to leave over.
     */
    static void planFlash(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1) return;
        int turn = g.getPhaseHandler().getTurn();
        if (flashGame == g && flashTurn == turn && flashFor == me) return;
        flashGame = g; flashTurn = turn; flashFor = me;
        List<Card> lands = untappedLands(me);
        Card f = flashCreature(me, lands);
        if (f == null) return;
        int mana = lands.size(), hold = (int) f.getCMC(), bestAll = 0, bestRest = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (c.isCreature() && c != f && !c.hasKeyword(forge.game.keyword.Keyword.FLASH)) {
                if (c.getCMC() <= mana) bestAll = Math.max(bestAll, (int) c.getCMC());
                if (c.getCMC() <= mana - hold) bestRest = Math.max(bestRest, (int) c.getCMC());
            }
        if (bestRest < bestAll - 1) { ControlBrain.count("flashhold_skipped_for_bigger_play"); return; }
        for (Card l : pick(f, lands)) AiCardMemory.rememberCard(me, l, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
        ControlBrain.count("flashhold");
    }

    /** Before casting the held spell: free the reserved lands so its own payment can use them. */
    static void release(Player me) {
        AiCardMemory.clearMemorySet(me, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
    }
}
