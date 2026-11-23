package mindustry.client.morj;

import arc.*;
import arc.graphics.g2d.*;
import arc.math.geom.Geometry;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.sandbox.LiquidSource.LiquidSourceBuild;

import static mindustry.Vars.*;

/** Checks the liquid network under the cursor without joining the two channels of a junction. */
public final class DuctMarks{
    private record Node(Building build, int axis){}
    private record Inlet(Building source, Liquid liquid){}

    // Below this amount pipes allow another liquid to enter.
    private static final float minLiquid = 0.2f;

    private static final Seq<Node> network = new Seq<>();
    private static final ObjectSet<Node> seen = new ObjectSet<>();
    private static final ObjectMap<Building, Inlet> inlets = new ObjectMap<>();
    private static final ObjectSet<Building> contacts = new ObjectSet<>();
    private static final ObjectSet<Liquid> liquids = new ObjectSet<>();
    private static Building last;
    private static float lastScan;
    private static boolean conflict;

    static{
        Events.on(WorldLoadEvent.class, event -> {
            network.clear();
            seen.clear();
            liquids.clear();
            inlets.clear();
            contacts.clear();
            last = null;
        });
    }

    private DuctMarks(){}

    public static void draw(){
        if(!Core.settings.getBool("ductcolor", false) || Core.scene.hasMouse() || player == null) return;
        Building origin = world.buildWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
        if(origin == null || !origin.enabled || origin.team != player.team() || !pipe(origin)){
            last = null;
            return;
        }

        if(origin != last || Time.time - lastScan >= 15f || conflict && network.contains(node -> !node.build.enabled)){
            last = origin;
            lastScan = Time.time;
            conflict = scan(origin, 0);
            if(!conflict && origin.block instanceof LiquidJunction) conflict = scan(origin, 1);
        }
        if(!conflict) return;

        Draw.z(Layer.overlayUI);
        Draw.color(Pal.remove);
        Lines.stroke(1f);
        Lines.square(origin.x, origin.y, origin.block.size * tilesize / 2f);
        for(Building build : contacts){
            if(build != origin && build.isValid() && build.enabled
                && build.within(Core.camera.position, Core.camera.width + Core.camera.height)){
                Lines.square(build.x, build.y, build.block.size * tilesize / 2f);
            }
        }
        Draw.color();
        float x = origin.x - (liquids.size - 1) * 3f;
        for(Liquid liquid : liquids){
            Draw.rect(liquid.uiIcon, x, origin.y + origin.block.size * tilesize / 2f + 5f, 6f, 6f);
            x += 6f;
        }
        Draw.reset();
    }

    private static boolean scan(Building origin, int axis){
        network.clear();
        seen.clear();
        liquids.clear();
        inlets.clear();
        contacts.clear();
        enqueue(origin, axis);
        for(int i = 0; i < network.size; i++){
            Node node = network.get(i);
            Building build = node.build;
            if(!pipe(build)) continue;

            for(Building other : build.proximity){
                if(other.team != build.team || !other.block.hasLiquids || !pipe(other) && !other.block.outputsLiquid) continue;
                int dir = build.relativeToEdge(other.tile);
                int back = other.relativeToEdge(build.tile);
                if(dir < 0 || back < 0 || build.block instanceof LiquidJunction && dir % 2 != node.axis) continue;
                if(sendsLocally(build, dir) && acceptsLocally(other, back)
                    || sendsLocally(other, back) && acceptsLocally(build, dir)) enqueue(other, back % 2);
            }

            if(build instanceof LiquidBridge.LiquidBridgeBuild bridge){
                Building target = world.build(bridge.link);
                if(target != null && ((ItemBridge)bridge.block).linkValid(bridge.tile, target.tile)){
                    enqueue(target, 0);
                }
                for(int j = 0; j < bridge.incoming.size; j++){
                    Building source = world.build(bridge.incoming.get(j));
                    if(source instanceof LiquidBridge.LiquidBridgeBuild incoming && incoming.link == bridge.pos()) enqueue(source, 0);
                }
            }else if(build instanceof DirectionLiquidBridge.DuctBridgeBuild bridge){
                var target = bridge.findLink();
                if(target != null) enqueue(target, 0);
                for(int dir = 0; dir < 4; dir++){
                    for(int dist = 1; dist <= ((DirectionBridge)bridge.block).range; dist++){
                        var tile = bridge.tile.nearby(Geometry.d4x(dir) * dist, Geometry.d4y(dir) * dist);
                        if(tile != null && tile.build instanceof DirectionLiquidBridge.DuctBridgeBuild incoming
                            && incoming.team == bridge.team && incoming.findLink() == bridge) enqueue(incoming, 0);
                    }
                }
            }
        }
        for(Node node : network){
            Building build = node.build;
            if(!(build.block instanceof LiquidJunction)) traceOutlets(build);
        }
        return !contacts.isEmpty();
    }

