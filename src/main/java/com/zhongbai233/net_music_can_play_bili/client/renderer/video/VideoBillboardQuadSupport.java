package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ProjectorScreenBounds;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/** Camera/world-space quad construction and event model-view drawing. */
abstract class VideoBillboardQuadSupport extends VideoBillboardState {
    protected static PreviewQuad transformedLocalQuad(Matrix4f pose, float halfWidth, float halfHeight) {
        Vector4f p0 = new Vector4f(-halfWidth, halfHeight, 0.0F, 1.0F).mul(pose);
        Vector4f p1 = new Vector4f(-halfWidth, -halfHeight, 0.0F, 1.0F).mul(pose);
        Vector4f p2 = new Vector4f(halfWidth, -halfHeight, 0.0F, 1.0F).mul(pose);
        Vector4f p3 = new Vector4f(halfWidth, halfHeight, 0.0F, 1.0F).mul(pose);
        return new PreviewQuad(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z,
                p2.x, p2.y, p2.z, p3.x, p3.y, p3.z);
    }

    protected static void drawWithEventModelView(RenderType renderType, MeshData mesh, RenderLevelStageEvent event) {
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        try {
            // RenderType.draw() 使用当前模型视图矩阵；这里切到事件提供的世界矩阵，避免相机相对顶点漂移。
            modelViewStack.set(event.getModelViewMatrix());
            renderType.draw(mesh);
        } finally {
            modelViewStack.popMatrix();
        }
    }

    protected static void logFirstImmediateQuad(PreviewQuad quad, Camera camera, boolean cameraRelative,
            boolean forceWorldAnchored) {
        if (firstImmediateQuadLogged) {
            return;
        }
        firstImmediateQuadLogged = true;
        Vec3 cameraPos = camera.position();
        LOGGER.debug(
                "Iris/YUV immediate quad: cameraRelative={}, forceWorldAnchored={}, pose='{}', anchor=({}, {}, {}), "
                        + "anchorYaw={}, camera=({}, {}, {}), p0=({}, {}, {}), p1=({}, {}, {}), p2=({}, {}, {}), p3=({}, {}, {})",
                cameraRelative, forceWorldAnchored, YUV_IMMEDIATE_POSE,
                fmt(anchorX), fmt(anchorY), fmt(anchorZ), fmt(anchorYawDeg),
                fmt(cameraPos.x), fmt(cameraPos.y), fmt(cameraPos.z),
                fmt(quad.p0x()), fmt(quad.p0y()), fmt(quad.p0z()),
                fmt(quad.p1x()), fmt(quad.p1y()), fmt(quad.p1z()),
                fmt(quad.p2x()), fmt(quad.p2y()), fmt(quad.p2z()),
                fmt(quad.p3x()), fmt(quad.p3y()), fmt(quad.p3z()));
    }

