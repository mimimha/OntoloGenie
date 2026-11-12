import { act, cleanup, render } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { VoicePoweredOrb } from './voice-powered-orb'

const resources = vi.hoisted(() => ({
  render: vi.fn(),
  setSize: vi.fn(),
  geometryRemove: vi.fn(),
  programRemove: vi.fn(),
  loseContext: vi.fn(),
  uniforms: null as Record<string, { value: unknown }> | null,
}))

vi.mock('ogl', () => ({
  Renderer: class {
    dpr: number
    gl = {
      canvas: document.createElement('canvas'),
      clearColor: vi.fn(),
      LINK_STATUS: 1,
      getProgramParameter: () => true,
      getExtension: () => ({ loseContext: resources.loseContext }),
    }
    constructor(options: { dpr: number }) {
      this.dpr = options.dpr
    }
    setSize(width: number, height: number) {
      resources.setSize(width, height)
      this.gl.canvas.width = width * this.dpr
      this.gl.canvas.height = height * this.dpr
    }
    render = resources.render
  },
  Program: class {
    uniforms: Record<string, { value: unknown }>
    program = {}
    constructor(
      _gl: unknown,
      options: { uniforms: Record<string, { value: unknown }> },
    ) {
      this.uniforms = options.uniforms
      resources.uniforms = options.uniforms
    }
    remove = resources.programRemove
  },
  Triangle: class {
    remove = resources.geometryRemove
  },
  Mesh: class {},
  Vec3: class {
    set = vi.fn()
  },
}))

const mediaDevicesDescriptor = Object.getOwnPropertyDescriptor(
  navigator,
  'mediaDevices',
)
const animateDescriptor = Object.getOwnPropertyDescriptor(
  HTMLElement.prototype,
  'animate',
)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.clearAllMocks()
  resources.uniforms = null
  if (animateDescriptor) {
    Object.defineProperty(HTMLElement.prototype, 'animate', animateDescriptor)
  } else {
    Reflect.deleteProperty(HTMLElement.prototype, 'animate')
  }
  if (mediaDevicesDescriptor) {
    Object.defineProperty(navigator, 'mediaDevices', mediaDevicesDescriptor)
  } else {
    Reflect.deleteProperty(navigator, 'mediaDevices')
  }
})

function setupEnvironment(reduced = false) {
  vi.stubGlobal('WebGLRenderingContext', class {})
  vi.stubGlobal('AudioContext', vi.fn())
  vi.stubGlobal('devicePixelRatio', 2)
  const motion = Object.assign(new EventTarget(), { matches: reduced })
  vi.stubGlobal('matchMedia', () => motion)
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(400)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(400)
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn(() => 1),
  )
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  const getUserMedia = vi.fn()
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia },
  })
  return { getUserMedia, motion }
}

test('고밀도 화면에서 크기를 중복 확대하지 않고 기본 상태에서는 마이크를 요청하지 않는다', () => {
  const { getUserMedia } = setupEnvironment()
  const view = render(<VoicePoweredOrb />)
  const canvas = view.container.querySelector('canvas')!
  expect(resources.setSize).toHaveBeenCalledWith(500, 500)
  expect(canvas.width).toBe(1000)
  expect(canvas.height).toBe(1000)
  expect(getUserMedia).not.toHaveBeenCalled()
  view.unmount()
  expect(resources.geometryRemove).toHaveBeenCalledOnce()
  expect(resources.programRemove).toHaveBeenCalledOnce()
  expect(resources.loseContext).toHaveBeenCalledOnce()
})

test('동작 줄이기에서는 정지 상태로 그린 뒤 애니메이션과 마이크 요청을 시작하지 않는다', () => {
  const { getUserMedia } = setupEnvironment(true)
  const view = render(<VoicePoweredOrb enableVoiceControl />)
  expect(view.container.firstElementChild).toHaveAttribute('data-ready', 'true')
  expect(view.container.firstElementChild).toHaveAttribute(
    'data-animated',
    'false',
  )
  expect(resources.render).toHaveBeenCalled()
  expect(requestAnimationFrame).not.toHaveBeenCalled()
  expect(getUserMedia).not.toHaveBeenCalled()
})

test('컴포넌트가 제거된 뒤 도착한 마이크 스트림은 즉시 종료한다', async () => {
  const { getUserMedia } = setupEnvironment()
  let resolveStream!: (stream: MediaStream) => void
  getUserMedia.mockReturnValue(
    new Promise<MediaStream>((resolve) => {
      resolveStream = resolve
    }),
  )
  const stop = vi.fn()
  const view = render(<VoicePoweredOrb enableVoiceControl />)
  expect(getUserMedia).toHaveBeenCalledOnce()
  view.unmount()
  await act(async () => {
    resolveStream({ getTracks: () => [{ stop }] } as unknown as MediaStream)
  })
  expect(stop).toHaveBeenCalledOnce()
  expect(AudioContext).not.toHaveBeenCalled()
})

