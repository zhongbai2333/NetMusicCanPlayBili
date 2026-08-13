package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleSnapshotBudget;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 完整权威中控台文档的有界网络编解码；仅供服务端冲突快照使用。 */
final class ControlConsoleDocumentPacketCodec {
    /** 场景快照 64 KiB 之外，为 ACL、源绑定和文档身份保留有界开销。 */
    private static final int MAX_ENCODED_BYTES = ControlConsoleSnapshotBudget.MAX_BYTES + 8 * 1024;
    private static final int MAX_DIMENSION_LENGTH = 256;

    private ControlConsoleDocumentPacketCodec() {
    }

    static ControlConsoleDocument decode(RegistryFriendlyByteBuf buf) {
        int startIndex = buf.readerIndex();
        int schemaVersion = buf.readVarInt();
        UUID consoleId = buf.readUUID();
        long revision = buf.readLong();
        UUID ownerId = buf.readBoolean() ? buf.readUUID() : null;
        ControlConsoleDocument.AccessMode accessMode = ControlConsoleDocument.AccessMode.parse(buf.readUtf(16));
        int trustedCount = buf.readVarInt();
        if (trustedCount < 0 || trustedCount > ControlConsoleDocument.MAX_TRUSTED_PLAYERS) {
            throw new IllegalArgumentException("invalid trusted player count: " + trustedCount);
        }
        Set<UUID> trustedPlayerIds = new LinkedHashSet<>();
        for (int i = 0; i < trustedCount; i++) {
            if (!trustedPlayerIds.add(buf.readUUID())) {
                throw new IllegalArgumentException("duplicate trusted player id");
            }
        }
        String displayName = buf.readUtf(64);
        String sourceDimension = null;
        ControlConsoleDocument.SourceKind sourceKind = null;
        int sourceX = 0;
        int sourceY = 0;
        int sourceZ = 0;
        if (buf.readBoolean()) {
            sourceDimension = buf.readUtf(MAX_DIMENSION_LENGTH);
            sourceKind = parseSourceKind(buf.readUtf(24));
            sourceX = buf.readInt();
            sourceY = buf.readInt();
            sourceZ = buf.readInt();
        }
        double hardRangeX = buf.readDouble();
        double hardRangeY = buf.readDouble();
        double hardRangeZ = buf.readDouble();
        int elementCount = buf.readVarInt();
        if (elementCount < 0 || elementCount > ControlConsoleDocument.MAX_ELEMENTS) {
            throw new IllegalArgumentException("invalid control console element count: " + elementCount);
        }
        List<ControlConsoleElement> elements = new ArrayList<>(elementCount);
        Set<UUID> elementIds = new HashSet<>();
        for (int i = 0; i < elementCount; i++) {
            ControlConsoleElement element = ControlConsoleConfigPacket.readElement(buf);
            if (!elementIds.add(element.elementId())) {
                throw new IllegalArgumentException("duplicate elementId");
            }
            elements.add(element);
            requireWithinLimit(buf.readerIndex() - startIndex);
        }
        requireWithinLimit(buf.readerIndex() - startIndex);
        ControlConsoleSnapshotBudget.requireWithinLimit(displayName, elements);
        return new ControlConsoleDocument(schemaVersion, consoleId, revision, ownerId, accessMode, trustedPlayerIds,
                displayName, sourceDimension, sourceKind, sourceX, sourceY, sourceZ,
                hardRangeX, hardRangeY, hardRangeZ, List.copyOf(elements));
    }

    static void encode(RegistryFriendlyByteBuf buf, ControlConsoleDocument document) {
        ControlConsoleSnapshotBudget.requireWithinLimit(document.displayName(), document.elements());
        int startIndex = buf.writerIndex();
        buf.writeVarInt(document.schemaVersion());
        buf.writeUUID(document.consoleId());
        buf.writeLong(document.revision());
        buf.writeBoolean(document.ownerId() != null);
        if (document.ownerId() != null) {
            buf.writeUUID(document.ownerId());
        }
        buf.writeUtf(document.accessMode().name(), 16);
        buf.writeVarInt(document.trustedPlayerIds().size());
        document.trustedPlayerIds().forEach(buf::writeUUID);
        buf.writeUtf(document.displayName(), 64);
        buf.writeBoolean(document.hasSourceBinding());
        if (document.hasSourceBinding()) {
            buf.writeUtf(document.sourceDimension(), MAX_DIMENSION_LENGTH);
            buf.writeUtf(document.sourceKind().name(), 24);
            buf.writeInt(document.sourceX());
            buf.writeInt(document.sourceY());
            buf.writeInt(document.sourceZ());
        }
        buf.writeDouble(document.hardRangeX());
        buf.writeDouble(document.hardRangeY());
        buf.writeDouble(document.hardRangeZ());
        buf.writeVarInt(document.elements().size());
        for (ControlConsoleElement element : document.elements()) {
            ControlConsoleConfigPacket.writeElement(buf, element);
            requireWithinLimit(buf.writerIndex() - startIndex);
        }
        requireWithinLimit(buf.writerIndex() - startIndex);
    }

    private static ControlConsoleDocument.SourceKind parseSourceKind(String value) {
        try {
            return ControlConsoleDocument.SourceKind.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unsupported control console source kind: " + value, invalid);
        }
    }

    private static void requireWithinLimit(int encodedBytes) {
        if (encodedBytes > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("authoritative control console document exceeds transport limit");
        }
    }
}
