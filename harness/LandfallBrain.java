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

    /**
     * lfaudit@NAME (2026-10-23, user: minimise the share of games with unambiguous misplays): counts, without changing
     * any decision, the Landfall misplays below as ControlBrain counters "mis_*" (in the game's JSON).
     *  mis_land_unplayed   our turn ended with a land in hand and a land drop unused;
     *  mis_payoff_late     a landfall made in main 1 while a landfall payoff in hand was castable (cast it first);
     *  mis_landfall_post   a landfall made in main 2 while Mightform Harmonizer had been on the battlefield all turn
     *                      and one of our creatures attacked (its doubling came after the damage);
     *  mis_harm_target     before attacks, a Harmonizer doubling aimed at a creature that cannot attack this turn or
     *                      has no power, while one with power that can attack was available.
     */
    static int auditTurn = -1; static Game auditGame;

    static boolean canAttackThisTurn(Card c) {
        return c.isUntapped() && (!c.isSick() || c.hasKeyword(forge.game.keyword.Keyword.HASTE)) && CombatUtil.canAttack(c);
    }

    /** audit only (no AI calls: they can change the game): a payoff in hand whose mana value (Harmonizer: its warp
     *  cost, 3) is at most our untapped mana sources -- lands and creatures with a mana ability that can tap */
    static boolean payoffAffordable(Player me) {
        int mana = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isUntapped() && !c.getManaAbilities().isEmpty() && (c.isLand() || !c.isSick())) mana++;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            boolean pay = false;
            for (String p : PAYOFFS) if (named(c, p)) pay = true;
            if (!pay) continue;
            int cost = named(c, HARMONIZER) ? 3 : named(c, "Sapling Nursery") ? Math.max(2, c.getCMC() - forests(me)) : c.getCMC();
            if (cost <= mana) return true;
        }
        return false;
    }

    static int forests(Player me) {
        int n = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getType().hasSubtype("Forest")) n++;
        return n;
    }

    static List<SpellAbility> audit(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me)) return ai;
        PhaseType ph = g.getPhaseHandler().getPhase();
        int turn = g.getPhaseHandler().getTurn();
        boolean empty = g.getStack().isEmpty();
        SpellAbility pick = ai == null || ai.isEmpty() ? null : ai.get(0);
        if (pick != null && empty && makesLandfall(pick)) {
            if (ph == PhaseType.MAIN1 && payoffAffordable(me)) ControlBrain.count("mis_payoff_late");
            if (ph == PhaseType.MAIN2) {
                boolean harm = false, attacked = false;
                for (Card c : me.getCreaturesInPlay()) {
                    if (named(c, HARMONIZER) && c.getTurnInZone() < turn) harm = true;
                    if (c.getDamageHistory().getCreatureAttacksThisTurn() > 0) attacked = true;
                }
                if (harm && attacked) ControlBrain.count("mis_landfall_post");
            }
        }
        if (pick == null && empty && (ph == PhaseType.MAIN2 || ph == PhaseType.END_OF_TURN) && (auditGame != g || auditTurn != turn)
                && !me.getMaxLandPlaysInfinite() && me.getLandsPlayedThisTurn() < me.getMaxLandPlays()) {
            for (Card c : me.getCardsIn(ZoneType.Hand))
                if (c.isLand()) {
                    {
                        ControlBrain.count("mis_land_unplayed");
                        auditGame = g; auditTurn = turn;
                        break;
                    }
                }
        }
        return ai;
    }

    static void auditTrigger(Player me, WrappedAbility wa) {
        Card host = wa.getHostCard();
        Game g = me.getGame();
        if (host == null || !named(host, HARMONIZER) || !g.getPhaseHandler().isPlayerTurn(me)) return;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (ph != PhaseType.UPKEEP && ph != PhaseType.DRAW && ph != PhaseType.MAIN1 && ph != PhaseType.COMBAT_BEGIN) return;
        Card tgt = null;
        if (wa.getTargets() != null) for (Object o : wa.getTargets()) if (o instanceof Card) tgt = (Card) o;
        if (tgt == null) return;
        if (canAttackThisTurn(tgt) && tgt.getNetPower() > 0) return;
        for (Card c : me.getCreaturesInPlay())
            if (c != tgt && canAttackThisTurn(c) && c.getNetPower() > 0 && !c.hasKeyword(forge.game.keyword.Keyword.HEXPROOF)) {
                ControlBrain.count("mis_harm_target");
                return;
            }
    }

    /**
     * lftrace@NAME (2026-10-23, for agent scoring of game samples): before each of our main-phase decisions with an
     * empty stack, a snapshot line in ControlBrain.trace -- the game-log position, hand, untapped mana sources, land
     * drops used, our battlefield -- and the choice made. Reads state only.
     */
    static String lastSnap = "";

    static void trace(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || !g.getStack().isEmpty()) return;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (ph != PhaseType.MAIN1 && ph != PhaseType.MAIN2) return;
        traceLine(me, ai, "", null);
    }

    /**
     * spelltrace@NAME (2026-10-23, Izzet Spellementals: instant-speed decks decide on the opponent's turn and in response):
     * a snapshot at EVERY decision Forge makes (any phase, any stack), at each of our main-phase passes with an empty
     * stack, and whenever we pass with an opponent's spell or ability on the stack (choosing not to respond is a
     * decision). The stack, top first, is part of the line. Reads state only, like lftrace.
     */
    static void trace2(Player me, List<SpellAbility> ai, String src) {
        Game g = me.getGame();
        boolean ours = g.getPhaseHandler().isPlayerTurn(me), empty = g.getStack().isEmpty();
        PhaseType ph = g.getPhaseHandler().getPhase();
        boolean acted = ai != null && !ai.isEmpty();
        boolean oppOnStack = false;
        List<String> st = new ArrayList<>();
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
            Card h = si.getSourceCard();
            boolean mine = si.getActivatingPlayer() == me;
            if (!mine && st.isEmpty()) oppOnStack = true;   // the top item is the opponent's
            st.add((mine ? "US " : "OPP ") + (h == null ? "?" : h.getName()));
        }
        boolean oppEnd = !ours && empty && ph == PhaseType.END_OF_TURN;   // what we left open at the opponent's end step
        if (!acted && !(ours && empty && (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2)) && !oppOnStack && !oppEnd) return;
        traceLine(me, ai, " stack=[" + String.join("; ", st) + "]", src);
    }

    /** colours an untapped mana source can make, e.g. "UR" (C = colourless) */
    static String colours(Card c) {
        java.util.TreeSet<Character> cs = new java.util.TreeSet<>();
        for (SpellAbility ma : c.getManaAbilities()) {
            try {
                // only abilities usable now (2026-10-25: a Verge's conditional colour was listed without its land type)
                ma.setActivatingPlayer(c.getController());
                if (!ma.canPlay()) continue;
                String p = ma.getManaPart().isComboMana() ? ma.getManaPart().getComboColors(ma) : ma.getManaPart().getOrigProduced();
                for (char x : p.toCharArray()) if ("WUBRGC".indexOf(x) >= 0) cs.add(x);
                if (p.contains("Any")) for (char x : "WUBRG".toCharArray()) cs.add(x);
            } catch (Exception e) { }
        }
        StringBuilder b = new StringBuilder();
        for (char x : cs) b.append(x);
        return b.length() == 0 ? "?" : b.toString();
    }

    static Game paidGame;
    static final java.util.Map<Integer, List<String>> pool = new java.util.HashMap<>();

    /** the mana each traced player spends on each spell or ability (2026-10-25: which colour a dual paid). Forge's AI
     *  rarely gets priority over its own spell, so the payment is read from the game's events: the colours added to a
     *  player's pool since their last cast are logged at the cast ("PAID") */
    static void paid(Player me) {
        Game g = me.getGame();
        if (paidGame == g) return;
        paidGame = g; pool.clear();
        g.subscribeToEvents(new Object() {
            @com.google.common.eventbus.Subscribe
            public void on(forge.game.event.GameEvent e) {
                try {
                    if (e instanceof forge.game.event.GameEventManaPool mp && mp.mode() == forge.game.event.EventValueChangeType.Added) {
                        List<String> b = pool.computeIfAbsent(mp.player().getId(), k -> new ArrayList<>());
                        for (forge.card.MagicColor.Color c : mp.colors()) b.add(c == null ? "?" : c.name().equals("BLUE") ? "U" : c.name().substring(0, 1));
                    } else if (e instanceof forge.game.event.GameEventSpellAbilityCast sc && sc.si() != null && sc.si().getActivatingPlayer() != null) {
                        int pid = sc.si().getActivatingPlayer().getId();
                        List<String> b = pool.remove(pid);
                        if (b == null || b.isEmpty()) return;
                        for (Player p : g.getPlayers())
                            if (p.getId() == pid && AiTweaks.on("spelltrace", p))
                                ControlBrain.trace.append("@").append(g.getGameLog().getAllEntries().size()).append(" ").append(deckTag(p)).append(" T")
                                        .append(g.getPhaseHandler().getTurn()).append(" PAID: ")
                                        .append(sc.sa() == null || sc.sa().getHostCard() == null ? "?" : sc.sa().getHostCard().getName())
                                        .append(": ").append(String.join(",", b)).append("\n");
                    }
                } catch (RuntimeException x) {
                    ControlBrain.count("trace_paid_error");
                }
            }
        });
    }

    static void traceLine(Player me, List<SpellAbility> ai, String extra, String src) {
        Game g = me.getGame();
        PhaseType ph = g.getPhaseHandler().getPhase();
        StringBuilder s = new StringBuilder();
        s.append("hand=[");
        List<String> h = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Hand)) h.add(c.getName());
        java.util.Collections.sort(h);
        s.append(String.join("; ", h)).append("]");
        int mana = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isUntapped() && !c.getManaAbilities().isEmpty() && (c.isLand() || !c.isSick())) mana++;
        List<String> col = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isUntapped() && !c.getManaAbilities().isEmpty() && (c.isLand() || !c.isSick())) col.add(colours(c));
        java.util.Collections.sort(col);
        s.append(" untapped_mana_sources=").append(mana).append("(").append(String.join(",", col)).append(")");
        s.append(" land_drops=").append(me.getLandsPlayedThisTurn()).append("/").append(me.getMaxLandPlaysInfinite() ? "inf" : String.valueOf(me.getMaxLandPlays()));
        List<String> b = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            String x = c.getName();
            if (c.isCreature()) x += " " + c.getNetPower() + "/" + c.getNetToughness() + (c.isSick() && !c.hasKeyword(forge.game.keyword.Keyword.HASTE) ? " sick" : "");
            if (c.isTapped()) x += " tapped";
            b.add(x);
        }
        s.append(" battlefield=[").append(String.join("; ", b)).append("]");
        List<String> gy = new ArrayList<>(), ex = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) gy.add(c.getName());   // every card (2026-10-25: creatures were missing)
        for (Card c : me.getCardsIn(ZoneType.Exile)) ex.add(c.getName());
        java.util.Collections.sort(gy); java.util.Collections.sort(ex);
        s.append(" gy=[").append(String.join("; ", gy)).append("] exile=[").append(String.join("; ", ex)).append("]");
        s.append(" usb=[").append(board(me)).append("]");
        Player op = ProwessBrain.opponent(me);
        if (op != null) {
            s.append(" opp=[").append(board(op)).append("]");
            // the opponent's public zones (2026-10-25: Deep-Cavern Bat's exiled card showed only in the victim's file)
            List<String> og = new ArrayList<>(), oe = new ArrayList<>();
            for (Card c : op.getCardsIn(ZoneType.Graveyard)) og.add(c.getName());
            for (Card c : op.getCardsIn(ZoneType.Exile)) if (!c.isFaceDown()) oe.add(c.getName());
            java.util.Collections.sort(og); java.util.Collections.sort(oe);
            s.append(" oppgy=[").append(String.join("; ", og)).append("] oppexile=[").append(String.join("; ", oe)).append("]");
        }
        paid(me);
        String snap = s.toString();
        String choice = ai == null || ai.isEmpty() ? "pass" : choiceText(ai.get(0));
        if (src != null && !src.equals("forge")) choice += " {by " + src + "}";
        String line = "@" + g.getGameLog().getAllEntries().size() + " " + deckTag(me) + " T" + g.getPhaseHandler().getTurn() + " " + ph + ": " + snap + extra + " -> " + choice;
        if ((snap + choice).equals(lastSnap)) return;
        lastSnap = snap + choice;
        ControlBrain.trace.append(line).append("\n");
    }

    static final forge.game.keyword.Keyword[] KW = {forge.game.keyword.Keyword.FLYING, forge.game.keyword.Keyword.REACH,
            forge.game.keyword.Keyword.TRAMPLE, forge.game.keyword.Keyword.DEATHTOUCH, forge.game.keyword.Keyword.VIGILANCE,
            forge.game.keyword.Keyword.HASTE, forge.game.keyword.Keyword.MENACE, forge.game.keyword.Keyword.INDESTRUCTIBLE,
            forge.game.keyword.Keyword.LIFELINK, forge.game.keyword.Keyword.HEXPROOF};

    /** a player's permanents for the trace: "Name#id P/T kw tapped sick" (creatures), "Name#id tapped" (other
     *  nonland permanents), then "lands U/T" (untapped/total); ids let the condenser follow each card */
    static String board(Player p) {
        List<String> b = new ArrayList<>();
        int lands = 0, untapped = 0;
        boolean active = p.getGame().getPhaseHandler().isPlayerTurn(p);   // summoning sickness matters only on its own turn
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isCreature()) { lands++; if (c.isUntapped()) untapped++; continue; }
            StringBuilder x = new StringBuilder(c.getName()).append(c.isLand() ? " (land)" : "").append('#').append(c.getId());   // (land): an animated/earthbent land (2026-10-25)
            if (c.isCreature()) {
                x.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
                for (forge.game.keyword.Keyword k : KW) if (c.hasKeyword(k)) x.append(' ').append(k.toString().toLowerCase());
                if (active && c.isSick() && !c.hasKeyword(forge.game.keyword.Keyword.HASTE)) x.append(" sick");
            }
            if (!c.isCreature() && c.hasCounters())   // e.g. Earthbender Ascension's quest counters (2026-10-23)
                for (forge.game.card.CounterType ct : c.getCounters().elementSet())
                    x.append(' ').append(c.getCounters(ct)).append(' ').append(ct.toString().toLowerCase());
            if (c.isTapped()) x.append(" tapped");
            b.add(x.toString());
        }
        b.add("lands " + untapped + "/" + lands);
        return String.join("; ", b);
    }

    /** lftrace at combat: our attack declaration (ATTACK) and our block declaration (BLOCK) */
    static void traceCombat(Player me, String what, forge.game.combat.Combat combat) {
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        if (op == null || combat == null) return;
        List<String> pairs = new ArrayList<>();
        for (Card a : combat.getAttackers()) {
            List<String> bl = new ArrayList<>();
            for (Card b : combat.getBlockers(a)) bl.add(b.getName() + "#" + b.getId());
            pairs.add(a.getName() + "#" + a.getId() + (bl.isEmpty() ? "" : " blocked by " + String.join(" + ", bl)));
        }
        ControlBrain.trace.append("@").append(g.getGameLog().getAllEntries().size()).append(" ").append(deckTag(me)).append(" T").append(g.getPhaseHandler().getTurn())
                .append(" ").append(what).append(": us=[").append(board(me)).append("] opp=[").append(board(op))
                .append("] -> ").append(pairs.isEmpty() ? "none" : String.join("; ", pairs)).append("\n");
    }

    /** the deck tag on every trace line ("deck=NAME", 2026-10-25: both seats can be traced) */
    static String deckTag(Player me) {
        String d = AiTweaks.deckName(me);
        return "deck=" + (d == null ? "?" : d.replace(' ', '_'));
    }

    static String choiceText(SpellAbility sa) {
        Card h = sa.getHostCard();
        String n = h == null ? "?" : h.getName();
        if (sa.isLandAbility()) return "play land " + n;
        String d = sa.getDescription() == null ? "" : sa.getDescription().replace("\n", " ");
        if (d.length() > 70) d = d.substring(0, 70);
        String modes = "";   // the chosen modes of a charm or spree spell (2026-10-25: scorers could not see them)
        if (sa.getChosenList() != null && !sa.getChosenList().isEmpty()) {
            List<String> ms = new ArrayList<>();
            for (SpellAbility m : sa.getChosenList()) {
                String md = m.getDescription() == null ? "?" : m.getDescription().replace("\n", " ");
                ms.add(md.length() > 45 ? md.substring(0, 45) : md);
            }
            modes = " [modes: " + String.join(" + ", ms) + "]";
        }
        return (sa.isSpell() ? "cast " : "activate ") + n + (sa.isSpell() ? (isWarp(sa) ? " (warp)" : "") + modes : " (" + d + ")");
    }

    /**
     * landseq2@NAME (2026-10-23, from the one-shot report on the landseq sample, pilot/reports/lf-landseq-report.md):
     * in our main phases with an empty stack --
     *  A. payoffs first: when Forge's choice makes a landfall (a land play; Fabled Passage, Escape Tunnel or Elven
     *     Passage cracked for a land; Shared Roots, Earthbender Ascension, Lumbering Worldwagon), cast a castable
     *     payoff first: Sazh's Chocobo, Mightform Harmonizer (from hand, or from exile after a warp; by warp only if a
     *     creature that can attack would get the doubling), Icetill Explorer, Sapling Nursery;
     *  B. no idle warp: Forge's warp of Mightform Harmonizer is replaced unless it is main 1, a landfall is still
     *     available this turn, and a creature with power that can attack exists -- by our most expensive other
     *     castable spell, or nothing;
     *  C. landfall before combat: when Forge would leave main 1 while a landfall payoff is on our battlefield and a
     *     creature can attack, cast a castable Shared Roots / Earthbender Ascension / Lumbering Worldwagon first;
     *  D. Esper Origins: when Forge would pass main 2 with Esper Origins castable from hand, cast it.
     */
    static final String[] PAYOFFS2 = {CHOCOBO, HARMONIZER, EXPLORER, "Sapling Nursery"};
    static final java.util.Set<String> LANDFALL_SPELLS = java.util.Set.of("Shared Roots", "Earthbender Ascension", "Lumbering Worldwagon");

    static boolean makesLandfall2(SpellAbility sa) {
        if (sa.isLandAbility()) return true;
        Card h = sa.getHostCard();
        if (h == null) return false;
        String n = h.getName();
        if ((n.equals(PASSAGE) || n.equals(TUNNEL) || n.equals("Elven Passage")) && sa.getApi() == ApiType.ChangeZone) return true;
        return sa.isSpell() && LANDFALL_SPELLS.contains(n);
    }

    static boolean isWarp(SpellAbility sa) {
        return (sa.getDescription() + " " + sa).toLowerCase().contains("warp");
    }

    /** castable spell abilities of our cards named N in zone Z (warp variants included) */
    static List<SpellAbility> castable(Player me, ZoneType z, String n) {
        CardCollection cs = new CardCollection();
        for (Card c : me.getCardsIn(z)) if (n == null || named(c, n)) cs.add(c);
        List<SpellAbility> out = new ArrayList<>();
        if (cs.isEmpty()) return out;
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(cs, me)) {
            if (!sa.isSpell()) continue;
            sa.setActivatingPlayer(me);
            try {
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) out.add(sa);
            } catch (Exception e) {
                continue;
            }
        }
        return out;
    }

    static boolean attackerWithPower(Player me) {
        for (Card c : me.getCreaturesInPlay()) if (canAttackThisTurn(c) && c.getNetPower() > 0) return true;
        return false;
    }

    static boolean landfallLeft(Player me) {
        boolean drop = me.getMaxLandPlaysInfinite() || me.getLandsPlayedThisTurn() < me.getMaxLandPlays();
        if (drop) for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isLand()) return true;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isUntapped() && (named(c, PASSAGE) || named(c, TUNNEL) || named(c, "Elven Passage"))) return true;
        for (String n : LANDFALL_SPELLS) if (!castable(me, ZoneType.Hand, n).isEmpty()) return true;
        return false;
    }

    static boolean payoffOnBoard(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) for (String p : PAYOFFS2) if (named(c, p)) return true;
        return false;
    }

    static SpellAbility bestOther(Player me) {
        SpellAbility best = null;
        for (SpellAbility sa : castable(me, ZoneType.Hand, null)) {
            if (named(sa.getHostCard(), HARMONIZER) || isWarp(sa)) continue;
            if (best == null || sa.getHostCard().getCMC() > best.getHostCard().getCMC()) best = sa;
        }
        return best;
    }

    /** A's choice: a castable payoff to cast before a landfall (Chocobo, Harmonizer from hand or exile -- by warp only
     *  with an attacker for its doubling --, Explorer, Nursery), or null */
    static SpellAbility payoffFirst(Player me, PhaseType ph, boolean v3) {
        Game g = me.getGame();
        for (String p : PAYOFFS2) {
            SpellAbility warp = null;
            for (ZoneType z : new ZoneType[]{ZoneType.Hand, ZoneType.Exile})
                for (SpellAbility sa : castable(me, z, p)) {
                    if (isWarp(sa)) { if (warp == null) warp = sa; continue; }
                    ControlBrain.count("landseq2_payoff_first");
                    return sa;
                }
            if (warp != null && ph == PhaseType.MAIN1 && (v3 ? safeAttacker(me, true) != null : attackerWithPower(me))) {
                if (v3) { warpGame = g; warpTurn = g.getPhaseHandler().getTurn(); }
                ControlBrain.count("landseq2_payoff_first_warp");
                return warp;
            }
        }
        return null;
    }

    /**
     * landseq6 (2026-10-23, from pilot/reports/lf-landseq5-10-score-v2.md, fetch_basic x4): when Forge plays a basic land
     * while a landfall payoff is on our battlefield (Chocobo, Harmonizer, Nursery, Earthbender Ascension) and a fetch
     * can be played (hand, or graveyard with Icetill Explorer), play the fetch: two landfalls instead of one. Unless the
     * basic's mana is needed this turn: a spell in hand costs exactly one more than our untapped sources, and the fetch
     * gives no mana now (Fabled Passage's land enters untapped once we control 4+ lands).
     */
    static SpellAbility fetchOverBasic(Player me, SpellAbility pick, boolean v7) {
        Card b = pick.getHostCard();
        if (!pick.isLandAbility() || b == null || !b.getType().isBasic()) return null;
        boolean payoff = false;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (named(c, CHOCOBO) || named(c, "Sapling Nursery") || named(c, "Earthbender Ascension")
                    || (named(c, HARMONIZER) && (!v7 || attackerWithPower(me)))) payoff = true;   // landseq7: a doubling must have an attacker
        if (!payoff) return null;
        SpellAbility f = landPlay(me, true);
        if (f == null) return null;
        int src = untappedSources(me);
        int lands = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.isLand()) lands++;
        boolean fetchGivesMana = named(f.getHostCard(), PASSAGE) && lands >= 3;
        boolean need = false;
        boolean v8 = false;
        for (int v = 8; v <= 16; v++) v8 |= AiTweaks.on("landseq" + v, me);
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) continue;
            if (v8 && named(c, RESOLVE) && !resolveUseful(me)) continue;   // landseq8: a useless Resolve needs no mana
            int cost = named(c, HARMONIZER) ? Math.min(3, c.getCMC()) : c.getCMC();
            if (cost == src + 1) need = true;
        }
        if (need && !fetchGivesMana) { ControlBrain.count("landseq6_basic_kept_for_mana"); return null; }
        ControlBrain.count("landseq6_fetch_over_basic");
        return f;
    }

    static final String RESOLVE = "Meltstrider's Resolve", OURO = "Ouroboroid";

    /** every landseq version that runs seq2 (2026-10-23: one helper instead of a growing chain of flags) */
    static boolean seqOn(Player p) {
        for (int v = 2; v <= 17; v++) if (AiTweaks.on("landseq" + v, p)) return true;
        return false;
    }

    /** landseq3 and later (lethal mode, safe attacks, trigger aiming) */
    static boolean v3On(Player p) {
        for (int v = 3; v <= 17; v++) if (AiTweaks.on("landseq" + v, p)) return true;
        return false;
    }

    /** landseq8 (pilot/reports/blind-landfall-scores.md, G10/G15/G1): Meltstrider's Resolve is worth casting only if one
     *  of our creatures would kill an opposing creature in the fight (power >= its remaining toughness, or deathtouch) */
    static boolean resolveUseful(Player me) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return false;
        for (Card x : me.getCreaturesInPlay()) {
            if (x.getNetPower() <= 0) continue;
            for (Card y : op.getCreaturesInPlay()) {
                if (y.hasKeyword(forge.game.keyword.Keyword.HEXPROOF) || y.hasKeyword(forge.game.keyword.Keyword.SHROUD)) continue;
                if (x.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH) || x.getNetPower() >= y.getNetToughness() - y.getDamage()) return true;
            }
        }
        return false;
    }

    /** landseq9 (blind report G11): after Forge's blocks, move single blockers between attackers when that turns a lethal
     *  hit into survival, or prevents more damage with no more of our creatures lost and no fewer attackers killed.
     *  Damage model: the attacker kills blockers in the cheapest order (deathtouch: 1 each); a trampler deals what is
     *  left after lethal damage to every blocker; an attacker dies if a blocker has deathtouch or their power sums to
     *  its toughness. */
    static long[] blockEval(Player me, forge.game.combat.Combat combat) {
        long dmg = 0, lost = 0, kills = 0;
        for (Card a : combat.getAttackers()) {
            if (combat.getDefenderPlayerByAttacker(a) != me) continue;
            List<Card> bl = new ArrayList<>(combat.getBlockers(a));
            long p = Math.max(0, a.getNetPower());
            if (bl.isEmpty()) { dmg += p; continue; }
            boolean dt = a.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH);
            bl.sort((x, y) -> Long.compare(dt ? 1 : x.getNetToughness() - x.getDamage(), dt ? 1 : y.getNetToughness() - y.getDamage()));
            long rem = p; boolean all = true;
            for (Card b : bl) {
                long need = dt ? 1 : Math.max(0, b.getNetToughness() - b.getDamage());
                if (b.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) { all = false; break; }
                if (rem >= need && need > 0) { rem -= need; lost++; } else { all = false; break; }
            }
            if (a.hasKeyword(forge.game.keyword.Keyword.TRAMPLE) && all) dmg += rem;
            long bp = 0; boolean bdt = false;
            for (Card b : bl) { bp += Math.max(0, b.getNetPower()); if (b.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH) && b.getNetPower() > 0) bdt = true; }
            if (!a.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE) && (bdt || bp >= a.getNetToughness() - a.getDamage())) kills++;
        }
        return new long[]{dmg, lost, kills};
    }

    static boolean better(Player me, long[] n, long[] o) {
        boolean nAlive = me.getLife() - n[0] > 0, oAlive = me.getLife() - o[0] > 0;
        if (nAlive != oAlive) return nAlive;
        return n[0] < o[0] && n[1] <= o[1] && n[2] >= o[2];
    }

    static void fixBlocks(Player me, forge.game.combat.Combat combat) {
        if (combat == null) return;
        for (int round = 0; round < 8; round++) {
            long[] cur = blockEval(me, combat);
            boolean moved = false;
            for (Card b : new ArrayList<>(combat.getAllBlockers())) {
                if (b.getController() != me) continue;
                List<Card> from = new ArrayList<>(combat.getAttackersBlockedBy(b));
                if (from.size() != 1) continue;
                Card a1 = from.get(0);
                if (a1.hasKeyword(forge.game.keyword.Keyword.MENACE) && combat.getBlockers(a1).size() <= 2) continue;
                for (Card a2 : new ArrayList<>(combat.getAttackers())) {   // a copy: the loop changes the combat
                    if (a2 == a1 || combat.getDefenderPlayerByAttacker(a2) != me) continue;
                    if (a2.hasKeyword(forge.game.keyword.Keyword.MENACE) && combat.getBlockers(a2).isEmpty()) continue;
                    boolean can;
                    try { can = CombatUtil.canBlock(a2, b); } catch (Exception e) { can = false; }
                    if (!can) continue;
                    combat.removeBlockAssignment(a1, b);
                    combat.addBlocker(a2, b);
                    long[] n = blockEval(me, combat);
                    if (better(me, n, cur)) { ControlBrain.count("landseq9_block_moved"); moved = true; break; }
                    combat.removeBlockAssignment(a2, b);
                    combat.addBlocker(a1, b);
                }
                if (moved) break;
            }
            if (!moved) return;
        }
    }

    /** landseq10 (blind report G4): the opponent's next combat with Ouroboroid. At the beginning of its combat each
     *  Ouroboroid puts X +1/+1 counters on each of its creatures, X = its power (the first one's counters grow the
     *  next). Every opposing creature then attacks; each of our creatures still untapped then blocks one attacker
     *  (the biggest first). Threat = the rest reaches our life. EXTRA: our creatures that will be tapped as well. */
    static boolean ouroThreat(Player me, int extraTapped) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return false;
        List<Card> ouros = new ArrayList<>();
        for (Card c : op.getCreaturesInPlay()) if (named(c, OURO)) ouros.add(c);
        if (ouros.isEmpty()) return false;
        long acc = 0;
        for (Card o : ouros) acc += o.getNetPower() + acc;
        List<Long> pw = new ArrayList<>();
        for (Card c : op.getCreaturesInPlay()) pw.add(Math.max(0, c.getNetPower()) + acc);
        pw.sort((x, y) -> Long.compare(y, x));
        int blockers = 0;
        for (Card c : me.getCreaturesInPlay()) if (c.isUntapped()) blockers++;
        blockers = Math.max(0, blockers - extraTapped);
        long dmg = 0;
        for (int i = blockers; i < pw.size(); i++) dmg += pw.get(i);
        return dmg >= me.getLife();
    }

    /** creatures that paying COST for HOST's ability would have to tap: COST minus our untapped non-creature mana lands */
    static int landManaShortfall(Player me, Card host, int cost) {
        int lands = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c != host && c.isLand() && !c.isCreature() && c.isUntapped() && !c.getManaAbilities().isEmpty()) lands++;
        return Math.max(0, cost - lands);
    }

    /** landseq10 attacks: unless our attack is lethal, keep home (smallest first) the attackers whose absence as
     *  blockers would let the Ouroboroid crack-back kill us */
    static void ouroAttack(Player me, forge.game.combat.Combat combat) {
        if (lethalOn(me)) return;
        for (int guard = 0; guard < 20; guard++) {
            List<Card> att = new ArrayList<>();
            for (Card c : combat.getAttackers()) if (c.getController() == me && !c.hasKeyword(forge.game.keyword.Keyword.VIGILANCE)) att.add(c);
            if (att.isEmpty()) return;
            // attacking creatures are tapped next turn: count them out of the blockers
            if (!ouroThreat(me, att.size())) return;
            att.sort((x, y) -> Integer.compare(x.getNetPower(), y.getNetPower()));
            combat.removeFromCombat(att.get(0));
            ControlBrain.count("landseq10_attacker_kept_home");
        }
    }

    /**
     * landseq12/13 (2026-10-23, user: "focus on how to better manage and prevent our opponent from being given the
     * opportunity to launch a lethal attack"): the opponent's next attack. Every opposing creature attacks (they untap),
     * with the counters each Ouroboroid adds at the start of its combat (X = its power; the first one's counters grow the
     * next). Our creatures still untapped then (not attacking now, not in AWAY) block the biggest attackers, our
     * toughest blocker on the biggest; a blocked trampler deals what exceeds the blocker's toughness. We survive if the
     * damage is below our life.
     */
    static boolean crackSurvives(Player me, List<Card> away) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return true;
        long acc = 0;
        for (Card o : op.getCreaturesInPlay()) if (named(o, OURO)) acc += o.getNetPower() + acc;
        List<long[]> att = new ArrayList<>();   // [power, trample]
        for (Card c : op.getCreaturesInPlay())
            att.add(new long[]{Math.max(0, c.getNetPower()) + acc, c.hasKeyword(forge.game.keyword.Keyword.TRAMPLE) ? 1 : 0});
        att.sort((x, y) -> Long.compare(y[0], x[0]));
        List<Long> bl = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) if (c.isUntapped() && !away.contains(c)) bl.add((long) Math.max(1, c.getNetToughness() - c.getDamage()));
        bl.sort((x, y) -> Long.compare(y, x));
        long dmg = 0;
        for (int i = 0; i < att.size(); i++) {
            long p = att.get(i)[0];
            if (i < bl.size()) dmg += att.get(i)[1] == 1 ? Math.max(0, p - bl.get(i)) : 0;
            else dmg += p;
        }
        return dmg < me.getLife();
    }

    /** the creatures a cost of N mana would tap: our untapped creatures with a mana ability first (Elves, earthbent lands) */
    static List<Card> manaCreaturesFor(Player me, int n) {
        List<Card> out = new ArrayList<>(), rest = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) if (c.isUntapped()) (c.getManaAbilities().isEmpty() ? rest : out).add(c);
        out.addAll(rest);
        return new ArrayList<>(out.subList(0, Math.min(n, out.size())));
    }

    /** Forge's own judgement that an all-out attack is lethal (AiAttackController.doAssault, its "attack with everything"
     *  path; a pure calculation over the attackers, blockers and life). user, 2026-10-23: "never stop Forge if Forge thinks
     *  the attack is lethal". On any failure: assume lethal (do not stop the attack). */
    static boolean forgeThinksLethal(Player me) {
        try {
            forge.ai.AiAttackController c = new forge.ai.AiAttackController(me);
            java.lang.reflect.Method m = forge.ai.AiAttackController.class.getDeclaredMethod("doAssault");
            m.setAccessible(true);
            return (Boolean) m.invoke(c);
        } catch (Exception e) {
            return true;
        }
    }

    /** landseq15 attacks: never against Forge's lethal judgement (or our lethal mode); hold back only when holding back
     *  saves us from a lethal crack-back, and then the attackers with the least power first (least damage given up);
     *  when we die either way, attack as planned. */
    static void crackAttack15(Player me, forge.game.combat.Combat combat) {
        if (lethalOn(me) || forgeThinksLethal(me)) { ControlBrain.count("landseq15_lethal_attack_kept"); return; }
        List<Card> att = new ArrayList<>();
        for (Card c : combat.getAttackers()) if (c.getController() == me && !c.hasKeyword(forge.game.keyword.Keyword.VIGILANCE)) att.add(c);
        if (att.isEmpty() || crackSurvives(me, att) || !crackSurvives(me, new ArrayList<>())) return;
        att.sort((x, y) -> Integer.compare(x.getNetPower(), y.getNetPower()));
        List<Card> still = new ArrayList<>(att);
        for (Card c : att) {
            combat.removeFromCombat(c); still.remove(c);
            ControlBrain.count("landseq15_attacker_kept_home");
            if (crackSurvives(me, still)) return;
        }
    }

    /** landseq12/13 attacks: if this attack lets the opponent kill us next turn and keeping attackers home would not,
     *  keep home the fewest (best blockers first: highest toughness); if we die either way: landseq12 attacks as
     *  planned, landseq13 keeps every attacker home (blockers deter). Our own lethal attack is never held back. */
    static void crackAttack(Player me, forge.game.combat.Combat combat, boolean holdAll) {
        if (lethalOn(me)) return;
        List<Card> att = new ArrayList<>();
        for (Card c : combat.getAttackers()) if (c.getController() == me && !c.hasKeyword(forge.game.keyword.Keyword.VIGILANCE)) att.add(c);
        if (att.isEmpty() || crackSurvives(me, att)) return;
        if (!crackSurvives(me, new ArrayList<>())) {
            if (!holdAll) return;
            for (Card c : att) combat.removeFromCombat(c);
            ControlBrain.count("landseq13_all_kept_home");
            return;
        }
        att.sort((x, y) -> Integer.compare(y.getNetToughness(), x.getNetToughness()));
        List<Card> still = new ArrayList<>(att);
        for (Card c : att) {
            combat.removeFromCombat(c); still.remove(c);
            ControlBrain.count("landseq12_attacker_kept_home");
            if (crackSurvives(me, still)) return;
        }
    }

    static List<SpellAbility> seq2(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        boolean v15 = AiTweaks.on("landseq15", me);
        boolean v14 = v15 || AiTweaks.on("landseq14", me) || AiTweaks.on("landseq16", me);
        boolean v13 = AiTweaks.on("landseq13", me), v12 = AiTweaks.on("landseq12", me);
        boolean v11 = v12 || v13 || v14 || AiTweaks.on("landseq11", me);
        boolean v10 = AiTweaks.on("landseq10", me);
        boolean v9 = v10 || v11 || AiTweaks.on("landseq9", me);
        boolean v8 = v9 || AiTweaks.on("landseq8", me);
        boolean v17 = AiTweaks.on("landseq17", me);   // landseq7 + the z>=1.0 bundle: landseq5's Resolve fix and doublings last
        boolean v7 = v8 || v17 || AiTweaks.on("landseq7", me);
        boolean v6 = v7 || AiTweaks.on("landseq6", me);
        boolean v5 = v6 || AiTweaks.on("landseq5", me);
        boolean v4 = v5 || AiTweaks.on("landseq4", me);
        boolean v3 = v4 || AiTweaks.on("landseq3", me);
        if (v3) { List<SpellAbility> l = lethalStep(me, ai); if (l != ai) return l; }
        if (!g.getPhaseHandler().isPlayerTurn(me) || !g.getStack().isEmpty()) return ai;
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (ph != PhaseType.MAIN1 && ph != PhaseType.MAIN2) return ai;
        SpellAbility pick = ai == null || ai.isEmpty() ? null : ai.get(0);
        if (v8 && pick != null && pick.isSpell() && pick.getHostCard() != null && named(pick.getHostCard(), RESOLVE)
                && !resolveUseful(me)) {                                                   // landseq8: no useless Resolve
            SpellAbility o = null;
            for (SpellAbility sa : castable(me, ZoneType.Hand, null)) {
                Card h = sa.getHostCard();
                if (h.isLand() || named(h, RESOLVE) || isWarp(sa)) continue;
                if (o == null || h.getCMC() > o.getHostCard().getCMC()) o = sa;
            }
            ControlBrain.count(o == null ? "landseq8_resolve_vetoed" : "landseq8_resolve_replaced");
            return o == null ? null : List.of(o);
        }
        if (v10 && pick != null && !pick.isSpell() && pick.getHostCard() != null && named(pick.getHostCard(), "Ba Sing Se")
                && !pick.isManaAbility() && ouroThreat(me, landManaShortfall(me, pick.getHostCard(), 3))) {   // landseq10
            ControlBrain.count("landseq10_basingse_vetoed");
            return null;
        }
        if ((v12 || v13 || v15) && pick != null && !pick.isSpell() && pick.getHostCard() != null && named(pick.getHostCard(), "Ba Sing Se")
                && !pick.isManaAbility() && !(v15 && pick.isLandAbility())) {              // landseq12/13/15: don't tap our way into a lethal crack-back
            List<Card> tapped = manaCreaturesFor(me, landManaShortfall(me, pick.getHostCard(), 3));
            if (crackSurvives(me, new ArrayList<>()) && !crackSurvives(me, tapped)) {
                ControlBrain.count("landseq12_basingse_vetoed");
                return null;
            }
        }
        if (v5 && pick != null && !pick.isSpell() && pick.getHostCard() != null && named(pick.getHostCard(), "Ba Sing Se")
                && !pick.isManaAbility() && !(v14 && pick.isLandAbility())) {               // landseq5: spells before Ba Sing Se (v14: its ability, not the land play)
            SpellAbility best = null;
            for (SpellAbility sa : castable(me, ZoneType.Hand, null)) {
                Card h = sa.getHostCard();
                if (h.isLand() || isWarp(sa)) continue;
                if (!(h.isCreature() || h.isArtifact() || h.isEnchantment())) continue;   // permanents (Worldwagon, creatures)
                if ((v11 || v17) && named(h, RESOLVE) && !resolveUseful(me)) continue;  // landseq11/17: landseq5 cast useless Resolves
                if (best == null || h.getCMC() > best.getHostCard().getCMC()) best = sa;
            }
            if (best != null) { ControlBrain.count("landseq5_spell_before_basingse"); return List.of(best); }
        }
        if (pick != null && makesLandfall2(pick)) {                                   // A
            SpellAbility p = payoffFirst(me, ph, v3);
            if (p != null) return List.of(p);
            if (v6 && !v7) { SpellAbility f = fetchOverBasic(me, pick, false); if (f != null) return List.of(f); }
            // landseq4 P1: Earthbender Ascension before the turn's other landfalls (its quest counters count them)
            if (v4 && ph == PhaseType.MAIN1 && !(pick.isSpell() && named(pick.getHostCard(), "Earthbender Ascension")))
                for (SpellAbility sa : castable(me, ZoneType.Hand, "Earthbender Ascension")) {
                    ControlBrain.count("landseq4_ascension_first");
                    return List.of(sa);
                }
            if (v7) { SpellAbility f = fetchOverBasic(me, pick, true); if (f != null) return List.of(f); }   // landseq7: after Ascension
            return ai;
        }
        if (pick != null && pick.isSpell() && pick.getHostCard() != null && named(pick.getHostCard(), HARMONIZER) && isWarp(pick)) {   // B
            if (ph == PhaseType.MAIN1 && landfallLeft(me) && (v3 ? safeAttacker(me, true) != null : attackerWithPower(me))) {
                if (v3) { warpGame = g; warpTurn = g.getPhaseHandler().getTurn(); }
                return ai;
            }
            SpellAbility o = bestOther(me);
            ControlBrain.count(o == null ? "landseq2_warp_vetoed" : "landseq2_warp_replaced");
            return o == null ? null : List.of(o);
        }
        if (pick == null && ph == PhaseType.MAIN1 && payoffOnBoard(me) && attackerWithPower(me)) {          // C
            if (v4) {   // landseq4 fix: payoffs before the landfall spell this part casts (game 2 of the landseq3 report)
                SpellAbility p = payoffFirst(me, ph, v3);
                if (p != null) return List.of(p);
            }
            for (String n : LANDFALL_SPELLS)
                for (SpellAbility sa : castable(me, ZoneType.Hand, n)) {
                    ControlBrain.count("landseq2_landfall_precombat");
                    return List.of(sa);
                }
        }
        if (pick == null && ph == PhaseType.MAIN2)                                                    // D
            for (SpellAbility sa : castable(me, ZoneType.Hand, "Esper Origins")) {
                ControlBrain.count("landseq2_esper");
                return List.of(sa);
            }
        if (v3 && pick == null && ph == PhaseType.MAIN2                                               // E
                && (count(me, ZoneType.Battlefield, "Sapling Nursery") == 0 || untappedSources(me) >= 6))
            for (SpellAbility sa : castable(me, ZoneType.Graveyard, "Esper Origins")) {
                ControlBrain.count("landseq3_esper_flashback");
                return List.of(sa);
            }
        return ai;
    }

    /**
     * landseq8@NAME — landseq7 + Meltstrider's Resolve only when its fight kills (resolveUseful).
 * landseq9@NAME — landseq8 + block reassignment (fixBlocks).
 * landseq10@NAME — landseq9 + the Ouroboroid crack-back (ouroThreat: attacks and Ba Sing Se payments).
 * landseq17@NAME — the bundle (user, 2026-10-23: "bundle at 1.0 and greater"): landseq7 + landseq5 casts no useless Resolve
 *  (landseq11, z 2.04) + Harmonizer doublings last (landseq16, z 1.46). Not: Resolve check (0.58), block moves (0.38),
 *  Ba Sing Se ability fix (-0.17), crack-back (-0.47).
 * landseq16@NAME — landseq14 + Harmonizer doublings resolve after our other landfall triggers (order16).
 * landseq14@NAME — landseq11 with the Ba Sing Se check meaning its activated ability only (not playing the land).
 * landseq15@NAME — landseq14 + crack-back check that never stops an attack Forge judges lethal (forgeThinksLethal),
 *  holds back only when that saves us, and the attackers with the least power first.
 * landseq11@NAME — landseq9 + no useless Resolve from landseq5 + block-loop crash fix.
 * landseq12/13@NAME — landseq11 + the lethal crack-back check (crackSurvives): 12 acts only when holding back saves us; 13 also
 *  keeps everything home when we would die either way.
 * landseq7@NAME — landseq6 with its two clear misfires fixed (seeds 517, 1148): the fetch rule runs after Ascension-first, and
 *  a Harmonizer counts as a payoff only if a creature that can attack this turn would get the doubling.
 * landseq6@NAME — landseq5 + a fetch instead of a basic when a payoff is on board (fetchOverBasic).
 *
 * landseq5@NAME (2026-10-23, A/B of the recurring judgement call in pilot/reports/lf-landseq4-10-report.md): landseq4, plus
 *  when Forge would activate Ba Sing Se's earthbend, cast our most expensive castable permanent spell from hand instead
 *  (creatures, Lumbering Worldwagon; not by warp). The earthbend can still follow if mana is left.
 *
 * landseq4@NAME (2026-10-23, from pilot/reports/lf-landseq3-10-report.md): landseq3, plus Earthbender Ascension cast
 *  before the turn's other landfalls (after the payoffs), and payoffs first also before part C's landfall spell.
 *
 * landseq3@NAME (2026-10-23, from pilot/reports/lf-landseq2-report.md): landseq2, plus
     *  E. Esper Origins flashback from the graveyard when Forge would pass main 2 (6+ untapped sources with Sapling
     *     Nursery out, to keep {1}{G} for it);
     *  F. the warp needs a SAFE attacker (safeAttacker: can attack, power 1+, no mana ability -- it may pay for the
     *     warp --, and no untapped opposing creature that could block it has deathtouch or power >= its toughness);
     *     the turn's safe attackers are then made to attack;
     *  G. safe attacks: a safe attacker that Forge keeps home attacks, unless it is needed for defence (the
     *     opponent's creatures' total power >= our life);
     *  H. lethal mode, main 1: the best attacker's power after this turn's remaining landfalls (each doubled by every
     *     Harmonizer; Chocobo +1 each), against the opponent's best blocks (trample: minus the toughness of every
     *     untapped creature that can block it; no trample: 0 if any can block it). If that reaches the opponent's
     *     life: every Harmonizer and Earthbender Ascension trigger targets that creature, a fetch is played before a
     *     basic, every landfall is made before combat (lands, fetch cracks), and every creature attacks.
     */
    static Game warpGame, lethalGame; static int warpTurn = -1, lethalTurn = -1; static Card lethalTarget;

    static int untappedSources(Player me) {
        int mana = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isUntapped() && !c.getManaAbilities().isEmpty() && (c.isLand() || !c.isSick())) mana++;
        return mana;
    }

    static boolean kills(Card blocker, Card attacker) {
        return blocker.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH) || blocker.getNetPower() >= attacker.getNetToughness() - attacker.getDamage();
    }

    static Card safeAttacker(Player me, boolean any) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return null;
        for (Card c : me.getCreaturesInPlay()) {
            if (!canAttackThisTurn(c) || c.getNetPower() < 1 || !c.getManaAbilities().isEmpty()) continue;
            boolean safe = true;
            for (Card b : op.getCreaturesInPlay()) {
                if (b.isTapped()) continue;
                boolean can;
                try { can = CombatUtil.canBlock(c, b); } catch (Exception e) { can = true; }
                if (can && kills(b, c)) { safe = false; break; }
            }
            if (safe) return c;
        }
        return null;
    }

    static boolean defenceNeeded(Player me) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return true;
        long p = 0;
        for (Card b : op.getCreaturesInPlay()) p += Math.max(0, b.getNetPower());
        return p >= me.getLife();
    }

    static boolean lethalOn(Player me) {
        return lethalGame == me.getGame() && lethalTurn == me.getGame().getPhaseHandler().getTurn()
                && lethalTarget != null && lethalTarget.isInPlay() && lethalTarget.getController() == me;
    }

    /** H: plan (once per main 1) and, while on, the next landfall step */
    static List<SpellAbility> lethalStep(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1 || !g.getStack().isEmpty()) return ai;
        Player op = ProwessBrain.opponent(me);
        if (op == null) return ai;
        if (!lethalOn(me)) {
            int lf = remainingLandfalls(me, null);
            long harm = count(me, ZoneType.Battlefield, HARMONIZER);
            Card best = null; long bestD = -1;
            for (Card x : me.getCreaturesInPlay()) {
                if (!canAttackThisTurn(x)) continue;
                long p = project(x, lf, harm);
                long d;
                List<Card> bl = new ArrayList<>();
                for (Card b : op.getCreaturesInPlay()) {
                    boolean can;
                    try { can = b.isUntapped() && CombatUtil.canBlock(x, b); } catch (Exception e) { can = true; }
                    if (can) bl.add(b);
                }
                if (x.hasKeyword(forge.game.keyword.Keyword.TRAMPLE)) {
                    long tough = 0;
                    for (Card b : bl) tough += Math.max(0, b.getNetToughness() - b.getDamage());
                    d = p - tough;
                } else d = bl.isEmpty() ? p : 0;
                if (d > bestD) { bestD = d; best = x; }
            }
            if (best == null || bestD < op.getLife()) return ai;
            lethalGame = g; lethalTurn = g.getPhaseHandler().getTurn(); lethalTarget = best;
            ControlBrain.count("landseq3_lethal_planned");
        }
        SpellAbility pick = ai == null || ai.isEmpty() ? null : ai.get(0);
        // a fetch before a basic
        if (pick != null && pick.isLandAbility() && pick.getHostCard() != null && pick.getHostCard().getType().isBasic()) {
            SpellAbility f = landPlay(me, true);
            if (f != null) { ControlBrain.count("landseq3_lethal_fetch_first"); return List.of(f); }
        }
        if (pick == null) {   // Forge would leave main 1: make the remaining landfalls first
            SpellAbility l = landPlay(me, true);
            if (l == null) l = landPlay(me, false);
            if (l == null) l = fetchCrack(me);
            if (l != null) { ControlBrain.count("landseq3_lethal_landfall"); return List.of(l); }
        }
        return ai;
    }

    /* From scan gl-s10 (2026-10-25), two missed lethals the single-target lethal plan (landseq3) cannot see:
     *   landtunnel@NAME   F1  when Forge's next step in main 1 is another land drop, a fetch crack or passing, and a
     *                         creature of ours with power 2 or less would reach lethal power from the turn's remaining
     *                         landfalls, play Escape Tunnel first: the "landfall" stroke then makes that creature
     *                         unblockable while it is still small (g0 T12: Passage first left the Chocobo blockable)
     *   landlethal@NAME   F2  when the all-out attack is already lethal against the opponent's best blocks, cast
     *                         nothing more before combat (no attacker tapped for mana) and attack with everything
     *                         (g8 T11: the 12/12 earthbent land was tapped for Earthbender Ascension, no attack) */
    static boolean f1(Player p) { return AiTweaks.on("landtunnel", p); }
    static boolean f2(Player p) { return AiTweaks.on("landlethal", p); }

    static List<SpellAbility> tunnelFirst(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        if (!f1(me) || !g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1 || !g.getStack().isEmpty()) return ai;
        SpellAbility pick = ai == null || ai.isEmpty() ? null : ai.get(0);
        boolean landStep = pick == null || pick.isLandAbility() || (pick.getHostCard() != null && fetchType(pick.getHostCard()) && pick.getApi() == ApiType.ChangeZone);
        if (!landStep || (pick != null && pick.getHostCard() != null && named(pick.getHostCard(), TUNNEL))) return ai;
        Player op = ProwessBrain.opponent(me);
        if (op == null) return ai;
        int lf = Math.max(0, remainingLandfalls(me, null) - 1);   // the Tunnel is spent on the stroke, not cracked
        long harm = count(me, ZoneType.Battlefield, HARMONIZER);
        boolean lethal = false;
        for (Card x : me.getCreaturesInPlay())
            if (canAttackThisTurn(x) && x.getNetPower() <= 2 && project(x, lf, harm) >= op.getLife()) lethal = true;
        if (!lethal) return ai;
        boolean drop = me.getMaxLandPlaysInfinite() || me.getLandsPlayedThisTurn() < me.getMaxLandPlays();
        if (!drop) return ai;
        List<Card> tunnels = new ArrayList<>();   // in hand, or in the graveyard with Icetill Explorer (as landPlay)
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (named(c, TUNNEL)) tunnels.add(c);
        if (count(me, ZoneType.Battlefield, EXPLORER) > 0)
            for (Card c : me.getCardsIn(ZoneType.Graveyard)) if (named(c, TUNNEL)) tunnels.add(c);
        for (Card c : tunnels) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (!sa.isLandAbility()) continue;
                try {
                    if (sa.canPlay() && me.canPlayLand(c, false, sa)) { ControlBrain.count("landtunnel_first"); return List.of(sa); }
                } catch (Exception e) {
                    continue;
                }
            }
        }
        return ai;
    }

    /** F1 (with landtunnel): Forge's AI picks the targets of simultaneous triggers itself (neither playTrigger nor
     *  chooseTargetsFor is called), so the stroke's "every Harmonizer doubling on the stroke creature" (rule "landfall")
     *  never acted (2026-10-25: no stroke_doubling in the scanned games). We get priority while those triggers wait on
     *  the stack: while a stroke is on, each of our Harmonizer doublings there is retargeted to the stroke creature. */
    static void retargetStroke(Player me) {
        if (!strokeOn(me)) return;
        for (forge.game.spellability.SpellAbilityStackInstance si : me.getGame().getStack()) {
            SpellAbility sa = si.getSpellAbility();
            if (sa == null || si.getActivatingPlayer() != me || sa.getHostCard() == null || !named(sa.getHostCard(), HARMONIZER)
                    || !sa.usesTargeting() || !sa.canTarget(strokeTarget)) continue;
            if (sa.getTargets() != null && sa.getTargets().contains(strokeTarget)) continue;
            forge.game.spellability.TargetChoices nt = new forge.game.spellability.TargetChoices();
            nt.add(strokeTarget);
            try {
                sa.resetTargets();
                sa.getTargets().add(strokeTarget);   // what resolves (the stack entry's own record alone is not enough)
                si.updateTarget(nt, new java.util.HashSet<>());
                ControlBrain.count("landtunnel_doubling");
            } catch (RuntimeException e) {
                ControlBrain.count("landtunnel_error_" + e.getClass().getSimpleName());
            }
        }
    }

    static boolean boardLethal(Player me) {
        Player op = ProwessBrain.opponent(me);
        return op != null && SpellBrain.unblocked(me, op, java.util.Collections.emptyList()) >= op.getLife();
    }

    static List<SpellAbility> lethalFirst(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        if (!f2(me) || ai == null || ai.isEmpty() || !g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1
                || !g.getStack().isEmpty()) return ai;
        SpellAbility pick = ai.get(0);
        if (pick.isLandAbility() || !boardLethal(me)) return ai;
        ControlBrain.count("landlethal_held");
        return null;
    }

    static void lethalAttack(Player me, forge.game.combat.Combat combat) {
        if (!f2(me) || !boardLethal(me)) return;
        Player op = ProwessBrain.opponent(me);
        for (Card c : me.getCreaturesInPlay())
            if (!combat.isAttacking(c) && canAttackThisTurn(c) && CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("landlethal_attacker");
            }
    }

    static SpellAbility landPlay(Player me, boolean fetchOnly) {
        boolean drop = me.getMaxLandPlaysInfinite() || me.getLandsPlayedThisTurn() < me.getMaxLandPlays();
        if (!drop) return null;
        List<Card> lands = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.isLand() && (!fetchOnly || fetchType(c) || named(c, "Elven Passage"))) lands.add(c);
        if (count(me, ZoneType.Battlefield, EXPLORER) > 0)
            for (Card c : me.getCardsIn(ZoneType.Graveyard)) if (c.isLand() && (!fetchOnly || fetchType(c) || named(c, "Elven Passage"))) lands.add(c);
        for (Card c : lands)
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (!sa.isLandAbility()) continue;
                try {
                    if (sa.canPlay() && me.canPlayLand(c, false, sa)) return sa;
                } catch (Exception e) {
                    continue;
                }
            }
        return null;
    }

    static SpellAbility fetchCrack(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isUntapped() || !(fetchType(c) || named(c, "Elven Passage"))) continue;
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() != ApiType.ChangeZone) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) return sa;
                } catch (Exception e) {
                    continue;
                }
            }
        }
        return null;
    }

    /** H: Harmonizer and Earthbender Ascension triggers at the lethal creature */
    static Boolean playTrigger3(Player me, WrappedAbility wa) {
        Card host = wa.getHostCard();
        if (host == null || !(named(host, HARMONIZER) || named(host, "Earthbender Ascension")) || !wa.usesTargeting() || !lethalOn(me)) return null;
        if (!wa.canTarget(lethalTarget)) return null;
        wa.resetTargets();
        wa.getTargets().add(lethalTarget);
        ControlBrain.count("landseq3_lethal_target");
        return ComputerUtil.playNoStack(wa.getActivatingPlayer(), wa, me.getGame(), true);
    }

    /** F/G/H attacks */
    static void attack3(Player me, forge.game.combat.Combat combat) {
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        if (op == null) return;
        boolean all = lethalOn(me);
        boolean warped = warpGame == g && warpTurn == g.getPhaseHandler().getTurn();
        boolean defence = defenceNeeded(me);
        for (Card c : me.getCreaturesInPlay()) {
            if (combat.isAttacking(c) || !canAttackThisTurn(c) || !CombatUtil.canAttack(c, op)) continue;
            boolean add = all;
            if (!add && c.getNetPower() >= 1 && c.getManaAbilities().isEmpty()) {
                boolean safe = true;
                for (Card b : op.getCreaturesInPlay()) {
                    if (b.isTapped()) continue;
                    boolean can;
                    try { can = CombatUtil.canBlock(c, b); } catch (Exception e) { can = true; }
                    if (can && kills(b, c)) { safe = false; break; }
                }
                add = safe && (warped || !defence);
            }
            if (add) {
                combat.addAttacker(c, op);
                ControlBrain.count(all ? "landseq3_lethal_attacker" : warped ? "landseq3_warp_attacker" : "landseq3_safe_attacker");
            }
        }
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
    /** landseq16 (2026-10-23): whenever Mightform Harmonizer's doubling triggers together with our other landfall
     *  triggers (Sazh's Chocobo's counter, Earthbender Ascension's counter, ...), the doublings resolve LAST, so they
     *  double the counters too (never worse). Forge's order list puts its first item on the stack first, so that item
     *  resolves last: Harmonizer triggers go first. */
    static List<SpellAbility> order16(Player me, List<SpellAbility> in) {
        List<SpellAbility> h = new ArrayList<>(), rest = new ArrayList<>();
        for (SpellAbility sa : in) (sa.getHostCard() != null && named(sa.getHostCard(), HARMONIZER) ? h : rest).add(sa);
        if (h.isEmpty() || rest.isEmpty()) return null;
        ControlBrain.count("landseq16_doublings_last");
        h.addAll(rest);
        return h;
    }

    static List<SpellAbility> order(Player me, List<SpellAbility> in) {
        if (!strokeOn(me)) return null;
        List<SpellAbility> h = new ArrayList<>(), rest = new ArrayList<>();
        for (SpellAbility sa : in) (sa.getHostCard() != null && named(sa.getHostCard(), HARMONIZER) ? h : rest).add(sa);
        if (h.isEmpty() || rest.isEmpty()) return null;
        h.addAll(rest);
        return h;
    }
}
