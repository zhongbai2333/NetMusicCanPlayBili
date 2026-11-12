package com.zhongbai233.net_music_can_play_bili.bili;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpRequestScope;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real b23.tv short-link resolution; HTTP transport and header identity are explicit boundaries. */
class BiliProviderAdaptersProductionTest {
    MockedStatic<CancellableHttpRequestScope> http; MockedStatic<BiliRequestHeaders> headers;
    List<HttpRequest> requests=new ArrayList<>(); List<String> requestKinds=new ArrayList<>();
    @BeforeEach void setup() {
        headers=mockStatic(BiliRequestHeaders.class,CALLS_REAL_METHODS);
        headers.when(BiliRequestHeaders::userAgent).thenReturn("provider-fixture-UA");
        http=mockStatic(CancellableHttpRequestScope.class);
    }
    @AfterEach void cleanup() { http.close(); headers.close(); }
    @ParameterizedTest @ValueSource(strings={"https://outside.test/path","http://www.bilibili.com/video/BV1xx411c7mD","https://user@www.bilibili.com/video/BV1xx411c7mD","https://www.bilibili.com:444/video/BV1xx411c7mD"})
    void actualShortLinkTransportNeverFollowsUntrustedRedirect(String target) {
        shortTransport(target,false);assertNull(BiliApiClient.extractVideoSelectionLenientWithShortLink("https://b23.tv/test"));assertEquals(1,requests.size());
    }
    @Test void actualShortLinkTransportBuildsPublicHeadersAtEveryTrustedHopAndKeepsPage() {
        shortTransport("https://www.bilibili.com/video/BV1xx411c7mD?p=2",true);
        var selection=BiliApiClient.extractVideoSelectionLenientWithShortLink("https://b23.tv/test");assertNotNull(selection);assertEquals(2,selection.page());
        assertEquals(2,requests.size());for(var request:requests){assertEquals("GET",request.method());assertTrue(request.timeout().orElseThrow().toMillis()<=5000);
            assertEquals("provider-fixture-UA",request.headers().firstValue("User-Agent").orElseThrow());assertEquals(BiliRequestHeaders.WEB_REFERER,request.headers().firstValue("Referer").orElseThrow());assertTrue(request.headers().firstValue("Cookie").isEmpty());}
    }
    void shortTransport(String target,boolean success) {
        http.when(()->CancellableHttpRequestScope.sendOneBlocking(any(),any(),any(),any(),anyString())).thenAnswer(invocation->{
            HttpRequest request=invocation.getArgument(1);requests.add(request);requestKinds.add(invocation.getArgument(4));
            @SuppressWarnings("unchecked") HttpResponse<Void> response=mock(HttpResponse.class);
            boolean first=request.uri().getHost().equals("b23.tv");when(response.statusCode()).thenReturn(first?302:200);
            when(response.headers()).thenReturn(HttpHeaders.of(first?Map.of("Location",List.of(target)):Map.of(),(k,v)->true));return response;
        });
        requests.clear();requestKinds.clear();
    }
}
