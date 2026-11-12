package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual save/Transfer/file cleanup/atomic replacement. Minecraft delivery and delayed timer are boundaries. */
class ProviderFunctionalClosureCsvTest {
    @TempDir Path directory;
    Minecraft minecraft;
    MockedStatic<Minecraft> access;
    MockedStatic<CompletableFuture> timers;
    List<Runnable> scheduled = new ArrayList<>();
    Map<String,Object> transfers;
    @BeforeAll static void bootstrap(){ PlatformBootstrap.initialize(); }
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        minecraft=mock(Minecraft.class);
        var game=Minecraft.class.getDeclaredField("gameDirectory");game.setAccessible(true);game.set(minecraft,directory.toFile());
        minecraft.player=mock(LocalPlayer.class);
        access=mockStatic(Minecraft.class);access.when(Minecraft::getInstance).thenReturn(minecraft);
        timers=mockStatic(CompletableFuture.class,CALLS_REAL_METHODS);
        timers.when(()->CompletableFuture.delayedExecutor(anyLong(),any(TimeUnit.class)))
            .thenReturn((Executor)scheduled::add);
        var field=WhitelistCsvExportClient.class.getDeclaredField("TRANSFERS");field.setAccessible(true);
        transfers=(Map<String,Object>)field.get(null);assertTrue(transfers.isEmpty());
    }
    @AfterEach void cleanup() throws Exception {
        for(var entry:List.copyOf(transfers.entrySet())) invoke("discard",new Class[]{String.class,entry.getValue().getClass()},entry.getKey(),entry.getValue());
        timers.close();access.close();
    }
    Path exports(){ return directory.resolve("exports/net_music_can_play_bili"); }
    WhitelistCsvExportPacket packet(String id,String file,int index,int count,int total,byte[] bytes){
        return new WhitelistCsvExportPacket(id,file,index,count,total,bytes);
    }
    void save(String id,int index,int count,int total,byte[] bytes){WhitelistCsvExportClient.save(packet(id,"result.csv",index,count,total,bytes));}
    @Test void overCapacityRejectsBeforeOpeningTemporaryFile() {
        save("oversize",0,1024,WhitelistCsvExportPacket.MAX_TOTAL_BYTES+1,new byte[]{1});
        assertTrue(transfers.isEmpty());assertFalse(Files.exists(exports()));
        verify(minecraft.player).sendSystemMessage(argThat(message->message.getString().contains("失败")));
    }
    @Test void abandonedCleanupOnlyDeletesExpiredOwnedPartsAndSanitizesTargetAndUtf8Bytes() throws Exception {
        Files.createDirectories(exports());var old=exports().resolve(".ncpb-whitelist-abandoned.part");Files.writeString(old,"old");
        Files.setLastModifiedTime(old,FileTime.fromMillis(System.currentTimeMillis()-120001));
        var fresh=exports().resolve(".ncpb-whitelist-fresh.part");Files.writeString(fresh,"fresh");
        var user=exports().resolve("user.csv");Files.writeString(user,"user");
        byte[] utf8="中文,标题\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String malicious="../escape.csv";
        WhitelistCsvExportClient.save(packet("utf8",malicious,0,2,utf8.length,Arrays.copyOfRange(utf8,0,2)));
        WhitelistCsvExportClient.save(packet("utf8",malicious,1,2,utf8.length,Arrays.copyOfRange(utf8,2,utf8.length)));
        assertArrayEquals(utf8,Files.readAllBytes(exports().resolve(".._escape.csv")));
        assertFalse(Files.exists(old));assertTrue(Files.exists(fresh));assertEquals("user",Files.readString(user));
        assertFalse(Files.exists(directory.resolve("exports/escape.csv")));
    }
    static Object invoke(String name,Class<?>[] types,Object...arguments) throws Exception {
        Method method=WhitelistCsvExportClient.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,arguments);
    }
}