    protected static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    protected static PreviewQuad computePreviewQuad(Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector,
            int textureWidth, int textureHeight, boolean cameraRelative, boolean forceWorldAnchored) {
        float scale = projector != null ? Math.abs(projector.getProjectionScale()) : 1.0F;
        float aspect = textureWidth / (float) textureHeight;
        float halfHeight = HEIGHT * scale * 0.5F;
        float halfWidth = halfHeight * aspect;

        final float p0x;
        final float p0y;
        final float p0z;
        final float p1x;
        final float p1y;
        final float p1z;
        final float p2x;
        final float p2y;
        final float p2z;
        final float p3x;
        final float p3y;
        final float p3z;

        if (forceWorldAnchored || WORLD_ANCHORED || LEGACY_PREVIEW.requiresProjector()) {
            if (!ensureWorldAnchor(minecraft, camera, projector)) {
                return null;
            }
            Vec3 cameraPos = camera.position();
            double dx = anchorX - cameraPos.x;
            double dy = anchorY - cameraPos.y;
            double dz = anchorZ - cameraPos.z;
            if (projector != null
                    ? !isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)
                    : dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
                return null;
            }

            double yawRad = Math.toRadians(anchorYawDeg);
            double pitchRad = Math.toRadians(projector != null ? projector.getProjectionPitch() : 0.0F);
            float rightX = (float) Math.cos(yawRad);
            float rightZ = (float) Math.sin(yawRad);
            float forwardX = (float) -Math.sin(yawRad);
            float forwardZ = (float) Math.cos(yawRad);
            float upX = (float) (forwardX * Math.sin(pitchRad));
            float upY = (float) Math.cos(pitchRad);
            float upZ = (float) (forwardZ * Math.sin(pitchRad));

            float cx = cameraRelative ? (float) (anchorX - cameraPos.x) : (float) anchorX;
            float cy = cameraRelative ? (float) (anchorY - cameraPos.y) : (float) anchorY;
            float cz = cameraRelative ? (float) (anchorZ - cameraPos.z) : (float) anchorZ;
            float rx = rightX * halfWidth;
            float rz = rightZ * halfWidth;
            float ux = upX * halfHeight;
            float uy = upY * halfHeight;
            float uz = upZ * halfHeight;

            p0x = cx - rx + ux;
            p0y = cy + uy;
            p0z = cz - rz + uz;
            p1x = cx - rx - ux;
            p1y = cy - uy;
            p1z = cz - rz - uz;
            p2x = cx + rx - ux;
            p2y = cy - uy;
            p2z = cz + rz - uz;
            p3x = cx + rx + ux;
            p3y = cy + uy;
            p3z = cz + rz + uz;
        } else {
            Vector3fc forward = camera.forwardVector();
            Vector3fc left = camera.leftVector();
            Vector3fc up = camera.upVector();

            float cx = (float) (forward.x() * DISTANCE);
            float cy = (float) (forward.y() * DISTANCE);
            float cz = (float) (forward.z() * DISTANCE);
            float lx = left.x() * halfWidth;
            float ly = left.y() * halfWidth;
            float lz = left.z() * halfWidth;
            float ux = up.x() * halfHeight;
            float uy = up.y() * halfHeight;
            float uz = up.z() * halfHeight;

            p0x = cx + lx + ux;
            p0y = cy + ly + uy;
            p0z = cz + lz + uz;
            p1x = cx + lx - ux;
            p1y = cy + ly - uy;
            p1z = cz + lz - uz;
            p2x = cx - lx - ux;
            p2y = cy - ly - uy;
            p2z = cz - lz - uz;
            p3x = cx - lx + ux;
            p3y = cy - ly + uy;
            p3z = cz - lz + uz;
        }

