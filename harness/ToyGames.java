import com.google.common.eventbus.Subscribe;
import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.*;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.PlayerStatistics;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.view.TimeLimitedCodeBlock;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Games for card ratings: random mono-green decks (LANDS Forest + SPELLS different pool cards), AI vs AI.
 * One JSON line per game: both decks, each player's organic draws (real draws, opening hand included, mulligans
 * undone, each physical card at most once per game), cards that reached hand from the library without being drawn
 * (searches), winner, how it ended, turns, milliseconds, final life totals, the game's random seed. "cards" lists every
 * organically drawn card as [name, turn drawn (0 = opening hand), turn first cast or played (-1 = never)].
 *
 * The pool file is read again before every game (a coordinator retires cards from it as they get enough data). When it
 * holds fewer cards than SPELLS, the deck is filled up from FILLER (retired cards); filler cards are listed separately.
 *
 * With FIXED.dck (gauntlet mode), P2 always plays that deck and every pool card goes to P1's random deck; P2's
 * cards are recorded under "fixed_cards", never as picks, so they do not count toward any pool card's data.
 *
 * Core mode (--core CORE.txt --field FIELD.txt): P1 plays LANDS Forest + the core cards (lines "N Name" or "Name") +
 * SPELLS minus the core's size in pool cards; P2 plays the decks listed in FIELD.txt (one .dck path per line, read once
 * at start) in turn, starting at a random one. The opponent's file name is recorded as "opp". Core cards are not
 * picks, so they count toward nothing; keep them out of the pool file.
 *
 * LANDS is a number of Forests (default 24) or a list such as Swamp:12,Mountain:12.
 *
 * Usage: java -cp forge.jar:. ToyGames POOL.txt FILLER.txt OUT.jsonl GAMES SEED TIMEOUT_S [LANDS] [SPELLS] [FIXED.dck]
 *        [--core CORE.txt --field FIELD.txt]
 */
public class ToyGames {
    static final class Draws {
        final Game game;
        boolean started; // after the opening hands are kept
        final Map<String, Map<Integer, String>> drawn = new HashMap<>();    // player -> card id -> name (organic)
        final Map<String, Map<Integer, String>> searched = new HashMap<>(); // library -> hand, not a draw
        final Map<Integer, Integer> drawnTurn = new HashMap<>(); // card id -> turn drawn (0: opening hand)
        final Map<Integer, Integer> playedTurn = new HashMap<>(); // card id -> turn it first left hand to stack/battlefield

        int turnNow() {
            return started ? game.getPhaseHandler().getTurn() : 0;
        }

        Draws(Game game) {
            this.game = game;
        }

        /** Library -> hand moves not yet classified: Forge marks a card as drawn only after it has moved. */
        final List<Object[]> pending = new ArrayList<>(); // {owner name, card id, card name}

        /** Classify pending moves (the draw that moved them has finished by the next event). */
        void resolve() {
            for (Object[] m : pending) {
                String who = (String) m[0];
                int id = (Integer) m[1];
                forge.game.card.Card c = game.findById(id);
                boolean draw = c != null && c.getDrawnThisTurn();
                // a physical card counts once per game (drawn, put back on top, drawn again: one draw)
                if (draw) {
                    searched.getOrDefault(who, new HashMap<>()).remove(id);
                    drawn.computeIfAbsent(who, k -> new HashMap<>()).putIfAbsent(id, (String) m[2]);
                    drawnTurn.putIfAbsent(id, (Integer) m[3]);
                } else if (!drawn.getOrDefault(who, Map.of()).containsKey(id)) {
                    searched.computeIfAbsent(who, k -> new HashMap<>()).putIfAbsent(id, (String) m[2]);
                }
            }
            pending.clear();
        }

        @Subscribe
        public void any(forge.game.event.GameEvent e) {
            if (!(e instanceof GameEventCardChangeZone)) resolve();
        }

        @Subscribe
        public void zone(GameEventCardChangeZone e) {
            resolve();
            if (e.from() == null || e.to() == null || e.card() == null || e.card().getOwner() == null) return;
            String who = e.card().getOwner().getLobbyPlayerName();
            int id = e.card().getId();
            String name = e.card().getCurrentState().getName();
            if (e.from().zoneType() == ZoneType.Library && e.to().zoneType() == ZoneType.Hand) {
                if (!started) {
                    drawn.computeIfAbsent(who, k -> new HashMap<>()).put(id, name); // opening hand
                    drawnTurn.put(id, 0);
                } else {
                    pending.add(new Object[] {who, id, name, turnNow()});
                }
            } else if (!started && e.from().zoneType() == ZoneType.Hand && e.to().zoneType() == ZoneType.Library) {
                drawn.computeIfAbsent(who, k -> new HashMap<>()).remove(id); // mulligan: put back before the game
                drawnTurn.remove(id);
            } else if (started && e.from().zoneType() == ZoneType.Hand
                    && (e.to().zoneType() == ZoneType.Stack || e.to().zoneType() == ZoneType.Battlefield)) {
                playedTurn.putIfAbsent(id, turnNow()); // cast, or a land played
            }
        }

