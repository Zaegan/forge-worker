import forge.LobbyPlayer;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.GamePlayerUtil;

import java.util.List;

/**
 * Hand-written AI decisions layered over Forge's AI, for plays it never makes on its own. A tweaked player behaves
 * exactly like Forge's AI except when a tweak's conditions hold, so decks without the cards involved play identical
 * games (paired seeds stay paired). Enabled per run with -Dforge.sim.tweaks=spiderham (MatchGames).
 *
 * spiderham — Radioactive Spider's Fateful Bite ({2}, sacrifice it: search for a Spider Hero card) to fetch
 * Spider-Ham, Peter Porker. Forge's AI only pays a sacrifice-itself cost when the creature is about to die anyway, so
 * it never does this (runs/spiderham-search-1: 0 of 80 games). Played in our own main phase with an empty stack when:
 * Diligent Zookeeper is on our battlefield; no Spider-Ham on our battlefield or in hand, one in the library; we have a
 * Radioactive Spider whose ability we can activate; at least 4 mana available (2 for the Bite, 2 to cast Spider-Ham);
 * and no creature card in hand with mana value 4 or more that we could cast instead.
 *
 * control — ControlBrain's decisions for the 4c Control deck (sweepers, Revelation, Together as One, land first,
 * no Great Hall animation, shocklands).
 *
 * leyline — LeylineBrain's lethal pump turn and Burn Together for Izzet Leyline.
 * leylinespend — with leyline: on non-lethal turns, spend surplus mana on damage pumps for evasive/unblocked attackers.
 * leyplan — with leyline: rules of thumb for getting damage through (LeylineBrain): flier walls, killing a blocker by
 * pumping after blocks, holding pumps against a thick wall, end-step removal, Burn Together only when worth it.
 * simhybrid / simfull — the Izzet Leyline player uses Forge's simulation (search) AI, hybrid or full.
 * spendmana — when Forge's AI would pass in our main phase 2, cast the most expensive castable creature in hand.
 * flasheot — at the opponent's end step, cast the most expensive castable flash creature in hand.
 * Scoping (2026-10-13): spendmana, flasheot, prowesspre, equipidle, lordtarget and prowessplan (2026-10-23) may be written "rule@NAME" to act --
 *   and run their checks -- only for the player whose deck name contains NAME (see on()).
 * prowesspre — in our main 1, with an untapped prowess creature able to attack, cast cheap untargeted spells first.
 * reckless=NAME — the player whose deck name contains NAME uses Forge's Reckless AI profile (aggressive attacks).
 * dwarvesetb — Boros Dwarves: cast Thorin, Mountain-king and Chainsaw before combat (their enter-the-battlefield
 *   attach/damage then shapes this turn's combat).
 * dwarvesetb2 — as dwarvesetb, but once the opponent has removed one of our creatures outside combat (removal, sweeper),
 *   Thorin is held -- also from Forge's own choices -- until the opponent is tapped out.
 * lordtarget — when our damage spell or trigger targets an opponent's creature, retarget it to a "lord" (a creature
 *   whose continuous ability affects other creatures, e.g. Galerider Sliver) the damage kills, unless already a lord.
 * equipidle — when Forge would pass in our main phase, equip unattached equipment we can pay for (haste givers on a
 *   creature that can't attack yet, others on our best attacker; after combat, on any creature).
 * prowessplan — Izzet Prowess (ProwessBrain): each turn "can we win this turn?" (lethal search over cheap spells, burn
 *   targets and Eddymurk Crab, worst-case blocks); if so, cast the line and attack with everything; if not, set-up mode.
 * lifeshock / lifeprecombat / lifeorder / lifewarp @NAME — Lifegain G1-G4 (MetaBrain); landtunnel / landlethal @NAME —
 *   Landfall F1, F2 (LandfallBrain).
 * azoriushold / dimirbetrayal / dimirlethal @NAME — stock meta decks' hypotheses A1, D1, D2 (MetaBrain).
 * dimirfill@NAME — D7, with dimirbetrayal: the held Betrayal's turn casts another spell instead of passing (MetaBrain).
 * monline@NAME — the Eldrazi Monument + Bloodghast line (FetchBrain; implies fetchplay and ghastsac).
 * ghastsac@NAME — when we sacrifice a creature (Eldrazi Monument, edicts), Bloodghast goes first (MetaBrain V1).
 * azplan@NAME — the Azorius turn planner (AzoriusPlan): one mana budget per turn cycle, counter mana kept for the
 *  opponent's turn, card flow at their end step (2026-10-26).
 * azplanres / azplanflow / azplanmain @NAME — the planner's parts alone (2026-10-27): the counter-mana reserve, the
 *  end-step card flow, main 1 (land first, sweeper, Starcage); azplan = all three.
 * dragonmomo / dragonpassage / dragontutor / dragonnova @NAME — Boros Dragons B1-B4 (MetaBrain).
 * landorder@NAME — A6, any deck: a tapped land first when the untapped land's mana would go unused (LandOrder).
 * landcolour@NAME — A11, any deck: another land when Forge's leaves fewer hand instants castable (LandColour).
 * lifeorder2 / lifeshock2 / lifeempty / lifelegend @NAME — Lifegain G7a-G7d (MetaBrain, 2026-10-28).
 * lifeconvo@NAME — Lifegain G5: no Lunar Convocation draw that leaves us within reach of the opponent's board (MetaBrain).
 * prowessstoke / prowesscantrip / prowesshaste / prowessdefend @NAME — Prowess hypotheses P1, P2, P4, P5, one switch each
 *   (add-ons to prowessplan; ProwessBrain). prowessplan2 is P3.
 * prowessplan3@NAME — prowessplan + held cantrips must draw/dig (not Secret Identity) + no Stoke Genius discarding 3+ cards.
 * leyburn / leyotter / leydig / leyland — with leyline, fixes from reading losses (LeylineBrain): no Burn Together on
 * our own creature before combat, and "attack, then Burn Together to the face" counted as lethal; Elusive Otter pumped
 * past every blocker; cantrip pumps cast to dig when stuck; the land drop that lets a cheap creature be cast.
 * leylinemull[A-D,S] — Izzet Leyline's mulligans (LeylineBrain.keepHand; A, the default: the 7-card hand needs Leyline
 * of Resonance, a creature and a land; after one mulligan a creature and a land; after two a land; then keep. S: the
 * exact optimal strategy for the 10-criteria hand score, goldfish/mull_opt.py).
 *
 * landfall — LandfallBrain's Escape Tunnel + Mightform Harmonizer kill for Mono-Green Landfall.
 * spellplan1..7@NAME — SpellBrain (Izzet Spellementals): Sunderflock before combat / Eddymurk Crab on the opponent's turn /
 *  Opt at the opponent's end step / Crab for lethal, then attack with everything / removal at the opponent's end step /
 *  Spell Snare discipline / Prismari Charm damage over bounce.
 * fetchplay@NAME — FetchBrain: Zendikar fetchlands held and cracked only for a reason (Bloodghast, landfall attacker,
 *  mana, Searing Blaze on the opponent's turn), else at the opponent's end step.
 * spelltrace@NAME — LandfallBrain.trace2: snapshots at every decision, our main-phase passes, passes on opponent spells and at
 *  the opponent's end step (any deck; each line names the deck, the colours of untapped mana and which rule decided: by=...).
 * lftrace@NAME — LandfallBrain.trace: state snapshots at our main-phase decisions (for agent scoring), changes nothing.
 * lfaudit@NAME — LandfallBrain.audit: counts Landfall misplays (mis_*), changes nothing.
 * crowdno[@NAME] — never take the counter on an opponent's "may put a counter on this" trigger (Cheering Crowd).
 * landseq17@NAME — landseq7 + landseq5 Resolve fix + doublings last (the z>=1.0 bundle).
 * landseq16@NAME — landseq14 + Harmonizer doublings resolve after our other landfall triggers.
 * landseq14/15@NAME — landseq11 with the Ba Sing Se ability fix / + crack-back check that defers to Forge's lethal judgement.
 * landseq11/12/13@NAME — landseq9 + fixes / + crack-back check (act only if it saves us) / + keep all home when doomed.
 * landseq8/9/10@NAME — landseq7 + Resolve only when useful / + block reassignment / + Ouroboroid crack-back.
 * landseq7@NAME — landseq6 with the fetch rule after Ascension-first and no fetch for an attacker-less Harmonizer.
 * landseq6@NAME — landseq5 + a fetch instead of a basic when a payoff is on board.
 * landseq5@NAME — landseq4 + permanent spells before Ba Sing Se's earthbend.
 * landseq4@NAME — landseq3 + Earthbender Ascension before the other landfalls; payoffs before part C.
 * landseq3@NAME — landseq2 + Esper flashback, safe-attacker warp, safe attacks, main-1 lethal mode (LandfallBrain).
 * landseq2@NAME — LandfallBrain.seq2: payoffs first (also from exile), no idle warp, landfall spells before combat, Esper Origins.
 * landseq@NAME — LandfallBrain.seq: cast a castable landfall payoff before the landfall Forge chose (2026-10-23).
 *
 * ooze — with protect: Ferocity of the Hunt is held (no end-of-turn cast) for removal on a non-indestructible creature
 * (Ooze's partners: Beorn, Zookeeper, ...) or for Predator Ooze in a combat its power cannot win (ProtectBrain).
 * oozeeot — as ooze, plus the end-of-turn cast on our best creature that is not indestructible.
 * manahold — ManaHold: reserve the lands for Ferocity of the Hunt at our main phase 1 (use with oozeeot, whose
 * end-of-turn cast is the scheduled fallback).
 *
 * control@NAME — ControlBrain only for the deck named *NAME* (2026-10-25: Azorius Control has Day of Judgment, so plain
 *  "control" would pilot it untested); plain "control" is unchanged (every control deck, plus the field rules).
 * protect — ProtectBrain's uses of Tam, Mindful First-Year and Ferocity of the Hunt. Several tweaks can be combined:
 * -Dforge.sim.tweaks=spiderham,control,protect
 */
