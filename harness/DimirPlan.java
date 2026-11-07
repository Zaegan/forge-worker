import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * dimirplan@NAME: Dimir Midrange's strategic turn planner against Forest decks (user, 2026-11-07: "develop a
 * first-principles strategic turn planner tailored to that deck"; "you could have the planner only activate if the
 * opponent has or mostly-essentially has forests"). Built from the dps1 study (pilot/reports/dps1-A/spec.md,
 * dps1-B/spec.md: 18 games vs Mono-Green Landfall): Dimir lost on stance, not single misplays -- it tapped out at
 * sorcery speed into Harmonizer boards where life does not matter, and sat passive when the opponent had nothing.
 *
 * Gate: the opponent controls a basic Forest and Forests are at least half of its lands. Before that, and against
 * every other deck, nothing here runs (Spellementals, 4c, Prowess, Lifegain, Dwarves, Azorius, Dragons, Slivers: none
 * play Forests; Landfall and v2.3 do).
 *
 * Every decision sees only Dimir's information: our hand, the board, graveyards, life, the opponent's hand size, lands
 * and open mana. Quantities (study A, section 0):
 *   L      landfalls the opponent can make next turn: 1 + (hand >= 1) + Icetill Explorers + uncracked fetch lands
 *   D      Mightform Harmonizers with their abilities (not blanked by our Tidebinder or Wasp)
 *   burst  their next attack against our blockers: powers + Chocobo growth (L each) + Harmonizer doubling of the biggest
 *          attacker (x 2^(L*D)); with no Harmonizer out, a warped one is assumed when they hold a card and will have 3
 *          mana (x 2^L); Escape Tunnel visible: the doubled creature is a small unblockable one. Our blockers (untapped
 *          creatures + flash creatures castable with the mana we keep) chump the biggest blockable attackers.
 *   clock  our turns to kill: their life / damage our evasive creatures and unblockable ground creatures deal per turn.
 * Stance, first that applies: LETHAL (dimirlethal attacks) > STABILISE (burst kills us, or an active Harmonizer with no
 * answer in hand) > RACE (our clock <= theirs) > PRESSURE (their board is small and has no engine) > HOLD.
 * Actions:
 *   - answers first (any stance, when we have priority): Tidebinder on a Harmonizer/Chocobo/Explorer trigger on the
 *     stack; We Say Thee Nay! / Spell Pierce on Ascension, Nursery, Harmonizer, Explorer when they can't pay the tax;
 *     Requiting Hex on Sazh's Chocobo before it grows.
 *   - our turn, STABILISE/HOLD/PRESSURE: no sorcery-speed play that leaves less than the reserve for their turn
 *     (Tidebinder 3 while a Harmonizer can come down, Nay 2 while Ascension/Nursery can, Curiosity 4 while a Harmonizer
 *     is active); the vetoed play becomes the land drop or a cheap play within the budget. Kaito is hard-cast only when
 *     nothing they show can kill him, ninjutsu never under STABILISE nor by returning a Tidebinder/Wasp lock; creature
 *     lands are animated only to race.
 *   - their turn: Tidebinder is kept for a trigger worth it (never Ascension/Nursery) until their end step; Drowner/Wasp
 *     are not spent on an Elf-only board before their end step.
 *   - targets: stun/ability loss (Drowner, Wasp) and removal on the biggest threat: Harmonizer > Explorer > Chocobo >
 *     power (never an earthbent land: noearthbent).
 *   - combat: STABILISE keeps a blocker home per attacker of theirs with power >= 2; PRESSURE/RACE add safe attackers;
 *     no attack into two or more untapped blockers that kill it unless lethal. Blocks: chump when the damage left
 *     unblocked puts us at or below their next-turn burst.
 * Counters: dimirplan_* (ControlBrain.count).
 */
public class DimirPlan {
    static boolean on(Player p) { return AiTweaks.on("dimirplan", p); }

