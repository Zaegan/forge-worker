import forge.ai.AiController;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;
import java.util.Set;

/**
 * azplan@NAME (Azorius turn planner, user 2026-10-26: "It seems to me like Azorius can only succeed with a turn planner",
 * then "yes, build the Azorius turn planner"). A1 (hold Three Steps Ahead) was the only Azorius rule that moved results;
 * A6 (land order, one decision on our own turn) tied. The scan's remaining Azorius misplays (ad-s11: flash creatures
 * and Fountainport on the wrong turn, No More Lies uncastable, tapping out in main 2, Starcage not cast) are one
 * question: how this turn cycle's mana splits between our turn and the opponent's. One budget per cycle:
 *
 *   reserve   mana kept for the opponent's turn: the cheapest counter in hand (No More Lies 2, Three Steps Ahead's
 *             counter mode 3), 0 with no counter in hand or the opponent's hand empty.
 *   our turn  (stack empty, any step) instant-speed card flow -- Consult the Star Charts, Wan Shi Tong, Three Steps
 *             Ahead without its counter mode, Fountainport -- waits for the opponent's end step. Everything else that
 *             costs mana is played only if it leaves the reserve, unless it is an answer while we are under pressure
 *             (their power x 3 >= our life) or a threat while the opponent holds 2 or fewer cards. A vetoed play is
 *             replaced by the best play that fits: an answer when pressured, else the costliest proactive spell left
 *             within budget, else our land drop, else pass.
 *             Main 1, an answer the board calls for is cast without waiting for Forge: Day of Judgment by 4c's sweep
 *             test (ControlBrain), Pinnacle Starcage when it exiles more of theirs than ours (2+ of theirs).
 *   their turn before the end step, a non-counter play that eats the reserve while they hold cards is vetoed, unless
 *             it is removal while pressured, or anything while the creatures attacking us are lethal.
 *   their end step the reserve is released: Wan Shi Tong (X >= 2), Consult the Star Charts, a Fountainport Fish,
 *             in that order, then Forge (Three Steps Ahead's loot via A1).
 * Only public information: the board, life totals, the opponent's hand size and our own hand.
 */
public class AzoriusPlan {
    static boolean on(Player p) { return AiTweaks.on("azplan", p); }

    static final String NML = "No More Lies", TSA = "Three Steps Ahead", CONSULT = "Consult the Star Charts", WAN = "Wan Shi Tong, Librarian",
            PORT = "Fountainport", DOJ = "Day of Judgment", CAGE = "Pinnacle Starcage";
    static final Set<String> FLOW = Set.of(CONSULT, WAN, PORT);   // instant-speed card flow: the opponent's end step
    static final Set<String> ANSWERS = Set.of(DOJ, CAGE, "Seam Rip", "Get Lost", "Erode");
    static final Set<String> REMOVAL = Set.of("Get Lost", "Erode");
    static final Set<String> THREATS = Set.of("Emeritus of Ideation");

