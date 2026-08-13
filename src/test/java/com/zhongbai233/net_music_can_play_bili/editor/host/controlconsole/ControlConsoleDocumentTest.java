package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleOperation;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleRangeMigration;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.ControlConsoleHostAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleDocumentTest {
    @Test
    void schemaV6NormalizesAnglesAndValidatesAdvancedTransformDomain() {
        ControlConsoleElement element = advancedElement(540.0F, 2.0F, 0.5F);
        assertEquals(-180.0F, element.yaw());
        assertEquals(2.0F, element.scaleX());
        assertEquals(0.5F, element.skewXByY());

        assertThrows(IllegalArgumentException.class, () -> advancedElement(0.0F, 0.049F, 0.0F));
        assertThrows(IllegalArgumentException.class, () -> advancedElement(0.0F, 1.0F, 1.01F));
    }

    private static ControlConsoleElement advancedElement(float yaw, float scaleX, float skewXByY) {
        return new ControlConsoleElement(java.util.UUID.randomUUID(), ControlConsoleElement.Type.SCREEN,
                "高级屏幕", 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, yaw, 0.0F, 0.0F,
                "SOURCE", "", false, false, 1.0F, 0xFFFFFFFF, 1.0F, 0, 32.0F, false,
                ControlConsoleElement.DEFAULT_TRANSLATION_COLOR,
                ControlConsoleElement.DEFAULT_BACKGROUND_COLOR, ControlConsoleElement.Alignment.CENTER,
                0.0F, false, true, false, scaleX, 1.0F, 1.0F,
                0.0F, 0.0F, 0.0F, skewXByY, 0.0F);
    }
    @Test
    void emptyDocumentHasSafeRectangularRangeAndNoSource() {
        ControlConsoleDocument document = ControlConsoleDocument.empty();

        assertEquals(ControlConsoleDocument.CURRENT_SCHEMA_VERSION, document.schemaVersion());
        assertEquals(0L, document.revision());
        assertFalse(document.hasSourceBinding());
        assertEquals(ControlConsoleDocument.DEFAULT_HARD_RANGE_X, document.hardRangeX());
        assertEquals(ControlConsoleDocument.DEFAULT_HARD_RANGE_Y, document.hardRangeY());
        assertEquals(ControlConsoleDocument.DEFAULT_HARD_RANGE_Z, document.hardRangeZ());
                assertEquals(1, document.elements().size());
                assertEquals("主屏幕", document.elements().getFirst().name());
    }

        @Test
        void onlyPristineLegacyEmptyDocumentReceivesInitialScreen() {
                ControlConsoleDocument pristine = new ControlConsoleDocument(
                                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L, "中控台", null,
                                0, 0, 0, ControlConsoleDocument.DEFAULT_HARD_RANGE_X,
                                ControlConsoleDocument.DEFAULT_HARD_RANGE_Y, ControlConsoleDocument.DEFAULT_HARD_RANGE_Z);
                assertEquals(1, pristine.withInitialScreenIfPristine().elements().size());

                ControlConsoleDocument intentionallyEmpty = pristine.withRevision(1L);
                assertEquals(0, intentionallyEmpty.withInitialScreenIfPristine().elements().size());
        }

        @Test
        void schemaV5MigratesOnlyThePersistedV4LegacyDefaultRange() {
                var migrated = ControlConsoleRangeMigration.migrate(4, 8.0D, 4.0D, 8.0D);
                assertEquals(ControlConsoleDocument.DEFAULT_HARD_RANGE_X, migrated.x());
                assertEquals(ControlConsoleDocument.DEFAULT_HARD_RANGE_Y, migrated.y());
                assertEquals(ControlConsoleDocument.DEFAULT_HARD_RANGE_Z, migrated.z());

                var customizedV4 = ControlConsoleRangeMigration.migrate(4, 8.0D, 6.0D, 8.0D);
                assertEquals(8.0D, customizedV4.x());
                assertEquals(6.0D, customizedV4.y());
                assertEquals(8.0D, customizedV4.z());

                var intentionalV5 = ControlConsoleRangeMigration.migrate(5, 8.0D, 4.0D, 8.0D);
                assertEquals(8.0D, intentionalV5.x());
                assertEquals(4.0D, intentionalV5.y());
                assertEquals(8.0D, intentionalV5.z());
        }

    @Test
    void revisionUpdatePreservesBusinessConfiguration() {
        ControlConsoleElement element = element("主屏幕");
        ControlConsoleDocument document = new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 3L, "主控台", "minecraft:overworld",
                10, 64, -4, 12.0D, 5.0D, 9.0D, List.of(element));

        ControlConsoleDocument revised = document.withRevision(4L);

        assertEquals(4L, revised.revision());
        assertEquals(document.displayName(), revised.displayName());
        assertEquals(document.sourceDimension(), revised.sourceDimension());
        assertEquals(document.hardRangeZ(), revised.hardRangeZ());
        assertEquals(List.of(element), revised.elements());
        assertTrue(revised.hasSourceBinding());
        assertEquals(document.consoleId(), revised.consoleId());
        assertEquals(document.elements().getFirst().elementId(), revised.elements().getFirst().elementId());
    }

    @Test
    void schemaV4RequiresStableUniqueIdentityAndExplicitSourceKind() {
        UUID consoleId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        ControlConsoleElement element = new ControlConsoleElement(elementId,
                ControlConsoleElement.Type.SCREEN, "主屏幕", 0.0F, 0.0F, -1.05F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        ControlConsoleDocument document = new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, consoleId, 0L, null,
                ControlConsoleDocument.AccessMode.OWNER_ONLY, Set.of(), "中控台", "minecraft:overworld",
                ControlConsoleDocument.SourceKind.LIVE_STREAMER, 1, 2, 3,
                8.0D, 4.0D, 8.0D, List.of(element));

        assertEquals(consoleId, document.consoleId());
        assertEquals(elementId, document.elements().getFirst().elementId());
        assertEquals(ControlConsoleDocument.SourceKind.LIVE_STREAMER, document.sourceKind());
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, consoleId, 0L, null,
                ControlConsoleDocument.AccessMode.OWNER_ONLY, Set.of(), "中控台", "minecraft:overworld",
                null, 1, 2, 3, 8.0D, 4.0D, 8.0D, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, consoleId, 0L, null,
                ControlConsoleDocument.AccessMode.OWNER_ONLY, Set.of(), "中控台", null,
                null, 0, 0, 0, 8.0D, 4.0D, 8.0D, List.of(element, element)));
    }

        @Test
        void accessModesApplyOwnerTrustedPublicAndAdministratorRules() {
                UUID owner = UUID.randomUUID();
                UUID trusted = UUID.randomUUID();
                UUID stranger = UUID.randomUUID();
                ControlConsoleDocument ownerOnly = documentWithAcl(owner,
                                ControlConsoleDocument.AccessMode.OWNER_ONLY, Set.of(trusted));
                ControlConsoleDocument trustedMode = documentWithAcl(owner,
                                ControlConsoleDocument.AccessMode.TRUSTED, Set.of(trusted));
                ControlConsoleDocument publicMode = documentWithAcl(owner,
                                ControlConsoleDocument.AccessMode.PUBLIC_EDIT, Set.of());

                assertTrue(ownerOnly.canEdit(owner, false));
                assertFalse(ownerOnly.canEdit(trusted, false));
                assertTrue(trustedMode.canEdit(trusted, false));
                assertFalse(trustedMode.canEdit(stranger, false));
                assertTrue(publicMode.canEdit(stranger, false));
                assertTrue(ownerOnly.canEdit(stranger, true));
                assertFalse(ControlConsoleDocument.empty().canEdit(stranger, false));
        }

        @Test
        void claimingUnownedDocumentPreservesSceneAndRevision() {
                UUID owner = UUID.randomUUID();
                ControlConsoleDocument original = ControlConsoleDocument.empty().withRevision(7L);

                ControlConsoleDocument claimed = original.withOwnerIfAbsent(owner);

                assertEquals(owner, claimed.ownerId());
                assertEquals(7L, claimed.revision());
                assertEquals(original.elements(), claimed.elements());
                assertEquals(claimed, claimed.withOwnerIfAbsent(UUID.randomUUID()));
        }

        @Test
        void accessControlUpdateAdvancesRevisionAndPreservesOwnerAndScene() {
                UUID owner = UUID.randomUUID();
                UUID trusted = UUID.randomUUID();
                ControlConsoleDocument original = documentWithAcl(owner,
                                ControlConsoleDocument.AccessMode.OWNER_ONLY, Set.of()).withRevision(4L);

                ControlConsoleDocument updated = original.withAccessControl(
                                ControlConsoleDocument.AccessMode.TRUSTED, Set.of(trusted));

                assertEquals(5L, updated.revision());
                assertEquals(owner, updated.ownerId());
                assertEquals(Set.of(trusted), updated.trustedPlayerIds());
                assertEquals(original.elements(), updated.elements());
        }

    @Test
        void currentSchemaDefensivelyCopiesAndLimitsElements() {
        List<ControlConsoleElement> mutable = new ArrayList<>();
        mutable.add(element("主屏幕"));
        ControlConsoleDocument document = new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L, "中控台", null,
                0, 0, 0, 8.0D, 4.0D, 8.0D, mutable);
        mutable.clear();

        assertEquals(1, document.elements().size());
        assertThrows(UnsupportedOperationException.class, () -> document.elements().clear());

        List<ControlConsoleElement> tooMany = new ArrayList<>();
        for (int i = 0; i <= ControlConsoleDocument.MAX_ELEMENTS; i++) {
            tooMany.add(element("屏幕 " + i));
        }
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L, "中控台", null,
                0, 0, 0, 8.0D, 4.0D, 8.0D, tooMany));
    }

        @Test
        void currentSchemaDoesNotImposeProductLimitsPerElementType() {
                List<ControlConsoleElement> elements = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                        elements.add(element("屏幕 " + i));
                }
                assertEquals(40, new ControlConsoleDocument(
                                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L, "中控台", null,
                                0, 0, 0, 8.0D, 4.0D, 8.0D, elements).elements().size());
        }

    @Test
    void elementRejectsInvalidTypeNameNumbersAndDimensions() {
        assertEquals(ControlConsoleElement.Type.SUBTITLE, ControlConsoleElement.Type.parse("subtitle"));
        assertThrows(IllegalArgumentException.class, () -> ControlConsoleElement.Type.parse("camera"));
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleElement(
                ControlConsoleElement.Type.SCREEN, "", 2.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F));
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleElement(
                ControlConsoleElement.Type.SCREEN, "坏位置", Float.NaN, 0.0F, 0.0F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F));
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleElement(
                ControlConsoleElement.Type.SCREEN, "坏尺寸", 2.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F));
    }

    @Test
    void finiteTransformsAndPositiveDimensionsHaveNoProductMaximum() {
        ControlConsoleElement element = new ControlConsoleElement(ControlConsoleElement.Type.SCREEN, "无限制屏幕",
                50_000.0F, -60_000.0F, 70_000.0F,
                10_000.0F, 0.25F, 7_200.0F, -9_000.0F, 12_345.0F);

        assertEquals(50_000.0F, element.distance());
        assertEquals(0.25F, element.aspect());
        assertEquals(0.0F, element.pitch());

        ControlConsoleDocument document = new ControlConsoleDocument(
                ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L, "无限制中控台", null,
                0, 0, 0, 100_000.0D, 200_000.0D, 300_000.0D, List.of(element));
        assertEquals(300_000.0D, document.hardRangeZ());
    }

    @Test
    void derivedWidthMustRemainFinite() {
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleElement(
                ControlConsoleElement.Type.SCREEN, "溢出屏幕", 0.0F, 0.0F, 0.0F,
                Float.MAX_VALUE, 2.0F, 0.0F, 0.0F, 0.0F));
    }

    @Test
    void legacyElementDefaultsContentAndNewFieldsAreValidated() {
        ControlConsoleElement legacy = element("旧屏幕");
        assertEquals("SOURCE", legacy.contentMode());
        assertEquals(1.0F, legacy.textScale());
        assertFalse(legacy.autoMixJoc());
        assertTrue(legacy.enabled());

        ControlConsoleElement subtitle = new ControlConsoleElement(
                ControlConsoleElement.Type.SUBTITLE, "字幕", 2.0F, 0.0F, 0.0F,
                1.0F, 2.0F, 0.0F, 0.0F, 0.0F,
                "FIXED", "欢迎", false, false, 1.5F, 0xFF00FF00,
                1.0F, 0, 32.0F, false, true);
        assertEquals("欢迎", subtitle.text());
        assertEquals(0xFF00FF00, subtitle.color());
        ControlConsoleElement liveStatus = new ControlConsoleElement(
                ControlConsoleElement.Type.SUBTITLE, "直播状态", 2.0F, 0.0F, 0.0F,
                1.0F, 2.0F, 0.0F, 0.0F, 0.0F,
                "LIVE_STATUS", "", false, false, 1.0F, 0xFFFFFFFF,
                1.0F, 0, 32.0F, false, true);
        assertEquals("LIVE_STATUS", liveStatus.contentMode());
        ControlConsoleElement aiSubtitle = new ControlConsoleElement(
                ControlConsoleElement.Type.SUBTITLE, "AI字幕", 2.0F, 0.0F, 0.0F,
                1.0F, 2.0F, 0.0F, 0.0F, 0.0F,
                "AI_SUBTITLE", "暂无字幕", true, true, 1.0F, 0xFFFFFFFF,
                1.0F, 0, 32.0F, false, true);
        assertEquals("AI_SUBTITLE", aiSubtitle.contentMode());
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleElement(
                ControlConsoleElement.Type.AUDIO, "坏音源", 2.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F,
                "SOURCE", "", false, false, 1.0F, 0xFFFFFFFF,
                5.0F, 0, 32.0F, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ControlConsoleElement(
                ControlConsoleElement.Type.SCREEN, "坏模式", 2.0F, 0.0F, 0.0F,
                1.0F, 1.0F, 0.0F, 0.0F, 0.0F,
                "SCROLL_MAIN", "", false, false, 1.0F, 0xFFFFFFFF,
                1.0F, 0, 32.0F, false, true));
    }

        @Test
        void sourceBindingCarriesDimensionAndCoordinates() {
                ControlConsoleDocument document = new ControlConsoleDocument(1, 1L, "直播中控",
                                "minecraft:the_nether", -12, 71, 35, 16.0D, 6.0D, 12.0D);

                assertTrue(document.hasSourceBinding());
                assertEquals("minecraft:the_nether", document.sourceDimension());
                assertEquals(-12, document.sourceX());
                assertEquals(71, document.sourceY());
                assertEquals(35, document.sourceZ());
                assertEquals(16.0D, document.hardRangeX());
        }

    @Test
    void rejectsInvalidSchemaRevisionNameAndRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlConsoleDocument(ControlConsoleDocument.CURRENT_SCHEMA_VERSION + 1,
                        0L, "中控台", null, 0, 0, 0, 8.0D, 4.0D, 8.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlConsoleDocument(1, -1L, "中控台", null, 0, 0, 0, 8.0D, 4.0D, 8.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlConsoleDocument(1, 0L, "", null, 0, 0, 0, 8.0D, 4.0D, 8.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlConsoleDocument(1, 0L, "中控台", null, 0, 0, 0, 0.0D, 4.0D, 8.0D));
    }

    @Test
    void hostAdapterLoadsDescribesAndSubmitsImmutableOperations() {
        ControlConsoleDocument document = ControlConsoleDocument.empty().withRevision(5L);
        AtomicReference<List<ControlConsoleOperation>> submitted = new AtomicReference<>();
        AtomicReference<ControlConsoleDocument> rendered = new AtomicReference<>();
        ControlConsoleHostAdapter adapter = new ControlConsoleHostAdapter(() -> document, submitted::set,
                (context, draft) -> rendered.set(draft));

        assertEquals(document, adapter.loadDocument());
        assertTrue(adapter.validateDraft(document).valid());
        List<com.zhongbai233.scene_editor.core.host.EditorHostAdapter.PropertyDescriptor>
                properties = new ArrayList<>();
        adapter.describeProperties(document, properties::add);
        assertEquals(List.of("displayName", "hardRangeX", "hardRangeY", "hardRangeZ"),
                properties.stream().map(property -> property.id()).toList());

        ControlConsoleOperation operation = new ControlConsoleOperation.ReplaceDocument(5L, document);
        adapter.submitOperations(List.of(operation));
        assertEquals(List.of(operation), submitted.get());
        assertThrows(UnsupportedOperationException.class,
                () -> submitted.get().add(operation));

        adapter.renderEnvironment(new Object(), document);
        assertEquals(document, rendered.get());
        assertThrows(IllegalArgumentException.class,
                () -> new ControlConsoleOperation.ReplaceDocument(4L, document));
    }

        private static ControlConsoleElement element(String name) {
                return new ControlConsoleElement(ControlConsoleElement.Type.SCREEN, name,
                                2.5F, 0.25F, -0.1F, 1.8F, 16.0F / 9.0F,
                                15.0F, -5.0F, 2.0F);
        }

        private static ControlConsoleDocument documentWithAcl(UUID owner,
                        ControlConsoleDocument.AccessMode accessMode, Set<UUID> trusted) {
                return new ControlConsoleDocument(ControlConsoleDocument.CURRENT_SCHEMA_VERSION, 0L,
                                owner, accessMode, trusted, "中控台", null, 0, 0, 0,
                                8.0D, 4.0D, 8.0D, List.of());
        }
}
