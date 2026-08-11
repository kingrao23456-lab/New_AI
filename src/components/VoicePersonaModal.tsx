import React from "react";
import { motion, AnimatePresence } from "motion/react";
import { X, User, Heart, Check, Sparkles, Volume2, Shield, Smile } from "lucide-react";
import { VoicePersona } from "../hooks/useLiveSession";

interface VoicePersonaModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentPersona: VoicePersona;
  onSelectPersona: (persona: VoicePersona) => void;
}

export const VoicePersonaModal: React.FC<VoicePersonaModalProps> = ({
  isOpen,
  onClose,
  currentPersona,
  onSelectPersona,
}) => {
  const personas = [
    {
      id: "alex" as VoicePersona,
      name: "Alex",
      title: "Calm & Supportive Best Friend (Yaar)",
      voiceName: "Puck (Casual & Reassuring)",
      description:
        "Jabardast confidence aur sakoon. Bilkul casual, apne dost ki tarah (Hinglish & English). Ek saccha sathi jo dhairya se sunta hai, bina judgment ke sahi salah deta hai, aur khushiyon mein tumhare saath jashn manata hai.",
      icon: <Smile className="w-6 h-6 text-emerald-400" />,
      tags: ["Sakoon & Confidence", "Saccha Yaar", "Non-Judgmental", "Hinglish + English"],
    },
    {
      id: "male" as VoicePersona,
      name: "Zayn",
      title: "Confident & Smooth Male Companion",
      voiceName: "Fenrir (Deep & Charismatic)",
      description:
        "A confident, charming, witty, and effortlessly smooth male persona. Flirty, playful, and playfully sarcastic tone—like a close, protective best friend/guy who always knows how to tease you just right. Smart, emotionally attuned, and expressive.",
      icon: <User className="w-6 h-6 text-blue-400" />,
      tags: ["Charming", "Protective Best Friend", "Playfully Sarcastic", "Smooth"],
    },
    {
      id: "female" as VoicePersona,
      name: "Zoya",
      title: "Witty & Sassy Female Companion",
      voiceName: "Zephyr (Warm & Sassy)",
      description:
        "A young, confident, witty, and sassy female AI assistant. Her personality is flirty, playful, and slightly teasing—like a close girlfriend talking casually. She is smart, emotionally responsive, and expressive with bold one-liners.",
      icon: <Heart className="w-6 h-6 text-pink-400" />,
      tags: ["Flirty & Playful", "Sassy", "Engaging", "Warm"],
    },
  ];

  const getStyle = (id: VoicePersona) => {
    if (id === "alex") {
      return {
        border: "bg-emerald-500/15 border-emerald-500 shadow-[0_0_20px_rgba(16,185,129,0.25)]",
        iconBox: "bg-emerald-500/20 border-emerald-500/30 text-emerald-400",
        badge: "bg-emerald-500/20 text-emerald-300 border border-emerald-500/30",
        checkBg: "bg-emerald-500 border-emerald-400 text-white",
        tag: "bg-emerald-500/10 text-emerald-300 border border-emerald-500/20",
      };
    }
    if (id === "male") {
      return {
        border: "bg-blue-500/15 border-blue-500 shadow-[0_0_20px_rgba(59,130,246,0.25)]",
        iconBox: "bg-blue-500/20 border-blue-500/30 text-blue-400",
        badge: "bg-blue-500/20 text-blue-300 border border-blue-500/30",
        checkBg: "bg-blue-500 border-blue-400 text-white",
        tag: "bg-blue-500/10 text-blue-300 border border-blue-500/20",
      };
    }
    return {
      border: "bg-pink-500/15 border-pink-500 shadow-[0_0_20px_rgba(236,72,153,0.25)]",
      iconBox: "bg-pink-500/20 border-pink-500/30 text-pink-400",
      badge: "bg-pink-500/20 text-pink-300 border border-pink-500/30",
      checkBg: "bg-pink-500 border-pink-400 text-white",
      tag: "bg-pink-500/10 text-pink-300 border border-pink-500/20",
    };
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          key="persona-modal-backdrop"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4 overflow-y-auto"
          onClick={onClose}
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0, y: 15 }}
            animate={{ scale: 1, opacity: 1, y: 0 }}
            exit={{ scale: 0.95, opacity: 0, y: 15 }}
          onClick={(e) => e.stopPropagation()}
          className="bg-zinc-900 border border-white/10 rounded-3xl p-6 w-full max-w-lg space-y-6 relative shadow-2xl overflow-hidden"
        >
          {/* Background Glow */}
          <div className="absolute -top-24 -right-24 w-48 h-48 rounded-full bg-gradient-to-br from-blue-500/20 via-emerald-500/20 to-pink-500/20 blur-3xl pointer-events-none" />

          {/* Header */}
          <div className="flex items-center justify-between border-b border-white/10 pb-4">
            <div className="flex items-center gap-3">
              <div className="p-3 rounded-2xl bg-gradient-to-br from-emerald-500/20 via-blue-500/20 to-pink-500/20 border border-white/10">
                <Volume2 className="w-6 h-6 text-pink-300" />
              </div>
              <div>
                <h3 className="font-bold text-xl text-white flex items-center gap-2">
                  <span>AI Voice Persona</span>
                  <Sparkles className="w-4 h-4 text-amber-400 animate-pulse" />
                </h3>
                <p className="text-xs text-zinc-400">
                  Select your preferred AI voice and conversational tone
                </p>
              </div>
            </div>
            <button
              onClick={onClose}
              className="text-zinc-400 hover:text-white p-2 rounded-xl bg-white/5 hover:bg-white/10 transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Persona Selection Cards */}
          <div className="space-y-4">
            {personas.map((persona) => {
              const isSelected = currentPersona === persona.id;
              const style = getStyle(persona.id);
              return (
                <div
                  key={persona.id}
                  onClick={() => {
                    onSelectPersona(persona.id);
                    onClose();
                  }}
                  className={`relative p-5 rounded-2xl border transition-all cursor-pointer group select-none ${
                    isSelected
                      ? style.border
                      : "bg-black/40 border-white/10 hover:border-white/20 hover:bg-white/5"
                  }`}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex items-center gap-3">
                      <div className={`p-3 rounded-2xl border ${style.iconBox}`}>
                        {persona.icon}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <h4 className="font-bold text-lg text-white">
                            {persona.name}
                          </h4>
                          <span
                            className={`text-[10px] font-mono px-2 py-0.5 rounded-full ${style.badge}`}
                          >
                            {persona.voiceName}
                          </span>
                        </div>
                        <p className="text-xs font-medium text-zinc-300 mt-0.5">
                          {persona.title}
                        </p>
                      </div>
                    </div>

                    <div
                      className={`w-6 h-6 rounded-full flex items-center justify-center border transition-all shrink-0 ${
                        isSelected
                          ? style.checkBg
                          : "border-zinc-600 bg-zinc-800"
                      }`}
                    >
                      {isSelected && <Check className="w-3.5 h-3.5 stroke-[3]" />}
                    </div>
                  </div>

                  <p className="text-xs text-zinc-300 leading-relaxed mt-3 pl-1">
                    {persona.description}
                  </p>

                  <div className="flex flex-wrap gap-1.5 mt-3 pt-3 border-t border-white/5">
                    {persona.tags.map((tag, idx) => (
                      <span
                        key={idx}
                        className={`text-[10px] px-2.5 py-0.5 rounded-full font-medium ${style.tag}`}
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Footer Info */}
          <div className="p-3.5 rounded-xl bg-white/5 border border-white/10 flex items-center gap-3">
            <Shield className="w-4 h-4 text-purple-400 shrink-0" />
            <p className="text-xs text-zinc-400 leading-tight">
              Switching voice persona will instantly apply the new voice and conversational personality for your next session.
            </p>
          </div>

          <div className="flex justify-end pt-1">
            <button
              onClick={onClose}
              className="px-6 py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 hover:text-white font-semibold text-xs transition-colors"
            >
              Close
            </button>
          </div>
        </motion.div>
      </motion.div>
    )}
    </AnimatePresence>
  );
};

