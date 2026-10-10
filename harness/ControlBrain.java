import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.AiController;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Control-deck decisions layered over Forge's AI (tweak "control", see AiTweaks), written from the failures in
 * runs/control-baseline-3 (Forge's AI piloting Standard_4c_Control vs v2.3: 18/50). Every rule keys on the control
 * deck's own cards, so other decks play exactly as Forge's AI does. In our own main phase with an empty stack, in order:
 *
 *  1. land first: in main 1, play the land Forge's AI would choose now instead of holding it for main 2 (it was
 *     casting spells and then playing the land that would have enabled a sweeper);
 *  2. sweep: Day of Judgment / Deadly Cover-Up when the opponent has 3+ creatures, or 2+ with total power 6+, or any
 *     board threatening lethal next turn, and our own creatures are worth less than theirs; never while Great Hall is
 *     animated unless facing lethal;
 *  3. Jeskai Revelation whenever affordable: the damage kills their best creature with toughness <= 4 (after damage
 *     already marked), else goes face; the bounce takes their best remaining creature (a token if that is the best),
 *     else one of their lands;
 *  4. Together as One when 3+ colours can be spent: one source of each colour is tapped first (converge counts colours
 *     spent), we draw, and the damage kills their best creature within reach, else goes face.
 *
 * Before any of that, on our own turn we pass through upkeep and draw step (see holdUntilMain).
 *
 * Also: Great Hall of the Biblioplex is never animated (the animation is permanent: it then blocks bigger creatures
 * or dies to our own sweepers, and 15 Halls were lost in 50 baseline games), and shocklands are paid for on our own
 * turn while at 8+ life, or at 3+ when the untapped land makes a spell in hand castable. On our own turn we also do
 * not respond to a stack of triggered abilities only (see onlyTriggers).
 */
public class ControlBrain {
    static final Map<String, Integer> counts = new TreeMap<>(); // decisions taken this game (reset by MatchGames)

    static final StringBuilder trace = new StringBuilder(); // per-turn snapshots (written by MatchGames next to the game log)
    static int tracedTurn = -1;

