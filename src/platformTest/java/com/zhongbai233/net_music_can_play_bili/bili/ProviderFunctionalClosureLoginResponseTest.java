package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual QR manager/HTTP scope/parser using fabricated responses, no account, machine identity, credential reads or socket. */
class ProviderFunctionalClosureLoginResponseTest {
    @TempDir Path directory;
    HttpClient client;BiliLoginManager manager;int status;String body;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        client=mock(HttpClient.class);status=200;body="{}";
        when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(i->{
            HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
            return CompletableFuture.completedFuture(response);
        });
        manager=new BiliLoginManager(client,URI.create("https://fixture.invalid/generate"),"https://fixture.invalid/poll",
            new BiliCredentialStore(directory.resolve("unused.enc"),Optional::empty,new SecureRandom()));
    }
    @AfterEach void cleanup(){manager.close();assertEquals(0,manager.activeRequestCount());assertFalse(Files.exists(directory.resolve("unused.enc")));}
    @ParameterizedTest @ValueSource(strings={"not json","{\"code\":1}"})
    void malformedGenerationFailsWithoutPublishingLoginOrPersisting(String malformed){
        body=malformed;assertEquals(BiliLoginManager.State.FAILED,manager.generate().join());assertNull(manager.getQrUrl());assertEquals(0,manager.activeRequestCount());
    }
    @Test void blankSuccessfulKeyCannotPublishQrGeneration(){
        body="{\"code\":0,\"data\":{\"qrcode_key\":\" \",\"url\":\"https://fixture.invalid/qr\"}}";assertEquals(BiliLoginManager.State.FAILED,manager.generate().join());assertNull(manager.getQrcodeKey());assertNull(manager.getQrUrl());
        assertEquals(BiliLoginManager.State.FAILED,manager.poll().join());
    }
    @Test void failedPartialGenerationDoesNotPublishKeyThatCouldStartAnUnusablePoll(){
        body="{\"code\":0,\"data\":{\"qrcode_key\":\"fabricated-key\"}}";
        assertEquals(BiliLoginManager.State.FAILED,manager.generate().join());assertNull(manager.getQrcodeKey());assertEquals(BiliLoginManager.State.FAILED,manager.poll().join());
        verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
    @Test void unsuccessfulGenerationHttpStatusCannotPublishOtherwiseValidBody(){
        status=503;body="{\"code\":0,\"data\":{\"qrcode_key\":\"fabricated-key\",\"url\":\"https://fixture.invalid/qr\"}}";
        assertEquals(BiliLoginManager.State.FAILED,manager.generate().join());assertNull(manager.getQrcodeKey());assertNull(manager.getQrUrl());
    }
    @Test void malformedPollRemainsRetryableAndKeepsNoActiveRequest(){
        body="{\"code\":0,\"data\":{\"qrcode_key\":\"fabricated-key\",\"url\":\"https://fixture.invalid/qr\"}}";
        assertEquals(BiliLoginManager.State.PENDING,manager.generate().join());body="not json";
        assertEquals(BiliLoginManager.State.PENDING,manager.poll().join());assertEquals(0,manager.activeRequestCount());
        body="{\"code\":0,\"data\":{\"code\":86038}}";assertEquals(BiliLoginManager.State.EXPIRED,manager.poll().join());
    }
    @Test void unsuccessfulPollHttpStatusCannotPublishScannedBodyAndRemainsRetryable(){
        body="{\"code\":0,\"data\":{\"qrcode_key\":\"fabricated-key\",\"url\":\"https://fixture.invalid/qr\"}}";
        assertEquals(BiliLoginManager.State.PENDING,manager.generate().join());
        status=503;body="{\"code\":0,\"data\":{\"code\":86090}}";
        assertEquals(BiliLoginManager.State.PENDING,manager.poll().join());assertEquals(0,manager.activeRequestCount());
        assertTrue("fabricated-key".equals(manager.getQrcodeKey()),"Recoverable transport failure keeps current fabricated generation");
        status=200;body="{\"code\":0,\"data\":{\"code\":86090}}";
        assertEquals(BiliLoginManager.State.SCANNED,manager.poll().join());
    }
    @Test void nonzeroOuterPollCodeCannotPublishScannedInnerCodeAndNextValidPollRecovers(){
        body="{\"code\":0,\"data\":{\"qrcode_key\":\"fabricated-key\",\"url\":\"https://fixture.invalid/qr\"}}";
        assertEquals(BiliLoginManager.State.PENDING,manager.generate().join());
        body="{\"code\":-101,\"data\":{\"code\":86090}}";
        assertEquals(BiliLoginManager.State.PENDING,manager.poll().join());assertEquals(0,manager.activeRequestCount());
        body="{\"code\":0,\"data\":{\"code\":86090}}";assertEquals(BiliLoginManager.State.SCANNED,manager.poll().join());
    }
    @Test void successCodeWithoutSessionCookieCannotMarkLoginSuccessful(){
        body="{\"code\":0,\"data\":{\"qrcode_key\":\"fabricated-key\",\"url\":\"https://fixture.invalid/qr\"}}";manager.generate().join();
        body="{\"code\":0,\"data\":{\"code\":0}}";assertEquals(BiliLoginManager.State.FAILED,manager.poll().join());
    }
}
