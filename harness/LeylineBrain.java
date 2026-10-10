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
        if (ph == PhaseType.MAIN2) return burn(me, op);
        if (ph == PhaseType.MAIN1) {
            long other = 0;
            List<Card> attackers = new ArrayList<>();
            for (Card c : me.getCreaturesInPlay()) if (canAttackNow(c)) attackers.add(c);
            for (Card c : attackers) if (unblockable(c, op)) other += c.getNetPower();
            for (Card t : attackers) {
                boolean free = unblockable(t, op);
                List<Step> avail = pumps(me, t, false);
                if (avail.isEmpty()) continue;
                Plan p = plan(me, t, avail, mana, !free);
                if (p == null || p.steps.isEmpty()) continue;
                long dmg = p.power + other - (free ? t.getNetPower() : 0);
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
                    if (!SPEND_MAIN1.contains(st.name)) continue;
                    if (st.name.equals(PYM) == free) continue;   // Pym only where it makes the attacker unblockable
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
