package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.UI;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/** 60 one-second samples of core item counts, drawn as one line per item. */
public final class CoreChart{
    private static final int samples = 60;
    private static final int maxLines = 5;
    private static final Color[] seriesColors = {
        Color.valueOf("79caff"), Color.valueOf("ffd27a"), Color.valueOf("f4a7d9"),
        Color.valueOf("93df9e"), Color.valueOf("d7bcff")
    };
    private static CoreBuild sampledCore;
    private static int[][] history;
    private static int cursor, count;
    private static float acc;
    private static final IntSeq picked = new IntSeq();

    private CoreChart(){}

    static{
        Events.on(WorldLoadEvent.class, e -> reset());
    }

    public static boolean enabled(){
        return Core.settings.getBool("corechart", true);
    }

    public static void reset(){
        cursor = 0;
        count = 0;
        acc = 0f;
        history = null;
        sampledCore = null;
    }

    public static void update(){
        if(!enabled() || !state.isGame() || state.isPaused() || player == null) return;
        CoreBuild core = player.team().core();
        if(sampledCore != core){
            reset();
            sampledCore = core;
        }
        if(core == null || core.items == null || content.items().isEmpty()) return;
        acc += Time.delta;
        if(acc < 60f) return;
        acc = 0f;
        int n = content.items().size;
        if(history == null || history.length < n){
            history = new int[n][samples];
            cursor = count = 0;
        }
        for(Item item : content.items()){
            if(item.id < 0 || item.id >= history.length) continue;
            history[item.id][cursor] = core.items.get(item);
        }
        cursor = (cursor + 1) % samples;
        if(count < samples) count++;
    }

    public static Element widget(){
        return new Element(){
            @Override
            public void draw(){
                if(!enabled() || history == null || count < 2) return;
                pick();
                float left = x + 6f, right = x + width - 60f;
                float bottom = y + 14f, top = y + height - 13f;
                if(right <= left) return;
                Draw.color(Pal.darkestGray, 0.9f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height);

                int max = 1;
                for(int i = 0; i < picked.size; i++){
                    for(int j = 0; j < count; j++) max = Math.max(max, sample(picked.get(i), j));
                }
                Draw.color(Color.lightGray, 0.25f);
                Lines.stroke(1f);
                for(int line = 0; line < 3; line++){
                    float py = bottom + (top - bottom) * line / 2f;
                    Lines.line(left, py, right, py);
                }

                var font = Fonts.outline;
                float oldX = font.getData().scaleX, oldY = font.getData().scaleY;
                font.getData().setScale(0.7f);
                font.setColor(Color.lightGray);
                font.draw(UI.formatAmount(max), left, top + 11f);
                font.draw("-" + (count - 1) + "s", left, bottom - 1f);
                font.draw("0s", right - 13f, bottom - 1f);
                for(int i = 0; i < picked.size; i++){
                    Item item = content.item(picked.get(i));
                    if(item == null) continue;
                    Color color = seriesColors[i];
                    Draw.color(color);
                    Lines.stroke(1.8f);
                    float prevX = 0f, prevY = 0f;
                    for(int s = 0; s < count; s++){
                        float px = left + (right - left) * (s / (float)(count - 1));
                        float py = bottom + (top - bottom) * (sample(item.id, s) / (float)max);
                        if(s > 0) Lines.line(prevX, prevY, px, py);
                        prevX = px;
                        prevY = py;
                    }
                    float labelY = top - i * 14f;
                    Fill.rect(right + 7f, labelY, 4f, 2f);
                    Draw.color();
                    Draw.rect(item.uiIcon, right + 16f, labelY, 11f, 11f);
                    font.setColor(color);
                    font.draw(UI.formatAmount(sample(item.id, count - 1)), right + 24f, labelY + 4f);
                }
                font.getData().setScale(oldX, oldY);
                font.setColor(Color.white);
                Draw.reset();
            }

            @Override
            public float getPrefHeight(){
                return 92f;
            }
        };
    }

    private static int sample(int item, int ageFromOldest){
        int start = (cursor - count + samples) % samples;
        int index = (start + ageFromOldest) % samples;
        return history[item][index];
    }

    private static void pick(){
        picked.clear();
        if(history == null) return;
        for(int pass = 0; pass < 2 && picked.size < maxLines; pass++){
            for(Item item : content.items()){
                if(picked.size >= maxLines) break;
                if(item.id < 0 || item.id >= history.length || picked.contains(item.id)) continue;
                int oldest = sample(item.id, 0);
                int newest = sample(item.id, count - 1);
                boolean changed = false;
                if(pass == 0){
                    for(int i = 1; i < count && !changed; i++) changed = sample(item.id, i) != oldest;
                }
                boolean stock = newest > 0 || oldest > 0;
                if(pass == 0 ? changed : stock) picked.add(item.id);
            }
        }
    }
}
