export interface MemoryItem {
  id: string;
  date: string;
  text: string;
  category: "fact" | "preference" | "chat_summary" | "user_note";
}

export interface ChatSessionLog {
  id: string;
  timestamp: string;
  durationSeconds: number;
  title: string;
  summary: string;
  transcript: string;
  topics?: string[];
}

const MEMORY_BANK_KEY = "zoya_memory_bank";
const CHAT_HISTORY_KEY = "zoya_chat_history";

export function getMemoryBank(): MemoryItem[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = localStorage.getItem(MEMORY_BANK_KEY);
    if (!raw) {
      const initial = getInitialDefaultMemories();
      localStorage.setItem(MEMORY_BANK_KEY, JSON.stringify(initial));
      return initial;
    }
    return JSON.parse(raw);
  } catch (e) {
    return getInitialDefaultMemories();
  }
}

export function saveMemoryBank(memories: MemoryItem[]): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(MEMORY_BANK_KEY, JSON.stringify(memories));
}

export function addMemoryItem(text: string, category: MemoryItem["category"] = "fact"): MemoryItem {
  const memories = getMemoryBank();
  const newItem: MemoryItem = {
    id: "mem_" + Date.now() + "_" + Math.random().toString(36).substring(2, 6),
    date: new Date().toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }),
    text,
    category
  };
  memories.unshift(newItem);
  saveMemoryBank(memories);
  return newItem;
}

export function deleteMemoryItem(id: string): void {
  const memories = getMemoryBank().filter(m => m.id !== id);
  saveMemoryBank(memories);
}

export function getChatHistory(): ChatSessionLog[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_KEY);
    if (!raw) {
      const initial = getInitialDefaultChats();
      localStorage.setItem(CHAT_HISTORY_KEY, JSON.stringify(initial));
      return initial;
    }
    return JSON.parse(raw);
  } catch (e) {
    return getInitialDefaultChats();
  }
}

export function saveChatHistory(chats: ChatSessionLog[]): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(CHAT_HISTORY_KEY, JSON.stringify(chats));
}

export function addChatSession(session: Omit<ChatSessionLog, "id">): ChatSessionLog {
  const chats = getChatHistory();
  const newLog: ChatSessionLog = {
    ...session,
    id: "chat_" + Date.now()
  };
  chats.unshift(newLog);
  saveChatHistory(chats);
  return newLog;
}

export function deleteChatSession(id: string): void {
  const chats = getChatHistory().filter(c => c.id !== id);
  saveChatHistory(chats);
}

export function clearAllMemory(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(MEMORY_BANK_KEY);
  localStorage.removeItem(CHAT_HISTORY_KEY);
}

function getInitialDefaultMemories(): MemoryItem[] {
  return [
    {
      id: "mem_init_1",
      date: new Date().toLocaleDateString("en-IN", { day: "numeric", month: "short" }),
      text: "User loves quick, witty responses and wants Zoya to remember previous conversations.",
      category: "preference"
    },
    {
      id: "mem_init_2",
      date: new Date().toLocaleDateString("en-IN", { day: "numeric", month: "short" }),
      text: "Screen vision & Android app navigation controls are active.",
      category: "fact"
    }
  ];
}

function getInitialDefaultChats(): ChatSessionLog[] {
  return [
    {
      id: "chat_init_1",
      timestamp: new Date(Date.now() - 3600000 * 5).toLocaleDateString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }),
      durationSeconds: 95,
      title: "Voice Chat & Screen Vision Demo",
      summary: "First meeting with Zoya. Discussed Android screen vision, voice interactions, and app launcher controls.",
      transcript: "User: Hey Zoya, remember our conversation!\nZoya: Oh honey, I never forget a single detail. Tell me what you're working on today!",
      topics: ["Intro", "Screen Vision", "Setup"]
    }
  ];
}