test('설정 변경 알림이 늦어도 다음 프레임에서 동작 줄이기를 적용한다', () => {
  const { motion } = setupEnvironment()
  const view = render(<VoicePoweredOrb />)
  const callback = vi.mocked(requestAnimationFrame).mock.calls[0][0]
  motion.matches = true
  act(() => callback(100))
  expect(view.container.firstElementChild).toHaveAttribute(
    'data-animated',
    'false',
  )
  expect(requestAnimationFrame).toHaveBeenCalledOnce()
})

test('클릭 위치를 반동과 파동 중심으로 사용하고 반복 클릭·제거 시 이전 효과를 정리한다', () => {
  setupEnvironment()
  const cancel = vi.fn()
  const animate = vi.fn<
    (frames: Keyframe[], options?: KeyframeAnimationOptions) => Animation
  >(() => ({ cancel }) as unknown as Animation)
  Object.defineProperty(HTMLElement.prototype, 'animate', {
    configurable: true,
    writable: true,
    value: animate,
  })
  const view = render(<VoicePoweredOrb />)
  const container = view.container.firstElementChild!
  vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(
    new DOMRect(70, 80, 400, 400),
  )
  const canvas = view.container.querySelector('canvas')!
  const ripple = view.container.querySelector<HTMLElement>(
    '.voice-powered-orb-ripple',
  )!
  const rippleEcho = view.container.querySelector<HTMLElement>(
    '.voice-powered-orb-ripple--echo',
  )!
  const clickOrigin = resources.uniforms!.clickOrigin.value as {
    set: (x: number, y: number, z: number) => void
  }
  vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue(
    new DOMRect(20, 30, 500, 500),
  )
  act(() => {
    container.dispatchEvent(
      new MouseEvent('pointerdown', { button: 0, clientX: 120, clientY: 180 }),
    )
  })
  expect(animate).toHaveBeenCalledTimes(3)
  expect(canvas.style.transformOrigin).toBe('20% 30%')
  expect(clickOrigin.set).toHaveBeenLastCalledWith(
    expect.closeTo(-0.75),
    expect.closeTo(0.5),
    0,
  )
  expect(ripple.style.left).toBe('50px')
  expect(ripple.style.top).toBe('100px')
  expect(rippleEcho.style.left).toBe(ripple.style.left)
  expect(rippleEcho.style.top).toBe(ripple.style.top)
  const firstWave = animate.mock.calls[1][1]!
  const secondWave = animate.mock.calls[2][1]!
  expect(firstWave.delay ?? 0).toBe(0)
  expect(secondWave.delay).toBeGreaterThanOrEqual(250)
  act(() => {
    container.dispatchEvent(
      new MouseEvent('pointerdown', { button: 0, clientX: 420, clientY: 380 }),
    )
  })
  expect(canvas.style.transformOrigin).toBe('80% 70%')
  expect(clickOrigin.set).toHaveBeenLastCalledWith(
    expect.closeTo(0.75),
    expect.closeTo(-0.5),
    0,
  )
  expect(ripple.style.left).toBe('350px')
  expect(ripple.style.top).toBe('300px')
  expect(cancel).toHaveBeenCalledTimes(3)
  expect(animate).toHaveBeenCalledTimes(6)
  view.unmount()
  expect(cancel).toHaveBeenCalledTimes(6)
})

test('동작 줄이기에서는 클릭 파동을 시작하지 않는다', () => {
  setupEnvironment(true)
  const animate = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'animate', {
    configurable: true,
    writable: true,
    value: animate,
  })
  const view = render(<VoicePoweredOrb />)
  act(() => {
    view.container.firstElementChild!.dispatchEvent(
      new MouseEvent('pointerdown', { button: 0 }),
    )
  })
  expect(animate).not.toHaveBeenCalled()
})

test('클릭하면 촘촘한 파형이 나타났다가 안정되고 재클릭·동작 줄이기에 반응한다', () => {
  const { motion } = setupEnvironment()
  Object.defineProperty(HTMLElement.prototype, 'animate', {
    configurable: true,
    writable: true,
    value: vi.fn(() => ({ cancel: vi.fn() }) as unknown as Animation),
  })
  const view = render(<VoicePoweredOrb />)
  const container = view.container.firstElementChild!
  const frame = vi.mocked(requestAnimationFrame).mock.calls[0][0]
  const strength = () => resources.uniforms!.clickStrength.value as number
  expect(strength()).toBe(0)
  act(() => {
    frame(0)
    container.dispatchEvent(new MouseEvent('pointerdown', { button: 0 }))
    frame(40)
  })
  const initial = strength()
  expect(initial).toBeGreaterThan(0)
  act(() => frame(80))
  expect(strength()).toBeGreaterThan(initial)
  expect(strength()).toBeGreaterThan(0.8)
  act(() => {
    container.dispatchEvent(new MouseEvent('pointerleave'))
    for (let time = 130; time <= 3030; time += 50) frame(time)
  })
  expect(strength()).toBe(0)
  act(() => {
    container.dispatchEvent(new MouseEvent('pointerdown', { button: 0 }))
    frame(3070)
  })
  expect(strength()).toBeGreaterThan(0)
  act(() => {
    motion.matches = true
    frame(3110)
  })
  expect(strength()).toBe(0)
})
