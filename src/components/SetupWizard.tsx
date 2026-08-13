import { useState, useEffect, useCallback } from "react";
import { motion } from "motion/react";
import { X, Eye, Layers, Settings2, Camera, Mic, ShieldCheck, Check, ChevronRight, ListChecks, BatteryCharging, Bell } from "lucide-react";
import { NativeApi, isNative } from "../native";

interface SetupWizardProps {
  isOpen: boolean;
  onClose: () => void;
  onDone: () => void;
}

interface WizardStep {
  key: string;
  title: string;
  desc: string;
  why: string;
  icon: typeof Eye;
  color: string;
  optional: boolean;
  howToGrant: string;
}

const WIZARD_STEPS: WizardStep[] = [
  {
    key: "accessibility",
    title: "Accessibility Service",
    desc: "Core automation engine — read screen elements, click, type, scroll and run workflows.",
    why: "This is the heart of Zoya's automation. Without it, Zoya cannot see or control other apps.",
    icon: Eye,
    color: "text-pink-400 bg-pink-500/10 border-pink-500/30",
    optional: false,
    howToGrant: "Zoya will open Accessibility Settings. Find 'Zoya AI Assistant' and enable it.",
  },
  {
    key: "overlay",
    title: "Display Over Other Apps",
    desc: "Shows the gesture recorder indicator and floating controls above other apps.",
    why: "Needed for the gesture recorder and the automation overlay. Can be skipped if you don't record gestures.",
    icon: Layers,
    color: "text-blue-400 bg-blue-500/10 border-blue-500/30",
    optional: true,
    howToGrant: "Zoya will open the overlay permission page. Toggle 'Allow display over other apps'.",
  },
  {
    key: "notifications",
    title: "Notifications",
    desc: "Lets Zoya show its status, screen-capture and automation-running notifications.",
    why: "Android requires this to post notifications. Zoya always shows a visible notification while automating.",
    icon: Bell,
    color: "text-cyan-400 bg-cyan-500/10 border-cyan-500/30",
    optional: true,
    howToGrant: "Zoya will open App Info → Notifications. Enable 'Allow notifications'.",
  },
  {
    key: "write_settings",
    title: "Modify System Settings",
    desc: "Adjust brightness and volume as part of automation workflows.",
    why: "Only required if you want brightness/volume automation. Optional.",
    icon: Settings2,
    color: "text-amber-400 bg-amber-500/10 border-amber-500/30",
    optional: true,
    howToGrant: "Zoya will open the 'Modify system settings' page. Allow it.",
  },
  {
    key: "camera",
    title: "Camera",
    desc: "Take photos with the native CameraX automation command.",
    why: "Only needed for the takePhoto automation action. Optional.",
    icon: Camera,
    color: "text-emerald-400 bg-emerald-500/10 border-emerald-500/30",
    optional: true,
    howToGrant: "Zoya will show the Android permission dialog. Tap 'Allow'.",
  },
  {
    key: "microphone",
    title: "Microphone",
    desc: "Record audio to app storage for automation scenarios.",
    why: "Only needed for the startRecording automation action. Optional.",
    icon: Mic,
    color: "text-purple-400 bg-purple-500/10 border-purple-500/30",
    optional: true,
    howToGrant: "Zoya will show the Android permission dialog. Tap 'Allow'.",
  },
  {
    key: "battery",
    title: "Battery Optimization",
    desc: "Prevents Android from killing Zoya while a scheduled task runs.",
    why: "Recommended for reliable scheduled automation. Android may otherwise suspend background execution.",
    icon: BatteryCharging,
    color: "text-green-400 bg-green-500/10 border-green-500/30",
    optional: true,
    howToGrant: "Zoya will open battery settings. Choose 'Don't optimize' / 'Allow'.",
  },
];

