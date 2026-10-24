import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.*;

/**
 * Protection plays Forge's AI never makes (tweak "protect", see AiTweaks). In 40 logged games of v2.3 + 2 Tam + 4
 * Ferocity vs 4c Control (runs/v3probe-tam) the AI cast Tam 30 times and never used its ability, and drew Ferocity of
 * the Hunt 38 times and never cast it (its script, in Forge's upcoming/ folder, has no AI logic). Only acts when these
 * cards are ours, so other decks play exactly as Forge's AI does.
 *
 * Tam, Mindful First-Year — "Each other creature you control has hexproof from each of its colors"; {T}: target creature
 *   you control becomes all colors. When the top of the stack is an opponent's spell or ability targeting one of our
 *   creatures other than Tam, tap Tam to make that creature all colors: it then has hexproof from every colour and the
 *   spell loses that target. Tam does not attack while we have another creature (an attacker stays tapped through the
 *   opponent's turn, when sorcery-speed removal comes).
 * Hand disruption (Psychic Whorl, Duress, Pilfer, Deep-Cavern Bat, Gastal Raider and the other discard cards of the
 *   black sideboard pool) — cast first in our
 *   main phase 1 whenever castable and the opponent has cards in hand.
 * Ferocity of the Hunt — flash Aura, +1/+0 and deathtouch, the creature returns when it dies. (a) In response to an
 *   opponent's sweeper (DestroyAll / DamageAll) or a destroy / damage spell targeting our creature, flash it onto the
 *   threatened creature (our best creature for a sweeper); not against exile or bounce, which it doesn't answer.
 *   (b) Otherwise at the opponent's end step, onto our best creature that has no Ferocity yet.
 */
public class ProtectBrain {
    static final String TAM = "Tam, Mindful First-Year", FEROCITY = "Ferocity of the Hunt";

    static boolean named(Card c, String n) {
        return c.getName().equals(n);
    }

