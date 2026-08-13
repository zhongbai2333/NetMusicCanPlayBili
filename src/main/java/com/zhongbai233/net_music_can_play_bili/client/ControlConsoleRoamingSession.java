package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.scene_editor.core.camera.WorldCameraPose;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.projection.PickingRay;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.mixin.ClientInputAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** 中控台无 Screen 漫游会话。代理相机仅存在于客户端，不改变玩家服务端状态。 */
public final class ControlConsoleRoamingSession {
    private static final float DEFAULT_FLYING_SPEED = 0.05F;
    private static final float FLYING_SPEED_STEP = 0.005F;
    private static final float MAX_FLYING_SPEED = 0.2F;
    private static final double SPRINT_MULTIPLIER = 2.0D;
    private static final double HORIZONTAL_DRAG = 0.91D;
    private static final double VERTICAL_DRAG = 0.6D;
    private static final double SCREEN_BASE_Y = 1.55D;
    private static final int HOVER_COLOR = 0xFFFFB347;

    private static Session active;

    private ControlConsoleRoamingSession() {
    }

    public static boolean start(BlockPos consolePos, Vector3dc localPosition, Quaternionfc localOrientation) {
        return start(consolePos, localPosition, localOrientation, List.of(RoamingElement.defaultScreen()));
        }

