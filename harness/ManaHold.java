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

    /** Before casting the held spell: free the reserved lands so its own payment can use them. */
    static void release(Player me) {
        AiCardMemory.clearMemorySet(me, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
    }
}