    /** One line per own main phase 1: life, mana, hand, both boards, then what we chose. */
    static void snapshot(Player me, String chose) {
        Game g = me.getGame();
        int turn = g.getPhaseHandler().getTurn();
        if (turn == tracedTurn && chose == null) return;
        tracedTurn = turn;
        Player op = opponent(me);
        StringBuilder b = new StringBuilder("T").append(turn).append(" life ").append(me.getLife()).append("/").append(op.getLife())
                .append(" untapped ").append(me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && !c.isTapped()).count())
                .append(" hand [");
        for (Card c : me.getCardsIn(ZoneType.Hand)) b.append(c.getName()).append("; ");
        b.append("] opp [");
        for (Card c : op.getCreaturesInPlay()) b.append(c.getName()).append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness()).append("; ");
        b.append("] ours [");
        for (Card c : me.getCreaturesInPlay()) b.append(c.getName()).append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness()).append("; ");
        b.append("] opp hand ").append(op.getCardsIn(ZoneType.Hand).size());
        if (chose != null) b.append(" -> ").append(chose);
        trace.append(b).append("\n");
    }

    static String json() {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Integer> e : counts.entrySet()) b.append(b.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":").append(e.getValue());
        return b.append("}").toString();
    }

    static void count(String k) {
        counts.merge(k, 1, Integer::sum);
    }

    static boolean named(Card c, String n) {
        return c.getName().equals(n);
    }

    static Player opponent(Player me) {
        return me.getGame().getRegisteredPlayers().stream().filter(p -> p != me).findFirst().get();
    }

    static boolean controlDeck(Player me) {
        for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Battlefield))
            for (Card c : me.getCardsIn(z))
                if (named(c, "Jeskai Revelation") || named(c, "Day of Judgment") || named(c, "Together as One")) return true;
        return false;
    }

    /** The spell abilities of our hand cards named NAME that can be cast and paid for now. */
    static SpellAbility castable(Player me, String name) {
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, name)) hand.add(c);
        if (hand.isEmpty()) return null;
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell()) continue;
            sa.setActivatingPlayer(me);
            try {
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) return sa;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static int value(Card c) {
        return ComputerUtilCard.evaluateCreature(c);
    }

    /**
     * True when we should pass without letting Forge's AI act: our own upkeep and draw step with an empty stack. The AI
     * otherwise casts instants there (Flashback into a burn spell, Helix), tapping the mana main phase 1 needs for a
     * sweeper, before it has even played its land (runs/control-ab-brain games 3 and 35).
     */
    static boolean holdUntilMain(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me)) return false;
        if (!g.getStack().isEmpty()) return onlyTriggers(me);
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (ph != PhaseType.UPKEEP && ph != PhaseType.DRAW) return false;
        if (!controlDeck(me)) return false;
        count("held_until_main");
        return true;
    }

    /**
     * On our own turn, a stack holding only triggered abilities (no spell) is not worth answering: the AI was
     * responding to the opponent's Cheering Crowd trigger at the start of our main phase by tapping out for burn,
     * leaving Day of Judgment uncast against five creatures (runs/control-trace1-brain game 26).
     */
    static boolean onlyTriggers(Player me) {
        if (!controlDeck(me)) return false;
        for (forge.game.spellability.SpellAbilityStackInstance si : me.getGame().getStack())
            if (si.isSpell() || !si.isTrigger()) return false;
        count("ignored_triggers");
        return true;
    }

    /** Our decision for this priority stop, or null to let Forge's AI decide. */
    static SpellAbility choose(AiController ai, Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || !g.getStack().isEmpty()) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (ph != PhaseType.MAIN1 && ph != PhaseType.MAIN2) return null;
        if (!controlDeck(me)) return null;
        neverAnimateHall(me);
        Player op = opponent(me);

        if (ph == PhaseType.MAIN1) snapshot(me, null);
        if (ph == PhaseType.MAIN1) {
            SpellAbility land = landFirst(ai, me);
            if (land != null) {
                count("land_first");
                return land;
            }
        }

        List<Card> theirs = new ArrayList<>(op.getCreaturesInPlay()), ours = new ArrayList<>(me.getCreaturesInPlay());
        int theirPower = theirs.stream().mapToInt(c -> Math.max(0, c.getNetPower())).sum();
        int theirValue = theirs.stream().mapToInt(ControlBrain::value).sum();
        int ourValue = ours.stream().mapToInt(ControlBrain::value).sum();
        boolean hallAnimated = ours.stream().anyMatch(c -> named(c, "Great Hall of the Biblioplex"));
        boolean lethalNext = theirPower >= me.getLife();
        boolean worth = theirs.size() >= 3 || (theirs.size() >= 2 && theirPower >= 6) || (lethalNext && !theirs.isEmpty());
        if (worth && ourValue < theirValue && (!hallAnimated || lethalNext)) {
            for (String n : List.of("Day of Judgment", "Deadly Cover-Up")) {
                SpellAbility sw = castable(me, n);
                if (sw != null) {
                    count("sweep"); trace.append("   " + "sweep" + "\n");
                    return sw;
                }
            }
        }

        SpellAbility rev = castable(me, "Jeskai Revelation");
        if (rev != null && targetRevelation(rev, me, op)) {
            count("revelation"); trace.append("   " + "revelation" + "\n");
            return rev;
        }

        SpellAbility tao = castable(me, "Together as One");
        if (tao != null) {
            int x = floatConverge(me, g, true);
            if (x >= 3) {
                floatConverge(me, g, false);
                targetTogether(tao, me, op, x);
                count("together"); trace.append("   " + "together" + "\n");
                return tao;
            }
        }
        return null;
    }

    static SpellAbility landFirst(AiController ai, Player me) {
        if (me.getLandsPlayedThisTurn() > 0) return null;
        CardCollection lands = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isLand()) lands.add(c);
        if (lands.isEmpty()) return null;
        Card pick;
        try {
            Method filter = AiController.class.getDeclaredMethod("filterLandsToPlay", CardCollection.class);
            Method best = AiController.class.getDeclaredMethod("chooseBestLandToPlay", CardCollection.class);
            filter.setAccessible(true);
            best.setAccessible(true);
            CardCollection ok = (CardCollection) filter.invoke(ai, lands);
            if (ok == null || ok.isEmpty()) return null;
            pick = (Card) best.invoke(ai, ok);
        } catch (Exception e) {
            return null;
        }
        if (pick == null) return null;
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(new CardCollection(List.of(pick)), me)) {
            if (!sa.isLandAbility()) continue;
            sa.setActivatingPlayer(me);
            if (me.canPlayLand(pick, false, sa)) return sa;
        }
        return null;
    }

    /** Their best creature that DAMAGE kills (counting damage already marked), excluding SKIP, or null. */
    static Card killable(Player op, int damage, Card skip) {
        Card best = null;
        for (Card c : op.getCreaturesInPlay())
            if (c != skip && c.getNetToughness() - c.getDamage() <= damage && (best == null || value(c) > value(best))) best = c;
        return best;
    }

    static boolean targetRevelation(SpellAbility rev, Player me, Player op) {
        SpellAbility bounce = rev, dmg = rev.getSubAbility();
        if (bounce.getApi() != ApiType.ChangeZone || dmg == null || dmg.getApi() != ApiType.DealDamage) return false;
        Card kill = killable(op, 4, null);
        Card back = null;
        for (Card c : op.getCreaturesInPlay())
            if (c != kill && bounce.canTarget(c) && (back == null || value(c) > value(back))) back = c;
        if (back == null)
            for (Card c : op.getCardsIn(ZoneType.Battlefield))
                if (c.isLand() && bounce.canTarget(c) && (back == null || c.isTapped() && !back.isTapped())) back = c;
        if (back == null) return false;
        bounce.resetTargets();
        bounce.getTargets().add(back);
        dmg.resetTargets();
        dmg.getTargets().add(kill != null && dmg.canTarget(kill) ? kill : op);
        return true;
    }

    static void targetTogether(SpellAbility tao, Player me, Player op, int x) {
        SpellAbility draw = tao, dmg = tao.getSubAbility();
        draw.resetTargets();
        draw.getTargets().add(me);
        if (dmg != null && dmg.usesTargeting()) {
            Card kill = killable(op, x, null);
            dmg.resetTargets();
            dmg.getTargets().add((GameObject) (kill != null && dmg.canTarget(kill) ? kill : op));
        }
    }

    /**
     * Converge: picks one untapped land for each of W, U, B, R (scarcest colour first, preferring lands that make the
     * fewest colours). With dryRun, returns how many colours it could cover; otherwise taps them into the pool.
     */
    static int floatConverge(Player me, Game g, boolean dryRun) {
        String[] colours = {"W", "U", "B", "R"};
        Map<String, List<SpellAbility>> by = new HashMap<>();
        Map<SpellAbility, Integer> breadth = new HashMap<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped() || !c.isLand()) continue;
            for (SpellAbility ma : c.getManaAbilities()) {
                ma.setActivatingPlayer(me);
                if (!ma.canPlay() || !ma.getPayCosts().hasTapCost() || ma.getPayCosts().hasSpecificCostType(forge.game.cost.CostPayLife.class))
                    continue;
                String prod = ma.getManaPart().isComboMana() ? ma.getManaPart().getComboColors(ma) : ma.getManaPart().getOrigProduced();
                int n = 0;
                for (String col : colours) if (prod.contains(col)) { by.computeIfAbsent(col, k -> new ArrayList<>()).add(ma); n++; }
                breadth.put(ma, n);
            }
        }
        List<String> order = new ArrayList<>(List.of(colours));
        order.sort(Comparator.comparingInt(col -> by.getOrDefault(col, List.of()).size()));
        Set<Card> used = new HashSet<>();
        int got = 0;
        for (String col : order) {
            List<SpellAbility> cands = new ArrayList<>(by.getOrDefault(col, List.of()));
            cands.sort(Comparator.comparingInt(breadth::get));
            for (SpellAbility ma : cands) {
                if (used.contains(ma.getHostCard())) continue;
                used.add(ma.getHostCard());
                got++;
                if (!dryRun) {
                    if (ma.getManaPart().isComboMana()) ma.getManaPart().setExpressChoice(col);
                    ComputerUtil.playNoStack(me, ma, g, true);
                    if (ma.getManaPart().isComboMana()) ma.getManaPart().clearExpressChoice();
                }
                break;
            }
        }
        return got;
    }

    /** Forge's AI skips any ability whose AILogic is "Never", so it then makes its next-best play instead. */
    static void neverAnimateHall(Player me) {
        for (ZoneType z : List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Library))
            for (Card c : me.getCardsIn(z))
                if (named(c, "Great Hall of the Biblioplex"))
                    for (SpellAbility sa : c.getSpellAbilities())
                        if (sa.getApi() == ApiType.Animate && !"Never".equals(sa.getParam("AILogic"))) sa.putParam("AILogic", "Never");
    }

    /** Never animate Great Hall of the Biblioplex (a backstop; neverAnimateHall should keep the AI from choosing it). */
    static boolean vetoed(SpellAbility sa) {
        return sa != null && sa.getApi() == ApiType.Animate && named(sa.getHostCard(), "Great Hall of the Biblioplex");
    }

    /** Some spell in hand costs exactly one more than the untapped lands we already have (the new land enables it). */
    static boolean enablesSpell(Player me) {
        long untapped = me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && !c.isTapped()).count();
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (!c.isLand() && c.getCMC() == untapped + 1) return true;
        return false;
    }

    /** Shockland "pay 2 life or enter tapped": pay on our own turn at 8+ life; null leaves it to Forge's AI. */
    static Boolean payShock(Cost cost, SpellAbility sa, Player me) {
        if (sa == null || sa.getHostCard() == null || !sa.getHostCard().isLand() || !controlDeck(me)) return null;
        if (!cost.toSimpleString().contains("2 life")) return null;
        boolean pay = me.getGame().getPhaseHandler().isPlayerTurn(me) && (me.getLife() >= 8 || me.getLife() >= 3 && enablesSpell(me));
        count(pay ? "shock_paid" : "shock_tapped");
        return pay;
    }
}
