package io.github.jengamon.novation.modes;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.controller.api.*;
import io.github.jengamon.novation.Utils;
import io.github.jengamon.novation.internal.Session;
import io.github.jengamon.novation.modes.session.ArrowPadLight;
import io.github.jengamon.novation.modes.session.SessionPadLight;
import io.github.jengamon.novation.surface.LaunchpadXPad;
import io.github.jengamon.novation.surface.LaunchpadXSurface;
import io.github.jengamon.novation.surface.state.PadLightState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionMode extends AbstractMode {
    private final SessionSceneLight[] sceneLights = new SessionSceneLight[8];
    private final HardwareActionBindable[] sceneLaunchActions = new HardwareActionBindable[8];
    private final HardwareActionBindable[] sceneLaunchReleaseActions = new HardwareActionBindable[8];
    private final SessionPadLight[][] padLights = new SessionPadLight[8][8];
    private final HardwareActionBindable[][] padActions = new HardwareActionBindable[8][8];
    private final HardwareActionBindable[][] padReleaseActions = new HardwareActionBindable[8][8];
    private final ArrowPadLight[] arrowLights = new ArrowPadLight[4];
    private final HardwareBindable[] arrowActions;

    // References so we can compute local indices & touch pad lights
    private final ControllerHost host;
    private final TrackBank trackBank;
    private final SceneBank sceneBank;
    private final BooleanValue mPulseSessionPads;
    private final RangedValue bpm;
    private final LaunchpadXSurface surface;

    // Color index for “yellow-ish” flash (tweak if needed)
    private static final int FLASH_YELLOW_COLOR = 62;
    // How long the pad should blink (ms)
    private static final int FLASH_DURATION_MS = 2000; // 1.2 seconds, tweak to taste


    /**
     * Flash a single pad (one clip slot) using a yellow blink, then restore the
     * exact previous PadLightState for that pad.
     *
     * globalTrackIndex / globalSceneIndex come from BitX (0..N),
     * we map them into the current 8×8 Launchpad window using scrollPosition().
     */
    public void flashPadFromGlobalVisual(int globalTrackIndex, int globalSceneIndex) {
        int trackScroll = trackBank.scrollPosition().get();
        int sceneScroll = sceneBank.scrollPosition().get();

        int localTrack = globalTrackIndex - trackScroll;
        int localScene = globalSceneIndex - sceneScroll;

        host.println("Pad visual flash: globalTrack=" + globalTrackIndex +
                " globalScene=" + globalSceneIndex +
                " trackScroll=" + trackScroll +
                " sceneScroll=" + sceneScroll +
                " -> localTrack=" + localTrack +
                " localScene=" + localScene);

        // Check if that pad is visible in the 8×8 grid
        if (localTrack < 0 || localTrack >= 8 || localScene < 0 || localScene >= 8) {
            host.println("Pad visual flash: target pad not in current window, no flash.");
            return;
        }

        LaunchpadXPad[][] pads = surface.notes();
        MultiStateHardwareLight light = pads[localScene][localTrack].light();

        // Save original state
        InternalHardwareLightState current = light.state().currentValue();
        PadLightState originalState = (current instanceof PadLightState)
                ? (PadLightState) current
                : null;

        byte solid = 0;
        byte blink = 0;
        byte pulse = 0;

        if (originalState != null) {
            solid = originalState.solid();
            blink = originalState.blink();
            pulse = originalState.pulse();
        }

        // Create a yellow blink over the existing solid/pulse
        PadLightState flashState = new PadLightState(
                bpm.getRaw(),
                solid,
                (byte) FLASH_YELLOW_COLOR,   // blink color = yellow-ish
                pulse
        );

        light.state().setValue(flashState);
        host.println("   ⚡ Flashing pad [scene=" + localScene + ", track=" + localTrack + "] yellow");
        host.requestFlush();

        // Restore original state after ~500ms
        host.scheduleTask(() -> {
            host.println("   ⏹ Restoring pad [scene=" + localScene + ", track=" + localTrack + "]");
            LaunchpadXPad[][] padsAfter = surface.notes();
            MultiStateHardwareLight lightAfter = padsAfter[localScene][localTrack].light();

            if (originalState != null) {
                lightAfter.state().setValue(originalState);
            } else {
                padLights[localScene][localTrack].draw(lightAfter);
            }

            host.requestFlush();
        }, FLASH_DURATION_MS);

    }

    private class SessionSceneLight {
        private final RangedValue mBPM;
        private final BooleanValue mPulseSessionPads;
        private final ColorValue mSceneColor;
        private final BooleanValue mSceneExists;

        public SessionSceneLight(LaunchpadXSurface surface, Scene scene, BooleanValue pulseSessionPads, RangedValue bpm) {
            mBPM = bpm;
            mPulseSessionPads = pulseSessionPads;
            mSceneColor = scene.color();
            mSceneExists = scene.exists();

            mSceneColor.addValueObserver((r, g, b) -> redraw(surface));
            mSceneExists.addValueObserver(e -> redraw(surface));
            mBPM.addValueObserver(b -> redraw(surface));
            mPulseSessionPads.addValueObserver(p -> redraw(surface));
        }

        public void draw(MultiStateHardwareLight sceneLight) {
            Color baseColor = mSceneColor.get();
            if (mSceneExists.get()) {
                if (mPulseSessionPads.get()) {
                    sceneLight.state().setValue(PadLightState.pulseLight(mBPM.getRaw(), Utils.toNovation(baseColor)));
                } else {
                    sceneLight.setColor(baseColor);
                }
            }
        }
    }

    public SessionMode(TrackBank bank,
                       Transport transport,
                       LaunchpadXSurface surface,
                       ControllerHost host,
                       BooleanValue pulseSessionPads,
                       AtomicBoolean launchAlt) {

        this.host = host;
        this.trackBank = bank;
        this.sceneBank = bank.sceneBank();
        this.mPulseSessionPads = pulseSessionPads;
        this.bpm = transport.tempo().modulatedValue();
        this.surface = surface;

        // We need scroll positions so we can map global scene index → local row
        this.sceneBank.scrollPosition().markInterested();
        this.trackBank.scrollPosition().markInterested();

        RangedValue bpm = this.bpm;

        // Set up scene buttons
        for (int i = 0; i < 8; i++) {
            Scene scene = sceneBank.getItemAt(i);
            sceneLights[i] = new SessionSceneLight(surface, scene, pulseSessionPads, bpm);
            int finalI = i;
            sceneLaunchActions[i] = host.createAction(() -> {
                if (launchAlt.get()) {
                    scene.launchAlt();
                } else {
                    scene.launch();
                }
                scene.selectInEditor();
            }, () -> "Press Scene " + finalI);
            sceneLaunchReleaseActions[i] = host.createAction(() -> {
                if (launchAlt.get()) {
                    scene.launchReleaseAlt();
                } else {
                    scene.launchRelease();
                }
            }, () -> "Release Scene " + finalI);
        }

        // Setup pad lights and buttons
        /*
        The indices map the pad out as
        0,0.......0,7
        1,0.......1,7
        ...
        7,0.......7,7

        since we want scenes to go down, we simply mark the indices as (scene, track)
         */
        for (int scene = 0; scene < 8; scene++) {
            padActions[scene] = new HardwareActionBindable[8];
            padLights[scene] = new SessionPadLight[8];
            for (int trk = 0; trk < 8; trk++) {
                Track track = bank.getItemAt(trk);
                ClipLauncherSlotBank slotBank = track.clipLauncherSlotBank();
                ClipLauncherSlot slot = slotBank.getItemAt(scene);

                int finalTrk = trk;
                int finalScene = scene;
                padLights[scene][trk] = new SessionPadLight(surface, slot, track, bpm, this::redraw, scene);
                padActions[scene][trk] = host.createAction(() -> {
                    if (launchAlt.get()) {
                        slot.launchAlt();
                    } else {
                        slot.launch();
                    }
                }, () -> "Press Scene " + finalScene + " Track " + finalTrk);
                padReleaseActions[scene][trk] = host.createAction(() -> {
                    if (launchAlt.get()) {
                        slot.launchReleaseAlt();
                    } else {
                        slot.launchRelease();
                    }
                }, () -> "Release Scene " + finalScene + " Track " + finalTrk);
            }
        }

        arrowActions = new HardwareActionBindable[]{
                sceneBank.scrollBackwardsAction(),
                sceneBank.scrollForwardsAction(),
                bank.scrollBackwardsAction(),
                bank.scrollForwardsAction()
        };

        BooleanValue[] arrowEnabled = new BooleanValue[]{
                sceneBank.canScrollBackwards(),
                sceneBank.canScrollForwards(),
                bank.canScrollBackwards(),
                bank.canScrollForwards()
        };

        LaunchpadXPad[] arrows = surface.arrows();
        for (int i = 0; i < arrows.length; i++) {
            arrowLights[i] = new ArrowPadLight(surface, arrowEnabled[i], this::redraw);
        }
    }

    // ======= NEW API: called from LaunchpadXExtension when BitX sends OSC =======

    /**
     * Flash the whole scene row (all 8 pads) using a yellow blink, then restore
     * the exact previous PadLightState for each pad.
     *
     * globalSceneIndex is the absolute scene index from BitX (0..N),
     * mapped into the current 8-row window using sceneBank.scrollPosition().
     */
    public void flashSceneRowFromGlobalVisual(int globalSceneIndex) {
        int sceneScroll = sceneBank.scrollPosition().get();
        int localScene = globalSceneIndex - sceneScroll;

        host.println("Scene visual flash: globalScene=" + globalSceneIndex +
                " scroll=" + sceneScroll + " -> localScene=" + localScene);

        if (localScene < 0 || localScene >= 8) {
            host.println("Scene visual flash: target scene not in current window, no flash.");
            return;
        }

        LaunchpadXPad[][] pads = surface.notes();

        // Save original states for this row
        PadLightState[] originalStates = new PadLightState[8];

        for (int trk = 0; trk < 8; trk++) {
            MultiStateHardwareLight light = pads[localScene][trk].light();

            InternalHardwareLightState current = light.state().currentValue();
            PadLightState currentState = (current instanceof PadLightState)
                    ? (PadLightState) current
                    : null;

            originalStates[trk] = currentState;

            byte solid = 0;
            byte blink = 0;
            byte pulse = 0;

            if (currentState != null) {
                solid = currentState.solid();
                blink = currentState.blink();
                pulse = currentState.pulse();
            }

            // Create a *yellow blink* over the existing solid/pulse
            PadLightState flashState = new PadLightState(
                    bpm.getRaw(),
                    solid,
                    (byte) FLASH_YELLOW_COLOR, // blink color
                    pulse
            );

            light.state().setValue(flashState);
        }

        host.println("   ⚡ Flashing scene row " + localScene + " yellow");
        host.requestFlush();

        // Restore the original states after ~500ms (feels like the green "whoosh")
        host.scheduleTask(() -> {
            host.println("   ⏹ Restoring scene row " + localScene);
            LaunchpadXPad[][] padsAfter = surface.notes();

            for (int trk = 0; trk < 8; trk++) {
                MultiStateHardwareLight light = padsAfter[localScene][trk].light();
                PadLightState original = originalStates[trk];

                if (original != null) {
                    // If we had a real previous state, restore it exactly
                    light.state().setValue(original);
                } else {
                    // Fall back to normal drawing if somehow null
                    padLights[localScene][trk].draw(light);
                }
            }

            host.requestFlush();
        }, 500);
    }

    // ==========================================================================

    @Override
    public List<HardwareBinding> onBind(LaunchpadXSurface surface) {
        List<HardwareBinding> bindings = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            bindings.add(surface.scenes()[i].button().pressedAction().addBinding(sceneLaunchActions[i]));
            bindings.add(surface.scenes()[i].button().releasedAction().addBinding(sceneLaunchReleaseActions[i]));
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                bindings.add(surface.notes()[i][j].button().pressedAction().addBinding(padActions[i][j]));
                bindings.add(surface.notes()[i][j].button().releasedAction().addBinding(padReleaseActions[i][j]));
            }
        }
        LaunchpadXPad[] arrows = surface.arrows();
        for (int i = 0; i < 4; i++) {
            bindings.add(arrows[i].button().pressedAction().addBinding(arrowActions[i]));
        }
        return bindings;
    }

    @Override
    public void onDraw(LaunchpadXSurface surface) {
        LaunchpadXPad[] scenes = surface.scenes();
        for (int i = 0; i < scenes.length; i++) {
            sceneLights[i].draw(scenes[i].light());
        }
        LaunchpadXPad[] arrows = surface.arrows();
        for (int i = 0; i < arrows.length; i++) {
            arrowLights[i].draw(surface.arrows()[i].light());
        }

        LaunchpadXPad[][] pads = surface.notes();
        for (int scene = 0; scene < pads.length; scene++) {
            for (int trk = 0; trk < pads[scene].length; trk++) {
                padLights[scene][trk].draw(pads[scene][trk].light());
            }
        }
    }

    @Override
    public void finishedBind(Session session) {
        session.sendSysex("14 00 00");
        session.sendSysex("00 00");
    }
}
