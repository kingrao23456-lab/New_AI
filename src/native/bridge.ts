import { Capacitor, registerPlugin } from '@capacitor/core';
import type { BridgeResult } from './types';

/**
 * The native bridge client. All calls go through a single plugin method
 * (`execute`) that returns a structured result. In non-native (web) contexts
 * every call fails gracefully with an UNSUPPORTED status — the web app never
 * breaks because the bridge is absent.
 */

interface ZoyaNativeBridge {
  execute(options: { command: string; args?: Record<string, any>; consent?: boolean }): Promise<BridgeResult>;
  cancelOperation(): Promise<{ cancelled: boolean }>;
  isNative(): Promise<{ native: boolean }>;
  requestPermission(options: { permission: string }): Promise<{ status: string }>;
  getPermissionStates(): Promise<Record<string, string>>;
  captureScreenForOcr(): Promise<BridgeResult>;
  stopScreenCapture(): Promise<{ stopped: boolean }>;
  subscribeToEvents(): Promise<{ subscribed: boolean }>;
  unsubscribeFromEvents(): Promise<{ unsubscribed: boolean }>;
  biometricAuth(): Promise<{ authenticated: boolean; reason?: string; code?: number }>;
  addListener(eventName: string, callback: (event: any) => void): Promise<{ remove: () => void }>;
}

const pluginProxy: ZoyaNativeBridge | null = Capacitor.isNativePlatform()
  ? (registerPlugin<ZoyaNativeBridge>('ZoyaNativeBridge') as ZoyaNativeBridge)
  : null;

function getPlugin(): ZoyaNativeBridge | null {
  return pluginProxy;
}

export const isNative = (): boolean => {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
};

/**
 * Executes a native automation command. Never throws for missing native
 * support — returns an UNSUPPORTED result instead.
 */
export async function execute<T = Record<string, any>>(
  command: string,
  args: Record<string, any> = {},
  consent: boolean = false,
): Promise<BridgeResult<T>> {
  const p = getPlugin();
  if (!p) {
    return {
      status: 'UNSUPPORTED',
      ok: false,
      error: {
        code: 'NATIVE_UNAVAILABLE',
        message: 'This feature is only available in the native Android app.',
      },
      meta: { durationMs: 0, attempt: 1, recovered: false },
    };
  }
  try {
    const result = await p.execute({ command, args, consent });
    return normalize(result);
  } catch (e: any) {
    // Capacitor rejects with the structured result payload in the error.
    if (e?.result && typeof e.result === 'object') {
      return normalize(e.result);
    }
    return {
      status: 'FAILURE',
      ok: false,
      error: { code: e?.code ?? 'BRIDGE_ERROR', message: e?.message ?? String(e) },
      meta: { durationMs: 0, attempt: 1, recovered: false },
    };
  }
}

function normalize<T>(result: any): BridgeResult<T> {
  return {
    status: result?.status ?? 'FAILURE',
    ok: result?.ok ?? result?.status === 'SUCCESS',
    data: result?.data,
    error: result?.error,
    meta: result?.meta ?? { durationMs: 0, attempt: 1, recovered: false },
  };
}

export async function cancelOperation(): Promise<void> {
  const p = getPlugin();
  if (!p) return;
  try {
    await p.cancelOperation();
  } catch {
    // best effort
  }
}

export async function requestPermission(permission: string): Promise<boolean> {
  const p = getPlugin();
  if (!p) return false;
  try {
    await p.requestPermission({ permission });
    const states = await p.getPermissionStates();
    return states[permission] === 'granted';
  } catch {
    return false;
  }
}

export async function getPermissionStates(): Promise<Record<string, string>> {
  const p = getPlugin();
  if (!p) {
    return { camera: 'denied', microphone: 'denied', accessibility: 'denied', overlay: 'denied', write_settings: 'denied' };
  }
  try {
    return await p.getPermissionStates();
  } catch {
    return {};
  }
}

export async function captureScreenForOcr<T = Record<string, any>>(): Promise<BridgeResult<T>> {
  const p = getPlugin();
  if (!p) {
    return unsupported();
  }
  try {
    const result = await p.captureScreenForOcr();
    return normalize(result);
  } catch (e: any) {
    if (e?.result) return normalize(e.result);
    return { status: 'FAILURE', ok: false, error: { code: 'CAPTURE_FAILED', message: String(e) }, meta: { durationMs: 0, attempt: 1, recovered: false } };
  }
}

export async function stopScreenCapture(): Promise<void> {
  const p = getPlugin();
  if (!p) return;
  try {
    await p.stopScreenCapture();
  } catch {
    // best effort
  }
}

export async function subscribeToEvents(): Promise<void> {
  const p = getPlugin();
  if (!p) return;
  try {
    await p.subscribeToEvents();
  } catch {
    // best effort
  }
}

export async function unsubscribeFromEvents(): Promise<void> {
  const p = getPlugin();
  if (!p) return;
  try {
    await p.unsubscribeFromEvents();
  } catch {
    // best effort
  }
}

export async function biometricAuth(): Promise<{ authenticated: boolean; reason?: string; code?: number }> {
  const p = getPlugin();
  if (!p) return { authenticated: false, reason: 'no_native' };
  try {
    const res = await p.biometricAuth();
    return res as { authenticated: boolean; reason?: string; code?: number };
  } catch {
    return { authenticated: false, reason: 'error' };
  }
}

/**
 * Registers a live listener for native automation log events.
 * Returns an unsubscribe function. On web this is a no-op.
 */
export function onLogEvent(callback: (entry: any) => void): () => void {
  const p = getPlugin();
  if (!p) {
    return () => {};
  }
  let removed = false;
  p.addListener('automationLog', (event: any) => {
    if (!removed) callback(event);
  }).catch(() => {});
  subscribeToEvents().catch(() => {});
  return () => {
    removed = true;
    unsubscribeFromEvents().catch(() => {});
  };
}

function unsupported<T = Record<string, any>>(): BridgeResult<T> {
  return {
    status: 'UNSUPPORTED',
    ok: false,
    error: { code: 'NATIVE_UNAVAILABLE', message: 'This feature is only available in the native Android app.' },
    meta: { durationMs: 0, attempt: 1, recovered: false },
  } as BridgeResult<T>;
}
