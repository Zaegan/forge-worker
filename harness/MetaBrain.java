import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.List;

/**
 * Rules for the stock meta decks (pilot/TUNE_TARGETS.md, from scan ad-s10 of Azorius Control vs Dimir Midrange,
 * 2026-10-25). One switch per hypothesis, each scoped to its deck with @NAME:
 *   azoriushold@NAME   A1  Three Steps Ahead is held as a counter: cast without its counter mode only at the
 *                          opponent's end step (Forge cast it for the draw in its own main phase, tapping low, 7 times in
 *                          5 games, and the opponent resolved Jace, Kaito, Beavers into the gap)
 *   dimirbetrayal@NAME D1  no Strategic Betrayal while the opponent controls no creature (it then exiles only their
 *                          graveyard; every Betrayal in the scan was cast that way, 6 games)
 *   dimirfill@NAME D7      with D1: when Betrayal is held, cast the costliest other non-instant spell we can instead of
 *                          passing (scan dl-s10 g7: Dream Beavers left uncast twice; 2026-10-27)
 *   dimirlethal@NAME   D2  attack with everything when the all-out attack is lethal even if the opponent blocks as well
 *                          as it can (one game went 4 turns without an attack, the opponent at 2 and no blockers)
 *   From scan gl-s10 (Orzhov Lifegain vs Mono-Green Landfall):
 *   lifeshock@NAME      G1  Godless Shrine: pay 2 life on our turn when a spell in hand could be cast this turn and
 *                           life is 6+ (or 12+ any time); and play a Plains first when the Shrine would enter tapped
 *   lifeprecombat@NAME  G2  main 1, Forge would go to combat, the opponent has no untapped creature: cast our castable
 *                           creature first (ETB gains, drains and counters before damage)
 *   lifeorder@NAME      G3  a gain source (Hinterland Sanctifier, Case of the Uneaten Feast) before a creature spell
 *                           when there is mana for both
 *   lifewarp@NAME       G4  no Haliya warp outside main 1 or with nothing able to attack: a castable 1-2 mana
 *                           permanent instead, else nothing
 *   From scan al-s10 (Lifegain as Azorius Control's opponent, 2026-10-28; G7 in pilot/TUNE_TARGETS.md):
 *   lifeorder2@NAME     G7a G3 with Liliana the Faultless and a hard-cast Haliya as gain sources too, also applied to
 *                           G2's precombat creature (5 games cast the source after the creature it triggers on)
 *   lifeshock2@NAME     G7b Godless Shrine untapped only when the extra mana casts more of the hand this turn (by mana
 *                           value, or the only source of a colour a castable card needs), at 6+ life; G1 paid at 12+ for
 *                           nothing (5 games)
 *   lifeempty@NAME      G7c no Emptiness evoke when neither mode has a target (no creature on the opponent's side, no
 *                           creature of mana value 3 or less in our graveyard): the costliest other castable permanent
 *   lifelegend@NAME     G7d no legendary creature spell while we control one of the same name: the costliest other
 *                           castable permanent instead (3 duplicate Amalia/Haliya casts)
 *   lifeconvo@NAME      G5  no Lunar Convocation draw (2 life) when it leaves us within reach of the opponent's board:
 *                           life after paying at most the total power of their creatures, or 4 or less (scan gl-s10)
 *   From scan bz-s10 (Boros Dragons vs Izzet Spellementals):
 *   dragonmomo@NAME     B1  a flying non-Lemur creature spell while Momo, Friendly Flier is in hand and the discount is
 *                           unused this turn: cast Momo first when the mana covers both (Momo's {W} + the spell less 1)
 *   dragonpassage@NAME  B2  Multiversal Passage names the basic type of the colour our hand needs most for its sources
 *                           (Forge named Island in a deck with no blue cards)
 *   dragontutor@NAME    B3  no Maelstrom of the Spirit Dragon tutor (sacrificing it) while a creature in hand is
 *                           castable: cast that creature instead
 *   dragonnova@NAME     B4  main 1, Forge would pass, the opponent has no untapped creature and no untapped land: warp
 *                           Nova Hellkite for its hasty damage
 *   For the Worldwake Vampires line (user 2026-10-27: "yeah, try sack lines"):
 *   ghastsac@NAME       V1  a creature we must sacrifice (Eldrazi Monument's upkeep, an opponent's edict) is Bloodghast
 *                           when we control one: it returns at our next land drop, so Monument costs us nothing
 * Only public information is used: the board, life totals and our own hand.
 */
