import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.*;
import forge.game.player.PlayerStatistics;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.view.TimeLimitedCodeBlock;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * Two fixed decks (Forge .dck files) played against each other, AI vs AI, with the same telemetry as ToyGames:
 * organic draws (once per physical card per game), [name, turn drawn, turn cast] per drawn card, winner, how it
 * ended, turns, ms, life, per-game seed. Which deck sits in seat 1 alternates by game (Forge picks who starts).
 *
 * B may be @FIELD.txt (one .dck path per line): the opponent then changes every two games (one game in each seat),
 * cycling through the field; "b" in each line names that game's opponent.
 *
 * GAMES is a count (games 0..GAMES-1) or a comma-separated list of game indices (each game's seed and seats depend
 * only on SEED and its index, so any subset can be played; iterate/select_seeds.py skips games a paired mutant can't
 * change).
 *
 * Usage: java -cp forge.jar:. MatchGames A.dck B.dck|@FIELD.txt OUT.jsonl GAMES|I,J,K.. SEED TIMEOUT_S
 */
public class MatchGames {
    static Deck load(Path p) throws IOException {
        Deck d = new Deck(p.getFileName().toString().replaceAll("\\.dck$", ""));
        boolean main = false;
        for (String line : Files.readAllLines(p)) {
            line = line.trim();
            if (line.startsWith("[")) { main = line.equalsIgnoreCase("[Main]"); continue; }
            Matcher m = Pattern.compile("^(\\d+)\\s+(.+?)(\\|.*)?$").matcher(line);
            if (main && m.matches()) d.getMain().add(m.group(2).trim(), Integer.parseInt(m.group(1)));
        }
        return d;
    }

    /** A deck file's main section as one card name per copy, in file order (a name may appear on several lines). */
    static List<String> fileOrder(Path p) throws IOException {
        List<String> slots = new ArrayList<>();
        boolean main = false;
        for (String line : Files.readAllLines(p)) {
            line = line.trim();
            if (line.startsWith("[")) { main = line.equalsIgnoreCase("[Main]"); continue; }
            Matcher m = Pattern.compile("^(\\d+)\\s+(.+?)(\\|.*)?$").matcher(line);
            if (main && m.matches()) for (int i = Integer.parseInt(m.group(1)); i > 0; i--) slots.add(m.group(2).trim());
        }
        return slots;
    }

    /**
     * Game work, counted from the game itself (2026-10-15, user: a cap on how far a game has got, not on wall time,
     * "with a penalty for turns which involve many creatures or activated abilities"). At every phase of every turn:
     * 1 for the phase, plus WC for each creature on the battlefield and WA for each permanent there with a non-mana
     * activated ability -- the board the AI must evaluate. Deterministic: paired games stay paired on every machine
     * and at any load. Tweak "workcap=CAP" (or "workcap=CAP:WC:WA"; CAP 0 = measure only) ends a game as a draw once
     * its work reaches CAP ("end":"cap"). Without the tweak the meter only measures.
     */
    static final class WorkMeter {
        static final String SPEC = spec();
        static final boolean ON = SPEC != null;
        static final long CAP = ON ? Long.parseLong(SPEC.split(":")[0]) : 0;
        static final long WC = ON && SPEC.split(":").length > 1 ? Long.parseLong(SPEC.split(":")[1]) : 1;
        static final long WA = ON && SPEC.split(":").length > 2 ? Long.parseLong(SPEC.split(":")[2]) : 1;

        static String spec() {
            for (String t : System.getProperty("forge.sim.tweaks", "").split(","))
                if (t.trim().startsWith("workcap=")) return t.trim().substring("workcap=".length());
            return null;
        }

        final Game game;
        final long cap;
        long phases, creatures, abilities, events;
        boolean capped;

        WorkMeter(Game game, long cap) {
            this.game = game;
            this.cap = cap;
        }

        long work() { return phases + WC * creatures + WA * abilities; }

