import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.ai.ComputerUtilCost;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * fetchplay@NAME (2026-10-25, user: "instrument proper play to test whether the fetches offer a measurable benefit when
 * used sensibly"): Zendikar fetchlands held uncracked until a crack is worth something. Forge's AI cracks a fetch in
 * main 1 whatever the board; with this rule a held fetch is hidden from Forge's choice (marked tapped for that call
 * only) and cracked by us, in this order:
 *   bloodghast — Bloodghast is in our graveyard, in our main 1 or at the opponent's end step (it returns on landfall);
 *   landfall_attack — our main 1, a landfall creature (Plated Geopede, Steppe Lynx) can attack: +2/+2 before combat;
 *   mana — our main phase, a nonland spell in hand costs exactly one more than our untapped mana lands;
 *   blaze — the opponent's declare-attackers step or end step, Searing Blaze castable, no landfall for us yet this turn,
 *     and the opponent has a creature Blaze's 3 damage kills: crack, then cast Blaze (Forge's targets) if Forge's AI
 *     would play it;
 *   eot — the opponent's end step: crack anyway, so the land is there for our turn.
 *
 * monline@NAME (user 2026-10-27: "maybe if we had the rule working properly, with the correct number of fetches, we could
 * increase the number of monuments"): the Eldrazi Monument + Bloodghast line. With it fetchplay is on too, and:
 *   - no eot crack while Monument and our Bloodghast are both on the battlefield: Monument sacrifices Bloodghast at our
 *     upkeep (ghastsac makes sure of that) and the held fetch returns it in main 1 (the bloodghast crack);
 *   - with no held fetch, our last land in hand is not played on such a turn unless its mana would be used this turn
 *     (LandOrder.extraUsed): it is next turn's land drop that returns Bloodghast. A spell Forge would cast after the
 *     land is cast first (the costliest castable one); with none, we pass.
 */
public class FetchBrain {
    static final Set<String> FETCH = Set.of("Marsh Flats", "Verdant Catacombs", "Scalding Tarn", "Arid Mesa", "Misty Rainforest");
    static final Set<String> LANDFALL_ATTACKERS = Set.of("Plated Geopede", "Steppe Lynx");
    static final String BLAZE = "Searing Blaze", GHAST = "Bloodghast";

    static boolean ON(Player p) { return AiTweaks.on("fetchplay", p) || MON(p); }
    static boolean MON(Player p) { return AiTweaks.on("monline", p); }

    /** Monument and our Bloodghast are both on the battlefield: Bloodghast goes at our next upkeep */
    static boolean ghastDue(Player me) {
        boolean mon = false, ghast = false;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Eldrazi Monument")) mon = true;
            if (c.getName().equals(GHAST) && c.isCreature()) ghast = true;
        }
        return mon && ghast;
    }

    /** monline: hold our last land in hand (see the class comment); the replacement for Forge's land play, or AI */
    static List<SpellAbility> holdLand(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty() || !MON(me)) return ai;
        SpellAbility pick = ai.get(0);
        Card land = pick.getHostCard();
        if (!pick.isLandAbility() || land == null || !me.getGame().getPhaseHandler().isPlayerTurn(me) || !ghastDue(me)) return ai;
        if (!held(me).isEmpty()) return ai;
        long lands = me.getCardsIn(ZoneType.Hand).stream().filter(Card::isLand).count();
        if (lands != 1) return ai;
        try {
            if (LandOrder.extraUsed(me, land)) { ControlBrain.count("monline_land_needed"); return ai; }
        } catch (RuntimeException e) { ControlBrain.count("monline_error_" + e.getClass().getSimpleName()); return ai; }
        ControlBrain.count("monline_land_held");
        List<SpellAbility> sp = MetaBrain.castableHand(me, c -> !c.isLand());
        sp.sort((x, y) -> Integer.compare(y.getHostCard().getCMC(), x.getHostCard().getCMC()));
        return sp.isEmpty() ? null : List.of(sp.get(0));
    }

    static List<Card> held(Player me) {
        List<Card> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (FETCH.contains(c.getName()) && c.isUntapped()) out.add(c);
        return out;
    }

    static SpellAbility crack(Player me, Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.getApi() != ApiType.ChangeZone) continue;
            sa.setActivatingPlayer(me);
            try {
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) return sa;
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    static int manaLands(Player me) {
        int n = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.isLand() && c.isUntapped() && !c.getManaAbilities().isEmpty()) n++;
        return n;
    }

    /** a land of ours entered this turn (one still on the battlefield; a fetch cracked this turn leaves its land) */
    static boolean landfallThisTurn(Player me) {
        int t = me.getGame().getPhaseHandler().getTurn();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.isLand() && c.getTurnInZone() == t) return true;
        return me.getLandsPlayedThisTurn() > 0;
    }

    static boolean blazeKills(Player me) {
        Player op = ProwessBrain.opponent(me);
        if (op == null) return false;
        for (Card c : op.getCreaturesInPlay()) if (c.getNetToughness() - c.getDamage() <= 3) return true;
        return false;
    }

    static Game blazeGame; static int blazeTurn = -1;

    static String reason(Player me) {
        Game g = me.getGame();
        if (!g.getStack().isEmpty()) return null;
        PhaseType ph = g.getPhaseHandler().getPhase();
        boolean ours = g.getPhaseHandler().isPlayerTurn(me);
        boolean ghast = false;
        for (Card c : me.getCardsIn(ZoneType.Graveyard)) if (c.getName().equals(GHAST)) ghast = true;
        if (ghast && ((ours && ph == PhaseType.MAIN1) || (!ours && ph == PhaseType.END_OF_TURN))) return "bloodghast";
        if (ours && ph == PhaseType.MAIN1)
            for (Card c : me.getCreaturesInPlay())
                if (LANDFALL_ATTACKERS.contains(c.getName()) && LandfallBrain.canAttackThisTurn(c)) return "landfall_attack";
        if (ours && (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2)) {
            int u = manaLands(me);
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (!c.isLand() && c.getCMC() == u + 1) return "mana";
        }
        if (!ours && (ph == PhaseType.COMBAT_DECLARE_ATTACKERS || ph == PhaseType.END_OF_TURN) && !landfallThisTurn(me)
                && blazeKills(me) && manaLands(me) >= 2)
            for (Card c : me.getCardsIn(ZoneType.Hand)) if (c.getName().equals(BLAZE)) return "blaze";
        if (!ours && ph == PhaseType.END_OF_TURN && MON(me) && ghastDue(me)) { ControlBrain.count("monline_fetch_held"); return null; }
        if (!ours && ph == PhaseType.END_OF_TURN) return "eot";
        return null;
    }

    /** our choice before Forge's: a Searing Blaze after a blaze crack, or a crack */
    static SpellAbility choose(Player me) {
        Game g = me.getGame();
        if (blazeGame == g && blazeTurn == g.getPhaseHandler().getTurn() && g.getStack().isEmpty()) {
            for (SpellAbility sa : LandfallBrain.castable(me, ZoneType.Hand, BLAZE)) {
                try {
                    forge.ai.AiController ai = ((forge.ai.PlayerControllerAi) me.getController()).getAi();
                    if (ai.canPlaySa(sa) == forge.ai.AiPlayDecision.WillPlay) { blazeTurn = -1; ControlBrain.count("fetchplay_blaze_cast"); return sa; }
                } catch (Exception e) { continue; }
            }
        }
        String r = reason(me);
        if (r == null) return null;
        for (Card c : held(me)) {
            SpellAbility sa = crack(me, c);
            if (sa == null) continue;
            if (r.equals("blaze")) { blazeGame = g; blazeTurn = g.getPhaseHandler().getTurn(); }
            ControlBrain.count("fetchplay_crack_" + r);
            return sa;
        }
        return null;
    }

    /** the fetches to hide from Forge's own choice (marked tapped for that call; the caller untaps them) */
    static List<Card> hide(Player me) {
        List<Card> h = held(me);
        for (Card c : h) c.setTapped(true);
        if (!h.isEmpty()) ControlBrain.count("fetchplay_hidden");
        return h;
    }
}
