package mindustry.client.fallen;

import arc.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.types.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * Moves friendly AI units that stand on a build plan out of its footprint with an RTS command.
 * Used by {@link mindustry.input.InputHandler#flushPlans} when a plan is invalid only because of units:
 * the plan is still queued and the builders simply wait until the units have stepped aside.
 */
public class UnitClearLogic {
    /** unit id -> Time.millis() of the last move order */
    private static final IntFloatMap lastOrder = new IntFloatMap();
    private static final long cooldownMs = 2500L;
    private static final Vec2 out = new Vec2();
    private static float lastScan;

    /** Loads this class so its update listener is registered even before the first blocked plan appears. */
    public static void init(){
    }

    static {
        Events.on(EventType.WorldLoadEvent.class, e -> {
            lastOrder.clear();
            lastScan = 0f;
        });

        //also clear units that wandered onto an already queued plan
        Events.run(EventType.Trigger.update, () -> {
            if(!state.isGame() || player == null || player.dead() || Time.time - lastScan < 60f) return;
            lastScan = Time.time;

            Unit self = player.unit();
            if(self == null) return;
            for(BuildPlan plan : self.plans){
                if(plan == null || plan.block == null || plan.breaking) continue;
                if(!Build.checkNoUnitOverlap(plan.block, plan.x, plan.y)
                    && Build.validPlaceIgnoreUnits(plan.block, player.team(), plan.x, plan.y, plan.rotation, true, true)){
                    clearPlan(plan);
                }
            }
        });
    }

    /**
     * Commands every friendly AI unit overlapping the plan footprint to a free air tile around it.
     * Player-controlled units are skipped: only RTS units can be moved this way.
     */
    public static boolean clearPlan(BuildPlan plan) {
        if(plan == null || plan.block == null || plan.breaking || !state.isGame() || player == null || player.dead()) return false;

        Unit self = player.unit();
        if(self == null) return false;

        float size = plan.block.size * tilesize;
        float cx = plan.drawx(), cy = plan.drawy();
        float x = cx - size / 2f, y = cy - size / 2f;

        Seq<Unit> blockers = new Seq<>();
        boolean[] enemy = {false};
        Units.nearby(null, x, y, size, size, unit -> {
            if(!blocking(unit, self, x, y, size)) return;
            if(unit.team != player.team()){
                enemy[0] = true;
                return;
            }
            if(!(unit.controller() instanceof CommandAI)) return;
            blockers.addUnique(unit);
        });

        //an enemy unit cannot be ordered away - let vanilla keep rejecting this plan
        if(enemy[0]) return false;

        Seq<Vec2> reserved = new Seq<>();
        for(Unit unit : blockers) {
            float now = Time.millis();
            if(now - lastOrder.get(unit.id, -1e9f) < cooldownMs) continue;
            if(!findFreeSpot(unit, cx, cy, size, reserved)) continue;

            lastOrder.put(unit.id, now);
            reserved.add(new Vec2(out.x, out.y));
            Call.commandUnits(player, new int[]{unit.id}, null, null, new Vec2(out.x, out.y), false, true);
        }

        return true;
    }

    private static boolean blocking(Unit unit, Unit self, float x, float y, float size) {
        if(unit == null || unit == self || unit.dead() || !unit.isValid()) return false;
        if(!unit.isAI()) return false;

        //hitbox overlap with the plan footprint
        float half = unit.hitSize / 2f;
        return unit.x + half > x && unit.x - half < x + size
            && unit.y + half > y && unit.y - half < y + size;
    }

    /** Finds an air tile around the plan that the unit can stand on and that is not inside another queued plan. */
    private static boolean findFreeSpot(Unit unit, float cx, float cy, float planSize, Seq<Vec2> reserved) {
        int centerX = World.toTile(cx), centerY = World.toTile(cy);
        float half = planSize / 2f;
        Unit self = player.unit();
        boolean[] found = {false};

        //nearest ring first, so the unit steps just outside the footprint
        for(int radius = 1; radius <= 16 && !found[0]; radius++) {
            for(int dx = -radius; dx <= radius && !found[0]; dx++) {
                for(int dy = -radius; dy <= radius && !found[0]; dy++) {
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;

                    Tile tile = world.tile(centerX + dx, centerY + dy);
                    if(tile == null) continue;
                    if(tile.solid() || tile.floor().isDeep()) continue;
                    if(!unit.canPass(tile.x, tile.y)) continue;

                    float wx = tile.worldx(), wy = tile.worldy();
                    if(Math.abs(wx - cx) < half && Math.abs(wy - cy) < half) continue;
                    if(insideAnyPlan(wx, wy)) continue;
                    if(self != null && self.within(wx, wy, self.hitSize / 2f + unit.hitSize / 2f)) continue;
                    if(reserved.contains(v -> v.within(wx, wy, unit.hitSize))) continue;

                    out.set(wx, wy);
                    found[0] = true;
                }
            }
        }

        return found[0];
    }

    private static boolean insideAnyPlan(float x, float y) {
        Unit unit = player == null ? null : player.unit();
        if(unit == null) return false;

        for(BuildPlan plan : unit.plans) {
            if(plan != null && plan.bounds(Tmp.r2).contains(x, y)) return true;
        }
        return false;
    }
}