        @com.google.common.eventbus.Subscribe
        public void event(forge.game.event.Event e) {
            if (capped || game.isGameOver()) return;
            events++;
            if (!(e instanceof forge.game.event.GameEventTurnPhase)) return;
            phases++;
            for (forge.game.player.Player p : game.getPlayers())
                for (forge.game.card.Card c : p.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    if (c.isCreature()) creatures++;
                    for (forge.game.spellability.SpellAbility sa : c.getSpellAbilities())
                        if (sa.isActivatedAbility() && !sa.isManaAbility()) { abilities++; break; }
                }
            if (cap > 0 && work() >= cap) {
                capped = true;
                game.setGameOver(GameEndReason.Draw);
            }
        }

        String json() {
            return "{\"work\":" + work() + ",\"phases\":" + phases + ",\"creatures\":" + creatures + ",\"abilities\":" + abilities + ",\"events\":" + events + "}";
        }
    }

    /**
     * Paired seeds: Forge builds each library from a hash map (Match.prepareAllZones), so its order before the shuffle
     * depends on which cards are in the deck, and swapping one card scrambles every position. When the game starts
     * (before opening hands), put each library in deck-file order and shuffle it again with a generator seeded from the
     * game seed and the seat. The order is slot by slot: copy k of the file is library card k before the shuffle, so a
     * card written where other copies were (on any line, even splitting a line) takes over exactly their positions and
     * the rest of the library is identical. Forge's own random stream is untouched.
     */
    static final class FileOrderLibraries {
        final Game game;
        final List<List<String>> order; // per seat: one name per copy, file order
        final long seed;

        final Map<Integer, Integer> slotOf = new HashMap<>(); // card id -> its deck slot (file order)
        final int[] shuffles = new int[2];                    // per seat: in-game shuffles so far
        static final boolean PAIR_SHUFFLES = !System.getProperty("forge.sim.pairshuffles", "").isEmpty();

        FileOrderLibraries(Game game, List<List<String>> order, long seed) {
            this.game = game;
            this.order = order;
            this.seed = seed;
        }

        /**
         * With -Dforge.sim.pairshuffles=1, every later shuffle (mulligan, search, ...) is redone the same way: the
         * library's cards go back in deck-slot order and are shuffled with a generator seeded from the game seed, the
         * seat and how many shuffles that seat has had. Paired games whose libraries hold the same slots then get the same
         * order again after a search, instead of independent ones.
         */
        @com.google.common.eventbus.Subscribe
        public void shuffled(forge.game.event.GameEventShuffle e) {
            if (!PAIR_SHUFFLES) return;
            for (forge.game.player.Player pl : game.getPlayers()) {
                if (!pl.getName().equals(e.player().getName())) continue;
                int seat = Integer.parseInt(pl.getName().substring(1)) - 1;
                forge.game.zone.PlayerZone lib = pl.getZone(forge.game.zone.ZoneType.Library);
                List<forge.game.card.Card> cards = new ArrayList<>(lib.getCards());
                cards.sort(Comparator.<forge.game.card.Card>comparingInt(c -> slotOf.getOrDefault(c.getId(), 1_000_000))
                        .thenComparingInt(forge.game.card.Card::getId));
                shuffles[seat]++;
                Collections.shuffle(cards, new Random((seed * 31 + seat) * 1_000_003L + shuffles[seat]));
                lib.setCards(cards);
            }
        }

