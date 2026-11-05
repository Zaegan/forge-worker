import forge.ai.AiCardMemory;
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

/**
 * The open hypotheses of pilot/TUNE_TARGETS.md (user, 2026-11-02: "build instrumentation for all untested hypotheses
 * and queue up tests for them"), one switch each, scoped with @NAME. Rules shared by several decks are written once
 * and scoped per deck.
 *
 * Shared:
 *   chumplethal@NAME  D8b/G6/A9  after Forge's blocks: if the unblocked damage leaves us at or below the opponent's
 *                     total creature power (dead next turn) and chump blocks can keep us above it, chump the biggest
 *                     unblocked attackers with our least valuable untapped creatures (scans dl-s11, gl-s10, ad-s11)
 *   manlandsafe@NAME  A3/D3  on our turn, no creature-land animation (Restless Anchorage/Reef, Soulstone Sanctuary)
 *                     outside main 1 / beginning of combat, nor into 2+ open opponent mana with a card in their hand,
 *                     unless the animated land's power is lethal (scans ad-s10, ad-s11, al2-s11, dl-s11)
 *   undervoice@NAME   S7/A12  the opponent controls Voice of Victory (we can't cast on their turn): in our main 2,
 *                     when Forge would pass, cast the costliest castable instant or flash card that needs no spell to
 *                     target (draw, flash creatures) instead of holding it for a turn we can't use
 *   freeattack@NAME   B5b/S8  after Forge declares attackers: add each creature no untapped opposing creature could
 *                     block and kill, when the opponent's creatures can't kill us next turn without it (ab-s10: Voice
 *                     of Victory stayed home; sl-s10: Hearth Elementals stayed home against fliers)
 *   landcast@NAME     P6/A13a  (LandColour) like landcolour, counting every nonland card castable this turn
 * Dimir Midrange (dl-s11, ad-s11):
 *   hexblight@NAME    D8a  no optional blight (Requiting Hex) unless a creature of ours survives a -1/-1 counter
 *   kaitoafter@NAME   D4   Kaito's [0] (draws per opponent who lost life) not in main 1 while one of our creatures
 *                     can attack and the opponent has lost no life this turn
 *   mapfirst@NAME     D6   main 1, Forge would go to combat: crack a Map on our creatures before attacking
 * Azorius Control (ab-s10, al2-s11, ad-s11):
 *   azvoice@NAME      A12a  the opponent controls Voice of Victory: on our turn answer it (Seam Rip, Get Lost, Erode,
 *                     Pinnacle Starcage, Day of Judgment) when Forge plays something else or passes
 *   azholdvoice@NAME  A12b  A1 (azoriushold) lets the draw-only Three Steps Ahead through on our own turn while the
 *                     opponent controls Voice of Victory (MetaBrain)
 *   starcagesafe@NAME A13b  no Pinnacle Starcage that exiles our best creature, or as much of ours as of theirs
 *   azerode@NAME      A12/A13  Erode on a target Seam Rip can take: Seam Rip instead; Erode while the opponent has a
 *                     landfall card: Get Lost instead (Erode's basic land feeds landfall)
 *   azstabilise@NAME  A2   facing a lethal board (their creatures' power >= our life), on our turn: Day of Judgment
 *                     or Pinnacle Starcage before card draw or a pass, when it removes more of theirs than ours
 *   azreserve2@NAME   A13c  vs a ramp board (5+ opponent lands, 2+ cards in hand), on our turn: no Stock Up and no
 *                     Restless Anchorage animation that leaves No More Lies / Three Steps Ahead in hand uncastable
 *   azcopy@NAME       A4   no Three Steps Ahead copy mode on a token
 *   azdemo@NAME       A7   Demolition Field on the opponent's animated creature land
 * Boros Dragons (ab-s10):
 *   smaugattack@NAME  B5c  Smaug the Magnificent attacks when his Treasure trigger alone is lethal
 *   dragontutor2@NAME B5d  B3 (dragontutor) also counts a creature castable from exile (warped Nova Hellkite)
 *   dragonshock@NAME  B6a  (MetaBrain.payShrine) Sacred Foundry / Multiversal Passage untapped for 2 life on our turn
 *                     when one more mana casts a card in hand, at 6+ life (bl-s11: declined 6 times in 4 games)
 *   dragonprecombat@NAME B6b main 1, Forge would go to combat: cast a castable Dragon first (bl-s11: 3 Dragons cast
 *                     after combat, so Sarkhan / Momo attacked without the boost)
 * Izzet Prowess (pl-s11, pg-s11):
 *   prowesssorc@NAME  P10b main 1, no lethal line: sorcery cantrips before combat when a per-spell creature of ours
 *                     can attack (P10 needed an unblockable one; 10 sorceries were cast after combat in 20 losses)
 *   prowessharm@NAME  P16c Wild Ride's harmonize taps a creature that is neither the spell's target nor able to attack
 *   prowesslethal2@NAME P16 (ProwessBrain) the lethal search also counts Secret Identity (Reveal: 3/4 flier) and an
 *                     untapped land drop from hand (Steam Vents paid)
 * manasave@NAME     A14c / D8 / B5e  which lands pay a spell: those making colours the rest of the hand needs are
 *                     held back (Forge's held-mana memory) while the spell stays payable, so the generic is paid from
 *                     the rest; Cavern of Souls is kept for a creature (AiTweaks.chooseSpellAbilityToPlay, released at
 *                     the next decision)
 * Round 5 (scan dml-s14, Dimir vs Landfall, 2026-11-04):
 *   waspcombat@NAME   D10a The Wondrous Wasp is cast in the opponent's main 1 (when we get priority there, with the stack
 *                     empty) so its ETB taps an attacker before combat, not on our turn or in their upkeep, while they
 *                     have a possible attacker; on our turn other spells keep Wasp's 2 mana open (smoke: Wasp was held
 *                     but Dimir had 0-1 open lands on their turn, so it never came down)
 *   tidehold@NAME     D10b while the opponent has a landfall creature, our own-turn spells leave the mana for Tishana's
 *                     Tidebinder in hand (it stops the landfall trigger and strips the creature's abilities)
 * Round 3 (user 2026-11-02: "build out all hypotheses and test them"; scans dl2-s12, sl-s12, sb-s12):
 *   holdcounter@NAME  D9a/S13a  on our turn, no spell (but a counter) that leaves us without the mana for the cheapest
 *                     counterspell in hand, when we could cast it now and the opponent holds cards (not vs Cavern of Souls)
 *   eotflash@NAME     D9a/S13a  the opponent's end step, Forge would pass: cast the costliest castable instant or flash
 *                     card that is not a counter (the mana held up for counters is spent before our untap)
 *   idlecantrip@NAME  S14a  Forge would pass in our main 2 (main 1 with no land in hand and no land played): cast a
 *                     cantrip that targets nothing, when the counter mana held in hand stays up
 *   stunbig@NAME      D9b  (playTrigger) Floodpits Drowner / The Wondrous Wasp target the opponent's biggest creature
 *   noearthbent@NAME  D9c  no removal on the opponent's land creature (earthbent: it comes back): their best other
 *                     creature instead, or nothing
 *   keepblocker@NAME  S13b  after attacks are declared, when the attack is not lethal and the crack-back (their whole
 *                     board, minus one attacker per creature left home) kills us: take attackers back, best blockers
 *                     first, until it doesn't
 *   elemfirst@NAME    S14e  Sunderflock in hand and not castable: cast an Elemental first when Sunderflock is then
 *                     castable with the mana left (costs X less, X the greatest Elemental mana value)
 *   keephearth@NAME   S13c  surveil and discard keep Hearth Elemental, Eddymurk Crab and Sunderflock (spare spells go)
 *   spellshock2@NAME  S14b  (MetaBrain.payShrine) Steam Vents' 2 life only when one more mana casts a card in hand
 *   (B7, Dragons' passivity, is smaugattack / dragonprecombat / spendmana in m2-BD.)
 * Round 4 (user 2026-11-03: "build out all hypotheses and test them"; scans ps-s13, pc-s13, ac-s13):
 *   vetofall@NAME     P19a  (AiTweaks) when prowessplan vetoes Forge's play, cast the costliest castable creature (not
 *                     Eddymurk Crab) or non-targeting sorcery that doesn't dump the hand, instead of passing
 *   hastefirst@NAME   P19b/P20b  our main 1: cast a castable haste creature before combat
 *   sidprotect@NAME   P20a  the opponent's spell or ability on the stack targets a creature of ours: Secret Identity's
 *                     Conceal (hexproof) on it
 *   creaturefirst@NAME P20c  our main phase, Forge casts a noncreature spell while a per-spell creature (prowess,
 *                     Slickshot Show-Off) is castable from hand: the creature first
 *   prowessharm@NAME  (fix) Forge asks harmonize's tap with chooseNumber(sa, title, min, max), not the list form
 *   hallsafe@NAME     P20e  no creature-land animation (Great Hall of the Biblioplex and the MANLANDS) on the opponent's
 *                     turn except to block (their declare-attackers step)
 *   monkpump@NAME     pc-s13 (4c seat)  our main 1, Forge would go to combat while a prowess creature of ours can
 *                     attack: cast a castable non-targeting noncreature spell first (4c: Stock Up before the Monks attack).
 *                     4c's pilot changes need the user's approval before adoption (2026-11-03)
 *   nmlcounter@NAME   ac-s13 (A14a)  the opponent's spell (mana value 3+) on top of the stack and fewer than 3 of their
 *                     lands untapped: No More Lies on it (it can't pay the 3)
 *   flashwait@NAME    A14b  no flash creature (Wan Shi Tong) on our own turn: it waits for their end step (with eotflash)
 *   fountainlate@NAME A14d  no Fountainport activation (Treasure, Fish, draw) on the opponent's turn before their end step
 *   shockneed@NAME    ac-s13 (4c seat)  any shockland: 2 life only at 6+ life when one more mana casts a card in hand
 *                     (MetaBrain.payShrine)
 *   wardpay@NAME      ac-s13 (4c seat)  no targeted spell at a creature whose ward we can't pay with the mana left
 *   flashbackmana@NAME ac-s13 (4c seat)  no Flashback unless the mana left recasts an instant or sorcery in our graveyard
 *   (A14c, Emeritus's generic paid with W/U, needs a mana-payment hook: not instrumentable, like B5e.)
 * Only public information is used: the board, life totals and our own hand.
 */
