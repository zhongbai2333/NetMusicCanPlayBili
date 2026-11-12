package com.zhongbai233.net_music_can_play_bili;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import com.zhongbai233.net_music_can_play_bili.testsupport.DeviceAdapterBootstrap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import java.io.InputStreamReader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real resource files; eight registered-output recipes use real vanilla codec/crafting input. No FML resource reload or creative event. */
class ModResourcesTest {
    private static final Path RES=Path.of("src/main/resources");
    private Map<TagKey<Item>,List<Holder<Item>>> previousTags;
    @BeforeAll static void bootstrap(){DeviceAdapterBootstrap.initialize();}
    @BeforeEach void attachGenuineVanillaIngredientTagFiles()throws Exception{
        previousTags=new LinkedHashMap<>();BuiltInRegistries.ITEM.getTags().filter(t->t.isBound()).forEach(t->previousTags.put(t.key(),t.stream().toList()));
        var tags=new LinkedHashMap<>(previousTags);
        for(String name:List.of("planks","wool")){
            String path="data/minecraft/tags/item/"+name+".json";
            try(var stream=ModResourcesTest.class.getClassLoader().getResourceAsStream(path)){
                assertNotNull(stream,"real vanilla tag resource "+path);
                JsonArray values=JsonParser.parseReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("values");
                List<Holder<Item>> holders=new ArrayList<>();for(JsonElement value:values)holders.add(BuiltInRegistries.ITEM.get(Identifier.parse(value.getAsString())).orElseThrow());
                tags.put(TagKey.create(Registries.ITEM,Identifier.parse("minecraft:"+name)),holders);
            }
        }
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM,tags)).apply();
    }
    @AfterEach void restoreOriginalTags(){if(previousTags!=null)BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM,previousTags)).apply();}
    private JsonObject resource(String name)throws Exception{return JsonParser.parseString(Files.readString(RES.resolve(name))).getAsJsonObject();}
    @ParameterizedTest @CsvSource({"mp4,1","pad,1","modern_turntable,1","lyric_projector,1","video_projector,1","speaker,2","live_streamer,1","media_management_tool,1"})
    void recipesDecodeAndCraftTheirDeclaredOutputs(String name,int expectedCount)throws Exception{
        JsonObject json=resource("data/net_music_can_play_bili/recipe/"+name+".json");
        var ops=RegistryOps.create(JsonOps.INSTANCE,PlatformBootstrap.registries());
        ShapedRecipe recipe=ShapedRecipe.MAP_CODEC.codec().parse(ops,json).getOrThrow();
        List<ItemStack> stacks=new ArrayList<>();
        for(var ingredient:recipe.getIngredients())stacks.add(ingredient.map(i->new ItemStack(i.items().findFirst().orElseThrow())).orElse(ItemStack.EMPTY));
        CraftingInput input=CraftingInput.of(recipe.getWidth(),recipe.getHeight(),stacks);
        List<Character> pattern=new ArrayList<>();for(JsonElement row:json.getAsJsonArray("pattern"))for(char c:row.getAsString().toCharArray())pattern.add(c);
        for(int slot=0;slot<pattern.size();slot++){
            char symbol=pattern.get(slot);if(symbol==' '||!json.getAsJsonObject("key").get(String.valueOf(symbol)).getAsString().startsWith("#"))continue;
            for(var member:recipe.getIngredients().get(slot).orElseThrow().items().toList()){
                List<ItemStack> alternate=new ArrayList<>(stacks);alternate.set(slot,new ItemStack(member));
                assertTrue(recipe.matches(CraftingInput.of(recipe.getWidth(),recipe.getHeight(),alternate),null),"tag member "+member+" in slot "+slot);
            }
        }
        assertTrue(recipe.matches(input,null));
        ItemStack result=recipe.assemble(input);
        assertEquals("net_music_can_play_bili:"+name,BuiltInRegistries.ITEM.getKey(result.getItem()).toString());assertEquals(expectedCount,result.getCount());
    }
    @Test void everyRecipeResultNamesADeclaredItemAndEveryPatternSymbolHasOneIngredient()throws Exception{
        Set<String> declared=ModItems.ITEMS.getEntries().stream().map(e->e.getId().toString()).collect(java.util.stream.Collectors.toSet());
        try(var paths=Files.list(RES.resolve("data/net_music_can_play_bili/recipe"))){
            List<Path> files=paths.filter(p->p.toString().endsWith(".json")).toList();assertFalse(files.isEmpty());
            for(Path file:files){JsonObject r=JsonParser.parseString(Files.readString(file)).getAsJsonObject();assertEquals("minecraft:crafting_shaped",r.get("type").getAsString(),file.toString());
                assertTrue(declared.contains(r.getAsJsonObject("result").get("id").getAsString()),file.toString());
                Set<String> used=new HashSet<>();for(JsonElement row:r.getAsJsonArray("pattern")){for(char c:row.getAsString().toCharArray())if(c!=' ')used.add(String.valueOf(c));}
                assertEquals(used,r.getAsJsonObject("key").keySet(),file.toString());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"headphones","holographic_glasses"})
    void enchantmentHeadSlotSupportedTagAttributeAndTranslationResolveWithinResources(String name)throws Exception{
        JsonObject e=resource("data/net_music_can_play_bili/enchantment/"+name+".json");
        assertEquals(1,e.get("max_level").getAsInt());assertEquals("head",e.getAsJsonArray("slots").get(0).getAsString());
        assertEquals("#net_music_can_play_bili:enchantable/"+name,e.get("supported_items").getAsString());
        JsonObject tag=resource("data/net_music_can_play_bili/tags/item/enchantable/"+name+".json");
        assertEquals("#minecraft:enchantable/head_armor",tag.getAsJsonArray("values").get(0).getAsString());
        JsonObject attribute=e.getAsJsonObject("effects").getAsJsonArray("minecraft:attributes").get(0).getAsJsonObject();
        assertEquals("net_music_can_play_bili:"+name,attribute.get("attribute").getAsString());assertEquals("add_value",attribute.get("operation").getAsString());
        String translation=e.getAsJsonObject("description").get("translate").getAsString();
        assertTrue(resource("assets/net_music_can_play_bili/lang/zh_cn.json").has(translation));
        assertTrue(resource("assets/net_music_can_play_bili/lang/en_us.json").has(translation));
        for(String path:List.of("in_enchanting_table","non_treasure")){
            var values=resource("data/minecraft/tags/enchantment/"+path+".json").getAsJsonArray("values");
            assertTrue(values.asList().stream().anyMatch(v->v.getAsString().equals("net_music_can_play_bili:"+name)));
        }
    }
}