    private static void enqueue(Building build, int axis){
        if(!build.enabled) return;
        Node node = new Node(build, build.block instanceof LiquidJunction ? axis : 0);
        if(seen.add(node)) network.add(node);
    }

    private static boolean pipe(Building build){
        return build instanceof Conduit.ConduitBuild || build.block instanceof LiquidRouter || build.block instanceof LiquidJunction
            || build instanceof LiquidBridge.LiquidBridgeBuild || build instanceof DirectionLiquidBridge.DuctBridgeBuild;
    }

    private static boolean sendsLocally(Building build, int dir){
        if(build instanceof Conduit.ConduitBuild) return build.rotation == dir;
        if(build instanceof LiquidBridge.LiquidBridgeBuild bridge){
            Building target = world.build(bridge.link);
            return target == null || !((ItemBridge)bridge.block).linkValid(bridge.tile, target.tile);
        }
        if(build instanceof DirectionLiquidBridge.DuctBridgeBuild bridge) return build.rotation == dir && bridge.findLink() == null;
        return build.block.outputsLiquid;
    }

    private static boolean acceptsLocally(Building build, int dir){
        if(build instanceof Conduit.ConduitBuild) return build.rotation != dir;
        if(build instanceof LiquidBridge.LiquidBridgeBuild bridge){
            Building target = world.build(bridge.link);
            return target != null && ((ItemBridge)bridge.block).linkValid(bridge.tile, target.tile)
                && bridge.relativeTo(target) != dir;
        }
        if(build instanceof DirectionLiquidBridge.DuctBridgeBuild bridge){
            return build.rotation != dir && bridge.findLink() != null && bridge.occupied[(dir + 2) % 4] == null;
        }
        return pipe(build);
    }

    private static void traceOutlets(Building source){
        if(source instanceof LiquidBridge.LiquidBridgeBuild bridge){
            Building target = world.build(bridge.link);
            if(target != null && ((ItemBridge)bridge.block).linkValid(bridge.tile, target.tile)){
                checkInlet(source, target, currentLiquid(source));
                return;
            }
        }else if(source instanceof DirectionLiquidBridge.DuctBridgeBuild bridge){
            Building target = bridge.findLink();
            if(target != null){
                checkInlet(source, target, currentLiquid(source));
                return;
            }
        }

        for(Building neighbor : source.proximity){
            int dir = source.relativeToEdge(neighbor.tile);
            if(dir < 0 || !sendsLocally(source, dir)) continue;

            Building entry = source, target = neighbor;
            while(target != null && target.enabled && target.team == source.team && target.block instanceof LiquidJunction){
                int incomingSide = target.relativeToEdge(entry.tile);
                if(incomingSide < 0) break;
                entry = target;
                target = target.nearby((incomingSide + 2) % 4);
            }
            if(target == null || !target.enabled || target.team != source.team) continue;
            int side = target.relativeToEdge(entry.tile);
            if(side < 0 || !acceptsLocally(target, side)) continue;
            if(!seen.contains(new Node(target, 0))) continue;

            if(source.block instanceof GenericCrafter crafter && crafter.outputLiquids != null && source.liquids != null){
                for(int i = 0; i < crafter.outputLiquids.length; i++){
                    Liquid output = crafter.outputLiquids[i].liquid;
                    int outputDir = i < crafter.liquidOutputDirections.length ? crafter.liquidOutputDirections[i] : -1;
                    if((outputDir == -1 || (outputDir + source.rotation) % 4 == dir) && source.liquids.get(output) >= minLiquid){
                        checkInlet(entry, target, output);
                    }
                }
            }else{
                Liquid liquid = currentLiquid(source);
                if(liquid != null && source.canDumpLiquid(target, liquid)) checkInlet(entry, target, liquid);
            }
        }
    }

    private static Liquid currentLiquid(Building build){
        if(build instanceof LiquidSourceBuild source) return source.source;
        return build.liquids != null && build.liquids.currentAmount() >= minLiquid ? build.liquids.current() : null;
    }

    private static void checkInlet(Building source, Building target, Liquid liquid){
        if(liquid == null || !target.enabled) return;

        Liquid current = currentLiquid(target);
        if(current != null && current != liquid){
            contacts.add(source);
            contacts.add(target);
            liquids.add(current);
            liquids.add(liquid);
        }

        Inlet previous = inlets.get(target);
        if(previous == null){
            inlets.put(target, new Inlet(source, liquid));
        }else if(previous.liquid != liquid){
            contacts.add(previous.source);
            contacts.add(source);
            contacts.add(target);
            liquids.add(previous.liquid);
            liquids.add(liquid);
        }
    }
}