public class MetaBrain2 {
    static boolean on(String r, Player p) { return AiTweaks.on(r, p); }

    static final String VOICE = "Voice of Victory", STARCAGE = "Pinnacle Starcage", SEAMRIP = "Seam Rip", ERODE = "Erode",
            GETLOST = "Get Lost", DOJ = "Day of Judgment", KAITO = "Kaito, Bane of Nightmares", SMAUG = "Smaug the Magnificent",
            NML = "No More Lies", THREESTEPS = "Three Steps Ahead", STOCKUP = "Stock Up", WILDRIDE = "Wild Ride";
    static final java.util.Set<String> MANLANDS = java.util.Set.of("Restless Anchorage", "Restless Reef", "Soulstone Sanctuary");

    static boolean voiceOut(Player op) {
        return op != null && op.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals(VOICE));
    }

    static int boardPower(Player p) {
        return p.getCreaturesInPlay().stream().mapToInt(c -> Math.max(0, c.getNetCombatDamage())).sum();
    }

    static int openLands(Player p) {
        return (int) p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isLand() && c.isUntapped()).count();
    }

    static final String WASP = "The Wondrous Wasp", TIDEBINDER = "Tishana's Tidebinder";

    /* ---------------- manasave: which lands pay (A14c, D8, B5e) ---------------- */

    static final java.util.Map<Player, List<Card>> saved = new java.util.HashMap<>();

    /** our spell SA is about to be paid: lands making colours the rest of our hand needs (and Cavern of Souls, for a
     *  non-creature spell while a creature is in hand) go into Forge's held-mana memory, which its payment code skips,
     *  one at a time while SA stays payable; most-needed colours first, so the generic part is paid from the rest */
    static void manaSave(Player me, SpellAbility sa) {
        if (sa == null || !sa.isSpell() || sa.isManaAbility() || sa.getHostCard() == null || sa.getPayCosts() == null) return;
        forge.card.mana.ManaCost cost;
        try { cost = sa.getPayCosts().getTotalMana(); } catch (Exception e) { return; }
        if (cost == null || cost.countX() > 0 || cost.getGenericCost() == 0) return;
        Card host = sa.getHostCard();
        java.util.Map<Character, Integer> need = new java.util.HashMap<>();
        boolean creatureInHand = false;
        int lands = openLands(me);
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c == host || c.isLand()) continue;
            if (c.isCreature()) creatureInHand = true;
            if (c.getCMC() > lands) continue;
            for (char x : c.getManaCost().getShortString().toCharArray()) if ("WUBRG".indexOf(x) >= 0) need.merge(x, 1, Integer::sum);
        }
        List<Card> cand = new ArrayList<>();
        java.util.Map<Card, Integer> score = new java.util.HashMap<>();
        for (Card l : ManaHold.untappedLands(me)) {
            if (AiCardMemory.isRememberedCard(me, l, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK)) continue;
            int v = 0;
            for (char x : LandOrder.colours(l).toCharArray()) v += need.getOrDefault(x, 0);
            if (l.getName().equals("Cavern of Souls") && !host.isCreature() && creatureInHand) v += 100;
            if (v > 0) { cand.add(l); score.put(l, v); }
        }
        if (cand.isEmpty()) return;
        cand.sort((x, y) -> Integer.compare(score.get(y), score.get(x)));
        List<Card> held = new ArrayList<>();
        for (Card l : cand) {
            AiCardMemory.rememberCard(me, l, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
            boolean ok;
            try { ok = forge.ai.ComputerUtilMana.canPayManaCost(new forge.game.mana.ManaCostBeingPaid(cost), sa, me, false); }
            catch (Exception e) { ok = false; }
            if (ok) held.add(l);
            else AiCardMemory.forgetCard(me, l, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
        }
        if (held.isEmpty()) { ControlBrain.count("manasave_none"); return; }
        saved.put(me, held);
        ControlBrain.count("manasave_held");
    }

    /** at our next decision: the lands manaSave held go back to Forge */
    static void manaRelease(Player me) {
        List<Card> held = saved.remove(me);
        if (held != null) for (Card l : held) AiCardMemory.forgetCard(me, l, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
    }

    /** diagnostics: why our Wasp is not castable (canPlay / canPayCost, untapped lands) */
    static String waspWhy(Player me) {
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (!c.getName().equals(WASP)) continue;
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (!sa.isSpell()) continue;
                sa.setActivatingPlayer(me);
                boolean cp = false, pay = false;
                try { cp = sa.canPlay(); } catch (Exception e) { }
                try { pay = forge.ai.ComputerUtilCost.canPayCost(sa, me, false); } catch (Exception e) { }
                return "_" + (cp ? "play" : "noplay") + "_" + (pay ? "pay" : "nopay") + "_l" + Math.min(openLands(me), 3);
            }
        }
        return "_nosa";
    }

    /** an untapped creature of OP that can attack this turn (null if none) */
    static Card attackerOf(Player op) {
        for (Card c : op.getCreaturesInPlay()) if (c.isUntapped() && !c.hasSickness() && !c.hasKeyword(Keyword.DEFENDER)) return c;
        return null;
    }

    /** OP controls a creature with a landfall ability (Harmonizer, Chocobo) */
    static boolean landfallEngine(Player op) {
        for (Card c : op.getCreaturesInPlay()) {
            String t = c.getOracleText() == null ? "" : c.getOracleText().toLowerCase();
            if (t.contains("landfall") || t.contains("whenever a land you control enters")) return true;
        }
        return false;
    }

    static boolean ourTurn(Player me) { return me.getGame().getPhaseHandler().isPlayerTurn(me); }
    static PhaseType phase(Player me) { return me.getGame().getPhaseHandler().getPhase(); }

    static boolean any(Player p) {
        for (String r : new String[]{"manlandsafe", "undervoice", "kaitoafter", "mapfirst", "azvoice", "starcagesafe", "azerode",
                "azstabilise", "azreserve2", "azcopy", "azdemo", "dragontutor2", "prowesssorc", "dragonprecombat",
                "holdcounter", "eotflash", "idlecantrip", "noearthbent", "elemfirst",
                "hastefirst", "sidprotect", "creaturefirst", "hallsafe", "monkpump",
                "nmlcounter", "flashwait", "fountainlate", "wardpay", "flashbackmana", "waspcombat", "tidehold", "manasave"})
            if (on(r, p)) return true;
        return false;
    }

    /* ---------------- choose: plays of our own, before Forge's AI ---------------- */

    static Card concealFor = null;

    static SpellAbility choose(Player me) {
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        if (op == null) return null;
        if (on("sidprotect", me) && !g.getStack().isEmpty()) {
            SpellAbility p = protect(me, op);
            if (p != null) { ControlBrain.count("sidprotect_cast"); return p; }
        }
        if (on("nmlcounter", me) && !g.getStack().isEmpty()) {
            SpellAbility c = nml(me, op);
            if (c != null) { ControlBrain.count("nmlcounter_cast"); return c; }
        }
        if (!g.getStack().isEmpty()) return null;
        if (on("azdemo", me)) { SpellAbility d = demo(me, op); if (d != null) return d; }
        if (!ourTurn(me)) return null;
        if (on("hastefirst", me) && phase(me) == PhaseType.MAIN1) {
            List<SpellAbility> hs = MetaBrain.castableHand(me, c -> c.isCreature() && c.hasKeyword(Keyword.HASTE) && c.getNetPower() > 0);
            hs.sort((x, y) -> Integer.compare(y.getHostCard().getNetPower(), x.getHostCard().getNetPower()));
            if (!hs.isEmpty()) { ControlBrain.count("hastefirst_cast"); return hs.get(0); }
        }
        if (on("elemfirst", me) && (phase(me) == PhaseType.MAIN1 || phase(me) == PhaseType.MAIN2)) {
            SpellAbility e = elemFirst(me);
            if (e != null) { ControlBrain.count("elemfirst_cast"); return e; }
        }
        if (on("azvoice", me) && voiceOut(op) && (phase(me) == PhaseType.MAIN1 || phase(me) == PhaseType.MAIN2)) {
            SpellAbility v = answerVoice(me, op);
            if (v != null) { ControlBrain.count("azvoice_answer"); return v; }
        }
        if (on("azstabilise", me) && phase(me) == PhaseType.MAIN1 && boardPower(op) >= me.getLife()) {
            SpellAbility s = stabilise(me, op);
            if (s != null) { ControlBrain.count("azstabilise_cast"); return s; }
        }
        if (on("prowesssorc", me) && phase(me) == PhaseType.MAIN1) {
            SpellAbility s = sorcFirst(me, op);
            if (s != null) { ControlBrain.count("prowesssorc_cast"); return s; }
        }
        return null;
    }

    /** A7: Demolition Field on an opponent's land that is a creature now */
    static SpellAbility demo(Player me, Player op) {
        Card target = null;
        for (Card c : op.getCardsIn(ZoneType.Battlefield)) if (c.isLand() && c.isCreature() && !c.getType().isBasicLand()) target = c;
        if (target == null) return null;
        for (Card f : me.getCardsIn(ZoneType.Battlefield)) {
            if (!f.getName().equals("Demolition Field") || f.isTapped()) continue;
            for (SpellAbility sa : f.getSpellAbilities()) {
                if (sa.isManaAbility() || sa.getApi() != ApiType.Destroy) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (!sa.canPlay() || !forge.ai.ComputerUtilCost.canPayCost(sa, me, false) || !sa.canTarget(target)) continue;
                } catch (Exception e) { continue; }
                sa.resetTargets(); sa.getTargets().add(target);
                ControlBrain.count("azdemo_fired");
                return sa;
            }
        }
        return null;
    }

    /** our castable spells (hand) named N, with the first target Voice of Victory when the spell targets */
    static SpellAbility answerVoice(Player me, Player op) {
        Card voice = null;
        for (Card c : op.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals(VOICE)) voice = c;
        for (String n : new String[]{SEAMRIP, GETLOST, ERODE, STARCAGE, DOJ}) {
            for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(n))) {
                if (n.equals(DOJ) && countOurs(me, c -> true) >= op.getCreaturesInPlay().size()) continue;
                if (n.equals(STARCAGE) && cageBad(me, op)) continue;
                if (n.equals(GETLOST) || n.equals(ERODE)) {
                    if (!sa.canTarget(voice)) continue;
                    sa.resetTargets(); sa.getTargets().add(voice);
                }
                return sa;   // Seam Rip's ETB targets on resolution (Forge's choice: Voice is usually its only MV<=2 target)
            }
        }
        return null;
    }

    static int countOurs(Player me, java.util.function.Predicate<Card> p) {
        return (int) me.getCreaturesInPlay().stream().filter(p).count();
    }

    /** A2: a sweeper that removes more of theirs than ours */
    static SpellAbility stabilise(Player me, Player op) {
        int theirs = op.getCreaturesInPlay().size(), ours = me.getCreaturesInPlay().size();
        if (theirs >= 2 && theirs > ours)
            for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(DOJ))) return sa;
        for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(STARCAGE)))
            if (!cageBad(me, op) && cageCount(op) >= 2) return sa;
        return null;
    }

    static int cageCount(Player p) {
        return (int) p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> (c.isCreature() || c.isArtifact()) && !c.isLand() && c.getCMC() <= 2).count();
    }

    /** A13b: Starcage exiles our best creature, or at least as many of ours as of theirs */
    static boolean cageBad(Player me, Player op) {
        Card best = null;
        for (Card c : me.getCreaturesInPlay()) if (best == null || c.getNetPower() > best.getNetPower()) best = c;
        if (best != null && best.getCMC() <= 2 && best.getNetPower() >= 3) return true;
        int ours = (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> (c.isCreature() || c.isArtifact()) && !c.isLand() && c.getCMC() <= 2
                && !c.getName().equals(STARCAGE)).count();
        return ours > 0 && ours >= cageCount(op);
    }

    /** P10b: a castable non-targeting sorcery cantrip when a per-spell creature of ours can attack */
    static SpellAbility sorcFirst(Player me, Player op) {
        boolean grower = false;
        for (Card c : me.getCreaturesInPlay()) if (ProwessBrain.canAttackNow(c) && ProwessBrain.perSpell(c) > 0) grower = true;
        if (!grower) return null;
        for (SpellAbility sa : ProwessBrain.castable(me, c -> c.isSorcery(), ProwessBrain.mana(me)))
            if (!AiTweaks.targets(sa) && ProwessBrain.cantrip(sa) && !ProwessBrain.dumpsHand(me, sa)) return sa;
        return null;
    }

    /* ---------------- post: changes to Forge's chosen play ---------------- */

    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        if (op == null) return ai;
        boolean ours = ourTurn(me);
        PhaseType ph = phase(me);
        boolean pass = ai == null || ai.isEmpty();
        if (on("waspcombat", me) && !ours && me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals(WASP)))
            ControlBrain.count("waspcombat_seen_" + ph + (g.getStack().isEmpty() ? "" : "_stack") + (pass ? "" : "_ai")
                    + (MetaBrain.castableHand(me, c -> c.getName().equals(WASP)).isEmpty() ? "_nocast" + waspWhy(me) : "")
                    + (attackerOf(op) == null ? "_noatt" : ""));
        if (pass) {
            if (on("undervoice", me) && ours && ph == PhaseType.MAIN2 && g.getStack().isEmpty() && voiceOut(op)) {
                List<SpellAbility> inst = MetaBrain.castableHand(me, c -> (c.isInstant() || c.hasKeyword(Keyword.FLASH)) && !c.isLand());
                inst.removeIf(sa -> sa.getApi() == ApiType.Counter || MetaBrain.hasMode(sa, ApiType.Counter) && !MetaBrain.hasMode(sa, ApiType.Draw));
                inst.sort((x, y) -> Integer.compare(MetaBrain.cost(y), MetaBrain.cost(x)));
                if (!inst.isEmpty()) { ControlBrain.count("undervoice_cast"); return List.of(inst.get(0)); }
            }
            if (on("waspcombat", me) && !ours && (ph == PhaseType.MAIN1 || ph == PhaseType.COMBAT_BEGIN) && g.getStack().isEmpty()
                    && attackerOf(op) != null) {   // Forge gives no priority at their beginning of combat: main 1 once they pass
                List<SpellAbility> w = MetaBrain.castableHand(me, c -> c.getName().equals(WASP));
                if (!w.isEmpty()) { ControlBrain.count("waspcombat_cast"); return List.of(w.get(0)); }
            }
            if (on("eotflash", me) && !ours && ph == PhaseType.END_OF_TURN && g.getStack().isEmpty()) {
                List<SpellAbility> inst = MetaBrain.castableHand(me, c -> (c.isInstant() || c.hasKeyword(Keyword.FLASH)) && !c.isLand() && !isCounter(c));
                inst.removeIf(AiTweaks::targets);
                inst.sort((x, y) -> Integer.compare(MetaBrain.cost(y), MetaBrain.cost(x)));
                if (!inst.isEmpty()) { ControlBrain.count("eotflash_cast"); return List.of(inst.get(0)); }
            }
            if (on("idlecantrip", me) && ours && g.getStack().isEmpty() && (ph == PhaseType.MAIN2 || ph == PhaseType.MAIN1
                    && me.getLandsPlayedThisTurn() == 0 && me.getCardsIn(ZoneType.Hand).stream().noneMatch(Card::isLand))) {
                int hold = counterCost(me);
                for (SpellAbility sa : MetaBrain.castableHand(me, c -> (c.isInstant() || c.isSorcery()) && !isCounter(c)))
                    if (!AiTweaks.targets(sa) && ProwessBrain.cantrip(sa) && (hold == 0 || openLands(me) - MetaBrain.cost(sa) >= hold)) {
                        ControlBrain.count("idlecantrip_cast"); return List.of(sa);
                    }
            }
            if (on("monkpump", me) && ours && ph == PhaseType.MAIN1 && g.getStack().isEmpty()
                    && me.getCreaturesInPlay().stream().anyMatch(c -> c.hasKeyword(Keyword.PROWESS) && LandfallBrain.canAttackThisTurn(c))) {
                List<SpellAbility> sp = MetaBrain.castableHand(me, c -> !c.isCreature() && !c.isLand() && !isCounter(c));
                sp.removeIf(AiTweaks::targets);
                sp.sort((x, y) -> Integer.compare(MetaBrain.cost(y), MetaBrain.cost(x)));
                if (!sp.isEmpty()) { ControlBrain.count("monkpump_cast"); return List.of(sp.get(0)); }
            }
            if (on("dragonprecombat", me) && ours && ph == PhaseType.MAIN1 && g.getStack().isEmpty()) {
                List<SpellAbility> dr = MetaBrain.castableHand(me, c -> c.isCreature() && c.getType().hasCreatureType("Dragon"));
                dr.sort((x, y) -> Integer.compare(MetaBrain.cost(y), MetaBrain.cost(x)));
                if (!dr.isEmpty()) { ControlBrain.count("dragonprecombat_cast"); return List.of(dr.get(0)); }
            }
            if (on("mapfirst", me) && ours && ph == PhaseType.MAIN1 && g.getStack().isEmpty()
                    && me.getCreaturesInPlay().stream().anyMatch(LandfallBrain::canAttackThisTurn)) {
                for (Card m : me.getCardsIn(ZoneType.Battlefield)) {
                    if (!m.getName().equals("Map Token") && !(m.isToken() && m.getName().startsWith("Map"))) continue;
                    for (SpellAbility sa : m.getSpellAbilities()) {
                        sa.setActivatingPlayer(me);
                        try {
                            if (sa.canPlay() && forge.ai.ComputerUtilCost.canPayCost(sa, me, false)) { ControlBrain.count("mapfirst_crack"); return List.of(sa); }
                        } catch (Exception e) { }
                    }
                }
            }
            return ai;
        }
        SpellAbility pick = ai.get(0);
        Card h = pick.getHostCard();
        if (h == null) return ai;
        String n = h.getName();
        if (on("holdcounter", me) && ours && pick.isSpell() && !h.isLand() && !isCounter(h) && !op.getCardsIn(ZoneType.Hand).isEmpty()
                && op.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> c.getName().equals("Cavern of Souls"))) {
            int hold = counterCost(me);
            if (hold > 0 && openLands(me) >= hold && openLands(me) - MetaBrain.cost(pick) < hold) { ControlBrain.count("holdcounter_held"); return null; }
        }
        if (on("waspcombat", me) && pick.isSpell() && n.equals(WASP)
                && (ours || ph.isBefore(PhaseType.MAIN1)) && attackerOf(op) != null) {
            ControlBrain.count("waspcombat_held"); return null;
        }
        if (on("waspcombat", me) && ours && pick.isSpell() && !h.isLand() && !n.equals(WASP)
                && op.getCreaturesInPlay().stream().anyMatch(c -> !c.hasKeyword(Keyword.DEFENDER))) {
            List<SpellAbility> w = MetaBrain.castableHand(me, c -> c.getName().equals(WASP));
            if (!w.isEmpty() && openLands(me) - MetaBrain.cost(pick) < MetaBrain.cost(w.get(0))) { ControlBrain.count("waspcombat_mana"); return null; }
        }
        if (on("tidehold", me) && ours && pick.isSpell() && !h.isLand() && !n.equals(TIDEBINDER) && landfallEngine(op)) {
            List<SpellAbility> tb = MetaBrain.castableHand(me, c -> c.getName().equals(TIDEBINDER));
            int hold = tb.isEmpty() ? 0 : MetaBrain.cost(tb.get(0));
            if (hold > 0 && openLands(me) - MetaBrain.cost(pick) < hold) { ControlBrain.count("tidehold_held"); return null; }
        }
        if (on("flashwait", me) && ours && pick.isSpell() && h.isCreature() && h.hasKeyword(Keyword.FLASH)
                && !op.getCardsIn(ZoneType.Hand).isEmpty()) { ControlBrain.count("flashwait_held"); return null; }
        if (on("fountainlate", me) && !ours && n.equals("Fountainport") && !pick.isManaAbility() && ph != PhaseType.END_OF_TURN) {
            ControlBrain.count("fountainlate_held"); return null;
        }
        if (on("flashbackmana", me) && pick.isSpell() && n.equals("Flashback")) {
            int left = openLands(me) - MetaBrain.cost(pick);
            boolean recast = me.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c -> (c.isInstant() || c.isSorcery()) && c.getCMC() <= left);
            if (!recast) { ControlBrain.count("flashbackmana_held"); return null; }
        }
        if (on("wardpay", me) && pick.isSpell()) {
            int left = openLands(me) - MetaBrain.cost(pick);
            for (SpellAbility x = pick; x != null; x = x.getSubAbility())
                if (x.getTargets() != null)
                    for (Card t : x.getTargets().getTargetCards())
                        if (t.getController() == op && ward(t) > left) { ControlBrain.count("wardpay_held"); return null; }
        }
        if (on("hallsafe", me) && !ours && pick.getApi() == ApiType.Animate && !pick.isManaAbility()
                && (MANLANDS.contains(n) || n.equals("Great Hall of the Biblioplex")) && ph != PhaseType.COMBAT_DECLARE_ATTACKERS) {
            ControlBrain.count("hallsafe_held"); return null;
        }
        if (on("creaturefirst", me) && ours && pick.isSpell() && !h.isCreature() && !h.isLand()
                && (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2) && g.getStack().isEmpty()) {
            List<SpellAbility> cr = MetaBrain.castableHand(me, c -> c.isCreature() && ProwessBrain.perSpell(c) > 0 && c != h);
            cr.sort((x, y) -> Integer.compare(ProwessBrain.perSpell(y.getHostCard()), ProwessBrain.perSpell(x.getHostCard())));
            if (!cr.isEmpty()) { ControlBrain.count("creaturefirst_cast"); return List.of(cr.get(0)); }
        }
        if (on("noearthbent", me) && pick.isSpell()) {
            for (SpellAbility x = pick; x != null; x = x.getSubAbility()) {
                if (x.getTargets() == null || !(x.getApi() == ApiType.Destroy || x.getApi() == ApiType.ChangeZone || x.getApi() == ApiType.DealDamage
                        || x.getApi() == ApiType.PutCounter)) continue;
                for (Card t : new ArrayList<>(x.getTargets().getTargetCards())) {
                    if (t.getController() != op || !t.isLand() || !t.isCreature()) continue;
                    Card alt = null;
                    for (Card c : op.getCreaturesInPlay()) if (!c.isLand() && x.canTarget(c) && (alt == null || value(c) > value(alt))) alt = c;
                    if (alt == null) { ControlBrain.count("noearthbent_held"); return null; }
                    x.resetTargets(); x.getTargets().add(alt); ControlBrain.count("noearthbent_retarget");
                }
            }
        }
        if (on("manlandsafe", me) && ours && MANLANDS.contains(n) && !pick.isManaAbility() && pick.getApi() == ApiType.Animate) {
            boolean timing = ph == PhaseType.MAIN1 || ph == PhaseType.COMBAT_BEGIN;
            int pw = n.equals("Restless Reef") ? 4 : n.equals("Soulstone Sanctuary") ? 3 : 2;
            boolean danger = openLands(op) >= 2 && !op.getCardsIn(ZoneType.Hand).isEmpty();
            if (!timing || danger && op.getLife() > pw) { ControlBrain.count("manlandsafe_" + (timing ? "danger" : "timing")); return null; }
        }
        if (on("kaitoafter", me) && ours && ph == PhaseType.MAIN1 && n.equals(KAITO) && pick.isPwAbility()
                && pick.getApi() == ApiType.Surveil && op.getLifeLostThisTurn() == 0
                && me.getCreaturesInPlay().stream().anyMatch(c -> c != h && LandfallBrain.canAttackThisTurn(c))) {
            ControlBrain.count("kaitoafter_held");
            return null;
        }
        if (on("starcagesafe", me) && pick.isSpell() && n.equals(STARCAGE) && cageBad(me, op)) {
            ControlBrain.count("starcagesafe_vetoed");
            return null;
        }
        if (on("azcopy", me) && pick.isSpell() && n.equals(THREESTEPS) && MetaBrain.hasMode(pick, ApiType.CopyPermanent)) {
            for (SpellAbility s = pick; s != null; s = s.getSubAbility())
                if (s.getApi() == ApiType.CopyPermanent && s.getTargets() != null)
                    for (forge.game.GameObject t : s.getTargets()) if (t instanceof Card && ((Card) t).isToken()) { ControlBrain.count("azcopy_vetoed"); return null; }
        }
        if (on("azerode", me) && pick.isSpell() && n.equals(ERODE) && pick.getTargets() != null) {
            Card t = null;
            for (forge.game.GameObject o : pick.getTargets()) if (o instanceof Card) t = (Card) o;
            if (t != null && t.getController() == op) {
                if (t.getCMC() <= 2)
                    for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(SEAMRIP))) { ControlBrain.count("azerode_seamrip"); return List.of(sa); }
                if (landfallOpp(op))
                    for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(GETLOST)))
                        if (sa.canTarget(t)) { sa.resetTargets(); sa.getTargets().add(t); ControlBrain.count("azerode_getlost"); return List.of(sa); }
            }
        }
        if (on("azreserve2", me) && ours && openLands(op) >= 5 && op.getCardsIn(ZoneType.Hand).size() >= 2
                && (n.equals(STOCKUP) && pick.isSpell() || n.equals("Restless Anchorage") && pick.getApi() == ApiType.Animate)) {
            boolean counter = me.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals(NML) || c.getName().equals(THREESTEPS));
            int left = openLands(me) - MetaBrain.cost(pick);
            if (counter && left < 2) { ControlBrain.count("azreserve2_held"); return null; }
        }
        return ai;
    }

    /** the generic mana of a creature's Ward (0: none or a non-mana ward) */
    static int ward(Card c) {
        int w = 0;
        for (forge.game.keyword.KeywordInterface k : c.getKeywords()) {
            String t = k.getOriginal();
            if (t == null || !t.startsWith("Ward")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(t);
            if (m.find()) w = Math.max(w, Integer.parseInt(m.group(1)));
        }
        return w;
    }

    /** A14a: No More Lies on the opponent's spell (MV 3+) on top when they have fewer than 3 untapped lands */
    static SpellAbility nml(Player me, Player op) {
        SpellAbility top = me.getGame().getStack().peekAbility();
        if (top == null || !top.isSpell() || top.getActivatingPlayer() != op || top.getHostCard() == null || top.getHostCard().getCMC() < 3) return null;
        if (openLands(op) >= 3) return null;
        for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(NML))) {
            if (!sa.canTarget(top)) continue;
            sa.resetTargets(); sa.getTargets().add(top);
            return sa;
        }
        return null;
    }

    /** P20a: Secret Identity (Conceal: hexproof) on our creature that the opponent's spell or ability on top targets */
    static SpellAbility protect(Player me, Player op) {
        SpellAbility top = me.getGame().getStack().peekAbility();
        if (top == null || top.getActivatingPlayer() != op) return null;
        Card mine = null;
        for (SpellAbility x = top; x != null; x = x.getSubAbility())
            if (x.getTargets() != null)
                for (Card t : x.getTargets().getTargetCards()) if (t.getController() == me && t.isCreature()) mine = t;
        if (mine == null || mine.hasKeyword(Keyword.HEXPROOF)) return null;
        for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.getName().equals(ProwessBrain.SECRET))) { concealFor = mine; return sa; }
        return null;
    }

    /** P19a: instead of prowessplan's veto-pass, the costliest castable creature (not the held Crab) or a sorcery */
    static SpellAbility vetoFall(Player me) {
        if (!ourTurn(me) || !me.getGame().getStack().isEmpty() || !(phase(me) == PhaseType.MAIN1 || phase(me) == PhaseType.MAIN2)) return null;
        List<SpellAbility> c = MetaBrain.castableHand(me, x -> x.isCreature() && !x.getName().equals("Eddymurk Crab")
                || x.isSorcery());
        c.removeIf(sa -> AiTweaks.targets(sa) || ProwessBrain.dumpsHand(me, sa));
        c.sort((x, y) -> Integer.compare((y.getHostCard().isCreature() ? 100 : 0) + MetaBrain.cost(y), (x.getHostCard().isCreature() ? 100 : 0) + MetaBrain.cost(x)));
        return c.isEmpty() ? null : c.get(0);
    }

    static boolean isCounter(Card c) {
        String t = c.getOracleText();
        return t != null && t.contains("Counter target");
    }

    /** the cheapest counterspell in our hand (0: none) */
    static int counterCost(Player me) {
        return me.getCardsIn(ZoneType.Hand).stream().filter(c -> !c.isLand() && isCounter(c)).mapToInt(Card::getCMC).min().orElse(0);
    }

    /** S14e: an Elemental that makes Sunderflock castable with the mana left */
    static SpellAbility elemFirst(Player me) {
        if (me.getCardsIn(ZoneType.Hand).stream().noneMatch(c -> c.getName().equals("Sunderflock"))) return null;
        if (!MetaBrain.castableHand(me, c -> c.getName().equals("Sunderflock")).isEmpty()) return null;
        int have = me.getCreaturesInPlay().stream().filter(c -> c.getType().hasCreatureType("Elemental")).mapToInt(Card::getCMC).max().orElse(0);
        for (SpellAbility sa : MetaBrain.castableHand(me, c -> c.isCreature() && c.getType().hasCreatureType("Elemental") && !c.getName().equals("Sunderflock"))) {
            int x = Math.max(have, sa.getHostCard().getCMC());
            if (openLands(me) - MetaBrain.cost(sa) >= Math.max(2, 9 - x)) return sa;
        }
        return null;
    }

    static boolean stunKind(SpellAbility sa) {
        Card h = sa == null ? null : sa.getHostCard();
        return h != null && (h.getName().equals("Floodpits Drowner") || h.getName().equals("The Wondrous Wasp")) && sa.usesTargeting();
    }

    /** D9b: a stun / ability-loss trigger aimed at the opponent's biggest creature (false: no legal target, Forge's way) */
    static boolean stunBig(Player me, SpellAbility sa) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return false;
        Card best = null;
        for (Card c : op.getCreaturesInPlay())
            if (sa.canTarget(c) && (best == null || c.getNetPower() > best.getNetPower()
                    || c.getNetPower() == best.getNetPower() && c.getNetToughness() > best.getNetToughness())) best = c;
        if (best == null) return false;
        sa.resetTargets(); sa.getTargets().add(best); ControlBrain.count("stunbig_target");
        return true;
    }

    /** S13b: keep blockers home when their crack-back would kill us */
    static void keepBlocker(Player me, Combat combat) {
        Player op = ProwessBrain.opponent(me);
        if (op == null || combat.getAttackers().isEmpty()) return;
        int ours = combat.getAttackers().stream().mapToInt(c -> Math.max(0, c.getNetCombatDamage())).sum();
        if (ours >= op.getLife()) return;
        List<Integer> pw = new ArrayList<>();
        for (Card c : op.getCreaturesInPlay()) pw.add(Math.max(0, c.getNetCombatDamage()));
        pw.sort(java.util.Collections.reverseOrder());
        List<Card> att = new ArrayList<>(combat.getAttackers());
        att.removeIf(c -> c.hasKeyword(Keyword.VIGILANCE));
        att.sort((x, y) -> Integer.compare(y.getNetToughness(), x.getNetToughness()));
        int home = (int) me.getCreaturesInPlay().stream().filter(c -> !combat.isAttacking(c) || c.hasKeyword(Keyword.VIGILANCE)).count();
        java.util.function.IntUnaryOperator through = hm -> { int t = 0; for (int i = hm; i < pw.size(); i++) t += pw.get(i); return t; };
        if (through.applyAsInt(home) < me.getLife()) return;
        int k = 0;
        while (k < att.size() && through.applyAsInt(home + k + 1) >= me.getLife()) k++;
        if (k >= att.size()) { ControlBrain.count("keepblocker_hopeless"); return; }
        for (int i = 0; i <= k; i++) { combat.removeFromCombat(att.get(i)); ControlBrain.count("keepblocker_home"); }
    }

    static final java.util.Set<String> KEEP = java.util.Set.of("Hearth Elemental", "Eddymurk Crab", "Sunderflock");

    /** S13c: surveil keeps the key creatures on top */
    static org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection> surveil(
            org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection> r) {
        if (r == null || r.right == null) return r;
        forge.game.card.CardCollection top = r.left == null ? new forge.game.card.CardCollection() : new forge.game.card.CardCollection(r.left);
        forge.game.card.CardCollection gy = new forge.game.card.CardCollection();
        for (Card c : r.right) if (KEEP.contains(c.getName())) { top.add(0, c); ControlBrain.count("keephearth_surveil"); } else gy.add(c);
        return org.apache.commons.lang3.tuple.ImmutablePair.of(top, gy);
    }

    /** S13c: a discard keeps the key creatures when another card can go */
    static forge.game.card.CardCollection discard(forge.game.card.CardCollection chosen, forge.game.card.CardCollection valid) {
        if (chosen == null) return null;
        forge.game.card.CardCollection out = new forge.game.card.CardCollection(chosen);
        for (Card c : chosen) {
            if (!KEEP.contains(c.getName())) continue;
            Card alt = null;
            for (Card v : valid) if (!out.contains(v) && !KEEP.contains(v.getName()) && (alt == null || v.isLand())) alt = v;
            if (alt == null) continue;
            out.remove(c); out.add(alt); ControlBrain.count("keephearth_discard");
        }
        return out;
    }

    static boolean landfallOpp(Player op) {
        for (Card c : op.getCardsIn(ZoneType.Battlefield))
            for (forge.game.trigger.Trigger t : c.getTriggers())
                if (t.getMode() == forge.game.trigger.TriggerType.ChangesZone && "Battlefield".equals(t.getParam("Destination"))
                        && t.getParam("ValidCard") != null && t.getParam("ValidCard").startsWith("Land")) return true;
        return false;
    }

    /* ---------------- combat ---------------- */

    /** D8b/G6/A9: chump blocks when the unblocked damage leaves us dead to their board next turn */
    static void chump(Player me, Combat combat) {
        Player op = ProwessBrain.opponent(me);
        if (op == null || combat == null) return;
        List<Card> open = new ArrayList<>();
        int dmg = 0;
        for (Card a : combat.getAttackers()) if (!combat.isBlocked(a)) { open.add(a); dmg += Math.max(0, a.getNetCombatDamage()); }
        int clock = boardPower(op);
        int after = me.getLife() - dmg;
        if (after > clock) return;
        open.sort((x, y) -> Integer.compare(y.getNetCombatDamage(), x.getNetCombatDamage()));
        List<Card> free = new ArrayList<>();
        for (Card b : me.getCreaturesInPlay()) if (b.isUntapped() && !combat.isBlocking(b) && CombatUtil.canBlock(b, combat)) free.add(b);
        free.sort((x, y) -> Integer.compare(value(x), value(y)));
        List<Card[]> plan = new ArrayList<>();
        int saved = 0;
        for (Card a : open) {
            if (after + saved > clock) break;
            for (Card b : free) {
                if (!CombatUtil.canBlock(a, b, combat)) continue;
                plan.add(new Card[]{a, b}); free.remove(b); saved += Math.max(0, a.getNetCombatDamage());
                break;
            }
        }
        // only blocks that change the outcome: out of reach of their board, or alive this turn when we would die now
        if (plan.isEmpty() || !(after + saved > clock || after <= 0 && after + saved > 0)) { ControlBrain.count("chumplethal_no_save"); return; }
        for (Card[] p : plan) { combat.addBlocker(p[0], p[1]); ControlBrain.count("chumplethal_block"); }
    }

    static int value(Card c) { return c.getCMC() * 2 + Math.max(0, c.getNetPower()) + (c.isToken() ? -3 : 0); }

    /** B5b/S8: attackers no untapped opposing creature could block and kill */
    static void freeAttack(Player me, Combat combat) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return;
        int theirs = boardPower(op);
        for (Card c : me.getCreaturesInPlay()) {
            if (combat.isAttacking(c) || !LandfallBrain.canAttackThisTurn(c) || !CombatUtil.canAttack(c, op)) continue;
            if (c.getNetPower() <= 0) continue;
            boolean safe = true;
            for (Card b : op.getCreaturesInPlay()) {
                if (!b.isUntapped() || !ProwessBrain.canBlock(c, c.getNetPower(), b)) continue;
                if (b.getNetPower() >= c.getNetToughness() - c.getDamage() || b.hasKeyword(Keyword.DEATHTOUCH)) { safe = false; break; }
            }
            int without = me.getCreaturesInPlay().stream().filter(x -> x != c && !combat.isAttacking(x) && x.isUntapped()).mapToInt(x -> 1).sum();
            if (!safe || theirs >= me.getLife() && without == 0) continue;
            combat.addAttacker(c, op);
            ControlBrain.count("freeattack_added");
        }
    }

    /** B5c: Smaug attacks when his Treasure trigger is lethal */
    static void smaug(Player me, Combat combat) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return;
        int treasures = (int) me.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getType().hasSubtype("Treasure")).count();
        if (treasures < op.getLife()) return;
        for (Card c : me.getCreaturesInPlay())
            if (c.getName().equals(SMAUG) && !combat.isAttacking(c) && LandfallBrain.canAttackThisTurn(c) && CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("smaugattack_added");
            }
    }

    /* ---------------- costs and numbers ---------------- */

    /** D8a: drop an optional blight unless a creature of ours survives the -1/-1 counter */
    static List<forge.game.spellability.OptionalCostValue> blight(Player me, List<forge.game.spellability.OptionalCostValue> chosen) {
        if (chosen == null || chosen.isEmpty()) return chosen;
        boolean survives = me.getCreaturesInPlay().stream().anyMatch(c -> c.getNetToughness() - c.getDamage() >= 2);
        List<forge.game.spellability.OptionalCostValue> out = new ArrayList<>();
        for (forge.game.spellability.OptionalCostValue v : chosen) {
            if (v.getCost() != null && v.getCost().toString().toLowerCase().contains("blight") && !survives) { ControlBrain.count("hexblight_declined"); continue; }
            out.add(v);
        }
        return out;
    }

    /** P16c: Wild Ride's harmonize tap: a power whose creature is neither the target nor able to attack (null: Forge's) */
    static Integer harmonize(Player me, SpellAbility sa, String title, List<Integer> options) {
        if (sa == null || sa.getHostCard() == null || !sa.getHostCard().getName().equals(WILDRIDE) || title == null || !title.contains("power of creature to tap"))
            return null;
        java.util.Set<Card> keep = new java.util.HashSet<>();
        if (sa.getTargets() != null) for (forge.game.GameObject o : sa.getTargets()) if (o instanceof Card) keep.add((Card) o);
        boolean main1 = ourTurn(me) && phase(me) == PhaseType.MAIN1;
        int best = -1;
        for (Integer x : options) {
            for (Card c : me.getCreaturesInPlay()) {
                if (!c.isUntapped() || c.getNetPower() != x || keep.contains(c) || main1 && ProwessBrain.canAttackNow(c)) continue;
                if (x > best) best = x;
            }
        }
        int low = options.stream().mapToInt(Integer::intValue).min().orElse(0);
        int r = best >= 0 ? best : low;
        ControlBrain.count(best >= 0 ? "prowessharm_spare" : "prowessharm_none");
        return r;
    }
}
