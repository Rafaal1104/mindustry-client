package mindustry.client.fallen;

import arc.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.*;
import mindustry.client.communication.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;

import java.security.cert.*;

import static mindustry.Vars.*;

/**
 * Общие отметки о грифе: свой детектор (ActivityLogger) или клавиша над игроком/постройкой шлёт союзникам с клиентом,
 * кто, где и сколько сломал/построил. У получателя - строка в чате с кликабельными координатами и метка на карте.
 * Отправителя называет ретранслятор сервера, подделать его нельзя; если у него есть ключ, отчёт ещё и подписан.
 */
public class GriefReports{
    private static final float lifetime = 60f * 60f;
    /** Один отчёт на пару «кто сообщил - на кого» раз в минуту, чтобы детекторы нескольких клиентов не заспамили чат. */
    private static final long repeatMs = 60_000L;
    private static boolean hooked;
    private static final Seq<Report> reports = new Seq<>();
    private static final ObjectMap<String, Long> lastSeen = new ObjectMap<>();
    private static final Rect bounds = new Rect();

    private static class Report{
        int suspectId;
        String suspect;
        float x, y, created;
    }

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                reports.clear();
                return;
            }
            if(Core.scene.getKeyboardFocus() == null && Core.input.keyTap(Binding.grief_mark)){
                Vec2 m = Core.input.mouseWorld();
                reportAt(m.x, m.y);
            }
            reports.removeAll(r -> Time.time - r.created > lifetime);
        });

        Events.run(Trigger.draw, () -> {
            if(state.isGame() && reports.any()) draw();
        });

        Events.on(WorldLoadEvent.class, e -> {
            reports.clear();
            lastSeen.clear();
        });
    }

    // ---------- отправка ----------

    /** Свой детектор нашёл подозрительного: поделиться, если включено. Имя - без цветов, как в истории. */
    public static void autoReport(String rawName, int breaks, int builds){
        if(!Core.settings.getBool("griefmark-share", true)) return;
        Player suspect = byName(rawName);
        if(suspect == null || suspect == player) return;
        Vec2 at = lastBreak(rawName, new Vec2(suspect.x, suspect.y));
        send(0, suspect, at.x, at.y, breaks, builds);
    }

    /** Клавиша: игрок под курсором, а если там постройка - тот, кто трогал её последним. */
    private static void reportAt(float x, float y){
        Player suspect = null;
        for(Player p : Groups.player){
            if(p == player || p.unit() == null || p.unit().dead) continue;
            if(p.unit().within(x, y, p.unit().hitSize / 2f + tilesize)){
                suspect = p;
                break;
            }
        }
        if(suspect == null){
            Building b = world.buildWorld(x, y);
            if(b != null && b.lastAccessed != null) suspect = byName(Strings.stripColors(b.lastAccessed));
        }
        if(suspect == null || suspect == player){
            ui.showInfoFade(Core.bundle.get("client.grief.nosuspect"));
            return;
        }
        int[] counts = counts(Strings.stripColors(suspect.name));
        send(1, suspect, x, y, counts[1], counts[0]);
        ui.showInfoFade(Core.bundle.format("client.grief.sent", suspect.coloredName()));
    }

    private static void send(int reason, Player suspect, float x, float y, int breaks, int builds){
        add(suspect, x, y);
        Main.INSTANCE.send(new GriefMarkTransmission(reason, suspect.id, x, y, breaks, builds), null);
    }

    private static @Nullable Player byName(String rawName){
        return Groups.player.find(p -> Strings.stripColors(p.name).equals(rawName));
    }

    /** [построено, сломано] этим игроком по истории действий. */
    private static int[] counts(String rawName){
        int[] out = new int[2];
        for(ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans){
            if(rawName.equals(plan.lastacs)) out[plan.wasbreaking ? 1 : 0]++;
        }
        return out;
    }

    /** Где игрок ломал последним (история идёт от новых к старым). */
    private static Vec2 lastBreak(String rawName, Vec2 fallback){
        for(ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans){
            if(plan.wasbreaking && rawName.equals(plan.lastacs)) return fallback.set(plan.x * tilesize, plan.y * tilesize);
        }
        return fallback;
    }

    // ---------- приём ----------

    /** Called on the main thread when another client user reports a griefer. */
    public static void receive(GriefMarkTransmission t, int senderId){
        if(!state.isGame() || !Core.settings.getBool("griefmark-receive", true)) return;
        Player sender = Groups.player.getByID(senderId);
        if(sender == null || sender == player || sender.team() != player.team()) return;
        Player suspect = Groups.player.getByID(t.getSuspectId());
        if(suspect == null || Float.isNaN(t.getX()) || Float.isNaN(t.getY())) return;

        String key = senderId + ":" + suspect.id;
        Long last = lastSeen.get(key);
        if(last != null && Time.timeSinceMillis(last) < repeatMs) return;
        lastSeen.put(key, Time.millis());

        add(suspect, t.getX(), t.getY());

        X509Certificate cert = t.verifiedBy();
        String who = sender.coloredName() + "[]" + (cert != null ? " [green]" + Iconc.ok + " " + Main.INSTANCE.getKeyStorage().aliasOrName(cert) + "[]" : "");
        String msg = Core.bundle.format(t.getReason() == 0 ? "client.grief.auto" : "client.grief.manual",
            who, suspect.coloredName() + "[]", t.getBreaks(), t.getBuilds(),
            (int)(t.getX() / tilesize) + "," + (int)(t.getY() / tilesize));
        if(ui.chatfrag != null) NetClient.findCoords(ui.chatfrag.addMessage(msg, null, null, "", msg));
        if(Core.settings.getBool("pings-sound", true)) Sounds.uiNotify.play();
    }

    private static void add(Player suspect, float x, float y){
        reports.removeAll(r -> r.suspectId == suspect.id);
        Report r = new Report();
        r.suspectId = suspect.id;
        r.suspect = suspect.coloredName();
        r.x = Mathf.clamp(x, 0f, world.unitWidth());
        r.y = Mathf.clamp(y, 0f, world.unitHeight());
        r.created = Time.time;
        reports.add(r);
    }

    private static void draw(){
        Draw.z(Layer.overlayUI);
        Core.camera.bounds(bounds);
        for(Report r : reports){
            float fade = Mathf.clamp((lifetime - (Time.time - r.created)) / 120f);
            //метка там, где ломали, и линия к самому игроку, если он ещё здесь
            Player p = Groups.player.getByID(r.suspectId);
            if(p != null && p.unit() != null && !p.unit().dead){
                Lines.stroke(1f, Tmp.c1.set(Pal.remove).a(0.4f * fade));
                Lines.dashLine(r.x, r.y, p.unit().x, p.unit().y, (int)Math.max(2, Mathf.dst(r.x, r.y, p.unit().x, p.unit().y) / 8f));
                Lines.stroke(1.5f, Tmp.c1.set(Pal.remove).a(fade));
                Lines.circle(p.unit().x, p.unit().y, p.unit().hitSize / 2f + 4f + Mathf.absin(Time.time, 4f, 2f));
            }
            if(!bounds.contains(r.x, r.y)) continue;
            Lines.stroke(2f, Tmp.c1.set(Pal.remove).a(fade));
            Lines.poly(r.x, r.y, 3, 10f + Mathf.absin(Time.time, 6f, 2f), 90f);
            FocusMarks.text("[scarlet]" + Iconc.warning + "[] " + r.suspect, r.x, r.y + 20f, fade);
        }
        Draw.reset();
    }
}
