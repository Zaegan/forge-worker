import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.game.ability.ApiType;
import forge.game.card.CardCollection;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Mono-Green Landfall's kill, which Forge's AI never sets up on its own (runs/audition-mglandfall: Escape Tunnel's
 * unblockable mode 7 times in 500 games, against 226 Mightform Harmonizers cast).
 *
 * The kill: in main 1, Escape Tunnel ({T}, sacrifice: target creature with power 2 or less can't be blocked this
 * turn) on a small creature that can attack, then the turn's land drops: every landfall makes every Mightform
 * Harmonizer double the power of a target creature, and every land we play, plus every Fabled Passage or Escape
 * Tunnel cracked for a land, is a landfall. The AI already plays its lands and cracks its fetches in main 1; it
 * does not use the Tunnel's second mode, and it aims the Harmonizer triggers at whatever creature it likes.
 *
 * Tweak "landfall": (1) stroke: activate the Tunnel's unblockable mode when the projected power of the target after
 * this turn's remaining landfalls is lethal; (2) while a stroke is on this turn, every Harmonizer trigger targets
 * the stroke creature; (3) simultaneous triggers are ordered so Sazh's Chocobo's counter resolves before the
 * doublings.
 * The AI cracks a Tunnel in response to its own landfall triggers, and Harmonizer triggers pick their targets as
 * they go on the stack, so the plan is made there too (when the first Harmonizer trigger of the turn is put on the
 * stack) and the Tunnel is activated while only our own triggers are on the stack.
 * Only decks with Mightform Harmonizer and Escape Tunnel are affected; counts in ControlBrain.counts.
 */
public class LandfallBrain {
    static final String TUNNEL = "Escape Tunnel", HARMONIZER = "Mightform Harmonizer", PASSAGE = "Fabled Passage",
            CHOCOBO = "Sazh's Chocobo", EXPLORER = "Icetill Explorer";
    static Card strokeTarget;
    static Game strokeGame;
    static int strokeTurn = -1;

    static boolean named(Card c, String n) {
        return c.getName().equals(n);
    }

    static long count(Player me, ZoneType z, String n) {
        return me.getCardsIn(z).stream().filter(c -> named(c, n)).count();
    }

    static boolean landfallDeck(Player me) {
        boolean h = false, t = false;
        for (Card c : me.getAllCards()) {
            if (named(c, HARMONIZER)) h = true;
            if (named(c, TUNNEL)) t = true;
        }
        return h && t;
    }

    static boolean strokeOn(Player me) {
        return strokeTarget != null && strokeGame == me.getGame() && strokeTurn == me.getGame().getPhaseHandler().getTurn()
                && strokeTarget.getController() == me && strokeTarget.isInPlay();
    }