    static final String HARM = "Mightform Harmonizer", CHOCO = "Sazh's Chocobo", EXPL = "Icetill Explorer", ASC = "Earthbender Ascension",
            NURS = "Sapling Nursery", TUNNEL = "Escape Tunnel", TIDE = "Tishana's Tidebinder", WASP = "The Wondrous Wasp",
            DROWN = "Floodpits Drowner", CURIO = "Enduring Curiosity", NAY = "We Say Thee Nay!", PIERCE = "Spell Pierce",
            HEX = "Requiting Hex", KAITO = "Kaito, Bane of Nightmares", JACE = "The Theorist, Jace Beleren";
    static final Set<String> FETCH = Set.of("Fabled Passage", "Escape Tunnel", "Elven Passage", "Evolving Wilds");
    static final Set<String> COUNTERWORTHY = Set.of(ASC, NURS, HARM, EXPL);
    static final Set<String> MANLANDS = Set.of("Restless Reef", "Soulstone Sanctuary", "Restless Anchorage");

    enum Stance { LETHAL, STABILISE, RACE, PRESSURE, HOLD }

    /* ---------------- the gate ---------------- */

    static boolean forests(Player op) {
        int lands = 0, forest = 0, basic = 0;
        for (Card c : op.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isLand()) continue;
            lands++;
            if (c.getType().hasSubtype("Forest")) { forest++; if (c.getType().isBasicLand()) basic++; }
        }
        return basic >= 1 && 2 * forest >= lands;
    }

    static boolean active(Player me) {
        if (!on(me)) return false;
        Player op = ProwessBrain.opponent(me);
        return op != null && forests(op);
    }

    /* ---------------- the model ---------------- */

    static boolean hasTriggers(Card c) { return !c.getTriggers().isEmpty(); }
    static boolean named(Card c, String n) { return c.getName().equals(n); }

    static int harmonizers(Player op) {
        return (int) op.getCreaturesInPlay().stream().filter(c -> named(c, HARM) && hasTriggers(c)).count();
    }

    static int landfalls(Player op) {
        int l = 1 + (op.getCardsIn(ZoneType.Hand).isEmpty() ? 0 : 1);
        boolean gyFetch = op.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c -> FETCH.contains(c.getName()));
        for (Card c : op.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && FETCH.contains(c.getName())) l++;
            if (named(c, EXPL) && hasTriggers(c)) l += gyFetch ? 2 : 1;
        }
        return Math.min(l, 6);
    }

    /** the opponent's mana next turn (lands + 1 drop + mana creatures) */
    static int theirManaNext(Player op) {
        int m = 0;
        for (Card c : op.getCardsIn(ZoneType.Battlefield))
            if (c.isLand() || c.isCreature() && !c.getManaAbilities().isEmpty()) m++;
        return m + (op.getCardsIn(ZoneType.Hand).isEmpty() ? 0 : 1);
    }

    /** the opponent's mana now: untapped lands and untapped, unsick mana creatures */
    static int theirOpen(Player op) {
        int m = 0;
        for (Card c : op.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isUntapped() || c.getManaAbilities().isEmpty()) continue;
            if (c.isCreature() && !c.isLand() && c.isSick()) continue;
            m++;
        }
        return m;
    }

    static int power(Card c) { return Math.max(0, c.getNetCombatDamage()); }

    /** the damage their next attack deals through our blockers (BLOCKERS of them), with or without the burst */
    static int incoming(Player me, Player op, int blockers, boolean burst) {
        List<int[]> att = new ArrayList<>();   // {power, unblockable}
        int L = landfalls(op), D = harmonizers(op);
        for (Card c : op.getCreaturesInPlay()) {
            if (c.hasKeyword(Keyword.DEFENDER) || c.getNetPower() <= 0 && !(burst && named(c, CHOCO) && hasTriggers(c))) continue;
            int p = power(c);
            if (burst && named(c, CHOCO) && hasTriggers(c)) p += L;
            att.add(new int[]{p, 0});
        }
        if (att.isEmpty()) return 0;
        if (burst) {
            boolean tunnel = op.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> named(c, TUNNEL));
            int doublings = D > 0 ? L * D : !op.getCardsIn(ZoneType.Hand).isEmpty() && theirManaNext(op) >= 3 ? L : 0;
            if (doublings > 0) {
                int[] t = null;
                if (tunnel) for (int[] a : att) if (a[0] <= 2 && (t == null || a[0] > t[0])) t = a;
                if (t == null) for (int[] a : att) if (t == null || a[0] > t[0]) t = a;
                if (t == null || t[0] == 0) t = att.get(0);
                if (tunnel && t[0] <= 2) t[1] = 1;
                long p = Math.max(1, t[0]);
                for (int i = 0; i < doublings && p < 1000; i++) p *= 2;
                t[0] = (int) Math.min(p, 1000);
            }
            if (op.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> named(c, ASC) && c.getCounters(forge.game.card.CounterEnumType.QUEST) + L >= 4)) {
                int[] t = att.get(0);
                for (int[] a : att) if (a[0] > t[0]) t = a;
                t[0] += L;
            }
        }
        att.sort((x, y) -> Integer.compare(y[0], x[0]));
        int dmg = 0, b = blockers;
        for (int[] a : att) {
            if (a[1] == 0 && b > 0) { b--; continue; }
            dmg += a[0];
        }
        return dmg;
    }

    static int blockersNext(Player me, int keepMana) {
        int n = 0;
        for (Card c : me.getCreaturesInPlay()) if (!c.hasKeyword(Keyword.DEFENDER)) n++;
        // flash creatures in hand castable with the mana kept for their turn
        int mana = keepMana;
        List<Integer> costs = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isCreature() && c.hasKeyword(Keyword.FLASH)) costs.add(Math.max(2, c.getCMC()));
        costs.sort(null);
        for (int k : costs) if (k <= mana) { mana -= k; n++; }
        return n;
    }

    /** our turns to kill: their life / the damage per turn that gets through their untapped creatures */
    static int ourClock(Player me, Player op) {
        boolean airBlock = op.getCreaturesInPlay().stream().anyMatch(c -> c.isUntapped() && (c.hasKeyword(Keyword.REACH) || c.hasKeyword(Keyword.FLYING)));
        int ground = (int) op.getCreaturesInPlay().stream().filter(Card::isUntapped).count();
        int dmg = 0;
        List<Integer> walkers = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (c.getNetPower() <= 0 || c.hasKeyword(Keyword.DEFENDER)) continue;
            if (c.hasKeyword(Keyword.FLYING) && !airBlock) dmg += c.getNetPower();
            else walkers.add(c.getNetPower());
        }
        walkers.sort(java.util.Collections.reverseOrder());
        for (int i = ground; i < walkers.size(); i++) dmg += walkers.get(i);
        if (dmg <= 0) return 99;
        return (op.getLife() + dmg - 1) / dmg;
    }

    static boolean answerInHand(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (named(c, TIDE) || named(c, WASP) || named(c, "Bitter Triumph") || named(c, "Shoot the Sheriff") || named(c, KAITO)) return true;
        return false;
    }

    static int lastTurn = -1;

    static Stance stance(Player me, Player op) {
        int life = me.getLife();
        int keep = ProwessBrain.mana(me);
        int burst = incoming(me, op, blockersNext(me, keep), true);
        int base = incoming(me, op, blockersNext(me, keep), false);
        int survBurst = burst <= 0 ? 99 : burst >= life ? 1 : (life + burst - 1) / burst;
        int clock = ourClock(me, op);
        Stance s;
        if (clock <= 1) s = Stance.LETHAL;
        else if (burst >= life || harmonizers(op) >= 1 && !answerInHand(me)) s = Stance.STABILISE;
        else if (clock <= survBurst) s = Stance.RACE;
        else if (MetaBrain2.boardPower(op) <= 3 && harmonizers(op) == 0
                && op.getCreaturesInPlay().stream().noneMatch(c -> named(c, CHOCO) && c.getNetPower() > 2)
                && op.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> named(c, ASC) && c.getCounters(forge.game.card.CounterEnumType.QUEST) >= 3))
            s = Stance.PRESSURE;
        else s = Stance.HOLD;
        int turn = me.getGame().getPhaseHandler().getTurn();
        if (turn != lastTurn && MetaBrain2.ourTurn(me)) { lastTurn = turn; ControlBrain.count("dimirplan_stance_" + s); }
        if (base < 0) ControlBrain.count("dimirplan_never");
        return s;
    }

    /** mana to keep for the opponent's next turn (0: none) */
    static int reserve(Player me, Player op, Stance s) {
        if (s == Stance.LETHAL || s == Stance.RACE || op.getCardsIn(ZoneType.Hand).isEmpty() && harmonizers(op) == 0) return 0;
        int r = 0;
        boolean tide = hand(me, TIDE), nay = hand(me, NAY), curio = hand(me, CURIO), pierce = hand(me, PIERCE);
        int next = theirManaNext(op);
        if (tide && (harmonizers(op) > 0 || next >= 3)) r = Math.max(r, 3);
        if (curio && s == Stance.STABILISE && harmonizers(op) > 0) r = Math.max(r, 4);
        if (nay && (next >= 4 || op.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).count() >= 3)) r = Math.max(r, 2);
        if (r == 0 && pierce && next >= 3) r = 1;
        return r;
    }

    static boolean hand(Player me, String n) { return me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> named(c, n)); }

    /** the opponent's threat order (study A, section 2) */
    static int threat(Card c) {
        if (!hasTriggers(c) && (named(c, HARM) || named(c, CHOCO) || named(c, EXPL))) return power(c);   // already blanked
        if (named(c, HARM)) return 1000 + power(c);
        if (named(c, EXPL)) return 600 + power(c);
        if (named(c, CHOCO)) return 500 + 10 * power(c);
        if (c.isLand()) return power(c) - 50;   // earthbent: comes back (noearthbent)
        return 10 * power(c) + c.getCMC();
    }

    /* ---------------- answers first ---------------- */

    static SpellAbility choose(Player me) {
        if (!active(me)) return null;
        Player op = ProwessBrain.opponent(me);
        Game g = me.getGame();
        SpellAbility top = g.getStack().isEmpty() ? null : g.getStack().peekAbility();
        if (top != null && top.getActivatingPlayer() == op) {
            Card h = top.getHostCard();
            if (top.isTrigger() && h != null && (named(h, HARM) || named(h, CHOCO) || named(h, EXPL)) && hasTriggers(h)) {
                List<SpellAbility> t = MetaBrain.castableHand(me, c -> named(c, TIDE));
                if (!t.isEmpty()) { tideFor = top; ControlBrain.count("dimirplan_tide_" + h.getName().split(" ")[0]); return t.get(0); }
            }
            if (!top.isSpell() && !top.isTrigger() && h != null && named(h, TUNNEL) && top.getApi() != ApiType.ChangeZone) {   // the unblockable mode
                List<SpellAbility> t = MetaBrain.castableHand(me, c -> named(c, TIDE));
                if (!t.isEmpty()) { tideFor = top; ControlBrain.count("dimirplan_tide_tunnel"); return t.get(0); }
            }
            if (top.isSpell() && h != null && COUNTERWORTHY.contains(h.getName()) && theirOpen(op) < 2) {
                if (!h.isCreature())
                    for (SpellAbility sa : MetaBrain.castableHand(me, c -> named(c, PIERCE)))
                        if (sa.canTarget(top)) { sa.resetTargets(); sa.getTargets().add(top); ControlBrain.count("dimirplan_pierce"); return sa; }
                for (SpellAbility sa : MetaBrain.castableHand(me, c -> named(c, NAY)))
                    if (sa.canTarget(top)) { sa.resetTargets(); sa.getTargets().add(top); ControlBrain.count("dimirplan_nay"); return sa; }
            }
            return null;
        }
        if (top != null) return null;
        for (Card c : op.getCreaturesInPlay()) {
            if (!named(c, CHOCO) || !hasTriggers(c)) continue;
            for (SpellAbility sa : MetaBrain.castableHand(me, x -> named(x, HEX)))
                if (sa.canTarget(c)) { sa.resetTargets(); sa.getTargets().add(c); ControlBrain.count("dimirplan_hexchoco"); return sa; }
        }
        return null;
    }

    static SpellAbility tideFor = null;

    /* ---------------- vetoes and replacements ---------------- */

    static int cost(SpellAbility sa) {
        try { return MetaBrain.cost(sa); } catch (Exception e) { return sa.getHostCard() == null ? 0 : sa.getHostCard().getCMC(); }
    }

    static boolean answer(SpellAbility sa, Player op) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            if (x.getTargets() == null) continue;
            for (Card t : x.getTargets().getTargetCards()) if (t.getController() == op && threat(t) >= 500) return true;
        }
        return false;
    }

    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (!active(me) || ai == null || ai.isEmpty()) return ai;
        Player op = ProwessBrain.opponent(me);
        Game g = me.getGame();
        SpellAbility pick = ai.get(0);
        Card h = pick.getHostCard();
        if (h == null || pick.isManaAbility() || pick.isLandAbility()) return ai;
        boolean ours = MetaBrain2.ourTurn(me);
        PhaseType ph = MetaBrain2.phase(me);
        String n = h.getName();
        retarget(me, op, pick);
        if (!ours) {
            if (pick.isSpell() && named(h, TIDE) && ph != PhaseType.END_OF_TURN && tideFor == null) {
                ControlBrain.count("dimirplan_tide_kept"); return null;
            }
            if (pick.isSpell() && (named(h, DROWN) || named(h, WASP)) && ph != PhaseType.END_OF_TURN && g.getStack().isEmpty()
                    && op.getCreaturesInPlay().stream().allMatch(c -> threat(c) < 30)) {
                ControlBrain.count("dimirplan_stun_kept"); return null;
            }
            return ai;
        }
        Stance s = stance(me, op);
        if (s == Stance.LETHAL || s == Stance.RACE) return ai;
        boolean ninjutsu = !pick.isSpell() && h.isInZone(ZoneType.Hand) && named(h, KAITO);
        if (ninjutsu) {
            Combat cb = g.getCombat();
            boolean lock = cb != null && cb.getAttackers().stream().anyMatch(a -> !cb.isBlocked(a) && (named(a, TIDE) || named(a, WASP)));
            if (s == Stance.STABILISE || lock) { ControlBrain.count("dimirplan_ninjutsu_" + (lock ? "lock" : "stabilise")); return null; }
            return ai;
        }
        boolean animate = MANLANDS.contains(n) && pick.getApi() == ApiType.Animate;
        boolean sorcery = pick.isSpell() && !h.isInstant() && !h.hasKeyword(Keyword.FLASH) || animate || pick.isPwAbility() && false;
        if (!sorcery) {
            if (pick.isSpell() && h.hasKeyword(Keyword.FLASH) && h.isCreature() && g.getStack().isEmpty() && !op.getCardsIn(ZoneType.Hand).isEmpty()
                    && (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2) && s != Stance.PRESSURE) {
                ControlBrain.count("dimirplan_flash_wait"); return fallback(me, op, s, h, 99);
            }
            return ai;
        }
        if (answer(pick, op)) return ai;
        int left = ProwessBrain.mana(me) - cost(pick);
        int r = reserve(me, op, s);
        if (named(h, KAITO) && pick.isSpell() && (s == Stance.STABILISE || harmonizers(op) > 0 || incoming(me, op, 0, true) >= 4)) {
            ControlBrain.count("dimirplan_kaito_held"); return fallback(me, op, s, h, ProwessBrain.mana(me) - r);
        }
        if (named(h, JACE) && pick.isSpell() && (MetaBrain2.boardPower(op) >= 3 || me.getCreaturesInPlay().isEmpty())) {
            ControlBrain.count("dimirplan_jace_held"); return fallback(me, op, s, h, ProwessBrain.mana(me) - r);
        }
        if (animate) { ControlBrain.count("dimirplan_animate_held"); return fallback(me, op, s, h, ProwessBrain.mana(me) - r); }
        if (r > 0 && left < r && cost(pick) > 1) {
            ControlBrain.count("dimirplan_reserve_" + r); return fallback(me, op, s, h, ProwessBrain.mana(me) - r);
        }
        return ai;
    }

    /** instead of a vetoed play: our land drop, else the costliest cheap sorcery-speed play within BUDGET, else pass */
    static List<SpellAbility> fallback(Player me, Player op, Stance s, Card vetoed, int budget) {
        if (me.getLandsPlayedThisTurn() < 1)
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (!c.isLand()) continue;
                for (SpellAbility sa : c.getAllPossibleAbilities(me, true))
                    if (sa.isLandAbility() && sa.canPlay() && me.canPlayLand(c, false, sa)) { ControlBrain.count("dimirplan_fall_land"); return List.of(sa); }
            }
        List<SpellAbility> alt = MetaBrain.castableHand(me, c -> c != vetoed && !c.isLand() && !c.isInstant() && !c.hasKeyword(Keyword.FLASH)
                && !named(c, KAITO) && !named(c, JACE));
        alt.removeIf(sa -> cost(sa) > budget || AiTweaks.targets(sa));
        alt.sort((x, y) -> Integer.compare(cost(y), cost(x)));
        if (!alt.isEmpty()) { ControlBrain.count("dimirplan_fall_alt"); return List.of(alt.get(0)); }
        ControlBrain.count("dimirplan_fall_pass");
        return null;
    }

    /** removal of ours aimed at the opponent's biggest threat it can target */
    static void retarget(Player me, Player op, SpellAbility pick) {
        if (!pick.isSpell()) return;
        for (SpellAbility x = pick; x != null; x = x.getSubAbility()) {
            if (x.getTargets() == null || !(x.getApi() == ApiType.Destroy || x.getApi() == ApiType.ChangeZone)) continue;
            for (Card t : new ArrayList<>(x.getTargets().getTargetCards())) {
                if (t.getController() != op || !t.isCreature()) continue;
                Card best = t;
                for (Card c : op.getCreaturesInPlay()) if (!c.isLand() && threat(c) > threat(best) && x.canTarget(c)) best = c;
                if (best != t) { x.resetTargets(); x.getTargets().add(best); ControlBrain.count("dimirplan_retarget"); }
            }
        }
    }

    /* ---------------- triggers ---------------- */

    static boolean ours(SpellAbility sa) {
        Card h = sa == null ? null : sa.getHostCard();
        return h != null && sa.usesTargeting() && (named(h, DROWN) || named(h, WASP) || named(h, TIDE));
    }

    /** our ETB triggers: Drowner/Wasp on the biggest threat, Tidebinder on the trigger we cast it for (false: Forge's) */
    static boolean target(Player me, SpellAbility sa) {
        if (!active(me) || sa == null || sa.getHostCard() == null || !sa.usesTargeting()) return false;
        Player op = ProwessBrain.opponent(me);
        Card h = sa.getHostCard();
        if (named(h, DROWN) || named(h, WASP)) {
            Card best = null;
            for (Card c : op.getCreaturesInPlay()) if (sa.canTarget(c) && (best == null || threat(c) > threat(best))) best = c;
            if (best == null) return false;
            sa.resetTargets(); sa.getTargets().add(best); ControlBrain.count("dimirplan_stun_target");
            return true;
        }
        if (named(h, TIDE)) {
            SpellAbility want = tideFor;
            tideFor = null;
            if (want == null) {   // the best trigger of theirs on the stack
                for (forge.game.spellability.SpellAbilityStackInstance si : me.getGame().getStack()) {
                    SpellAbility x = si.getSpellAbility();
                    Card xh = x == null ? null : x.getHostCard();
                    if (x != null && x.isTrigger() && x.getActivatingPlayer() == op && xh != null && (named(xh, HARM) || named(xh, CHOCO) || named(xh, EXPL))) { want = x; break; }
                }
            }
            if (want == null || !sa.canTarget(want)) {
                boolean bad = false;   // never Ascension's or Nursery's trigger (no ability loss)
                for (forge.game.spellability.SpellAbilityStackInstance si : me.getGame().getStack()) {
                    SpellAbility x = si.getSpellAbility();
                    if (x != null && x.getHostCard() != null && (named(x.getHostCard(), ASC) || named(x.getHostCard(), NURS))) bad = true;
                }
                if (!bad) return false;
                sa.resetTargets(); ControlBrain.count("dimirplan_tide_none");
                return true;
            }
            sa.resetTargets(); sa.getTargets().add(want); ControlBrain.count("dimirplan_tide_target");
            return true;
        }
        return false;
    }

    /* ---------------- combat ---------------- */

    static void attack(Player me, Combat combat) {
        if (!active(me)) return;
        Player op = ProwessBrain.opponent(me);
        int ours = combat.getAttackers().stream().mapToInt(DimirPlan::power).sum();
        if (ours >= op.getLife()) return;
        Stance s = stance(me, op);
        // no attack into two or more untapped blockers that together kill it
        for (Card a : new ArrayList<>(combat.getAttackers())) {
            int n = 0, pw = 0;
            for (Card b : op.getCreaturesInPlay())
                if (b.isUntapped() && ProwessBrain.canBlock(a, a.getNetPower(), b)) { n++; pw += power(b); }
            if (n >= 2 && pw >= a.getNetToughness() - a.getDamage()) { combat.removeFromCombat(a); ControlBrain.count("dimirplan_attack_gangblock"); }
        }
        if (s == Stance.STABILISE) {
            int need = (int) op.getCreaturesInPlay().stream().filter(c -> power(c) >= 2 || named(c, CHOCO) || named(c, HARM)).count();
            if (harmonizers(op) == 0 && !op.getCardsIn(ZoneType.Hand).isEmpty() && theirManaNext(op) >= 3) need++;
            int home = (int) me.getCreaturesInPlay().stream().filter(c -> !combat.isAttacking(c) || c.hasKeyword(Keyword.VIGILANCE)).count();
            List<Card> att = new ArrayList<>(combat.getAttackers());
            att.removeIf(c -> c.hasKeyword(Keyword.VIGILANCE));
            att.sort((x, y) -> Integer.compare(y.getNetToughness() + y.getNetPower(), x.getNetToughness() + x.getNetPower()));
            for (Card a : att) {
                if (home >= need) break;
                combat.removeFromCombat(a); home++; ControlBrain.count("dimirplan_attack_home");
            }
            return;
        }
        if (s == Stance.PRESSURE || s == Stance.RACE) {
            int theirs = incoming(me, op, 0, true);
            for (Card c : me.getCreaturesInPlay()) {
                if (combat.isAttacking(c) || !LandfallBrain.canAttackThisTurn(c) || !CombatUtil.canAttack(c, op) || c.getNetPower() <= 0) continue;
                boolean safe = true;
                for (Card b : op.getCreaturesInPlay()) {
                    if (!b.isUntapped() || !ProwessBrain.canBlock(c, c.getNetPower(), b)) continue;
                    if (b.getNetPower() >= c.getNetToughness() - c.getDamage() || b.hasKeyword(Keyword.DEATHTOUCH)) { safe = false; break; }
                }
                if (!safe) continue;
                if (s == Stance.PRESSURE && !c.hasKeyword(Keyword.VIGILANCE)) {
                    int home = (int) me.getCreaturesInPlay().stream().filter(x -> x != c && (!combat.isAttacking(x) || x.hasKeyword(Keyword.VIGILANCE))).count();
                    if (incoming(me, op, home, true) >= me.getLife() && theirs > 0) continue;
                }
                combat.addAttacker(c, op);
                ControlBrain.count("dimirplan_attack_add");
            }
        }
    }

    static void block(Player me, Combat combat) {
        if (!active(me) || combat == null) return;
        Player op = ProwessBrain.opponent(me);
        List<Card> open = new ArrayList<>();
        int dmg = 0;
        for (Card a : combat.getAttackers()) if (!combat.isBlocked(a)) { open.add(a); dmg += power(a); }
        if (open.isEmpty()) return;
        int after = me.getLife() - dmg;
        List<Card> free = new ArrayList<>();
        for (Card b : me.getCreaturesInPlay()) if (b.isUntapped() && !combat.isBlocking(b) && CombatUtil.canBlock(b, combat)) free.add(b);
        int next = incoming(me, op, free.size(), true);
        if (after > next && after > 0) return;
        open.sort((x, y) -> Integer.compare(power(y), power(x)));
        free.sort((x, y) -> Integer.compare(MetaBrain2.value(x), MetaBrain2.value(y)));
        for (Card a : open) {
            if (after > 0 && after > incoming(me, op, free.size(), true)) break;
            for (Card b : free) {
                if (!CombatUtil.canBlock(a, b, combat)) continue;
                combat.addBlocker(a, b); free.remove(b); after += power(a);
                ControlBrain.count("dimirplan_block");
                break;
            }
        }
    }
}
