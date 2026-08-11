import { useState, useEffect, useRef, ChangeEvent } from "react";
import { motion, AnimatePresence } from "motion/react";
import { 
  Mic, MicOff, Power, Loader2, AlertCircle, ExternalLink, Key, X, Check, 
  Smartphone, ShieldCheck, Layers, Sliders, Sparkles, AppWindow, 
  Play, MousePointerClick, ArrowUp, ArrowDown, Settings, Globe, MessageCircle,
  Eye, Zap, RefreshCw, ChevronRight, Shield, Camera, Upload, ImageIcon,
  Download, DownloadCloud, Package, Share2, Brain, History,
  User, Users, Volume2, Heart, Smile
} from "lucide-react";
import { useLiveSession } from "./hooks/useLiveSession";
import { MemoryModal } from "./components/MemoryModal";
import { VoicePersonaModal } from "./components/VoicePersonaModal";

const Visualizer = ({ active, color }: { active: boolean; color: string }) => {
  return (
    <div className="flex items-center justify-center gap-1 h-12">
      {[...Array(8)].map((_, i) => (
        <motion.div
          key={i}
          className={`w-1.5 rounded-full ${color}`}
          animate={
            active
              ? {
                  height: [8, 32, 12, 40, 16, 24, 8][(i + Math.floor(Date.now() / 100)) % 7],
                }
              : { height: 4 }
          }
          transition={{
            duration: 0.5,
            repeat: Infinity,
            ease: "easeInOut",
          }}
        />
      ))}
    </div>
  );
};

interface AndroidAppItem {
  id: string;
  name: string;
  category: string;
  iconColor: string;
  url: string;
  sampleCommand: string;
}

const getPersonaName = (persona: string): string => {
  if (persona === "alex") return "Alex";
  if (persona === "male") return "Zayn";
  return "Zoya";
};

const getPersonaLabel = (persona: string): string => {
  if (persona === "alex") return "😊 Alex";
  if (persona === "male") return "👨 Zayn";
  return "👩 Zoya";
};

const getPersonaDescription = (persona: string): string => {
  if (persona === "alex") return "Calm & supportive best friend (Yaar) • Hinglish & English";
  if (persona === "male") return "Confident & effortlessly smooth male companion";
  return "Witty, sassy, and slightly flirty female assistant";
};

const getPersonaVoiceName = (persona: string): string => {
  if (persona === "alex") return "Puck (Casual & Reassuring)";
  if (persona === "male") return "Fenrir (Deep & Charismatic)";
  return "Zephyr (Warm & Sassy)";
};

const getAndroidApps = (persona: string): AndroidAppItem[] => {
  const name = getPersonaName(persona);
  return [
    { id: "whatsapp", name: "WhatsApp", category: "Social & Chat", iconColor: "text-emerald-400 bg-emerald-500/10 border-emerald-500/20", url: "https://web.whatsapp.com", sampleCommand: `${name}, open WhatsApp` },
    { id: "instagram", name: "Instagram", category: "Social Feed", iconColor: "text-pink-400 bg-pink-500/10 border-pink-500/20", url: "https://www.instagram.com", sampleCommand: `${name}, scroll Instagram` },
    { id: "youtube", name: "YouTube", category: "Video & Shorts", iconColor: "text-red-400 bg-red-500/10 border-red-500/20", url: "https://www.youtube.com", sampleCommand: `${name}, play YouTube shorts` },
    { id: "spotify", name: "Spotify", category: "Music & Audio", iconColor: "text-green-400 bg-green-500/10 border-green-500/20", url: "https://open.spotify.com", sampleCommand: `${name}, play music on Spotify` },
    { id: "chrome", name: "Chrome", category: "Browser", iconColor: "text-blue-400 bg-blue-500/10 border-blue-500/20", url: "https://www.google.com", sampleCommand: `${name}, search Chrome` },
    { id: "tiktok", name: "TikTok", category: "Short Videos", iconColor: "text-cyan-400 bg-cyan-500/10 border-cyan-500/20", url: "https://www.tiktok.com", sampleCommand: `${name}, open TikTok` },
    { id: "maps", name: "Google Maps", category: "Navigation", iconColor: "text-amber-400 bg-amber-500/10 border-amber-500/20", url: "https://maps.google.com", sampleCommand: `${name}, open Maps` },
    { id: "settings", name: "Android Settings", category: "System Control", iconColor: "text-purple-400 bg-purple-500/10 border-purple-500/20", url: "chrome://settings", sampleCommand: `${name}, open Android Settings` },
  ];
};

