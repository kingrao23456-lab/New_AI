/**
 * Converts Float32Array (Web Audio) to Int16Array (PCM16)
 */
export function floatTo16BitPCM(float32Array: Float32Array): Int16Array {
  const buffer = new Int16Array(float32Array.length);
  for (let i = 0; i < float32Array.length; i++) {
    const s = Math.max(-1, Math.min(1, float32Array[i]));
    buffer[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
  }
  return buffer;
}

/**
 * Converts Int16Array (PCM16) to Float32Array (Web Audio)
 */
export function pcm16ToFloat32(pcmData: Int16Array): Float32Array {
  const float32Array = new Float32Array(pcmData.length);
  for (let i = 0; i < pcmData.length; i++) {
    float32Array[i] = pcmData[i] / 32768;
  }
  return float32Array;
}

/**
 * Converts base64 string to Int16Array
 */
export function base64ToPCM16(base64: string): Int16Array {
  const binaryString = atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return new Int16Array(bytes.buffer);
}

/**
 * Converts Int16Array to base64 string
 */
export function pcm16ToBase64(pcmData: Int16Array): string {
  const bytes = new Uint8Array(pcmData.buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}
