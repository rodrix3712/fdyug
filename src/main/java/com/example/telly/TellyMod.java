package com.example.telly;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RegisterKeyBindingsEvent;
import org.lwjgl.glfw.GLFW;

import java.util.*;

@Mod("telly")
public class TellyMod {
    private TellyCore core;

    public TellyMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        // 注册键位事件
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerKeyBindings);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        core = new TellyCore();
    }

    private void registerKeyBindings(RegisterKeyBindingsEvent event) {
        if (core != null) {
            event.register(core.getKeyBinding());
        }
    }

    @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent e) { if (core != null) core.onClientTick(e); }
    @SubscribeEvent public void onRenderGui(RenderGuiEvent.Post e) { if (core != null) core.onRenderGui(e); }
    @SubscribeEvent public void onInputKey(InputEvent.Key e) { if (core != null) core.onInputKey(e); }

    public static class TellyCore {
        private final Minecraft mc = Minecraft.getInstance();
        private KeyMapping toggleKey;
        private boolean running = false, armed = false;
        private long activatePromptAt = 0L, promptBrokeAt = 0L;
        private float promptAlpha = 0f;
        private int promptFadeRgb = 0xFF5555;
        private int[] hitboxLastPos = null, activationAnchorPos = null;
        private int hitboxLastFace = -1, activationAnchorFace = -1;
        private boolean activationMovementHeld = false;
        private int setupTick = 0, cyclePhase = 19;
        private float stagedForward = -1f, stagedStrafe = -1f;
        private boolean stagedJump = false, stagedSprint = false;
        private float baseYaw = 0f;
        private int travelX = 0, travelZ = 0;
        private double antiSwayLane = 0.0;
        private float antiSwayYawOffset = 0f;
        private int bridgeLaneBlock = 0, bridgeStartProgress = 0;
        private int[] latestStraightPlacedPos = null;
        private boolean firstTellyPlacementPending = false, adaptiveAimValid = false;
        private float adaptiveAimYaw = 0f, adaptiveAimPitch = 0f;
        private long adaptiveAimUpdatedAt = 0L;
        private boolean rotationActive = false;
        private long rotationStartedAt = 0L, rotationDuration = 50L;
        private float rotationStartYaw = 0f, rotationStartPitch = 0f, rotationTargetYaw = 0f, rotationTargetPitch = 0f;
        private float scriptedRotationYaw = 0f, scriptedRotationPitch = 0f;
        private int rotationStepCounter = 0;
        private final int[] YAW_NUDGE_PATTERN = {0,1,-1,2,-2};
        private final double ACTIVATION_ACROSS_MIN = 0.38, ACTIVATION_ACROSS_MAX = 0.65;
        private final double ACTIVATION_HEIGHT_MIN = 0.25, ACTIVATION_HEIGHT_MAX = 0.75;
        private final float ACTIVATION_YAW_TOLERANCE = 2f;
        private float[] yawCurve = {91.68f,98.88f,78.94f,37.45f,1.61f,-21.69f,-33.98f,-35.80f,-34.64f,-33.85f,-33.06f,-31.55f,-29.26f,-26.65f,-24.19f,-21.07f,-18.84f,-17.06f,-8.87f,2.61f,41.94f};
        private float[] pitchCurve = {64.31f,59.95f,60.57f,61.46f,60.64f,58.89f,56.91f,56.63f,58.65f,61.63f,64.20f,66.74f,68.69f,70.64f,73.01f,75.37f,77.46f,78.56f,78.90f,77.22f,72.25f};
        private float[] forwardCurve = {1f,1f,0f,0f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,-1f,1f};
        private float[] strafeCurve = {-1f,-1f,-1f,-1f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,-1f,-1f,-1f,-1f};

        private boolean tellyAutoPlaceWindow = false;
        private final Map<String, Boolean> settings = new HashMap<>();
        private boolean antiSwayTapUsed = false;
        private long freezeLastTickAt = 0L;

        public TellyCore() {
            settings.put("autoSwap", true);
            settings.put("disableSafeWalk", true);
            settings.put("showActivationHitbox", false);
            settings.put("print", false);
            toggleKey = new KeyMapping("key.telly.toggle", GLFW.GLFW_KEY_T, "key.categories.telly");
        }

        public KeyMapping getKeyBinding() {
            return toggleKey;
        }

        public void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase == TickEvent.Phase.START) handleUpdate();
            if (running) applyTellyMovementInput();
        }

        public void onRenderGui(RenderGuiEvent.Post e) {
            drawActivatePrompt(e.getGuiGraphics());
            if (settings.get("showActivationHitbox")) drawActivationFaceRegion(e.getGuiGraphics());
        }

        public void onInputKey(InputEvent.Key e) {
            if (e.getAction() == GLFW.GLFW_PRESS && toggleKey.isDown()) {
                if (!running && !armed) armAutomation();
                else stopAutomation(true);
            }
        }

        // ---------- 核心更新 ----------
        private void handleUpdate() {
            if (!running && armed) updateActivationPrompt();
            if (!running) return;
            LocalPlayer p = mc.player;
            if (p == null || p.isDeadOrDying() || p.fallDistance > 7f) { stopAutomation(true); return; }
            handleAutoSwap(p);
            if (!isHoldingBlock(p)) { stopAutomation(true); return; }
            if (firstTellyPlacementPending) updateAdaptivePlacementAim(p);
            advanceTellyCycle();
            if (tellyAutoPlaceWindow) attemptPlacement(p);
        }

        // ---------- 放置 ----------
        private void attemptPlacement(LocalPlayer p) {
            BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
            if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
            BlockPos support = hit.getBlockPos();
            Direction face = hit.getDirection();
            BlockPos target = support.relative(face);
            if (!isReplaceable(target)) return;
            int[] t = {target.getX(), target.getY(), target.getZ()};
            if (!isStraightTellyTarget(t)) return;
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        }

        private boolean isStraightTellyTarget(int[] pos) {
            if (!running || pos == null) return true;
            int lane = travelX != 0 ? pos[2] : pos[0];
            if (lane != bridgeLaneBlock) return false;
            int prog = pos[0]*travelX + pos[2]*travelZ;
            return prog >= bridgeStartProgress;
        }

        private boolean isReplaceable(BlockPos pos) {
            BlockState state = mc.level.getBlockState(pos);
            return state.isAir() || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA ||
                   state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.GRASS || state.getBlock() == Blocks.TALL_GRASS;
        }

        private boolean isHoldingBlock(LocalPlayer p) {
            return p.getMainHandItem().getItem() instanceof BlockItem;
        }

        private void handleAutoSwap(LocalPlayer p) {
            if (!settings.get("autoSwap")) return;
            ItemStack held = p.getMainHandItem();
            int heldCount = held.getCount();
            if (heldCount > 5) return;
            int bestSlot = -1, bestSize = heldCount;
            for (int i=0; i<9; i++) {
                if (i == p.getInventory().selected) continue;
                ItemStack stack = p.getInventory().getItem(i);
                if (!(stack.getItem() instanceof BlockItem)) continue;
                if (stack.getCount() > bestSize) { bestSize = stack.getCount(); bestSlot = i; }
            }
            if (bestSlot != -1) p.getInventory().selected = bestSlot;
        }

        // ---------- 激活提示 ----------
        private void updateActivationPrompt() {
            LocalPlayer p = mc.player;
            if (p == null || mc.screen != null) { clearActivationPrompt(); return; }
            boolean lookingDown = p.getXRot() >= 75f;
            boolean atEdge = lookingDown && isLookingAtEdge(p);
            if (mc.options.keyShift.isDown() && atEdge) {
                if (activatePromptAt == 0L) activatePromptAt = System.currentTimeMillis();
                promptBrokeAt = 0L;
                captureActivationAnchor(p);
                if (activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 850L) setKeyPressed("use", false);
                if (activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 1000L) {
                    if (mc.mouseHandler.isLeftPressed()) {
                        // 安全行走禁用逻辑
                    }
                }
                return;
            }
            if (activatePromptAt == 0L) return;
            if (System.currentTimeMillis()-activatePromptAt < 1000L) { clearActivationPrompt(); return; }
            if (promptBrokeAt == 0L) { promptFadeRgb = 0x55FF55; promptBrokeAt = System.currentTimeMillis(); }
            setKeyPressed("use", false);
            if (!mc.options.keyShift.isDown() && mc.mouseHandler.isLeftPressed() && isActivationYawAligned(p.getYRot())) {
                activatePromptAt = 0L; promptBrokeAt = 0L;
                beginAutomation();
                if (!running) setKeyPressed("use", false);
                return;
            }
            if (System.currentTimeMillis() - promptBrokeAt > 300L) clearActivationPrompt();
        }

        private void clearActivationPrompt() {
            if (activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 850L) setKeyPressed("use", false);
            activatePromptAt = 0L; promptBrokeAt = 0L;
            activationAnchorPos = null; activationAnchorFace = -1;
            setActivationMovementHold(false);
        }

        private boolean isLookingAtEdge(LocalPlayer p) {
            if (!isActivationYawAligned(p.getYRot())) return false;
            BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
            if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
            Direction face = hit.getDirection();
            if (face.getAxis() == Direction.Axis.Y) return false;
            Vec3 local = hit.getLocation().subtract(hit.getBlockPos().getCenter());
            double across = face.getAxis() == Direction.Axis.X ? local.z : local.x;
            if (face == Direction.NORTH || face == Direction.WEST) across = 1 - across;
            if (across < ACTIVATION_ACROSS_MIN || across > ACTIVATION_ACROSS_MAX) return false;
            if (local.y < ACTIVATION_HEIGHT_MIN || local.y > ACTIVATION_HEIGHT_MAX) return false;
            int[] travel = travelDirectionFromYaw(p.getYRot());
            int travelFace = travel[0] > 0 ? Direction.EAST.ordinal() : travel[0] < 0 ? Direction.WEST.ordinal() : travel[1] > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
            if (face.ordinal() != travelFace) return false;
            int[] posArr = {hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()};
            if (!isPlayerOnActivationBlock(p, posArr)) return false;
            int aheadX = posArr[0] + travel[0];
            int aheadZ = posArr[2] + travel[1];
            if (!isReplaceable(new BlockPos(aheadX, posArr[1]+1, aheadZ))) return false;
            hitboxLastPos = posArr; hitboxLastFace = face.ordinal();
            return true;
        }

        private void captureActivationAnchor(LocalPlayer p) {
            if (p == null) return;
            BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
            if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                if (hitboxLastPos != null && hitboxLastFace >= 2) {
                    activationAnchorPos = hitboxLastPos.clone();
                    activationAnchorFace = hitboxLastFace;
                }
                return;
            }
            Direction face = hit.getDirection();
            if (face.getAxis() == Direction.Axis.Y) return;
            int[] posArr = {hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()};
            if (!isPlayerOnActivationBlock(p, posArr)) return;
            Vec3 local = hit.getLocation().subtract(hit.getBlockPos().getCenter());
            double across = face.getAxis() == Direction.Axis.X ? local.z : local.x;
            if (face == Direction.NORTH || face == Direction.WEST) across = 1 - across;
            if (across < ACTIVATION_ACROSS_MIN || across > ACTIVATION_ACROSS_MAX) return;
            int[] travel = travelDirectionFromYaw(p.getYRot());
            int travelFace = travel[0] > 0 ? Direction.EAST.ordinal() : travel[0] < 0 ? Direction.WEST.ordinal() : travel[1] > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
            if (face.ordinal() != travelFace) return;
            activationAnchorPos = posArr.clone();
            activationAnchorFace = face.ordinal();
            hitboxLastPos = posArr.clone();
            hitboxLastFace = face.ordinal();
        }

        private boolean isActivationYawAligned(float yaw) {
            float nearest = Math.round((yaw - 45f) / 90f) * 90f + 45f;
            return Math.abs(wrapAngle(yaw - nearest)) <= ACTIVATION_YAW_TOLERANCE;
        }

        private boolean isPlayerOnActivationBlock(LocalPlayer p, int[] pos) {
            if (pos == null) return false;
            if (pos[1] != (int)Math.floor(p.getY() - 0.01)) return false;
            double cx = pos[0] + 0.5, cz = pos[2] + 0.5;
            return Math.abs(p.getX() - cx) <= 0.85 && Math.abs(p.getZ() - cz) <= 0.85;
        }

        private int[] travelDirectionFromYaw(float yaw) {
            double rad = Math.toRadians(yaw);
            double rawX = Math.sin(rad) - Math.cos(rad);
            double rawZ = -Math.cos(rad) - Math.sin(rad);
            if (Math.abs(rawX) >= Math.abs(rawZ)) return new int[]{rawX >= 0 ? 1 : -1, 0};
            return new int[]{0, rawZ >= 0 ? 1 : -1};
        }

        private void setActivationMovementHold(boolean hold) {
            if (hold) { activationMovementHeld = true; setKeyPressed("back", true); setKeyPressed("right", true); }
            else if (activationMovementHeld) { activationMovementHeld = false; setKeyPressed("back", false); setKeyPressed("right", false); }
        }

        // ---------- 自动开始/停止 ----------
        private void armAutomation() {
            armed = true; running = false;
            activatePromptAt = 0L; promptBrokeAt = 0L; setupTick = 0; cyclePhase = 19;
            rotationActive = false; activationMovementHeld = false;
            printStatus("Armed. Sneak looking down, wait for green, hold rmb and release sneak");
        }

        private void stopAutomation(boolean turnOff) {
            armed = false; running = false; setupTick = 0; cyclePhase = 19;
            rotationActive = false; activationMovementHeld = false;
            tellyAutoPlaceWindow = false;
            antiSwayYawOffset = 0f; antiSwayTapUsed = false;
            firstTellyPlacementPending = false; latestStraightPlacedPos = null;
            activationAnchorPos = null; activationAnchorFace = -1;
            stagedForward = 0f; stagedStrafe = 0f; stagedJump = false; stagedSprint = false;
            adaptiveAimValid = false; adaptiveAimUpdatedAt = 0L;
            scriptedRotationYaw = 0f; scriptedRotationPitch = 0f;
            if (mc.player != null) { mc.player.input.forwardImpulse = 0; mc.player.input.leftImpulse = 0; mc.player.input.jumping = false; }
            mc.options.keySprint.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keyUse.setDown(false);
            freezeLastTickAt = 0L;
            armed = true;
            activatePromptAt = 0L; promptBrokeAt = 0L;
            if (turnOff) printStatus("Stopped. Sneak looking down to arm again");
        }

        private void beginAutomation() {
            LocalPlayer p = mc.player;
            if (p == null || !isHoldingBlock(p)) { printStatus("Hold blocks before starting"); return; }
            if (!isActivationYawAligned(p.getYRot())) return;
            baseYaw = Math.round((p.getYRot() - 45f) / 90f) * 90f + 45f;
            calculateTravelDirection(baseYaw);
            antiSwayLane = travelX != 0 ? p.getZ() : p.getX();
            antiSwayYawOffset = 0f; antiSwayTapUsed = false;
            if (activationAnchorPos == null) captureActivationAnchor(p);
            initializeStraightBridgeLane(p);
            firstTellyPlacementPending = false;
            adaptiveAimValid = false; adaptiveAimUpdatedAt = 0L;
            setupTick = 0; cyclePhase = 19;
            stagedForward = -1f; stagedStrafe = -1f; stagedJump = false; stagedSprint = false;
            armed = false; running = true;
            freezeLastTickAt = System.currentTimeMillis();
            activationMovementHeld = false;
            tellyAutoPlaceWindow = true;
            scriptedRotationYaw = baseYaw; scriptedRotationPitch = 74.52f;
            rotationStartYaw = baseYaw; rotationStartPitch = 74.52f;
            rotationTargetYaw = baseYaw; rotationTargetPitch = 74.52f;
            rotationActive = false;
            p.setYRot(baseYaw); p.setXRot(74.52f);
            setKeyPressed("attack", false);
            applyMovement(-1f, -1f, false, false);
            setRotationTarget(baseYaw, 74.52f, 50L);
            applySmoothedRotation();
            applyUse(true);
            printStatus("Started");
        }

        // ---------- 周期推进 ----------
        private void advanceTellyCycle() {
            if (!running) return;
            suppressSneakInput();
            if (setupTick >= 0) {
                if (setupTick < 12) {
                    boolean setupJump = setupTick >= 6;
                    stagedForward = -1f; stagedStrafe = -1f; stagedJump = setupJump; stagedSprint = false;
                    applyUse(true);
                    if (setupTick == 11) setRotationTarget(baseYaw + yawCurve[19], pitchCurve[19], 50L);
                    else setRotationTarget(baseYaw, 74.52f, 50L);
                    setupTick++;
                    return;
                }
                setupTick = -1;
                cyclePhase = 19;
                firstTellyPlacementPending = true;
                adaptiveAimValid = false;
                clearCachedCandidate();
                updateAdaptivePlacementAim(mc.player);
            }
            int phase = cyclePhase;
            stagedForward = forwardCurve[phase];
            stagedStrafe = strafeCurve[phase];
            stagedJump = phase >= 1 && phase <= 19;
            stagedSprint = phase == 0 || phase == 1;
            applyUse(phase >= 7);
            int next = (phase + 1) % yawCurve.length;
            setRotationTarget(baseYaw + yawCurve[next], pitchCurve[next], 50L);
            cyclePhase = next;
        }

        // ---------- 输入控制 ----------
        private void applyTellyMovementInput() {
            if (!running) return;
            suppressSneakInput();
            holdScriptedRotation();
            applyMovement(stagedForward, stagedStrafe, stagedJump, stagedSprint);
        }

        private void applyUse(boolean pressed) {
            tellyAutoPlaceWindow = pressed;
            setKeyPressed("use", pressed);
        }

        private void applyMovement(float forward, float strafe, boolean jump, boolean sprint) {
            if (mc.player == null) return;
            mc.player.input.forwardImpulse = forward;
            mc.player.input.leftImpulse = strafe;
            mc.player.input.jumping = jump;
            mc.options.keySprint.setDown(sprint);
            mc.player.setSprinting(sprint);
            mc.options.keyShift.setDown(false);
            mc.player.input.shiftKeyDown = false;
        }

        private void setKeyPressed(String key, boolean pressed) {
            if (key.equals("attack")) mc.options.keyAttack.setDown(pressed);
            else if (key.equals("use")) mc.options.keyUse.setDown(pressed);
            else if (key.equals("sneak")) mc.options.keyShift.setDown(pressed);
            else if (key.equals("sprint")) mc.options.keySprint.setDown(pressed);
            else if (key.equals("forward")) mc.options.keyUp.setDown(pressed);
            else if (key.equals("back")) mc.options.keyDown.setDown(pressed);
            else if (key.equals("left")) mc.options.keyLeft.setDown(pressed);
            else if (key.equals("right")) mc.options.keyRight.setDown(pressed);
            else if (key.equals("jump")) mc.options.keyJump.setDown(pressed);
        }

        private void suppressSneakInput() { mc.options.keyShift.setDown(false); if (mc.player != null) mc.player.input.shiftKeyDown = false; }

        // ---------- 旋转 ----------
        private void holdScriptedRotation() {
            if (mc.player != null) { mc.player.setYRot(scriptedRotationYaw); mc.player.setXRot(scriptedRotationPitch); }
        }

        private void applySmoothedRotation() {
            if (!rotationActive) { if (running) holdScriptedRotation(); return; }
            LocalPlayer p = mc.player; if (p == null) return;
            double prog = (double)(System.currentTimeMillis() - rotationStartedAt) / rotationDuration;
            if (prog < 0) prog = 0; if (prog > 1) prog = 1;
            float desiredYaw = rotationStartYaw + (rotationTargetYaw - rotationStartYaw) * (float)prog;
            float desiredPitch = rotationStartPitch + (rotationTargetPitch - rotationStartPitch) * (float)prog;
            float quantYaw = quantizeFrom(rotationStartYaw, desiredYaw);
            float quantPitch = quantizeFrom(rotationStartPitch, desiredPitch);
            scriptedRotationYaw = quantYaw;
            scriptedRotationPitch = clamp(quantPitch, -90f, 90f);
            holdScriptedRotation();
            if (prog >= 1) rotationActive = false;
        }

        private float quantizeFrom(float origin, float value) {
            float gcd = rotationGcd();
            float delta = value - origin;
            delta -= delta % gcd;
            return origin + delta;
        }

        private float rotationGcd() {
            float sens = mc.options.sensitivity().get().floatValue(); // 修复：使用 floatValue()
            float f = sens * 0.6f + 0.2f;
            return f * f * f * 8f * 0.15f;
        }

        private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
            LocalPlayer p = mc.player; if (p == null) return;
            applySmoothedRotation();
            if (running) { rotationStartYaw = scriptedRotationYaw; rotationStartPitch = scriptedRotationPitch; }
            else { rotationStartYaw = p.getYRot(); rotationStartPitch = p.getXRot(); }
            float corrected = targetYaw;
            boolean adaptive = running && tellyAutoPlaceWindow && firstTellyPlacementPending && adaptiveAimValid && System.currentTimeMillis()-adaptiveAimUpdatedAt <= 125L;
            if (adaptive) { corrected = adaptiveAimYaw; targetPitch = adaptiveAimPitch; }
            else if (running) corrected += antiSwayYawOffset;
            rotationStepCounter++;
            corrected += rotationGcd() * YAW_NUDGE_PATTERN[rotationStepCounter % 5];
            rotationTargetYaw = rotationStartYaw + wrapAngle(corrected - rotationStartYaw);
            rotationTargetPitch = clamp(targetPitch, -90f, 90f);
            rotationStartedAt = System.currentTimeMillis();
            rotationDuration = Math.max(1L, duration);
            rotationActive = true;
        }

        private float clamp(float v, float min, float max) { return v < min ? min : (v > max ? max : v); }
        private float wrapAngle(float a) { while (a <= -180f) a += 360f; while (a > 180f) a -= 360f; return a; }

        // ---------- 自适应瞄准 ----------
        private void updateAdaptivePlacementAim(LocalPlayer p) {
            if (!firstTellyPlacementPending) return;
            if (latestStraightPlacedPos != null) {
                int face = travelX > 0 ? Direction.EAST.ordinal() : travelX < 0 ? Direction.WEST.ordinal() : travelZ > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
                int[] target = offsetPos(latestStraightPlacedPos, face);
                if (isStraightTellyTarget(target) && isReplaceable(new BlockPos(target[0], target[1], target[2]))) {
                    Vec3 hitVec = getSupportFaceHitVec(latestStraightPlacedPos, face, 0.5, 0.5);
                    setAdaptiveAimToPoint(p, hitVec);
                }
            }
        }

        private void setAdaptiveAimToPoint(LocalPlayer p, Vec3 point) {
            if (p == null || point == null) return;
            Vec3 eyes = p.getEyePosition(1f);
            double dx = point.x - eyes.x, dy = point.y - eyes.y, dz = point.z - eyes.z;
            double horiz = Math.sqrt(dx*dx + dz*dz);
            if (horiz < 1e-5) return;
            adaptiveAimYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90f);
            adaptiveAimPitch = clamp((float)(-Math.toDegrees(Math.atan2(dy, horiz))), -89f, 89f);
            adaptiveAimUpdatedAt = System.currentTimeMillis();
            adaptiveAimValid = true;
        }

        private Vec3 getSupportFaceHitVec(int[] supportPos, int face, double primary, double secondary) {
            double p = Math.max(0.001, Math.min(0.999, primary));
            double s = Math.max(0.001, Math.min(0.999, secondary));
            Direction dir = Direction.values()[face];
            Vec3 base = new Vec3(supportPos[0], supportPos[1], supportPos[2]);
            if (dir == Direction.NORTH) return base.add(new Vec3(p, s, 0));
            if (dir == Direction.SOUTH) return base.add(new Vec3(p, s, 1));
            if (dir == Direction.EAST) return base.add(new Vec3(1, p, s));
            if (dir == Direction.WEST) return base.add(new Vec3(0, p, s));
            if (dir == Direction.DOWN) return base.add(new Vec3(p, 0, s));
            return base.add(new Vec3(p, 1, s));
        }

        private int[] offsetPos(int[] pos, int face) {
            Direction d = Direction.values()[face];
            return new int[]{pos[0]+d.getStepX(), pos[1]+d.getStepY(), pos[2]+d.getStepZ()};
        }

        private void clearCachedCandidate() {}

        private void initializeStraightBridgeLane(LocalPlayer p) {
            int[] anchor = activationAnchorPos != null ? activationAnchorPos : (hitboxLastPos != null ? hitboxLastPos : null);
            if (anchor == null) {
                BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
                if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    BlockPos pos = hit.getBlockPos();
                    int[] pArr = {pos.getX(), pos.getY(), pos.getZ()};
                    if (isPlayerOnActivationBlock(p, pArr)) { anchor = pArr; activationAnchorPos = pArr.clone(); activationAnchorFace = hit.getDirection().ordinal(); }
                }
            }
            if (anchor != null) { bridgeLaneBlock = travelX != 0 ? anchor[2] : anchor[0]; bridgeStartProgress = anchor[0]*travelX + anchor[2]*travelZ; }
            else { bridgeLaneBlock = travelX != 0 ? (int)Math.floor(p.getZ()) : (int)Math.floor(p.getX()); bridgeStartProgress = (int)Math.floor(p.getX())*travelX + (int)Math.floor(p.getZ())*travelZ; }
            latestStraightPlacedPos = anchor != null ? anchor.clone() : new int[]{(int)Math.floor(p.getX()), (int)Math.floor(p.getY())-1, (int)Math.floor(p.getZ())};
        }

        private void calculateTravelDirection(float yaw) {
            double rad = Math.toRadians(yaw);
            double rawX = Math.sin(rad) - Math.cos(rad);
            double rawZ = -Math.cos(rad) - Math.sin(rad);
            if (Math.abs(rawX) >= Math.abs(rawZ)) { travelX = rawX >= 0 ? 1 : -1; travelZ = 0; }
            else { travelX = 0; travelZ = rawZ >= 0 ? 1 : -1; }
        }

        // ---------- 渲染 ----------
        private void drawActivatePrompt(net.minecraft.client.gui.GuiGraphics gui) {
            if (promptAlpha < 0.05f) return;
            String text = "Activate?";
            int alpha = (int)(promptAlpha * 255);
            if (alpha < 16) alpha = 16;
            int color = (alpha << 24) | promptFadeRgb;
            float x = mc.getWindow().getGuiScaledWidth() / 2f - mc.font.width(text) / 2f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f + 10f;
            gui.drawString(mc.font, text, (int)x, (int)y, color);
        }

        private void drawActivationFaceRegion(net.minecraft.client.gui.GuiGraphics gui) {
            // 简化的激活框渲染（可省略，保持编译通过）
        }

        // ---------- 工具 ----------
        private void printStatus(String msg) {
            if (settings.get("print") && mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§bTelly §7| " + msg));
            }
        }
    }
}
        // ---------- 自动开始/停止 ----------
        private void armAutomation() {
            armed = true; running = false;
            activatePromptAt = 0L; promptBrokeAt = 0L; setupTick = 0; cyclePhase = 19;
            rotationActive = false; activationMovementHeld = false;
            printStatus("Armed. Sneak looking down, wait for green, hold rmb and release sneak");
        }

        private void stopAutomation(boolean turnOff) {
            armed = false; running = false; setupTick = 0; cyclePhase = 19;
            rotationActive = false; activationMovementHeld = false;
            tellyAutoPlaceWindow = false;
            antiSwayYawOffset = 0f; antiSwayTapUsed = false;
            firstTellyPlacementPending = false; latestStraightPlacedPos = null;
            activationAnchorPos = null; activationAnchorFace = -1;
            stagedForward = 0f; stagedStrafe = 0f; stagedJump = false; stagedSprint = false;
            adaptiveAimValid = false; adaptiveAimUpdatedAt = 0L;
            scriptedRotationYaw = 0f; scriptedRotationPitch = 0f;
            if (mc.player != null) { mc.player.input.forwardImpulse = 0; mc.player.input.leftImpulse = 0; mc.player.input.jumping = false; }
            mc.options.keySprint.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keyUse.setDown(false);
            freezeLastTickAt = 0L;
            armed = true;
            activatePromptAt = 0L; promptBrokeAt = 0L;
            if (turnOff) printStatus("Stopped. Sneak looking down to arm again");
        }

        private void beginAutomation() {
            LocalPlayer p = mc.player;
            if (p == null || !isHoldingBlock(p)) { printStatus("Hold blocks before starting"); return; }
            if (!isActivationYawAligned(p.getYRot())) return;
            baseYaw = Math.round((p.getYRot() - 45f) / 90f) * 90f + 45f;
            calculateTravelDirection(baseYaw);
            antiSwayLane = travelX != 0 ? p.getZ() : p.getX();
            antiSwayYawOffset = 0f; antiSwayTapUsed = false;
            if (activationAnchorPos == null) captureActivationAnchor(p);
            initializeStraightBridgeLane(p);
            firstTellyPlacementPending = false;
            adaptiveAimValid = false; adaptiveAimUpdatedAt = 0L;
            setupTick = 0; cyclePhase = 19;
            stagedForward = -1f; stagedStrafe = -1f; stagedJump = false; stagedSprint = false;
            armed = false; running = true;
            freezeLastTickAt = System.currentTimeMillis();
            activationMovementHeld = false;
            tellyAutoPlaceWindow = true;
            scriptedRotationYaw = baseYaw; scriptedRotationPitch = 74.52f;
            rotationStartYaw = baseYaw; rotationStartPitch = 74.52f;
            rotationTargetYaw = baseYaw; rotationTargetPitch = 74.52f;
            rotationActive = false;
            p.setYRot(baseYaw); p.setXRot(74.52f);
            setKeyPressed("attack", false);
            applyMovement(-1f, -1f, false, false);
            setRotationTarget(baseYaw, 74.52f, 50L);
            applySmoothedRotation();
            applyUse(true);
            printStatus("Started");
        }

        // ---------- 周期推进 ----------
        private void advanceTellyCycle() {
            if (!running) return;
            suppressSneakInput();
            if (setupTick >= 0) {
                if (setupTick < 12) {
                    boolean setupJump = setupTick >= 6;
                    stagedForward = -1f; stagedStrafe = -1f; stagedJump = setupJump; stagedSprint = false;
                    applyUse(true);
                    if (setupTick == 11) setRotationTarget(baseYaw + yawCurve[19], pitchCurve[19], 50L);
                    else setRotationTarget(baseYaw, 74.52f, 50L);
                    setupTick++;
                    return;
                }
                setupTick = -1;
                cyclePhase = 19;
                firstTellyPlacementPending = true;
                adaptiveAimValid = false;
                clearCachedCandidate();
                updateAdaptivePlacementAim(mc.player);
            }
            int phase = cyclePhase;
            stagedForward = forwardCurve[phase];
            stagedStrafe = strafeCurve[phase];
            stagedJump = phase >= 1 && phase <= 19;
            stagedSprint = phase == 0 || phase == 1;
            applyUse(phase >= 7);
            int next = (phase + 1) % yawCurve.length;
            setRotationTarget(baseYaw + yawCurve[next], pitchCurve[next], 50L);
            cyclePhase = next;
        }

        // ---------- 输入控制 ----------
        private void applyTellyMovementInput() {
            if (!running) return;
            suppressSneakInput();
            holdScriptedRotation();
            applyMovement(stagedForward, stagedStrafe, stagedJump, stagedSprint);
        }

        private void applyUse(boolean pressed) {
            tellyAutoPlaceWindow = pressed;
            setKeyPressed("use", pressed);
        }

        private void applyMovement(float forward, float strafe, boolean jump, boolean sprint) {
            if (mc.player == null) return;
            mc.player.input.forwardImpulse = forward;
            mc.player.input.leftImpulse = strafe;
            mc.player.input.jumping = jump;
            mc.options.keySprint.setDown(sprint);
            mc.player.setSprinting(sprint);
            mc.options.keyShift.setDown(false);
            mc.player.input.shiftKeyDown = false;
        }

        private void setKeyPressed(String key, boolean pressed) {
            if (key.equals("attack")) mc.options.keyAttack.setDown(pressed);
            else if (key.equals("use")) mc.options.keyUse.setDown(pressed);
            else if (key.equals("sneak")) mc.options.keyShift.setDown(pressed);
            else if (key.equals("sprint")) mc.options.keySprint.setDown(pressed);
            else if (key.equals("forward")) mc.options.keyUp.setDown(pressed);
            else if (key.equals("back")) mc.options.keyDown.setDown(pressed);
            else if (key.equals("left")) mc.options.keyLeft.setDown(pressed);
            else if (key.equals("right")) mc.options.keyRight.setDown(pressed);
            else if (key.equals("jump")) mc.options.keyJump.setDown(pressed);
        }

        private void suppressSneakInput() { mc.options.keyShift.setDown(false); if (mc.player != null) mc.player.input.shiftKeyDown = false; }

        // ---------- 旋转 ----------
        private void holdScriptedRotation() {
            if (mc.player != null) { mc.player.setYRot(scriptedRotationYaw); mc.player.setXRot(scriptedRotationPitch); }
        }

        private void applySmoothedRotation() {
            if (!rotationActive) { if (running) holdScriptedRotation(); return; }
            LocalPlayer p = mc.player; if (p == null) return;
            double prog = (double)(System.currentTimeMillis() - rotationStartedAt) / rotationDuration;
            if (prog < 0) prog = 0; if (prog > 1) prog = 1;
            float desiredYaw = rotationStartYaw + (rotationTargetYaw - rotationStartYaw) * (float)prog;
            float desiredPitch = rotationStartPitch + (rotationTargetPitch - rotationStartPitch) * (float)prog;
            float quantYaw = quantizeFrom(rotationStartYaw, desiredYaw);
            float quantPitch = quantizeFrom(rotationStartPitch, desiredPitch);
            scriptedRotationYaw = quantYaw;
            scriptedRotationPitch = clamp(quantPitch, -90f, 90f);
            holdScriptedRotation();
            if (prog >= 1) rotationActive = false;
        }

        private float quantizeFrom(float origin, float value) {
            float gcd = rotationGcd();
            float delta = value - origin;
            delta -= delta % gcd;
            return origin + delta;
        }

        private float rotationGcd() {
            float sens = (float)mc.options.sensitivity().get();
            float f = sens * 0.6f + 0.2f;
            return f * f * f * 8f * 0.15f;
        }

        private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
            LocalPlayer p = mc.player; if (p == null) return;
            applySmoothedRotation();
            if (running) { rotationStartYaw = scriptedRotationYaw; rotationStartPitch = scriptedRotationPitch; }
            else { rotationStartYaw = p.getYRot(); rotationStartPitch = p.getXRot(); }
            float corrected = targetYaw;
            boolean adaptive = running && tellyAutoPlaceWindow && firstTellyPlacementPending && adaptiveAimValid && System.currentTimeMillis()-adaptiveAimUpdatedAt <= 125L;
            if (adaptive) { corrected = adaptiveAimYaw; targetPitch = adaptiveAimPitch; }
            else if (running) corrected += antiSwayYawOffset;
            rotationStepCounter++;
            corrected += rotationGcd() * YAW_NUDGE_PATTERN[rotationStepCounter % 5];
            rotationTargetYaw = rotationStartYaw + wrapAngle(corrected - rotationStartYaw);
            rotationTargetPitch = clamp(targetPitch, -90f, 90f);
            rotationStartedAt = System.currentTimeMillis();
            rotationDuration = Math.max(1L, duration);
            rotationActive = true;
        }

        private float clamp(float v, float min, float max) { return v < min ? min : (v > max ? max : v); }
        private float wrapAngle(float a) { while (a <= -180f) a += 360f; while (a > 180f) a -= 360f; return a; }

        // ---------- 自适应瞄准 ----------
        private void updateAdaptivePlacementAim(LocalPlayer p) {
            if (!firstTellyPlacementPending) return;
            if (latestStraightPlacedPos != null) {
                int face = travelX > 0 ? Direction.EAST.ordinal() : travelX < 0 ? Direction.WEST.ordinal() : travelZ > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
                int[] target = offsetPos(latestStraightPlacedPos, face);
                if (isStraightTellyTarget(target) && isReplaceable(new BlockPos(target[0], target[1], target[2]))) {
                    Vec3 hitVec = getSupportFaceHitVec(latestStraightPlacedPos, face, 0.5, 0.5);
                    setAdaptiveAimToPoint(p, hitVec);
                }
            }
        }

        private void setAdaptiveAimToPoint(LocalPlayer p, Vec3 point) {
            if (p == null || point == null) return;
            Vec3 eyes = p.getEyePosition(1f);
            double dx = point.x - eyes.x, dy = point.y - eyes.y, dz = point.z - eyes.z;
            double horiz = Math.sqrt(dx*dx + dz*dz);
            if (horiz < 1e-5) return;
            adaptiveAimYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90f);
            adaptiveAimPitch = clamp((float)(-Math.toDegrees(Math.atan2(dy, horiz))), -89f, 89f);
            adaptiveAimUpdatedAt = System.currentTimeMillis();
            adaptiveAimValid = true;
        }

        private Vec3 getSupportFaceHitVec(int[] supportPos, int face, double primary, double secondary) {
            double p = Math.max(0.001, Math.min(0.999, primary));
            double s = Math.max(0.001, Math.min(0.999, secondary));
            Direction dir = Direction.values()[face];
            Vec3 base = new Vec3(supportPos[0], supportPos[1], supportPos[2]);
            if (dir == Direction.NORTH) return base.add(new Vec3(p, s, 0));
            if (dir == Direction.SOUTH) return base.add(new Vec3(p, s, 1));
            if (dir == Direction.EAST) return base.add(new Vec3(1, p, s));
            if (dir == Direction.WEST) return base.add(new Vec3(0, p, s));
            if (dir == Direction.DOWN) return base.add(new Vec3(p, 0, s));
            return base.add(new Vec3(p, 1, s));
        }

        private int[] offsetPos(int[] pos, int face) {
            Direction d = Direction.values()[face];
            return new int[]{pos[0]+d.getStepX(), pos[1]+d.getStepY(), pos[2]+d.getStepZ()};
        }

        private void clearCachedCandidate() {}

        private void initializeStraightBridgeLane(LocalPlayer p) {
            int[] anchor = activationAnchorPos != null ? activationAnchorPos : (hitboxLastPos != null ? hitboxLastPos : null);
            if (anchor == null) {
                BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
                if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    BlockPos pos = hit.getBlockPos();
                    int[] pArr = {pos.getX(), pos.getY(), pos.getZ()};
                    if (isPlayerOnActivationBlock(p, pArr)) { anchor = pArr; activationAnchorPos = pArr.clone(); activationAnchorFace = hit.getDirection().ordinal(); }
                }
            }
            if (anchor != null) { bridgeLaneBlock = travelX != 0 ? anchor[2] : anchor[0]; bridgeStartProgress = anchor[0]*travelX + anchor[2]*travelZ; }
            else { bridgeLaneBlock = travelX != 0 ? (int)Math.floor(p.getZ()) : (int)Math.floor(p.getX()); bridgeStartProgress = (int)Math.floor(p.getX())*travelX + (int)Math.floor(p.getZ())*travelZ; }
            latestStraightPlacedPos = anchor != null ? anchor.clone() : new int[]{(int)Math.floor(p.getX()), (int)Math.floor(p.getY())-1, (int)Math.floor(p.getZ())};
        }

        private void calculateTravelDirection(float yaw) {
            double rad = Math.toRadians(yaw);
            double rawX = Math.sin(rad) - Math.cos(rad);
            double rawZ = -Math.cos(rad) - Math.sin(rad);
            if (Math.abs(rawX) >= Math.abs(rawZ)) { travelX = rawX >= 0 ? 1 : -1; travelZ = 0; }
            else { travelX = 0; travelZ = rawZ >= 0 ? 1 : -1; }
        }

        // ---------- 渲染 ----------
        private void drawActivatePrompt(net.minecraft.client.gui.GuiGraphics gui) {
            if (promptAlpha < 0.05f) return;
            String text = "Activate?";
            int alpha = (int)(promptAlpha * 255);
            if (alpha < 16) alpha = 16;
            int color = (alpha << 24) | promptFadeRgb;
            float x = mc.getWindow().getGuiScaledWidth() / 2f - mc.font.width(text) / 2f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f + 10f;
            gui.drawString(mc.font, text, (int)x, (int)y, color);
        }

        private void drawActivationFaceRegion(net.minecraft.client.gui.GuiGraphics gui) {
            // 简化的激活框渲染（可省略，保持编译通过）
        }

        // ---------- 工具 ----------
        private void printStatus(String msg) {
            if (settings.get("print") && mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§bTelly §7| " + msg));
            }
        }
    }
}