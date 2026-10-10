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

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
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
                return ai;
            }
            return super.chooseSpellAbilityToPlay();
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
        public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
            super.declareAttackers(attacker, combat);
            if (TWEAKS.contains("protect")) ProtectBrain.holdTamBack(getPlayer(), combat);
            if (TWEAKS.contains("leyline")) LeylineBrain.forceAttack(getPlayer(), combat);
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
