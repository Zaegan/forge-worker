import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/**
 * landorder@NAME (A6, user 2026-10-26: "yes, build the land-order rule. also test it on 4C"; scan ad-s11: Azorius
 * played an untapped land on a turn with no play, then a tapped land later, in 6 of 10 games; Spellementals S6 the
 * same habit): when Forge's land drop is a land that enters untapped and the hand holds a land that would enter tapped,
 * play the tapped one instead if the untapped land's mana would not be used before our next untap step.
 *
 * "Would be used" (any of these keeps Forge's land):
 *   - more mana could be spent with it: the most mana a set of our hand's spells and our permanents' mana-cost
 *     abilities (each once) can use, capped by our untapped sources, is larger with the extra source than without;
 *     an X spell (Traumatic Critique) counts as using any amount;
 *   - it brings a colour none of our untapped sources makes and a hand spell we could cast needs.
 * Enters tapped = its script's replacement says "enters tapped." with no condition, a fast land's "unless you control
 * two or fewer other lands" with 3+ other lands, a slow land's "unless you control two or more other lands" with 0-1, or
 * Starting Town after our third turn (approximated from the turn counter); shock lands, Verges and fetches are untapped
 * choices.
 */
public class LandOrder {
    static boolean on(Player p) { return AiTweaks.on("landorder", p); }

    /** null: not known (conditional or optional) */
    static Boolean entersTapped(Card c, Player me) {
        boolean any = false;
        for (ReplacementEffect re : c.getReplacementEffects()) {
            String d = re.getParam("Description");
            if (d == null || !d.contains("enters tapped")) continue;
            any = true;
            if (d.endsWith("enters tapped.") && !d.contains("may pay") && !d.contains("unless")) return true;
            long lands = me.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).count();
            if (d.contains("unless you control two or fewer other lands")) return lands > 2;   // fast lands
            if (d.contains("unless you control two or more other lands")) return lands < 2;    // slow lands
            if (d.contains("unless it's your first, second, or third turn")) {                   // Starting Town
                return (me.getGame().getPhaseHandler().getTurn() + 1) / 2 > 3;   // our turn number, roughly
            }
            return null;
        }
        return any ? null : false;
    }

    static String colours(Card c) {
        StringBuilder b = new StringBuilder();
        for (SpellAbility ma : c.getManaAbilities()) {
            try {
                String p = ma.getManaPart().isComboMana() ? ma.getManaPart().getComboColors(ma) : ma.getManaPart().getOrigProduced();
                if (p.contains("Any")) p = "WUBRG";
                for (char x : p.toCharArray()) if ("WUBRG".indexOf(x) >= 0 && b.indexOf(String.valueOf(x)) < 0) b.append(x);
            } catch (Exception e) { }
        }
        return b.toString();
    }

    static final forge.card.mana.ManaCostShard[] SHARDS = {forge.card.mana.ManaCostShard.WHITE, forge.card.mana.ManaCostShard.BLUE,
            forge.card.mana.ManaCostShard.BLACK, forge.card.mana.ManaCostShard.RED, forge.card.mana.ManaCostShard.GREEN};

    static int cost(SpellAbility sa) {
        if (sa.getPayCosts() == null || sa.getPayCosts().getTotalMana() == null) return 0;
        return sa.getPayCosts().getTotalMana().getCMC();
    }

    static boolean hasX(SpellAbility sa) {
        return sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null && sa.getPayCosts().getTotalMana().countX() > 0;
    }

    /** the most of CAP mana a subset of COSTS uses */
    static int maxSpend(List<Integer> costs, int cap) {
        boolean[] can = new boolean[cap + 1];
        can[0] = true;
        for (int k : costs) for (int m = cap; m >= k; m--) if (can[m - k]) can[m] = true;
        for (int m = cap; m >= 0; m--) if (can[m]) return m;
        return 0;
    }

    /** does the extra source PLAIN (entering untapped) get used before our next untap step? */
    static boolean extraUsed(Player me, Card plain) {
        int now = LandfallBrain.untappedSources(me);
        List<Integer> costs = new ArrayList<>();
        StringBuilder have = new StringBuilder();
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            if (c.isUntapped() && !c.getManaAbilities().isEmpty() && (c.isLand() || !c.isSick())) have.append(colours(c));
        String extra = colours(plain);
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) continue;
            for (SpellAbility sa : c.getSpells()) {
                if (hasX(sa)) return true;
                int k = cost(sa);
                if (k <= 0 || k > now + 1) continue;
                costs.add(k);
                if (sa.getPayCosts().getTotalMana() != null)
                    for (int i = 0; i < 5; i++) {
                        char x = "WUBRG".charAt(i);
                        if (sa.getPayCosts().getTotalMana().getShardCount(SHARDS[i]) > 0
                                && have.indexOf(String.valueOf(x)) < 0 && extra.indexOf(x) >= 0) return true;
                    }
                break;   // one spell per card
            }
        }
        for (Card c : me.getCardsIn(ZoneType.Battlefield))
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.isManaAbility() || sa.isSpell()) continue;
                int k = cost(sa);
                if (k > 0 && k <= now + 1) costs.add(k);
            }
        return maxSpend(costs, now + 1) > maxSpend(costs, now);
    }

    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty()) return ai;
        SpellAbility pick = ai.get(0);
        Card plain = pick.getHostCard();
        if (!pick.isLandAbility() || plain == null) return ai;
        Game g = me.getGame();
        if (!g.getPhaseHandler().isPlayerTurn(me)) return ai;
        if (!Boolean.FALSE.equals(entersTapped(plain, me)) && !plain.getName().matches("Steam Vents|Godless Shrine|Watery Grave|Hallowed Fountain|Sacred Foundry|Breeding Pool|Temple Garden|Stomping Ground|Blood Crypt|Overgrown Tomb|Multiversal Passage"))
        { ControlBrain.count("landorder_pick_not_untapped"); return ai; }   // Forge's land is not an untapped one
        Card tapped = null;
        for (Card c : me.getCardsIn(ZoneType.Hand))
            if (c != plain && c.isLand() && Boolean.TRUE.equals(entersTapped(c, me))) { tapped = c; break; }
        if (tapped == null) { ControlBrain.count("landorder_no_tapped_in_hand"); return ai; }
        try {
            if (extraUsed(me, plain)) { ControlBrain.count("landorder_kept"); return ai; }
        } catch (RuntimeException e) { ControlBrain.count("landorder_error_" + e.getClass().getSimpleName()); return ai; }
        for (SpellAbility sa : tapped.getAllPossibleAbilities(me, true))
            if (sa.isLandAbility() && sa.canPlay() && me.canPlayLand(tapped, false, sa)) {
                ControlBrain.count("landorder_tapped_first");
                return List.of(sa);
            }
        return ai;
    }
}
