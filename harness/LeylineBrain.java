import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Izzet Leyline's kill turn, which Forge's AI does not play: it holds most of its pump spells (runs/audition-izzetleyline:
 * 43 pump casts in 31 games from a deck with ~21) because each one alone rarely looks worth a card to its combat logic.
 *
 * The deck wins by stacking pumps on one evasive creature in a single turn. Leyline of Resonance copies every instant or
 * sorcery that targets only a single creature you control, Slickshot Show-Off gets +2/+0 for every noncreature spell
 * cast, prowess creatures +1/+1, and Burn Together (Callous Sell-Sword's adventure) makes a creature deal its power to
 * the opponent again after combat.
 *
 * Tweak "leyline": on our turn, when a plan exists that is LETHAL this turn, cast its spells one at a time:
 * - main 1: the target is an attacker (haste or not summoning sick) that the opponent cannot block (flying with no
 *   untapped flyer/reach on their side, Elusive Otter's power rule, or made unblockable by Pym Particles in the plan);
 *   all of the plan's sorceries and instants go on it before combat;
 * - declare blockers step: the target is an unblocked attacker; the plan uses instants only;
 * - main 2: Burn Together on a creature whose power is at least the opponent's life.
 * Damage counted: the target's pumped power plus the power of our other unblockable or unblocked attackers. Spells
 * are budgeted against the available mana estimate, adds first, Bulk Up (double) last. Only decks with these cards are
 * affected; counts in ControlBrain.counts.
 *
 * Tweak "leylinespend" (with leyline): when no lethal plan exists, spend SURPLUS mana on damage pumps instead of
 * holding them (runs measure-leyline-brain: ~3.5 pump spells drawn and never cast per loss, Pym Particles cast 7%,
 * opponent at a median 15-20 life when we die). A pump adds about the same damage whenever it is cast, and mana is
 * short, so unspent mana on an unblocked attacker is damage thrown away. Surplus = untapped mana lands minus the mana
 * value of the cheapest creature card in hand (that creature is cast first). One spell per priority:
 * - main 1, sorceries: Pym Particles on an attacker the opponent can block (it becomes unblockable and draws);
 *   Ancestral Anger / Wild Ride on an attacker the opponent cannot block. The target is then made to attack.
 * - declare blockers step (our attack), instants: Turn Inside Out, Might of the Meek, Bulk Up on an unblocked attacker.
 * Protection spells (Shore Up, Octopus Form, Secret Identity) are kept. Slickshot Show-Off is the preferred target
 * (+2/+0 per noncreature spell).
 */
public class LeylineBrain {
    static final String LEYLINE = "Leyline of Resonance", SHOWOFF = "Slickshot Show-Off", OTTER = "Elusive Otter",
            PYM = "Pym Particles", BULK = "Bulk Up", SELLSWORD = "Callous Sell-Sword", ANGER = "Ancestral Anger",
            SECRET = "Secret Identity";

    static final java.util.Set<Card> planned = new java.util.HashSet<>(); // attackers a main-1 plan counted on
    static Game plannedGame;
    static int plannedTurn = -1;

    /** From declareAttackers: make sure every creature a lethal main-1 plan counted on is attacking. */
    static void forceAttack(Player me, Combat combat) {
        Game g = me.getGame();
        if (plannedGame != g || plannedTurn != g.getPhaseHandler().getTurn()) return;
        Player op = opponent(me);
        for (Card c : planned)
            if (c.isInPlay() && c.getController() == me && !combat.isAttacking(c) && CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("leyline_forced_attacker");
            }
    }

    static boolean named(Card c, String n) {
        return c.getName().equals(n);
    }

    /** Power added by one application of a pump (Bulk Up and Secret Identity are handled separately). */
    static int add(String n, Player me) {
        switch (n) {
            case "Wild Ride":
            case "Turn Inside Out":
                return 3;
            case "Octopus Form":
            case "Shore Up":
                return 1;
            case ANGER:
                return 1 + (int) me.getCardsIn(ZoneType.Graveyard).stream().filter(c -> named(c, ANGER)).count();
            default:
                return 0;
        }
    }

    static boolean pump(String n) {
        switch (n) {
            case "Wild Ride": case "Turn Inside Out": case "Octopus Form": case "Shore Up": case ANGER: case BULK:
            case SECRET: case PYM: case "Might of the Meek":
                return true;
            default:
                return false;
        }
    }

    static final class Step {
        final SpellAbility sa;
        final String name;
        final int cost;

        Step(SpellAbility sa, String name, int cost) {
            this.sa = sa;
            this.name = name;
            this.cost = cost;
        }
    }

    /** Castable pump spells in hand (one SA per card), instants only when asked. */
    static List<Step> pumps(Player me, Card target, boolean instantsOnly) {
        List<Step> out = new ArrayList<>();
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (pump(c.getName())) hand.add(c);
        java.util.Set<Card> used = new java.util.HashSet<>();
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            Card h = sa.getHostCard();
            if (!sa.isSpell() || used.contains(h)) continue;
            if (instantsOnly && !h.isInstant()) continue;
            if (named(h, SECRET) && sa.getDescription() != null && sa.getDescription().contains("Conceal")) continue;
            sa.setActivatingPlayer(me);
            try {
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
            } catch (Exception e) {
                continue;
            }
            if (!sa.usesTargeting() || !sa.canTarget(target)) continue;
            used.add(h);
            out.add(new Step(sa, h.getName(), Math.max(1, (int) h.getCMC())));
        }
        return out;
    }

    static final class Plan {
        final List<Step> steps = new ArrayList<>();
        long power;
    }

    /** Greedy plan on TARGET with MANA: cheapest power first, Secret Identity early, Bulk Up last. */
    static Plan plan(Player me, Card target, List<Step> avail, int mana, boolean needPym) {
        int copies = (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> named(c, LEYLINE)).count();
        int apps = 1 + copies;
        boolean showoff = named(target, SHOWOFF), prowess = target.hasKeyword(forge.game.keyword.Keyword.PROWESS);
        Plan p = new Plan();
        List<Step> order = new ArrayList<>(avail);
        order.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        long pw = target.getNetPower();
        int left = mana;
        boolean pymDone = !needPym;
        for (Step s : order) {
            if (s.cost > left) continue;
            boolean isPym = s.name.equals(PYM);
            int gain = s.name.equals(BULK) || s.name.equals(SECRET) ? 1 : add(s.name, me) * apps;
            int trig = (showoff ? 2 : 0) + (prowess ? 1 : 0);
            if (!isPym && gain + trig == 0) continue;
            if (isPym && pymDone) continue;
            if (isPym) pymDone = true;
            left -= s.cost;
            p.steps.add(s);
            pw += trig;
            if (s.name.equals(SECRET)) pw = Math.max(pw, 3 + (pw - target.getBasePower()));
            else if (s.name.equals(BULK)) for (int i = 0; i < apps; i++) pw *= 2;
            else pw += add(s.name, me) * apps;
        }
        if (!pymDone) return null;
        p.power = pw;
        return p;
    }

    static int rank(Step s) {
        if (s.name.equals(PYM)) return 0;
        if (s.name.equals(SECRET)) return 1;
        if (s.name.equals(BULK)) return 9;
        return 2 + s.cost;
    }

    static Player opponent(Player me) {
        for (Player p : me.getGame().getPlayers()) if (p != me) return p;
        return null;
    }

    static boolean deck(Player me) {
        for (Card c : me.getAllCards()) if (named(c, LEYLINE) || named(c, SHOWOFF)) return true;
        return false;
    }

    static boolean canAttackNow(Card c) {
        return c.isUntapped() && (!c.isSick() || c.hasKeyword(forge.game.keyword.Keyword.HASTE)) && CombatUtil.canAttack(c);
    }

    static boolean unblockable(Card a, Player op) {
        for (Card b : op.getCreaturesInPlay()) {
            if (b.isTapped()) continue;
            try {
                if (CombatUtil.canBlock(a, b)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    static SpellAbility next(Plan p, Card target) {
        Step s = p.steps.get(0);
        s.sa.resetTargets();
        s.sa.getTargets().add(target);
        return s.sa;
    }

    static SpellAbility choose(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || !g.getStack().isEmpty() || !deck(me)) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        Player op = opponent(me);
        if (op == null) return null;
        // Forge's getAvailableManaEstimate over-counts here (9 with one untapped land, runs/audition-probe2-leyline);
        // the deck has no mana creatures, so count untapped lands that tap for mana
        int mana = (int) me.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.isLand() && c.isUntapped() && !c.getManaAbilities().isEmpty()).count();
        if (ph == PhaseType.MAIN2) {
            SpellAbility b = burn(me, op);
            if (b == null && AiTweaks.TWEAKS.contains("leydig")) b = dig(me, mana);
            return b;
        }
        if (ph == PhaseType.MAIN1) {
            long other = 0;
            List<Card> attackers = new ArrayList<>();
            for (Card c : me.getCreaturesInPlay()) if (canAttackNow(c)) attackers.add(c);
            for (Card c : attackers) if (unblockable(c, op)) other += c.getNetPower();
            boolean burnLater = AiTweaks.TWEAKS.contains("leyburn") && burnInHand(me);
            for (Card t : attackers) {
                boolean free = unblockable(t, op);
                List<Step> avail = pumps(me, t, false);
                if (avail.isEmpty()) continue;
                Plan p = plan(me, t, avail, mana, !free);
                if (p == null || p.steps.isEmpty()) continue;
                long dmg = p.power + other - (free ? t.getNetPower() : 0);
                if (dmg < op.getLife() && burnLater && mana >= 2) {
                    // attack, then Burn Together (R) to the face with the same pumped creature: its power counts twice
                    Plan q = plan(me, t, avail, mana - 1, !free);
                    if (q != null && !q.steps.isEmpty() && q.power + other - (free ? t.getNetPower() : 0) + q.power >= op.getLife()) {
                        p = q;
                        dmg = op.getLife();
                        ControlBrain.count("leyline_burn_planned");
                    }
                }
                if (dmg < op.getLife()) continue;
                ControlBrain.count("leyline_main1");
                planned.clear();
                planned.add(t);
                for (Card c : attackers) if (unblockable(c, op)) planned.add(c);
                plannedGame = g;
                plannedTurn = g.getPhaseHandler().getTurn();
                StringBuilder b = new StringBuilder("leyline plan T" + plannedTurn + " on " + t.getName() + " pow " + t.getNetPower()
                        + " -> " + p.power + " +other " + (other - (free ? t.getNetPower() : 0)) + " vs life " + op.getLife() + " mana " + mana + ":");
                for (Step st : p.steps) b.append(' ').append(st.name);
                ControlBrain.snapshot(me, b.toString());
                return next(p, t);
            }
            return AiTweaks.TWEAKS.contains("leylinespend") ? spend(me, op, ph, mana) : null;
        }
        if (ph == PhaseType.COMBAT_DECLARE_BLOCKERS) {
            Combat cb = g.getCombat();
            if (cb == null) return null;
            long other = 0;
            List<Card> open = new ArrayList<>();
            for (Card a : cb.getAttackers())
                if (a.getController() == me && !cb.isBlocked(a)) {
                    open.add(a);
                    other += a.getNetPower();
                }
            for (Card t : open) {
                List<Step> avail = pumps(me, t, true);
                if (avail.isEmpty()) continue;
                Plan p = plan(me, t, avail, mana, false);
                if (p == null || p.steps.isEmpty()) continue;
                if (p.power + other - t.getNetPower() < op.getLife()) continue;
                ControlBrain.count("leyline_blockers");
                return next(p, t);
            }
            if (AiTweaks.TWEAKS.contains("leylinespend")) return spend(me, op, ph, mana);
        }
        return null;
    }

    /**
     * Tweak "leylinemull[A-D]" (the user's rule, 2026-10-10, and variants): each mulligan drops the strictest
     * requirement. A (default): 7 cards -> Leyline of Resonance + a creature + a land; 6 -> a creature + a land;
     * 5 -> a land. B (literal): 7 -> Leyline; 6 -> a creature; 5 -> a land. C: 7 -> Leyline + a land, then as A.
     * D: as A with two lands. After three mulligans any hand is kept. S: the scored optimal strategy (MULL_T below).
     * (measure-leyline-brain: a Leyline in the opening hand was worth +11 to +25 points per matchup.)
     */
    static boolean keepHand(Player me, int mulligans) {
        boolean ley = false, body = false;
        int lands = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (named(c, LEYLINE)) ley = true;
            if (c.isCreature()) body = true;
            if (c.isLand()) lands++;
        }
        char v = mullVariant();
        boolean keep;
        if (v == 'S') {
            // Forge bottoms the London mulligan cards (tuck, below) BEFORE asking to keep: the hand may already hold
            // 7 - mulligans cards; only cards still to bottom are chosen here
            List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
            double bs = bestSub(hand, Math.max(0, hand.size() - Math.max(1, 7 - mulligans)));
            keep = mulligans >= MULL_T.length || bs >= MULL_T[mulligans];
            if (keep) ControlBrain.count("leyline_mullS_kept_score_" + bs);
        }
        else if (mulligans >= 3) keep = true;
        else switch (v) {
            case 'B':   // literal: one requirement per hand
                keep = mulligans == 0 ? ley : mulligans == 1 ? body : lands >= 1;
                break;
            case 'C':   // 7: Leyline + land; 6: creature + land; 5: land
                keep = lands >= 1 && (mulligans >= 2 || (mulligans == 1 ? body : ley));
                break;
            case 'D':   // as A with two lands
                keep = lands >= 2 && (mulligans >= 2 || (body && (mulligans >= 1 || ley)));
                break;
            default:    // 'A' cumulative: 7: Leyline + creature + land; 6: creature + land; 5: land
                keep = lands >= 1 && (mulligans >= 2 || (body && (mulligans >= 1 || ley)));
        }
        ControlBrain.count("leyline_mull" + v + "_" + mulligans + (keep ? "_keep" : "_mull"));
        return keep;
    }

    /** The variant letter of tweak "leylinemullX" (A when no letter follows). */
    static char mullVariant() {
        String t = AiTweaks.TWEAKS;
        int i = t.indexOf("leylinemull");
        if (i < 0) return 'A';
        int j = i + "leylinemull".length();
        return j < t.length() && Character.isUpperCase(t.charAt(j)) ? t.charAt(j) : 'A';
    }

    /*
     * Variant S: the exact optimal strategy for the 10-criteria hand score (goldfish/mull_opt.py, verified end to end by
     * goldfish/mull_sim.c). After k mulligans keep when the best (7-k)-card sub-hand scores at least MULL_T[k], the
     * expected final score of continuing; London bottoms keep that best sub-hand.
     */
    static final double[] MULL_T = {10.475578553519178, 9.43339652188486, 7.91923995242128, 5.740861813707959,
            2.9439575698608795, -0.7929538950237297};

    static double handScore(List<Card> h) {
        int lands = 0, cre = 0, pumps = 0, dual = 0, pas = 0, ver = 0, mtn = 0;
        boolean ley = false, one = false, show = false, can = false, prot = false;
        for (Card c : h) {
            switch (c.getName()) {
                case "Steam Vents": case "Spirebluff Canal": case "Starting Town": lands++; dual++; break;
                case "Multiversal Passage": lands++; pas++; break;
                case "Riverpyre Verge": lands++; ver++; break;
                case "Mountain": lands++; mtn++; break;
                case LEYLINE: ley = true; break;
                case SHOWOFF: cre++; show = true; break;
                case "Stadium Headliner": case OTTER: cre++; one = true; break;
                case "Drake Hatcher": cre++; break;
                case ANGER: case PYM: case "Might of the Meek": pumps++; can = true; break;
                case "Octopus Form": case "Shore Up": pumps++; prot = true; break;
                case "Wild Ride": case "Turn Inside Out": case BULK: case SECRET: pumps++; break;
                default: break;
            }
        }
        double s = 0;
        s += ley ? 3 : 0;
        s += lands == 2 || lands == 3 ? 3 : lands == 4 ? 1 : lands == 1 ? 0 : -4;
        boolean both = dual > 0 || (pas + ver + mtn >= 2 && (pas > 0 || (ver > 0 && mtn > 0)));
        s += both ? 1.5 : 0;
        s += one ? 2 : 0;
        s += show ? 2 : 0;
        s += cre >= 2 ? 1 : 0;
        s += Math.min(pumps, 3) - Math.max(0, pumps - 4);
        s += can ? 1 : 0;
        s += prot ? 0.5 : 0;
        s -= cre == 0 ? 2 : 0;
        return s;
    }

    /** Best score of a sub-hand after bottoming K cards; also the bottom set (as a bitmask over H) in BEST_MASK. */
    static int bestMask;
    static double bestSub(List<Card> h, int k) {
        double best = -1e9;
        bestMask = 0;
        int n = h.size();
        for (int m = 0; m < (1 << n); m++) {
            if (Integer.bitCount(m) != k) continue;
            List<Card> keep = new ArrayList<>();
            for (int i = 0; i < n; i++) if ((m >> i & 1) == 0) keep.add(h.get(i));
            double sc = handScore(keep);
            if (sc > best) { best = sc; bestMask = m; }
        }
        return best;
    }

    /** London mulligan bottom: Forge's choice, but never a Leyline of Resonance, nor the last creature or last land. */
    static forge.game.card.CardCollectionView tuck(forge.game.card.CardCollectionView hand,
                                                  forge.game.card.CardCollectionView chosen, int n) {
        if (mullVariant() == 'S') {   // bottom exactly the cards outside the best-scoring sub-hand
            List<Card> h = new ArrayList<>(hand);
            bestSub(h, n);
            CardCollection out = new CardCollection();
            for (int i = 0; i < h.size(); i++) if ((bestMask >> i & 1) == 1) out.add(h.get(i));
            ControlBrain.count("leyline_tuck_scored");
            return out;
        }
        CardCollection out = new CardCollection(chosen);
        CardCollection kept = new CardCollection(hand);
        kept.removeAll(out);
        java.util.function.Predicate<Card> protectedCard = c -> named(c, LEYLINE)
                || (c.isCreature() && kept.stream().filter(Card::isCreature).count() <= 1)
                || (c.isLand() && kept.stream().filter(Card::isLand).count() <= 1);
        for (Card c : new ArrayList<>(out)) {
            kept.add(c);   // as if it stayed: is it the one we must keep?
            boolean must = named(c, LEYLINE) || (c.isCreature() && kept.stream().filter(Card::isCreature).count() <= 1)
                    || (c.isLand() && kept.stream().filter(Card::isLand).count() <= 1);
            kept.remove(c);
            if (!must) continue;
            Card swap = null;   // the most expensive kept card that is not itself needed
            for (Card k : kept) {
                if (protectedCard.test(k)) continue;
                if (swap == null || k.getCMC() > swap.getCMC()) swap = k;
            }
            if (swap == null) continue;
            out.remove(c); out.add(swap);
            kept.remove(swap); kept.add(c);
            ControlBrain.count("leyline_tuck_swap");
        }
        return out;
    }

    static boolean burnInHand(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, SELLSWORD)) return true;
        return false;
    }

    /**
     * Post-filter of Forge's own choice (tweaks leyburn, leyland).
     * leyburn: never Burn Together (it sacrifices the creature) on our own creature in our main phase 1: the pumped
     * attacker should attack first (measure leylinec9961-spend-mullS, Slivers game 427: an 8-power Show-Off sacrificed
     * to kill a 2/1 when attack + Burn Together to the face was lethal).
     * leyland: when Forge plays a land, play instead the land that lets the most cheap creatures in hand be cast this
     * turn (v2.3 game 909: Mountain on turn 1 with Elusive Otter in hand and Spirebluff Canal available).
     */
    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty() || !deck(me)) return ai;
        Game g = me.getGame();
        boolean mine = g.getPhaseHandler().isPlayerTurn(me);
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (AiTweaks.TWEAKS.contains("leyburn") && mine && ph == PhaseType.MAIN1)
            for (SpellAbility sa : ai)
                if (sa.getHostCard() != null && named(sa.getHostCard(), SELLSWORD) && sa.isAdventure()) {
                    ControlBrain.count("leyline_burn_veto");
                    return null;
                }
        if (AiTweaks.TWEAKS.contains("leyland") && mine && ai.size() == 1 && ai.get(0).isLandAbility()) {
            SpellAbility better = betterLand(me, ai.get(0));
            if (better != null) {
                ControlBrain.count("leyline_land_swap");
                return List.of(better);
            }
        }
        return ai;
    }

    /** Colours a land can add this turn if played now ("" if it enters tapped); C counts as generic. */
    static String landColours(Player me, Card land, boolean entering) {
        int lands = (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).count();
        boolean islandOrMountain = me.getCardsIn(ZoneType.Battlefield).stream()
                .anyMatch(c -> c.isLand() && (c.getType().hasSubtype("Island") || c.getType().hasSubtype("Mountain")));
        switch (land.getName()) {
            case "Spirebluff Canal": return entering && lands > 2 ? "" : "UR";
            case "Starting Town": return entering && me.getGame().getPhaseHandler().getTurn() > 6 ? "" : "UR";
            case "Riverpyre Verge": return islandOrMountain ? "UR" : "R";
            case "Mountain": return "R";
            case "Steam Vents": return "UR";            // entering: Forge decides whether to pay 2 life; assume it does
            case "Multiversal Passage": return entering ? "" : "UR";   // assume tapped when played (may cost 2 life)
            default: return "C";
        }
    }

    /** How many creature cards in hand could be cast this turn with these mana colours (one string per land). */
    static int castableCreatures(Player me, List<String> pool) {
        int n = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (!c.isCreature()) continue;
            int cmc = (int) c.getCMC();
            if (cmc > pool.size()) continue;
            String cost = c.getManaCost().getShortString();
            boolean ok = true;
            List<String> left = new ArrayList<>(pool);
            for (char col : new char[]{'U', 'R'}) {
                long need = cost.chars().filter(x -> x == col).count();
                for (int i = 0; i < need && ok; i++) {
                    String src = null;
                    for (String p : left) if (p.indexOf(col) >= 0 && (src == null || p.length() < src.length())) src = p;
                    if (src == null) ok = false; else left.remove(src);
                }
            }
            if (ok && cost.indexOf('B') < 0 && cost.indexOf('G') < 0 && cost.indexOf('W') < 0) n++;
        }
        return n;
    }

    static SpellAbility betterLand(Player me, SpellAbility chosen) {
        List<String> base = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isLand() && c.isUntapped()) base.add(landColours(me, c, false));
        base.removeIf(String::isEmpty);
        java.util.function.Function<Card, Integer> value = land -> {
            List<String> pool = new ArrayList<>(base);
            String col = landColours(me, land, true);
            if (!col.isEmpty()) pool.add(col);
            return castableCreatures(me, pool);
        };
        int have = value.apply(chosen.getHostCard());
        SpellAbility best = null;
        int bestV = have;
        CardCollection lands = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isLand()) lands.add(c);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(lands, me)) {
            if (!sa.isLandAbility()) continue;
            sa.setActivatingPlayer(me);
            try { if (!sa.canPlay()) continue; } catch (Exception e) { continue; }
            int v = value.apply(sa.getHostCard());
            if (v > bestV) { bestV = v; best = sa; }
        }
        return best;
    }

    /** Elusive Otter with POWER: no untapped creature of theirs that could block it has power >= POWER. */
    static boolean otterFree(Card otter, Player op, long power) {
        for (Card b : op.getCreaturesInPlay()) {
            if (b.isTapped()) continue;
            boolean can;
            try { can = CombatUtil.canBlock(otter, b); } catch (Exception e) { can = true; }
            if (can && b.getNetPower() >= power) return false;
        }
        return true;
    }

    /**
     * Tweak leydig: main 2, a land-less hand and mana to spare: a cantrip pump (Pym Particles, Ancestral Anger, Might of
     * the Meek) on any creature of ours, to dig for lands and threats (v2.3 game 909: three idle turns on 3 lands holding
     * two Might of the Meek and an Ancestral Anger).
     */
    static SpellAbility dig(Player me, int mana) {
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isLand()) return null;
        int reserve = Integer.MAX_VALUE;
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isCreature()) reserve = Math.min(reserve, (int) c.getCMC());
        if (reserve == Integer.MAX_VALUE || reserve > mana) reserve = 0;
        if (mana - reserve <= 0) return null;
        for (Card t : me.getCreaturesInPlay())
            for (Step st : pumps(me, t, false)) {
                if (!(st.name.equals(PYM) || st.name.equals(ANGER) || st.name.equals("Might of the Meek"))) continue;
                if (st.cost > mana - reserve) continue;
                ControlBrain.count("leyline_dig");
                st.sa.resetTargets();
                st.sa.getTargets().add(t);
                return st.sa;
            }
        return null;
    }

    static final java.util.Set<String> SPEND_MAIN1 = java.util.Set.of(PYM, ANGER, "Wild Ride"),
            SPEND_COMBAT = java.util.Set.of("Turn Inside Out", "Might of the Meek", BULK);

    /** Non-lethal turns: one damage pump on an evasive/unblocked attacker, paid from surplus mana only. */
    static SpellAbility spend(Player me, Player op, PhaseType ph, int mana) {
        int reserve = Integer.MAX_VALUE;
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isCreature()) reserve = Math.min(reserve, (int) c.getCMC());
        if (reserve == Integer.MAX_VALUE || reserve > mana) reserve = 0;   // nothing castable to keep mana for
        int surplus = mana - reserve;
        if (surplus <= 0) return null;
        List<Card> targets = new ArrayList<>();
        Game g = me.getGame();
        if (ph == PhaseType.MAIN1) {
            for (Card c : me.getCreaturesInPlay()) if (canAttackNow(c)) targets.add(c);
        } else {
            Combat cb = g.getCombat();
            if (cb == null) return null;
            for (Card a : cb.getAttackers()) if (a.getController() == me && !cb.isBlocked(a)) targets.add(a);
        }
        targets.sort((x, y) -> Boolean.compare(named(y, SHOWOFF), named(x, SHOWOFF)) != 0
                ? Boolean.compare(named(y, SHOWOFF), named(x, SHOWOFF)) : Integer.compare(y.getNetPower(), x.getNetPower()));
        for (Card t : targets) {
            boolean free = ph != PhaseType.MAIN1 || unblockable(t, op);
            for (Step st : pumps(me, t, ph != PhaseType.MAIN1)) {
                if (st.cost > surplus) continue;
                if (ph == PhaseType.MAIN1) {
                    boolean otterPump = false;
                    if (!free && named(t, OTTER) && AiTweaks.TWEAKS.contains("leyotter") && !st.name.equals(PYM)
                            && (SPEND_MAIN1.contains(st.name) || SPEND_COMBAT.contains(st.name))) {
                        // Elusive Otter: creatures with less power can't block it, and it has prowess, so the pump
                        // itself can be the evasion (v2.3 games 33, 909: it waited at home holding pumps)
                        int apps = 1 + (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> named(c, LEYLINE)).count();
                        long pw = t.getNetPower() + 1;   // prowess: the cast (Leyline copies are not cast)
                        pw = st.name.equals(BULK) ? pw * (1L << apps) : pw + (long) add(st.name, me) * apps;
                        otterPump = otterFree(t, op, pw);
                        if (otterPump) ControlBrain.count("leyline_otter_pump");
                    }
                    if (!otterPump) {
                        if (!SPEND_MAIN1.contains(st.name)) continue;
                        if (st.name.equals(PYM) == free) continue;   // Pym only where it makes the attacker unblockable
                    }
                    if (plannedGame != g || plannedTurn != g.getPhaseHandler().getTurn()) planned.clear();
                    planned.add(t);   // forceAttack: the pumped creature attacks
                    plannedGame = g;
                    plannedTurn = g.getPhaseHandler().getTurn();
                } else if (!SPEND_COMBAT.contains(st.name)) continue;
                if (st.name.equals(BULK) && t.getNetPower() < 2) continue;
                ControlBrain.count(ph == PhaseType.MAIN1 ? "leyline_spend_main1" : "leyline_spend_combat");
                st.sa.resetTargets();
                st.sa.getTargets().add(t);
                return st.sa;
            }
        }
        return null;
    }

    /** Main 2: Burn Together on our most powerful creature when its power is lethal. */
    static SpellAbility burn(Player me, Player op) {
        Card best = null;
        for (Card c : me.getCreaturesInPlay()) if (best == null || c.getNetPower() > best.getNetPower()) best = c;
        if (best == null || best.getNetPower() < op.getLife()) return null;
        CardCollection hand = new CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, SELLSWORD)) hand.add(c);
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell() || !sa.usesTargeting() || sa.getSubAbility() == null) continue;
            sa.setActivatingPlayer(me);
            try {
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
            } catch (Exception e) {
                continue;
            }
            if (!sa.canTarget(best)) continue;
            sa.resetTargets();
            sa.getTargets().add(best);
            SpellAbility dmg = sa.getSubAbility();
            dmg.resetTargets();
            dmg.getTargets().add(op);
            ControlBrain.count("leyline_burn");
            return sa;
        }
        return null;
    }
}