public class AiTweaks {
    /**
     * The field decks' own AI is on whenever "control" (4c Control's) is: Boros Dwarves joined the field on 2026-10-13
     * with its rules (Reckless profile, equipidle, adaptive Thorin), each scoped to the deck named *Dwarves*, so they
     * act for nothing else. Running deck lines started with "spiderham,control,protect" pick these up too.
     */
    static final String FIELD_RULES = "reckless=Dwarves,equipidle@Dwarves,dwarvesetb2@Dwarves";
    static final String TWEAKS = withField(withoutWorkCap(System.getProperty("forge.sim.tweaks", "")));
    /** the rule NAMES of the tweaks: each token without its "@DECK" scope (2026-10-23: legacy switches were matched with
     *  TWEAKS.contains, so "landfall" was on in every "landseqN@mono_green_landfall" setting through the deck name, and
     *  "control" would be on for any setting naming 4c_control). Substrings of names still match as before ("oozeeot"
     *  contains "ooze", "leylinemull" contains "leyline"); deck names never do. */
    static final String NAMES = String.join(",", java.util.Arrays.stream(TWEAKS.split(",")).map(x -> x.trim().split("@", 2)[0])
            .toArray(String[]::new));

    /** a legacy switch: NAME occurs in a rule name. Every landseq pilot keeps the Escape Tunnel rules ("landfall") it has
     *  always run with (until 2026-10-23 they were on through the deck name). */
    static boolean has(String name) {
        if (name.equals("landfall") && NAMES.contains("landseq")) return true;
        return NAMES.contains(name);
    }

    /** The work cap ("workcap=..." in the tweaks, MatchGames.WorkMeter) is a game setting, not an AI rule: AI players
     *  never see it, so a campaign with only a work cap plays Forge's stock AI exactly as one with no tweaks. */
    static String withoutWorkCap(String t) {
        StringBuilder b = new StringBuilder();
        for (String x : t.split(",")) if (!x.isBlank() && !x.trim().startsWith("workcap=")) b.append(b.length() > 0 ? "," : "").append(x.trim());
        return b.toString();
    }

    static String withField(String t) {
        if (!java.util.Arrays.asList(t.split(",")).contains("control")) return t;
        StringBuilder b = new StringBuilder(t);
        for (String r : FIELD_RULES.split(",")) if (!java.util.Arrays.asList(t.split(",")).contains(r)) b.append(",").append(r);
        return b.toString();
    }

    /**
     * Whether general rule RULE (spendmana, flasheot, prowesspre, equipidle, lordtarget) is on for player P (user,
     * 2026-10-13: no change to how the field decks play unless confirmed; even a rule's checks can change a game).
     * "rule" alone: every player (as before). "rule@NAME": only a player whose deck name contains NAME -- the checks
     * never run for anyone else. Tokens are matched exactly, not as substrings.
     */
    static boolean on(String rule, Player p) {
        for (String t : TWEAKS.split(",")) {
            t = t.trim();
            if (t.equals(rule)) return true;
            if (t.startsWith(rule + "@")) {
                String deck = deckName(p);
                if (deck != null && deck.contains(t.substring(rule.length() + 1))) return true;
            }
        }
        return false;
    }

