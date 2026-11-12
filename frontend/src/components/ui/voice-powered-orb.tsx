import { useEffect, useRef } from 'react'
import { Mesh, Program, Renderer, Triangle, Vec3 } from 'ogl'
import {
  ORB_FRAGMENT_SHADER,
  ORB_VERTEX_SHADER,
} from './voice-powered-orb-shaders'
import './voice-powered-orb.css'
import { RippleTrace } from './voice-powered-orb-ripple'
import { createRippleShapeFrames } from './voice-powered-orb-ripple-shape'

const RENDER_SCALE = 1.25

interface VoicePoweredOrbProps {
  className?: string
  hue?: number
  enableVoiceControl?: boolean
  voiceSensitivity?: number
  maxRotationSpeed?: number
  maxHoverIntensity?: number
  onVoiceDetected?: (detected: boolean) => void
}

export function VoicePoweredOrb({
  className = '',
  hue = 0,
  enableVoiceControl = false,
  voiceSensitivity = 1.5,
  maxRotationSpeed = 1.2,
  maxHoverIntensity = 0.8,
  onVoiceDetected,
}: VoicePoweredOrbProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const rippleRef = useRef<HTMLSpanElement>(null)
  const rippleEchoRef = useRef<HTMLSpanElement>(null)
  const voiceCallbackRef = useRef(onVoiceDetected)

  useEffect(() => {
    voiceCallbackRef.current = onVoiceDetected
  }, [onVoiceDetected])

  useEffect(() => {
    const container = containerRef.current
    if (!container || !window.WebGLRenderingContext) return

    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    let renderer: Renderer | null = null
    let geometry: Triangle | null = null
    let program: Program | null = null
    let mesh: Mesh | null = null
    let resizeObserver: ResizeObserver | null = null
    let intersectionObserver: IntersectionObserver | null = null
    let stream: MediaStream | null = null
    let audioContext: AudioContext | null = null
    let analyser: AnalyserNode | null = null
    let microphone: MediaStreamAudioSourceNode | null = null
    let audioData: Uint8Array<ArrayBuffer> | null = null
    let frame = 0
    let disposed = false
    let contextLost = false
    let visible = true
    let elapsed = 0
    let previousTime: number | null = null
    let rotation = 0
    let hover = 0
    let targetHover = 0
    let voiceDetected = false
    let clickStartedAt: number | null = null
    let pulseAnimation: Animation | null = null
    let rippleAnimation: Animation | null = null
    let rippleEchoAnimation: Animation | null = null
    let rippleShapeAnimations: Animation[] = []

    function cancelClickFeedback() {
      pulseAnimation?.cancel()
      rippleAnimation?.cancel()
      rippleEchoAnimation?.cancel()
      rippleShapeAnimations.forEach((animation) => animation.cancel())
      rippleShapeAnimations = []
      pulseAnimation = null
      rippleAnimation = null
      rippleEchoAnimation = null
      clickStartedAt = null
      if (program) {
        program.uniforms.clickStrength.value = 0
        program.uniforms.clickAge.value = 0
      }
      rippleRef.current?.removeAttribute('style')
      rippleEchoRef.current?.removeAttribute('style')
    }

    function playClickFeedback(event: PointerEvent) {
      const canvas = renderer?.gl.canvas
      if (event.button !== 0 || motion.matches || !canvas?.animate) return
      cancelClickFeedback()
      clickStartedAt = elapsed
      const canvasBounds = canvas.getBoundingClientRect()
      const anchorX = canvasBounds.width
        ? Math.max(
            0,
            Math.min(
              1,
              (event.clientX - canvasBounds.left) / canvasBounds.width,
            ),
          )
        : 0.5
      const anchorY = canvasBounds.height
        ? Math.max(
            0,
            Math.min(
              1,
              (event.clientY - canvasBounds.top) / canvasBounds.height,
            ),
          )
        : 0.5
      canvas.style.transformOrigin = `${anchorX * 100}% ${anchorY * 100}%`
      const canvasSize = Math.min(canvasBounds.width, canvasBounds.height)
      program!.uniforms.clickOrigin.value.set(
        canvasSize
          ? ((anchorX - 0.5) * canvasBounds.width * 2 * RENDER_SCALE) /
              canvasSize
          : 0,
        canvasSize
          ? ((0.5 - anchorY) * canvasBounds.height * 2 * RENDER_SCALE) /
              canvasSize
          : 0,
        0,
      )
      pulseAnimation = canvas.animate(
        [
          {
            transform: 'translate(-50%, -50%) scale(1)',
            offset: 0,
            easing: 'ease-in',
          },
          ...Array.from({ length: 70 }, (_, index) => {
            const progress = index / 69
            const time = progress * 1.15
            const scale =
              index === 69
                ? 1
                : 1 - 0.07 * Math.exp(-5.2 * time) * Math.cos(18 * time)
            return {
              transform: `translate(-50%, -50%) scale(${scale})`,
              offset: (150 + progress * 1150) / 1300,
            }
          }),
        ],
        { duration: 1300, easing: 'linear' },
      )
      const bounds = container!.getBoundingClientRect()
      const ripple = rippleRef.current
      const rippleEcho = rippleEchoRef.current
      const viewportWidth =
        document.documentElement.clientWidth || window.innerWidth
      const availableRadius = Math.min(
        event.clientX - 16,
        viewportWidth - event.clientX - 16,
      )
      const waveDiameter = Math.min(
        bounds.width * 0.76,
        Math.max(1, availableRadius * 2 - 24),
      )
      const waveScale = Math.max(
        1,
        Math.min(3.2, (availableRadius * 2) / (waveDiameter + 24)),
      )
      const initialWaveScale = Math.min(1, 8 / waveDiameter)
      for (const ring of [ripple, rippleEcho]) {
        if (!ring) continue
        ring.style.left = `${event.clientX - bounds.left}px`
        ring.style.top = `${event.clientY - bounds.top}px`
        ring.style.width = `${waveDiameter}px`
        ring.style.height = `${waveDiameter}px`
        const trace = ring.querySelector<SVGPathElement>(
          '.voice-powered-orb-ripple-line',
        )
        if (trace?.animate) {
          const echo = ring === rippleEcho
          rippleShapeAnimations.push(
            trace.animate(createRippleShapeFrames(echo), {
              duration: echo ? 2900 : 2600,
              delay: echo ? 320 : 0,
              easing: 'ease-in-out',
            }),
          )
        }
      }
      const rippleFrames = Array.from({ length: 41 }, (_, index) => {
        const progress = index / 40
        const scale =
          initialWaveScale +
          (waveScale - initialWaveScale) * (1 - (1 - progress) ** 1.6)
        const opacity = 0.85 * (1 - progress ** 2) ** 2
        return {
          transform: `translate(-50%, -50%) scale(${scale})`,
          opacity,
          filter: `blur(${progress * progress * 2.3}px)`,
          offset: progress,
        }
      })
      rippleAnimation =
        ripple?.animate(rippleFrames, { duration: 2600, easing: 'linear' }) ??
        null
      rippleEchoAnimation =
        rippleEcho?.animate(
          rippleFrames.map((frame) => ({
            ...frame,
            opacity: frame.opacity * 0.5,
            filter: `blur(${0.35 + Number(frame.offset) * 2.3}px)`,
          })),
          { duration: 2900, delay: 320, easing: 'linear' },
        ) ?? null
      if (rippleAnimation) {
        const animation = rippleAnimation
        animation.onfinish = () => {
          if (rippleAnimation !== animation) return
          rippleAnimation = null
          ripple?.removeAttribute('style')
        }
      }
      if (rippleEchoAnimation) {
        const animation = rippleEchoAnimation
        animation.onfinish = () => {
          if (rippleEchoAnimation !== animation) return
          rippleEchoAnimation = null
          rippleEcho?.removeAttribute('style')
        }
      }
    }

    function stopMicrophone() {
      stream?.getTracks().forEach((track) => track.stop())
      stream = null
      microphone?.disconnect()
      analyser?.disconnect()
      microphone = null
      analyser = null
      audioData = null
      if (audioContext && audioContext.state !== 'closed') {
        void audioContext.close().catch(() => {})
      }
      audioContext = null
    }

    async function initializeMicrophone() {
      if (!navigator.mediaDevices?.getUserMedia || !window.AudioContext) return
      try {
        const nextStream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: false,
            noiseSuppression: false,
            autoGainControl: false,
          },
        })
        if (disposed) {
          nextStream.getTracks().forEach((track) => track.stop())
          return
        }
        stream = nextStream
        audioContext = new AudioContext()
        if (audioContext.state === 'suspended') await audioContext.resume()
        if (disposed || !audioContext) return
        analyser = audioContext.createAnalyser()
        analyser.fftSize = 512
        analyser.smoothingTimeConstant = 0.3
        analyser.minDecibels = -90
        analyser.maxDecibels = -10
        microphone = audioContext.createMediaStreamSource(stream)
        microphone.connect(analyser)
        audioData = new Uint8Array(analyser.frequencyBinCount)
      } catch {
        stopMicrophone()
      }
    }

    function readVoiceLevel() {
      if (!analyser || !audioData) return 0
      analyser.getByteFrequencyData(audioData)
      let sum = 0
      for (const value of audioData) sum += (value / 255) ** 2
      return Math.min(
        Math.sqrt(sum / audioData.length) * voiceSensitivity * 3,
        1,
      )
    }

    function draw(delta: number) {
      if (!renderer || !program || !mesh || contextLost) return
      const voice = motion.matches ? 0 : readVoiceLevel()
      const detected = voice > 0.1
      if (detected !== voiceDetected) {
        voiceDetected = detected
        voiceCallbackRef.current?.(detected)
      }
      hover +=
        (Math.max(targetHover, voice * 2) - hover) * (1 - Math.exp(-delta * 8))
      rotation +=
        delta *
        (voice > 0.05 ? 0.3 + voice * maxRotationSpeed * 2 : hover * 0.15)
      program.uniforms.iTime.value = elapsed
      program.uniforms.rot.value = rotation
      program.uniforms.hover.value = Math.min(hover, 1)
      program.uniforms.hoverIntensity.value = maxHoverIntensity
      const clickAge = clickStartedAt === null ? 0 : elapsed - clickStartedAt
      const attack = Math.min(clickAge / 0.08, 1)
      const release = Math.max(0, Math.min(1, (2.4 - clickAge) / 1.2))
      program.uniforms.clickAge.value = clickAge
      program.uniforms.clickStrength.value =
        clickStartedAt === null
          ? 0
          : attack *
            attack *
            (3 - 2 * attack) *
            release *
            release *
            (3 - 2 * release)
      if (clickAge >= 2.4) clickStartedAt = null
      renderer.render({ scene: mesh })
      container!.dataset.ready = 'true'
    }

    function animate(time: number) {
      if (disposed || contextLost) return
      if (motion.matches || document.hidden || !visible) {
        updatePlayback()
        return
      }
      const delta =
        previousTime === null ? 0 : Math.min((time - previousTime) / 1000, 0.05)
      previousTime = time
      elapsed += delta
      draw(delta)
      frame = requestAnimationFrame(animate)
    }

    function updatePlayback() {
      cancelAnimationFrame(frame)
      previousTime = null
      const active =
        !disposed &&
        !contextLost &&
        visible &&
        !document.hidden &&
        !motion.matches
      container!.dataset.animated = String(active)
      if (!active) cancelClickFeedback()
      if (motion.matches) {
        targetHover = 0
        hover = 0
        elapsed = 0
        rotation = 0
        draw(0)
      }
      if (active) frame = requestAnimationFrame(animate)
    }

    function resize() {
      if (!renderer || !program || contextLost) return
      cancelClickFeedback()
      const { clientWidth: width, clientHeight: height } = container!
      if (!width || !height) return
      renderer.dpr = Math.min(window.devicePixelRatio || 1, 2)
      renderer.setSize(width * RENDER_SCALE, height * RENDER_SCALE)
      program.uniforms.iResolution.value.set(
        renderer.gl.canvas.width,
        renderer.gl.canvas.height,
        width / height,
      )
      draw(0)
    }

    function pointerMove(event: PointerEvent) {
      if (motion.matches || event.pointerType === 'touch') return
      const bounds = container!.getBoundingClientRect()
      const x =
        (event.clientX - bounds.left - bounds.width / 2) / (bounds.width / 2)
      const y =
        (event.clientY - bounds.top - bounds.height / 2) / (bounds.height / 2)
      targetHover = Math.max(0, 1 - Math.hypot(x, y) * 0.65)
    }

    function pointerLeave() {
      targetHover = 0
    }

    function loseContext(event: Event) {
      event.preventDefault()
      contextLost = true
      container!.dataset.ready = 'false'
      updatePlayback()
    }

    function cleanup() {
      disposed = true
      cancelAnimationFrame(frame)
      resizeObserver?.disconnect()
      intersectionObserver?.disconnect()
      container!.removeEventListener('pointermove', pointerMove)
      container!.removeEventListener('pointerleave', pointerLeave)
      container!.removeEventListener('pointerdown', playClickFeedback)
      document.removeEventListener('visibilitychange', updatePlayback)
      motion.removeEventListener('change', updatePlayback)
      stopMicrophone()
      cancelClickFeedback()
      geometry?.remove()
      program?.remove()
      if (renderer) {
        renderer.gl.canvas.removeEventListener('webglcontextlost', loseContext)
        renderer.gl.canvas.remove()
        renderer.gl.getExtension('WEBGL_lose_context')?.loseContext()
      }
    }

    try {
      renderer = new Renderer({
        alpha: true,
        premultipliedAlpha: true,
        antialias: true,
        dpr: Math.min(window.devicePixelRatio || 1, 2),
        depth: false,
        webgl: 1,
        powerPreference: 'low-power',
      })
      const gl = renderer.gl
      gl.clearColor(0, 0, 0, 0)
      geometry = new Triangle(gl)
      program = new Program(gl, {
        vertex: ORB_VERTEX_SHADER,
        fragment: ORB_FRAGMENT_SHADER,
        depthTest: false,
        depthWrite: false,
        cullFace: null,
        uniforms: {
          iTime: { value: 0 },
          iResolution: { value: new Vec3() },
          viewportScale: { value: RENDER_SCALE },
          hue: { value: hue },
          hover: { value: 0 },
          rot: { value: 0 },
          hoverIntensity: { value: maxHoverIntensity },
          clickStrength: { value: 0 },
          clickAge: { value: 0 },
          clickOrigin: { value: new Vec3() },
        },
      })
      if (!gl.getProgramParameter(program.program, gl.LINK_STATUS))
        throw new Error('Orb shader unavailable')
      mesh = new Mesh(gl, { geometry, program })
      container.appendChild(gl.canvas)
      resizeObserver = new ResizeObserver(resize)
      resizeObserver.observe(container)
      intersectionObserver = new IntersectionObserver(([entry]) => {
        visible = entry.isIntersecting
        updatePlayback()
      })
      intersectionObserver.observe(container)
      container.addEventListener('pointermove', pointerMove, { passive: true })
      container.addEventListener('pointerleave', pointerLeave)
      container.addEventListener('pointerdown', playClickFeedback)
      gl.canvas.addEventListener('webglcontextlost', loseContext)
      document.addEventListener('visibilitychange', updatePlayback)
      motion.addEventListener('change', updatePlayback)
      resize()
      updatePlayback()
      if (enableVoiceControl && !motion.matches) void initializeMicrophone()
      return cleanup
    } catch {
      container.dataset.ready = 'false'
      cleanup()
    }
  }, [
    hue,
    enableVoiceControl,
    voiceSensitivity,
    maxRotationSpeed,
    maxHoverIntensity,
  ])

  return (
    <div
      ref={containerRef}
      className={`voice-powered-orb ${className}`}
      aria-hidden="true"
    >
      <span ref={rippleRef} className="voice-powered-orb-ripple">
        <RippleTrace />
      </span>
      <span
        ref={rippleEchoRef}
        className="voice-powered-orb-ripple voice-powered-orb-ripple--echo"
      >
        <RippleTrace echo />
      </span>
    </div>
  )
}