        @com.google.common.eventbus.Subscribe
        public void started(forge.game.event.GameEventGameStarted e) {
            for (forge.game.player.Player pl : game.getPlayers()) {
                int seat = Integer.parseInt(pl.getName().substring(1)) - 1;
                List<String> o = order.get(seat);
                forge.game.zone.PlayerZone lib = pl.getZone(forge.game.zone.ZoneType.Library);
                Map<String, Deque<forge.game.card.Card>> byName = new HashMap<>();
                List<forge.game.card.Card> all = new ArrayList<>(lib.getCards());
                all.sort(Comparator.comparingInt(forge.game.card.Card::getId));
                for (forge.game.card.Card c : all) byName.computeIfAbsent(c.getPaperCard().getName(), k -> new ArrayDeque<>()).add(c);
                List<forge.game.card.Card> cards = new ArrayList<>();
                for (String n : o) {
                    Deque<forge.game.card.Card> q = byName.get(n);
                    if (q != null && !q.isEmpty()) cards.add(q.poll());
                }
                for (forge.game.card.Card c : all) if (!cards.contains(c)) cards.add(c); // anything the file didn't name
                for (int i = 0; i < cards.size(); i++) slotOf.put(cards.get(i).getId(), i);
                Collections.shuffle(cards, new Random(seed * 31 + seat));
                lib.setCards(cards);
            }
        }
    }

    /**
     * How far into each library a game reached before anything disturbed its order: cards that left the library from
     * the game's start until the first shuffle or card put into it ("depth"), and whether such a disturbance happened.
     * A paired mutant whose swapped slots all lie at or beyond the baseline's depth, in a game the baseline never
     * disturbed, plays the identical game, so it need not be played (iterate/select_seeds.py).
     */
    static final class LibraryDepth {
        final Map<String, int[]> depth = new HashMap<>();       // player name -> {cards taken off the library}
        final Map<String, boolean[]> disturbed = new HashMap<>();
        final Map<String, int[]> bottomed = new HashMap<>();    // cards a Michelangelo's Technique put on the bottom
        final Map<String, int[]> resolving = new HashMap<>();   // Techniques of this player on the stack
        boolean started;

        @com.google.common.eventbus.Subscribe
        public void started(forge.game.event.GameEventGameStarted e) {
            started = true;
        }

        @com.google.common.eventbus.Subscribe
        public void shuffle(forge.game.event.GameEventShuffle e) {
            if (started) disturbed.computeIfAbsent(e.player().getName(), k -> new boolean[1])[0] = true;
        }

        @com.google.common.eventbus.Subscribe
        public void zone(forge.game.event.GameEventCardChangeZone e) {
            if (!started || e.card() == null || e.card().getOwner() == null) return;
            String who = e.card().getOwner().getName();
            boolean[] d = disturbed.computeIfAbsent(who, k -> new boolean[1]);
            forge.game.zone.ZoneType from = e.from() == null ? null : e.from().zoneType();
            forge.game.zone.ZoneType to = e.to() == null ? null : e.to().zoneType();
            // Michelangelo's Technique looks at the top eight, puts up to two creatures onto the battlefield and the rest
            // on the bottom: while one resolves, a card moved from the library to the library went from the top to the
            // bottom, so it uses up a top position like a card that left (the order of the cards above stays as it was)
            if (e.card().getName().equals("Michelangelo's Technique")) {
                int[] r = resolving.computeIfAbsent(who, k -> new int[1]);
                if (to == forge.game.zone.ZoneType.Stack) r[0]++;
                else if (from == forge.game.zone.ZoneType.Stack && r[0] > 0) r[0]--;
            }
            boolean technique = resolving.getOrDefault(who, new int[1])[0] > 0;
            if (technique && from == forge.game.zone.ZoneType.Library && to == forge.game.zone.ZoneType.Library) {
                bottomed.computeIfAbsent(who, k -> new int[1])[0]++;
                if (!d[0]) depth.computeIfAbsent(who, k -> new int[1])[0]++;
            } else if (to == forge.game.zone.ZoneType.Library) d[0] = true;
            else if (!d[0] && from == forge.game.zone.ZoneType.Library)
                depth.computeIfAbsent(who, k -> new int[1])[0]++;
        }
    }

    /**
     * Cards that went from a library straight onto the battlefield (Michelangelo's Technique, Earth's Mightiest Heroes,
     * land fetches ...): [name, turn, the card whose effect was resolving]. They were never drawn, so draw-based per-card
     * statistics miss them; this keeps them visible.
     */
    static final class Deployments {
        final Game game;
        final Map<String, List<String[]>> byPlayer = new HashMap<>();
        boolean started;
        static java.lang.reflect.Field resolvingCard;

