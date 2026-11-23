package mindustry.client.fallen;

import arc.Core;
import arc.Events;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.game.Team;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.control;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.world;
import static mindustry.Vars.fogControl;

/**
 * "Ставить схему с очисткой": a schematic plan blocked by our own buildings breaks them
 * and goes into the build queue as soon as its footprint is free.
 * Called from InputHandler.flushPlans for the plans that do not fit.
 */
public class CustomBuildLogic {

    private static final Seq<Pending> pending = new Seq<>();
    private static BaseDialog settingsDialog;

    static {
        Events.on(WorldLoadEvent.class, e -> pending.clear());
    }

    private static class Pending {
        final BuildPlan plan;
        final Unit unit;
        final long time = Time.millis();

        Pending(BuildPlan plan, Unit unit) {
            this.plan = plan;
            this.unit = unit;
        }
    }

    private static boolean isUnbuildable(Building b) {
        if (b.block instanceof CoreBlock) return true;
        // источники/поглотители не сносим
        return b.block == Blocks.powerSource || b.block == Blocks.powerVoid ||
                b.block == Blocks.itemSource || b.block == Blocks.itemVoid ||
                b.block == Blocks.liquidSource || b.block == Blocks.liquidVoid;
    }

    private static boolean keepBigger() {
        return Core.settings.getBool("schemclean-keepbigger", true);
    }

    private static long timeout() {
        return Core.settings.getInt("schemclean-wait", 60) * 1000L;
    }

    /** The same kind of block, only smaller: a vault under a container plan, a large wall under small ones. */
    private static boolean isDowngrade(Block standing, Block planned) {
        return standing.size > planned.size && standing.getClass() == planned.getClass();
    }

    private static boolean isUnplaceable(Block block) {
        return block instanceof CoreBlock || block == Blocks.spawn;
    }

    /**
     * Collects the buildings standing in the plan's footprint. False when the plan can never be
     * placed here: a foreign or unbreakable building, a static wall, or a floor the block can't stand on.
     * One such tile kills the whole plan, so nothing is demolished for nothing.
     */
    private static boolean blockers(BuildPlan plan, Seq<Building> out) {
        Block block = plan.block;
        Tile origin = world.tile(plan.x, plan.y);
        Team team = player.team();
        if(origin == null || !block.canPlaceOn(origin, team, plan.rotation) || block.isFloor()) return false;
        if(!state.isEditor() && (!block.environmentBuildable() || !block.isPlaceable() &&
            !(state.rules.waves && team == state.rules.waveTeam && block.isVisible()))) return false;
        if(!block.ignoreBuildDarkness && world.getDarkness(plan.x, plan.y) >= 3) return false;
        if(!state.isEditor() && !block.requiresWater && !block.placeableLiquid &&
            !Build.contactsShallows(plan.x, plan.y, block)) return false;
        if(block.isOverPlacementLimit(team)) return false;
        if(state.rules.placeRangeCheck && !state.isEditor() && Build.getEnemyOverlap(block, team, plan.x, plan.y) != null) return false;

        int off = (block.size - 1) / 2;
        for (int dx = 0; dx < block.size; dx++) {
            for (int dy = 0; dy < block.size; dy++) {
                Tile tile = world.tile(plan.x + dx - off, plan.y + dy - off);
                if(tile == null || block.size == 2 && world.getDarkness(tile.x, tile.y) >= 3) return false;
                if(state.rules.staticFog && state.rules.fog && !fogControl.isDiscovered(team, tile.x, tile.y)) return false;
                if(!tile.floor().placeableOn && !block.ignoreBuildDarkness) return false;
                if(block.requiresWater && tile.floor().liquidDrop != mindustry.content.Liquids.water) return false;
                if(!state.isEditor() && tile.floor().isDeep() && !block.floating && !block.requiresWater && !block.placeableLiquid) return false;

                Building build = tile.build;
                if (build == null) {
                    if (tile.block().solid && !tile.block().alwaysReplace) return false;
                    continue;
                }
                if (build.team != player.team() || isUnbuildable(build)) return false;
                if (keepBigger() && isDowngrade(build.block, block)) return false;
                if (!control.input.validBreak(build.tileX(), build.tileY())) return false;
                out.addUnique(build);
            }
        }
        return true;
    }

    /**
     * Queues the demolition of everything under a plan that does not fit and keeps the plan
     * until its footprint is free. A plan that can never fit is dropped, as vanilla does.
     * @param broken positions already queued for breaking in this placement
     */
    public static void placeAfterCleanup(BuildPlan plan, IntSet broken) {
        Unit unit = player.unit();
        if (unit == null || plan.block == null || isUnplaceable(plan.block)) return;
        if(plan.build() != null && plan.build().team == player.team() && plan.block == plan.build().block
            && plan.x == plan.build().tileX() && plan.y == plan.build().tileY()
            && (!plan.block.rotate || plan.rotation == plan.build().rotation)) return;

        Seq<Building> clear = new Seq<>();
        if (!blockers(plan, clear) || clear.isEmpty()) return;

        for (Building b : clear) {
            if(broken.add(b.pos()) && !breakQueued(unit, b)){
                unit.addBuild(new BuildPlan(b.tileX(), b.tileY()));
            }
        }
        pending.removeAll(p -> p.plan.x == plan.x && p.plan.y == plan.y);
        pending.add(new Pending(plan, unit));
    }

    public static void update() {
        if (pending.isEmpty() || !state.isGame()) return;

        Unit unit = player.unit();
        long now = Time.millis(), timeout = timeout();
        for (int i = pending.size - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            if (p.unit != unit || unit == null || unit.dead() || now - p.time > timeout) {
                pending.remove(i);
                continue;
            }

            BuildPlan plan = p.plan;
            if (control.input.validPlace(plan.x, plan.y, plan.block, plan.rotation, null, true)) {
                unit.addBuild(plan);
                control.input.playerPlanTree.insert(plan);
                pending.remove(i);
                continue;
            }

            Seq<Building> clear = new Seq<>();
            if(!blockers(plan, clear) || clear.isEmpty() ||
                clear.contains(b -> !breakQueued(unit, b))){
                pending.remove(i);
            }
        }
    }

    /** ПКМ по кнопке «Схема» на панели. */
    public static void showSettings() {
        if (settingsDialog == null) {
            settingsDialog = new BaseDialog(Core.bundle.get("client.schemclean.settings"));
            settingsDialog.addCloseButton();
            settingsDialog.cont.table(t -> {
                t.defaults().left().pad(4);
                t.check(Core.bundle.get("client.schemclean.keepbigger"), keepBigger(),
                        v -> Core.settings.put("schemclean-keepbigger", v)).row();

                Label label = new Label("");
                Slider slider = new Slider(10, 180, 10, false);
                slider.setValue(Core.settings.getInt("schemclean-wait", 60));
                slider.changed(() -> {
                    Core.settings.put("schemclean-wait", (int) slider.getValue());
                    label.setText(Core.bundle.format("client.schemclean.wait", (int) slider.getValue()));
                });
                slider.change();
                t.add(label).row();
                t.add(slider).width(320f).row();
            });
        }
        settingsDialog.show();
    }

    private static boolean breakQueued(Unit unit, Building building){
        for(BuildPlan plan : unit.plans){
            if(plan.breaking && plan.x == building.tileX() && plan.y == building.tileY()) return true;
        }
        return false;
    }

    public static void cancelPending(){
        pending.clear();
    }
}
