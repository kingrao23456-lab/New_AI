import { useState, useEffect, useCallback } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  X,
  Smartphone,
  ShieldCheck,
  Eye,
  Mic,
  Camera,
  Layers,
  Settings2,
  RefreshCw,
  ListChecks,
  Hand,
  CircleStop,
  Terminal,
  Check,
  AlertTriangle,
  Clock,
  LayoutDashboard,
  ScrollText,
  Cloud,
  Fingerprint,
  Activity,
  Square,
  Cpu,
  Zap,
  Trash2,
  Database,
  MonitorUp,
  Ban,
  ArrowLeft,
  ArrowRight,
  Play,
  Download,
} from "lucide-react";
import { NativeApi, isNative, onLogEvent, biometricAuth } from "../native";
import type { BridgeResult, PermissionStates, RecordedGesture } from "../native";

type Tab = "dashboard" | "permissions" | "test" | "logs" | "sync";

interface NativeAutomationModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const permissionMeta: Array<{
  key: keyof PermissionStates;
  label: string;
  desc: string;
  why: string;
  icon: typeof Eye;
  color: string;
  optional: boolean;
}> = [
  { key: "accessibility", label: "Accessibility Service", desc: "Read screen elements, click, scroll, automate apps", why: "Required for the core automation engine — detecting UI elements, typing, tapping, gestures and screen reading.", icon: Eye, color: "text-pink-400 bg-pink-500/10 border-pink-500/30", optional: false },
  { key: "overlay", label: "Display Over Other Apps", desc: "Floating widget + gesture recorder overlay", why: "Lets Zoya show the gesture recorder indicator and overlay controls on top of other apps while automating.", icon: Layers, color: "text-blue-400 bg-blue-500/10 border-blue-500/30", optional: true },
  { key: "write_settings", label: "Modify System Settings", desc: "Brightness & volume automation", why: "Allows Zoya to adjust brightness and volume as part of automation workflows.", icon: Settings2, color: "text-amber-400 bg-amber-500/10 border-amber-500/30", optional: true },
  { key: "camera", label: "Camera", desc: "Take photos via native CameraX", why: "Required only for the photo-capture automation command (takePhoto).", icon: Camera, color: "text-emerald-400 bg-emerald-500/10 border-emerald-500/30", optional: true },
  { key: "microphone", label: "Microphone", desc: "Record audio to app storage", why: "Required only for the audio-recording automation command (startRecording).", icon: Mic, color: "text-cyan-400 bg-cyan-500/10 border-cyan-500/30", optional: true },
];

function statusBadge(status: string) {
  if (status === "granted") return <span className="px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 text-[10px] font-bold uppercase tracking-wider">Granted</span>;
  return <span className="px-2 py-0.5 rounded-full bg-zinc-700/50 text-zinc-300 text-[10px] font-bold uppercase tracking-wider">Off</span>;
}