    /** No cache (2026-10-13): Forge's players compare equal by id (1, 2) across games, so a cache keyed by player
     *  served a long-lived worker's FIRST game's deck name in every later game -- Boros Dwarves' scoped rules were off
     *  whenever its seat had first held another deck. */
    static String deckName(Player p) {
        try {
            return p.getRegisteredPlayer().getDeck().getName();
        } catch (Exception e) {
            return null;
        }
    }
    static int bites; // Fateful Bites the tweak chose this game (reset by MatchGames)

    static LobbyPlayer player(String name, int index) {
        LobbyPlayer stock = GamePlayerUtil.createAiPlayer(name, index);
        if (TWEAKS.isEmpty() || !(stock instanceof LobbyPlayerAi)) return stock;
        Tweaked t = new Tweaked(name);
        t.setAiProfile(((LobbyPlayerAi) stock).getAiProfile());
        return t;
    }

    static final class Tweaked extends LobbyPlayerAi {
        Tweaked(String name) {
            super(name, null);
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player p = super.createIngamePlayer(game, id);
            p.dangerouslySetController(new Controller(game, p, this));
            return p;
        }
    }

    static final class Controller extends PlayerControllerAi {
        Controller(Game game, Player p, LobbyPlayer lp) {
            super(game, p, lp);
        }

        boolean simChecked, profileChecked;

        /** reckless=NAME (2026-10-12, Boros Dwarves: 1-3 attacks a game vs v2.3): the player whose DECK name contains
         *  NAME uses Forge's own Reckless AI profile (attacks into trades, plays aggro); everyone else keeps theirs.
         *  Set at the player's first decision: players are named P1/P2, the deck is only known in the game. */
        void checkProfile() {
            if (profileChecked) return;
            profileChecked = true;
            String deck;
            try {
                deck = getPlayer().getRegisteredPlayer().getDeck().getName();
            } catch (Exception e) {
                return;
            }
            for (String tw : TWEAKS.split(","))
                if (tw.startsWith("reckless=") && deck != null && deck.contains(tw.substring("reckless=".length())))
                    ((LobbyPlayerAi) getPlayer().getLobbyPlayer()).setAiProfile("Reckless");
        }

        /** which part of the harness made this decision (spelltrace's by=..., 2026-10-25): "forge" unless a rule did */
        String src = "forge";

        List<SpellAbility> by(String tag, SpellAbility sa) { src = tag; return List.of(sa); }

        /** a rule's change to Forge's choice: tags the decision "RULE-veto" or "RULE-replaced" */
        List<SpellAbility> post(String rule, List<SpellAbility> was, List<SpellAbility> now) {
            if (now != was) src = rule + ((now == null || now.isEmpty()) ? "-veto" : "-replaced");
            return now;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            src = "forge";
            if (LandfallBrain.f1(getPlayer()))
                try { LandfallBrain.retargetStroke(getPlayer()); } catch (RuntimeException e) { ControlBrain.count("landtunnel_error_" + e.getClass().getSimpleName()); }
            List<SpellAbility> r = choose0();
            if (on("spelltrace", getPlayer())) LandfallBrain.trace2(getPlayer(), r, src);
            return r;
        }