    /** Landfalls still available this turn from land drops: fetch-type lands first (two landfalls each). */
    static int remainingLandfalls(Player me, Card exceptTunnel) {
        int drops = me.getMaxLandPlaysInfinite() ? 9 : Math.max(0, me.getMaxLandPlays() - me.getLandsPlayedThisTurn());
        List<Card> lands = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isLand()) lands.add(c);
        if (count(me, ZoneType.Battlefield, EXPLORER) > 0)
            for (Card c : me.getCardsIn(ZoneType.Graveyard)) if (c.isLand()) lands.add(c);
        lands.sort((a, b) -> Boolean.compare(fetchType(b), fetchType(a)));
        int lf = 0;
        for (int i = 0; i < Math.min(drops, lands.size()); i++) lf += fetchType(lands.get(i)) ? 2 : 1;
        // fetch-type lands already on the battlefield, uncracked (other than the Tunnel spent on the stroke)
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c != exceptTunnel && fetchType(c) && c.isUntapped()) lf += 1;
        return lf;
    }

    static boolean fetchType(Card c) {
        return named(c, PASSAGE) || named(c, TUNNEL);
    }

    static long project(Card x, int landfalls, long harmonizers) {
        long p = x.getNetPower();
        for (int i = 0; i < landfalls && p < 1_000_000; i++) {
            if (named(x, CHOCOBO)) p += 1;
            for (int h = 0; h < harmonizers; h++) p *= 2;
        }
        return p;
    }

    static SpellAbility unblockableMode(Card tunnel, Player me, Card target) {
        for (SpellAbility sa : tunnel.getSpellAbilities()) {
            if (!sa.isAbility() || !sa.usesTargeting()) continue;
            sa.setActivatingPlayer(me);
            if (!sa.canPlay() || !sa.canTarget(target)) continue;
            sa.resetTargets();
            sa.getTargets().add(target);
            return sa;
        }
        return null;
    }

    static boolean tunnelPending; // a stroke is planned this turn and the Tunnel is not yet activated

    /** Our turn, main 1, and the stack holds nothing but our own triggered abilities. */
    static boolean window(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1) return false;
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack())
            if (si.isSpell() || !si.isTrigger() || si.getActivatingPlayer() != me) return false;
        return true;
    }

    static Card untappedTunnel(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (named(c, TUNNEL) && c.isUntapped()) return c;
        return null;
    }

    /**
     * Plan a stroke: the creature (power 1-2, able to attack) whose projected power after the landfalls still to
     * come this turn (extra: landfalls already happening, whose Harmonizer triggers are being put on the stack) is
     * lethal. Sets the stroke state and returns true when there is one.
     */
    static boolean plan(Player me, int extra) {
        Game g = me.getGame();
        if (!landfallDeck(me)) return false;
        ControlBrain.count("plan_called");
        long harm = count(me, ZoneType.Battlefield, HARMONIZER);
        Card tunnel = untappedTunnel(me);
        if (harm == 0) return false;
        ControlBrain.count("plan_harm");
        if (tunnel == null) return false;
        ControlBrain.count("plan_tunnel");
        Player op = null;
        for (Player p : g.getPlayers()) if (p != me) op = p;
        if (op == null) return false;
        int lf = remainingLandfalls(me, tunnel) + extra;
        if (lf == 0) return false;
        ControlBrain.count("plan_landfalls");
        Card best = null;
        long bestP = 0;
        for (Card x : me.getCreaturesInPlay()) {
            if (x.getNetPower() > 2 || x.getNetPower() < 1 || x.isTapped() || x.isSick() || !CombatUtil.canAttack(x)) continue;
            long p = project(x, lf, harm);
            if (p > bestP) {
                bestP = p;
                best = x;
            }
        }
        if (best == null) return false;
        ControlBrain.count("stroke_seen");
        if (bestP < op.getLife()) return false;
        strokeTarget = best;
        strokeGame = g;
        strokeTurn = g.getPhaseHandler().getTurn();
        tunnelPending = true;
        ControlBrain.count("stroke_planned");
        return true;
    }

    /** From chooseSpellAbilityToPlay: activate the Tunnel's unblockable mode for a planned (or newly found) stroke. */
    /**
     * landseq@NAME (2026-10-23, meta pilots: the agents' Mono-Green Landfall wins came from casting the landfall payoffs
     * BEFORE the turn's landfalls; Forge plays its land first): in our main 1 with an empty stack, when Forge's choice
     * makes a landfall (a land play, Fabled Passage / Escape Tunnel cracked for a land, Shared Roots, Earthbender
     * Ascension) and a landfall payoff in hand can be cast now, cast the payoff first (Mightform Harmonizer, Icetill
     * Explorer, Sapling Nursery, Sazh's Chocobo, in that order; the normal cost before warp). The landfall itself is
     * then Forge's next choice.
     */
    static final String[] PAYOFFS = {HARMONIZER, EXPLORER, "Sapling Nursery", CHOCOBO};

    static boolean makesLandfall(SpellAbility sa) {
        if (sa.isLandAbility()) return true;
        Card h = sa.getHostCard();
        if (h == null) return false;
        String n = h.getName();
        if ((n.equals(PASSAGE) || n.equals(TUNNEL)) && sa.getApi() == ApiType.ChangeZone) return true;
        return sa.isSpell() && (n.equals("Shared Roots") || n.equals("Earthbender Ascension"));
    }

    static List<SpellAbility> seq(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty()) return ai;
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1 || !g.getStack().isEmpty()) return ai;
        if (!makesLandfall(ai.get(0))) return ai;
        for (String p : PAYOFFS) {
            CardCollection hand = new CardCollection();
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, p)) { hand.add(c); break; }
            if (hand.isEmpty()) continue;
            SpellAbility warp = null;
            for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
                if (!sa.isSpell()) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
                } catch (Exception e) {
                    continue;
                }
                if ((sa.getDescription() + " " + sa).toLowerCase().contains("warp")) { if (warp == null) warp = sa; continue; }
                ControlBrain.count("landseq_payoff_first");
                return List.of(sa);
            }
            if (warp != null) { ControlBrain.count("landseq_payoff_first_warp"); return List.of(warp); }
        }
        return ai;
    }

    static SpellAbility choose(Player me) {
        ControlBrain.count("choose_called");
        if (!window(me)) return null;
        if (!strokeOn(me) && !plan(me, 0)) return null;
        if (!tunnelPending) return null;
        Card tunnel = untappedTunnel(me);
        if (tunnel == null || strokeTarget.getNetPower() > 2) {
            ControlBrain.count("stroke_lost");
            tunnelPending = false;
            return null;
        }
        SpellAbility sa = unblockableMode(tunnel, me, strokeTarget);
        if (sa == null) return null;
        tunnelPending = false;
        ControlBrain.count("stroke");
        return sa;
    }

    /** Harmonizer triggers: plan a stroke as the first one goes on the stack, then aim all of them at its creature. */
    static Boolean playTrigger(Player me, WrappedAbility wa) {
        Card host = wa.getHostCard();
        if (host != null && named(host, HARMONIZER)) ControlBrain.count("harm_trigger");
        if (host == null || !named(host, HARMONIZER) || !wa.usesTargeting()) return null;
        if (!strokeOn(me) && !(window(me) && plan(me, 1))) return null;
        if (!wa.canTarget(strokeTarget)) return null;
        wa.resetTargets();
        wa.getTargets().add(strokeTarget);
        ControlBrain.count("stroke_doubling");
        return ComputerUtil.playNoStack(wa.getActivatingPlayer(), wa, me.getGame(), true);
    }

    /** Stack order for simultaneous triggers: the Harmonizer doublings go on first, so they resolve last. */
    static List<SpellAbility> order(Player me, List<SpellAbility> in) {
        if (!strokeOn(me)) return null;
        List<SpellAbility> h = new ArrayList<>(), rest = new ArrayList<>();
        for (SpellAbility sa : in) (sa.getHostCard() != null && named(sa.getHostCard(), HARMONIZER) ? h : rest).add(sa);
        if (h.isEmpty() || rest.isEmpty()) return null;
        h.addAll(rest);
        return h;
    }
}