        public static boolean start(BlockPos consolePos, Vector3dc localPosition, Quaternionfc localOrientation,
            List<RoamingElement> elements) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
            return false;
        }
        ControlConsoleBlockEntity console = minecraft.level.getBlockEntity(consolePos)
            instanceof ControlConsoleBlockEntity found ? found : null;
        if (console == null) {
            return false;
        }
        LocalPlayer player = minecraft.player;
        WorldCameraPose pose;
        try {
            pose = WorldCameraPose.fromLocal(
                    new Vector3d(consolePos.getX() + 0.5D, consolePos.getY(), consolePos.getZ() + 0.5D),
                    localPosition, localOrientation, player.getYRot());
        } catch (IllegalArgumentException invalidPose) {
            return false;
        }
        stop(false);

        Marker camera = new Marker(EntityType.MARKER, minecraft.level);
        Vector3d worldPosition = pose.position();
        camera.setPos(worldPosition.x, worldPosition.y, worldPosition.z);
        camera.setYRot(pose.yawDegrees());
        camera.setXRot(pose.pitchDegrees());
        camera.setOldPosAndRot();
        Entity previousCamera = minecraft.getCameraEntity();
        CameraType previousCameraType = minecraft.options.getCameraType();
        var document = console.document();
        // An empty list can be a deliberately saved empty document. Only the convenience
        // overload above supplies a default screen; never revive one here implicitly.
        List<RoamingElement> snapshot = elements == null ? List.of() : List.copyOf(elements);
        active = new Session(consolePos.immutable(), minecraft.level, player, camera, previousCamera,
            previousCameraType, document.hardRangeX(), document.hardRangeY(), document.hardRangeZ(),
            new ArrayList<>(snapshot), -1, Vec3.ZERO, DEFAULT_FLYING_SPEED, 0.0D);
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setCameraEntity(camera);
        player.sendSystemMessage(Component.literal("灵魂漫游：").append(roamingControls(minecraft))
            .append("，滚轮调速，右键元素进入建模，Esc退出"));
        return true;
    }

    public static boolean isActive() {
        return active != null;
    }

    public static Entity cameraInputTarget() {
        return active != null ? active.camera : null;
    }

    /** 由 MouseHandler mixin 调用，保留原版灵敏度、平滑相机和反转设置。 */
    public static boolean turnCamera(double yawDelta, double pitchDelta) {
        Session session = active;
        if (session == null) {
            return false;
        }
        session.camera.turn(yawDelta, pitchDelta);
        return true;
    }

    public static void tick() {
        Session session = active;
        if (session == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!valid(minecraft, session) || minecraft.screen != null) {
            ControlConsoleClient.releaseLease(session.consolePos);
            stop(false);
            return;
        }
        if (!minecraft.level.hasChunk(Math.floorDiv(session.consolePos.getX(), 16),
            Math.floorDiv(session.consolePos.getZ(), 16))
                || !(minecraft.level.getBlockEntity(session.consolePos) instanceof ControlConsoleBlockEntity)) {
            ControlConsoleClient.releaseLease(session.consolePos);
            stop(true);
            return;
        }
        ControlConsoleClient.tickLease(session.consolePos);
        if (!ControlConsoleClient.hasLease(session.consolePos)) {
            stop(true);
            return;
        }
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        }

        session.camera.setOldPosAndRot();
        Vec3 look = session.camera.getViewVector(1.0F);
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        if (forward.lengthSqr() < 1.0e-8D) {
            forward = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            forward = forward.normalize();
        }
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 horizontalInput = Vec3.ZERO;
        if (minecraft.options.keyUp.isDown()) horizontalInput = horizontalInput.add(forward);
        if (minecraft.options.keyDown.isDown()) horizontalInput = horizontalInput.subtract(forward);
        if (minecraft.options.keyRight.isDown()) horizontalInput = horizontalInput.add(right);
        if (minecraft.options.keyLeft.isDown()) horizontalInput = horizontalInput.subtract(right);
        if (horizontalInput.lengthSqr() > 1.0D) horizontalInput = horizontalInput.normalize();

        double horizontalControl = session.flyingSpeed
                * (minecraft.options.keySprint.isDown() ? SPRINT_MULTIPLIER : 1.0D);
        double verticalControl = 0.0D;
        if (minecraft.options.keyJump.isDown()) verticalControl += session.flyingSpeed * 3.0D;
        if (minecraft.options.keyShift.isDown()) verticalControl -= session.flyingSpeed * 3.0D;
        session.velocity = session.velocity.add(horizontalInput.scale(horizontalControl))
                .add(0.0D, verticalControl, 0.0D);
        Vec3 next = session.camera.position().add(session.velocity);
        session.camera.setPos(next.x, next.y, next.z);
        session.velocity = new Vec3(session.velocity.x * HORIZONTAL_DRAG,
            session.velocity.y * VERTICAL_DRAG, session.velocity.z * HORIZONTAL_DRAG);
        session.hoveredElement = pickElement(session);
    }

    public static void adjustFlyingSpeed(double scrollDelta) {
        Session session = active;
        if (session == null || !Double.isFinite(scrollDelta) || scrollDelta == 0.0D) {
            return;
        }
        session.scrollAccumulator += scrollDelta;
        int scrollSteps = (int) session.scrollAccumulator;
        if (scrollSteps == 0) {
            return;
        }
        session.scrollAccumulator -= scrollSteps;
        session.flyingSpeed = Math.clamp(session.flyingSpeed + scrollSteps * FLYING_SPEED_STEP,
                0.0F, MAX_FLYING_SPEED);
    }

    public static void suppressPlayerInput(ClientInput input) {
        if (active == null) {
            return;
        }
        input.keyPresses = Input.EMPTY;
        ((ClientInputAccessor) input).net_music_can_play_bili$setMoveVector(Vec2.ZERO);
    }

    public static boolean handleMouseButton(int button, int action) {
        if (active == null) {
            return false;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && action == GLFW.GLFW_PRESS) {
            int hit = pickElement(active);
            if (hit >= 0) {
                openModeling(hit);
            } else if (isLookingAtConsole(active)) {
                Session session = active;
                stop(false);
                openModelingAtConsoleCenter(session);
            }
        }
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    }

    public static boolean handleEscape(int key, int action) {
        if (active == null || key != GLFW.GLFW_KEY_ESCAPE || action != GLFW.GLFW_PRESS) {
            return false;
        }
        Session session = active;
        stop(true);
        openModelingAtConsoleCenter(session);
        return true;
    }

    public static boolean handlePlacementKey(int key, int action) {
        Session session = active;
        if (session == null || action != GLFW.GLFW_PRESS) {
            return false;
        }
        String type = switch (key) {
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1 -> "SCREEN";
            case GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_KP_2 -> "SUBTITLE";
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_KP_3 -> "AUDIO";
            default -> null;
        };
        if (type == null) {
            return false;
        }
        if (session.elements.size() >= com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document
                .ControlConsoleDocument.MAX_ELEMENTS) {
            session.player.sendSystemMessage(Component.literal("中控台元素已达到传输安全阈值"));
            return true;
        }
        Vec3 camera = session.camera.position();
        float localX = (float) (camera.x - (session.consolePos.getX() + 0.5D));
        float localY = (float) (camera.y - session.consolePos.getY() - SCREEN_BASE_Y);
        float localZ = (float) (camera.z - (session.consolePos.getZ() + 0.5D));
        int sameTypeCount = 0;
        for (RoamingElement element : session.elements) {
            if (element.type().equals(type)) {
                sameTypeCount++;
            }
        }
        session.elements.add(RoamingElement.placed(type, displayName(type) + " " + (sameTypeCount + 1),
            localZ, localX, localY, -session.camera.getYRot(), session.camera.getXRot()));
        session.hoveredElement = session.elements.size() - 1;
        return true;
    }

    public static void drawHud(GuiGraphicsExtractor graphics) {
        Session session = active;
        Minecraft minecraft = Minecraft.getInstance();
        if (session == null || minecraft.font == null) {
            return;
        }
        int cx = minecraft.getWindow().getGuiScaledWidth() / 2;
        int cy = minecraft.getWindow().getGuiScaledHeight() / 2;
        int color = session.hoveredElement >= 0 ? HOVER_COLOR : 0xE8FFFFFF;
        graphics.fillGradient(cx - 8, cy, cx - 3, cy + 1, color, color);
        graphics.fillGradient(cx + 3, cy, cx + 8, cy + 1, color, color);
        graphics.fillGradient(cx, cy - 8, cx + 1, cy - 3, color, color);
        graphics.fillGradient(cx, cy + 3, cx + 1, cy + 8, color, color);
        Component mode = session.hoveredElement >= 0 ? Component.literal("右键进入元素建模")
            : isLookingAtConsole(session) ? Component.literal("右键以中控台为中心进入建模")
            : Component.literal("灵魂漫游  |  1屏幕  2字幕  3音源  滚轮调速");
        graphics.centeredText(minecraft.font, mode, cx, cy + 18, color);
    }

    public static void submitGeometry(SubmitNodeCollector collector) {
        Session session = active;
        Minecraft minecraft = Minecraft.getInstance();
        if (session == null || minecraft.gameRenderer.getMainCamera() == null) {
            return;
        }
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();
        PoseStack poseStack = new PoseStack();
        collector.submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(), (pose, buffer) -> {
            float ox = (float) (session.consolePos.getX() + 0.5D - cameraPos.x);
            float oy = (float) (session.consolePos.getY() - cameraPos.y);
            float oz = (float) (session.consolePos.getZ() + 0.5D - cameraPos.z);
            box(buffer, pose, ox - (float) session.rangeX, oy - (float) session.rangeY, oz - (float) session.rangeZ,
                    ox + (float) session.rangeX, oy + (float) session.rangeY + 1.0F, oz + (float) session.rangeZ,
                    0x7045E7FF, 1.4F);
            for (int i = 0; i < session.elements.size(); i++) {
                RoamingElement element = session.elements.get(i);
                Vector3d center = element.worldCenter(session.consolePos);
                Quaternionf rotation = element.rotation();
                Vector3f xAxis = rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
                Vector3f yAxis = rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
                Vector3f zAxis = rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
                float halfH = element.height() * 0.5F;
                float halfW = halfH * element.aspect();
                float cx = (float) (center.x - cameraPos.x);
                float cy = (float) (center.y - cameraPos.y);
                float cz = (float) (center.z - cameraPos.z);
                int color = i == session.hoveredElement ? HOVER_COLOR : element.color();
                if (element.isAudio()) {
                    orientedBox(buffer, pose, cx, cy, cz, xAxis, yAxis, zAxis, halfW, halfH,
                            element.audioHalfDepth(), color, 2.0F);
                } else {
                    Matrix4f transform = new Matrix4f().translate(0.0F, (float) SCREEN_BASE_Y, 0.0F)
                            .mul(element.editorTransform().matrix());
                    Vector3f p0 = roamingCorner(transform, session, cameraPos, -halfW, -halfH);
                    Vector3f p1 = roamingCorner(transform, session, cameraPos, halfW, -halfH);
                    Vector3f p2 = roamingCorner(transform, session, cameraPos, halfW, halfH);
                    Vector3f p3 = roamingCorner(transform, session, cameraPos, -halfW, halfH);
                    line(buffer, pose, p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, color, 2.0F);
                    line(buffer, pose, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color, 2.0F);
                    line(buffer, pose, p2.x, p2.y, p2.z, p3.x, p3.y, p3.z, color, 2.0F);
                    line(buffer, pose, p3.x, p3.y, p3.z, p0.x, p0.y, p0.z, color, 2.0F);
                }
            }
        });
    }

    public static void stop(boolean notifyPlayer) {
        Session session = active;
        if (session == null) {
            return;
        }
        active = null;
        Minecraft minecraft = Minecraft.getInstance();
        boolean ownsCamera = minecraft.getCameraEntity() == session.camera;
        if (ownsCamera) {
            Entity restore = session.previousCamera == session.player ? minecraft.player : session.previousCamera;
            if (restore == null || restore.isRemoved() || restore.level() != minecraft.level) {
                restore = minecraft.player;
            }
            if (restore != null) {
                minecraft.setCameraEntity(restore);
            }
            minecraft.options.setCameraType(session.previousCameraType);
        }
        // Input suppression can leave key mappings latched even if another mod replaced
        // the camera entity before this session stopped.
        releaseMovementKeys(minecraft);
        if (notifyPlayer && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("已退出灵魂漫游"));
        }
    }

    private static void openModeling(int elementIndex) {
        Session session = active;
        if (session == null) {
            return;
        }
        BlockPos consolePos = session.consolePos;
        Level level = session.level;
        LocalPlayer player = session.player;
        stop(false);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen != null || minecraft.level != level || minecraft.player != player
                || !player.isAlive() || !level.hasChunk(Math.floorDiv(consolePos.getX(), 16),
                    Math.floorDiv(consolePos.getZ(), 16))
                || !(level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity)
                || player.distanceToSqr(consolePos.getX() + 0.5D, consolePos.getY() + 0.5D,
                    consolePos.getZ() + 0.5D) > 64.0D) {
            return;
            }
                minecraft.setScreen(HolographicScreenConfigTestScreen.forControlConsole(consolePos, elementIndex,
                    sessionElements(session)));
        });
    }

    private static void openModelingAtConsoleCenter(Session session) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos consolePos = session.consolePos;
        Level level = session.level;
        LocalPlayer player = session.player;
        minecraft.execute(() -> {
            if (minecraft.screen != null || minecraft.level != level || minecraft.player != player
                    || !player.isAlive() || !level.hasChunk(Math.floorDiv(consolePos.getX(), 16),
                        Math.floorDiv(consolePos.getZ(), 16))
                    || !(level.getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity)
                    || player.distanceToSqr(consolePos.getX() + 0.5D, consolePos.getY() + 0.5D,
                        consolePos.getZ() + 0.5D) > 64.0D) {
                return;
            }
            minecraft.setScreen(HolographicScreenConfigTestScreen.forControlConsole(consolePos,
                    sessionElements(session)));
        });
    }

    private static List<RoamingElement> sessionElements(Session session) {
        return List.copyOf(session.elements);
    }

    private static boolean isLookingAtConsole(Session session) {
        if (Minecraft.getInstance().level != session.level
            || !(session.level.getBlockEntity(session.consolePos) instanceof ControlConsoleBlockEntity)) {
            return false;
        }
        Vec3 origin = session.camera.getEyePosition();
        Vec3 look = session.camera.getViewVector(1.0F);
        PickingRay ray = new PickingRay(new Vector3d(origin.x, origin.y, origin.z),
            new Vector3d(look.x, look.y, look.z));
        Vector3d min = new Vector3d(session.consolePos.getX(), session.consolePos.getY(), session.consolePos.getZ());
        Vector3d max = new Vector3d(session.consolePos.getX() + 1.0D, session.consolePos.getY() + 1.0D,
            session.consolePos.getZ() + 1.0D);
        var hit = ray.intersectAabb(min, max);
        return hit.isPresent();
    }

    private static String displayName(String type) {
        return switch (type) {
            case "SUBTITLE" -> "字幕";
            case "AUDIO" -> "音源";
            default -> "屏幕";
        };
    }

    private static int pickElement(Session session) {
        Vec3 origin = session.camera.getEyePosition();
        Vec3 direction = session.camera.getViewVector(1.0F);
        PickingRay ray = new PickingRay(new Vector3d(origin.x, origin.y, origin.z),
                new Vector3d(direction.x, direction.y, direction.z));
        int nearestIndex = -1;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < session.elements.size(); i++) {
            RoamingElement element = session.elements.get(i);
            Quaternionf rotation = element.rotation();
            double hitDistance;
            if (element.isAudio()) {
                Quaternionf inverse = new Quaternionf(rotation).conjugate();
                Vector3d localOrigin = new Vector3d(ray.origin()).sub(element.worldCenter(session.consolePos));
                inverse.transform(localOrigin);
                Vector3d localDirection = inverse.transform(ray.direction());
                double halfH = element.height() * 0.5D;
                double halfW = halfH * element.aspect();
                var hit = new PickingRay(localOrigin, localDirection).intersectAabb(
                        new Vector3d(-halfW, -halfH, -element.audioHalfDepth()),
                        new Vector3d(halfW, halfH, element.audioHalfDepth()));
                hitDistance = hit.isPresent() ? hit.orElseThrow() : Double.POSITIVE_INFINITY;
            } else {
                Vector3d consoleOrigin = new Vector3d(session.consolePos.getX() + 0.5D,
                        session.consolePos.getY(), session.consolePos.getZ() + 0.5D);
                PickingRay localRay = new PickingRay(new Vector3d(ray.origin()).sub(consoleOrigin), ray.direction());
                Matrix4f transform = new Matrix4f().translate(0.0F, (float) SCREEN_BASE_Y, 0.0F)
                        .mul(element.editorTransform().matrix());
                var hit = localRay.intersectTransformedRectangle(transform,
                        element.height() * element.aspect() * 0.5D, element.height() * 0.5D);
                hitDistance = hit.isPresent() ? hit.orElseThrow().distance() : Double.POSITIVE_INFINITY;
            }
            if (hitDistance < nearestDistance) {
            nearestIndex = i;
                nearestDistance = hitDistance;
            }
        }
        return nearestIndex;
    }

    private static boolean valid(Minecraft minecraft, Session session) {
        return minecraft.level == session.level && minecraft.player == session.player
                && minecraft.player != null && minecraft.player.isAlive()
                && minecraft.getCameraEntity() == session.camera;
    }

    private static void releaseMovementKeys(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keySprint.setDown(false);
    }

    private static Component roamingControls(Minecraft minecraft) {
        return Component.literal("WASD移动  ")
                .append(minecraft.options.keyJump.getTranslatedKeyMessage()).append("上升  ")
                .append(minecraft.options.keyShift.getTranslatedKeyMessage()).append("下降  ")
                .append(minecraft.options.keySprint.getTranslatedKeyMessage()).append("疾跑");
    }

    private static Vector3f roamingCorner(Matrix4f transform, Session session, Vec3 cameraPos,
            float x, float y) {
        Vector3f local = transform.transformPosition(new Vector3f(x, y, 0.0F));
        return new Vector3f((float) (session.consolePos.getX() + 0.5D + local.x - cameraPos.x),
                (float) (session.consolePos.getY() + local.y - cameraPos.y),
                (float) (session.consolePos.getZ() + 0.5D + local.z - cameraPos.z));
    }

    private static Vector3f corner(float cx, float cy, float cz, Vector3f xAxis, Vector3f yAxis,
            Vector3f zAxis, float x, float y, float z) {
        return new Vector3f(cx, cy, cz).fma(x, xAxis).fma(y, yAxis).fma(z, zAxis);
    }

    private static void orientedBox(VertexConsumer buffer, PoseStack.Pose pose, float cx, float cy, float cz,
            Vector3f xAxis, Vector3f yAxis, Vector3f zAxis, float halfW, float halfH, float halfD, int color,
            float lineWidth) {
        Vector3f[] p = new Vector3f[8];
        int index = 0;
        for (int z = -1; z <= 1; z += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int x = -1; x <= 1; x += 2) {
                    p[index++] = corner(cx, cy, cz, xAxis, yAxis, zAxis,
                            x * halfW, y * halfH, z * halfD);
                }
            }
        }
        int[][] edges = { {0, 1}, {0, 2}, {1, 3}, {2, 3}, {4, 5}, {4, 6}, {5, 7}, {6, 7},
                {0, 4}, {1, 5}, {2, 6}, {3, 7} };
        for (int[] edge : edges) {
            Vector3f a = p[edge[0]];
            Vector3f b = p[edge[1]];
            line(buffer, pose, a.x, a.y, a.z, b.x, b.y, b.z, color, lineWidth);
        }
    }

    private static void box(VertexConsumer buffer, PoseStack.Pose pose, float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ, int color, float lineWidth) {
        line(buffer, pose, minX, minY, minZ, maxX, minY, minZ, color, lineWidth);
        line(buffer, pose, maxX, minY, minZ, maxX, minY, maxZ, color, lineWidth);
        line(buffer, pose, maxX, minY, maxZ, minX, minY, maxZ, color, lineWidth);
        line(buffer, pose, minX, minY, maxZ, minX, minY, minZ, color, lineWidth);
        line(buffer, pose, minX, maxY, minZ, maxX, maxY, minZ, color, lineWidth);
        line(buffer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color, lineWidth);
        line(buffer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color, lineWidth);
        line(buffer, pose, minX, maxY, maxZ, minX, maxY, minZ, color, lineWidth);
        line(buffer, pose, minX, minY, minZ, minX, maxY, minZ, color, lineWidth);
        line(buffer, pose, maxX, minY, minZ, maxX, maxY, minZ, color, lineWidth);
        line(buffer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color, lineWidth);
        line(buffer, pose, minX, minY, maxZ, minX, maxY, maxZ, color, lineWidth);
    }

    private static void line(VertexConsumer buffer, PoseStack.Pose pose, float x1, float y1, float z1,
            float x2, float y2, float z2, int color, float lineWidth) {
        buffer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(0.0F, 1.0F, 0.0F).setLineWidth(lineWidth);
        buffer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(0.0F, 1.0F, 0.0F).setLineWidth(lineWidth);
    }

    private static final class Session {
        private final BlockPos consolePos;
        private final Level level;
        private final LocalPlayer player;
        private final Marker camera;
        private final Entity previousCamera;
        private final CameraType previousCameraType;
        private final double rangeX;
        private final double rangeY;
        private final double rangeZ;
        private final ArrayList<RoamingElement> elements;
        private int hoveredElement;
        private Vec3 velocity;
        private float flyingSpeed;
        private double scrollAccumulator;

        private Session(BlockPos consolePos, Level level, LocalPlayer player, Marker camera,
            Entity previousCamera, CameraType previousCameraType, double rangeX, double rangeY, double rangeZ,
            ArrayList<RoamingElement> elements, int hoveredElement, Vec3 velocity, float flyingSpeed,
            double scrollAccumulator) {
            this.consolePos = consolePos;
            this.level = level;
            this.player = player;
            this.camera = camera;
            this.previousCamera = previousCamera;
            this.previousCameraType = previousCameraType;
            this.rangeX = rangeX;
            this.rangeY = rangeY;
            this.rangeZ = rangeZ;
            this.elements = elements;
            this.hoveredElement = hoveredElement;
            this.velocity = velocity;
            this.flyingSpeed = flyingSpeed;
            this.scrollAccumulator = scrollAccumulator;
        }
    }

        public record RoamingElement(UUID elementId, String type, String name, float distance, float offsetX, float offsetY, float height,
            float aspect, float yaw, float pitch, float roll, String contentMode, String text,
            boolean followLyrics, boolean showTranslation, float textScale, int color, float volume,
            int channelIndex, float maxDistance, boolean autoMixJoc, int translationColor, int backgroundColor,
            ControlConsoleElement.Alignment alignment, float maxWidth, boolean wrap, boolean enabled, boolean locked,
            float scaleX, float scaleY, float scaleZ, float pivotX, float pivotY, float pivotZ,
            float skewXByY, float skewYByX) {
        public static RoamingElement defaultScreen() {
            HolographicGlassesItem.ScreenConfig config = HolographicGlassesItem.defaultScreenConfig();
            return new RoamingElement(UUID.randomUUID(), "SCREEN", "主屏幕", config.distance(), config.offsetX(), config.offsetY(),
                config.height(), config.aspect(), 0.0F, 0.0F, config.roll(), "SOURCE", "", false, true,
                1.0F, 0xFFFFFFFF, 1.0F, 0, 32.0F, false, ControlConsoleElement.DEFAULT_TRANSLATION_COLOR,
                ControlConsoleElement.DEFAULT_BACKGROUND_COLOR, ControlConsoleElement.Alignment.CENTER,
                ControlConsoleElement.DEFAULT_MAX_WIDTH, false, true, false,
                1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }

        public static RoamingElement placed(String type, String name, float distance, float offsetX, float offsetY,
            float yaw, float pitch) {
            HolographicGlassesItem.ScreenConfig config = HolographicGlassesItem.defaultScreenConfig();
            return new RoamingElement(UUID.randomUUID(), type, name, distance, offsetX, offsetY, config.height(), config.aspect(),
                yaw, pitch, config.roll(), "SUBTITLE".equals(type) ? "LYRICS" : "SOURCE", "",
                "SUBTITLE".equals(type), true, 1.0F, 0xFFFFFFFF, 1.0F, 0, 32.0F, false,
                ControlConsoleElement.DEFAULT_TRANSLATION_COLOR, ControlConsoleElement.DEFAULT_BACKGROUND_COLOR,
                ControlConsoleElement.Alignment.CENTER, ControlConsoleElement.DEFAULT_MAX_WIDTH, false, true, false,
                1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }

        private Vector3d worldCenter(BlockPos consolePos) {
            return new Vector3d(consolePos.getX() + 0.5D + offsetX,
                    consolePos.getY() + SCREEN_BASE_Y + offsetY,
                    consolePos.getZ() + 0.5D + distance);
        }

        public int color() {
            return switch (type) {
                case "SUBTITLE" -> 0xFFFFD166;
                case "AUDIO" -> 0xFFB47CFF;
                default -> 0xC045E7FF;
            };
        }

        public boolean isAudio() {
            return "AUDIO".equals(type);
        }

        public float audioHalfDepth() {
            return Math.max(0.18F, Math.min(height * 0.5F, height * aspect * 0.5F) * 0.55F);
        }

        private Quaternionf rotation() {
            return new Quaternionf().rotateYXZ((float) Math.toRadians(yaw),
                    (float) Math.toRadians(pitch), (float) Math.toRadians(roll));
        }

        private EditorTransform editorTransform() {
            return EditorTransform.fromEulerDegrees(new Vector3f(offsetX, offsetY, distance), yaw, pitch, roll,
                    new Vector3f(scaleX, scaleY, scaleZ), new Vector3f(pivotX, pivotY, pivotZ),
                    skewXByY, skewYByX);
        }
    }
}
