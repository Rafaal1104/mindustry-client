package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.*;
import mindustry.client.communication.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * Пинги на карте: клавишей открываешь меню у курсора, выбираешь тип - метку видят союзники с клиентом.
 * У каждого игрока одна живая метка каждого типа, новая заменяет старую. За краем экрана - стрелка.
 */
public class MapPings{
    public enum Kind{
        attack(Pal.remove), defend(Pal.accent), help(Color.orange), retreat(Pal.heal), gather(Color.sky);

        public final Color color;

        Kind(Color color){
            this.color = color;
        }

        Drawable icon(){
            return switch(this){
                case attack -> Icon.commandAttackSmall;
                case defend -> Icon.defenseSmall;
                case help -> Icon.warningSmall;
                case retreat -> Icon.undoSmall;
                case gather -> Icon.moveSmall;
            };
        }

        String label(){
            return Core.bundle.get("client.ping." + name());
        }
    }

    private static final float lifetime = 15f * 60f;
    /** Не чаще одного пинга в секунду от себя: ретранслятор на сервере режет по 40 за 10 с. */
    private static final long sendCooldownMs = 1000L;
    private static boolean hooked;
    private static long lastSend;
    private static Table menu;
    private static final Seq<Ping> pings = new Seq<>();
    private static final Rect bounds = new Rect();

    private static class Ping{
        int owner;
        String name;
        Kind kind;
        float x, y, created;
    }

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                pings.clear();
                closeMenu();
                return;
            }
            if(Core.scene.getKeyboardFocus() == null && Core.input.keyTap(Binding.map_ping)){
                if(menu != null) closeMenu();
                else openMenu();
            }
            pings.removeAll(p -> Time.time - p.created > lifetime);
        });

        Events.run(Trigger.draw, () -> {
            if(state.isGame() && pings.any()) draw();
        });

        Events.on(WorldLoadEvent.class, e -> pings.clear());
    }

    /** Меню типов у курсора; точка пинга - где был курсор в момент нажатия. */
    public static void openMenu(){
        closeMenu();
        Vec2 world = Core.input.mouseWorld().cpy();
        Vec2 screen = Core.input.mouse().cpy();

        Table t = new Table(Styles.black6);
        t.margin(4f);
        t.defaults().size(40f).pad(1f);
        for(Kind k : Kind.values()){
            t.button(k.icon(), Styles.clearNonei, () -> {
                closeMenu();
                ping(k, world.x, world.y);
            }).tooltip(k.label()).get().getImage().setColor(k.color);
        }
        t.pack();
        t.setPosition(Mathf.clamp(screen.x - t.getWidth() / 2f, 0f, Core.graphics.getWidth() - t.getWidth()), Mathf.clamp(screen.y + 12f, 0f, Core.graphics.getHeight() - t.getHeight()));

        //клик мимо меню закрывает его
        Table root = new Table();
        root.setFillParent(true);
        root.touchable = Touchable.enabled;
        root.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                if(event.targetActor == root) Core.app.post(MapPings::closeMenu);
                return event.targetActor == root;
            }
        });
        root.addChild(t);
        menu = root;
        Core.scene.add(root);
    }

    private static void closeMenu(){
        if(menu != null){
            Table m = menu;
            menu = null;
            Core.app.post(m::remove);
        }
    }

    private static void ping(Kind kind, float x, float y){
        if(Time.timeSinceMillis(lastSend) < sendCooldownMs){
            ui.showInfoFade(Core.bundle.get("client.ping.cooldown"));
            return;
        }
        lastSend = Time.millis();
        add(player.id, player.coloredName(), kind, x, y);
        if(Core.settings.getBool("pings-share", true)){
            Main.INSTANCE.send(new PingTransmission(kind.ordinal(), x, y), null);
        }
    }

    private static void add(int owner, String name, Kind kind, float x, float y){
        pings.removeAll(p -> p.owner == owner && p.kind == kind);
        Ping p = new Ping();
        p.owner = owner;
        p.name = name;
        p.kind = kind;
        p.x = Mathf.clamp(x, 0f, world.unitWidth());
        p.y = Mathf.clamp(y, 0f, world.unitHeight());
        p.created = Time.time;
        pings.add(p);
    }

    /** Called on the main thread when another client user pings. */
    public static void receive(PingTransmission t, int senderId){
        if(!state.isGame() || !Core.settings.getBool("pings-receive", true)) return;
        Player sender = Groups.player.getByID(senderId);
        if(sender == null || sender == player || sender.team() != player.team()) return;
        if(t.getKind() < 0 || t.getKind() >= Kind.values().length) return;
        if(Float.isNaN(t.getX()) || Float.isNaN(t.getY())) return;
        Kind kind = Kind.values()[t.getKind()];
        add(senderId, sender.coloredName(), kind, t.getX(), t.getY());

        String where = (int)(t.getX() / tilesize) + ", " + (int)(t.getY() / tilesize);
        ui.showInfoFade(Core.bundle.format("client.ping.received", sender.coloredName(), kind.label(), where));
        if(Core.settings.getBool("pings-sound", true)) Sounds.uiNotify.play();
    }

    private static void draw(){
        Draw.z(Layer.overlayUI);
        Core.camera.bounds(bounds);
        Rect inner = Tmp.r2.set(bounds).grow(-24f);
        for(Ping p : pings){
            float age = Time.time - p.created;
            float fade = Mathf.clamp((lifetime - age) / 120f);
            Color c = p.kind.color;

            if(bounds.contains(p.x, p.y)){
                //расходящиеся круги первые секунды, потом спокойный маркер
                if(age < 180f){
                    float f = (age % 60f) / 60f;
                    Lines.stroke(2f * (1f - f), Tmp.c1.set(c).a(fade));
                    Lines.circle(p.x, p.y, 6f + f * 28f);
                }
                Lines.stroke(2f, Tmp.c1.set(c).a(fade));
                Lines.poly(p.x, p.y, p.kind == Kind.help ? 3 : p.kind == Kind.defend ? 4 : 6, 9f + Mathf.absin(Time.time, 6f, 2f), Time.time * 0.5f);
                Draw.color(Tmp.c1.set(c).a(fade));
                Fill.circle(p.x, p.y, 2f);
                FocusMarks.text(p.kind.label() + "\n[lightgray]" + p.name, p.x, p.y + 22f, fade);
            }else{
                float cx = Mathf.clamp(p.x, inner.x, inner.x + inner.width);
                float cy = Mathf.clamp(p.y, inner.y, inner.y + inner.height);
                Draw.color(Tmp.c1.set(c).a(fade));
                Fill.poly(cx, cy, 3, 7f, Angles.angle(cx, cy, p.x, p.y));
            }
        }
        Draw.reset();
    }
}