        List<SpellAbility> choose0() {
            checkProfile();
            if (!simChecked) {   // simhybrid / simfull: Forge's search-based AI, for the Izzet Leyline player only
                simChecked = true;
                if ((has("simhybrid") || has("simfull")) && LeylineBrain.deck(getPlayer()))
                    getAi().setUseSimulation(has("simfull") ? forge.ai.AIOption.USE_FULL_SIMULATION : forge.ai.AIOption.USE_HYBRID_SIMULATION);
                // simdeck@NAME / simdeckfull@NAME (2026-10-23, meta pilots): the same for the deck named *NAME*
                else if (on("simdeck", getPlayer()) || on("simdeckfull", getPlayer()))
                    getAi().setUseSimulation(on("simdeckfull", getPlayer()) ? forge.ai.AIOption.USE_FULL_SIMULATION : forge.ai.AIOption.USE_HYBRID_SIMULATION);
            }
            if (on("flashhold", getPlayer())) {
                ManaHold.planFlash(getPlayer());
                SpellAbility b = flashBlocker(getPlayer());
                if (b != null) return by("flashhold", b);
            }
            if (on("flasheot", getPlayer()) || on("flashhold", getPlayer())) {
                if (on("flashhold", getPlayer()) && isOppEndStep(getPlayer())) ManaHold.release(getPlayer());
                SpellAbility f = flashAtEndOfTurn(getPlayer());
                if (f != null) return by("flasheot", f);
            }
            if (on("dwarvesetb2", getPlayer())) removalWatch(getPlayer());
            if (on("dwarvesetb", getPlayer()) || on("dwarvesetb2", getPlayer())) {
                SpellAbility d = dwarvesEtb(getPlayer());
                if (d != null) return by("dwarvesetb", d);
            }
            if (AzoriusPlan.any(getPlayer())) {
                SpellAbility az = AzoriusPlan.choose(getAi(), getPlayer());
                if (az != null) return by("azplan", az);
            }
            if (ProwessBrain.ON(getPlayer())) {
                SpellAbility pw = ProwessBrain.choose(getPlayer());
                if (pw != null) return by("prowessplan", pw);
            }
            if (SpellBrain.any(getPlayer())) {
                SpellAbility sb = SpellBrain.choose(getPlayer());
                if (sb != null) return by("spellplan", sb);
            }
            if (FetchBrain.ON(getPlayer())) {
                SpellAbility fb = FetchBrain.choose(getPlayer());
                if (fb != null) return by("fetchplay", fb);
            }
            if (on("prowesspre", getPlayer())) {
                SpellAbility pp = prowessPrecombat(getPlayer());
                if (pp != null) return by("prowesspre", pp);
            }
            if (has("manahold")) ManaHold.plan(getPlayer());
            if (has("spiderham")) {
                SpellAbility bite = spiderHamBite(getPlayer());
                if (bite != null) {
                    bites++;
                    return by("spiderham", bite);
                }
            }
            if (has("leyline")) {
                SpellAbility l = LeylineBrain.choose(getPlayer());
                if (l != null) return by("leyline", l);
            }
            if (has("landfall")) {
                SpellAbility s = LandfallBrain.choose(getPlayer());
                if (s != null) return by("landfall", s);
            }
            if (has("protect")) {
                SpellAbility p = ProtectBrain.choose(getPlayer());
                if (p != null) return by("protect", p);
            }
            if (on("control", getPlayer())) {   // "control@NAME" (2026-10-25): ControlBrain for one deck only
                if (ControlBrain.holdUntilMain(getPlayer())) { src = "control-hold"; return null; }
                SpellAbility mine = ControlBrain.choose(getAi(), getPlayer());
                if (mine != null && LandOrder.on(getPlayer())) {   // control's own land drop goes through land order too
                    List<SpellAbility> lo = LandOrder.post(getPlayer(), List.of(mine));
                    if (lo != null && !lo.isEmpty() && lo.get(0) != mine) return by("landorder", lo.get(0));
                }
                if (mine != null) return by("control", mine);
                List<SpellAbility> ai = aiChoose();
                if (ai != null && ai.stream().anyMatch(ControlBrain::vetoed)) {
                    ControlBrain.count("hall_veto");
                    src = "control-veto";
                    return null;
                }
                ai = has("leyline") ? LeylineBrain.post(getPlayer(), ai) : ai;
                ai = ProwessBrain.ON(getPlayer()) ? ProwessBrain.post(getPlayer(), ai) : ai;
                ai = post("landorder", ai, LandOrder.on(getPlayer()) ? LandOrder.post(getPlayer(), ai) : ai);
                return spendIfIdle(getPlayer(), ai);
            }
            List<SpellAbility> ai = aiChoose();
            ai = post("leyline", ai, has("leyline") ? LeylineBrain.post(getPlayer(), ai) : ai);
            ai = post("prowessplan", ai, ProwessBrain.ON(getPlayer()) ? ProwessBrain.post(getPlayer(), ai) : ai);
            ai = post("spellplan", ai, SpellBrain.any(getPlayer()) ? SpellBrain.post(getPlayer(), ai) : ai);
            ai = post("metaplan", ai, MetaBrain.any(getPlayer()) ? MetaBrain.post(getPlayer(), ai) : ai);
            ai = post("azplan", ai, AzoriusPlan.any(getPlayer()) ? AzoriusPlan.post(getAi(), getPlayer(), ai) : ai);
            ai = post("landorder", ai, LandOrder.on(getPlayer()) ? LandOrder.post(getPlayer(), ai) : ai);
            ai = post("landcolour", ai, LandColour.on(getPlayer()) ? LandColour.post(getPlayer(), ai) : ai);
            ai = post("monline", ai, FetchBrain.MON(getPlayer()) ? FetchBrain.holdLand(getPlayer(), ai) : ai);
            ai = post("landseq", ai, on("landseq", getPlayer()) ? LandfallBrain.seq(getPlayer(), ai) : ai);
            ai = post("landseq", ai, LandfallBrain.seqOn(getPlayer()) ? LandfallBrain.seq2(getPlayer(), ai) : ai);
            ai = post("landtunnel", ai, LandfallBrain.f1(getPlayer()) ? LandfallBrain.tunnelFirst(getPlayer(), ai) : ai);
            ai = post("landlethal", ai, LandfallBrain.f2(getPlayer()) ? LandfallBrain.lethalFirst(getPlayer(), ai) : ai);
            if (on("edgargrow", getPlayer()) && ai != null && !ai.isEmpty() && holdForEdgar(getPlayer(), ai.get(0))) {
                ControlBrain.count("edgargrow_pass");
                src = "edgargrow";
                return null;
            }
            if (on("lordtarget", getPlayer()) && ai != null) for (SpellAbility x : ai) lordRetarget(getPlayer(), x);
            if (on("dwarvesetb2", getPlayer()) && ai != null && holdThorin(getPlayer())
                    && ai.stream().anyMatch(x -> x.getHostCard() != null && x.getHostCard().getName().equals("Thorin, Mountain-king"))) {
                ControlBrain.count("dwarvesetb2_hold");
                src = "dwarvesetb2";
                return null;
            }
            List<SpellAbility> before = ai;
            ai = spendIfIdle(getPlayer(), ai);
            if (ai != before) src = "spendmana";
            if (on("lftrace", getPlayer())) LandfallBrain.trace(getPlayer(), ai);
            return on("lfaudit", getPlayer()) ? LandfallBrain.audit(getPlayer(), ai) : ai;
        }

        /** Forge's own choice. With the simulation AI on (simdeck), a failure inside its game copier (Forge 2.0.15:
         *  GameCopier -> StaticAbilityContinuous IndexOutOfBounds on a MayPlayPlayer static; the real game is untouched)
         *  falls back to the ordinary AI for this one decision instead of ending the game as an error draw. */
        List<SpellAbility> aiChoose() {
            if (FetchBrain.ON(getPlayer())) {   // fetchplay: held fetches are not Forge's to crack
                List<forge.game.card.Card> hidden = FetchBrain.hide(getPlayer());
                try {
                    return simSafe(() -> super.chooseSpellAbilityToPlay());
                } finally {
                    for (forge.game.card.Card c : hidden) c.setTapped(false);
                }
            }
            return simSafe(() -> super.chooseSpellAbilityToPlay());
        }