        Deployments(Game game) {
            this.game = game;
        }

        @com.google.common.eventbus.Subscribe
        public void started(forge.game.event.GameEventGameStarted e) {
            started = true;
        }

        String resolving() {
            try {
                if (resolvingCard == null) {
                    resolvingCard = forge.game.zone.MagicStack.class.getDeclaredField("curResolvingCard");
                    resolvingCard.setAccessible(true);
                }
                Object c = resolvingCard.get(game.getStack());
                return c == null ? "" : ((forge.game.card.Card) c).getName();
            } catch (ReflectiveOperationException ex) {
                return "?";
            }
        }

        @com.google.common.eventbus.Subscribe
        public void zone(forge.game.event.GameEventCardChangeZone e) {
            if (!started || e.card() == null || e.card().getOwner() == null || e.from() == null || e.to() == null) return;
            if (e.from().zoneType() != forge.game.zone.ZoneType.Library || e.to().zoneType() != forge.game.zone.ZoneType.Battlefield) return;
            byPlayer.computeIfAbsent(e.card().getOwner().getName(), k -> new ArrayList<>())
                    .add(new String[] {e.card().getName(), String.valueOf(game.getPhaseHandler().getTurn()), resolving()});
        }
    }

    /** One game: deck A (named NAMEA) against B, game index G of SEED (seats alternate by G); returns its JSON line. */
    static String playOne(Path a, String nameA, Path b, long seed, int g, int timeout) throws IOException {
        String nameB = b.getFileName().toString().replaceAll("\\.dck$", "");
        boolean swap = g % 2 == 1; // alternate seats
        String[] seat = swap ? new String[] {nameB, nameA} : new String[] {nameA, nameB};
        Path[] files = swap ? new Path[] {b, a} : new Path[] {a, b};
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int p = 0; p < 2; p++) {
            RegisteredPlayer rp = new RegisteredPlayer(load(files[p]));
            rp.setPlayer(AiTweaks.player("P" + (p + 1), p));
            players.add(rp);
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setSimTimeout(timeout);
        long gameSeed = seed * 1_000_003L + g;
        forge.util.MyRandom.setRandom(new Random(gameSeed));
        Match match = new Match(rules, players, "match");
        Game game = match.createGame();
        game.setNoGUIUser();
        List<forge.game.player.Player> seated = new ArrayList<>(game.getPlayers());
        game.subscribeToEvents(new FileOrderLibraries(game, List.of(fileOrder(files[0]), fileOrder(files[1])), gameSeed));
        AiTweaks.bites = 0;
        ControlBrain.counts.clear();
        ControlBrain.trace.setLength(0);
        ControlBrain.tracedTurn = -1;
        Deployments deployed = new Deployments(game);
        game.subscribeToEvents(deployed);
        WorkMeter work = new WorkMeter(game, WorkMeter.CAP);
        game.subscribeToEvents(work);
        LibraryDepth libDepth = new LibraryDepth();
        game.subscribeToEvents(libDepth);
        ToyGames.Draws draws = new ToyGames.Draws(game);
        game.subscribeToEvents(draws);
        long t0 = System.nanoTime();
        String end;
        long cpuMs = -1;
        if (WorkMeter.ON) {
            // work cap: the game ends at a budget of game work (deterministic, the same on every machine and at any
            // load); the timeout is only a backstop for genuine hangs, in CPU seconds of the game's own thread (not
            // wall time, which contention stretches), at least 120
            Throwable[] err = new Throwable[1];
            Thread t = new Thread(() -> {
                try { match.startGame(game); } catch (Throwable ex) { err[0] = ex; }
            }, "forge-game");
            t.setDaemon(true);
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            long cpuLimit = Math.max(120, timeout) * 1_000_000_000L, wallLimit = 30L * 60 * 1_000_000_000L;
            t.start();
            try {
                while (t.isAlive()) {
                    t.join(200);
                    long c = mx.getThreadCpuTime(t.getId());
                    if (c > cpuLimit || System.nanoTime() - t0 > wallLimit) break;
                }
            } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            cpuMs = Math.max(-1, mx.getThreadCpuTime(t.getId()) / 1_000_000);
            if (t.isAlive()) {
                game.setGameOver(GameEndReason.Draw);
                end = "timeout";
            } else if (err[0] != null) {
                game.setGameOver(GameEndReason.Draw);
                end = "error:" + err[0].getClass().getSimpleName();
            } else end = work.capped ? "cap" : "finished";
        } else {
            try {
                TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), timeout, TimeUnit.SECONDS);
                end = "finished";
            } catch (Exception ex) {
                game.setGameOver(GameEndReason.Draw);
                end = ex.getClass().getSimpleName().contains("Timeout") ? "timeout" : "error:" + ex.getClass().getSimpleName();
            }
        }
        draws.resolve();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String logDir = System.getProperty("forge.sim.gamelog", "");
        if (!logDir.isEmpty()) {
            // Forge's own game log (plays, attacks, blocks, damage, life, stack), for reading individual games
            try (PrintWriter lw = new PrintWriter(new FileWriter(logDir + "/" + nameA + "_vs_" + nameB + "_" + seed + "_" + g + ".log"))) {
                lw.println("P1 = " + seat[0] + ", P2 = " + seat[1] + "; game seed " + gameSeed);
                List<forge.game.GameLogEntry> entries = new ArrayList<>(game.getGameLog().getAllEntries());
                for (forge.game.GameLogEntry e : entries) lw.println(e.type() + ": " + e.message());
            }
            if (ControlBrain.trace.length() > 0)
                try (PrintWriter tw = new PrintWriter(new FileWriter(logDir + "/" + nameA + "_vs_" + nameB + "_" + seed + "_" + g + ".brain"))) {
                    tw.print(ControlBrain.trace);
                }
        }
        GameOutcome o = game.getOutcome();
        String winnerSeat = (o == null || o.isDraw() || o.getWinningLobbyPlayer() == null) ? "" : o.getWinningLobbyPlayer().getName();
        // A game that did not finish is a draw. Forge's setGameOver(Draw) marks every player who has not lost as a
        // winner (Player.onGameOver) and getWinningLobbyPlayer then returns whichever comes first in a HashMap, so
        // until 2026-10-15 a timed-out game got a coin-flip winner (sealed final: our deck "won" 52.8% of its timeouts
        // and 72.3% of its finished games).
        String winner = winnerSeat.isEmpty() || !end.equals("finished") ? "" : seat[Integer.parseInt(winnerSeat.substring(1)) - 1];
        String first = game.getStartingPlayer() == null ? "" : seat[Integer.parseInt(game.getStartingPlayer().getName().substring(1)) - 1];
        StringBuilder cards = new StringBuilder("{");
        for (int p = 0; p < 2; p++) {
            StringBuilder l = new StringBuilder("[");
            for (Map.Entry<Integer, String> e : draws.drawn.getOrDefault("P" + (p + 1), Map.of()).entrySet()) {
                l.append(l.length() > 1 ? "," : "").append("[").append(ToyGames.q(e.getValue())).append(",")
                        .append(draws.drawnTurn.getOrDefault(e.getKey(), -1)).append(",")
                        .append(draws.playedTurn.getOrDefault(e.getKey(), -1)).append("]");
            }
            cards.append(p > 0 ? "," : "").append(ToyGames.q(seat[p])).append(":").append(l).append("]");
        }
        cards.append("}");
        StringBuilder life = new StringBuilder("{");
        for (int p = 0; p < seated.size(); p++) {
            life.append(p > 0 ? "," : "").append(ToyGames.q(seat[p])).append(":").append(seated.get(p).getLife());
        }
        life.append("}");
        StringBuilder searched = new StringBuilder("{");
        for (int p = 0; p < 2; p++) {
            searched.append(p > 0 ? "," : "").append(ToyGames.q(seat[p])).append(":")
                    .append(ToyGames.list(draws.searched.getOrDefault("P" + (p + 1), Map.of()).values()));
        }
        searched.append("}");
        StringBuilder dep = new StringBuilder("{");
        for (int p = 0; p < 2; p++) {
            StringBuilder l = new StringBuilder("[");
            for (String[] d : deployed.byPlayer.getOrDefault("P" + (p + 1), List.of())) {
                l.append(l.length() > 1 ? "," : "").append("[").append(ToyGames.q(d[0])).append(",").append(d[1])
                        .append(",").append(ToyGames.q(d[2])).append("]");
            }
            dep.append(p > 0 ? "," : "").append(ToyGames.q(seat[p])).append(":").append(l).append("]");
        }
        dep.append("}");
        StringBuilder lib = new StringBuilder("{");
        for (int p = 0; p < 2; p++) {
            String pn = "P" + (p + 1);
            lib.append(p > 0 ? "," : "").append(ToyGames.q(seat[p])).append(":{\"depth\":")
                    .append(libDepth.depth.getOrDefault(pn, new int[1])[0]).append(",\"disturbed\":")
                    .append(libDepth.disturbed.getOrDefault(pn, new boolean[1])[0]).append(",\"technique_bottomed\":")
                    .append(libDepth.bottomed.getOrDefault(pn, new int[1])[0]).append("}");
        }
        lib.append("}");
        StringBuilder mull = new StringBuilder("{");
        if (o != null) {
            for (Map.Entry<RegisteredPlayer, PlayerStatistics> e : o) {
                int s = Integer.parseInt(e.getKey().getPlayer().getName().substring(1)) - 1;
                mull.append(mull.length() > 1 ? "," : "").append(ToyGames.q(seat[s])).append(":").append(e.getValue().getMulliganCount());
            }
        }
        mull.append("}");
        return "{\"a\":" + ToyGames.q(nameA) + ",\"b\":" + ToyGames.q(nameB) + ",\"game\":" + g
                + ",\"winner\":" + ToyGames.q(winner) + ",\"first\":" + ToyGames.q(first) + ",\"end\":" + ToyGames.q(end)
                + ",\"turns\":" + (o == null ? -1 : o.getLastTurnNumber()) + ",\"ms\":" + ms + ",\"cpu_ms\":" + cpuMs + ",\"work\":" + work.json() + ",\"game_seed\":" + gameSeed
                + ",\"life\":" + life + ",\"mulligans\":" + mull + ",\"library\":" + lib + ",\"searched\":" + searched + ",\"deployed\":" + dep + (AiTweaks.TWEAKS.isEmpty() ? "" : ",\"tweak_bites\":" + AiTweaks.bites + ",\"control_brain\":" + ControlBrain.json()) + ",\"cards\":" + cards + "}";
    }

    public static void main(String[] args) throws Exception {
        Path a = Paths.get(args[0]);
        List<Path> field = new ArrayList<>();
        if (args[1].startsWith("@")) {
            for (String l : Files.readAllLines(Paths.get(args[1].substring(1)))) if (!l.isBlank()) field.add(Paths.get(l.trim()));
        } else field.add(Paths.get(args[1]));
        String out = args[2];
        List<Integer> which = new ArrayList<>();
        if (args[3].contains(",")) for (String x : args[3].split(",")) { if (!x.isBlank()) which.add(Integer.parseInt(x.trim())); }
        else for (int i = 0; i < Integer.parseInt(args[3]); i++) which.add(i);
        long seed = Long.parseLong(args[4]);
        int timeout = Integer.parseInt(args[5]);
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        String nameA = a.getFileName().toString().replaceAll("\\.dck$", "");
        try (PrintWriter w = new PrintWriter(new FileWriter(out, true))) {
            for (int g : which) {
                Path b = field.get((g / 2) % field.size());
                w.println(playOne(a, nameA, b, seed, g, timeout));
                w.flush();
            }
        }
        System.exit(0);
    }
}
