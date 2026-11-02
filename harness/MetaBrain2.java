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

    static boolean ourTurn(Player me) { return me.getGame().getPhaseHandler().isPlayerTurn(me); }
    static PhaseType phase(Player me) { return me.getGame().getPhaseHandler().getPhase(); }

    static boolean any(Player p) {
        for (String r : new String[]{"manlandsafe", "undervoice", "kaitoafter", "mapfirst", "azvoice", "starcagesafe", "azerode",
                "azstabilise", "azreserve2", "azcopy", "azdemo", "dragontutor2", "prowesssorc", "dragonprecombat"})
            if (on(r, p)) return true;
        return false;
    }

    /* ---------------- choose: plays of our own, before Forge's AI ---------------- */

    static SpellAbility choose(Player me) {
        Game g = me.getGame();
        if (!g.getStack().isEmpty()) return null;
        Player op = ProwessBrain.opponent(me);
        if (op == null) return null;
        if (on("azdemo", me)) { SpellAbility d = demo(me, op); if (d != null) return d; }
        if (!ourTurn(me)) return null;
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
        if (pass) {
            if (on("undervoice", me) && ours && ph == PhaseType.MAIN2 && g.getStack().isEmpty() && voiceOut(op)) {
                List<SpellAbility> inst = MetaBrain.castableHand(me, c -> (c.isInstant() || c.hasKeyword(Keyword.FLASH)) && !c.isLand());
                inst.removeIf(sa -> sa.getApi() == ApiType.Counter || MetaBrain.hasMode(sa, ApiType.Counter) && !MetaBrain.hasMode(sa, ApiType.Draw));
                inst.sort((x, y) -> Integer.compare(MetaBrain.cost(y), MetaBrain.cost(x)));
                if (!inst.isEmpty()) { ControlBrain.count("undervoice_cast"); return List.of(inst.get(0)); }
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
