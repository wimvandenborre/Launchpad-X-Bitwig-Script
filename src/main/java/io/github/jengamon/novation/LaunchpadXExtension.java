// LaunchpadXExtension.java

package io.github.jengamon.novation;

import com.bitwig.extension.api.util.midi.ShortMidiMessage;
import com.bitwig.extension.callback.ObjectValueChangedCallback;
import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.api.*;
import io.github.jengamon.novation.internal.ChannelType;
import io.github.jengamon.novation.internal.HostErrorOutputStream;
import io.github.jengamon.novation.internal.HostOutputStream;
import io.github.jengamon.novation.internal.Session;
import io.github.jengamon.novation.modes.AbstractMode;
import io.github.jengamon.novation.modes.DrumPadMode;
import io.github.jengamon.novation.modes.SessionMode;
import io.github.jengamon.novation.modes.mixer.*;
import io.github.jengamon.novation.surface.LaunchpadXSurface;
import io.github.jengamon.novation.surface.state.PadLightState;
import com.bitwig.extension.api.opensoundcontrol.OscModule;
import com.bitwig.extension.api.opensoundcontrol.OscAddressSpace;
import com.bitwig.extension.api.opensoundcontrol.OscConnection;
import com.bitwig.extension.api.opensoundcontrol.OscMessage;
import com.bitwig.extension.api.opensoundcontrol.OscMethodCallback;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LaunchpadXExtension extends ControllerExtension {
    private Session mSession;
    private HardwareSurface mSurface;
    private LaunchpadXSurface mLSurface;
    private ModeMachine mMachine;

    // We keep a reference so OSC handler can ask it to flash a scene row
    private SessionMode mSessionMode;

    private final static String CLIP_LAUNCHER = "Clip Launcher";
    private final static String GLOBAL = "Global";
    private final static String TOGGLE_RECORD = "Toggle Record";
    private final static String CYCLE_TRACKS = "Cycle Tracks";
    private final static String LAUNCH_ALT = "Launch Alt";

    private SettableStringValue oscReceiveIpSetting;
    private SettableRangedValue oscReceivePortSetting;

    protected LaunchpadXExtension(final LaunchpadXExtensionDefinition definition, final ControllerHost host) {
        super(definition, host);
    }

    @Override
    public void init() {
        final ControllerHost host = getHost();

        Preferences prefs = host.getPreferences();
        DocumentState documentPrefs = host.getDocumentState();
        BooleanValue mSwapOnBoot = prefs.getBooleanSetting("Swap to Session on Boot?", "Behavior", true);
        BooleanValue mPulseSessionPads = prefs.getBooleanSetting("Pulse Session Scene Pads?", "Behavior", false);
        BooleanValue mViewableBanks = prefs.getBooleanSetting("Viewable Bank?", "Behavior", true);
        BooleanValue mStopClipsBeforeToggle = prefs.getBooleanSetting("Stop Recording Clips before Toggle Record?", "Record Button", false);

        EnumValue mRecordLevel = documentPrefs.getEnumSetting("Rec. Target", "Record Button", new String[]{GLOBAL, CLIP_LAUNCHER}, CLIP_LAUNCHER);
        EnumValue mRecordAction = documentPrefs.getEnumSetting("Action", "Record Button", new String[]{TOGGLE_RECORD, CYCLE_TRACKS, LAUNCH_ALT}, TOGGLE_RECORD);

        oscReceiveIpSetting = prefs.getStringSetting("Osc Receive IP", "OSC", 15, "127.0.0.1");
        oscReceivePortSetting = prefs.getNumberSetting("Osc Receive Port", "OSC", 1024, 65535, 1, "", 8000);

        // Replace System.out and System.err with ones that actually log in Bitwig
        System.setOut(new PrintStream(new HostOutputStream(host)));
        System.setErr(new PrintStream(new HostErrorOutputStream(host)));

        // Create state objects
        mSession = new Session(host);
        mSurface = host.createHardwareSurface();
        Transport mTransport = host.createTransport();
        CursorTrack mCursorTrack = host.createCursorTrack(8, 0);
        CursorDevice mCursorDevice = mCursorTrack.createCursorDevice(
                "Primary", "Primary Instrument", 0, CursorDeviceFollowMode.FIRST_INSTRUMENT);
        CursorDevice mControlsCursorDevice = mCursorTrack.createCursorDevice(
                "Primary IoE", "Primary Device", 0, CursorDeviceFollowMode.FOLLOW_SELECTION);
        TrackBank mSessionTrackBank = host.createTrackBank(8, 0, 8, true);
        mSessionTrackBank.setSkipDisabledItems(true);

        mViewableBanks.addValueObserver(vb -> mSessionTrackBank.sceneBank().setIndication(vb));

        // --- OSC FOLLOW SETUP ---
        setupBitxOscFollow(host, mSessionTrackBank);

        mCursorTrack.playingNotes().addValueObserver(new ObjectValueChangedCallback<PlayingNote[]>() {
            @Override
            public void valueChanged(PlayingNote[] playingNotes) {
                for (int pitch : mPrevPitches) {
                    mSession.midiOut(ChannelType.DAW).sendMidi(0x8f, pitch, 0);
                }
                mPrevPitches.clear();
                for (PlayingNote playingNote : playingNotes) {
                    mSession.midiOut(ChannelType.DAW).sendMidi(0x9f, playingNote.pitch(), 21);
                    mPrevPitches.add(playingNote.pitch());
                }
            }
            final ArrayList<Integer> mPrevPitches = new ArrayList<>();
        });

        // Create surface & mode machine
        mSurface.setPhysicalSize(241, 241);
        mLSurface = new LaunchpadXSurface(host, mSession, mSurface);
        mMachine = new ModeMachine(mSession);

        AtomicBoolean launchAlt = new AtomicBoolean(false);
        AtomicBoolean launchAltConfig = new AtomicBoolean(false);

        // --- SESSION MODE (keep reference in mSessionMode) ---
        mSessionMode = new SessionMode(mSessionTrackBank, mTransport, mLSurface, host, mPulseSessionPads, launchAlt);
        mMachine.register(Mode.SESSION, mSessionMode);

        // Drum & mixer modes unchanged...
        mMachine.register(Mode.DRUM, new DrumPadMode(host, mSession, mLSurface, mCursorDevice));
        mMachine.register(Mode.UNKNOWN, new AbstractMode() {
            @Override
            public List<HardwareBinding> onBind(LaunchpadXSurface surface) {
                return new ArrayList<>();
            }
        });

        AtomicReference<Mode> mixerMode = new AtomicReference<>(Mode.MIXER_VOLUME);
        mMachine.register(Mode.MIXER_VOLUME, new VolumeMixer(mixerMode, host, mTransport, mLSurface, mSessionTrackBank));
        mMachine.register(Mode.MIXER_PAN, new PanMixer(mixerMode, host, mTransport, mLSurface, mSessionTrackBank));
        mMachine.register(Mode.MIXER_SEND, new SendMixer(mixerMode, host, mTransport, mLSurface, mCursorTrack));
        mMachine.register(Mode.MIXER_CONTROLS, new ControlsMixer(mixerMode, host, mTransport, mLSurface, mControlsCursorDevice));
        mMachine.register(Mode.MIXER_STOP, new StopClipMixer(mixerMode, host, mTransport, mLSurface, mSessionTrackBank, launchAlt));
        mMachine.register(Mode.MIXER_MUTE, new MuteMixer(mixerMode, host, mTransport, mLSurface, mSessionTrackBank, launchAlt));
        mMachine.register(Mode.MIXER_SOLO, new SoloMixer(mixerMode, host, mTransport, mLSurface, mSessionTrackBank, launchAlt));
        mMachine.register(Mode.MIXER_ARM, new RecordArmMixer(mixerMode, host, mTransport, mLSurface, mSessionTrackBank, launchAlt));

        // Record button behaviour (unchanged – your existing code)
        mCursorTrack.hasNext().markInterested();
        AtomicBoolean recordActionToggle = new AtomicBoolean(false);
        AtomicBoolean recordLevelGlobal = new AtomicBoolean(false);
        mRecordAction.addValueObserver(val -> {
            recordActionToggle.set(val.equals(TOGGLE_RECORD));
            launchAltConfig.set(val.equals(LAUNCH_ALT));
        });
        mRecordLevel.addValueObserver(val -> recordLevelGlobal.set(val.equals("Global")));

        ClipLauncherSlotBank[] clsBanks = new ClipLauncherSlotBank[8];
        for (int i = 0; i < mSessionTrackBank.getSizeOfBank(); i++) {
            Track track = mSessionTrackBank.getItemAt(i);
            ClipLauncherSlotBank slotbank = track.clipLauncherSlotBank();
            clsBanks[i] = slotbank;
            for (int j = 0; j < slotbank.getSizeOfBank(); j++) {
                ClipLauncherSlot slot = slotbank.getItemAt(j);
                slot.isRecording().markInterested();
            }
        }

        Runnable selectAction = () -> {
            if (recordActionToggle.get()) {
                boolean clipStopped = false;

                if (mStopClipsBeforeToggle.get()) {
                    for (ClipLauncherSlotBank bank : clsBanks) {
                        int targetSlot = -1;
                        for (int i = 0; i < bank.getSizeOfBank(); i++) {
                            ClipLauncherSlot slot = bank.getItemAt(i);
                            if (slot.isRecording().get()) {
                                targetSlot = i;
                                break;
                            }
                        }
                        if (targetSlot >= 0) {
                            clipStopped = true;
                            bank.stop();
                            bank.launch(targetSlot);
                        }
                    }
                }

                if (!clipStopped) {
                    if (recordLevelGlobal.get()) {
                        mTransport.isArrangerRecordEnabled().toggle();
                    } else {
                        mTransport.isClipLauncherOverdubEnabled().toggle();
                    }
                }
            } else if (!launchAltConfig.get()) {
                if (mCursorTrack.hasNext().get()) {
                    mCursorTrack.selectNext();
                } else {
                    mCursorTrack.selectFirst();
                }
            } else {
                launchAlt.set(true);
            }
            host.requestFlush();
        };

        HardwareActionBindable recordState = host.createAction(selectAction, () -> "Press Record Button");
        mLSurface.record().button().pressedAction().setBinding(recordState);
        mLSurface.record().button().releasedAction().setBinding(host.createAction(
                () -> {
                    if (launchAltConfig.get()) {
                        launchAlt.set(false);
                    }
                    host.requestFlush();
                }, () -> "Release Record Action"
        ));

        MultiStateHardwareLight recordLight = mLSurface.record().light();
        BooleanValue arrangerRecord = mTransport.isArrangerRecordEnabled();
        BooleanValue clipLauncherOverdub = mTransport.isClipLauncherOverdubEnabled();
        mRecordLevel.addValueObserver(
                target -> {
                    if (recordActionToggle.get()) {
                        if (target.equals(GLOBAL)) {
                            recordLight.state().setValue(
                                    arrangerRecord.get() ? PadLightState.solidLight(5) : PadLightState.solidLight(7)
                            );
                        } else if (target.equals(CLIP_LAUNCHER)) {
                            recordLight.state().setValue(
                                    clipLauncherOverdub.get() ? PadLightState.solidLight(5) : PadLightState.solidLight(7)
                            );
                        }
                    }
                }
        );
        arrangerRecord.addValueObserver(are -> {
            if (recordActionToggle.get() && mRecordLevel.get().equals(GLOBAL)) {
                recordLight.state().setValue(are ? PadLightState.solidLight(5) : PadLightState.solidLight(7));
            }
        });
        clipLauncherOverdub.addValueObserver(ode -> {
            if (recordActionToggle.get() && mRecordLevel.get().equals(CLIP_LAUNCHER)) {
                recordLight.state().setValue(ode ? PadLightState.solidLight(5) : PadLightState.solidLight(7));
            }
        });
        mRecordAction.addValueObserver(val -> {
            if (val.equals(CYCLE_TRACKS)) {
                recordLight.state().setValue(PadLightState.solidLight(13));
            } else if (val.equals(LAUNCH_ALT)) {
                recordLight.state().setValue(PadLightState.solidLight(3));
            } else {
                if ((arrangerRecord.get() && mRecordLevel.get().equals(GLOBAL))
                        || (clipLauncherOverdub.get() && mRecordLevel.get().equals(CLIP_LAUNCHER))) {
                    recordLight.state().setValue(PadLightState.solidLight(5));
                } else {
                    recordLight.state().setValue(PadLightState.solidLight(7));
                }
            }
        });

        mLSurface.novation().light().state().setValue(PadLightState.solidLight(3));

        //AtomicReference<Mode> mixerMode = new AtomicReference<>(Mode.MIXER_VOLUME);
        AtomicReference<Mode> lastSessionMode = new AtomicReference<>(Mode.SESSION);

        HardwareActionBindable mSessionAction = host.createAction(() -> {
            switch (mMachine.mode()) {
                case SESSION:
                    lastSessionMode.set(mixerMode.get());
                    mMachine.setMode(mLSurface, mixerMode.get());
                    break;
                case MIXER_VOLUME:
                case MIXER_PAN:
                case MIXER_SEND:
                case MIXER_CONTROLS:
                case MIXER_STOP:
                case MIXER_MUTE:
                case MIXER_SOLO:
                case MIXER_ARM:
                    lastSessionMode.set(Mode.SESSION);
                    mMachine.setMode(mLSurface, Mode.SESSION);
                    break;
                case DRUM:
                case UNKNOWN:
                    mMachine.setMode(mLSurface, lastSessionMode.get());
                    break;
                default:
                    throw new RuntimeException("Unknown mode " + mMachine.mode());
            }
        }, () -> "Press Session View");

        HardwareActionBindable mNoteAction = host.createAction(() -> {
            Mode om = mMachine.mode();
            if (om != Mode.DRUM && om != Mode.UNKNOWN) {
                lastSessionMode.set(om);
            }
            mSession.sendSysex("00 01");
            mMachine.setMode(mLSurface, Mode.DRUM);
        }, () -> "Press Note View");

        HardwareActionBindable mCustomAction = host.createAction(() -> {
            Mode om = mMachine.mode();
            if (om != Mode.DRUM && om != Mode.UNKNOWN) {
                lastSessionMode.set(om);
            }
            mMachine.setMode(mLSurface, Mode.UNKNOWN);
        }, () -> "Press Custom View");

        if (mSwapOnBoot.get()) {
            mSessionAction.invoke();
        } else {
            mMachine.setMode(mLSurface, Mode.DRUM);
        }

        mSessionAction.addBinding(mLSurface.session().button().pressedAction());
        mNoteAction.addBinding(mLSurface.note().button().pressedAction());
        mCustomAction.addBinding(mLSurface.custom().button().pressedAction());

        mSession.setMidiCallback(ChannelType.DAW, this::onMidi0);
        mSession.setSysexCallback(ChannelType.DAW, this::onSysex0);
        mSession.setMidiCallback(ChannelType.CUSTOM, this::onMidi1);

        System.out.println("Launchpad X Initialized");
        host.requestFlush();
    }

    @Override
    public void exit() {
        mSession.shutdown();
        System.out.println("Launchpad X Exited");
    }

    @Override
    public void flush() {
        mSurface.updateHardware();
    }

    private void onMidi0(ShortMidiMessage msg) {
        // no-op
    }

    private void onSysex0(final String data) {
        byte[] sysex = Utils.parseSysex(data);
        mMachine.sendSysex(sysex);
        mSurface.invalidateHardwareOutputState();
    }

    private void onMidi1(ShortMidiMessage msg) {
        // no-op
    }

    /** OSC server so Launchpad session view can follow BitX JUMPTO. */
    /** OSC server so Launchpad session view can follow BitX JUMPTO. */
    private void setupBitxOscFollow(ControllerHost host, TrackBank sessionTrackBank) {
        try {
            OscModule oscModule = host.getOscModule();

            OscAddressSpace addrSpace = oscModule.createAddressSpace();
            addrSpace.setName("Launchpad BitX Follow");
            addrSpace.setShouldLogMessages(false);

            addrSpace.registerMethod(
                    "/bitx/jumpScene",
                    "*",
                    "Follow BitX JUMPTO scene",
                    (connection, msg) -> {
                        host.println("Launchpad OSC: received " +
                                msg.getAddressPattern() + " args=" + msg.getArguments());

                        if (msg.getArguments().isEmpty()) {
                            host.println("Launchpad OSC: /bitx/jumpScene missing arguments.");
                            return;
                        }

                        // Helper to extract integer arguments (int / float / double)
                        java.util.function.IntFunction<Integer> getIntArg = idx -> {
                            Integer i = msg.getInt(idx);
                            if (i != null) return i;
                            Double d = msg.getDouble(idx);
                            if (d != null) return d.intValue();
                            Float f = msg.getFloat(idx);
                            if (f != null) return f.intValue();
                            return null;
                        };

                        Integer trackIndex = msg.getArguments().size() >= 2 ? getIntArg.apply(0) : 0;
                        Integer sceneIndex = msg.getArguments().size() >= 2 ? getIntArg.apply(1) : getIntArg.apply(0);

                        if (sceneIndex == null) {
                            host.println("Launchpad OSC: sceneIndex not numeric.");
                            return;
                        }
                        if (trackIndex == null) {
                            host.println("Launchpad OSC: trackIndex not numeric, defaulting to 0.");
                            trackIndex = 0;
                        }

                        SceneBank sceneBank = sessionTrackBank.sceneBank();

                        // --- Scroll TRACKS so the target track is visible in the 8-wide TrackBank ---
                        int trackWindowSize = sessionTrackBank.getSizeOfBank(); // usually 8
                        if (trackWindowSize <= 0) trackWindowSize = 8;

                        host.println("Launchpad OSC: ensuring track " + trackIndex +
                                " visible (TrackBank size=" + trackWindowSize + ")");
                        sessionTrackBank.scrollIntoView(trackIndex);

                        // --- Scroll SCENES so the target scene is visible in the 8-high SceneBank ---
                        int sceneWindowSize = sceneBank.getSizeOfBank(); // usually 8
                        if (sceneWindowSize <= 0) sceneWindowSize = 8;

                        int page     = sceneIndex / sceneWindowSize;
                        int slotInWin = sceneIndex % sceneWindowSize;

                        sceneBank.scrollByPages(page);
                        sceneBank.scrollBy(slotInWin);
                        sceneBank.scrollIntoView(sceneIndex);

                        host.println("Launchpad OSC: JUMPTO -> track " + trackIndex + " scene " + sceneIndex);

                        // 🔸 Wait ~3 seconds so Bitwig + Launchpad are fully scrolled, then flash just that pad
                        if (mSessionMode != null) {
                            host.println("   ⏳ Scheduling pad visual flash in 3s...");
                            int finalTrackIndex = trackIndex;
                            int finalSceneIndex = sceneIndex;
                            host.scheduleTask(() -> {
                                host.println("   ⚡ Executing pad visual flash for track " +
                                        finalTrackIndex + " scene " + finalSceneIndex);
                                mSessionMode.flashPadFromGlobalVisual(finalTrackIndex, finalSceneIndex);
                            }, 300);
                        }
                    }
            );

            int port = (int) oscReceivePortSetting.getRaw();
            if (port < 1024 || port > 65535) {
                port = 9001;
            }

            oscModule.createUdpServer(port, addrSpace);
            host.println("Launchpad OSC: listening for BitX on UDP port " +
                    port + " (expecting from " + oscReceiveIpSetting.get() + ")");

        } catch (Exception ex) {
            host.println("Launchpad OSC: failed to set up: " + ex.getMessage());
        }
    }

}
