/**
 * Shared type definitions for the Zoya native bridge.
 * Mirrors the structured result contract implemented on the Android side.
 */

export type ResultStatus =
  | 'SUCCESS'
  | 'FAILURE'
  | 'PERMISSION_DENIED'
  | 'TIMEOUT'
  | 'UNSUPPORTED'
  | 'CANCELLED'
  | 'BLOCKED';

export interface BridgeError {
  code: string;
  message: string;
}

export interface BridgeMeta {
  durationMs: number;
  attempt: number;
  recovered: boolean;
}

export interface BridgeResult<T = Record<string, any>> {
  status: ResultStatus;
  ok: boolean;
  data?: T;
  error?: BridgeError;
  meta: BridgeMeta;
}

export interface Bounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
  width: number;
  height: number;
  centerX: number;
  centerY: number;
}

export interface NodeInfo {
  nodeId: string;
  text: string | null;
  contentDescription: string | null;
  resourceId: string | null;
  className: string | null;
  packageName: string | null;
  clickable: boolean;
  enabled: boolean;
  selected: boolean;
  focused: boolean;
  editable: boolean;
  scrollable: boolean;
  longClickable: boolean;
  checkable: boolean;
  checked: boolean;
  isPassword: boolean;
  isRoot: boolean;
  visibleToUser: boolean;
  bounds: Bounds | null;
  children?: NodeInfo[];
}

export interface Selector {
  exactText?: string;
  partialText?: string;
  regexText?: string;
  contentDescription?: string;
  contentDescriptionPartial?: string;
  resourceId?: string;
  className?: string;
  packageName?: string;
  clickable?: boolean;
  enabled?: boolean;
  editable?: boolean;
  scrollable?: boolean;
  checked?: boolean;
  index?: number;
}

export type GestureType =
  | 'tap'
  | 'click'
  | 'doubleTap'
  | 'longPress'
  | 'swipe'
  | 'scroll'
  | 'fling'
  | 'drag'
  | 'path'
  | 'custom'
  | 'pinch'
  | 'pinchIn'
  | 'pinchOut'
  | 'zoom';

export interface GestureSpec {
  type: GestureType;
  fromX?: number;
  fromY?: number;
  toX?: number;
  toY?: number;
  x?: number;
  y?: number;
  durationMs?: number;
  delayMs?: number;
  points?: Array<[number, number]>;
  repeatCount?: number;
  normalized?: boolean;
  scaleFactor?: number;
}

export interface RecordedGesture {
  id: string;
  name: string;
  type: string;
  points: number[][];
  timestamps: number[];
  durationMs: number;
  screenWidth: number;
  screenHeight: number;
  orientationDegrees: number;
  uiContext?: Record<string, any> | null;
  createdAt: string;
}

export interface AppInfo {
  packageName: string;
  name: string;
  activityName: string;
  category: string;
  versionName?: string;
  isSystem: boolean;
  icon?: number;
}

export interface OcrLine {
  text: string;
  confidence: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  centerX: number;
  centerY: number;
}

export interface OcrResult {
  text: string;
  lines: OcrLine[];
  lineCount: number;
  engine: string;
}

export interface VisualElement {
  type: string;
  text: string | null;
  confidence: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  centerX: number;
  centerY: number;
  leftNorm?: number;
  topNorm?: number;
  rightNorm?: number;
  bottomNorm?: number;
}

export interface FormField {
  kind: 'text' | 'email' | 'password' | 'phone' | 'username' | 'otp';
  hint: string;
  isPassword: boolean;
  focused: boolean;
  enabled: boolean;
  bounds: Bounds;
}

export interface FormControl {
  type: string;
  text: string;
  enabled: boolean;
  bounds: Bounds;
}

export type TaskScheduleType = 'ONCE' | 'INTERVAL' | 'DAILY' | 'WEEKLY' | 'EVENT';

export interface Task {
  id: string;
  name: string;
  workflow: Record<string, any>;
  scheduleType: TaskScheduleType;
  trigger: Record<string, any>;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
  nextRunAt?: number;
  lastRunAt?: number;
  lastRunStatus?: string;
  runCount: number;
}

export interface RunRecord {
  id: string;
  taskId: string;
  taskName: string;
  startedAt: number;
  durationMs: number;
  success: boolean;
  status: string;
  errorMessage?: string;
  stepSummary?: string;
}

export interface WorkflowStepRecord {
  index: number;
  type: string;
  command?: string;
  success: boolean;
  durationMs: number;
  errorMessage?: string;
}

export interface WorkflowRunResult {
  workflowId: string;
  success: boolean;
  status: ResultStatus;
  errorMessage?: string;
  stepCount: number;
  durationMs: number;
  stepHistory: WorkflowStepRecord[];
  variables: Record<string, string>;
}

export interface ScreenContext {
  packageName: string | null;
  className: string | null;
  lastAction: string | null;
  expectedResult: string | null;
  lastOcrText: string | null;
  workflowState: string | null;
  screenWidth: number;
  screenHeight: number;
  orientationDegrees: number;
  accessibilityEnabled: boolean;
  serviceAvailable: boolean;
}

export interface PermissionStates {
  camera: 'granted' | 'denied' | 'prompt';
  microphone: 'granted' | 'denied' | 'prompt';
  accessibility: 'granted' | 'denied';
  overlay: 'granted' | 'denied';
  write_settings: 'granted' | 'denied';
}