    static List<SpellAbility> chain(SpellAbility sa) {
        List<SpellAbility> out = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) out.add(s);
        return out;
    }

    static boolean hasFerocity(Card c) {
        for (Card a : c.getAttachedCards()) if (named(a, FEROCITY)) return true;
        return false;
    }

    static Card best(List<Card> cs) {
        Card b = null;
        for (Card c : cs) if (b == null || ComputerUtilCard.evaluateCreature(c) > ComputerUtilCard.evaluateCreature(b)) b = c;
        return b;
    }

    // hand disruption, cast first in our main phase 1 (best first); Forge's AI casts it late (held ~6 turns in
    // runs/sbprobe-discard), when control has already used or protected its key cards
    static final List<String> DISCARD = List.of("Psychic Whorl", "Duress", "Pilfer", "Deep-Cavern Bat", "Gastal Raider",
            "Cerebral Confiscation", "Hostile Investigator", "Burglar Rat", "Binding Negotiation", "Spellscorn Coven",
            "Tinybones, Pocket Nuisance", "Dream Seizer", "Thornplate Intimidator", "Shredder's Revenge", "Seeker's Folly",
            "Eriette's Whisper", "HYDRA Infiltration", "Rankle's Prank", "Cruelclaw's Heist", "Malboro", "Hollow Marauder",
            "Fanatic of the Harrowing", "Stony-Voiced Goblins", "Hecteyes", "Rank Rat", "Virus Beetle", "Corrupt Court Official",
            "Send in the Pest", "Bandit's Talent", "Temporal Intervention", "Aggressive Negotiations", "Down, Down to Goblin-town",
            "Intimidation Tactics", "Ego Drain", "Bloodtithe Collector");

    static SpellAbility discardFirst(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1 || !g.getStack().isEmpty()) return null;
        Player op = ControlBrain.opponent(me);
        if (op.getCardsIn(ZoneType.Hand).isEmpty()) return null;
        for (String n : DISCARD) {
            CardCollection hand = new CardCollection();
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, n)) hand.add(c);
            for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
                if (!sa.isSpell()) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
                } catch (Exception e) {
                    continue;
                }
                if (sa.usesTargeting()) {
                    if (!sa.canTarget(op)) continue;
                    sa.resetTargets();
                    sa.getTargets().add(op);
                }
                ControlBrain.count("discard_first");
                return sa;
            }
        }
        return null;
    }

    /**
     * Prepare (only with tweak "prepare"; untested for value, see mydeck/sideboard/TODO.md): a prepared creature's spell copy waits in its owner's exile in the PreparedSpell state; Forge's AI almost
     * never casts it (Vastlands Scavenger's Bind to Life: once in 150 logged v2.3 games, ~160 Scavengers). In our own
     * main phase with an empty stack: Soul Tether (a Heartwood token: Woodwork Prodigy, Heartwood Crafter, Konstrari
     * Improviser) whenever castable, in main 1 (ramp is worth most early); any other prepared spell (Bind to Life) in
     * main 2, from mana the AI left over.
     */
    static SpellAbility preparedCopy(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || !g.getStack().isEmpty()) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (ph != PhaseType.MAIN1 && ph != PhaseType.MAIN2) return null;
        for (Card c : me.getCardsIn(ZoneType.Exile)) {
            if (c.getCurrentStateName() != forge.card.CardStateName.PreparedSpell || c.mayPlay(me).isEmpty()) continue;
            boolean ramp = c.getName().equals("Soul Tether");
            if (!ramp && ph != PhaseType.MAIN2) continue;
            for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(List.of(c)), me)) {
                if (!sa.isSpell()) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
                } catch (Exception e) {
                    continue;
                }
                if (sa.usesTargeting()) continue;  // the prepared spells this rule is for have no targets
                ControlBrain.count("prepared:" + c.getName());
                return sa;
            }
        }
        return null;
    }

    static SpellAbility choose(Player me) {
        Game g = me.getGame();
        SpellAbility pc = AiTweaks.has("prepare") ? preparedCopy(me) : null;  // off unless tweak "prepare"
        if (pc != null) return pc;
        SpellAbility d = discardFirst(me);
        if (d != null) return d;
        boolean haveTam = me.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> named(c, TAM));
        boolean haveFerocity = me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> named(c, FEROCITY));
        if (!haveTam && !haveFerocity) return null;

        if (!g.getStack().isEmpty()) {
            SpellAbilityStackInstance top = g.getStack().peek();
            if (top.getActivatingPlayer() == me) return null;
            SpellAbility sa = top.getSpellAbility();
            Set<Card> targeted = new LinkedHashSet<>();
            boolean sweeper = false, kills = false;
            for (SpellAbility s : chain(sa)) {
                ApiType api = s.getApi();
                if (api == ApiType.DestroyAll || api == ApiType.DamageAll) sweeper = true;
                if (s.usesTargeting() && s.getTargets() != null)
                    for (Card c : s.getTargets().getTargetCards())
                        if (c.getController() == me && c.isCreature() && c.isInPlay()) {
                            targeted.add(c);
                            if (api == ApiType.Destroy || api == ApiType.DealDamage) kills = true;
                        }
            }
            // Tam first: it costs nothing and stops exile and bounce too
            for (Card c : targeted) {
                if (named(c, TAM)) continue;
                SpellAbility tam = tamAbility(me, c);
                if (tam != null) {
                    ControlBrain.count("tam_protect");
                    return tam;
                }
            }
            if (haveFerocity && (sweeper || kills)) {
                List<Card> pool = new ArrayList<>();
                for (Card c : sweeper ? me.getCreaturesInPlay() : targeted) if (!hasFerocity(c)) pool.add(c);
                // tweak "ooze": an indestructible creature survives destroy and damage anyway; don't spend Ferocity on it
                if (AiTweaks.has("ooze")) pool.removeIf(c -> c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE));
                Card t = best(pool);
                SpellAbility f = t == null ? null : ferocityOn(me, t);
                if (f != null) {
                    ControlBrain.count(sweeper ? "ferocity_vs_sweeper" : "ferocity_vs_removal");
                    return f;
                }
            }
            return null;
        }

        // tweak "ooze": hold Ferocity (no end-of-turn cast) for removal on a partner (above) or for Predator Ooze in a
        // combat it cannot win on power: deathtouch makes every point of its damage lethal, and each creature it damaged
        // that dies is another +1/+1 counter
        // tweak "oozeeot" (implies the above): the same, plus the normal end-of-turn cast, on a creature that is not
        // indestructible (with the Ooze in play that is a second creature that survives a board wipe, and it uses mana
        // that would otherwise go unspent)
        if (haveFerocity && AiTweaks.has("ooze")) {
            SpellAbility trick = oozeTrick(me);
            if (trick != null || !AiTweaks.has("oozeeot")) return trick;
        }
        // opponent's end step, empty stack: put Ferocity on our best creature without one
        if (haveFerocity && !g.getPhaseHandler().isPlayerTurn(me) && g.getPhaseHandler().getPhase() == PhaseType.END_OF_TURN) {
            List<Card> pool = new ArrayList<>();
            for (Card c : me.getCreaturesInPlay()) if (!hasFerocity(c)) pool.add(c);
            if (AiTweaks.has("ooze")) pool.removeIf(c -> c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE));
            Card t = best(pool);
            SpellAbility f = t == null ? null : ferocityOn(me, t);
            if (f != null) {
                ControlBrain.count("ferocity_eot");
                return f;
            }
        }
        return null;
    }

    /** Keep Tam home (untapped through the opponent's turn) while it has another creature of ours to protect. */
    static void holdTamBack(Player me, forge.game.combat.Combat combat) {
        if (me.getCreaturesInPlay().stream().noneMatch(c -> !named(c, TAM))) return;
        for (Card c : new ArrayList<>(combat.getAttackers()))
            if (named(c, TAM) && c.getController() == me) {
                combat.removeFromCombat(c);
                ControlBrain.count("tam_held_back");
            }
    }

    static SpellAbility tamAbility(Player me, Card target) {
        for (Card tam : me.getCardsIn(ZoneType.Battlefield)) {
            if (!named(tam, TAM) || tam.isTapped() || tam.isSick()) continue;
            for (SpellAbility sa : tam.getSpellAbilities()) {
                if (!sa.isAbility() || sa.getApi() != ApiType.Animate) continue;
                sa.setActivatingPlayer(me);
                if (!sa.canPlay() || !sa.canTarget(target)) continue;
                sa.resetTargets();
                sa.getTargets().add(target);
                return sa;
            }
        }
        return null;
    }

    /** Declare blockers step: our Predator Ooze is blocked by, or blocks, creatures its power cannot all kill. */
    static SpellAbility oozeTrick(Player me) {
        Game g = me.getGame();
        if (g.getPhaseHandler().getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS || !g.getStack().isEmpty()) return null;
        forge.game.combat.Combat cb = g.getCombat();
        if (cb == null) return null;
        for (Card o : me.getCreaturesInPlay()) {
            if (!named(o, "Predator Ooze") || hasFerocity(o)) continue;
            List<Card> foes = new ArrayList<>();
            if (cb.isAttacking(o)) foes.addAll(cb.getBlockers(o));
            else if (cb.isBlocking(o)) foes.addAll(cb.getAttackersBlockedBy(o));
            if (foes.isEmpty()) continue;
            int need = 0;
            for (Card f : foes) if (!f.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) need += Math.max(1, f.getNetToughness() - f.getDamage());
            if (need == 0 || o.getNetPower() >= need) continue;
            SpellAbility sa = ferocityOn(me, o);
            if (sa != null) {
                ControlBrain.count("ferocity_ooze_combat");
                return sa;
            }
        }
        return null;
    }

    static SpellAbility ferocityOn(Player me, Card target) {
        if (AiTweaks.has("manahold")) ManaHold.release(me);  // the held lands pay for it
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, FEROCITY)) hand.add(c);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell()) continue;
            sa.setActivatingPlayer(me);
            try {
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
            } catch (Exception e) {
                continue;
            }
            if (sa.usesTargeting()) {
                if (!sa.canTarget(target)) continue;
                sa.resetTargets();
                sa.getTargets().add(target);
            }
            return sa;
        }
        return null;
    }
}