public class MetaBrain {
    static final String THREE_STEPS = "Three Steps Ahead", BETRAYAL = "Strategic Betrayal";

    static boolean a1(Player p) { return AiTweaks.on("azoriushold", p); }
    static boolean d1(Player p) { return AiTweaks.on("dimirbetrayal", p); }
    static boolean dfill(Player p) { return AiTweaks.on("dimirfill", p); }
    static boolean d2(Player p) { return AiTweaks.on("dimirlethal", p); }
    static boolean g1(Player p) { return AiTweaks.on("lifeshock", p); }
    static boolean g2(Player p) { return AiTweaks.on("lifeprecombat", p); }
    static boolean g3(Player p) { return AiTweaks.on("lifeorder", p); }
    static boolean g4(Player p) { return AiTweaks.on("lifewarp", p); }
    static boolean g7a(Player p) { return AiTweaks.on("lifeorder2", p); }
    static boolean g7b(Player p) { return AiTweaks.on("lifeshock2", p); }
    static boolean g7c(Player p) { return AiTweaks.on("lifeempty", p); }
    static boolean g7d(Player p) { return AiTweaks.on("lifelegend", p); }
    static boolean g7(Player p) { return g7a(p) || g7b(p) || g7c(p) || g7d(p); }
    static boolean g5(Player p) { return AiTweaks.on("lifeconvo", p); }
    static boolean b1(Player p) { return AiTweaks.on("dragonmomo", p); }
    static boolean b2(Player p) { return AiTweaks.on("dragonpassage", p); }
    static boolean b3(Player p) { return AiTweaks.on("dragontutor", p); }
    static boolean b4(Player p) { return AiTweaks.on("dragonnova", p); }
    static boolean v1(Player p) { return AiTweaks.on("ghastsac", p) || FetchBrain.MON(p); }
    static boolean any(Player p) { return a1(p) || d1(p) || d2(p) || g1(p) || g2(p) || g3(p) || g4(p) || b1(p) || b3(p) || b4(p) || g7(p) || g5(p); }
    static final String MOMO = "Momo, Friendly Flier", PASSAGE = "Multiversal Passage", MAELSTROM = "Maelstrom of the Spirit Dragon",
            NOVA = "Nova Hellkite";
    static final String SHRINE = "Godless Shrine", HALIYA = "Haliya, Guided by Light";
    static final java.util.Set<String> GAIN_SOURCES = java.util.Set.of("Hinterland Sanctifier", "Case of the Uneaten Feast");
    static final java.util.Set<String> GAIN_SOURCES2 = java.util.Set.of("Hinterland Sanctifier", "Case of the Uneaten Feast",
            "Liliana the Faultless", HALIYA);
    static final String EMPTINESS = "Emptiness";

    static int untapped(Player me) {
        return (int) me.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream().filter(c -> c.isLand() && c.isUntapped()).count();
    }

