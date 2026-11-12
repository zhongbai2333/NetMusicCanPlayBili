package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real settings and encrypted store files in temp directory; fabricated machine identity. No real credentials or machine API. */
@ResourceLock("ncpb-bili-static-state")
class EntryFunctionalClosureSettingsFieldsTest {
    @TempDir Path temporary;
    private final Map<String,Object> oldFields=new LinkedHashMap<>();private String oldSession,oldCookie;
    @BeforeEach void capture()throws Exception{for(String field:List.of("dolbyEnabled","dolbyJocEnabled","dolbyMaxObjectSources","stereoCrossfeed","userAgent","rotateUserAgent"))oldFields.put(field,BiliConfig.class.getField(field).get(null));oldSession=BiliApiClient.sessdata;oldCookie=BiliApiClient.webCookie;}
    @AfterEach void restore()throws Exception{for(var field:oldFields.entrySet())BiliConfig.class.getField(field.getKey()).set(null,field.getValue());BiliApiClient.sessdata=oldSession;BiliApiClient.webCookie=oldCookie;}
    @ParameterizedTest @ValueSource(strings={"dolbyEnabled","dolbyJocEnabled","dolbyMaxObjectSources","stereoCrossfeed","userAgent","rotateUserAgent"})
    void malformedIndividualFieldRebuildsPublicSettingsAndStillLoadsUntouchedEncryptedVault(String field)throws Exception{
        Path settings=temporary.resolve("settings.json"),vault=temporary.resolve("credentials.enc");
        var store=new BiliCredentialStore(vault,()->Optional.of("fixture-machine"),new SecureRandom());
        assertTrue(store.save(new BiliCredentialStore.Credentials("fixture-session","SESSDATA=fixture-session; bili_jct=fixture-csrf")).saved());byte[] originalVault=Files.readAllBytes(vault);
        JsonObject bad=new JsonObject();bad.addProperty("sessdata","legacy-secret");bad.addProperty("webCookie","SESSDATA=legacy-secret");bad.add(field,new JsonObject());Files.writeString(settings,bad.toString());
        assertDoesNotThrow(()->BiliConfig.load(settings,store,new BiliCredentialStore.Credentials("","")));
        assertEquals("fixture-session",BiliApiClient.sessdata);assertEquals("SESSDATA=fixture-session; bili_jct=fixture-csrf",BiliApiClient.webCookie);assertArrayEquals(originalVault,Files.readAllBytes(vault));
        String rebuilt=Files.readString(settings);JsonObject root=JsonParser.parseString(rebuilt).getAsJsonObject();
        assertFalse(root.has("sessdata"));assertFalse(root.has("webCookie"));assertFalse(rebuilt.contains("legacy-secret"));assertTrue(root.get(field).isJsonPrimitive());
        assertTrue(BiliConfig.save(settings));assertArrayEquals(originalVault,Files.readAllBytes(vault));assertFalse(Files.readString(settings).contains("fixture-session"));
    }
}