        <T> T simSafe(java.util.function.Supplier<T> call) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                forge.ai.AIOption m = getAi().usesFullSimulation() ? forge.ai.AIOption.USE_FULL_SIMULATION
                        : getAi().usesHybridSimulation() ? forge.ai.AIOption.USE_HYBRID_SIMULATION : null;
                if (m == null) throw e;
                ControlBrain.count("sim_fallback");
                getAi().setUseSimulation(null);
                try {
                    return call.get();
                } finally {
                    getAi().setUseSimulation(m);
                }
            }
        }

        @Override
        public boolean playTrigger(Card host, forge.game.trigger.WrappedAbility wa, boolean isMandatory) {
            if (has("landfall")) {
                Boolean r = LandfallBrain.playTrigger(getPlayer(), wa);
                if (r != null) {
                    if (r && on("lfaudit", getPlayer())) LandfallBrain.auditTrigger(getPlayer(), wa);
                    return r;
                }
            }
            if (LandfallBrain.v3On(getPlayer())) {
                Boolean r3 = LandfallBrain.playTrigger3(getPlayer(), wa);
                if (r3 != null) return r3;
            }
            boolean play = super.playTrigger(host, wa, isMandatory);
            if (play && on("lordtarget", getPlayer())) lordRetarget(getPlayer(), wa);
            if (play && on("lfaudit", getPlayer())) LandfallBrain.auditTrigger(getPlayer(), wa);
            return play;
        }

        @Override
        public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> sas) {
            if (on("landseq16", getPlayer()) || on("landseq17", getPlayer())) {
                List<SpellAbility> o16 = LandfallBrain.order16(getPlayer(), sas);
                if (o16 != null) return o16;
            }
            if (has("landfall")) {
                List<SpellAbility> o = LandfallBrain.order(getPlayer(), sas);
                if (o != null) return o;
            }
            return super.orderSimultaneousSa(sas);
        }

        @Override
        public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
            checkProfile();
            if (has("leylinemull") && LeylineBrain.deck(getPlayer()))
                return LeylineBrain.keepHand(getPlayer(), cardsToReturn);
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }

        @Override
        public forge.game.card.CardCollectionView tuckCardsViaMulligan(forge.game.card.CardCollectionView hand, int n) {
            forge.game.card.CardCollectionView out = super.tuckCardsViaMulligan(hand, n);
            if (has("leylinemull") && LeylineBrain.deck(getPlayer())) return LeylineBrain.tuck(hand, out, n);
            return out;
        }

        @Override
        public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
            super.declareAttackers(attacker, combat);
            if (has("protect")) ProtectBrain.holdTamBack(getPlayer(), combat);
            if (has("leyline")) LeylineBrain.forceAttack(getPlayer(), combat);
            if (ProwessBrain.ON(getPlayer())) ProwessBrain.forceAttack(getPlayer(), combat);
            if (SpellBrain.on4(getPlayer())) SpellBrain.forceAttack(getPlayer(), combat);
            if (MetaBrain.d2(getPlayer())) MetaBrain.forceAttack(getPlayer(), combat);
            if (LandfallBrain.v3On(getPlayer())) LandfallBrain.attack3(getPlayer(), combat);
            if (LandfallBrain.f2(getPlayer())) LandfallBrain.lethalAttack(getPlayer(), combat);
            if (on("landseq10", getPlayer())) LandfallBrain.ouroAttack(getPlayer(), combat);
            if (on("landseq12", getPlayer()) || on("landseq13", getPlayer())) LandfallBrain.crackAttack(getPlayer(), combat, on("landseq13", getPlayer()));
            if (on("landseq15", getPlayer())) LandfallBrain.crackAttack15(getPlayer(), combat);
            if (on("lftrace", getPlayer()) || on("spelltrace", getPlayer())) LandfallBrain.traceCombat(getPlayer(), "ATTACK", combat);
        }

        @Override
        public void declareBlockers(Player defender, forge.game.combat.Combat combat) {
            super.declareBlockers(defender, combat);
            if (on("landseq9", getPlayer()) || on("landseq10", getPlayer()) || on("landseq11", getPlayer()) || on("landseq12", getPlayer())
                    || on("landseq13", getPlayer()) || on("landseq14", getPlayer()) || on("landseq15", getPlayer())
                    || on("landseq16", getPlayer())) LandfallBrain.fixBlocks(getPlayer(), combat);
            if (ProwessBrain.P15(getPlayer())) ProwessBrain.noBlock(getPlayer(), combat);
            if (on("lftrace", getPlayer()) || on("spelltrace", getPlayer())) LandfallBrain.traceCombat(getPlayer(), "BLOCK", combat);
        }

        @Override
        public boolean payCostToPreventEffect(forge.game.cost.Cost cost, SpellAbility sa, boolean alreadyPaid,
                                              forge.util.collect.FCollectionView<Player> allPayers) {
            boolean r = payCost0(cost, sa, alreadyPaid, allPayers);
            if (on("spelltrace", getPlayer())) {   // "unless" and optional costs: ward, Cheering Crowd's counter (2026-10-25)
                Game g = getPlayer().getGame();
                forge.game.card.Card h = sa.getHostCard();
                ControlBrain.trace.append("@").append(g.getGameLog().getAllEntries().size()).append(" ").append(LandfallBrain.deckTag(getPlayer()))
                        .append(" T").append(g.getPhaseHandler().getTurn()).append(" UNLESS: ").append(h == null ? "?" : h.getName())
                        .append(": ").append(cost.toSimpleString()).append(sa.hasParam("UnlessSwitched") ? " (optional)" : "")
                        .append(" -> ").append(r ? "pay" : "decline").append("\n");
            }
            return r;
        }

        @Override
        public forge.game.card.CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                                               forge.game.card.CardCollectionView valid, String message) {
            forge.game.card.CardCollectionView r = super.choosePermanentsToSacrifice(sa, min, max, valid, message);
            return MetaBrain.v1(getPlayer()) ? MetaBrain.ghastSac(getPlayer(), valid, r) : r;
        }

        @Override
        public String chooseSomeType(String kind, SpellAbility sa, java.util.Collection<String> valid, boolean isOptional) {
            String r = super.chooseSomeType(kind, sa, valid, isOptional);
            if (MetaBrain.b2(getPlayer()) && sa != null && sa.getHostCard() != null && sa.getHostCard().getName().equals(MetaBrain.PASSAGE)) {
                String t = MetaBrain.passageType(getPlayer(), valid);   // dragonpassage@NAME (B2)
                if (t != null) { if (!t.equals(r)) ControlBrain.count("dragonpassage_changed"); r = t; }
            }
            if (on("spelltrace", getPlayer())) {   // named types: Cavern of Souls, Multiversal Passage (2026-10-25)
                Game g = getPlayer().getGame();
                forge.game.card.Card h = sa == null ? null : sa.getHostCard();
                ControlBrain.trace.append("@").append(g.getGameLog().getAllEntries().size()).append(" ").append(LandfallBrain.deckTag(getPlayer()))
                        .append(" T").append(g.getPhaseHandler().getTurn()).append(" CHOOSE: ").append(h == null ? "?" : h.getName())
                        .append(": ").append(kind).append(" type -> ").append(r).append("\n");
            }
            return r;
        }

        boolean payCost0(forge.game.cost.Cost cost, SpellAbility sa, boolean alreadyPaid,
                         forge.util.collect.FCollectionView<Player> allPayers) {
            if (on("crowdno", getPlayer()) && feedsOpponent(cost, sa, getPlayer())) {
                ControlBrain.count("crowdno");
                return false;
            }
            Boolean shrine = MetaBrain.payShrine(cost, sa, getPlayer());   // lifeshock@NAME (G1)
            if (shrine != null) {
                if (!shrine || !ComputerUtilCost.canPayCost(cost, sa, getPlayer(), false)) return false;
                return new forge.game.cost.CostPayment(cost, sa).payComputerCosts(new forge.ai.AiCostDecision(getPlayer(), sa, false));
            }
            if (on("control", getPlayer())) {   // "control@NAME" (2026-10-25): ControlBrain for one deck only
                Boolean pay = ControlBrain.payShock(cost, sa, getPlayer());
                if (pay != null) {
                    if (!pay || !ComputerUtilCost.canPayCost(cost, sa, getPlayer(), false)) return false;
                    return new forge.game.cost.CostPayment(cost, sa).payComputerCosts(new forge.ai.AiCostDecision(getPlayer(), sa, false));
                }
            }
            return simSafe(() -> super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers));
        }
    }

    /**
     * crowdno (2026-10-25, sp12-s10b scan: Forge's AI took the counter on the OPPONENT's Cheering Crowd 8 times in 3 of
     * 10 games, mostly for {C} it never spent; the Crowd then hit for 16 and 11): never pay a "may put a counter on this"
     * cost (UnlessSwitched AddCounter) on a card an opponent controls. The counter is the opponent's for good; the mana
     * is ours for one phase.
     */
    static boolean feedsOpponent(forge.game.cost.Cost cost, SpellAbility sa, Player me) {
        forge.game.card.Card host = sa.getHostCard();
        if (host == null || host.getController() == null || !host.getController().isOpponentOf(me)) return false;
        if (!sa.hasParam("UnlessSwitched")) return false;
        for (forge.game.cost.CostPart p : cost.getCostParts())
            if (p instanceof forge.game.cost.CostPutCounter) return true;
        return false;
    }

    /**
     * spendmana (2026-10-11, mono-blue: 9% of turns with Tempest Djinn castable passed with nothing cast): when Forge's
     * AI would pass in our main phase 2 with the stack empty, cast the most expensive creature spell in hand it can pay
     * for, so mana is not left unused.
     */
    static List<SpellAbility> spendIfIdle(Player me, List<SpellAbility> ai) {
        if (on("equipidle", me) && (ai == null || ai.isEmpty())) {
            SpellAbility e = equipIdle(me);
            if (e != null) return List.of(e);
        }
        if (!on("spendmana", me) || (ai != null && !ai.isEmpty())) return ai;
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN2 || !g.getStack().isEmpty()) return ai;
        SpellAbility best = bestCreatureSpell(me, false);
        if (best == null) return ai;
        ControlBrain.count("spendmana");
        return List.of(best);
    }

    /**
     * flasheot (2026-10-11, mono-blue: Enduring Curiosity cast in only 38% of games where drawn): at the opponent's end
     * step, with the stack empty, cast the most expensive flash creature in hand we can pay for.
     */
    static boolean isOppEndStep(Player me) {
        Game g = me.getGame();
        return !g.getPhaseHandler().isPlayerTurn(me) && g.getPhaseHandler().getPhase() == PhaseType.END_OF_TURN && g.getStack().isEmpty();
    }

    /**
     * flashhold, the blocker half: in the opponent's declare-attackers step, with creatures attacking us, flash in the
     * held creature if it makes a good block -- it can block and kill an attacker without dying (attacker toughness <=
     * its power, attacker power < its toughness, no evasion it can't block), or the attack would otherwise be lethal.
     * The reserved lands are released first. Forge's AI then declares its blocks as usual.
     */
    static SpellAbility flashBlocker(Player me) {
        Game g = me.getGame();
        if (g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.COMBAT_DECLARE_ATTACKERS
                || !g.getStack().isEmpty() || g.getCombat() == null) return null;
        List<Card> att = new java.util.ArrayList<>();
        for (Card a : g.getCombat().getAttackers()) if (me.equals(g.getCombat().getDefenderPlayerByAttacker(a))) att.add(a);
        if (att.isEmpty()) return null;
        ManaHold.release(me);
        SpellAbility best = bestCreatureSpell(me, true);
        if (best == null || best.getHostCard() == null) return null;
        Card f = best.getHostCard();
        int dmg = 0;
        for (Card a : att) dmg += a.getNetCombatDamage();
        boolean good = dmg >= me.getLife();
        for (Card a : att)
            if (a.getNetToughness() <= f.getNetPower() && a.getNetPower() < f.getNetToughness()
                    && !a.hasKeyword(forge.game.keyword.Keyword.FLYING) && !a.hasKeyword(forge.game.keyword.Keyword.MENACE)) good = true;
        if (!good) return null;
        ControlBrain.count("flashhold_block");
        return best;
    }

    /**
     * edgargrow (2026-10-17; user: "not cast anything if what you would cast is worse than giving him +2+2", and "if you
     * never give him +1/+1 counters, he doesn't get the full benefit of his 4G"): on our turn, with Edgar, Moonlit
     * Sovereign on our battlefield and no spell cast yet this turn, pass instead of casting SA unless it is worth more
     * than his end-step counters. Worth of the counters: +2/+2 in Forge's creature evaluation (2 x 15 power + 2 x 10
     * toughness = 50), plus 30 while he has no +1/+1 counter yet (his {4}{G} grows only creatures with one). Worth of the
     * spell: a creature's own evaluation; a spell targeting an opponent's creature, that creature's evaluation;
     * anything else 0. Lands and activated abilities are not spells and are never held.
     */
    static boolean holdForEdgar(Player me, SpellAbility sa) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || !sa.isSpell() || me.getSpellsCastThisTurn() > 0) return false;
        Card edgar = null;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals("Edgar, Moonlit Sovereign")) edgar = c;
        if (edgar == null) return false;
        int grow = 50 + (edgar.getPowerBonusFromCounters() > 0 ? 0 : 30), worth = 0;
        Card host = sa.getHostCard();
        if (host != null && host.isCreature()) worth = forge.ai.ComputerUtilCard.evaluateCreature(host);
        else if (sa.getTargets() != null)
            for (Card t : sa.getTargets().getTargetCards())
                if (t.isCreature() && !t.getController().equals(me)) worth = Math.max(worth, forge.ai.ComputerUtilCard.evaluateCreature(t));
        return worth < grow;
    }

    static SpellAbility flashAtEndOfTurn(Player me) {
        Game g = me.getGame();
        if (g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.END_OF_TURN || !g.getStack().isEmpty()) return null;
        SpellAbility best = bestCreatureSpell(me, true);
        if (best != null) ControlBrain.count("flasheot");
        return best;
    }

    /**
     * prowesspre (2026-10-12, Izzet Prowess at 14.6% vs the field): in our main phase 1, with the stack empty and an
     * untapped prowess creature that can attack this turn, cast the cheapest instant or sorcery (mana value <= 2) that
     * needs no target -- cantrips such as Opt, Sleight of Hand, Flow State -- so the attack carries the prowess bonus.
     * Forge's AI otherwise tends to cast them at the opponent's end step. Burn and counterspells (targeted) stay Forge's.
     */
    static SpellAbility prowessPrecombat(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1 || !g.getStack().isEmpty()) return null;
        boolean attacker = false;
        for (Card c : me.getCreaturesInPlay())
            if (c.hasKeyword(forge.game.keyword.Keyword.PROWESS) && c.isUntapped() && !c.hasSickness()) attacker = true;
        if (!attacker) return null;
        forge.game.card.CardCollection hand = new forge.game.card.CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if ((c.isInstant() || c.isSorcery()) && !c.isCreature() && c.getCMC() <= 2) hand.add(c);
        SpellAbility best = null;
        for (SpellAbility sa : forge.ai.ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell() || sa.isAdventure() || targets(sa)) continue;
            sa.setActivatingPlayer(me);
            try {
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
            } catch (Exception e) {
                continue;
            }
            if (best == null || sa.getHostCard().getCMC() < best.getHostCard().getCMC()) best = sa;
        }
        if (best != null) ControlBrain.count("prowesspre");
        return best;
    }

    static boolean targets(SpellAbility sa) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility())
            if (x.usesTargeting()) return true;
        return false;
    }

    /**
     * equipidle (2026-10-13, Boros Dwarves: 13% of turns ended with an unattached equipment, a creature, and the mana
     * to equip it -- 63% of them Lavaspur Boots, which gives haste): when Forge's AI would pass in our main phase with
     * the stack empty, activate the equip ability of an unattached equipment we can pay for. Before combat: equipment
     * that grants haste goes on our strongest creature that can't attack yet (so it can), other equipment on our
     * strongest creature that can attack; after combat, on our strongest creature. Only unattached equipment is
     * moved, so nothing is shuffled back and forth.
     */
    static SpellAbility equipIdle(Player me) {
        Game g = me.getGame();
        PhaseType ph = g.getPhaseHandler().getPhase();
        if (!g.getPhaseHandler().isPlayerTurn(me) || !g.getStack().isEmpty() || (ph != PhaseType.MAIN1 && ph != PhaseType.MAIN2)) return null;
        List<Card> mine = new java.util.ArrayList<>(me.getCreaturesInPlay());
        if (mine.isEmpty()) return null;
        mine.sort((x, y) -> Integer.compare(y.getNetPower(), x.getNetPower()));
        for (Card eq : me.getCardsIn(ZoneType.Battlefield)) {
            if (!eq.isEquipment() || eq.isEquipping()) continue;
            boolean haste = eq.getStaticAbilities().stream().anyMatch(st -> String.valueOf(st.getParam("AddKeyword")).contains("Haste"));
            for (SpellAbility sa : eq.getSpellAbilities()) {
                if (!sa.isEquip()) continue;
                sa.setActivatingPlayer(me);
                Card target = null;
                for (Card c : mine) {
                    boolean canAttack = c.isUntapped() && (!c.isSick() || c.hasKeyword(forge.game.keyword.Keyword.HASTE));
                    if (ph == PhaseType.MAIN1 && haste && (canAttack || c.isTapped())) continue;
                    if (ph == PhaseType.MAIN1 && !haste && !canAttack) continue;
                    if (sa.canTarget(c)) { target = c; break; }
                }
                if (target == null) continue;
                sa.resetTargets();
                sa.getTargets().add(target);
                try {
                    if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) { sa.resetTargets(); continue; }
                } catch (Exception e) {
                    sa.resetTargets();
                    continue;
                }
                ControlBrain.count("equipidle" + (haste && ph == PhaseType.MAIN1 ? "_haste" : ""));
                return sa;
            }
        }
        return null;
    }

    static final java.util.Set<String> DWARVES_ETB = java.util.Set.of("Thorin, Mountain-king", "Chainsaw");

    /** dwarvesetb (2026-10-13, Boros Dwarves vs Slivers: Thorin cast after combat, his damage spent on a non-lord):
     *  in our main phase 1, cast Thorin (when we have an equipment) or Chainsaw (when the opponent has a creature). */
    static SpellAbility dwarvesEtb(Player me) {
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me) || g.getPhaseHandler().getPhase() != PhaseType.MAIN1 || !g.getStack().isEmpty()) return null;
        Player op = null;
        for (Player p : g.getPlayers()) if (p != me) op = p;
        boolean equipment = me.getCardsIn(ZoneType.Battlefield).stream().anyMatch(Card::isEquipment);
        boolean target = op != null && !op.getCreaturesInPlay().isEmpty();
        forge.game.card.CardCollection hand = new forge.game.card.CardCollection();
        boolean thorinOk = equipment && !(on("dwarvesetb2", me) && holdThorin(me));
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if ((c.getName().equals("Thorin, Mountain-king") && thorinOk) || (c.getName().equals("Chainsaw") && target)) hand.add(c);
        for (SpellAbility sa : forge.ai.ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell()) continue;
            sa.setActivatingPlayer(me);
            try {
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) {
                    ControlBrain.count("dwarvesetb_" + (sa.getHostCard().getName().startsWith("Thorin") ? "thorin" : "chainsaw"));
                    return sa;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    /** dwarvesetb2 (user, 2026-10-13: push Thorin out fast against creature decks, hold him until the opponent is
     *  tapped out once our creatures are being removed by spells). Removal is seen on the stack (our AI gets priority
     *  to respond to it): an opponent's spell or ability that destroys, exiles, damages or sacrifices one of OUR
     *  creatures, or hits all creatures. (Counting our creatures that left the battlefield misread combat deaths: the
     *  AI is often not asked anything during the combat damage step.) */
    static final java.util.Map<Player, java.util.Set<Integer>> seenStack = new java.util.HashMap<>();
    static final java.util.Map<Player, Integer> removedBySpells = new java.util.HashMap<>();
    static final java.util.Map<Player, Game> watchedGame = new java.util.HashMap<>();
    static final java.util.Set<forge.game.ability.ApiType> REMOVAL = java.util.EnumSet.of(
            forge.game.ability.ApiType.Destroy, forge.game.ability.ApiType.DestroyAll, forge.game.ability.ApiType.ChangeZone,
            forge.game.ability.ApiType.ChangeZoneAll, forge.game.ability.ApiType.DealDamage, forge.game.ability.ApiType.DamageAll,
            forge.game.ability.ApiType.Sacrifice, forge.game.ability.ApiType.SacrificeAll);

    static void removalWatch(Player me) {
        Game g = me.getGame();
        if (watchedGame.get(me) != g) { watchedGame.put(me, g); seenStack.put(me, new java.util.HashSet<>()); removedBySpells.put(me, 0); }
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
            Card src = si.getSpellAbility() == null ? null : si.getSpellAbility().getHostCard();
            // triggers record no activating player: judge "ours" by who controls the source
            if (si.getActivatingPlayer() == me || (src != null && src.getController() == me) || !seenStack.get(me).add(si.getId())) continue;
            for (SpellAbility x = si.getSpellAbility(); x != null; x = x.getSubAbility()) {
                if (x.getApi() == null || !REMOVAL.contains(x.getApi())) continue;
                boolean hit = false;
                if (x.getApi().name().endsWith("All")) hit = String.valueOf(x.getParam("ValidCards")).contains("Creature");
                else if (x.getTargets() != null)
                    for (Card t : x.getTargets().getTargetCards()) if (t.getController() == me && t.isCreature()) hit = true;
                if (hit) {
                    removedBySpells.merge(me, 1, Integer::sum);
                    // counters are summed over both players in a game: keep only the Dwarves player's own count
                    if (holdsThorin(me)) ControlBrain.count("dwarvesetb2_removal_seen");
                    break;
                }
            }
        }
    }

    static boolean holdsThorin(Player me) {
        for (Card c : me.getAllCards()) if (c.getName().equals("Thorin, Mountain-king")) return true;
        return false;
    }

    /** hold Thorin: our creatures have been removed by spells and the opponent still has untapped mana */
    static boolean holdThorin(Player me) {
        if (removedBySpells.getOrDefault(me, 0) == 0 || watchedGame.get(me) != me.getGame()) return false;
        for (Player p : me.getGame().getPlayers()) {
            if (p == me) continue;
            long open = p.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c.isLand() && c.isUntapped() && !c.getManaAbilities().isEmpty()).count();
            if (open > 0) return true;
        }
        return false;
    }

    /** a creature whose continuous ability affects other creatures (a "lord": Galerider Sliver, Predatory Sliver...) */
    static boolean isLord(Card c) {
        for (forge.game.staticability.StaticAbility st : c.getStaticAbilities()) {
            String mode = st.getParam("Mode"), aff = st.getParam("Affected");
            if (!"Continuous".equals(mode) || aff == null || !aff.contains("Creature")) continue;
            if (aff.contains("Self") || aff.contains("Equipped") || aff.contains("Enchanted") || aff.contains("Attached")) continue;
            return true;
        }
        return false;
    }

    /** lordtarget (2026-10-13): our damage aimed at one opponent creature goes to a lord it kills instead */
    static void lordRetarget(Player me, SpellAbility sa) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            if (x.getApi() != forge.game.ability.ApiType.DealDamage || !x.usesTargeting() || x.getTargets() == null) continue;
            List<Card> tc = new java.util.ArrayList<>(x.getTargets().getTargetCards());
            if (tc.size() != 1 || x.getTargets().size() != 1) continue;
            Card chosen = tc.get(0);
            if (chosen.getController() == me || !chosen.isCreature() || isLord(chosen)) continue;
            int dmg;
            try {
                dmg = forge.game.ability.AbilityUtils.calculateAmount(x.getHostCard(), x.getParam("NumDmg"), x);
            } catch (Exception e) {
                dmg = chosen.getNetToughness() - chosen.getDamage();   // what Forge's own choice needed
            }
            Card best = null;
            for (Card c : chosen.getController().getCreaturesInPlay()) {
                if (!isLord(c) || c.getNetToughness() - c.getDamage() > dmg || !x.canTarget(c)) continue;
                if (best == null || c.getNetPower() > best.getNetPower()) best = c;
            }
            if (best == null) continue;
            x.resetTargets();
            x.getTargets().add(best);
            ControlBrain.count("lordtarget");
        }
    }

    static SpellAbility bestCreatureSpell(Player me, boolean flashOnly) {
        forge.game.card.CardCollection hand = new forge.game.card.CardCollection();
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (c.isCreature() && (!flashOnly || c.hasKeyword(forge.game.keyword.Keyword.FLASH))) hand.add(c);
        SpellAbility best = null;
        for (SpellAbility sa : forge.ai.ComputerUtilAbility.getSpellAbilities(hand, me)) {
            if (!sa.isSpell() || !sa.getHostCard().isCreature() || sa.isAdventure()) continue;
            sa.setActivatingPlayer(me);
            try {
                if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) continue;
            } catch (Exception e) {
                continue;
            }
            if (best == null || sa.getHostCard().getCMC() > best.getHostCard().getCMC()) best = sa;
        }
        return best;
    }

    static boolean named(Card c, String name) {
        return c.getName().equals(name);
    }

    static SpellAbility spiderHamBite(Player me) {
        Game game = me.getGame();
        if (!game.getPhaseHandler().isPlayerTurn(me) || !game.getStack().isEmpty()) return null;
        PhaseType ph = game.getPhaseHandler().getPhase();
        if (ph != PhaseType.MAIN1 && ph != PhaseType.MAIN2) return null;
        String ham = "Spider-Ham, Peter Porker";
        if (me.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> named(c, "Diligent Zookeeper"))) return null;
        if (me.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> named(c, ham))) return null;
        if (me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> named(c, ham))) return null;
        if (me.getCardsIn(ZoneType.Library).stream().noneMatch(c -> named(c, ham))) return null;
        int mana = ComputerUtilMana.getAvailableManaEstimate(me);
        if (mana < 4) return null;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c.isCreature() && c.getCMC() >= 4 && c.getCMC() <= mana) return null; // a big creature to cast instead
        }
        for (Card spider : me.getCardsIn(ZoneType.Battlefield)) {
            if (!named(spider, "Radioactive Spider")) continue;
            for (SpellAbility sa : spider.getSpellAbilities()) {
                if (!sa.isAbility() || sa.getApi() != ApiType.ChangeZone) continue;
                sa.setActivatingPlayer(me);
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) return sa;
            }
        }
        return null;
    }
}