    static int reserve(Player me, Player op) {
        if (op == null || op.getCardsIn(ZoneType.Hand).isEmpty()) return 0;
        int r = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals(NML)) r = 2;
            else if (c.getName().equals(TSA) && r == 0) r = 3;
        }
        return r;
    }

    static boolean counter(SpellAbility sa) {
        Card h = sa.getHostCard();
        return h != null && (h.getName().equals(NML) || h.getName().equals(TSA) && MetaBrain.hasMode(sa, ApiType.Counter));
    }

    static boolean flow(SpellAbility sa) {
        Card h = sa.getHostCard();
        if (h == null || sa.isManaAbility() || sa.isLandAbility()) return false;
        return FLOW.contains(h.getName()) || h.getName().equals(TSA) && !MetaBrain.hasMode(sa, ApiType.Counter);
    }

    /** mana SA takes from our untapped sources; X spells (Wan Shi Tong) take all of it */
    static int cost(Player me, SpellAbility sa) {
        if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null && sa.getPayCosts().getTotalMana().countX() > 0)
            return LandfallBrain.untappedSources(me);
        int k = LandOrder.cost(sa);
        if (sa.getHostCard() != null && sa.getHostCard().isLand() && !sa.isSpell()) k++;   // the land taps itself too
        return k;
    }

    static boolean lethalAttack(Player me) {
        Game g = me.getGame();
        if (g.getCombat() == null) return false;
        int dmg = 0;
        for (Card a : g.getCombat().getAttackers())
            if (me.equals(g.getCombat().getDefenderPlayerByAttacker(a))) dmg += a.getNetCombatDamage();
        return dmg > 0 && dmg >= me.getLife();
    }

    static boolean cageWorth(Player me, Player op) {
        int theirs = 0, ours = 0;
        for (Card c : op.getCardsIn(ZoneType.Battlefield)) if ((c.isCreature() || c.isArtifact()) && !c.isLand() && c.getCMC() <= 2) theirs++;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if ((c.isCreature() || c.isArtifact()) && !c.isLand() && c.getCMC() <= 2) ours++;
        return theirs >= 2 && theirs > ours;
    }

    static boolean sweepWorth(Player me, Player op) {
        List<Card> theirs = op.getCreaturesInPlay(), ours = me.getCreaturesInPlay();
        int pow = theirs.stream().mapToInt(c -> Math.max(0, c.getNetPower())).sum();
        int tv = theirs.stream().mapToInt(ControlBrain::value).sum(), ov = ours.stream().mapToInt(ControlBrain::value).sum();
        boolean worth = theirs.size() >= 3 || theirs.size() >= 2 && pow >= 6 || pow >= me.getLife() && !theirs.isEmpty();
        return worth && ov < tv;
    }

    static SpellAbility ability(Player me, String card, ApiType api) {
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (!c.getName().equals(card) || c.isTapped()) continue;
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() != api || sa.isManaAbility()) continue;
                if (api == ApiType.Token && !String.valueOf(sa.getParam("TokenScript")).contains("fish")) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) return sa;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** our own decision for this priority stop, or null for Forge's (then post) */
    static SpellAbility choose(AiController ai, Player me) {
        Game g = me.getGame();
        if (!g.getStack().isEmpty()) return null;
        Player op = ProwessBrain.opponent(me);
        if (op == null) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (!g.getPhaseHandler().isPlayerTurn(me)) {
            if (ph != PhaseType.END_OF_TURN) return null;
            if (LandfallBrain.untappedSources(me) >= 4) {
                SpellAbility w = ControlBrain.castable(me, WAN);
                if (w != null) { ControlBrain.count("azplan_eot_wan"); return w; }
            }
            SpellAbility c = ControlBrain.castable(me, CONSULT);
            if (c != null) { ControlBrain.count("azplan_eot_consult"); return c; }
            SpellAbility f = ability(me, PORT, ApiType.Token);
            if (f != null) { ControlBrain.count("azplan_eot_fish"); return f; }
            return null;
        }
        if (ph != PhaseType.MAIN1) return null;
        SpellAbility land = ControlBrain.landFirst(ai, me);
        if (land != null) { ControlBrain.count("azplan_land_first"); return land; }
        if (sweepWorth(me, op)) {
            SpellAbility s = ControlBrain.castable(me, DOJ);
            if (s != null) { ControlBrain.count("azplan_sweep"); return s; }
        }
        if (cageWorth(me, op)) {
            SpellAbility s = ControlBrain.castable(me, CAGE);
            if (s != null) { ControlBrain.count("azplan_cage"); return s; }
        }
        return null;
    }

    /** the best play within BUDGET on our turn (an answer first when pressured), or null */
    static SpellAbility fits(Player me, int budget, boolean pressured) {
        SpellAbility best = null;
        int bestScore = -1;
        for (SpellAbility sa : MetaBrain.castableHand(me, c -> !c.isLand() && !FLOW.contains(c.getName()) && !c.getName().equals(TSA) && !c.getName().equals(NML))) {
            String n = sa.getHostCard().getName();
            int k = cost(me, sa);
            if (k > budget || REMOVAL.contains(n) && !pressured) continue;
            int score = (pressured && ANSWERS.contains(n) ? 100 : 0) + k;
            if (score > bestScore) { best = sa; bestScore = score; }
        }
        return best;
    }

    static List<SpellAbility> post(AiController ai, Player me, List<SpellAbility> in) {
        if (in == null || in.isEmpty()) return in;
        SpellAbility pick = in.get(0);
        Card h = pick.getHostCard();
        if (h == null || h.getController() != me || pick.isLandAbility() || pick.isManaAbility()) return in;
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        if (op == null || !g.getStack().isEmpty() && g.getPhaseHandler().isPlayerTurn(me)) return in;
        if (counter(pick)) return in;
        int k = cost(me, pick);
        if (k <= 0) return in;
        int have = LandfallBrain.untappedSources(me), res = reserve(me, op);
        boolean pressured = SpellBrain.pressured(me, op);
        PhaseType ph = g.getPhaseHandler().getPhase();
        String n = h.getName();
        if (!g.getPhaseHandler().isPlayerTurn(me)) {
            if (ph == PhaseType.END_OF_TURN || have - k >= res) return in;
            if (lethalAttack(me) || pressured && REMOVAL.contains(n)) { ControlBrain.count("azplan_their_turn_allowed"); return in; }
            ControlBrain.count("azplan_their_turn_kept");
            return null;
        }
        boolean veto;
        if (flow(pick)) veto = true;
        else if (have - k >= res) veto = false;
        else veto = !(pressured && ANSWERS.contains(n) || THREATS.contains(n) && op.getCardsIn(ZoneType.Hand).size() <= 2);
        if (!veto) return in;
        ControlBrain.count(flow(pick) ? "azplan_flow_deferred" : "azplan_kept");
        if (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2) {
            SpellAbility alt = fits(me, have - res, pressured);
            if (alt != null && alt != pick) { ControlBrain.count("azplan_alt"); return List.of(alt); }
            SpellAbility land = ControlBrain.landFirst(ai, me);
            if (land != null) { ControlBrain.count("azplan_land_instead"); return List.of(land); }
        }
        return null;
    }
}
