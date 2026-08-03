package com.example.telly;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientTickEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.network.PacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.protocol.game.*;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod("telly")
public class TellyMod {
    public static TellyMod INSTANCE;
    private TellyCore core;

    public TellyMod() {
        INSTANCE = this;
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        core = new TellyCore();
        core.registerKeyBinding();
    }

    @SubscribeEvent public void onClientTick(ClientTickEvent e) { if (core != null) core.onClientTick(e); }
    @SubscribeEvent public void onRenderGui(RenderGuiEvent.Post e) { if (core != null) core.onRenderGui(e); }
    @SubscribeEvent public void onRenderWorld(RenderLevelLastEvent e) { if (core != null) core.onRenderWorld(e); }
    @SubscribeEvent public void onInputKey(InputEvent.Key e) { if (core != null) core.onInputKey(e); }
    @SubscribeEvent public void onInputMouse(InputEvent.Mouse e) { if (core != null) core.onInputMouse(e); }
    @SubscribeEvent public void onPacketSend(PacketEvent.SendToServer e) { if (core != null) core.onPacketSend(e); }
    @SubscribeEvent public void onPacketReceive(PacketEvent.ReceiveFromServer e) { if (core != null) core.onPacketReceive(e); }
    @SubscribeEvent public void onWorldLoad(WorldEvent.Load e) { if (core != null) core.onWorldLoad(e); }
    @SubscribeEvent public void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) { if (core != null) core.onRightClickBlock(e); }
    @SubscribeEvent public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) { if (core != null) core.onLeftClickBlock(e); }

    public static class TellyCore {
        private final Minecraft mc = Minecraft.getInstance();
        private KeyMapping toggleKey;
        private boolean armed = false, running = false;
        private long activatePromptAt = 0L, promptBrokeAt = 0L, promptFadeLastAt = 0L;
        private float promptAlpha = 0f;
        private int promptFadeRgb = 0xFF5555;
        private int[] hitboxLastPos = null, activationAnchorPos = null;
        private int hitboxLastFace = -1, activationAnchorFace = -1;
        private boolean activationMovementHeld = false, eagleDisabledForActivation = false, eagleWasDisabledByTelly = false;
        private boolean safeWalkStateCaptured = false, safeWalkWasEnabled = false;
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
        private long adaptiveAimUpdatedAt = 0L, takeoverDetectionAt = 0L;
        private boolean takeoverCameraValid = false;
        private float takeoverCameraYaw = 0f, takeoverCameraPitch = 0f, takeoverAccumulated = 0f;
        private long takeoverLastFrameAt = 0L, freezeLastTickAt = 0L;
        private boolean ignoreForwardUntilRelease = false, ignoreBackUntilRelease = false, ignoreLeftUntilRelease = false,
                ignoreRightUntilRelease = false, ignoreJumpUntilRelease = false, ignoreSneakUntilRelease = false,
                ignoreSprintUntilRelease = false;
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

        // AutoPlace state
        private int currentClientTick = Integer.MIN_VALUE, placementEvaluationTick = Integer.MIN_VALUE;
        private int lastPlacementAttemptTick = Integer.MIN_VALUE, lastSuccessfulPlaceTick = Integer.MIN_VALUE;
        private int forceSuppressTick = Integer.MIN_VALUE;
        private long totalC08Counter = 0L, c08CounterAtTickBoundary = 0L;
        private boolean hasLastSentServerPos = false;
        private double lastSentServerPosX, lastSentServerPosY, lastSentServerPosZ;
        private Object[] cachedCandidate = null;
        private int cachedCandidateTick = Integer.MIN_VALUE;
        private float cachedCandidateYaw = Float.NaN, cachedCandidatePitch = Float.NaN;
        private boolean candidateResolvedThisTick = false;
        private int[] lastPlacedPos = null, lastSupportPos = null;
        private int lastSupportFace = -1;
        private List<int[]> cachedBelowTargets = null;
        private int cachedBelowTargetsTick = Integer.MIN_VALUE;
        private Map<String, Integer> rejectedTargets = new HashMap<>();
        private int forcedModeCheck = 0;
        private boolean useSuppressed = false, silentPitchActive = false;
        private float silentPitch = 0f;
        private boolean placingViaModule = false, manualC08InWindow = false;
        private boolean tellyAutoPlaceWindow = false, autoPlaceDebugActive = false;
        private final List<int[]> cancelledGhostBlocks = new ArrayList<>();
        private final Map<String, Boolean> settings = new HashMap<>();
        private final Map<String, Object> bridgeStore = new ConcurrentHashMap<>();

        public TellyCore() {
            settings.put("autoSwap", true);
            settings.put("disableSafeWalk", true);
            settings.put("showActivationHitbox", false);
            settings.put("print", false);
            toggleKey = new KeyMapping("key.telly.toggle", GLFW.GLFW_KEY_T, "key.categories.telly");
        }
        public void registerKeyBinding() { net.minecraftforge.client.ClientRegistry.registerKeyBinding(toggleKey); }

        public void onClientTick(ClientTickEvent e) {
            if (e.getPhase() == ClientTickEvent.Phase.START) handleUpdatePre();
            else handleUpdatePost();
            if (running) applyTellyMovementInput();
        }
        public void onRenderGui(RenderGuiEvent.Post e) { drawActivatePrompt(e.getPoseStack()); }
        public void onRenderWorld(RenderLevelLastEvent e) { if (settings.get("showActivationHitbox")) drawActivationFaceRegion(e.getPoseStack(), e.getPartialTick()); }
        public void onInputKey(InputEvent.Key e) {
            if (e.getAction() == GLFW.GLFW_PRESS && toggleKey.isDown()) {
                if (!running && !armed) armAutomation();
                else stopAutomation(true);
            }
            if (mc.player != null) onKey(e.getKey(), e.getAction() != GLFW.GLFW_RELEASE);
        }
        public void onInputMouse(InputEvent.Mouse e) { if (e.getButton() != -1) onMouse(e.getButton(), e.getAction() != GLFW.GLFW_RELEASE); }
        public void onPacketSend(PacketEvent.SendToServer e) {
            if (!running) return;
            Object p = e.getPacket();
            boolean cancel = false;
            if (p instanceof ServerboundUseItemOnPacket) cancel = !onPacketSent((ServerboundUseItemOnPacket)p);
            else if (p instanceof ServerboundPlayerActionPacket) cancel = !onPacketSent((ServerboundPlayerActionPacket)p);
            else if (p instanceof ServerboundMovePlayerPacket) onPacketSent((ServerboundMovePlayerPacket)p);
            else if (p instanceof ServerboundInteractPacket) cancel = !onPacketSent((ServerboundInteractPacket)p);
            if (cancel) e.setCanceled(true);
        }
        public void onPacketReceive(PacketEvent.ReceiveFromServer e) {
            Object p = e.getPacket();
            if (p instanceof ClientboundPlayerPositionPacket && running) stopAutomation(true);
            else if (p instanceof ClientboundBlockUpdatePacket) {
                ClientboundBlockUpdatePacket upd = (ClientboundBlockUpdatePacket)p;
                BlockPos pos = upd.getPos();
                cancelledGhostBlocks.removeIf(arr -> arr[0]==pos.getX() && arr[1]==pos.getY() && arr[2]==pos.getZ());
            }
        }
        public void onWorldLoad(WorldEvent.Load e) { if (mc.player != null) stopAutomation(false); }
        public void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) { if (running) e.setCanceled(true); }
        public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) { if (running) e.setCanceled(true); }

        private void handleUpdatePre() {
            if (!running && armed) updateActivationPrompt();
            if (!running) return;
            enforceSafeWalkDisabledForRun();
            setKeyPressed("attack", false);
            applySmoothedRotation();
            holdScriptedRotation();
            long now = System.currentTimeMillis();
            if (freezeLastTickAt != 0L && now - freezeLastTickAt > 300L) { stopAutomation(true); return; }
            freezeLastTickAt = now;
            LocalPlayer p = mc.player;
            if (p == null || p.isDeadOrDying() || p.fallDistance > 7f) { stopAutomation(true); return; }
            handleAutoSwap(p);
            if (!isHoldingBlock(p)) { stopAutomation(true); return; }
            if (firstTellyPlacementPending) updateAdaptivePlacementAim(p);
            syncPlacementTick(p);
            if (placementEvaluationTick != currentClientTick) {
                placementEvaluationTick = currentClientTick;
                processAutoPlaceTick(p);
            }
        }
        private void handleUpdatePost() {
            c08CounterAtTickBoundary = totalC08Counter;
            manualC08InWindow = false;
        }

        // ---------- Core logic ----------
        private void armAutomation() {
            armed = true; running = false;
            activatePromptAt = 0L; promptBrokeAt = 0L; setupTick = 0; cyclePhase = 19;
            rotationActive = false; activationMovementHeld = false;
            eagleDisabledForActivation = false; eagleWasDisabledByTelly = false;
            printStatus("Armed. Sneak looking down, wait for green, hold rmb and release sneak");
        }
        private void stopAutomation(boolean turnOff) {
            boolean restoreEagle = eagleWasDisabledByTelly;
            armed = false; running = false; setupTick = 0; cyclePhase = 19;
            rotationActive = false; activationMovementHeld = false;
            eagleDisabledForActivation = false; eagleWasDisabledByTelly = false;
            tellyAutoPlaceWindow = false; autoPlaceDebugActive = false;
            antiSwayYawOffset = 0f; antiSwayTapUsed = false;
            firstTellyPlacementPending = false; latestStraightPlacedPos = null;
            activationAnchorPos = null; activationAnchorFace = -1;
            stagedForward = 0f; stagedStrafe = 0f; stagedJump = false; stagedSprint = false;
            adaptiveAimValid = false; adaptiveAimUpdatedAt = 0L;
            scriptedRotationYaw = 0f; scriptedRotationPitch = 0f;
            takeoverDetectionAt = 0L; takeoverCameraValid = false;
            takeoverAccumulated = 0f; takeoverLastFrameAt = 0L;
            cancelledGhostBlocks.clear();
            clearInitialMovementHolds();
            resetControllerState();
            if (mc.player != null) { mc.player.input.forwardImpulse = 0; mc.player.input.leftImpulse = 0; mc.player.input.jumping = false; }
            mc.options.keySprint.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.keyUse.setDown(mc.mouseHandler.isLeftPressed() ? false : false); // restore later
            restoreSafeWalkState();
            freezeLastTickAt = 0L;
            armed = true;
            activatePromptAt = 0L; promptBrokeAt = 0L;
            if (restoreEagle) restoreEagleAfterTelly();
            if (turnOff) printStatus("Stopped. Sneak looking down to arm again");
        }
        private void beginAutomation() {
            LocalPlayer p = mc.player;
            if (p == null || !isHoldingBlock(p)) { printStatus("Hold blocks before starting"); return; }
            if (!isActivationYawAligned(p.getYRot())) return;
            disableSafeWalkForRun();
            baseYaw = Math.round((p.getYRot() - 45f) / 90f) * 90f + 45f;
            calculateTravelDirection(baseYaw);
            antiSwayLane = travelX != 0 ? p.getZ() : p.getX();
            antiSwayYawOffset = 0f; antiSwayTapUsed = false;
            cancelledGhostBlocks.clear();
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
            takeoverDetectionAt = 0L; takeoverCameraValid = false;
            clearInitialMovementHolds();
            resetControllerState();
            setKeyPressed("attack", false);
            applyMovement(-1f, -1f, false, false);
            setRotationTarget(baseYaw, 74.52f, 50L);
            applySmoothedRotation();
            applyUse(true);
            printStatus("Started");
        }
        private void advanceTellyCycle() {
            if (!running) return;
            suppressSneakInput();
            enforceSafeWalkDisabledForRun();
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
                takeoverDetectionAt = System.currentTimeMillis() + 125L;
                takeoverCameraValid = mc.player != null;
                takeoverAccumulated = 0f; takeoverLastFrameAt = System.currentTimeMillis();
                if (mc.player != null) { takeoverCameraYaw = mc.player.getYRot(); takeoverCameraPitch = mc.player.getXRot(); }
                captureInitialMovementHolds();
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
        private void applyTellyMovementInput() {
            if (!running) return;
            suppressSneakInput();
            enforceSafeWalkDisabledForRun();
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
        private void enforceSafeWalkDisabledForRun() {
            if (safeWalkStateCaptured) {
                // 直接禁用shift
                mc.options.keyShift.setDown(false);
                if (mc.player != null) mc.player.input.shiftKeyDown = false;
            }
        }
        private void disableSafeWalkForRun() {
            if (safeWalkStateCaptured) return;
            if (!settings.get("disableSafeWalk")) return;
            safeWalkStateCaptured = true;
            safeWalkWasEnabled = false; // 实际我们直接禁用shift
        }
        private void restoreSafeWalkState() {
            if (!safeWalkStateCaptured) return;
            safeWalkStateCaptured = false;
            // 不恢复，保持禁用
        }
        private void restoreEagleAfterTelly() {}
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
            float sens = mc.options.sensitivity().get();
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

        // Activation prompt
        private void updateActivationPrompt() {
            LocalPlayer p = mc.player; if (p == null || mc.screen != null) { clearActivationPrompt(); return; }
            setActivationMovementHold(activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 1000L && mc.mouseHandler.isLeftPressed());
            boolean lookingDown = p.getXRot() >= 75f;
            boolean atEdge = lookingDown && isLookingAtEdge(p);
            if (mc.options.keyShift.isDown() && atEdge) {
                if (activatePromptAt == 0L) activatePromptAt = System.currentTimeMillis();
                promptBrokeAt = 0L;
                captureActivationAnchor(p);
                if (activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 850L) setKeyPressed("use", false);
                if (activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 1000L) {
                    disableSafeWalkForRun();
                    if (mc.mouseHandler.isLeftPressed()) {
                        if (safeWalkStateCaptured) enforceSafeWalkDisabledForRun();
                    } else if (safeWalkStateCaptured) restoreSafeWalkState();
                }
                return;
            }
            if (activatePromptAt == 0L) return;
            if (System.currentTimeMillis()-activatePromptAt < 1000L) { clearActivationPrompt(); return; }
            if (promptBrokeAt == 0L) { promptFadeRgb = 0x55FF55; promptBrokeAt = System.currentTimeMillis(); }
            setKeyPressed("use", false);
            if (!mc.options.keyShift.isDown() && mc.mouseHandler.isLeftPressed() && isActivationYawAligned(p.getYRot())) {
                promptFadeRgb = 0x55FF55;
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
            eagleDisabledForActivation = false;
            setActivationMovementHold(false);
            if (!running) restoreSafeWalkState();
        }
        private void setActivationMovementHold(boolean hold) {
            if (hold) { activationMovementHeld = true; setKeyPressed("back", true); setKeyPressed("right", true); }
            else if (activationMovementHeld) { activationMovementHeld = false; setKeyPressed("back", false); setKeyPressed("right", false); }
        }
        private boolean isLookingAtEdge(LocalPlayer p) {
            if (!isActivationYawAligned(p.getYRot())) return false;
            BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
            Direction face = hit.getDirection();
            if (face.getAxis() == Direction.Axis.Y) return false;
            Vec3 pos = hit.getBlockPos().getCenter();
            Vec3 hitVec = hit.getLocation();
            Vec3 local = hitVec.subtract(pos);
            double across = face.getAxis() == Direction.Axis.X ? local.z : local.x;
            if (face == Direction.NORTH || face == Direction.WEST) across = 1 - across;
            if (across < ACTIVATION_ACROSS_MIN || across > ACTIVATION_ACROSS_MAX) return false;
            double height = local.y;
            if (height < ACTIVATION_HEIGHT_MIN || height > ACTIVATION_HEIGHT_MAX) return false;
            int[] travel = travelDirectionFromYaw(p.getYRot());
            int travelFace = travel[0] > 0 ? Direction.EAST.ordinal() : travel[0] < 0 ? Direction.WEST.ordinal() : travel[1] > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
            if (face.ordinal() != travelFace) return false;
            BlockPos blockPos = hit.getBlockPos();
            int[] posArr = {blockPos.getX(), blockPos.getY(), blockPos.getZ()};
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
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) { if (hitboxLastPos != null && hitboxLastFace >= 2) { activationAnchorPos = hitboxLastPos.clone(); activationAnchorFace = hitboxLastFace; } return; }
            Direction face = hit.getDirection();
            if (face.getAxis() == Direction.Axis.Y) return;
            BlockPos pos = hit.getBlockPos();
            int[] posArr = {pos.getX(), pos.getY(), pos.getZ()};
            if (!isPlayerOnActivationBlock(p, posArr)) return;
            Vec3 local = hit.getLocation().subtract(pos.getCenter());
            double across = face.getAxis() == Direction.Axis.X ? local.z : local.x;
            if (face == Direction.NORTH || face == Direction.WEST) across = 1 - across;
            if (across < ACTIVATION_ACROSS_MIN || across > ACTIVATION_ACROSS_MAX) return;
            int[] travel = travelDirectionFromYaw(p.getYRot());
            int travelFace = travel[0] > 0 ? Direction.EAST.ordinal() : travel[0] < 0 ? Direction.WEST.ordinal() : travel[1] > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
            if (face.ordinal() != travelFace) return;
            activationAnchorPos = posArr.clone(); activationAnchorFace = face.ordinal();
            hitboxLastPos = posArr.clone(); hitboxLastFace = face.ordinal();
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

        private void drawActivationPrompt(PoseStack pose) {
            if (promptAlpha < 0.05f) return;
            String text = "Activate?";
            int alpha = (int)(promptAlpha * 255);
            if (alpha < 16) alpha = 16;
            int color = (alpha << 24) | promptFadeRgb;
            float x = mc.getWindow().getGuiScaledWidth() / 2f - mc.font.width(text) / 2f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f + 10f;
            mc.font.draw(pose, text, x, y, color);
        }
        private void drawActivationFaceRegion(PoseStack pose, float partial) {
            if (promptAlpha < 0.05f || hitboxLastPos == null || hitboxLastFace < 2) return;
            // 简化渲染，略
        }
        // 为了节省空间，省略渲染实现，但可加

        // AutoPlace methods (精简)
        private void processAutoPlaceTick(LocalPlayer p) {
            // 简化：直接放置
            if (!tellyAutoPlaceWindow || !isHoldingBlock(p)) return;
            if (!isReplaceable(new BlockPos((int)Math.floor(p.getX()), (int)Math.floor(p.getY())-1, (int)Math.floor(p.getZ())))) return;
            // 根据脚本视角搜索放置
            float yaw = running ? scriptedRotationYaw : p.getYRot();
            float pitch = running ? scriptedRotationPitch : p.getXRot();
            BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
            BlockPos support = hit.getBlockPos();
            Direction face = hit.getDirection();
            BlockPos target = support.relative(face);
            if (!isReplaceable(target)) return;
            if (!isStraightTellyTarget(new int[]{target.getX(), target.getY(), target.getZ()})) return;
            // 放置
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            totalC08Counter++;
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
            return state.isAir() || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.GRASS || state.getBlock() == Blocks.TALL_GRASS;
        }
        private void initializeStraightBridgeLane(LocalPlayer p) {
            int[] anchor = activationAnchorPos != null ? activationAnchorPos : (hitboxLastPos != null ? hitboxLastPos : null);
            if (anchor == null) {
                BlockHitResult hit = mc.hitResult instanceof BlockHitResult ? (BlockHitResult)mc.hitResult : null;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
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
        private boolean isHoldingBlock(LocalPlayer p) { return p.getMainHandItem().getItem() instanceof BlockItem; }
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
        private void syncPlacementTick(LocalPlayer p) {
            int tick = p.tickCount;
            if (tick == currentClientTick) return;
            currentClientTick = tick;
            candidateResolvedThisTick = false;
            silentPitchActive = false;
        }
        private void resetControllerState() {
            currentClientTick = Integer.MIN_VALUE;
            placementEvaluationTick = Integer.MIN_VALUE;
            lastPlacementAttemptTick = Integer.MIN_VALUE;
            lastSuccessfulPlaceTick = Integer.MIN_VALUE;
            forceSuppressTick = Integer.MIN_VALUE;
            totalC08Counter = 0L;
            c08CounterAtTickBoundary = 0L;
            hasLastSentServerPos = false;
            clearCachedCandidate();
            lastPlacedPos = null; lastSupportPos = null; lastSupportFace = -1;
            cachedBelowTargets = null; cachedBelowTargetsTick = Integer.MIN_VALUE;
            rejectedTargets.clear();
            forcedModeCheck = 0;
            useSuppressed = false; silentPitchActive = false;
            placingViaModule = false; manualC08InWindow = false;
        }
        private void clearCachedCandidate() { cachedCandidate = null; cachedCandidateTick = Integer.MIN_VALUE; cachedCandidateYaw = Float.NaN; cachedCandidatePitch = Float.NaN; candidateResolvedThisTick = false; }
        private void updateAdaptivePlacementAim(LocalPlayer p) {
            if (!firstTellyPlacementPending) return;
            // 简化：使用上次放置位置
            if (latestStraightPlacedPos != null) {
                int[] support = latestStraightPlacedPos;
                int face = travelX > 0 ? Direction.EAST.ordinal() : travelX < 0 ? Direction.WEST.ordinal() : travelZ > 0 ? Direction.SOUTH.ordinal() : Direction.NORTH.ordinal();
                int[] target = offsetPos(support, face);
                if (isStraightTellyTarget(target) && isReplaceable(new BlockPos(target[0], target[1], target[2]))) {
                    Vec3 hitVec = getSupportFaceHitVec(support, face, 0.5, 0.5);
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
        private boolean onPacketSent(ServerboundUseItemOnPacket pkt) {
            if (!running) return true;
            BlockPos pos = pkt.getBlockPos();
            Direction face = pkt.getDirection();
            BlockPos target = pos.relative(face);
            int[] t = {target.getX(), target.getY(), target.getZ()};
            if (!isStraightTellyTarget(t)) { cancelledGhostBlocks.add(new int[]{pos.getX(), pos.getY(), pos.getZ()}); return false; }
            return true;
        }
        private boolean onPacketSent(ServerboundPlayerActionPacket pkt) {
            if (!running) return true;
            if (pkt.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return false;
            return true;
        }
        private boolean onPacketSent(ServerboundMovePlayerPacket pkt) { return true; }
        private boolean onPacketSent(ServerboundInteractPacket pkt) { return !"ATTACK".equals(pkt.getAction().name()); }
        private void onKey(int key, boolean pressed) {
            if (!running) return;
            if (key == mc.options.keyShift.getKey().getValue()) { suppressSneakInput(); return; }
            if (!pressed) clearInitialMovementHold(key);
            if (pressed && setupTick < 0 && isManualMovementKey(key) && !isInitialMovementHold(key) && !isScriptHeldKey(key)) stopAutomation(true);
        }
        private void onMouse(int button, boolean pressed) {
            if (running) {
                if (button == 0) { setKeyPressed("attack", false); return; }
                if (button == 1) { setKeyPressed("use", tellyAutoPlaceWindow); return; }
            }
            if (armed && button == 1 && !pressed) setActivationMovementHold(false);
            if (armed && activatePromptAt != 0L && System.currentTimeMillis()-activatePromptAt >= 850L && button == 1) return;
        }
        private boolean isManualMovementKey(int key) {
            return key == mc.options.keyUp.getKey().getValue() || key == mc.options.keyDown.getKey().getValue() ||
                    key == mc.options.keyLeft.getKey().getValue() || key == mc.options.keyRight.getKey().getValue() ||
                    key == mc.options.keyJump.getKey().getValue() || key == mc.options.keyShift.getKey().getValue() ||
                    key == mc.options.keySprint.getKey().getValue();
        }
        private boolean isScriptHeldKey(int key) {
            return false; // 简化
        }
        private boolean isInitialMovementHold(int key) {
            if (key == mc.options.keyUp.getKey().getValue()) return ignoreForwardUntilRelease;
            if (key == mc.options.keyDown.getKey().getValue()) return ignoreBackUntilRelease;
            if (key == mc.options.keyLeft.getKey().getValue()) return ignoreLeftUntilRelease;
            if (key == mc.options.keyRight.getKey().getValue()) return ignoreRightUntilRelease;
            if (key == mc.options.keyJump.getKey().getValue()) return ignoreJumpUntilRelease;
            if (key == mc.options.keyShift.getKey().getValue()) return ignoreSneakUntilRelease;
            if (key == mc.options.keySprint.getKey().getValue()) return ignoreSprintUntilRelease;
            return false;
        }
        private void clearInitialMovementHold(int key) {
            if (key == mc.options.keyUp.getKey().getValue()) ignoreForwardUntilRelease = false;
            if (key == mc.options.keyDown.getKey().getValue()) ignoreBackUntilRelease = false;
            if (key == mc.options.keyLeft.getKey().getValue()) ignoreLeftUntilRelease = false;
            if (key == mc.options.keyRight.getKey().getValue()) ignoreRightUntilRelease = false;
            if (key == mc.options.keyJump.getKey().getValue()) ignoreJumpUntilRelease = false;
            if (key == mc.options.keyShift.getKey().getValue()) ignoreSneakUntilRelease = false;
            if (key == mc.options.keySprint.getKey().getValue()) ignoreSprintUntilRelease = false;
        }
        private void clearInitialMovementHolds() {
            ignoreForwardUntilRelease = ignoreBackUntilRelease = ignoreLeftUntilRelease = ignoreRightUntilRelease =
            ignoreJumpUntilRelease = ignoreSneakUntilRelease = ignoreSprintUntilRelease = false;
        }
        private void captureInitialMovementHolds() {
            ignoreForwardUntilRelease = mc.options.keyUp.isDown();
            ignoreBackUntilRelease = mc.options.keyDown.isDown();
            ignoreLeftUntilRelease = mc.options.keyLeft.isDown();
            ignoreRightUntilRelease = mc.options.keyRight.isDown();
            ignoreJumpUntilRelease = mc.options.keyJump.isDown();
            ignoreSneakUntilRelease = mc.options.keyShift.isDown();
            ignoreSprintUntilRelease = mc.options.keySprint.isDown();
        }
        private void printStatus(String msg) {
            if (settings.get("print") && mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§bTelly §7| " + msg));
        }
        // 其他辅助
        private boolean antiSwayTapUsed = false;
    }
}