        @Subscribe
        public void turn(forge.game.event.GameEventTurnBegan e) {
            started = true;
        }
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String list(Collection<String> xs) {
        StringBuilder b = new StringBuilder("[");
        for (String x : xs) b.append(b.length() > 1 ? "," : "").append(q(x));
        return b.append("]").toString();
    }

    static List<String> readList(Path p) throws IOException {
        if (!Files.exists(p)) return new ArrayList<>();
        List<String> xs = new ArrayList<>(Files.readAllLines(p));
        xs.removeIf(String::isBlank);
        return xs;
    }

    public static void main(String[] argv) throws Exception {
        List<String> al = new ArrayList<>(Arrays.asList(argv));
        Map<String, Integer> core = new LinkedHashMap<>();
        List<Path> field = new ArrayList<>();
        int ci = al.indexOf("--core");
        if (ci >= 0) {
            for (String l : readList(Paths.get(al.get(ci + 1)))) {
                String[] p = l.trim().split(" ", 2);
                if (p.length == 2 && p[0].matches("\\d+")) core.merge(p[1].trim(), Integer.parseInt(p[0]), Integer::sum);
                else core.merge(l.trim(), 1, Integer::sum);
            }
            al.subList(ci, ci + 2).clear();
        }
        int fi = al.indexOf("--field");
        if (fi >= 0) {
            for (String l : readList(Paths.get(al.get(fi + 1)))) field.add(Paths.get(l.trim()));
            al.subList(fi, fi + 2).clear();
        }
        String[] args = al.toArray(new String[0]);
        int coreSize = core.values().stream().mapToInt(Integer::intValue).sum();
        Path poolPath = Paths.get(args[0]);
        Path fillerPath = Paths.get(args[1]);
        String out = args[2];
        int games = Integer.parseInt(args[3]);
        long seed = Long.parseLong(args[4]);
        int timeout = Integer.parseInt(args[5]);
        // LANDS: a number (that many Forest) or a list like "Swamp:12,Mountain:12"
        Map<String, Integer> lands = new LinkedHashMap<>();
        String landArg = args.length > 6 ? args[6] : "24";
        if (landArg.matches("\\d+")) lands.put("Forest", Integer.parseInt(landArg));
        else for (String part : landArg.split(",")) {
            String[] kv = part.split(":");
            lands.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
        }
        int spells = args.length > 7 ? Integer.parseInt(args[7]) : 36;
        Path fixed = args.length > 8 ? Paths.get(args[8]) : null;
        if (!field.isEmpty() && fixed != null) throw new IllegalArgumentException("FIXED.dck and --field are exclusive");
        int picksPerDeck = spells - coreSize;
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Random rng = new Random(seed);
        int fieldStart = field.isEmpty() ? 0 : rng.nextInt(field.size());
        try (PrintWriter w = new PrintWriter(new FileWriter(out, true))) {
            for (int g = 0; g < games; g++) {
                List<String> pool = readList(poolPath);
                if (pool.isEmpty()) break; // every card has its data
                List<String> filler = readList(fillerPath);
                List<List<String>> decks = new ArrayList<>();
                List<List<String>> fills = new ArrayList<>();
                List<RegisteredPlayer> players = new ArrayList<>();
                // Deal the active cards out between the decks: never the same card in both (a card meeting itself
                // pulls its win rate when drawn toward 50%). With fewer than 2 x SPELLS left, they split between the
                // decks (a random one gets the odd card) and each deck is filled up with retired cards.
                List<String> dealt = new ArrayList<>(pool);
                Collections.shuffle(dealt, rng);
                List<List<String>> hands = List.of(new ArrayList<>(), new ArrayList<>());
                int turn = rng.nextInt(2);
                Path opp = field.isEmpty() ? fixed : field.get((fieldStart + g) % field.size());
                if (opp != null) {
                    hands.get(0).addAll(dealt.subList(0, Math.min(picksPerDeck, dealt.size())));
                    dealt = List.of();
                }
                for (String c : dealt) {
                    if (hands.get(turn).size() < spells) hands.get(turn).add(c);
                    else if (hands.get(1 - turn).size() < spells) hands.get(1 - turn).add(c);
                    else break;
                    turn = 1 - turn;
                }
                for (int p = 0; p < 2; p++) {
                    List<String> picks = new ArrayList<>(hands.get(p));
                    List<String> fill = new ArrayList<>(filler);
                    fill.removeAll(hands.get(0));
                    fill.removeAll(hands.get(1));
                    Collections.shuffle(fill, rng);
                    fill.removeAll(core.keySet());
                    fill = new ArrayList<>(fill.subList(0, Math.min((p == 0 ? picksPerDeck : spells) - picks.size(), fill.size())));
                    Collections.sort(picks);
                    Collections.sort(fill);
                    decks.add(picks);
                    fills.add(fill);
                    Deck d;
                    if (opp != null && p == 1) {
                        d = MatchGames.load(opp);
                        picks.clear();
                        fill.clear();
                    } else {
                        d = new Deck("P" + (p + 1));
                        for (Map.Entry<String, Integer> e : lands.entrySet()) d.getMain().add(e.getKey(), e.getValue());
                        for (String c : picks) d.getMain().add(c, 1);
                        for (String c : fill) d.getMain().add(c, 1);
                        if (p == 0) for (Map.Entry<String, Integer> e : core.entrySet()) d.getMain().add(e.getKey(), e.getValue());
                    }
                    RegisteredPlayer rp = new RegisteredPlayer(d);
                    rp.setPlayer(GamePlayerUtil.createAiPlayer("P" + (p + 1), p));
                    players.add(rp);
                }
                GameRules rules = new GameRules(GameType.Constructed);
                rules.setGamesPerMatch(1);
                rules.setSimTimeout(timeout);
                long gameSeed = seed * 1_000_003L + g;
                forge.util.MyRandom.setRandom(new Random(gameSeed)); // the game's own shuffles and coin flips: replayable
                Match match = new Match(rules, players, "toy");
                Game game = match.createGame();
                game.setNoGUIUser();
                List<forge.game.player.Player> seated = new ArrayList<>(game.getPlayers()); // losers leave getPlayers()
                Draws draws = new Draws(game);
                game.subscribeToEvents(draws);
                long t0 = System.nanoTime();
                String end;
                try {
                    TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), timeout, TimeUnit.SECONDS);
                    end = "finished";
                } catch (Exception ex) {
                    game.setGameOver(GameEndReason.Draw);
                    end = ex.getClass().getSimpleName().contains("Timeout") ? "timeout" : "error:" + ex.getClass().getSimpleName();
                }
                draws.resolve();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                GameOutcome o = game.getOutcome();
                String winner = "";
                StringBuilder reasons = new StringBuilder("{");
                if (o != null) {
                    if (!o.isDraw() && o.getWinningLobbyPlayer() != null) winner = o.getWinningLobbyPlayer().getName();
                    for (Map.Entry<RegisteredPlayer, PlayerStatistics> e : o) {
                        String r = e.getValue().getOutcome() == null ? "" :
                                (e.getValue().getOutcome().lossState == null ? "won" : e.getValue().getOutcome().lossState.toString());
                        reasons.append(reasons.length() > 1 ? "," : "").append(q(e.getKey().getPlayer().getName())).append(":").append(q(r))
                                .append(",").append(q(e.getKey().getPlayer().getName() + "_mulligans")).append(":")
                                .append(e.getValue().getMulliganCount());
                    }
                }
                reasons.append("}");
                String first = game.getStartingPlayer() == null ? "" : game.getStartingPlayer().getName();
                StringBuilder cards = new StringBuilder("{");
                StringBuilder life = new StringBuilder("{");
                for (String who : List.of("P1", "P2")) {
                    StringBuilder l = new StringBuilder("[");
                    for (Map.Entry<Integer, String> e : draws.drawn.getOrDefault(who, Map.of()).entrySet()) {
                        l.append(l.length() > 1 ? "," : "").append("[").append(q(e.getValue())).append(",")
                                .append(draws.drawnTurn.getOrDefault(e.getKey(), -1)).append(",")
                                .append(draws.playedTurn.getOrDefault(e.getKey(), -1)).append("]");
                    }
                    cards.append(cards.length() > 1 ? "," : "").append(q(who)).append(":").append(l).append("]");
                }
                for (forge.game.player.Player x : seated) {
                    life.append(life.length() > 1 ? "," : "").append(q(x.getLobbyPlayer().getName())).append(":").append(x.getLife());
                }
                cards.append("}");
                life.append("}");
                w.println("{\"seed\":" + seed + ",\"game\":" + g
                        + (field.isEmpty() ? "" : ",\"opp\":" + q(opp.getFileName().toString().replaceAll("\\.dck$", ""))) + ",\"P1\":" + list(decks.get(0)) + ",\"P2\":" + list(decks.get(1))
                        + ",\"fill\":{\"P1\":" + list(fills.get(0)) + ",\"P2\":" + list(fills.get(1)) + "}"
                        + ",\"drawn\":{\"P1\":" + list(draws.drawn.getOrDefault("P1", Map.of()).values())
                        + ",\"P2\":" + list(draws.drawn.getOrDefault("P2", Map.of()).values()) + "}"
                        + ",\"searched\":{\"P1\":" + list(draws.searched.getOrDefault("P1", Map.of()).values())
                        + ",\"P2\":" + list(draws.searched.getOrDefault("P2", Map.of()).values()) + "}"
                        + ",\"winner\":" + q(winner) + ",\"end\":" + q(end)
                        + ",\"reason\":" + (o == null ? q("") : q(String.valueOf(o.getWinCondition())))
                        + ",\"players\":" + reasons + ",\"first\":" + q(first)
                        + ",\"turns\":" + (o == null ? -1 : o.getLastTurnNumber()) + ",\"ms\":" + ms
                        + ",\"game_seed\":" + gameSeed + ",\"life\":" + life + ",\"cards\":" + cards + "}");
                w.flush();
            }
        }
        System.exit(0);
    }
}
