package com.zhongbai233.net_music_can_play_bili.network;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static com.zhongbai233.net_music_can_play_bili.network.PacketCodecFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real packet codecs and real CSV chunk generation; no network/client file I/O. */
class WhitelistWireProductionTest {
    @Test void reviewActionPreservesMutationVersionOffsetsAndCorrelation(){
        var packet=new WhitelistReviewActionPacket(WhitelistReviewActionPacket.Action.COMMENT,"bili:BV1xx411c7mD",9876,"reason","version",32,64,999);
        assertEquals(packet,roundTrip(WhitelistReviewActionPacket.STREAM_CODEC,packet));
    }
    @Test void reviewEncoderBoundsStringsAndNormalizesNegativeFields(){
        var decoded=roundTrip(WhitelistReviewActionPacket.STREAM_CODEC,new WhitelistReviewActionPacket(WhitelistReviewActionPacket.Action.COMMENT,"x".repeat(600),-1," y ","v".repeat(200),-3,-4,-5));
        assertEquals(512,decoded.idOrLink().length());assertEquals("y",decoded.text());assertEquals(128,decoded.expectedAddedAt().length());
        assertEquals(0,decoded.targetMillis());assertEquals(0,decoded.entryOffset());assertEquals(0,decoded.removalOffset());assertEquals(0,decoded.requestId());
    }
    @Test void unknownActionWireIdRetainsLegacyRefreshFallback(){
        var buffer=buffer();try{buffer.writeVarInt(99);buffer.writeUtf("");buffer.writeVarLong(0);buffer.writeUtf("");buffer.writeUtf("");buffer.writeVarInt(0);buffer.writeVarInt(0);buffer.writeVarLong(0);
            assertEquals(WhitelistReviewActionPacket.Action.REFRESH,WhitelistReviewActionPacket.STREAM_CODEC.decode(buffer).action());}finally{buffer.release();}
    }
    @ParameterizedTest @ValueSource(ints={0,1,2}) void negativeReviewPageOrRequestIsRejected(int bad){
        var buffer=buffer();try{buffer.writeVarInt(0);buffer.writeUtf("");buffer.writeVarLong(0);buffer.writeUtf("");buffer.writeUtf("");
            buffer.writeVarInt(bad==0?-1:0);buffer.writeVarInt(bad==1?-1:0);buffer.writeVarLong(bad==2?-1:0);
            assertThrows(DecoderException.class,()->WhitelistReviewActionPacket.STREAM_CODEC.decode(buffer));}finally{buffer.release();}
    }
    @Test void mutationResultRetainsStatusTargetAndCorrelation(){
        var packet=new WhitelistReviewMutationResultPacket(WhitelistReviewMutationResultPacket.Mutation.COMMENT,
                WhitelistReviewMutationResultPacket.Status.STALE,"target","message",998);
        assertEquals(packet,roundTrip(WhitelistReviewMutationResultPacket.STREAM_CODEC,packet));
    }
    @ParameterizedTest @CsvSource({"-1,0","99,0","0,-1","0,99"}) void unknownResultEnumsRejectOnWire(int mutation,int status){
        var buffer=buffer();try{buffer.writeVarInt(mutation);buffer.writeVarInt(status);buffer.writeUtf("");buffer.writeUtf("");buffer.writeVarLong(0);
            assertThrows(DecoderException.class,()->WhitelistReviewMutationResultPacket.STREAM_CODEC.decode(buffer));}finally{buffer.release();}
    }
    @Test void actualReviewSnapshotCodecPreservesCommentsAndRemovalHistory(){
        var entry=new BiliWhitelistManager.Entry();entry.id="url:https://example.test/a";entry.type="url";entry.addedAt="v";
        var comment=new BiliWhitelistManager.ReviewComment();comment.text="comment";comment.authorName="reviewer";entry.comments.add(comment);
        var removal=new BiliWhitelistManager.RemovalRecord();removal.recordId="record";removal.entry=entry;removal.note="removed";
        var packet=WhitelistReviewPacket.create(List.of(entry),List.of(removal));assertEquals(packet,roundTrip(WhitelistReviewPacket.STREAM_CODEC,packet));
    }
    @Test void previewCodecPreservesSeekSessionAndNormalizesSizes(){
        var packet=new WhitelistPreviewPacket(new UUID(3,4),"title","raw","audio","video",0,-2,0,13,-9,-8,false,-7);
        var decoded=roundTrip(WhitelistPreviewPacket.STREAM_CODEC,packet);
        assertEquals(packet.previewId(),decoded.previewId());assertEquals(1,decoded.videoWidth());assertEquals(1,decoded.videoHeight());assertEquals(1,decoded.fps());
        assertEquals(13,decoded.codecId());assertEquals(0,decoded.durationSeconds());assertEquals(0,decoded.elapsedMillis());assertEquals(0,decoded.requestId());
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void previewEncoderRejectsOverlongTitleOrUrl(boolean url){
        var buffer=buffer();try{var packet=new WhitelistPreviewPacket(new UUID(3,4),url?"title":"x".repeat(257),url?"x".repeat(8193):"raw","audio","video",1,1,30,7,1,0,true,1);
            assertThrows(EncoderException.class,()->WhitelistPreviewPacket.STREAM_CODEC.encode(buffer,packet));}finally{buffer.release();}
    }
    @Test void previewDecoderRejectsNegativeCorrelationId(){
        var buffer=buffer();try{buffer.writeUUID(new UUID(3,4));buffer.writeUtf("title");buffer.writeUtf("raw");buffer.writeUtf("audio");buffer.writeUtf("video");
            for(int value:new int[]{1,1,30,7,60})buffer.writeVarInt(value);buffer.writeVarLong(0);buffer.writeBoolean(true);buffer.writeVarLong(-1);
            assertThrows(DecoderException.class,()->WhitelistPreviewPacket.STREAM_CODEC.decode(buffer));}finally{buffer.release();}
    }
    @ParameterizedTest @ValueSource(ints={0,1,65536,65537,131073}) void actualCsvChunksRoundTripAndReassembleExactly(int length) throws Exception {
        String csv="x".repeat(length);var chunks=WhitelistCsvExportPacket.createChunks(csv);var output=new java.io.ByteArrayOutputStream();
        assertEquals(Math.max(1,(length+65535)/65536),chunks.size());
        for(int i=0;i<chunks.size();i++){var packet=chunks.get(i);var decoded=roundTrip(WhitelistCsvExportPacket.STREAM_CODEC,packet);
            assertEquals(chunks.getFirst().transferId(),decoded.transferId());assertEquals(i,decoded.chunkIndex());assertEquals(chunks.size(),decoded.chunkCount());
            assertEquals(length,decoded.totalBytes());assertArrayEquals(packet.chunk(),decoded.chunk());output.write(decoded.chunk());}
        assertEquals(csv,output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void multibyteCsvChunkBoundariesPreserveUtf8Content() throws Exception {
        String csv="中,文\r\n".repeat(20000);var chunks=WhitelistCsvExportPacket.createChunks(csv);assertTrue(chunks.size()>1);
        var output=new java.io.ByteArrayOutputStream();for(var packet:chunks)output.write(roundTrip(WhitelistCsvExportPacket.STREAM_CODEC,packet).chunk());
        assertEquals(csv,output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @ParameterizedTest @CsvSource({"-1,1,1","1,1,1","0,0,1","0,1025,1","0,1,-1","0,1,67108865"})
    void invalidCsvTransferMetadataIsRejectedBeforeEncoding(int index,int count,int total){
        var buffer=buffer();try{var packet=new WhitelistCsvExportPacket("transfer","file.csv",index,count,total,new byte[1]);
            assertThrows(EncoderException.class,()->WhitelistCsvExportPacket.STREAM_CODEC.encode(buffer,packet));}finally{buffer.release();}
    }
    @Test void csvDecoderRejectsChunkExceedingTransportBound(){
        var buffer=buffer();try{buffer.writeUtf("transfer");buffer.writeUtf("file.csv");buffer.writeVarInt(0);buffer.writeVarInt(2);buffer.writeVarInt(65537);buffer.writeByteArray(new byte[65537]);
            assertThrows(DecoderException.class,()->WhitelistCsvExportPacket.STREAM_CODEC.decode(buffer));}finally{buffer.release();}
    }
    @Test void reportCodecPreservesKeyAndNormalizesNull(){assertEquals(new MediaToolReportPacket("key"),roundTrip(MediaToolReportPacket.STREAM_CODEC,new MediaToolReportPacket("key")));
        assertEquals("",roundTrip(MediaToolReportPacket.STREAM_CODEC,new MediaToolReportPacket(null)).sourceKey());}
}
