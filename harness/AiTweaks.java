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
 * prowesspre — in our main 1, with an untapped prowess creature able to attack, cast cheap untargeted spells first.
 * reckless=NAME — the player whose deck name contains NAME uses Forge's Reckless AI profile (aggressive attacks).
 * prowessplan — Izzet Prowess (ProwessBrain): each turn "can we win this turn?" (lethal search over cheap spells, burn
 *   targets and Eddymurk Crab, worst-case blocks); if so, cast the line and attack with everything; if not, set-up mode.
 * leyburn / leyotter / leydig / leyland — with leyline, fixes from reading losses (LeylineBrain): no Burn Together on
 * our own creature before combat, and "attack, then Burn Together to the face" counted as lethal; Elusive Otter pumped
 * past every blocker; cantrip pumps cast to dig when stuck; the land drop that lets a cheap creature be cast.
 * leylinemull[A-D,S] — Izzet Leyline's mulligans (LeylineBrain.keepHand; A, the default: the 7-card hand needs Leyline
 * of Resonance, a creature and a land; after one mulligan a creature and a land; after two a land; then keep. S: the
 * exact optimal strategy for the 10-criteria hand score, goldfish/mull_opt.py).
 *
 * landfall — LandfallBrain's Escape Tunnel + Mightform Harmonizer kill for Mono-Green Landfall.
 *
 * ooze — with protect: Ferocity of the Hunt is held (no end-of-turn cast) for removal on a non-indestructible creature
 * (Ooze's partners: Beorn, Zookeeper, ...) or for Predator Ooze in a combat its power cannot win (ProtectBrain).
 * oozeeot — as ooze, plus the end-of-turn cast on our best creature that is not indestructible.
 * manahold — ManaHold: reserve the lands for Ferocity of the Hunt at our main phase 1 (use with oozeeot, whose
 * end-of-turn cast is the scheduled fallback).
 *
 * protect — ProtectBrain's uses of Tam, Mindful First-Year and Ferocity of the Hunt. Several tweaks can be combined:
 * -Dforge.sim.tweaks=spiderham,control,protect
 */
public class AiTweaks {
    static final String TWEAKS = System.getProperty("forge.sim.tweaks", "");
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

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            checkProfile();
            if (!simChecked) {   // simhybrid / simfull: Forge's search-based AI, for the Izzet Leyline player only
                simChecked = true;
                if ((TWEAKS.contains("simhybrid") || TWEAKS.contains("simfull")) && LeylineBrain.deck(getPlayer()))
                    getAi().setUseSimulation(TWEAKS.contains("simfull") ? forge.ai.AIOption.USE_FULL_SIMULATION : forge.ai.AIOption.USE_HYBRID_SIMULATION);
            }
            if (TWEAKS.contains("flasheot")) {
                SpellAbility f = flashAtEndOfTurn(getPlayer());
                if (f != null) return List.of(f);
            }
            if (TWEAKS.contains("prowessplan")) {
                SpellAbility pw = ProwessBrain.choose(getPlayer());
                if (pw != null) return List.of(pw);
            }
            if (TWEAKS.contains("prowesspre")) {
                SpellAbility pp = prowessPrecombat(getPlayer());
                if (pp != null) return List.of(pp);
            }
            if (TWEAKS.contains("manahold")) ManaHold.plan(getPlayer());
            if (TWEAKS.contains("spiderham")) {
                SpellAbility bite = spiderHamBite(getPlayer());
                if (bite != null) {
                    bites++;
                    return List.of(bite);
                }
            }
            if (TWEAKS.contains("leyline")) {
                SpellAbility l = LeylineBrain.choose(getPlayer());
                if (l != null) return List.of(l);
            }
            if (TWEAKS.contains("landfall")) {
                SpellAbility s = LandfallBrain.choose(getPlayer());
                if (s != null) return List.of(s);
            }
            if (TWEAKS.contains("protect")) {
                SpellAbility p = ProtectBrain.choose(getPlayer());
                if (p != null) return List.of(p);
            }
            if (TWEAKS.contains("control")) {
                if (ControlBrain.holdUntilMain(getPlayer())) return null;
                SpellAbility mine = ControlBrain.choose(getAi(), getPlayer());
                if (mine != null) return List.of(mine);
                List<SpellAbility> ai = super.chooseSpellAbilityToPlay();
                if (ai != null && ai.stream().anyMatch(ControlBrain::vetoed)) {
                    ControlBrain.count("hall_veto");
                    return null;
                }
                ai = TWEAKS.contains("leyline") ? LeylineBrain.post(getPlayer(), ai) : ai;
                ai = TWEAKS.contains("prowessplan") ? ProwessBrain.post(getPlayer(), ai) : ai;
                return spendIfIdle(getPlayer(), ai);
            }
            List<SpellAbility> ai = super.chooseSpellAbilityToPlay();
            ai = TWEAKS.contains("leyline") ? LeylineBrain.post(getPlayer(), ai) : ai;
            ai = TWEAKS.contains("prowessplan") ? ProwessBrain.post(getPlayer(), ai) : ai;
            return spendIfIdle(getPlayer(), ai);
        }

        @Override
        public boolean playTrigger(Card host, forge.game.trigger.WrappedAbility wa, boolean isMandatory) {
            if (TWEAKS.contains("landfall")) {
                Boolean r = LandfallBrain.playTrigger(getPlayer(), wa);
                if (r != null) return r;
            }
            return super.playTrigger(host, wa, isMandatory);
        }

        @Override
        public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> sas) {
            if (TWEAKS.contains("landfall")) {
                List<SpellAbility> o = LandfallBrain.order(getPlayer(), sas);
                if (o != null) return o;
            }
            return super.orderSimultaneousSa(sas);
        }

        @Override
        public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
            checkProfile();
            if (TWEAKS.contains("leylinemull") && LeylineBrain.deck(getPlayer()))
                return LeylineBrain.keepHand(getPlayer(), cardsToReturn);
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }

        @Override
        public forge.game.card.CardCollectionView tuckCardsViaMulligan(forge.game.card.CardCollectionView hand, int n) {
            forge.game.card.CardCollectionView out = super.tuckCardsViaMulligan(hand, n);
            if (TWEAKS.contains("leylinemull") && LeylineBrain.deck(getPlayer())) return LeylineBrain.tuck(hand, out, n);
            return out;
        }

        @Override
        public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
            super.declareAttackers(attacker, combat);
            if (TWEAKS.contains("protect")) ProtectBrain.holdTamBack(getPlayer(), combat);
            if (TWEAKS.contains("leyline")) LeylineBrain.forceAttack(getPlayer(), combat);
            if (TWEAKS.contains("prowessplan")) ProwessBrain.forceAttack(getPlayer(), combat);
        }

        @Override
        public boolean payCostToPreventEffect(forge.game.cost.Cost cost, SpellAbility sa, boolean alreadyPaid,
                                              forge.util.collect.FCollectionView<Player> allPayers) {
            if (TWEAKS.contains("control")) {
                Boolean pay = ControlBrain.payShock(cost, sa, getPlayer());
                if (pay != null) {
                    if (!pay || !ComputerUtilCost.canPayCost(cost, sa, getPlayer(), false)) return false;
                    return new forge.game.cost.CostPayment(cost, sa).payComputerCosts(new forge.ai.AiCostDecision(getPlayer(), sa, false));
                }
            }
            return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }
    }

    /**
     * spendmana (2026-10-11, mono-blue: 9% of turns with Tempest Djinn castable passed with nothing cast): when Forge's
     * AI would pass in our main phase 2 with the stack empty, cast the most expensive creature spell in hand it can pay
     * for, so mana is not left unused.
     */
    static List<SpellAbility> spendIfIdle(Player me, List<SpellAbility> ai) {
        if (!TWEAKS.contains("spendmana") || (ai != null && !ai.isEmpty())) return ai;
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
