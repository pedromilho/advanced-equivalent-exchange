package br.pedro.aee;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Advanced Storage item details and rarity styles, placed inside the chest page. */
final class StorageTooltip {
    private record Content(String name,String id,String quality,String skin,boolean showQuality,String description,String stats) {}
    private Content mounted;
    private String geometry="";
    private static final String HOST="#PageTooltipHost ";
    private static final int BODY_LINE_HEIGHT=22;
    private static final Pattern FORMATTING_TAG=Pattern.compile("</?(?:i|b|u|color)(?:\\s+[^<>]*)?>",Pattern.CASE_INSENSITIVE);
    private boolean visible;
    private void hide(UICommandBuilder commands) {
        if (visible) commands.set(HOST + "#StorageTooltip.Visible", false);
        visible = false;
    }

    private static String translated(String language,String key,String fallback) {
        if(key==null || key.isEmpty()) return fallback;
        String value=I18nModule.get().getMessage(language,key);
        if(value==null) value=I18nModule.get().getMessage("en-US",key);
        return value==null?fallback:value;
    }
    private static String number(double value) {return String.format(Locale.US,value==Math.rint(value)?"%.0f":"%.1f",value);}
    private static Content content(ItemStack stack,String language) {
        var item=stack.getItem();
        var quality=ItemQuality.getAssetMap().getAsset(stack.getQualityIndex());
        if(quality==null) quality=ItemQuality.DEFAULT_ITEM_QUALITY;
        String skin=quality.getId();
        if(!Set.of("Common","Debug","Developer","Epic","Junk","Legendary","Rare","Technical","Template","Tool","Uncommon").contains(skin)) skin="Common";
        String description=translated(language,item.getDescriptionTranslationKey(),"");
        var stats=new ArrayList<String>();
        if(item.getArmor()!=null) stats.add("Defense: "+number(item.getArmor().getBaseDamageResistance()));
        if(item.getWeapon()!=null && item.getWeapon().getBasicDamageBreakdown()!=null)
            for(var damage:item.getWeapon().getBasicDamageBreakdown().entries())
                stats.add(translated(language,damage.labelKey(),"Damage")+": "+number(damage.min())+(damage.min()!=damage.max()?" - "+number(damage.max()):""));
        if(stack.getMaxDurability()>0) stats.add("Durability: "+number(stack.getDurability())+" / "+number(stack.getMaxDurability()));
        return new Content(translated(language,item.getTranslationKey(),stack.getItemId()),"ID: "+stack.getItemId(),
            translated(language,quality.getLocalizationKey(),quality.getId()),skin,quality.isVisibleQualityLabel(),description,String.join("\n",stats));
    }
    private static void anchor(UICommandBuilder commands,String selector,int left,int top,int width,int height) {
        var a=new Anchor();a.setLeft(Value.of(left));a.setTop(Value.of(top));a.setWidth(Value.of(width));a.setHeight(Value.of(height));commands.setObject(selector+".Anchor",a);
    }
    static List<String> wrapBody(String text,int width) {
        String plain=FORMATTING_TAG.matcher(text).replaceAll("").strip();
        if(plain.isEmpty()) return List.of();
        var lines=new ArrayList<String>();
        for(String paragraph:plain.split("\\R",-1)) {
            paragraph=paragraph.strip();
            if(paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            int start=0;
            while(start<paragraph.length()) {
                int end=start;
                int lastSpace=-1;
                int used=0;
                while(end<paragraph.length()) {
                    int glyph=paragraph.codePointAt(end);
                    if(Character.isWhitespace(glyph)) lastSpace=end;
                    int next=used+bodyGlyphWidth(glyph);
                    if(next>width && end>start) break;
                    used=next;
                    end+=Character.charCount(glyph);
                }
                if(end<paragraph.length() && lastSpace>start) end=lastSpace;
                lines.add(paragraph.substring(start,end).stripTrailing());
                start=end;
                while(start<paragraph.length() && Character.isWhitespace(paragraph.codePointAt(start)))
                    start+=Character.charCount(paragraph.codePointAt(start));
            }
        }
        return lines;
    }
    private static int bodyGlyphWidth(int glyph) {
        // Budget generously for the client's proportional 14 px font.
        if(Character.isWhitespace(glyph) || "ilI.,:;!'|`".indexOf(glyph)>=0) return 6;
        if(glyph>127 || "mMwW@%".indexOf(glyph)>=0) return 16;
        return Character.isUpperCase(glyph)?12:10;
    }
    void render(UICommandBuilder commands, ItemStack stack, String language,
                int cellX, int cellY, int cellSize, int panelWidth, int panelHeight) {
        render(commands, stack, language, cellX, cellY, cellSize, panelWidth, panelHeight, "");
    }

    void render(UICommandBuilder commands, ItemStack stack, String language,
                int cellX, int cellY, int cellSize, int panelWidth, int panelHeight, String extra) {
        if (ItemStack.isEmpty(stack)) {
            hide(commands);
            return;
        }
        var current = content(stack, language);
        if (!extra.isEmpty()) {
            String stats = current.stats.isEmpty() ? extra : extra + "\n" + current.stats;
            current = new Content(current.name, current.id, current.quality, current.skin,
                current.showQuality, current.description, stats);
        }
        int width=Math.min(480,Math.max(320,Math.max(current.name.length()*10+48,current.id.length()*7+48)));
        var descriptionLines=wrapBody(current.description,width-52).stream().limit(8).toList();
        var statLines=wrapBody(current.stats,width-52).stream().limit(6).toList();
        int descriptionHeight=descriptionLines.isEmpty()?0:12+descriptionLines.size()*BODY_LINE_HEIGHT;
        int statsHeight=statLines.isEmpty()?0:17+statLines.size()*BODY_LINE_HEIGHT;
        int idHeight=18*Math.max(1,(int)Math.ceil(current.id.length()/((width-48)/7.0)));
        int headerHeight=Math.max(24,20*(int)Math.ceil(current.name.length()*9.0/Math.max(100,width-48-(current.showQuality?current.quality.length()*7+5:0))));
        int height=headerHeight+idHeight+45+descriptionHeight+statsHeight;
        int center = cellX + cellSize / 2;
        int left = Math.clamp(center - width / 2, 0, panelWidth - width);
        int above = cellY - height - 8;
        int below = cellY + cellSize + 8;
        boolean pointUp = above < 0 && below + height <= panelHeight;
        int top = pointUp ? below : Math.clamp(above, 0, Math.max(0, panelHeight - height - 8));
        int arrowLeft = Math.clamp(center - left - 16, 12, width - 45);
        String nextGeometry = left + ":" + top + ":" + width + ":" + height + ":" + arrowLeft + ":" + pointUp
            + ":" + headerHeight + ":" + idHeight + ":" + descriptionHeight + ":" + statsHeight;
        boolean moved=!nextGeometry.equals(geometry);
        if(moved) {
            anchor(commands, HOST + "#StorageTooltipCanvas", 0, 0, panelWidth, panelHeight);
            anchor(commands, HOST + "#StorageTooltip", left, top, width, height);
            var arrow = new Anchor();
            arrow.setLeft(Value.of(arrowLeft));
            if (pointUp) arrow.setTop(Value.of(-8));
            else arrow.setBottom(Value.of(-8));
            arrow.setWidth(Value.of(33));
            arrow.setHeight(Value.of(24));
            commands.setObject(HOST + "#StorageTooltipArrow.Anchor", arrow);
            for(var entry:Map.of("#StorageTooltipHeader",headerHeight,"#StorageTooltipId",idHeight,"#StorageTooltipDescriptionGroup",descriptionHeight,"#StorageTooltipStatsGroup",statsHeight).entrySet()) {
                var a=new Anchor();a.setHeight(Value.of(entry.getValue()));commands.setObject(HOST+entry.getKey()+".Anchor",a);
            }
            geometry=nextGeometry;
        }
        boolean contentChanged=!current.equals(mounted);
        if(moved || contentChanged) {
            commands.set(HOST+"#StorageTooltipArrow.Background",Value.ref("AdvancedEquivalency/StorageTooltip.ui",(pointUp?"ArrowUp":"Arrow")+current.skin));
        }
        if(contentChanged) {
            commands.set(HOST+"#StorageTooltipName.Text",current.name);commands.set(HOST+"#StorageTooltipId.Text",current.id);
            commands.set(HOST+"#StorageTooltipQuality.Text",current.quality);commands.set(HOST+"#StorageTooltipQuality.Visible",current.showQuality);
            commands.set(HOST+"#StorageTooltipDescription.Text",String.join("\n",descriptionLines));commands.set(HOST+"#StorageTooltipDescriptionGroup.Visible",!descriptionLines.isEmpty());
            commands.set(HOST+"#StorageTooltipStats.Text",String.join("\n",statLines));commands.set(HOST+"#StorageTooltipStatsGroup.Visible",!statLines.isEmpty());
            commands.set(HOST+"#StorageTooltip.Background",Value.ref("AdvancedEquivalency/StorageTooltip.ui","Body"+current.skin));
            commands.set(HOST+"#StorageTooltipName.Style",Value.ref("AdvancedEquivalency/StorageTooltip.ui","Name"+current.skin));
            mounted=current;
        }
        if(!visible) commands.set(HOST+"#StorageTooltip.Visible",true);
        visible=true;
    }
}
