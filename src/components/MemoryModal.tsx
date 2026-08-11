import React, { useState, useEffect } from "react";
import { motion, AnimatePresence } from "motion/react";
import { 
  Brain, History, Trash2, Plus, Search, X, Check, Download, Sparkles, 
  Clock, FileText, UserCheck, Heart, MessageSquare, AlertTriangle, Copy, ChevronDown, ChevronUp
} from "lucide-react";
import { 
  MemoryItem, ChatSessionLog, getMemoryBank, saveMemoryBank, addMemoryItem, 
  deleteMemoryItem, getChatHistory, saveChatHistory, addChatSession, 
  deleteChatSession, clearAllMemory 
} from "../lib/memory";

interface MemoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSessionSaveNeeded?: () => void;
}

export function MemoryModal({ isOpen, onClose }: MemoryModalProps) {
  const [activeTab, setActiveTab] = useState<"memories" | "chats">("memories");
  const [memories, setMemories] = useState<MemoryItem[]>([]);
  const [chatLogs, setChatLogs] = useState<ChatSessionLog[]>([]);
  
  // New memory input state
  const [newMemoryText, setNewMemoryText] = useState("");
  const [newMemoryCategory, setNewMemoryCategory] = useState<MemoryItem["category"]>("fact");
  const [showAddForm, setShowAddForm] = useState(false);

  // Search filter
  const [searchQuery, setSearchQuery] = useState("");

  // Expanded chat session view
  const [expandedChatId, setExpandedChatId] = useState<string | null>(null);

  // Copy toast feedback
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const reloadData = () => {
    setMemories(getMemoryBank());
    setChatLogs(getChatHistory());
  };

  useEffect(() => {
    if (isOpen) {
      reloadData();
    }
  }, [isOpen]);

  const handleAddMemory = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newMemoryText.trim()) return;
    addMemoryItem(newMemoryText.trim(), newMemoryCategory);
    setNewMemoryText("");
    setShowAddForm(false);
    reloadData();
  };

  const handleDeleteMemory = (id: string) => {
    deleteMemoryItem(id);
    reloadData();
  };

  const handleDeleteChat = (id: string) => {
    deleteChatSession(id);
    reloadData();
  };

  const handleClearAll = () => {
    if (window.confirm("Are you sure you want to clear all saved memories and chat history? Zoya will forget past chats.")) {
      clearAllMemory();
      reloadData();
    }
  };

  const handleCopyTranscript = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const handleExportData = () => {
    const data = {
      memories: getMemoryBank(),
      chatLogs: getChatHistory(),
      exportedAt: new Date().toISOString()
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `zoya_ai_chat_memory_${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const filteredMemories = memories.filter(m => 
    m.text.toLowerCase().includes(searchQuery.toLowerCase()) ||
    m.category.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const filteredChats = chatLogs.filter(c => 
    c.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
    c.summary.toLowerCase().includes(searchQuery.toLowerCase()) ||
    c.transcript.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          key="memory-modal-backdrop"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-3 sm:p-5"
        >
          <motion.div
            initial={{ scale: 0.95, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          exit={{ scale: 0.95, opacity: 0 }}
          className="bg-zinc-900 border border-purple-500/30 rounded-3xl w-full max-w-2xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden relative"
        >
          {/* Header */}
          <div className="p-5 border-b border-white/10 flex items-center justify-between bg-zinc-950/60">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-2xl bg-gradient-to-br from-pink-500/20 to-purple-500/20 border border-pink-500/30 text-pink-400">
                <Brain className="w-6 h-6" />
              </div>
              <div>
                <h2 className="font-bold text-lg text-white flex items-center gap-2">
                  Zoya's Persistent Memory & Chat History
                  <span className="text-[10px] px-2 py-0.5 rounded-full bg-pink-500/20 text-pink-300 border border-pink-500/30">
                    Saved
                  </span>
                </h2>
                <p className="text-xs text-zinc-400">Zoya automatically remembers past conversation facts & history</p>
              </div>
            </div>

            <button
              onClick={onClose}
              className="p-2 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-400 hover:text-white transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Tab Switcher & Search Bar */}
          <div className="p-4 border-b border-white/10 bg-zinc-900/80 space-y-3">
            <div className="flex items-center justify-between gap-2">
              <div className="flex rounded-xl bg-zinc-950 p-1 border border-white/10 w-full sm:w-auto">
                <button
                  onClick={() => setActiveTab("memories")}
                  className={`flex-1 sm:flex-initial flex items-center justify-center gap-2 px-4 py-2 rounded-lg text-xs font-bold transition-all ${
                    activeTab === "memories"
                      ? "bg-gradient-to-r from-pink-500 to-purple-600 text-white shadow-md"
                      : "text-zinc-400 hover:text-white"
                  }`}
                >
                  <Brain className="w-3.5 h-3.5" />
                  <span>Memory Bank ({memories.length})</span>
                </button>

                <button
                  onClick={() => setActiveTab("chats")}
                  className={`flex-1 sm:flex-initial flex items-center justify-center gap-2 px-4 py-2 rounded-lg text-xs font-bold transition-all ${
                    activeTab === "chats"
                      ? "bg-gradient-to-r from-pink-500 to-purple-600 text-white shadow-md"
                      : "text-zinc-400 hover:text-white"
                  }`}
                >
                  <History className="w-3.5 h-3.5" />
                  <span>Saved Chats ({chatLogs.length})</span>
                </button>
              </div>

              <div className="hidden sm:flex items-center gap-2">
                <button
                  onClick={handleExportData}
                  className="p-2 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-300 text-xs font-medium flex items-center gap-1.5 border border-white/10 transition-colors"
                  title="Export memory backup"
                >
                  <Download className="w-3.5 h-3.5 text-cyan-400" />
                  <span>Export</span>
                </button>

                <button
                  onClick={handleClearAll}
                  className="p-2 rounded-xl bg-red-500/10 hover:bg-red-500/20 text-red-400 text-xs font-medium flex items-center gap-1.5 border border-red-500/20 transition-colors"
                  title="Clear all saved memory"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>Clear</span>
                </button>
              </div>
            </div>

            {/* Search Input */}
            <div className="relative">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-zinc-500" />
              <input
                type="text"
                placeholder={activeTab === "memories" ? "Search facts, preferences, user info..." : "Search past conversation logs, topics, or transcripts..."}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full bg-zinc-950 border border-white/10 rounded-xl pl-9 pr-4 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-purple-500 transition-colors"
              />
            </div>
          </div>

          {/* Content Body */}
          <div className="p-4 sm:p-5 overflow-y-auto flex-1 space-y-4">
            {/* TAB 1: MEMORIES */}
            {activeTab === "memories" && (
              <div className="space-y-4">
                {/* Add memory button / form */}
                {!showAddForm ? (
                  <button
                    onClick={() => setShowAddForm(true)}
                    className="w-full py-2.5 px-4 rounded-xl border border-dashed border-pink-500/40 bg-pink-500/5 hover:bg-pink-500/10 text-pink-300 font-semibold text-xs transition-colors flex items-center justify-center gap-2"
                  >
                    <Plus className="w-4 h-4" />
                    <span>Teach Zoya a New Fact / Memory manually</span>
                  </button>
                ) : (
                  <form onSubmit={handleAddMemory} className="p-4 rounded-2xl bg-zinc-950 border border-pink-500/30 space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-bold text-pink-300 flex items-center gap-1.5">
                        <Sparkles className="w-3.5 h-3.5 text-pink-400" /> Add Custom Fact / User Note
                      </span>
                      <button 
                        type="button" 
                        onClick={() => setShowAddForm(false)}
                        className="text-zinc-500 hover:text-white"
                      >
                        <X className="w-4 h-4" />
                      </button>
                    </div>

                    <textarea
                      placeholder="e.g. 'User prefers to be called Alex', 'User loves coffee and lives in Mumbai', 'User is studying Python'"
                      value={newMemoryText}
                      onChange={(e) => setNewMemoryText(e.target.value)}
                      rows={2}
                      className="w-full bg-zinc-900 border border-white/10 rounded-xl p-3 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-pink-500"
                    />

                    <div className="flex items-center justify-between gap-3">
                      <select
                        value={newMemoryCategory}
                        onChange={(e) => setNewMemoryCategory(e.target.value as any)}
                        className="bg-zinc-900 border border-white/10 rounded-xl px-3 py-1.5 text-xs text-zinc-300 focus:outline-none"
                      >
                        <option value="fact">Personal Fact</option>
                        <option value="preference">Preference</option>
                        <option value="user_note">User Note</option>
                      </select>

                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setShowAddForm(false)}
                          className="px-3 py-1.5 rounded-xl bg-zinc-800 text-zinc-300 text-xs"
                        >
                          Cancel
                        </button>
                        <button
                          type="submit"
                          className="px-4 py-1.5 rounded-xl bg-pink-500 text-white text-xs font-bold hover:bg-pink-600 transition-colors"
                        >
                          Save Memory
                        </button>
                      </div>
                    </div>
                  </form>
                )}

                {/* Memory items list */}
                {filteredMemories.length === 0 ? (
                  <div className="text-center py-10 space-y-2">
                    <Brain className="w-10 h-10 text-zinc-600 mx-auto opacity-50" />
                    <p className="text-sm font-semibold text-zinc-400">No saved memories found</p>
                    <p className="text-xs text-zinc-500 max-w-xs mx-auto">
                      Zoya will automatically save key facts from your voice chats here, or you can add custom facts manually above!
                    </p>
                  </div>
                ) : (
                  <div className="grid gap-3">
                    {filteredMemories.map((m) => (
                      <div
                        key={m.id}
                        className="p-3.5 rounded-2xl bg-zinc-950 border border-white/10 hover:border-purple-500/30 transition-all flex items-start justify-between gap-3 group"
                      >
                        <div className="space-y-1.5 flex-1">
                          <div className="flex items-center gap-2">
                            <span className={`text-[10px] uppercase font-bold px-2 py-0.5 rounded-full ${
                              m.category === "preference" 
                                ? "bg-pink-500/20 text-pink-300 border border-pink-500/30"
                                : m.category === "fact"
                                ? "bg-purple-500/20 text-purple-300 border border-purple-500/30"
                                : "bg-cyan-500/20 text-cyan-300 border border-cyan-500/30"
                            }`}>
                              {m.category}
                            </span>
                            <span className="text-[10px] text-zinc-500 flex items-center gap-1">
                              <Clock className="w-2.5 h-2.5" /> {m.date}
                            </span>
                          </div>
                          <p className="text-xs text-zinc-200 leading-relaxed font-medium">{m.text}</p>
                        </div>

                        <button
                          onClick={() => handleDeleteMemory(m.id)}
                          className="p-1.5 rounded-lg text-zinc-600 hover:text-red-400 hover:bg-red-500/10 transition-colors opacity-80 group-hover:opacity-100"
                          title="Delete memory"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* TAB 2: CHAT HISTORY LOGS */}
            {activeTab === "chats" && (
              <div className="space-y-4">
                {filteredChats.length === 0 ? (
                  <div className="text-center py-10 space-y-2">
                    <History className="w-10 h-10 text-zinc-600 mx-auto opacity-50" />
                    <p className="text-sm font-semibold text-zinc-400">No past conversation logs</p>
                    <p className="text-xs text-zinc-500 max-w-xs mx-auto">
                      When you talk to Zoya using voice or screen vision, session transcripts & summaries will be saved here automatically!
                    </p>
                  </div>
                ) : (
                  <div className="grid gap-3">
                    {filteredChats.map((c) => {
                      const isExpanded = expandedChatId === c.id;
                      return (
                        <div
                          key={c.id}
                          className="rounded-2xl bg-zinc-950 border border-white/10 overflow-hidden transition-all"
                        >
                          <div 
                            onClick={() => setExpandedChatId(isExpanded ? null : c.id)}
                            className="p-4 cursor-pointer hover:bg-white/[0.02] flex items-center justify-between gap-3"
                          >
                            <div className="space-y-1 flex-1">
                              <div className="flex items-center gap-2">
                                <h4 className="font-bold text-xs text-white flex items-center gap-2">
                                  <MessageSquare className="w-3.5 h-3.5 text-pink-400" />
                                  {c.title}
                                </h4>
                                <span className="text-[10px] text-zinc-500 font-mono">
                                  {c.timestamp}
                                </span>
                              </div>
                              <p className="text-xs text-zinc-400 line-clamp-2">{c.summary}</p>
                            </div>

                            <div className="flex items-center gap-2">
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleDeleteChat(c.id);
                                }}
                                className="p-1.5 rounded-lg text-zinc-600 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                                title="Delete session log"
                              >
                                <Trash2 className="w-4 h-4" />
                              </button>

                              {isExpanded ? (
                                <ChevronUp className="w-4 h-4 text-zinc-400" />
                              ) : (
                                <ChevronDown className="w-4 h-4 text-zinc-400" />
                              )}
                            </div>
                          </div>

                          {/* Expanded Transcript Details */}
                          {isExpanded && (
                            <div className="p-4 border-t border-white/10 bg-black/40 space-y-3">
                              <div className="flex items-center justify-between">
                                <span className="text-[11px] font-bold text-pink-300 uppercase tracking-wider flex items-center gap-1.5">
                                  <FileText className="w-3.5 h-3.5" /> Full Conversation Transcript
                                </span>

                                <button
                                  onClick={() => handleCopyTranscript(c.transcript, c.id)}
                                  className="px-2.5 py-1 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-[10px] font-semibold flex items-center gap-1 transition-colors"
                                >
                                  {copiedId === c.id ? (
                                    <>
                                      <Check className="w-3 h-3 text-emerald-400" /> Copied!
                                    </>
                                  ) : (
                                    <>
                                      <Copy className="w-3 h-3 text-cyan-400" /> Copy Transcript
                                    </>
                                  )}
                                </button>
                              </div>

                              <div className="p-3 rounded-xl bg-zinc-900 border border-white/5 font-mono text-[11px] text-zinc-300 whitespace-pre-wrap max-h-60 overflow-y-auto leading-relaxed">
                                {c.transcript}
                              </div>

                              {c.topics && c.topics.length > 0 && (
                                <div className="flex flex-wrap items-center gap-1.5 pt-1">
                                  <span className="text-[10px] text-zinc-500">Topics:</span>
                                  {c.topics.map((t, idx) => (
                                    <span key={idx} className="text-[10px] px-2 py-0.5 rounded-full bg-zinc-800 text-zinc-300 border border-white/5">
                                      #{t}
                                    </span>
                                  ))}
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Footer controls */}
          <div className="p-4 border-t border-white/10 bg-zinc-950/80 flex items-center justify-between gap-3">
            <p className="text-[11px] text-zinc-400 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-pink-400" />
              <span>Zoya reads these memories on every new connect!</span>
            </p>

            <button
              onClick={onClose}
              className="px-5 py-2 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-white text-xs font-semibold transition-colors"
            >
              Done
            </button>
          </div>
        </motion.div>
      </motion.div>
    )}
    </AnimatePresence>
  );
}
