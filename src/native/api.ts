import {
  execute,
  cancelOperation,
  requestPermission,
  getPermissionStates,
  captureScreenForOcr,
  stopScreenCapture,
  isNative,
} from './bridge';
import type {
  BridgeResult,
  Selector,
  GestureSpec,
  RecordedGesture,
  AppInfo,
  OcrResult,
  VisualElement,
  FormField,
  FormControl,
  Task,
  RunRecord,
  WorkflowRunResult,
  PermissionStates,
  ScreenContext,
} from './types';

/**
 * High-level, fully-typed API surface for the native Android automation
 * engine. Every method returns a structured BridgeResult so callers can
 * distinguish success / failure / permission / timeout / unsupported /
 * cancelled / blocked states.
 */

export const NativeApi = {
  isNative,

  // ------------------------------------------------------------------
  // Element discovery & interaction (accessibility-first)
  // ------------------------------------------------------------------

  async findElement(selector: Selector): Promise<BridgeResult<{ element: any }>> {
    return execute('findElement', selector);
  },

  async findUIElement(selector: Selector): Promise<BridgeResult<{ element: any }>> {
    return execute('findElement', selector);
  },

  async findElements(selector: Selector, limit = 50): Promise<BridgeResult<{ elements: any[]; count: number }>> {
    return execute('findElements', { ...selector, limit });
  },

  async isElementPresent(selector: Selector): Promise<BridgeResult<{ present: boolean }>> {
    return execute('isElementPresent', selector);
  },

  async waitForElement(selector: Selector, timeoutMs = 10000): Promise<BridgeResult<{ found: boolean }>> {
    return execute('waitForElement', { ...selector, timeoutMs });
  },

  async clickElement(selector: Selector): Promise<BridgeResult> {
    return execute('clickElement', selector);
  },

  async longClickElement(selector: Selector): Promise<BridgeResult> {
    return execute('longClickElement', selector);
  },

  async focusElement(selector: Selector): Promise<BridgeResult> {
    return execute('focusElement', selector);
  },

  async clearElement(selector: Selector): Promise<BridgeResult> {
    return execute('clearElement', selector);
  },

  async toggleElement(selector: Selector): Promise<BridgeResult> {
    return execute('toggleElement', selector);
  },

  async typeText(text: string, selector?: Selector): Promise<BridgeResult<{ length: number; echoed: boolean }>> {
    return execute('typeText', { text, ...(selector ?? {}) });
  },

  // ------------------------------------------------------------------
  // Gestures
  // ------------------------------------------------------------------

  async performGesture(spec: GestureSpec): Promise<BridgeResult> {
    const map: Record<string, any> = { ...spec } as any;
    const type = spec.type;
    const command =
      type === 'tap' || type === 'click' || type === 'doubleTap' || type === 'longPress'
        ? type === 'tap' || type === 'click'
          ? 'tapCoordinate'
          : type === 'doubleTap'
            ? 'doubleTap'
            : 'longPressCoordinate'
        : type === 'swipe' || type === 'scroll' || type === 'fling' || type === 'drag'
          ? 'swipe'
          : type === 'path' || type === 'custom'
            ? 'gesturePath'
            : type === 'pinch' || type === 'zoom'
              ? 'pinch'
              : 'swipe';
    return execute(command, map);
  },

  async tap(x: number, y: number, normalized = true): Promise<BridgeResult> {
    return execute('tapCoordinate', { x, y, normalized });
  },

  async longPress(x: number, y: number, normalized = true): Promise<BridgeResult> {
    return execute('longPressCoordinate', { x, y, normalized });
  },

  async swipe(fromX: number, fromY: number, toX: number, toY: number, durationMs = 400): Promise<BridgeResult> {
    return execute('swipe', { type: 'swipe', fromX, fromY, toX, toY, durationMs });
  },

  async scroll(direction: 'up' | 'down'): Promise<BridgeResult> {
    return execute('swipe', { type: 'scroll', direction });
  },

  async gesturePath(points: Array<[number, number]>, durationMs = 800): Promise<BridgeResult> {
    return execute('gesturePath', { points, durationMs });
  },

  async gesture(points: Array<[number, number]>, durationMs = 800): Promise<BridgeResult> {
    return execute('gesturePath', { points, durationMs });
  },

  async pinch(x: number, y: number, scaleFactor = 0.3, kind: 'in' | 'out' = 'in'): Promise<BridgeResult> {
    return execute('pinch', { type: kind === 'out' ? 'pinchOut' : 'pinch', x, y, scaleFactor });
  },

  // ------------------------------------------------------------------
  // Gesture recorder
  // ------------------------------------------------------------------

  async recordGesture(durationMs = 10000): Promise<BridgeResult<{ recording: boolean; durationMs: number }>> {
    return execute('recordGesture', { durationMs });
  },

  async stopGestureRecording(): Promise<BridgeResult<{ gesture: RecordedGesture }>> {
    return execute('stopGestureRecording');
  },

  async listGestures(): Promise<BridgeResult<{ gestures: RecordedGesture[]; count: number }>> {
    return execute('listGestures');
  },

  async getGesture(id: string): Promise<BridgeResult<{ gesture: RecordedGesture }>> {
    return execute('getGesture', { id });
  },

  async saveGesture(gestureJson: string): Promise<BridgeResult<{ gesture: RecordedGesture }>> {
    return execute('saveGesture', { gesture: gestureJson });
  },

  async renameGesture(id: string, name: string): Promise<BridgeResult<{ renamed: boolean }>> {
    return execute('renameGesture', { id, name });
  },

  async duplicateGesture(id: string, name?: string): Promise<BridgeResult<{ gesture: RecordedGesture }>> {
    return execute('duplicateGesture', { id, name });
  },

  async deleteGesture(id: string): Promise<BridgeResult<{ deleted: boolean }>> {
    return execute('deleteGesture', { id });
  },

  async importGestures(json: string): Promise<BridgeResult<{ imported: number }>> {
    return execute('importGesture', { json });
  },

  async exportGestures(ids: string[]): Promise<BridgeResult<{ json: string; count: number }>> {
    return execute('exportGesture', { ids });
  },

  async replayGesture(id: string): Promise<BridgeResult<{ gestureId: string; replayed: boolean }>> {
    return execute('replayGesture', { id });
  },

  // ------------------------------------------------------------------
  // Apps
  // ------------------------------------------------------------------

  async launchApp(packageName: string, appName?: string): Promise<BridgeResult> {
    return execute('launchApp', { packageName, appName });
  },

  async launchAppByName(name: string, consent = false): Promise<BridgeResult<{ packageName: string; label: string }>> {
    return execute('launchAppByName', { name }, consent);
  },

  async listApps(query?: string): Promise<BridgeResult<{ apps: AppInfo[]; count: number }>> {
    return execute('listApps', { query });
  },

  async getInstalledApps(query?: string): Promise<BridgeResult<{ apps: AppInfo[]; count: number }>> {
    return execute('listApps', { query });
  },

  async currentApp(): Promise<BridgeResult<{ packageName: string | null }>> {
    return execute('currentApp');
  },

  async getCurrentApp(): Promise<BridgeResult<{ packageName: string | null }>> {
    return execute('currentApp');
  },

  async pressBack(): Promise<BridgeResult> {
    return execute('pressBack');
  },

  async pressHome(): Promise<BridgeResult> {
    return execute('pressHome');
  },

  async startAutomation(): Promise<BridgeResult<{ started: boolean }>> {
    return execute('startAutomation');
  },

  async stopAutomation(): Promise<BridgeResult<{ stopped: boolean }>> {
    return execute('stopAutomation');
  },

  async openAppInfo(packageName: string): Promise<BridgeResult> {
    return execute('openAppInfo', { packageName });
  },

  async openAppPermissions(packageName: string): Promise<BridgeResult> {
    return execute('openAppPermissions', { packageName });
  },

  async openNotificationSettings(packageName: string): Promise<BridgeResult> {
    return execute('openNotificationSettings', { packageName });
  },

  async openBatterySettings(packageName: string): Promise<BridgeResult> {
    return execute('openBatterySettings', { packageName });
  },

  async stopApp(packageName: string): Promise<BridgeResult> {
    return execute('stopApp', { packageName });
  },

  // ------------------------------------------------------------------
  // Browser
  // ------------------------------------------------------------------

  async openUrl(url: string): Promise<BridgeResult> {
    return execute('openUrl', { url });
  },

  async searchBrowser(query: string): Promise<BridgeResult> {
    return execute('searchBrowser', { query });
  },

  async searchInBrowser(query: string): Promise<BridgeResult> {
    return execute('searchInBrowser', { query });
  },

  async readVisibleText(): Promise<BridgeResult<{ text: string; visibleLines: number }>> {
    return execute('readVisibleText');
  },

  async clickLink(partialText: string): Promise<BridgeResult> {
    return execute('clickLink', { partialText });
  },

  async browserScroll(direction: 'up' | 'down'): Promise<BridgeResult> {
    return execute('browserScroll', { direction });
  },

  async verifyNavigation(url: string): Promise<BridgeResult<{ verified: boolean }>> {
    return execute('verifyNavigation', { url });
  },

  // ------------------------------------------------------------------
  // Forms
  // ------------------------------------------------------------------

  async detectForm(): Promise<BridgeResult<{ fields: FormField[]; controls: FormControl[]; fieldCount: number; submitFound: boolean }>> {
    return execute('detectForm');
  },

  async fillForm(fields: Record<string, string>): Promise<BridgeResult> {
    return execute('fillForm', { fields });
  },

  async submitForm(confirmed = false): Promise<BridgeResult> {
    return execute('submitForm', { confirmed });
  },

  // ------------------------------------------------------------------
  // Settings
  // ------------------------------------------------------------------

  async openSettingsPage(page: string): Promise<BridgeResult> {
    return execute('openSettingsPage', { page });
  },

  async openSettings(page: string): Promise<BridgeResult> {
    return execute('openSettingsPage', { page });
  },

  async setBrightness(value: number): Promise<BridgeResult<{ brightness: number }>> {
    return execute('setBrightness', { value });
  },

  async getBrightness(): Promise<BridgeResult<{ brightness: number }>> {
    return execute('getBrightness');
  },

  async setVolume(level: number): Promise<BridgeResult<{ volume: number }>> {
    return execute('setVolume', { level });
  },

  async getVolume(): Promise<BridgeResult<{ volume: number }>> {
    return execute('getVolume');
  },

  // ------------------------------------------------------------------
  // Camera & microphone
  // ------------------------------------------------------------------

  async takePhoto(camera: 'front' | 'back' = 'back', fileName?: string): Promise<BridgeResult> {
    return execute('takePhoto', { camera, fileName });
  },

  async cameraPermissionStatus(): Promise<BridgeResult> {
    return execute('cameraPermissionStatus');
  },

  async startRecording(fileName?: string): Promise<BridgeResult> {
    return execute('startRecording', { fileName });
  },

  async stopRecording(): Promise<BridgeResult> {
    return execute('stopRecording');
  },

  async microphoneStatus(): Promise<BridgeResult> {
    return execute('micPermissionStatus');
  },

  // ------------------------------------------------------------------
  // Vision / OCR
  // ------------------------------------------------------------------

  async readScreenText(): Promise<BridgeResult<{ text: string; source: string }>> {
    return execute('readScreenText');
  },

  async readScreen(): Promise<BridgeResult<{ text: string; source: string }>> {
    return execute('readScreenText');
  },

  async performOCR(): Promise<BridgeResult<OcrResult>> {
    return execute('performOCR');
  },

  async visualDetect(): Promise<BridgeResult<{ elements: VisualElement[]; elementCount: number; fallback: boolean }>> {
    return execute('visualDetect');
  },

  async captureScreenForOcr(): Promise<BridgeResult<OcrResult>> {
    return captureScreenForOcr<OcrResult>();
  },

  async captureScreen(): Promise<BridgeResult<OcrResult>> {
    return captureScreenForOcr<OcrResult>();
  },

  async stopScreenCapture(): Promise<void> {
    return stopScreenCapture();
  },

  async screenCaptureStatus(): Promise<BridgeResult<{ capturing: boolean }>> {
    return execute('screenCaptureStatus');
  },

  // ------------------------------------------------------------------
  // System status, logs & security
  // ------------------------------------------------------------------

  async getAutomationStatus(): Promise<BridgeResult> {
    return execute('getAutomationStatus');
  },

  async getDeviceCapabilities(): Promise<BridgeResult> {
    return execute('getDeviceCapabilities');
  },

  async getExecutionLogs(limit = 200): Promise<BridgeResult<{ logs: any[]; count: number }>> {
    return execute('getExecutionLogs', { limit });
  },

  async exportLogs(): Promise<BridgeResult<{ path: string; size: number; entries: number }>> {
    return execute('exportLogs');
  },

  async clearExecutionLogs(): Promise<BridgeResult<{ cleared: boolean }>> {
    return execute('clearExecutionLogs');
  },

  async getPermissionStatus(): Promise<BridgeResult> {
    return execute('getPermissionStatus');
  },

  async getSecurityStatus(): Promise<BridgeResult> {
    return execute('getSecurityStatus');
  },

  async biometricStatus(): Promise<BridgeResult<{ available: boolean }>> {
    return execute('biometricStatus');
  },

  async getActiveWorkflow(): Promise<BridgeResult<{ activeWorkflow: any }>> {
    return execute('getActiveWorkflow');
  },

  // ------------------------------------------------------------------
  // State & accessibility
  // ------------------------------------------------------------------

  async getScreenContext(): Promise<BridgeResult<ScreenContext>> {
    return execute('getScreenContext');
  },

  async accessibilityStatus(): Promise<BridgeResult> {
    return execute('accessibilityStatus');
  },

  async openAccessibilitySettings(): Promise<BridgeResult> {
    return execute('openAccessibilitySettings');
  },

  // ------------------------------------------------------------------
  // Tasks & workflows
  // ------------------------------------------------------------------

  async createTask(params: {
    name: string;
    workflow: string;
    scheduleType: string;
    trigger?: Record<string, any>;
    enabled?: boolean;
  }): Promise<BridgeResult<{ task: Task }>> {
    return execute('createTask', params);
  },

  async scheduleTask(params: {
    name: string;
    workflow: string;
    scheduleType: string;
    trigger?: Record<string, any>;
    enabled?: boolean;
  }): Promise<BridgeResult<{ task: Task }>> {
    return execute('scheduleTask', params);
  },

  async listTasks(): Promise<BridgeResult<{ tasks: Task[]; count: number }>> {
    return execute('listTasks');
  },

  async updateTask(params: { id: string } & Record<string, any>): Promise<BridgeResult<{ task: Task }>> {
    return execute('updateTask', params);
  },

  async deleteTask(id: string): Promise<BridgeResult<{ deleted: boolean }>> {
    return execute('deleteTask', { id });
  },

  async setTaskEnabled(id: string, enabled: boolean): Promise<BridgeResult<{ task: Task }>> {
    return execute(enabled ? 'enableTask' : 'disableTask', { id });
  },

  async taskHistory(taskId?: string): Promise<BridgeResult<{ history: RunRecord[]; count: number }>> {
    return execute('taskHistory', { taskId });
  },

  async executeTask(id: string): Promise<BridgeResult> {
    return execute('executeTask', { id });
  },

  async runWorkflow(workflow: string): Promise<BridgeResult<WorkflowRunResult>> {
    return execute('runWorkflow', { workflow });
  },

  // ------------------------------------------------------------------
  // Versioned workflow store (offline)
  // ------------------------------------------------------------------

  async saveWorkflow(workflow: string): Promise<BridgeResult<{ version: number; workflowId: string }>> {
    return execute('saveWorkflow', { workflow });
  },

  async listWorkflows(): Promise<BridgeResult<{ workflows: any[]; count: number }>> {
    return execute('listWorkflows');
  },

  async getWorkflow(id: string): Promise<BridgeResult<{ workflow: string }>> {
    return execute('getWorkflow', { id });
  },

  async workflowVersions(id: string): Promise<BridgeResult<{ versions: any[] }>> {
    return execute('workflowVersions', { id });
  },

  async restoreWorkflowVersion(id: string, version: number): Promise<BridgeResult<{ restored: boolean; version: number }>> {
    return execute('restoreWorkflowVersion', { id, version });
  },

  async deleteWorkflow(id: string): Promise<BridgeResult<{ deleted: boolean }>> {
    return execute('deleteWorkflow', { id });
  },

  // ------------------------------------------------------------------
  // Optional cloud sync
  // ------------------------------------------------------------------

  async getSyncStatus(): Promise<BridgeResult<{ enabled: boolean; endpoint: string; lastSyncAt: number }>> {
    return execute('getSyncStatus');
  },

  async setSyncEnabled(enabled: boolean): Promise<BridgeResult> {
    return execute('setSyncEnabled', { enabled });
  },

  async setSyncEndpoint(endpoint: string): Promise<BridgeResult> {
    return execute('setSyncEndpoint', { endpoint });
  },

  async syncNow(): Promise<BridgeResult> {
    return execute('syncNow');
  },

  // ------------------------------------------------------------------
  // Permissions
  // ------------------------------------------------------------------

  async requestPermission(permission: 'camera' | 'microphone' | 'accessibility' | 'overlay' | 'write_settings'): Promise<boolean> {
    return requestPermission(permission);
  },

  async permissionStates(): Promise<PermissionStates> {
    const states = await getPermissionStates();
    return states as unknown as PermissionStates;
  },

  async cancel(): Promise<void> {
    return cancelOperation();
  },
};

export { execute, onLogEvent, biometricAuth, subscribeToEvents, unsubscribeFromEvents } from './bridge';
export * from './types';