export function NativeAutomationModal({ isOpen, onClose }: NativeAutomationModalProps) {
  const native = isNative();
  const [tab, setTab] = useState<Tab>("dashboard");
  const [states, setStates] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [testOutput, setTestOutput] = useState<{ ok: boolean; text: string } | null>(null);
  const [gestures, setGestures] = useState<RecordedGesture[]>([]);
  const [recording, setRecording] = useState(false);
  const [permissionStatus, setPermissionStatus] = useState<string | null>(null);
  const [status, setStatus] = useState<any>(null);
  const [caps, setCaps] = useState<any>(null);
  const [logs, setLogs] = useState<any[]>([]);
  const [syncStatus, setSyncStatus] = useState<any>(null);
  const [syncEndpoint, setSyncEndpoint] = useState("");
  const [bioAvailable, setBioAvailable] = useState(false);

  const refresh = useCallback(async () => {
    if (!native) return;
    const s = await NativeApi.permissionStates();
    setStates(s as unknown as Record<string, string>);
    const st = await NativeApi.getAutomationStatus();
    if (st.ok) setStatus(st.data);
    const c = await NativeApi.getDeviceCapabilities();
    if (c.ok) setCaps(c.data);
    const sy = await NativeApi.getSyncStatus();
    if (sy.ok) setSyncStatus(sy.data);
    const b = await NativeApi.biometricStatus();
    if (b.ok) setBioAvailable((b.data as any)?.available ?? false);
  }, [native]);

  useEffect(() => {
    if (isOpen) {
      refresh();
      loadGestures();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, native]);

  useEffect(() => {
    if (!native || tab !== "logs") return;
    NativeApi.getExecutionLogs(100).then((r) => {
      if (r.ok) setLogs((r.data as any)?.logs ?? []);
    });
    const unsubscribe = onLogEvent((entry) => {
      setLogs((prev) => [entry, ...prev].slice(0, 200));
    });
    return unsubscribe;
  }, [native, tab, isOpen]);

  const handleRequest = async (key: string) => {
    if (!native) return;
    setBusy(key);
    setPermissionStatus(null);
    const perm = key === "overlay" ? "overlay" : key === "write_settings" ? "write_settings" : (key as "camera" | "microphone");
    const granted = await NativeApi.requestPermission(perm);
    setPermissionStatus(
      granted
        ? `${perm} permission granted`
        : `${perm} requires manual enabling in Android Settings (or is not a runtime permission).`,
    );
    await refresh();
    setBusy(null);
  };

  const openSystemSettings = async (key: string) => {
    if (!native) return;
    setBusy(key);
    if (key === "accessibility") {
      await NativeApi.openAccessibilitySettings();
    } else if (key === "overlay") {
      await NativeApi.openSettingsPage("overlay");
    } else if (key === "write_settings") {
      await NativeApi.openSettingsPage("write_settings");
    } else {
      await NativeApi.openSettingsPage("app_info");
    }
    setBusy(null);
  };

  const runTest = async (kind: string) => {
    if (!native) {
      setTestOutput({ ok: false, text: "Native bridge not available in web mode." });
      return;
    }
    setTestOutput(null);
    setBusy(kind);
    let r: BridgeResult | null = null;
    if (kind === "readScreen") {
      r = await NativeApi.readScreenText();
    } else if (kind === "currentApp") {
      r = await NativeApi.currentApp();
    } else if (kind === "listApps") {
      r = await NativeApi.listApps();
    } else if (kind === "captureOcr") {
      r = await NativeApi.captureScreenForOcr();
      if (r.ok) {
        const d = r.data as any;
        setTestOutput({ ok: true, text: `OCR text: ${d?.text || "(no text found)"} (${d?.lineCount ?? 0} lines)` });
        setBusy(null);
        return;
      }
    } else if (kind === "screenContext") {
      r = await NativeApi.getScreenContext();
    } else if (kind === "visualDetect") {
      r = await NativeApi.visualDetect();
    } else if (kind === "pressBack") {
      r = await NativeApi.pressBack();
    } else if (kind === "pressHome") {
      r = await NativeApi.pressHome();
    } else if (kind === "accessibilityTree") {
      r = await NativeApi.getScreenContext();
    } else if (kind === "performOcr") {
      r = await NativeApi.performOCR();
    }
    setBusy(null);
    if (!r) return;
    setTestOutput({
      ok: r.ok,
      text: r.ok ? JSON.stringify(r.data ?? {}, null, 2) : `[${r.status}] ${r.error?.message ?? "unknown error"}`,
    });
  };

  const startRecording = async () => {
    if (!native) return;
    setRecording(true);
    setTestOutput(null);
    const r = await NativeApi.recordGesture(15000);
    if (r.status === "PERMISSION_DENIED" || r.status === "BLOCKED") {
      setRecording(false);
      setTestOutput({ ok: false, text: `[${r.status}] ${r.error?.message ?? "Overlay permission required to show the recording indicator."}` });
    }
  };

  const stopRecording = async () => {
    if (!native) return;
    const r = await NativeApi.stopGestureRecording();
    setRecording(false);
    if (r.ok) {
      setTestOutput({ ok: true, text: `Gesture recorded: ${(r.data as any)?.gesture?.name ?? "recording"}` });
      const list = await NativeApi.listGestures();
      if (list.ok) setGestures((list.data as any)?.gestures ?? []);
    } else {
      setTestOutput({ ok: false, text: `[${r.status}] ${r.error?.message ?? "Recording failed"}` });
    }
  };

  const loadGestures = async () => {
    if (!native) return;
    const r = await NativeApi.listGestures();
    if (r.ok) setGestures((r.data as any)?.gestures ?? []);
  };

  const emergencyStop = async () => {
    setBusy("stop");
    await NativeApi.stopAutomation();
    setBusy(null);
    refresh();
  };

  const clearLogs = async () => {
    const r = await NativeApi.clearExecutionLogs();
    if (r.ok) setLogs([]);
  };

  const [exportMessage, setExportMessage] = useState<string | null>(null);
  const [exportOk, setExportOk] = useState(true);

  const exportLogs = async () => {
    setExportMessage(null);
    const r = await NativeApi.exportLogs();
    if (r.ok) {
      const path = (r.data as any)?.path;
      setExportOk(true);
      setExportMessage(`Log file created. Share it via the Android share sheet that just opened. Path: ${path ?? "see device files"}`);
    } else {
      setExportOk(false);
      setExportMessage(`Export failed: ${r.error?.message ?? "unknown error"}`);
    }
  };

  const toggleSync = async (enabled: boolean) => {
    setBusy("sync");
    const r = await NativeApi.setSyncEnabled(enabled);
    if (r.ok) setSyncStatus(r.data);
    else setPermissionStatus(`Sync: [${r.status}] ${r.error?.message}`);
    setBusy(null);
  };

  const saveEndpoint = async () => {
    setBusy("syncEndpoint");
    const r = await NativeApi.setSyncEndpoint(syncEndpoint.trim());
    if (r.ok) setSyncStatus(r.data);
    else setPermissionStatus(`Endpoint: [${r.status}] ${r.error?.message}`);
    setBusy(null);
  };

  const runSync = async () => {
    setBusy("syncNow");
    const r = await NativeApi.syncNow();
    setPermissionStatus(r.ok ? "Sync completed." : `Sync failed: [${r.status}] ${r.error?.message}`);
    refresh();
    setBusy(null);
  };

  const doBiometricAuth = async () => {
    const res = await biometricAuth();
    setPermissionStatus(res.authenticated ? "Biometric authentication passed." : `Biometric authentication ${res.reason ?? "failed"}.`);
  };

  if (!isOpen) return null;

  const statusCard = (label: string, ok: boolean) => (
    <div className="p-3 rounded-xl bg-black/40 border border-white/5 flex items-center gap-2.5">
      <span className={`w-2 h-2 rounded-full shrink-0 ${ok ? "bg-emerald-400" : "bg-red-500"}`} />
      <span className="text-[11px] text-zinc-300">{label}</span>
    </div>
  );

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4 overflow-y-auto"
    >
      <motion.div
        initial={{ scale: 0.95, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        exit={{ scale: 0.95, opacity: 0 }}
        className="bg-zinc-900 border border-white/10 rounded-2xl p-6 w-full max-w-3xl space-y-6 relative shadow-2xl max-h-[92vh] overflow-y-auto"
      >
        <button
          onClick={onClose}
          className="absolute top-4 right-4 text-zinc-400 hover:text-white p-1.5 rounded-lg bg-white/5 hover:bg-white/10 transition-colors"
        >
          <X className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-3 border-b border-white/10 pb-4">
          <div className="p-3 rounded-2xl bg-gradient-to-br from-pink-500/20 to-purple-500/20 border border-pink-500/30 text-pink-400">
            <Smartphone className="w-6 h-6" />
          </div>
          <div className="flex-1">
            <h3 className="font-bold text-xl text-white">Zoya Android Automation</h3>
            <p className="text-xs text-zinc-400">Engine dashboard: permissions, test mode, logs & sync</p>
          </div>
          {native && <RefreshCw onClick={refresh} className="w-4 h-4 text-emerald-400 cursor-pointer hover:scale-110 transition-transform" />}
        </div>

        {/* Native mode banner */}
        <div
          className={`p-3 rounded-xl flex items-center gap-3 border ${
            native ? "bg-emerald-500/10 border-emerald-500/30" : "bg-amber-500/10 border-amber-500/30"
          }`}
        >
          <ShieldCheck className={`w-5 h-5 ${native ? "text-emerald-400" : "text-amber-400"}`} />
          <p className="text-xs text-zinc-300">
            {native ? "Running inside the native Android app — structured bridge active." : "Web / PWA mode — install the native APK to enable the automation engine."}
          </p>
        </div>

        {/* Tabs */}
        <div className="flex flex-wrap gap-1.5 border-b border-white/10 pb-3">
          {([
            ["dashboard", "Dashboard", LayoutDashboard],
            ["permissions", "Permissions", ListChecks],
            ["test", "Test Mode", Terminal],
            ["logs", "Logs", ScrollText],
            ["sync", "Sync & Security", Cloud],
          ] as Array<[Tab, string, typeof Eye]>).map(([t, label, Icon]) => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className={`px-3 py-1.5 rounded-lg text-[11px] font-semibold flex items-center gap-1.5 transition-colors ${
                tab === t ? "bg-pink-500/20 text-pink-300 border border-pink-500/30" : "bg-white/5 text-zinc-400 border border-white/5 hover:bg-white/10"
              }`}
            >
              <Icon className="w-3.5 h-3.5" /> {label}
            </button>
          ))}
        </div>

        {/* Emergency STOP */}
        <button
          onClick={emergencyStop}
          disabled={busy === "stop"}
          className="w-full py-3.5 rounded-xl bg-red-600 hover:bg-red-500 text-white font-bold text-sm flex items-center justify-center gap-2.5 shadow-lg shadow-red-600/30 transition-colors disabled:opacity-50"
        >
          <Square className="w-5 h-5 fill-current" />
          EMERGENCY STOP
        </button>

        {/* TAB: Dashboard */}
        {tab === "dashboard" && (
          <div className="space-y-5">
            <div className="grid grid-cols-2 md:grid-cols-3 gap-2.5">
              {statusCard("Accessibility service", !!status?.accessibilityEnabled)}
              {statusCard("Screen capture", !!status?.screenCaptureActive)}
              {statusCard("Camera permission", !!status?.cameraGranted)}
              {statusCard("Microphone permission", !!status?.microphoneGranted)}
              {statusCard("Overlay permission", !!status?.overlayGranted)}
              {statusCard("Foreground automation", !!status?.foregroundAutomationActive)}
            </div>

            <div className="p-3 rounded-xl bg-black/40 border border-white/5">
              <p className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5 flex items-center gap-1.5">
                <Activity className="w-3 h-3 text-pink-400" /> Active workflow
              </p>
              <p className="text-xs text-zinc-300">
                {status?.activeWorkflow ? `Workflow ${status.activeWorkflow.id ?? ""} running (${status.activeWorkflow.stepCount ?? 0} steps)` : "No workflow running"}
              </p>
            </div>

            <div className="grid md:grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <p className="text-[10px] uppercase tracking-wider text-zinc-500 flex items-center gap-1.5">
                  <Clock className="w-3 h-3 text-emerald-400" /> Scheduled tasks ({status?.scheduledTaskCount ?? 0})
                </p>
                {(status?.scheduledTasks ?? []).slice(0, 6).map((t: any) => (
                  <div key={t.id} className="px-3 py-2 rounded-lg bg-black/40 border border-white/5 flex items-center justify-between">
                    <span className="text-[11px] text-zinc-300">{t.name}</span>
                    <span className="text-[10px] font-mono text-zinc-500">{t.scheduleType} {t.enabled ? "" : "(disabled)"}</span>
                  </div>
                ))}
                {!status?.scheduledTasks?.length && <p className="text-[11px] text-zinc-500">No scheduled tasks.</p>}
              </div>
              <div className="space-y-1.5">
                <p className="text-[10px] uppercase tracking-wider text-zinc-500 flex items-center gap-1.5">
                  <CircleStop className="w-3 h-3 text-cyan-400" /> Recent executions
                </p>
                {(status?.recentExecutions ?? []).slice(0, 6).map((r: any) => (
                  <div key={r.id} className="px-3 py-2 rounded-lg bg-black/40 border border-white/5 flex items-center justify-between">
                    <span className="text-[11px] text-zinc-300 truncate">{r.taskName}</span>
                    <span className={`text-[10px] font-mono ${r.success ? "text-emerald-400" : "text-red-400"}`}>{r.success ? "OK" : r.status}</span>
                  </div>
                ))}
                {!status?.recentExecutions?.length && <p className="text-[11px] text-zinc-500">No executions yet.</p>}
              </div>
            </div>

            {caps && (
              <div className="p-3 rounded-xl bg-black/40 border border-white/5">
                <p className="text-[10px] uppercase tracking-wider text-zinc-500 mb-2 flex items-center gap-1.5">
                  <Cpu className="w-3 h-3 text-purple-400" /> Device capabilities
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {[
                    ["Android", `${caps.androidVersion} (SDK ${caps.sdkInt})`],
                    ["Model", `${caps.manufacturer} ${caps.model}`],
                    ["Camera", caps.hasCamera ? "yes" : "no"],
                    ["Microphone", caps.hasMicrophone ? "yes" : "no"],
                    ["Biometric", caps.hasBiometric ? "yes" : "no"],
                    ["OCR (offline)", caps.supportsOcr ? "yes" : "no"],
                    ["Media projection", caps.supportsMediaProjection ? "yes" : "no"],
                    ["A11y gestures", caps.supportsAccessibilityGestures ? "yes" : "no"],
                    ["Shizuku detected", caps.shizukuAvailable ? "yes" : "no"],
                    ["Device owner", caps.deviceOwnerActive ? "yes" : "no"],
                  ].map(([k, v]) => (
                    <span key={k as string} className="px-2 py-1 rounded-md bg-white/5 border border-white/10 text-[10px] text-zinc-300">
                      {k}: <b className="text-zinc-100">{v}</b>
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* TAB: Permissions */}
        {tab === "permissions" && (
          <div className="space-y-4">
            <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
              <ListChecks className="w-4 h-4 text-pink-400" />
              <span>Centralized Permission Manager</span>
            </h4>
            <div className="space-y-2.5">
              {permissionMeta.map((meta) => {
                const Icon = meta.icon;
                const status = states[meta.key] ?? "unknown";
                return (
                  <div key={meta.key} className="p-3 rounded-xl bg-black/40 border border-white/5">
                    <div className="flex items-start justify-between gap-3">
                      <div className="space-y-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <Icon className={`w-4 h-4 ${meta.color.split(" ")[0]}`} />
                          <p className="text-sm font-semibold text-white">{meta.label}</p>
                          {statusBadge(status)}
                          {meta.optional && <span className="text-[9px] uppercase tracking-wider text-zinc-500">optional</span>}
                        </div>
                        <p className="text-[11px] text-zinc-400">{meta.desc}</p>
                        <p className="text-[10px] text-zinc-500 leading-relaxed"><b>Why:</b> {meta.why}</p>
                      </div>
                      <div className="flex gap-1.5 shrink-0">
                        <button
                          onClick={() => handleRequest(meta.key)}
                          disabled={busy === meta.key || status === "granted"}
                          className="px-2.5 py-1.5 rounded-lg text-[11px] font-semibold bg-pink-500/20 text-pink-300 border border-pink-500/30 hover:bg-pink-500/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                        >
                          {busy === meta.key ? "..." : status === "granted" ? "Granted" : "Grant"}
                        </button>
                        <button
                          onClick={() => openSystemSettings(meta.key)}
                          disabled={busy === meta.key}
                          className="px-2.5 py-1.5 rounded-lg text-[11px] font-semibold bg-white/5 text-zinc-300 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors"
                        >
                          Settings
                        </button>
                        {status !== "granted" && (
                          <button
                            onClick={() => { refresh(); }}
                            className="px-2.5 py-1.5 rounded-lg text-[11px] font-semibold bg-white/5 text-zinc-300 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors"
                          >
                            Retry
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
            {permissionStatus && (
              <p className="text-[11px] text-zinc-400 flex items-center gap-1.5">
                <AlertTriangle className="w-3.5 h-3.5 text-amber-400" /> {permissionStatus}
              </p>
            )}
            <p className="text-[10px] text-zinc-500">
              Zoya never requests unnecessary permissions and never bypasses Android's permission dialogs. Denied or permanently-denied
              permissions surface here with the exact Settings link to re-enable them.
            </p>
          </div>
        )}

        {/* TAB: Test Mode */}
        {tab === "test" && (
          <div className="space-y-4">
            <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
              <Terminal className="w-4 h-4 text-emerald-400" />
              <span>Automation Test Mode</span>
            </h4>
            <div className="flex flex-wrap gap-2">
              <button onClick={() => runTest("readScreen")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <Eye className="w-3.5 h-3.5 text-pink-400" /> Read screen
              </button>
              <button onClick={() => runTest("currentApp")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <Smartphone className="w-3.5 h-3.5 text-blue-400" /> Current app
              </button>
              <button onClick={() => runTest("listApps")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <Layers className="w-3.5 h-3.5 text-purple-400" /> List apps
              </button>
              <button onClick={() => runTest("performOcr")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <Camera className="w-3.5 h-3.5 text-emerald-400" /> OCR
              </button>
              <button onClick={() => runTest("screenContext")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <Activity className="w-3.5 h-3.5 text-cyan-400" /> Accessibility tree
              </button>
              <button onClick={() => runTest("visualDetect")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <MonitorUp className="w-3.5 h-3.5 text-orange-400" /> Visual detect
              </button>
              <button onClick={() => runTest("pressBack")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <ArrowLeft className="w-3.5 h-3.5 text-zinc-400" /> Press back
              </button>
              <button onClick={() => runTest("pressHome")} disabled={busy !== null} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 border border-white/10 hover:bg-white/10 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                <ArrowRight className="w-3.5 h-3.5 text-zinc-400" /> Press home
              </button>
            </div>
            {testOutput && (
              <div
                className={`p-3 rounded-lg border text-[11px] font-mono whitespace-pre-wrap max-h-60 overflow-y-auto ${
                  testOutput.ok ? "bg-emerald-500/5 border-emerald-500/20 text-emerald-200" : "bg-red-500/5 border-red-500/20 text-red-200"
                }`}
              >
                {testOutput.text}
              </div>
            )}

            <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2 pt-2">
              <Hand className="w-4 h-4 text-blue-400" />
              <span>Gesture Recorder</span>
            </h4>
            <div className="flex gap-2">
              <button
                onClick={startRecording}
                disabled={recording || busy !== null}
                className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-red-500/20 text-red-300 border border-red-500/30 hover:bg-red-500/30 disabled:opacity-40 transition-colors flex items-center gap-1.5 flex-1 justify-center"
              >
                {recording ? <CircleStop className="w-3.5 h-3.5 animate-pulse" /> : <Hand className="w-3.5 h-3.5" />}
                {recording ? "Recording... tap to stop" : "Record gesture (15s)"}
              </button>
              {recording && (
                <button onClick={stopRecording} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white text-black hover:bg-zinc-200 transition-colors">
                  Stop
                </button>
              )}
            </div>
            {gestures.length > 0 && (
              <div className="space-y-1.5">
                <p className="text-[10px] uppercase tracking-wider text-zinc-500">Saved gestures ({gestures.length})</p>
                {gestures.slice(0, 5).map((g) => (
                  <div key={g.id} className="flex items-center justify-between px-3 py-2 rounded-lg bg-black/40 border border-white/5">
                    <span className="text-[11px] text-zinc-300">{g.name}</span>
                    <span className="text-[10px] font-mono text-zinc-500">{Math.round(g.durationMs)}ms</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* TAB: Logs */}
        {tab === "logs" && (
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
                <ScrollText className="w-4 h-4 text-cyan-400" />
                <span>Real-time Automation Logs ({logs.length})</span>
              </h4>
              <div className="flex items-center gap-1.5">
                <button
                  onClick={exportLogs}
                  disabled={busy !== null}
                  className="px-2.5 py-1.5 rounded-lg text-[11px] font-semibold bg-emerald-500/10 text-emerald-300 border border-emerald-500/20 hover:bg-emerald-500/20 transition-colors flex items-center gap-1.5 disabled:opacity-40"
                >
                  <Download className="w-3 h-3" /> Export TXT
                </button>
                <button
                  onClick={clearLogs}
                  className="px-2.5 py-1.5 rounded-lg text-[11px] font-semibold bg-white/5 text-zinc-300 border border-white/10 hover:bg-white/10 transition-colors flex items-center gap-1.5"
                >
                  <Trash2 className="w-3 h-3 text-red-400" /> Clear
                </button>
              </div>
            </div>
            {exportMessage && (
              <p className={`text-[11px] px-3 py-2 rounded-lg border ${exportOk ? "text-emerald-300 border-emerald-500/20 bg-emerald-500/5" : "text-red-300 border-red-500/20 bg-red-500/5"}`}>
                {exportMessage}
              </p>
            )}
            <div className="space-y-1.5 max-h-80 overflow-y-auto">
              {logs.length === 0 && <p className="text-[11px] text-zinc-500">No log entries yet. Run an automation action to see live entries here.</p>}
              {logs.map((entry, i) => {
                const level = entry.level ?? "info";
                const ok = level !== "error" && level !== "warn";
                return (
                  <div key={`${entry.ts}-${i}`} className={`px-3 py-2 rounded-lg border text-[11px] font-mono flex gap-2 items-start ${ok ? "bg-black/40 border-white/5" : "bg-red-500/5 border-red-500/20"}`}>
                    <span className={`shrink-0 font-bold ${level === "error" ? "text-red-400" : level === "warn" ? "text-amber-400" : "text-emerald-400"}`}>{level.toUpperCase()}</span>
                    <span className="text-zinc-500 shrink-0">{new Date(entry.ts).toLocaleTimeString()}</span>
                    <span className="text-zinc-300 truncate">{entry.command ? `[${entry.command}] ` : ""}{entry.detail ?? entry.phase}</span>
                  </div>
                );
              })}
            </div>
            <p className="text-[10px] text-zinc-500">Passwords, OTPs, API keys and tokens are never stored or shown in logs.</p>
          </div>
        )}

        {/* TAB: Sync & Security */}
        {tab === "sync" && (
          <div className="space-y-5">
            <div className="p-4 rounded-xl bg-black/40 border border-white/5 space-y-3">
              <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
                <Cloud className="w-4 h-4 text-sky-400" />
                <span>Optional Cloud Sync</span>
              </h4>
              <div className="flex items-center justify-between">
                <p className="text-[11px] text-zinc-300">Sync workflows, gestures & non-sensitive settings</p>
                <button
                  onClick={() => toggleSync(!(syncStatus?.enabled ?? false))}
                  disabled={busy === "sync"}
                  className={`px-3 py-1.5 rounded-lg text-[11px] font-semibold transition-colors ${
                    syncStatus?.enabled ? "bg-emerald-500/20 text-emerald-300 border border-emerald-500/30" : "bg-white/5 text-zinc-300 border border-white/10"
                  }`}
                >
                  {syncStatus?.enabled ? "Enabled" : "Disabled"} ({busy === "sync" ? "..." : "toggle"})
                </button>
              </div>
              <div className="flex gap-2">
                <input
                  value={syncEndpoint}
                  onChange={(e) => setSyncEndpoint(e.target.value)}
                  placeholder="https://your-sync-server.example/sync"
                  className="flex-1 px-3 py-2 rounded-lg bg-black/60 border border-white/10 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-sky-500/50"
                />
                <button onClick={saveEndpoint} disabled={busy === "syncEndpoint"} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-white/5 text-zinc-300 border border-white/10 hover:bg-white/10 disabled:opacity-40">
                  Set endpoint
                </button>
              </div>
              <div className="flex items-center gap-2">
                <button onClick={runSync} disabled={busy === "syncNow" || !(syncStatus?.enabled ?? false)} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-sky-500/20 text-sky-300 border border-sky-500/30 hover:bg-sky-500/30 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                  <Zap className="w-3.5 h-3.5" /> Sync now
                </button>
                <span className="text-[10px] text-zinc-500">Last sync: {syncStatus?.lastSyncAt ? new Date(syncStatus.lastSyncAt).toLocaleString() : "never"}</span>
              </div>
              <p className="text-[10px] text-zinc-500 leading-relaxed">
                HTTPS only. Payloads are AES-GCM encrypted before transmission. Passwords, OTPs, tokens and screen contents are never uploaded. Sync is off by default.
              </p>
            </div>

            <div className="p-4 rounded-xl bg-black/40 border border-white/5 space-y-3">
              <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
                <Fingerprint className="w-4 h-4 text-purple-400" />
                <span>Biometric Protection</span>
              </h4>
              <p className="text-[11px] text-zinc-300">
                {bioAvailable ? "Biometric hardware is available. Sensitive automation settings and stored credentials are protected by Android Keystore + biometric gate." : "No biometric hardware detected. Device lock / PIN protection is still enforced."}
              </p>
              <div className="flex items-center gap-2">
                <button onClick={doBiometricAuth} disabled={!bioAvailable} className="px-3 py-2 rounded-lg text-[11px] font-semibold bg-purple-500/20 text-purple-300 border border-purple-500/30 hover:bg-purple-500/30 disabled:opacity-40 transition-colors flex items-center gap-1.5">
                  <Fingerprint className="w-3.5 h-3.5" /> Test biometric auth
                </button>
              </div>
              <p className="text-[10px] text-zinc-500 leading-relaxed flex items-center gap-1.5">
                <Database className="w-3 h-3 text-emerald-400" />
                Secure storage: AES-GCM encryption backed by the Android Keystore. Credentials are never stored in plaintext.
              </p>
            </div>
          </div>
        )}

        <div className="flex justify-end pt-2 gap-2">
          <button
            onClick={onClose}
            className="px-5 py-2.5 rounded-xl bg-pink-500 hover:bg-pink-600 text-white font-medium text-xs transition-colors flex items-center gap-2 shadow-lg shadow-pink-500/20"
          >
            <Check className="w-4 h-4" />
            <span>Done</span>
          </button>
        </div>
      </motion.div>
    </motion.div>
  );
}