    /** a nonland spell in hand that could be cast with one more untapped land */
    static boolean spellFor(Player me, int mana) {
        for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Hand)) if (!c.isLand() && c.getCMC() >= 1 && c.getCMC() <= mana) return true;
        return false;
    }

    /** G1: pay the Shrine's 2 life? null: not ours to decide. S9 (spellshock@NAME, 2026-10-26, scan sl-s10: Spellementals
     *  declined Steam Vents with no other red source, then sat on castable red cards): the same rule for Steam Vents */
    static Boolean payShrine(forge.game.cost.Cost cost, SpellAbility sa, Player me) {
        if (sa == null || sa.getHostCard() == null || !cost.toSimpleString().contains("2 life")) return null;
        String n = sa.getHostCard().getName();
        if (!(g1(me) && n.equals(SHRINE) || AiTweaks.on("spellshock", me) && n.equals("Steam Vents"))) return null;
        boolean ours = me.getGame().getPhaseHandler().isPlayerTurn(me);
        if (g7b(me) && n.equals(SHRINE)) {
            boolean pay = ours && me.getLife() >= 6 && shrineGains(me);
            ControlBrain.count("lifeshock2" + (pay ? "_paid" : "_tapped"));
            return pay;
        }
        boolean pay = me.getLife() >= 12 || ours && me.getLife() >= 6 && spellFor(me, untapped(me) + 1);
        ControlBrain.count((n.equals(SHRINE) ? "lifeshock" : "spellshock") + (pay ? "_paid" : "_tapped"));
        return pay;
    }

    /** G7b: one more untapped land casts more of the hand this turn: a larger total mana value fits, or a castable card
     *  needs a colour (W or B) no untapped land of ours makes */
    static boolean shrineGains(Player me) {
        int m = untapped(me);
        List<Integer> cmc = new java.util.ArrayList<>();
        boolean colour = false;
        forge.card.mana.ManaCostShard[] sh = {forge.card.mana.ManaCostShard.WHITE, forge.card.mana.ManaCostShard.BLACK};
        String[] col = {"W", "B"};
        for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Hand)) {
            if (c.isLand() || c.getCMC() < 1 || dupLegend(me, c)) continue;
            cmc.add(c.getCMC());
            if (c.getCMC() > m + 1) continue;
            for (int i = 0; i < sh.length; i++) {
                final String k = col[i];
                if (c.getManaCost().getShardCount(sh[i]) > 0 && me.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                        .noneMatch(l -> l.isLand() && l.isUntapped() && l.getManaAbilities().stream().anyMatch(a -> a.canProduce(k)))) colour = true;
            }
        }
        return colour || fits(cmc, m + 1) > fits(cmc, m);
    }

    /** the largest total of a subset of XS that is at most CAP */
    static int fits(List<Integer> xs, int cap) {
        boolean[] can = new boolean[Math.max(cap, 0) + 1];
        can[0] = true;
        for (int x : xs) for (int t = cap; t >= x; t--) if (can[t - x]) can[t] = true;
        for (int t = cap; t > 0; t--) if (can[t]) return t;
        return 0;
    }

    /** G7d: a legendary creature card whose name we already control */
    static boolean dupLegend(Player me, Card c) {
        return c.isCreature() && c.getType().isLegendary()
                && me.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream().anyMatch(b -> b != c && b.getName().equals(c.getName()));
    }

    /** G7c: an Emptiness evoke with a target for neither mode */
    static boolean idleEvoke(Player me, SpellAbility sa) {
        Card h = sa.getHostCard();
        if (h == null || !h.getName().equals(EMPTINESS) || !sa.isEvoke()) return false;
        Player op = ProwessBrain.opponent(me);
        boolean bTarget = op != null && !op.getCreaturesInPlay().isEmpty();
        boolean wTarget = me.getCardsIn(forge.game.zone.ZoneType.Graveyard).stream().anyMatch(c -> c.isCreature() && c.getCMC() <= 3);
        return !bTarget && !wTarget;
    }

    /** a G7 veto of SA (c: Emptiness evoke with no target; d: a duplicate legend) */
    static String g7veto(Player me, SpellAbility sa) {
        Card h = sa.getHostCard();
        if (h == null || !sa.isSpell()) return null;
        if (g7c(me) && idleEvoke(me, sa)) return "lifeempty";
        if (g7d(me) && dupLegend(me, h)) return "lifelegend";
        return null;
    }

    /** G7c/G7d: the costliest castable permanent spell of the hand that no G7 rule vetoes (null: pass) */
    static List<SpellAbility> g7alt(Player me, String why) {
        List<SpellAbility> alt = castableHand(me, c -> !c.isLand() && !c.isInstant() && !c.isSorcery());
        alt.removeIf(sa -> g7veto(me, sa) != null);
        alt.sort((x, y) -> Integer.compare(cost(y), cost(x)));
        ControlBrain.count(why + (alt.isEmpty() ? "_pass" : "_alt"));
        return alt.isEmpty() ? null : List.of(alt.get(0));
    }

    /** castable spells of our hand cards matching P */
    static List<SpellAbility> castableHand(Player me, java.util.function.Predicate<Card> p) {
        forge.game.card.CardCollection cs = new forge.game.card.CardCollection();
        for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Hand)) if (p.test(c)) cs.add(c);
        List<SpellAbility> out = new java.util.ArrayList<>();
        for (SpellAbility sa : forge.ai.ComputerUtilAbility.getSpellAbilities(cs, me)) {
            if (!sa.isSpell() || LandfallBrain.isWarp(sa)) continue;
            sa.setActivatingPlayer(me);
            try {
                if (sa.canPlay() && forge.ai.ComputerUtilCost.canPayCost(sa, me, false)) out.add(sa);
            } catch (Exception e) { }
        }
        return out;
    }

    /** the modes chosen for a modal spell (spree/charm), plus its sub-abilities */
    static boolean hasMode(SpellAbility sa, ApiType api) {
        if (sa.getChosenList() != null)
            for (SpellAbility m : sa.getChosenList()) if (m.getApi() == api) return true;
        for (SpellAbility s = sa.getSubAbility(); s != null; s = s.getSubAbility()) if (s.getApi() == api) return true;
        return false;
    }

    /** the mana value Forge's play SA will cost */
    static int cost(SpellAbility sa) {
        return sa.getPayCosts() == null || sa.getPayCosts().getTotalMana() == null ? sa.getHostCard().getCMC() : sa.getPayCosts().getTotalMana().getCMC();
    }

    /** a flying non-Lemur creature spell, the kind Momo discounts */
    static boolean momoKind(Card c) { return c.isCreature() && c.hasKeyword(forge.game.keyword.Keyword.FLYING) && !c.getType().hasCreatureType("Lemur"); }

    /** B2: the basic type for Multiversal Passage (null: Forge's choice) */
    static String passageType(Player me, java.util.Collection<String> valid) {
        String[][] cols = {{"W", "Plains"}, {"U", "Island"}, {"B", "Swamp"}, {"R", "Mountain"}, {"G", "Forest"}};
        forge.card.mana.ManaCostShard[] sh = {forge.card.mana.ManaCostShard.WHITE, forge.card.mana.ManaCostShard.BLUE,
                forge.card.mana.ManaCostShard.BLACK, forge.card.mana.ManaCostShard.RED, forge.card.mana.ManaCostShard.GREEN};
        String best = null; double bs = 0;
        for (int i = 0; i < cols.length; i++) {
            if (!valid.contains(cols[i][1])) continue;
            int need = 0, seen = 0, src = 0; final String col = cols[i][0];
            for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Hand)) if (!c.isLand()) need += c.getManaCost().getShardCount(sh[i]);
            for (Card c : me.getCardsIn(java.util.List.of(forge.game.zone.ZoneType.Battlefield, forge.game.zone.ZoneType.Graveyard, forge.game.zone.ZoneType.Exile)))
                if (!c.isLand()) seen += c.getManaCost().getShardCount(sh[i]);
            for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Battlefield))
                if (c.isLand() && !c.getName().equals(PASSAGE) && c.getManaAbilities().stream().anyMatch(m -> m.canProduce(col))) src++;
            if (need + seen == 0) continue;   // a colour we have shown no use for
            double score = (need + 0.5 * seen + 0.01) / (src + 1);
            if (score > bs) { bs = score; best = cols[i][1]; }
        }
        return best;
    }

    /** vetoes on Forge's chosen play (null: pass instead) */
    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        Game g = me.getGame();
        Player op = ProwessBrain.opponent(me);
        boolean main1 = g.getPhaseHandler().isPlayerTurn(me) && g.getPhaseHandler().getPhase() == PhaseType.MAIN1 && g.getStack().isEmpty();
        if (g2(me) && main1 && (ai == null || ai.isEmpty()) && op != null && op.getCreaturesInPlay().stream().noneMatch(Card::isUntapped)) {
            List<SpellAbility> cr = castableHand(me, c -> c.isCreature());
            if (g7(me)) cr.removeIf(sa -> g7veto(me, sa) != null);
            cr.sort((x, y) -> Integer.compare(y.getHostCard().getCMC(), x.getHostCard().getCMC()));
            if (!cr.isEmpty()) {
                ControlBrain.count("lifeprecombat_cast");
                if (!g7a(me)) return List.of(cr.get(0));
                ai = List.of(cr.get(0));   // G7a: the precombat creature goes through the source-first check below
            }
        }
        if (b4(me) && main1 && (ai == null || ai.isEmpty()) && op != null && op.getCreaturesInPlay().stream().noneMatch(Card::isUntapped)
                && op.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream().noneMatch(c -> c.isLand() && c.isUntapped())) {
            for (SpellAbility sa : LandfallBrain.castable(me, forge.game.zone.ZoneType.Hand, NOVA))
                if (LandfallBrain.isWarp(sa)) { ControlBrain.count("dragonnova_warp"); return List.of(sa); }
        }
        if (ai == null || ai.isEmpty()) return ai;
        SpellAbility pick = ai.get(0);
        Card ph = pick.getHostCard();
        boolean ours = g.getPhaseHandler().isPlayerTurn(me) && g.getStack().isEmpty();
        if (b1(me) && ours && pick.isSpell() && ph != null && momoKind(ph) && !ph.getName().equals(MOMO)
                && me.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream().noneMatch(c -> c.getName().equals(MOMO))
                && g.getStack().getSpellCardsCastThisTurn().stream().noneMatch(c -> c.getController() == me && momoKind(c))) {
            List<SpellAbility> momo = castableHand(me, c -> c.getName().equals(MOMO));
            if (!momo.isEmpty() && untapped(me) >= cost(pick)) { ControlBrain.count("dragonmomo_first"); return List.of(momo.get(0)); }
        }
        if (b3(me) && ours && !pick.isSpell() && ph != null && ph.getName().equals(MAELSTROM) && pick.getApi() == ApiType.ChangeZone) {
            List<SpellAbility> cr = castableHand(me, c -> c.isCreature());
            cr.sort((x, y) -> Integer.compare(y.getHostCard().getCMC(), x.getHostCard().getCMC()));
            if (!cr.isEmpty()) { ControlBrain.count("dragontutor_deploy"); return List.of(cr.get(0)); }
        }
        if (g5(me) && !pick.isSpell() && ph != null && ph.getName().equals("Lunar Convocation") && pick.getApi() == ApiType.Draw && op != null) {
            int power = op.getCreaturesInPlay().stream().mapToInt(c -> Math.max(0, c.getNetPower())).sum();
            if (me.getLife() - 2 <= Math.max(power, 4)) { ControlBrain.count("lifeconvo_held"); return null; }
            ControlBrain.count("lifeconvo_drawn");
        }
        if (g7(me) && ours && pick.isSpell()) {
            String why = g7veto(me, pick);
            if (why != null) { ControlBrain.count(why + "_vetoed"); return g7alt(me, why); }
        }
        if ((g1(me) || g7b(me)) && pick.isLandAbility() && ph != null && ph.getName().equals(SHRINE)
                && !(me.getLife() >= 6 && (g7b(me) ? shrineGains(me) : spellFor(me, untapped(me) + 1)))) {
            for (Card c : me.getCardsIn(forge.game.zone.ZoneType.Hand))   // the Shrine would enter tapped: a Plains first
                if (c.getName().equals("Plains"))
                    for (SpellAbility sa : c.getAllPossibleAbilities(me, true))
                        if (sa.isLandAbility() && sa.canPlay() && me.canPlayLand(c, false, sa)) { ControlBrain.count("lifeshock_plains_first"); return List.of(sa); }
        }
        if (g4(me) && pick.isSpell() && ph != null && ph.getName().equals(HALIYA) && LandfallBrain.isWarp(pick)) {
            boolean attack = main1 && me.getCreaturesInPlay().stream().anyMatch(LandfallBrain::canAttackThisTurn);
            if (!attack) {
                List<SpellAbility> cheap = castableHand(me, c -> !c.isLand() && !c.isInstant() && !c.isSorcery() && c.getCMC() <= 2 && !c.getName().equals(HALIYA));
                ControlBrain.count("lifewarp_vetoed");
                return cheap.isEmpty() ? null : List.of(cheap.get(0));
            }
        }
        java.util.Set<String> sources = g7a(me) ? GAIN_SOURCES2 : GAIN_SOURCES;
        if ((g3(me) || g7a(me)) && pick.isSpell() && ph != null && ph.isCreature() && !sources.contains(ph.getName())) {
            List<SpellAbility> src = castableHand(me, c -> sources.contains(c.getName()) && !dupLegend(me, c));
            int cost = pick.getPayCosts() == null || pick.getPayCosts().getTotalMana() == null ? ph.getCMC() : pick.getPayCosts().getTotalMana().getCMC();
            for (SpellAbility sa : src)
                if (untapped(me) >= cost + sa.getHostCard().getCMC()) { ControlBrain.count(g7a(me) ? "lifeorder2_source_first" : "lifeorder_source_first"); return List.of(sa); }
        }
        for (SpellAbility sa : ai) {
            Card h = sa.getHostCard();
            if (h == null || h.getController() != me || !sa.isSpell()) continue;
            if (a1(me) && h.getName().equals(THREE_STEPS) && !hasMode(sa, ApiType.Counter)) {
                boolean oppEnd = !g.getPhaseHandler().isPlayerTurn(me) && g.getPhaseHandler().getPhase() == PhaseType.END_OF_TURN;
                if (!oppEnd) { ControlBrain.count("azoriushold_held"); return null; }
            }
            if (d1(me) && h.getName().equals(BETRAYAL) && op != null && op.getCreaturesInPlay().isEmpty()) {
                ControlBrain.count("dimirbetrayal_held");
                if (dfill(me)) {   // D7: the held Betrayal's mana goes to the costliest other spell we can cast now
                    List<SpellAbility> alt = castableHand(me, c -> !c.isLand() && !c.isInstant() && !c.getName().equals(BETRAYAL));
                    alt.sort((x, y) -> y.getHostCard().getCMC() - x.getHostCard().getCMC());
                    if (!alt.isEmpty()) { ControlBrain.count("dimirfill_cast"); return List.of(alt.get(0)); }
                }
                return null;
            }
        }
        return ai;
    }

    /** V1: Forge's sacrifice choice R from VALID, with Bloodghast in place of another creature of ours */
    static forge.game.card.CardCollectionView ghastSac(Player me, forge.game.card.CardCollectionView valid, forge.game.card.CardCollectionView r) {
        if (r == null || r.isEmpty()) return r;
        List<Card> ghasts = new java.util.ArrayList<>();
        for (Card c : valid) if (c.getController() == me && c.isCreature() && c.getName().equals("Bloodghast") && !r.contains(c)) ghasts.add(c);
        boolean ours = false;
        for (Card c : r) if (c.getController() == me && c.isCreature()) ours = true;
        if (!ours) return r;
        if (r.stream().anyMatch(c -> c.getName().equals("Bloodghast"))) { ControlBrain.count("ghastsac_forge_chose_bloodghast"); if (ghasts.isEmpty()) return r; }
        if (ghasts.isEmpty()) { ControlBrain.count("ghastsac_no_bloodghast"); return r; }
        forge.game.card.CardCollection out = new forge.game.card.CardCollection(r);
        for (Card c : r)
            if (!ghasts.isEmpty() && c.getController() == me && c.isCreature() && !c.getName().equals("Bloodghast")) {
                out.remove(c); out.add(ghasts.remove(0));
                ControlBrain.count("ghastsac_swapped " + c.getName());
            }
        return out;
    }

    /** D2: after Forge declares attackers, add every creature that can attack if the all-out attack is lethal */
    static void forceAttack(Player me, Combat combat) {
        if (!d2(me)) return;
        Player op = ProwessBrain.opponent(me);
        if (op == null || SpellBrain.unblocked(me, op, java.util.Collections.emptyList()) < op.getLife()) return;
        for (Card c : me.getCreaturesInPlay())
            if (!combat.isAttacking(c) && LandfallBrain.canAttackThisTurn(c) && CombatUtil.canAttack(c, op)) {
                combat.addAttacker(c, op);
                ControlBrain.count("dimirlethal_attacker");
            }
    }
}
