import forge.ai.AiController;
import forge.ai.ComputerUtilAbility;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameSimulator;
import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * dimirsearch@NAME: Dimir's search planner (user, 2026-11-07: "the planner is supposed to create a decision tree which
 * determines optimal play from first principles based on the board state and available lands and casts"). Unlike
 * DimirPlan (rules of thumb), nothing here says what a good play is: at each decision it lists our legal lines, plays
 * each one forward in copies of the game, and keeps the line whose futures come out best.
 *
 * Decisions (Forest gate as DimirPlan; at most once per window):
 *   A  our main phase, empty stack, when Forge's pipeline would cast a sorcery-speed spell, or pass with one castable:
 *      lines = Forge's pick, HOLD (land only; no sorcery-speed spell for the rest of the turn), each other castable spell
 *   B  an opponent's spell on top of the stack, when we can cast something: lines = Forge's pick, pass, each castable
 *   C  their declare-attackers step with attackers, D  their end step: lines = Forge's pick, pass, each castable
 * Line value: R futures per line (common random numbers: future r uses the same seed and the same guess of the hidden
 * cards for every line). A future copies the game (Forge's GameCopier), redeals the opponent's hand from its hand and
 * library and shuffles our library (the copy would otherwise know both), forces the line's first action (HOLD: for the
 * rest of the turn), then lets both players' usual pilots play on until the end of the turn after next, or the game's
 * end. Score: win 1, loss 0, else 0.5 + 0.5 tanh(Forge's GameStateEvaluator / K). Forge's pick is kept unless another
 * line beats it by MARGIN. While a future plays, the real game's random generator and the harness's static state are
 * saved and restored, so the real game continues as if nothing had happened.
 * Settings (tweak dimirsearch_r=N, _h=N half-turns, _ms=N per decision, _k=N, _m=N/100): defaults below.
 * Counters: dsearch_*.
 */
public class DimirSearch {
    static boolean on(Player p) { return AiTweaks.on("dimirsearch", p); }

    static int R = 3, H = 3, MS = 12000, K = 400;
    static double MARGIN = 0.08;
    static boolean configured;

    static void configure() {
        if (configured) return;
        configured = true;
        for (String t : AiTweaks.TWEAKS.split(",")) {
            t = t.trim();
            try {
                if (t.startsWith("dimirsearch_r=")) R = Integer.parseInt(t.substring(14));
                if (t.startsWith("dimirsearch_h=")) H = Integer.parseInt(t.substring(14));
                if (t.startsWith("dimirsearch_ms=")) MS = Integer.parseInt(t.substring(15));
                if (t.startsWith("dimirsearch_k=")) K = Integer.parseInt(t.substring(14));
                if (t.startsWith("dimirsearch_m=")) MARGIN = Integer.parseInt(t.substring(14)) / 100.0;
            } catch (NumberFormatException e) { }
        }
    }

    /** games that are futures of ours (never planned in, only played) */
    static final Set<Game> SIMS = Collections.newSetFromMap(new WeakHashMap<>());

    static boolean sim(Game g) { return SIMS.contains(g); }

    /* ---------------- lines ---------------- */

    enum Kind { FORGE, HOLD, PLAY, PASS }

    /** a spell ability by card id and its index among the card's abilities, with its targets by id */
    static final class Ref {
        int card, index;
        List<List<Object>> targets = new ArrayList<>();   // per sub-ability: Integer card id, "P" + player id, "S" + host id
        String name;
    }

    static final class Line {
        Kind kind;
        Ref ref;   // PLAY, and FORGE when Forge's pick is a spell from hand
        double total;
        int n;
        Line(Kind k, Ref r) { kind = k; ref = r; }
        String tag() { return kind == Kind.PLAY ? "PLAY" : kind.name(); }
    }

    /** what a player of a future must do: its first action, and HOLD for the turn */
    static final class Plan {
        Line line;
        boolean first = true;
        int turn;
    }

    static final Map<Game, Map<Integer, Plan>> PLANS = new WeakHashMap<>();
    /** HOLD chosen in the real game: game -> turn */
    static final Map<Game, Integer> HELD = new WeakHashMap<>();
    static final Map<Game, String> DECIDED = new WeakHashMap<>();
    static final Map<Game, int[]> PER_TURN = new WeakHashMap<>();

    static List<SpellAbility> abilities(Card c, Player me) {
        CardCollection cs = new CardCollection();
        cs.add(c);
        return ComputerUtilAbility.getSpellAbilities(cs, me);
    }

    static Ref ref(SpellAbility sa, Player me) {
        Card h = sa.getHostCard();
        if (h == null || !h.isInZone(ZoneType.Hand) || !sa.isSpell()) return null;
        List<SpellAbility> all = abilities(h, me);
        int i = all.indexOf(sa);
        if (i < 0) for (int j = 0; j < all.size(); j++) if (all.get(j).toString().equals(sa.toString())) { i = j; break; }
        if (i < 0) return null;
        Ref r = new Ref();
        r.card = h.getId(); r.index = i; r.name = h.getName();
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            List<Object> t = new ArrayList<>();
            if (x.getTargets() != null)
                for (GameObject o : x.getTargets()) {
                    if (o instanceof Card) t.add(((Card) o).getId());
                    else if (o instanceof Player) t.add("P" + ((Player) o).getId());
                    else if (o instanceof SpellAbility && ((SpellAbility) o).getHostCard() != null) t.add("S" + ((SpellAbility) o).getHostCard().getId());
                }
            r.targets.add(t);
        }
        return r;
    }

    /** the spell ability REF names in ME's game, with its targets (null: gone or not castable) */
    static SpellAbility resolve(Ref r, Player me) {
        Game g = me.getGame();
        Card c = g.findById(r.card);
        if (c == null || !c.isInZone(ZoneType.Hand)) return null;
        List<SpellAbility> all = abilities(c, me);
        if (r.index >= all.size()) return null;
        SpellAbility sa = all.get(r.index);
        sa.setActivatingPlayer(me);
        int k = 0;
        for (SpellAbility x = sa; x != null; x = x.getSubAbility(), k++) {
            if (k >= r.targets.size() || r.targets.get(k).isEmpty() || !x.usesTargeting()) continue;
            x.resetTargets();
            for (Object o : r.targets.get(k)) {
                GameObject t = null;
                if (o instanceof Integer) t = g.findById((Integer) o);
                else if (((String) o).startsWith("P")) t = g.getPlayer(Integer.parseInt(((String) o).substring(1)));
                else {
                    Card host = g.findById(Integer.parseInt(((String) o).substring(1)));
                    if (host != null) t = g.getStack().getSpellMatchingHost(host);
                }
                if (t == null || !x.canTarget(t)) return null;
                x.getTargets().add(t);
            }
        }
        try {
            if (!sa.canPlay() || !forge.ai.ComputerUtilCost.canPayCost(sa, me, false)) return null;
        } catch (Exception e) { return null; }
        return sa;
    }

    /** targets for a line Forge did not pick: Forge's own choice, else the opponent's top spell / biggest threat */
    static boolean prepare(SpellAbility sa, Player me) {
        if (!sa.usesTargeting() && noSubTargets(sa)) return true;
        Player op = ProwessBrain.opponent(me);
        try {
            AiController ai = ((PlayerControllerAi) me.getController()).getAi();
            ai.canPlaySa(sa);
        } catch (Exception e) { }
        if (targetsValid(sa)) return true;
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            if (!x.usesTargeting() || x.isTargetNumberValid()) continue;
            x.resetTargets();
            GameObject best = null;
            if (x.getApi() == ApiType.Counter || x.getApi() == ApiType.ChangeZone && !me.getGame().getStack().isEmpty()) {
                for (SpellAbilityStackInstance si : me.getGame().getStack()) {
                    SpellAbility s = si.getSpellAbility();
                    if (s != null && s.getActivatingPlayer() == op && x.canTarget(s)) { best = s; break; }
                }
            }
            if (best == null) {
                Card b = null;
                for (Card c : op.getCardsIn(ZoneType.Battlefield))
                    if (x.canTarget(c) && (b == null || DimirPlan.threat(c) > DimirPlan.threat(b))) b = c;
                best = b;
            }
            if (best == null && x.canTarget(op)) best = op;
            if (best == null) return false;
            x.getTargets().add(best);
        }
        return targetsValid(sa);
    }

    static boolean noSubTargets(SpellAbility sa) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) if (x.usesTargeting()) return false;
        return true;
    }

    static boolean targetsValid(SpellAbility sa) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility())
            if (x.usesTargeting() && !x.isTargetNumberValid()) return false;
        return true;
    }

    static boolean sorcerySpeed(Card h) { return !h.isInstant() && !h.hasKeyword(Keyword.FLASH); }

    /* ---------------- hooks ---------------- */

    /** before the pipeline: a future's forced first action (null: none; empty: pass) */
    static List<SpellAbility> pre(Player me) {
        Game g = me.getGame();
        if (!sim(g)) return null;
        Map<Integer, Plan> m = PLANS.get(g);
        Plan p = m == null ? null : m.get(me.getId());
        if (p == null || !p.first) return null;
        p.first = false;
        Line l = p.line;
        if (l.kind == Kind.PASS) return List.of();
        if (l.ref == null) return null;   // FORGE with a pick that is not a spell from hand: the pilot plays on
        SpellAbility sa = resolve(l.ref, me);
        if (sa == null && l.kind == Kind.PLAY) {
            sa = resolveFresh(l.ref, me);
        }
        return sa == null ? null : List.of(sa);
    }

    /** a PLAY line's spell, its targets chosen afresh in this game */
    static SpellAbility resolveFresh(Ref r, Player me) {
        Card c = me.getGame().findById(r.card);
        if (c == null || !c.isInZone(ZoneType.Hand)) return null;
        List<SpellAbility> all = abilities(c, me);
        if (r.index >= all.size()) return null;
        SpellAbility sa = all.get(r.index);
        sa.setActivatingPlayer(me);
        try {
            if (!sa.canPlay() || !forge.ai.ComputerUtilCost.canPayCost(sa, me, false)) return null;
        } catch (Exception e) { return null; }
        return prepare(sa, me) ? sa : null;
    }

    /** after the pipeline: HOLD's veto (real game and futures), and the real game's decisions */
    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        int turn = g.getPhaseHandler().getTurn();
        boolean ours = g.getPhaseHandler().isPlayerTurn(me);
        if (sim(g)) {
            Map<Integer, Plan> m = PLANS.get(g);
            Plan p = m == null ? null : m.get(me.getId());
            if (p != null && p.line.kind == Kind.HOLD && p.turn == turn) return hold(me, ai);
            return ai;
        }
        Integer held = HELD.get(g);
        if (held != null && held == turn && ours) return hold(me, ai);
        if (!on(me) || !DimirPlan.forests(ProwessBrain.opponent(me))) return ai;
        configure();
        try {
            return decide(me, ai);
        } catch (RuntimeException e) {
            ControlBrain.count("dsearch_error_" + e.getClass().getSimpleName());
            return ai;
        }
    }

    /** HOLD: a sorcery-speed spell becomes our land drop, else a pass */
    static List<SpellAbility> hold(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty()) return ai;
        SpellAbility s = ai.get(0);
        Card h = s.getHostCard();
        if (h == null || !s.isSpell() || !sorcerySpeed(h)) return ai;
        if (me.getLandsPlayedThisTurn() < 1)
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (!c.isLand()) continue;
                for (SpellAbility sa : c.getAllPossibleAbilities(me, true))
                    if (sa.isLandAbility() && sa.canPlay() && me.canPlayLand(c, false, sa)) return List.of(sa);
            }
        return null;
    }

    static List<SpellAbility> decide(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        PhaseHandler ph = g.getPhaseHandler();
        Player op = ProwessBrain.opponent(me);
        boolean ours = ph.isPlayerTurn(me);
        PhaseType phase = ph.getPhase();
        SpellAbility pick = ai == null || ai.isEmpty() ? null : ai.get(0);
        if (pick != null && (pick.isManaAbility() || pick.isLandAbility())) return ai;
        SpellAbility top = g.getStack().isEmpty() ? null : g.getStack().peekAbility();
        String window = null;
        List<SpellAbility> cast;
        if (top != null) {
            if (top.getActivatingPlayer() != op || !top.isSpell() || top.getHostCard() == null) return ai;
            window = "B" + top.getHostCard().getId() + "/" + g.getStack().size();
            cast = MetaBrain.castableHand(me, c -> !c.isLand() && !sorcerySpeed(c));
        } else if (ours) {
            if (!phase.isMain()) return ai;
            if (pick != null && !(pick.isSpell() && pick.getHostCard() != null && sorcerySpeed(pick.getHostCard()))) return ai;
            cast = MetaBrain.castableHand(me, c -> !c.isLand());
            if (cast.isEmpty()) return ai;
            window = "A" + turn(g) + phase + "/" + ProwessBrain.mana(me) + "/" + me.getCardsIn(ZoneType.Hand).size();
        } else {
            Combat cb = g.getCombat();
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && cb != null && !cb.getAttackers().isEmpty()) window = "C" + turn(g);
            else if (phase == PhaseType.END_OF_TURN) window = "D" + turn(g);
            else return ai;
            cast = MetaBrain.castableHand(me, c -> !c.isLand() && !sorcerySpeed(c));
        }
        if (cast.isEmpty()) return ai;
        if (window.equals(DECIDED.get(g))) return ai;
        DECIDED.put(g, window);
        int[] pt = PER_TURN.computeIfAbsent(g, k -> new int[]{-1, 0});
        if (pt[0] != turn(g)) { pt[0] = turn(g); pt[1] = 0; }
        if (++pt[1] > 4) return ai;

        // the lines
        List<Line> lines = new ArrayList<>();
        Line forge = new Line(pick == null ? Kind.PASS : Kind.FORGE, pick == null ? null : ref(pick, me));   // Forge's pick (a pass, too)
        lines.add(forge);
        if (pick != null && window.startsWith("A")) lines.add(new Line(Kind.HOLD, null));
        if (pick != null && !window.startsWith("A")) lines.add(new Line(Kind.PASS, null));
        Map<String, SpellAbility> byName = new LinkedHashMap<>();
        for (SpellAbility sa : cast) {
            Card h = sa.getHostCard();
            if (h == null || pick != null && pick.getHostCard() != null && h.getName().equals(pick.getHostCard().getName())) continue;
            byName.putIfAbsent(h.getName() + "|" + sa.getDescription(), sa);
        }
        Map<Line, SpellAbility> realSa = new HashMap<>();
        for (SpellAbility sa : byName.values()) {
            if (lines.size() >= 5) break;
            if (!prepare(sa, me)) continue;
            Ref r = ref(sa, me);
            if (r == null) continue;
            Line l = new Line(Kind.PLAY, r);
            lines.add(l);
            realSa.put(l, sa);
        }
        if (lines.size() < 2) return ai;
        ControlBrain.count("dsearch_decide_" + window.charAt(0));

        // the futures
        long t0 = System.nanoTime();
        long seed = 1_000_003L * g.getPhaseHandler().getTurn() + 7919L * window.hashCode();
        int rounds = 0;
        for (int r = 0; r < R; r++) {
            for (Line l : lines) {
                double v = future(g, me, l, seed + r);
                if (Double.isNaN(v)) continue;
                l.total += v; l.n++;
            }
            rounds++;
            if ((System.nanoTime() - t0) / 1_000_000 > MS) break;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        ControlBrain.counts.merge("dsearch_ms", (int) ms, Integer::sum);
        ControlBrain.counts.merge("dsearch_futures", rounds * lines.size(), Integer::sum);
        for (Map.Entry<String, Integer> e : LOCAL.entrySet()) ControlBrain.counts.merge(e.getKey(), e.getValue(), Integer::sum);
        LOCAL.clear();
        if (forge.n == 0) { ControlBrain.count("dsearch_noforge"); return ai; }
        Line best = forge;   // never "passes" over a passing Forge
        double fv = forge.total / forge.n;
        for (Line l : lines) {
            if (l == forge || l.n == 0) continue;
            double v = l.total / l.n;
            if (v > fv + MARGIN && (best == forge || v > best.total / best.n)) best = l;
        }
        ControlBrain.count("dsearch_pick_" + window.charAt(0) + "_" + (best == forge ? "FORGE" : best.tag()));
        if (AiTweaks.on("spelltrace", me)) {   // the spotter's record: every line's value (user 2026-11-07: "better at spotting misplays")
            StringBuilder b = new StringBuilder("@").append(g.getGameLog().getAllEntries().size()).append(" ").append(LandfallBrain.deckTag(me))
                    .append(" T").append(ph.getTurn()).append(" ").append(phase).append(" DSEARCH ").append(window.charAt(0)).append(":");
            for (Line l : lines)
                b.append(" ").append(l == forge ? "FORGE" : l.tag()).append(l.ref == null ? "" : "(" + l.ref.name + ")")
                        .append("=").append(l.n == 0 ? "?" : String.format("%.2f", l.total / l.n)).append("/").append(l.n);
            b.append(" -> ").append(best == forge ? "FORGE" : best.tag() + (best.ref == null ? "" : "(" + best.ref.name + ")"));
            ControlBrain.trace.append(b).append("\n");
        }
        if (best == forge) return ai;
        if (best.kind == Kind.HOLD) {
            HELD.put(g, ph.getTurn());
            return hold(me, ai);
        }
        if (best.kind == Kind.PASS) return null;
        SpellAbility sa = resolve(best.ref, me);
        if (sa == null) sa = realSa.get(best);
        if (sa == null) { ControlBrain.count("dsearch_lost"); return ai; }
        ControlBrain.count("dsearch_play_" + best.ref.name.split("[ ,]")[0]);
        return List.of(sa);
    }

    static int turn(Game g) { return g.getPhaseHandler().getTurn(); }

    /* ---------------- a future ---------------- */

    static final GameStateEvaluator EVAL = new GameStateEvaluator();
    /** counts made inside futures (ControlBrain's are restored after each), merged after a decision */
    static final Map<String, Integer> LOCAL = new HashMap<>();

    static void note(String k) { LOCAL.merge(k, 1, Integer::sum); }

    /** the value of LINE in one future (NaN: the future failed) */
    static double future(Game g, Player me, Line line, long seed) {
        Map<Field, Object> saved = Statics.save();
        Random real = MyRandom.getRandom();
        boolean copyStack = GameSimulator.COPY_STACK;
        try {
            MyRandom.setRandom(new Random(seed));
            GameSimulator.COPY_STACK = true;
            GameCopier gc = new GameCopier(g);
            Game s = gc.makeCopy();
            SIMS.add(s);
            Player sme = s.getPlayer(me.getId()), sop = s.getPlayer(ProwessBrain.opponent(me).getId());
            copyPriority(g, s);
            redeal(sop, sme, new Random(seed * 31 + 17));
            Plan p = new Plan();
            p.line = line; p.turn = s.getPhaseHandler().getTurn();
            PLANS.computeIfAbsent(s, k -> new HashMap<>()).put(sme.getId(), p);
            int end = s.getPhaseHandler().getTurn() + H - 1;
            PhaseHandler sph = s.getPhaseHandler();
            int steps = 0;
            while (!s.isGameOver()) {
                if (sph.getTurn() > end || sph.getTurn() == end && sph.getPhase() == PhaseType.END_OF_TURN) break;
                if (++steps > 4000) { note("dsearch_stepcap"); break; }
                sph.mainLoopStep();
            }
            double v;
            if (s.isGameOver()) v = sme.hasWon() ? 1 : sop.hasWon() ? 0 : 0.5;
            else {
                int sc = EVAL.getScoreForGameState(s, sme).value;
                v = 0.5 + 0.5 * Math.tanh(sc / (double) K);
            }
            PLANS.remove(s);
            SIMS.remove(s);
            return v;
        } catch (Throwable e) {
            note("dsearch_ferr_" + e.getClass().getSimpleName());
            StackTraceElement[] st = e.getStackTrace();
            int k = 0;
            for (int i = 0; i < st.length && k < 6; i++) {
                if (st[i].getClassName().startsWith("com.google") || st[i].getClassName().startsWith("java.")) continue;
                note("dsearch_ferrat" + k++ + "_" + st[i].getClassName().replaceAll(".*\\.", "") + "." + st[i].getMethodName() + ":" + st[i].getLineNumber());
            }
            return Double.NaN;
        } finally {
            MyRandom.setRandom(real);
            GameSimulator.COPY_STACK = copyStack;
            Statics.restore(saved);
        }
    }

    /** the opponent's hand comes from its hand and library at random; our library is shuffled */
    static void redeal(Player op, Player me, Random rnd) {
        List<Card> pool = new ArrayList<>(op.getCardsIn(ZoneType.Hand));
        int n = pool.size();
        pool.addAll(op.getCardsIn(ZoneType.Library));
        Collections.shuffle(pool, rnd);
        op.getZone(ZoneType.Hand).setCards(new ArrayList<>(pool.subList(0, n)));
        op.getZone(ZoneType.Library).setCards(new ArrayList<>(pool.subList(n, pool.size())));
        List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        Collections.shuffle(lib, rnd);
        me.getZone(ZoneType.Library).setCards(lib);
    }

    static Field[] PRIO;

    /** who has priority, who passed first, and whether priority is given, as in the real game */
    static void copyPriority(Game g, Game s) throws ReflectiveOperationException {
        if (PRIO == null) {
            Field a = PhaseHandler.class.getDeclaredField("pPlayerPriority"), b = PhaseHandler.class.getDeclaredField("pFirstPriority"),
                    c = PhaseHandler.class.getDeclaredField("givePriorityToPlayer"), d = PhaseHandler.class.getDeclaredField("nMainsThisTurn"),
                    e = PhaseHandler.class.getDeclaredField("nCombatsThisTurn"), f = PhaseHandler.class.getDeclaredField("playerPreviousTurn");
            for (Field x : new Field[]{a, b, c, d, e, f}) x.setAccessible(true);
            PRIO = new Field[]{a, b, c, d, e, f};
        }
        PhaseHandler o = g.getPhaseHandler(), n = s.getPhaseHandler();
        for (int i = 0; i < 2; i++) {
            Player p = (Player) PRIO[i].get(o);
            PRIO[i].set(n, p == null ? null : s.getPlayer(p.getId()));
        }
        Player prev = (Player) PRIO[5].get(o);
        PRIO[5].set(n, prev == null ? null : s.getPlayer(prev.getId()));
        PRIO[2].setBoolean(n, PRIO[2].getBoolean(o));
        PRIO[3].setInt(n, PRIO[3].getInt(o));
        PRIO[4].setInt(n, PRIO[4].getInt(o));
    }

    /** the harness's static state (per-game memories, counters, traces), saved before a future and restored after */
    static final class Statics {
        static final String[] CLASSES = {"AiTweaks", "AzoriusPlan", "ControlBrain", "DimirPlan", "FetchBrain", "LandColour", "LandOrder",
                "LandfallBrain", "LeylineBrain", "ManaHold", "MetaBrain", "MetaBrain2", "ProtectBrain", "ProwessBrain", "ProwessPlan", "SpellBrain"};
        static List<Field> fields;

        static void collect(Class<?> k, List<Field> out) {
            for (Field f : k.getDeclaredFields()) {
                int m = f.getModifiers();
                if (!Modifier.isStatic(m) || f.isSynthetic()) continue;
                if (Modifier.isFinal(m) && f.getType().isPrimitive()) continue;
                if (f.getType() == String.class && Modifier.isFinal(m)) continue;
                try { f.setAccessible(true); out.add(f); } catch (RuntimeException e) { }
            }
            for (Class<?> c : k.getDeclaredClasses()) collect(c, out);
        }

        static List<Field> fields() {
            if (fields == null) {
                List<Field> out = new ArrayList<>();
                for (String n : CLASSES) {
                    try { collect(Class.forName(n), out); } catch (ClassNotFoundException | LinkageError e) { }
                }
                fields = out;
            }
            return fields;
        }

        @SuppressWarnings("unchecked")
        static Map<Field, Object> save() {
            Map<Field, Object> s = new HashMap<>();
            for (Field f : fields()) {
                try {
                    Object v = f.get(null);
                    if (Modifier.isFinal(f.getModifiers())) {
                        if (v instanceof Map) s.put(f, new LinkedHashMap<>((Map<Object, Object>) v));
                        else if (v instanceof Collection) s.put(f, new ArrayList<>((Collection<Object>) v));
                        else if (v instanceof StringBuilder) s.put(f, v.toString());
                        else if (v instanceof int[]) s.put(f, ((int[]) v).clone());
                    } else s.put(f, v);
                } catch (ReflectiveOperationException | RuntimeException e) { }
            }
            return s;
        }

        @SuppressWarnings("unchecked")
        static void restore(Map<Field, Object> s) {
            for (Map.Entry<Field, Object> e : s.entrySet()) {
                Field f = e.getKey();
                try {
                    if (Modifier.isFinal(f.getModifiers())) {
                        Object cur = f.get(null);
                        if (cur instanceof Map) { ((Map<Object, Object>) cur).clear(); ((Map<Object, Object>) cur).putAll((Map<Object, Object>) e.getValue()); }
                        else if (cur instanceof Collection) { ((Collection<Object>) cur).clear(); ((Collection<Object>) cur).addAll((Collection<Object>) e.getValue()); }
                        else if (cur instanceof StringBuilder) { ((StringBuilder) cur).setLength(0); ((StringBuilder) cur).append((String) e.getValue()); }
                        else if (cur instanceof int[]) System.arraycopy((int[]) e.getValue(), 0, (int[]) cur, 0, ((int[]) cur).length);
                    } else f.set(null, e.getValue());
                } catch (ReflectiveOperationException | RuntimeException x) { }
            }
        }
    }
}
