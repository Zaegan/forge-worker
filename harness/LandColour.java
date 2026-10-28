import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;

/**
 * landcolour@NAME (A11, scan al-s10, 2026-10-28: Azorius played Demolition Field or a tapped land next to Floodfarm
 * Verge 4 times in 3 of 10 games when an Island or Plains would have turned on blue for the No More Lies or Consult in
 * hand; each time a 2-drop resolved into the dead counter): on our turn, when Forge's land drop leaves fewer of the
 * hand's instants and flash spells castable with our untapped lands than another land in hand would, play that land
 * instead (the one that enables the most; Forge's own order breaks ties).
 *
 * Castable = enough untapped lands for the mana value, and each coloured pip matched to a different land that makes
 * that colour (hybrid pips count as generic). A land's colours honour "Activate only if you control a Plains or an
 * Island" (the Verges) on the battlefield after the drop. A land that enters tapped, or whose entry is unknown
 * (Multiversal Passage), adds nothing; shock lands count as untapped.
 */
public class LandColour {
    static boolean on(Player p) { return AiTweaks.on("landcolour", p); }

    static final String SHOCKS = "Steam Vents|Godless Shrine|Watery Grave|Hallowed Fountain|Sacred Foundry|Breeding Pool|Temple Garden|Stomping Ground|Blood Crypt|Overgrown Tomb";

    static boolean untappedEntry(Card c, Player me) {
        return Boolean.FALSE.equals(LandOrder.entersTapped(c, me)) || c.getName().matches(SHOCKS);
    }

    /** the colours land C makes when LANDS (ours, after the drop) are on the battlefield */
    static String colours(Card c, List<Card> lands) {
        StringBuilder b = new StringBuilder();
        for (SpellAbility ma : c.getManaAbilities()) {
            String need = ma.getParam("IsPresent");
            if (need != null && need.contains(".YouCtrl")) {
                boolean ok = false;
                for (String t : need.split(",")) {
                    String type = t.substring(0, t.indexOf('.'));
                    for (Card l : lands) if (l.getType().hasSubtype(type)) { ok = true; break; }
                    if (ok) break;
                }
                if (!ok) continue;
            }
            try {
                String p = ma.getManaPart().isComboMana() ? ma.getManaPart().getComboColors(ma) : ma.getManaPart().getOrigProduced();
                if (p.contains("Any")) p = "WUBRG";
                for (char x : p.toCharArray()) if ("WUBRG".indexOf(x) >= 0 && b.indexOf(String.valueOf(x)) < 0) b.append(x);
            } catch (Exception e) { }
        }
        return b.toString();
    }

    /** can PIPS (one colour letter per coloured pip) be matched to distinct sources from index I on, given USED? */
    static boolean match(String pips, int i, List<String> src, boolean[] used) {
        if (i == pips.length()) return true;
        for (int k = 0; k < src.size(); k++)
            if (!used[k] && src.get(k).indexOf(pips.charAt(i)) >= 0) {
                used[k] = true;
                if (match(pips, i + 1, src, used)) { used[k] = false; return true; }
                used[k] = false;
            }
        return false;
    }

    /** how many of the hand's instants and flash spells (each card once) the untapped lands after playing DROP pay for */
    static int castable(Player me, Card drop) {
        List<Card> lands = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (c.isLand()) lands.add(c);
        lands.add(drop);
        List<String> src = new ArrayList<>();
        for (Card c : lands)
            if (c == drop ? untappedEntry(c, me) : c.isUntapped()) src.add(colours(c, lands));
        int n = 0;
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (c.isLand() || c == drop || !(c.isInstant() || c.hasKeyword(forge.game.keyword.Keyword.FLASH))) continue;
            for (SpellAbility sa : c.getSpells()) {
                if (sa.getPayCosts() == null || sa.getPayCosts().getTotalMana() == null) continue;
                forge.card.mana.ManaCost mc = sa.getPayCosts().getTotalMana();
                if (mc.countX() > 0 || mc.getCMC() > src.size()) continue;
                StringBuilder pips = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    int k = mc.getShardCount(LandOrder.SHARDS[i]);
                    for (int j = 0; j < k; j++) pips.append("WUBRG".charAt(i));
                }
                if (match(pips.toString(), 0, src, new boolean[src.size()])) { n++; break; }
            }
        }
        return n;
    }

    static List<SpellAbility> post(Player me, List<SpellAbility> ai) {
        if (ai == null || ai.isEmpty()) return ai;
        SpellAbility pick = ai.get(0);
        Card mine = pick.getHostCard();
        if (!pick.isLandAbility() || mine == null || !me.getGame().getPhaseHandler().isPlayerTurn(me)) return ai;
        try {
            int base = castable(me, mine), best = base;
            Card alt = null;
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (c == mine || !c.isLand() || c.getName().equals(mine.getName())) continue;
                int k = castable(me, c);
                if (k > best) { best = k; alt = c; }
            }
            if (alt == null) { ControlBrain.count(base > 0 ? "landcolour_kept" : "landcolour_none"); return ai; }
            for (SpellAbility sa : alt.getAllPossibleAbilities(me, true))
                if (sa.isLandAbility() && sa.canPlay() && me.canPlayLand(alt, false, sa)) {
                    ControlBrain.count("landcolour_swap");
                    return List.of(sa);
                }
        } catch (RuntimeException e) { ControlBrain.count("landcolour_error_" + e.getClass().getSimpleName()); }
        return ai;
    }
}