export default function App() {
  const { 
    status, 
    isSpeaking, 
    isListening, 
    isScreenSharing, 
    error, 
    voicePersona,
    setVoicePersona,
    connect, 
    disconnect, 
    startScreenShare, 
    stopScreenShare,
    sendImageFrame
  } = useLiveSession();
  const [showKeyModal, setShowKeyModal] = useState(false);
  const [showAccessibilityModal, setShowAccessibilityModal] = useState(false);
  const [showApkModal, setShowApkModal] = useState(false);
  const [showMemoryModal, setShowMemoryModal] = useState(false);
  const [showVoiceModal, setShowVoiceModal] = useState(false);
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isInstalled, setIsInstalled] = useState(false);
  
  const [apiKeyInput, setApiKeyInput] = useState("");
  const [savedKey, setSavedKey] = useState<string | null>(null);

  const screenshotInputRef = useRef<HTMLInputElement>(null);

  // Android Accessibility & Overlay Permissions state
  const [accessibilityServiceEnabled, setAccessibilityServiceEnabled] = useState(true);
  const [displayOverAppsEnabled, setDisplayOverAppsEnabled] = useState(true);
  const [appLauncherEnabled, setAppLauncherEnabled] = useState(true);
  const [batteryExemptionEnabled, setBatteryExemptionEnabled] = useState(true);
  const [showFloatingWidget, setShowFloatingWidget] = useState(false);

  // Toast notifications for voice-triggered app control
  const [actionToast, setActionToast] = useState<{ title: string; subtitle: string } | null>(null);

  useEffect(() => {
    const handleBeforeInstall = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };
    window.addEventListener("beforeinstallprompt", handleBeforeInstall);

    if (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches) {
      setIsInstalled(true);
    }

    return () => window.removeEventListener("beforeinstallprompt", handleBeforeInstall);
  }, []);

  const handleInstallPWA = async () => {
    if (deferredPrompt) {
      deferredPrompt.prompt();
      const { outcome } = await deferredPrompt.userChoice;
      if (outcome === "accepted") {
        setIsInstalled(true);
        setDeferredPrompt(null);
        setActionToast({
          title: "Installing Zoya WebAPK 🎉",
          subtitle: "Zoya is now installing on your Android home screen!"
        });
        setTimeout(() => setActionToast(null), 4000);
      }
    } else {
      alert("To install Zoya on Android:\n1. Open this app in Chrome on your phone.\n2. Tap Chrome Menu (⋮) -> 'Install app' or 'Add to Home screen'.\n3. Android automatically compiles & installs it as a native WebAPK app icon!");
    }
  };

  const renderSelectedModelSection = () => (
    <div
      onClick={() => setShowVoiceModal(true)}
      className={`w-full max-w-sm mx-auto p-4 rounded-2xl border transition-all cursor-pointer group backdrop-blur-md text-left flex items-center justify-between gap-3 shadow-lg ${
        voicePersona === "alex"
          ? "bg-emerald-500/10 border-emerald-500/30 hover:border-emerald-500/50"
          : voicePersona === "male"
          ? "bg-blue-500/10 border-blue-500/30 hover:border-blue-500/50"
          : "bg-pink-500/10 border-pink-500/30 hover:border-pink-500/50"
      }`}
    >
      <div className="flex items-center gap-3 min-w-0">
        <div
          className={`p-2.5 rounded-xl border shrink-0 ${
            voicePersona === "alex"
              ? "bg-emerald-500/20 border-emerald-500/30 text-emerald-400"
              : voicePersona === "male"
              ? "bg-blue-500/20 border-blue-500/30 text-blue-400"
              : "bg-pink-500/20 border-pink-500/30 text-pink-400"
          }`}
        >
          {voicePersona === "alex" ? (
            <Smile className="w-5 h-5" />
          ) : voicePersona === "male" ? (
            <User className="w-5 h-5" />
          ) : (
            <Heart className="w-5 h-5" />
          )}
        </div>
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-bold uppercase tracking-wider text-zinc-400">
              Selected Model
            </span>
            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full bg-white/10 text-[10px] font-mono text-zinc-300">
              <span className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse" />
              Active
            </span>
          </div>
          <div className="flex items-center gap-2 mt-0.5">
            <h3 className="font-bold text-base text-white truncate">
              {getPersonaName(voicePersona)}
            </h3>
            <span
              className={`text-[11px] font-mono px-2 py-0.5 rounded-full border shrink-0 ${
                voicePersona === "alex"
                  ? "bg-emerald-500/20 border-emerald-500/30 text-emerald-300"
                  : voicePersona === "male"
                  ? "bg-blue-500/20 border-blue-500/30 text-blue-300"
                  : "bg-pink-500/20 border-pink-500/30 text-pink-300"
              }`}
            >
              {getPersonaVoiceName(voicePersona)}
            </span>
          </div>
          <p className="text-xs text-zinc-400 mt-0.5 truncate">
            {getPersonaDescription(voicePersona)}
          </p>
        </div>
      </div>
      <div className="flex items-center gap-1 text-xs font-semibold text-zinc-400 group-hover:text-white transition-colors shrink-0">
        <Volume2 className="w-4 h-4" />
        <span className="hidden sm:inline">Change</span>
        <ChevronRight className="w-4 h-4" />
      </div>
    </div>
  );

  useEffect(() => {
    const key = localStorage.getItem("zoya_gemini_api_key");
    if (key) {
      setSavedKey(key);
      setApiKeyInput(key);
    }

    // Load accessibility settings from localStorage
    const savedAcc = localStorage.getItem("zoya_acc_service");
    if (savedAcc !== null) setAccessibilityServiceEnabled(savedAcc === "true");

    const savedOverlay = localStorage.getItem("zoya_overlay_perm");
    if (savedOverlay !== null) setDisplayOverAppsEnabled(savedOverlay === "true");

    const savedFloat = localStorage.getItem("zoya_floating_widget");
    if (savedFloat !== null) setShowFloatingWidget(savedFloat === "true");
  }, []);

  const handleScreenshotUpload = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = () => {
        const dataUrl = reader.result as string;
        const base64Jpeg = dataUrl.split(",")[1];
        if (base64Jpeg) {
          sendImageFrame(base64Jpeg);
          setActionToast({
            title: `Screenshot Sent 📸`,
            subtitle: `Zoya received screen image and is reading content!`
          });
          setTimeout(() => setActionToast(null), 4000);
        }
      };
      reader.readAsDataURL(file);
    }
  };

  useEffect(() => {
    // Listen for custom app action events dispatched by Gemini Live tool handler
    const handleAppAction = (e: Event) => {
      const customEvent = e as CustomEvent<{ type: string; appName?: string; action?: string; mode?: string; error?: string }>;
      if (customEvent.detail) {
        if (customEvent.detail.type === "launch_app") {
          setActionToast({
            title: `Launching ${customEvent.detail.appName}`,
            subtitle: `Triggered via Android Accessibility Intent (${customEvent.detail.action || "open"})`
          });
        } else if (customEvent.detail.type === "screen_control") {
          setActionToast({
            title: `Screen Gesture Executed`,
            subtitle: `Accessibility Action: ${customEvent.detail.action?.replace("_", " ").toUpperCase()}`
          });
        } else if (customEvent.detail.type === "screen_vision") {
          if (customEvent.detail.action === "started") {
            const isCam = customEvent.detail.mode === "camera";
            setActionToast({
              title: isCam ? `Camera Vision Active 📷` : `Screen Vision Active 👁️`,
              subtitle: isCam 
                ? `Camera is streaming live to Zoya. Point at screen or text!` 
                : `Zoya can now SEE & READ your screen in real time.`
            });
          } else if (customEvent.detail.action === "stopped") {
            setActionToast({
              title: `Vision Mode Paused`,
              subtitle: `Stopped live screen/camera frame capture.`
            });
          } else if (customEvent.detail.action === "error") {
            setActionToast({
              title: `Screen Capture Notice`,
              subtitle: customEvent.detail.error || "Permission required for screen or camera capture."
            });
          }
        } else if (customEvent.detail.type === "read_screen") {
          setActionToast({
            title: `Reading Screen Text 📖`,
            subtitle: `Zoya is analyzing active screen content...`
          });
        } else if (customEvent.detail.type === "remember_fact") {
          setActionToast({
            title: `Saved to Long-Term Memory 🧠`,
            subtitle: `Zoya remembered: "${(customEvent.detail as any).fact || "personal fact"}"`
          });
        }
        setTimeout(() => setActionToast(null), 4000);
      }
    };

    window.addEventListener("zoya_app_action", handleAppAction);
    return () => window.removeEventListener("zoya_app_action", handleAppAction);
  }, []);

  useEffect(() => {
    if (status === "connected") {
      setShowKeyModal(false);
    }
  }, [status]);

  const handleSaveKey = () => {
    const trimmed = apiKeyInput.trim();
    if (trimmed) {
      localStorage.setItem("zoya_gemini_api_key", trimmed);
      setSavedKey(trimmed);
      setShowKeyModal(false);
      connect(trimmed);
    }
  };

  const handleClearKey = () => {
    localStorage.removeItem("zoya_gemini_api_key");
    setSavedKey(null);
    setApiKeyInput("");
  };

  const toggleAccessibilityService = (val: boolean) => {
    setAccessibilityServiceEnabled(val);
    localStorage.setItem("zoya_acc_service", String(val));
  };

  const toggleDisplayOverApps = (val: boolean) => {
    setDisplayOverAppsEnabled(val);
    localStorage.setItem("zoya_overlay_perm", String(val));
  };

  const toggleFloatingWidget = (val: boolean) => {
    setShowFloatingWidget(val);
    localStorage.setItem("zoya_floating_widget", String(val));
  };

  const handleToggle = () => {
    if (status === "connected") {
      disconnect();
    } else {
      const key = savedKey || process.env.GEMINI_API_KEY;
      if (!key || key === "MY_GEMINI_API_KEY") {
        setShowKeyModal(true);
      } else {
        connect();
      }
    }
  };

  const simulateAppLaunch = (app: AndroidAppItem) => {
    setActionToast({
      title: `Testing ${app.name} Voice Trigger`,
      subtitle: `Opening ${app.name} via Accessibility Service...`
    });
    setTimeout(() => setActionToast(null), 3500);
    window.open(app.url, "_blank");
  };

  return (
    <div className="min-h-screen bg-black text-white font-sans selection:bg-pink-500/30 overflow-hidden flex flex-col items-center justify-center p-6 relative">
      {/* Background Glow */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-[20%] -left-[10%] w-[60%] h-[60%] bg-pink-600/10 blur-[120px] rounded-full" />
        <div className="absolute -bottom-[20%] -right-[10%] w-[60%] h-[60%] bg-blue-600/10 blur-[120px] rounded-full" />
      </div>

      {/* Action Toast Notification */}
      <AnimatePresence>
        {actionToast && (
          <motion.div
            key="action-toast"
            initial={{ opacity: 0, y: -40, scale: 0.9 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -20, scale: 0.9 }}
            className="fixed top-6 z-50 px-5 py-3 rounded-2xl bg-zinc-900/90 border border-pink-500/30 text-white shadow-2xl backdrop-blur-xl flex items-center gap-3.5 max-w-md"
          >
            <div className="w-9 h-9 rounded-xl bg-pink-500/20 border border-pink-500/40 flex items-center justify-center text-pink-400 shrink-0 animate-pulse">
              <Zap className="w-5 h-5" />
            </div>
            <div>
              <p className="font-semibold text-sm text-pink-300">{actionToast.title}</p>
              <p className="text-xs text-zinc-400">{actionToast.subtitle}</p>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Header */}
      <motion.div 
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="absolute top-8 left-0 right-0 px-6 flex items-center justify-between z-20 max-w-4xl mx-auto"
      >
        <div className="flex items-center gap-2 px-4 py-1.5 rounded-full bg-white/5 border border-white/10 backdrop-blur-md">
          <div className={`w-2 h-2 rounded-full ${status === "connected" ? "bg-green-500 shadow-[0_0_8px_rgba(34,197,94,0.6)]" : "bg-zinc-500"}`} />
          <span className="text-xs font-medium tracking-widest uppercase text-zinc-400">
            {status === "connected" ? `${getPersonaName(voicePersona)} is Live` : `${getPersonaName(voicePersona)} is Offline`}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowVoiceModal(true)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full border transition-colors backdrop-blur-md text-xs font-semibold ${
              voicePersona === "alex"
                ? "bg-emerald-500/10 border-emerald-500/30 hover:bg-emerald-500/20 text-emerald-300"
                : voicePersona === "male"
                ? "bg-blue-500/10 border-blue-500/30 hover:bg-blue-500/20 text-blue-300"
                : "bg-pink-500/10 border-pink-500/30 hover:bg-pink-500/20 text-pink-300"
            }`}
            title="Switch AI Voice Persona (Alex / Zayn / Zoya)"
          >
            {voicePersona === "alex" ? (
              <Smile className="w-3.5 h-3.5 text-emerald-400" />
            ) : voicePersona === "male" ? (
              <User className="w-3.5 h-3.5 text-blue-400" />
            ) : (
              <Heart className="w-3.5 h-3.5 text-pink-400" />
            )}
            <span>{getPersonaLabel(voicePersona)}</span>
          </button>

          <button
            onClick={() => setShowMemoryModal(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-purple-500/10 border border-purple-500/30 hover:bg-purple-500/20 text-purple-300 transition-colors backdrop-blur-md text-xs font-semibold"
            title="Saved Memory & Chat History (यादें और पुरानी बातचीत)"
          >
            <Brain className="w-3.5 h-3.5 text-purple-400" />
            <span className="hidden sm:inline">Memory</span>
          </button>

          <button
            onClick={() => setShowApkModal(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-pink-500/10 border border-pink-500/30 hover:bg-pink-500/20 text-pink-300 transition-colors backdrop-blur-md text-xs font-semibold"
            title="Convert to APK or Install App on Android"
          >
            <Download className="w-3.5 h-3.5 text-pink-400 animate-bounce" />
            <span className="hidden sm:inline">APK</span>
          </button>

          <button
            onClick={() => setShowKeyModal(true)}
            className="w-10 h-10 rounded-full bg-white/5 border border-white/10 flex items-center justify-center text-zinc-400 hover:text-white hover:bg-white/10 transition-colors"
            title="API Key Settings"
          >
            <Key className="w-4 h-4" />
          </button>
        </div>
      </motion.div>

      {/* Main Content */}
      <div className="relative z-10 w-full max-w-md flex flex-col items-center gap-12">
        <AnimatePresence mode="wait">
          {status === "disconnected" ? (
            <motion.div
              key="intro"
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 1.1 }}
              className="text-center space-y-5 w-full"
            >
              <h1 className="text-4xl sm:text-5xl font-bold tracking-tighter bg-gradient-to-br from-white via-zinc-200 to-pink-500 bg-clip-text text-transparent">
                Meet {getPersonaName(voicePersona)}.
              </h1>
              <p className="text-zinc-400 text-sm sm:text-base max-w-[340px] mx-auto leading-relaxed">
                Choose your AI companion model below. Control Android apps and talk hands-free with real-time screen vision.
              </p>
              {/* Dedicated Currently Selected Model Section */}
              <div className="pt-2">
                {renderSelectedModelSection()}
              </div>
            </motion.div>
          ) : status === "connecting" ? (
            <motion.div
              key="connecting"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex flex-col items-center gap-4"
            >
              <Loader2 className="w-12 h-12 text-pink-500 animate-spin" />
              <p className="text-zinc-400 font-medium animate-pulse">
                Waking {getPersonaName(voicePersona)} up...
              </p>
            </motion.div>
          ) : (
            <motion.div
              key="active"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex flex-col items-center gap-4 sm:gap-6 md:gap-8 w-full"
            >
              <div className="relative">
                {/* Pulse Rings */}
                {isSpeaking && (
                  <>
                    <motion.div 
                      className="absolute inset-0 rounded-full bg-pink-500/20"
                      animate={{ scale: [1, 1.5, 1], opacity: [0.5, 0, 0.5] }}
                      transition={{ duration: 2, repeat: Infinity }}
                    />
                    <motion.div 
                      className="absolute inset-0 rounded-full bg-pink-500/10"
                      animate={{ scale: [1, 2, 1], opacity: [0.3, 0, 0.3] }}
                      transition={{ duration: 3, repeat: Infinity, delay: 0.5 }}
                    />
                  </>
                )}
                
                <div className="w-[clamp(8rem,35vw,12rem)] h-[clamp(8rem,35vw,12rem)] rounded-full bg-gradient-to-br from-zinc-800 to-zinc-900 border border-white/10 flex items-center justify-center relative overflow-hidden shadow-2xl transition-all">
                  <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,rgba(236,72,153,0.15)_0%,transparent_70%)]" />
                  
                  <AnimatePresence mode="wait">
                    {isSpeaking ? (
                      <motion.div
                        key="speaking"
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        className="flex flex-col items-center gap-2"
                      >
                        <Visualizer active={true} color="bg-pink-500" />
                        <span className="text-[10px] uppercase tracking-widest text-pink-400 font-bold">Speaking</span>
                      </motion.div>
                    ) : (
                      <motion.div
                        key="listening"
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        className="flex flex-col items-center gap-2"
                      >
                        <Visualizer active={isListening} color="bg-blue-400" />
                        <span className="text-[10px] uppercase tracking-widest text-blue-400 font-bold">Listening</span>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              </div>

              <div className="text-center space-y-2">
                <p className="text-zinc-300 text-lg sm:text-xl font-medium">
                  {isSpeaking ? `${getPersonaName(voicePersona)} is talking...` : "Go ahead, say something."}
                </p>
                <p className="text-zinc-500 text-xs sm:text-sm italic">
                  {isScreenSharing ? `👁️ Screen Vision ON: ${getPersonaName(voicePersona)} can see and read your screen!` : `Tip: Turn on Screen Vision so ${getPersonaName(voicePersona)} can see & read your screen!`}
                </p>

                {/* Screen Vision Badge */}
                {isScreenSharing && (
                  <motion.div 
                    initial={{ scale: 0.9, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/30 text-emerald-300 text-xs font-semibold"
                  >
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                    <Eye className="w-3.5 h-3.5" />
                    <span>Real-time Screen Seeing Active</span>
                  </motion.div>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Error State */}
        {error && (
          <motion.div 
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            className="flex flex-col gap-2 p-4 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-sm w-full"
          >
            <div className="flex items-center gap-3">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <p className="flex-1">{error}</p>
            </div>
            <button
              onClick={() => setShowKeyModal(true)}
              className="mt-1 self-start px-3 py-1.5 bg-red-500/20 hover:bg-red-500/30 text-red-200 text-xs rounded-lg transition-colors flex items-center gap-1.5 font-medium"
            >
              <Key className="w-3.5 h-3.5" />
              <span>Configure API Key</span>
            </button>
          </motion.div>
        )}

        {/* Hidden Screenshot File Input */}
        <input 
          type="file" 
          ref={screenshotInputRef} 
          accept="image/*" 
          onChange={handleScreenshotUpload} 
          className="hidden" 
        />

        {/* Controls */}
        <div className="flex flex-wrap items-center justify-center gap-3 sm:gap-4 md:gap-6 w-full">
          {status === "connected" && (
            <>
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                onClick={isScreenSharing ? stopScreenShare : startScreenShare}
                className={`
                  flex items-center justify-center flex-1 w-[clamp(110px,25vw,200px)] gap-1.5 sm:gap-2 px-2 py-2.5 sm:px-4 sm:py-3 rounded-xl sm:rounded-2xl font-semibold text-[clamp(10px,2vw,12px)] transition-all duration-300 border shadow-lg
                  ${isScreenSharing 
                    ? "bg-emerald-500/20 border-emerald-500/50 text-emerald-300 shadow-emerald-500/10" 
                    : "bg-zinc-900 border-white/10 hover:border-pink-500/40 text-zinc-300 hover:text-white"}
                `}
                title={isScreenSharing ? "Stop Screen Seeing" : "Start Screen Seeing & AI Reading"}
              >
                <Eye className={`w-3.5 h-3.5 sm:w-4 sm:h-4 shrink-0 ${isScreenSharing ? "text-emerald-400 animate-pulse" : "text-pink-400"}`} />
                <span className="truncate">{isScreenSharing ? "Screen Seeing ON" : "See & Read Screen"}</span>
              </motion.button>

              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                onClick={() => screenshotInputRef.current?.click()}
                className="flex items-center justify-center flex-1 w-[clamp(110px,25vw,200px)] gap-1.5 sm:gap-2 px-2 py-2.5 sm:px-4 sm:py-3 rounded-xl sm:rounded-2xl font-semibold text-[clamp(10px,2vw,12px)] transition-all duration-300 border border-white/10 bg-zinc-900 hover:border-cyan-500/40 text-zinc-300 hover:text-cyan-300 shadow-lg"
                title="Upload or snap a screenshot for Zoya to read immediately"
              >
                <Camera className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-cyan-400 shrink-0" />
                <span className="truncate">Upload Screenshot</span>
              </motion.button>
            </>
          )}

          <motion.button
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            onClick={handleToggle}
            className={`
              relative w-[clamp(4rem,18vw,5rem)] h-[clamp(4rem,18vw,5rem)] rounded-full flex items-center justify-center transition-all duration-500 shrink-0 mx-auto
              ${status === "connected" 
                ? "bg-zinc-900 border-2 border-pink-500 text-pink-500 shadow-[0_0_30px_rgba(236,72,153,0.3)]" 
                : "bg-white text-black hover:bg-zinc-200 shadow-[0_0_20px_rgba(255,255,255,0.2)]"}
            `}
          >
            {status === "connected" ? (
              <Power className="w-6 h-6 sm:w-8 sm:h-8" />
            ) : status === "connecting" ? (
              <Loader2 className="w-6 h-6 sm:w-8 sm:h-8 animate-spin" />
            ) : (
              <Mic className="w-6 h-6 sm:w-8 sm:h-8" />
            )}
            
            {/* Tooltip-like hint */}
            <div className="absolute -bottom-7 whitespace-nowrap text-[9px] sm:text-[10px] uppercase tracking-widest text-zinc-500 font-bold">
              {status === "connected" ? "Disconnect" : "Start Session"}
            </div>
          </motion.button>
        </div>
      </div>

      {/* Footer Info */}
      <motion.div 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 1 }}
        className="absolute bottom-8 text-zinc-600 text-[10px] uppercase tracking-[0.2em] font-medium flex items-center gap-2"
      >
        <span>Powered by Gemini 3.1 Flash Live</span>
        <span>•</span>
        <button 
          onClick={() => setShowAccessibilityModal(true)}
          className="text-pink-400 hover:underline"
        >
          Android Accessibility Active
        </button>
      </motion.div>

      {/* Floating Action Hint */}
      {status === "connected" && (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="absolute bottom-20 flex items-center gap-2 text-zinc-400 text-xs bg-white/5 px-4 py-2 rounded-full border border-white/10 backdrop-blur-sm"
        >
          <Smartphone className="w-3.5 h-3.5 text-pink-400" />
          <span>Try: "{getPersonaName(voicePersona)}, read my screen" or "{getPersonaName(voicePersona)}, open WhatsApp"</span>
        </motion.div>
      )}

      {/* ANDROID ACCESSIBILITY & APP CONTROL SETTINGS MODAL */}
      <AnimatePresence>
        {showAccessibilityModal && (
          <motion.div
            key="accessibility-modal"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4 overflow-y-auto"
          >
            <motion.div
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              className="bg-zinc-900 border border-white/10 rounded-2xl p-6 w-full max-w-lg space-y-6 relative shadow-2xl max-h-[90vh] overflow-y-auto"
            >
              <button
                onClick={() => setShowAccessibilityModal(false)}
                className="absolute top-4 right-4 text-zinc-400 hover:text-white p-1.5 rounded-lg bg-white/5 hover:bg-white/10 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>

              <div className="flex items-center gap-3 border-b border-white/10 pb-4">
                <div className="p-3 rounded-2xl bg-gradient-to-br from-pink-500/20 to-purple-500/20 border border-pink-500/30 text-pink-400">
                  <Smartphone className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="font-bold text-xl text-white">Android App Control & Accessibility</h3>
                  <p className="text-xs text-zinc-400">Control apps over other apps & automate screen actions</p>
                </div>
              </div>

              {/* Status Banner */}
              <div className="p-4 rounded-xl bg-gradient-to-r from-emerald-500/10 via-pink-500/10 to-purple-500/10 border border-emerald-500/20 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <ShieldCheck className="w-5 h-5 text-emerald-400" />
                  <div>
                    <p className="text-xs font-semibold text-white">Accessibility Service Engine</p>
                    <p className="text-[11px] text-zinc-400">System Permission Handler Ready</p>
                  </div>
                </div>
                <span className="px-2.5 py-1 rounded-full bg-emerald-500/20 text-emerald-300 text-[10px] font-bold uppercase tracking-wider">
                  Active
                </span>
              </div>

              {/* Permissions & Controls Section */}
              <div className="space-y-4">
                <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
                  <Sliders className="w-4 h-4 text-pink-400" />
                  <span>Android System Permissions</span>
                </h4>

                {/* Switch 1: Accessibility Service */}
                <div className="p-3.5 rounded-xl bg-black/40 border border-white/5 flex items-start justify-between gap-4">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-semibold text-white">Accessibility Service</p>
                      <span className="text-[10px] px-2 py-0.5 rounded bg-pink-500/20 text-pink-300 font-mono">
                        BIND_ACCESSIBILITY_SERVICE
                      </span>
                    </div>
                    <p className="text-xs text-zinc-400">
                      Allows Zoya to read screen elements, trigger clicks, scroll social feeds, and execute cross-app voice commands.
                    </p>
                  </div>
                  <button
                    onClick={() => toggleAccessibilityService(!accessibilityServiceEnabled)}
                    className={`w-12 h-6 rounded-full p-1 transition-colors shrink-0 ${
                      accessibilityServiceEnabled ? "bg-pink-500" : "bg-zinc-700"
                    }`}
                  >
                    <div
                      className={`w-4 h-4 rounded-full bg-white transition-transform ${
                        accessibilityServiceEnabled ? "translate-x-6" : "translate-x-0"
                      }`}
                    />
                  </button>
                </div>

                {/* Switch 2: Display Over Other Apps */}
                <div className="p-3.5 rounded-xl bg-black/40 border border-white/5 flex items-start justify-between gap-4">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-semibold text-white">Display Over Other Apps</p>
                      <span className="text-[10px] px-2 py-0.5 rounded bg-blue-500/20 text-blue-300 font-mono">
                        SYSTEM_ALERT_WINDOW
                      </span>
                    </div>
                    <p className="text-xs text-zinc-400">
                      Allows Zoya's floating voice bubble to stay visible over Instagram, WhatsApp, YouTube, and TikTok.
                    </p>
                  </div>
                  <button
                    onClick={() => toggleDisplayOverApps(!displayOverAppsEnabled)}
                    className={`w-12 h-6 rounded-full p-1 transition-colors shrink-0 ${
                      displayOverAppsEnabled ? "bg-pink-500" : "bg-zinc-700"
                    }`}
                  >
                    <div
                      className={`w-4 h-4 rounded-full bg-white transition-transform ${
                        displayOverAppsEnabled ? "translate-x-6" : "translate-x-0"
                      }`}
                    />
                  </button>
                </div>

                {/* Switch 3: Floating Overlay Widget */}
                <div className="p-3.5 rounded-xl bg-black/40 border border-white/5 flex items-start justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-semibold text-white">Enable Floating Draggable Bubble</p>
                    <p className="text-xs text-zinc-400">
                      Show persistent floating mic widget on screen to control Zoya while browsing other apps.
                    </p>
                  </div>
                  <button
                    onClick={() => toggleFloatingWidget(!showFloatingWidget)}
                    className={`w-12 h-6 rounded-full p-1 transition-colors shrink-0 ${
                      showFloatingWidget ? "bg-pink-500" : "bg-zinc-700"
                    }`}
                  >
                    <div
                      className={`w-4 h-4 rounded-full bg-white transition-transform ${
                        showFloatingWidget ? "translate-x-6" : "translate-x-0"
                      }`}
                    />
                  </button>
                </div>

                {/* Switch 4: Battery Saver Optimization Exemption */}
                <div className="p-3.5 rounded-xl bg-black/40 border border-white/5 flex items-start justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-semibold text-white">Ignore Battery Optimization</p>
                    <p className="text-xs text-zinc-400">
                      Keeps Zoya background worker active for instant voice wake commands without sleeping.
                    </p>
                  </div>
                  <button
                    onClick={() => setBatteryExemptionEnabled(!batteryExemptionEnabled)}
                    className={`w-12 h-6 rounded-full p-1 transition-colors shrink-0 ${
                      batteryExemptionEnabled ? "bg-pink-500" : "bg-zinc-700"
                    }`}
                  >
                    <div
                      className={`w-4 h-4 rounded-full bg-white transition-transform ${
                        batteryExemptionEnabled ? "translate-x-6" : "translate-x-0"
                      }`}
                    />
                  </button>
                </div>

                {/* Switch 5: Real-time Screen Vision & OCR Reader */}
                <div className="p-3.5 rounded-xl bg-black/40 border border-white/5 flex items-start justify-between gap-4">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-semibold text-white">Screen Vision & OCR AI Reader</p>
                      <span className="text-[10px] px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 font-mono">
                        DISPLAY_CAPTURE
                      </span>
                    </div>
                    <p className="text-xs text-zinc-400">
                      Streams live video frames so Zoya can SEE, READ, and DESCRIBE text, messages, web pages, and photos on your screen.
                    </p>
                  </div>
                  <button
                    onClick={isScreenSharing ? stopScreenShare : startScreenShare}
                    className={`px-3 py-1.5 rounded-lg text-xs font-semibold shrink-0 transition-colors ${
                      isScreenSharing 
                        ? "bg-emerald-500/20 text-emerald-300 border border-emerald-500/40" 
                        : "bg-pink-500/20 text-pink-300 border border-pink-500/30 hover:bg-pink-500/30"
                    }`}
                  >
                    {isScreenSharing ? "Active 👁️" : "Toggle Vision"}
                  </button>
                </div>
              </div>

              {/* Supported Apps Launcher & Test Section */}
              <div className="space-y-3 pt-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-zinc-400 flex items-center gap-2">
                  <AppWindow className="w-4 h-4 text-purple-400" />
                  <span>Controllable Android Apps</span>
                </h4>
                <div className="grid grid-cols-2 gap-2.5">
                  {getAndroidApps(voicePersona).map((app) => (
                    <button
                      key={app.id}
                      onClick={() => simulateAppLaunch(app)}
                      className="p-3 rounded-xl bg-black/50 border border-white/5 hover:border-pink-500/30 text-left transition-all flex items-center justify-between group"
                    >
                      <div>
                        <div className="flex items-center gap-1.5">
                          <p className="text-xs font-bold text-white group-hover:text-pink-300 transition-colors">{app.name}</p>
                          <ExternalLink className="w-3 h-3 text-zinc-500 opacity-0 group-hover:opacity-100 transition-opacity" />
                        </div>
                        <p className="text-[10px] text-zinc-500">{app.sampleCommand}</p>
                      </div>
                      <span className={`px-2 py-0.5 rounded text-[9px] font-medium border ${app.iconColor}`}>
                        Ready
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Android Settings Step-by-Step Guide */}
              <div className="p-4 rounded-xl bg-pink-500/5 border border-pink-500/20 space-y-2.5">
                <p className="text-xs font-bold text-pink-300 flex items-center gap-1.5">
                  <Shield className="w-4 h-4" />
                  <span>How to Enable on Android Settings</span>
                </p>
                <ol className="text-xs text-zinc-400 space-y-1.5 list-decimal pl-4">
                  <li>Open phone <strong>Settings</strong> &rarr; <strong>Accessibility</strong>.</li>
                  <li>Tap <strong>Installed Apps / Downloaded Services</strong>.</li>
                  <li>Select <strong>Zoya AI Assistant</strong> & toggle <strong>ON</strong>.</li>
                  <li>Go to <strong>Special App Access</strong> &rarr; <strong>Display Over Other Apps</strong> &rarr; Enable <strong>Zoya</strong>.</li>
                </ol>
              </div>

              <div className="flex justify-end pt-2">
                <button
                  onClick={() => setShowAccessibilityModal(false)}
                  className="px-5 py-2.5 rounded-xl bg-pink-500 hover:bg-pink-600 text-white font-medium text-xs transition-colors flex items-center gap-2 shadow-lg shadow-pink-500/20"
                >
                  <Check className="w-4 h-4" />
                  <span>Apply Settings & Close</span>
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* API Key Modal */}
      <AnimatePresence>
        {showKeyModal && (
          <motion.div
            key="key-modal"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4"
          >
            <motion.div
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              className="bg-zinc-900 border border-white/10 rounded-2xl p-6 w-full max-w-md space-y-5 relative shadow-2xl"
            >
              <button
                onClick={() => setShowKeyModal(false)}
                className="absolute top-4 right-4 text-zinc-400 hover:text-white p-1 rounded-lg"
              >
                <X className="w-5 h-5" />
              </button>

              <div className="flex items-center gap-3">
                <div className="p-2.5 rounded-xl bg-pink-500/10 border border-pink-500/20 text-pink-400">
                  <Key className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-semibold text-lg text-white">Gemini API Key</h3>
                  <p className="text-xs text-zinc-400">Enter your key to connect with Zoya</p>
                </div>
              </div>

              <div className="space-y-2">
                <label className="text-xs font-medium text-zinc-300">API Key</label>
                <input
                  type="password"
                  value={apiKeyInput}
                  onChange={(e) => setApiKeyInput(e.target.value)}
                  placeholder="Paste AIzaSy... here"
                  className="w-full px-4 py-2.5 rounded-xl bg-black/50 border border-white/10 text-white placeholder:text-zinc-600 focus:outline-none focus:border-pink-500 text-sm font-mono"
                />
                <p className="text-[11px] text-zinc-500">
                  Get a key from <a href="https://aistudio.google.com/app/apikey" target="_blank" rel="noreferrer" className="text-pink-400 underline">Google AI Studio</a>.
                </p>
              </div>

              <div className="flex items-center justify-end gap-2 pt-2">
                {savedKey && (
                  <button
                    onClick={handleClearKey}
                    className="px-4 py-2 rounded-xl text-zinc-400 hover:text-white hover:bg-white/5 text-xs transition-colors"
                  >
                    Clear Key
                  </button>
                )}
                <button
                  onClick={handleSaveKey}
                  disabled={!apiKeyInput.trim()}
                  className="px-5 py-2 rounded-xl bg-pink-500 hover:bg-pink-600 disabled:opacity-50 text-white font-medium text-xs transition-colors flex items-center gap-1.5 shadow-lg shadow-pink-500/20"
                >
                  <Check className="w-4 h-4" />
                  <span>Save & Connect</span>
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* APK Conversion & Installation Modal */}
      <AnimatePresence>
        {showApkModal && (
          <motion.div
            key="apk-modal"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4"
          >
            <motion.div
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              className="bg-zinc-900 border border-pink-500/30 rounded-2xl p-6 w-full max-w-lg space-y-6 relative shadow-2xl max-h-[90vh] overflow-y-auto"
            >
              <button
                onClick={() => setShowApkModal(false)}
                className="absolute top-4 right-4 text-zinc-400 hover:text-white p-1 rounded-lg"
              >
                <X className="w-5 h-5" />
              </button>

              <div className="flex items-center gap-3">
                <div className="p-3 rounded-2xl bg-pink-500/20 border border-pink-500/40 text-pink-400">
                  <Package className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="font-bold text-lg text-white">Convert Zoya to Android APK</h3>
                  <p className="text-xs text-zinc-400">3 simple ways to install Zoya natively on your Android device</p>
                </div>
              </div>

              {/* Method 1: WebAPK 1-Tap Install */}
              <div className="p-4 rounded-xl bg-gradient-to-r from-pink-500/10 to-purple-500/10 border border-pink-500/30 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="w-6 h-6 rounded-full bg-pink-500 text-white text-xs font-bold flex items-center justify-center">1</span>
                    <p className="text-sm font-bold text-white">1-Tap Direct WebAPK Install (Recommended)</p>
                  </div>
                  <span className="text-[10px] uppercase font-mono px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">
                    Instant
                  </span>
                </div>
                <p className="text-xs text-zinc-300 leading-relaxed">
                  Android Chrome automatically compiles Zoya into a native system <strong>WebAPK</strong> app icon on your phone's app drawer with full screen, screen vision, and microphone access.
                </p>
                <button
                  onClick={handleInstallPWA}
                  className="w-full py-3 rounded-xl bg-gradient-to-r from-pink-500 to-purple-600 hover:from-pink-600 hover:to-purple-700 text-white font-bold text-xs transition-all shadow-lg shadow-pink-500/25 flex items-center justify-center gap-2"
                >
                  <DownloadCloud className="w-4 h-4 animate-bounce" />
                  <span>{isInstalled ? "Zoya App Installed!" : "Install WebAPK on Android Phone"}</span>
                </button>
              </div>

              {/* Method 2: PWABuilder APK Generator */}
              <div className="p-4 rounded-xl bg-black/40 border border-white/10 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="w-6 h-6 rounded-full bg-zinc-800 text-zinc-300 text-xs font-bold flex items-center justify-center">2</span>
                    <p className="text-sm font-bold text-white">Generate Standalone .APK File (PWABuilder)</p>
                  </div>
                  <span className="text-[10px] uppercase font-mono px-2 py-0.5 rounded bg-cyan-500/20 text-cyan-300 border border-cyan-500/30">
                    Online Tool
                  </span>
                </div>
                <p className="text-xs text-zinc-400 leading-relaxed">
                  Convert this app's URL on Microsoft PWABuilder or Web2APK to download a signed <strong>.apk</strong> file ready to install or publish to Google Play Store.
                </p>
                <a
                  href="https://www.pwabuilder.com"
                  target="_blank"
                  rel="noreferrer"
                  className="w-full py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 hover:text-white font-semibold text-xs transition-colors flex items-center justify-center gap-2 border border-white/10"
                >
                  <ExternalLink className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Open PWABuilder.com APK Generator</span>
                </a>
              </div>

              {/* Method 3: Native Capacitor APK Build */}
              <div className="p-4 rounded-xl bg-black/40 border border-white/10 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="w-6 h-6 rounded-full bg-zinc-800 text-zinc-300 text-xs font-bold flex items-center justify-center">3</span>
                    <p className="text-sm font-bold text-white">Build Native APK via Capacitor (Dev Mode)</p>
                  </div>
                  <span className="text-[10px] uppercase font-mono px-2 py-0.5 rounded bg-purple-500/20 text-purple-300 border border-purple-500/30">
                    Android Studio
                  </span>
                </div>
                <p className="text-xs text-zinc-400">
                  Run these commands in your local terminal to build a native Android Studio `.apk` project:
                </p>
                <div className="p-3 rounded-lg bg-zinc-950 font-mono text-[11px] text-pink-300 space-y-1 overflow-x-auto border border-white/5 select-all">
                  <p>npm run build</p>
                  <p>npx cap add android</p>
                  <p>npx cap open android</p>
                </div>
              </div>

              <div className="flex justify-end pt-2">
                <button
                  onClick={() => setShowApkModal(false)}
                  className="px-5 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-xs font-semibold transition-colors"
                >
                  Close
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Memory Modal */}
      <MemoryModal isOpen={showMemoryModal} onClose={() => setShowMemoryModal(false)} />
      {/* Voice Persona Selection Modal */}
      <VoicePersonaModal
        isOpen={showVoiceModal}
        onClose={() => setShowVoiceModal(false)}
        currentPersona={voicePersona}
        onSelectPersona={setVoicePersona}
      />
    </div>
  );
}