        return new PreviewQuad(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z);
    }

    protected static boolean ensureWorldAnchor(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
        if (projector != null) {
            BlockPos pos = projector.getBlockPos();
            anchorX = pos.getX() + 0.5D + projector.getProjectionDistanceX();
            anchorY = pos.getY() + projector.getProjectionHeight();
            anchorZ = pos.getZ() + 0.5D + projector.getProjectionDistanceZ();
            anchorYawDeg = projector.getProjectionYaw();
            anchorInitialized = true;
            return true;
        }
        if (LEGACY_PREVIEW.requiresProjector()) {
            // 正式投影仪会话在 TP/区块重载的一两帧里可能暂时拿不到 BE。
            // 这时绝不能退回到“玩家前方测试面”，否则只有本客户端会看到屏幕跟着玩家跑。
            anchorInitialized = false;
            return false;
        }
        if (anchorInitialized) {
            return true;
        }
        Player player = minecraft.player;
        if (player == null) {
            Vec3 pos = camera.position();
            anchorX = pos.x;
            anchorY = pos.y;
            anchorZ = pos.z;
            anchorYawDeg = 0.0F;
            anchorInitialized = true;
            return true;
        }
        double yawRad = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        anchorX = player.getX() + forwardX * WORLD_ANCHOR_DISTANCE;
        anchorY = player.getEyeY();
        anchorZ = player.getZ() + forwardZ * WORLD_ANCHOR_DISTANCE;
        // 屏幕横向轴由 yaw 推导，投影面朝向玩家当前视线方向。
        anchorYawDeg = player.getYRot();
        anchorInitialized = true;
        LOGGER.debug("视频投影测试面已锚定到世界坐标: ({}, {}, {}), yaw={}",
                String.format(java.util.Locale.ROOT, "%.2f", anchorX),
                String.format(java.util.Locale.ROOT, "%.2f", anchorY),
                String.format(java.util.Locale.ROOT, "%.2f", anchorZ),
                String.format(java.util.Locale.ROOT, "%.1f", anchorYawDeg));
        return true;
    }

    protected static void observeCameraContinuity(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            cameraContinuityInitialized = false;
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 pos = camera.position();
        String dimension = minecraft.level.dimension().identifier().toString();
        if (!cameraContinuityInitialized) {
            rememberCameraPosition(pos, dimension);
            return;
        }
        double dx = pos.x - lastCameraX;
        double dy = pos.y - lastCameraY;
        double dz = pos.z - lastCameraZ;
        if (!dimension.equals(lastCameraDimension)
                || dx * dx + dy * dy + dz * dz > CAMERA_TELEPORT_RESET_DISTANCE_SQR) {
            resetLocalRenderAnchors();
        }
        rememberCameraPosition(pos, dimension);
    }

    protected static void rememberCameraPosition(Vec3 pos, String dimension) {
        lastCameraX = pos.x;
        lastCameraY = pos.y;
        lastCameraZ = pos.z;
        lastCameraDimension = dimension;
        cameraContinuityInitialized = true;
    }

    protected static void resetLocalRenderAnchors() {
        anchorInitialized = false;
        firstImmediateQuadLogged = false;
        PROJECTOR_VISIBILITY_CACHE.clear();
    }

    protected static VideoProjectorBlockEntity activeVideoProjector(Minecraft minecraft) {
        BlockPos projectorPos = LEGACY_PREVIEW.primaryProjector();
        if (projectorPos == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector
                ? projector
                : null;
    }

    protected static List<VideoProjectorBlockEntity> activeVideoProjectors(Minecraft minecraft) {
        if (minecraft.level == null) {
            return List.of();
        }
        List<VideoProjectorBlockEntity> projectors = new ArrayList<>();
        for (BlockPos pos : LEGACY_PREVIEW.projectors()) {
            if (minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
                projectors.add(projector);
            }
        }
        LEGACY_PREVIEW.removeProjectorsIf(
                pos -> !(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity)
                        && !berManagedProjectorPositions.contains(pos));
        LEGACY_PREVIEW.setPrimaryProjector(
                projectors.isEmpty() ? null : projectors.get(0).getBlockPos().immutable());
        return projectors;
    }

    protected static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean reverse,
            float opacity) {
        if (reverse) {
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F, opacity);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F, opacity);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F, opacity);
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F, opacity);
        } else {
            vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F, opacity);
            vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F, opacity);
            vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F, opacity);
            vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F, opacity);
        }
    }

    protected static void emitQuad(VertexConsumer buffer, PoseStack.Pose pose,
            float p0x, float p0y, float p0z,
            float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z,
            float p3x, float p3y, float p3z,
            boolean reverse) {
        emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, reverse, 1.0F);
    }

    protected static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float u,
            float v, float opacity) {
        RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v, opacity);
    }

    static boolean isProjectorScreenRenderable(Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, double dotThreshold) {
        if (minecraft == null || camera == null || projector == null) {
            return false;
        }
        BlockPos projectorPos = projector.getBlockPos().immutable();
        long nowNs = System.nanoTime();
        int thresholdKey = (int) Math.round(dotThreshold * 1000.0D);
        VisibilitySample cached = PROJECTOR_VISIBILITY_CACHE.get(projectorPos);
        if (cached != null && cached.thresholdKey() == thresholdKey
                && nowNs - cached.createdNanoTime() <= Math.max(0L, VIEW_OCCLUSION_CACHE_NANOS)) {
            return cached.visible();
        }
        boolean visible = computeProjectorScreenRenderable(minecraft, camera, projector, dotThreshold);
        PROJECTOR_VISIBILITY_CACHE.put(projectorPos, new VisibilitySample(nowNs, thresholdKey, visible));
        return visible;
    }

    protected static boolean computeProjectorScreenRenderable(Minecraft minecraft, Camera camera,
            VideoProjectorBlockEntity projector, double dotThreshold) {
        BlockPos pos = projector.getBlockPos();
        double centerX = pos.getX() + 0.5D + projector.getProjectionDistanceX();
        double centerY = pos.getY() + projector.getProjectionHeight();
        double centerZ = pos.getZ() + 0.5D + projector.getProjectionDistanceZ();
        Vec3 cameraPos = camera.position();
        if (!isProjectorWithinRenderDistance(cameraPos, projector, centerX, centerY, centerZ, 16.0D / 9.0D)) {
            return false;
        }
        for (Vec3 sample : projectorVisibilitySamples(projector, centerX, centerY, centerZ)) {
            if (isScreenInView(camera, sample.x, sample.y, sample.z, dotThreshold)
                    && !isOccluded(minecraft, cameraPos, sample, pos)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean isProjectorWithinRenderDistance(Vec3 cameraPos, VideoProjectorBlockEntity projector,
            double centerX, double centerY, double centerZ, double aspect) {
        var bounds = ProjectorScreenBounds.aroundCenter(centerX, centerY, centerZ,
                projector.getProjectionYaw(), projector.getProjectionPitch(),
                projector.getProjectionScale(), aspect, 0.0D);
        return ProjectorScreenBounds.distanceToSqr(bounds, cameraPos) <= MAX_RENDER_DISTANCE_SQR;
    }

    protected static List<Vec3> projectorVisibilitySamples(VideoProjectorBlockEntity projector,
            double centerX, double centerY, double centerZ) {
        float scale = Math.abs(projector.getProjectionScale());
        double halfHeight = HEIGHT * scale * 0.5D * VIEW_SAMPLE_EDGE_SCALE;
        double halfWidth = halfHeight * 16.0D / 9.0D;
        double yawRad = Math.toRadians(projector.getProjectionYaw());
        double pitchRad = Math.toRadians(projector.getProjectionPitch());
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double upX = forwardX * Math.sin(pitchRad);
        double upY = Math.cos(pitchRad);
        double upZ = forwardZ * Math.sin(pitchRad);
        Vec3 center = new Vec3(centerX, centerY, centerZ);
        Vec3 right = new Vec3(rightX * halfWidth, 0.0D, rightZ * halfWidth);
        Vec3 up = new Vec3(upX * halfHeight, upY * halfHeight, upZ * halfHeight);
        return List.of(center,
                center.add(right).add(up),
                center.add(right).subtract(up),
                center.subtract(right).add(up),
                center.subtract(right).subtract(up));
    }

    protected static boolean isOccluded(Minecraft minecraft, Vec3 cameraPos, Vec3 target, BlockPos projectorPos) {
        if (!VIEW_OCCLUSION_CHECK || minecraft.level == null) {
            return false;
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(cameraPos, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        if (hit.getBlockPos().equals(projectorPos)) {
            return false;
        }
        return hit.getLocation().distanceToSqr(cameraPos) + 1.0e-4D < target.distanceToSqr(cameraPos);
    }

    static double viewDotThreshold() {
        return VIEW_DOT_THRESHOLD;
    }

    protected static boolean isScreenInView(Camera camera, double centerX, double centerY, double centerZ,
            double dotThreshold) {
        Vec3 cameraPos = camera.position();
        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1.0e-4D) {
            return true;
        }
        Vector3fc forward = camera.forwardVector();
        double dot = (dx / len) * forward.x() + (dy / len) * forward.y() + (dz / len) * forward.z();
        return dot > dotThreshold;
    }

    protected record PreviewQuad(float p0x, float p0y, float p0z, float p1x, float p1y, float p1z,
            float p2x, float p2y, float p2z, float p3x, float p3y, float p3z) {
    }

}