export function SetupWizard({ isOpen, onClose, onDone }: SetupWizardProps) {
  const native = isNative();
  const [stepIdx, setStepIdx] = useState(0);
  const [states, setStates] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [done, setDone] = useState<string[]>([]);

  const refresh = useCallback(async () => {
    if (!native) return;
    const s = await NativeApi.permissionStates();
    setStates(s as unknown as Record<string, string>);
    const ps = await NativeApi.getPermissionStatus();
    if (ps.ok) {
      const d = ps.data as any;
      setStates((prev) => ({
        ...prev,
        notifications: d.notifications ? "granted" : "denied",
        battery: d.ignoreBatteryOptimizations ? "granted" : "denied",
      }));
    }
  }, [native]);

  useEffect(() => {
    if (isOpen) refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, native]);

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stepIdx]);

  if (!isOpen) return null;

  const current = WIZARD_STEPS[stepIdx];
  const Icon = current.icon;
  const granted = (states[current.key] ?? "unknown") === "granted";

  const handleGrant = async () => {
    if (!native) return;
    setBusy(current.key);
    if (current.key === "camera" || current.key === "microphone") {
      await NativeApi.requestPermission(current.key as "camera" | "microphone");
    } else if (current.key === "accessibility") {
      await NativeApi.openAccessibilitySettings();
    } else if (current.key === "overlay") {
      await NativeApi.openSettingsPage("overlay");
    } else if (current.key === "write_settings") {
      await NativeApi.openSettingsPage("write_settings");
    } else if (current.key === "notifications") {
      await NativeApi.openSettingsPage("app_info");
    } else if (current.key === "battery") {
      await NativeApi.openSettingsPage("app_info");
    }
    setBusy(null);
    refresh();
  };

  const skipOrNext = () => {
    if (granted) setDone((d) => (d.includes(current.key) ? d : [...d, current.key]));
    if (stepIdx < WIZARD_STEPS.length - 1) {
      setStepIdx(stepIdx + 1);
    } else {
      onDone();
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="fixed inset-0 z-[60] bg-black/90 backdrop-blur-md flex items-center justify-center p-4 overflow-y-auto"
    >
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        className="bg-zinc-900 border border-white/10 rounded-2xl p-6 w-full max-w-lg space-y-5 relative shadow-2xl max-h-[92vh] overflow-y-auto"
      >
        <button onClick={onClose} className="absolute top-4 right-4 text-zinc-400 hover:text-white p-1.5 rounded-lg bg-white/5 hover:bg-white/10 transition-colors">
          <X className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-3 border-b border-white/10 pb-4">
          <div className="p-3 rounded-2xl bg-gradient-to-br from-pink-500/20 to-purple-500/20 border border-pink-500/30 text-pink-400">
            <ShieldCheck className="w-6 h-6" />
          </div>
          <div>
            <h3 className="font-bold text-xl text-white">Welcome to Zoya Setup</h3>
            <p className="text-xs text-zinc-400">Step {stepIdx + 1} of {WIZARD_STEPS.length} — {native ? "native setup" : "web preview"}</p>
          </div>
        </div>

        {/* Progress */}
        <div className="flex gap-1.5">
          {WIZARD_STEPS.map((s, i) => (
            <div key={s.key} className={`h-1 flex-1 rounded-full ${i < stepIdx ? "bg-emerald-500/60" : i === stepIdx ? "bg-pink-500/80" : "bg-white/10"}`} />
          ))}
        </div>

        <div className="p-4 rounded-xl bg-black/40 border border-white/10 space-y-3">
          <div className="flex items-center gap-3">
            <div className={`p-3 rounded-xl border ${current.color}`}>
              <Icon className="w-5 h-5" />
            </div>
            <div>
              <p className="text-sm font-bold text-white flex items-center gap-2">
                {current.title}
                {current.optional && <span className="text-[9px] uppercase tracking-wider text-zinc-500 border border-white/10 rounded px-1.5 py-0.5">optional</span>}
                {granted && <Check className="w-4 h-4 text-emerald-400" />}
              </p>
              <p className="text-[11px] text-zinc-400">{current.desc}</p>
            </div>
          </div>
          <p className="text-[11px] text-zinc-300 leading-relaxed"><b className="text-zinc-200">Why it matters:</b> {current.why}</p>
          <p className="text-[11px] text-zinc-400 leading-relaxed"><b className="text-zinc-200">How to grant:</b> {current.howToGrant}</p>
        </div>

        <div className="flex gap-2">
          {current.optional && !granted && (
            <button
              onClick={skipOrNext}
              disabled={busy !== null}
              className="flex-1 py-3 rounded-xl bg-white/5 text-zinc-300 border border-white/10 hover:bg-white/10 font-semibold text-xs transition-colors disabled:opacity-40"
            >
              Skip for now
            </button>
          )}
          {granted ? (
            <button
              onClick={skipOrNext}
              className="flex-1 py-3 rounded-xl bg-emerald-500 hover:bg-emerald-600 text-white font-bold text-xs transition-colors flex items-center justify-center gap-2"
            >
              <ChevronRight className="w-4 h-4" /> Continue
            </button>
          ) : (
            <button
              onClick={handleGrant}
              disabled={busy !== null}
              className="flex-1 py-3 rounded-xl bg-pink-500 hover:bg-pink-600 text-white font-bold text-xs transition-colors flex items-center justify-center gap-2 disabled:opacity-50"
            >
              {busy === current.key ? "Opening..." : "Grant permission"}
            </button>
          )}
        </div>

        {!native && (
          <p className="text-[10px] text-zinc-500 text-center">
            Web preview — the wizard's permission steps run on an installed device.
          </p>
        )}
      </motion.div>
    </motion.div>
  );
}
