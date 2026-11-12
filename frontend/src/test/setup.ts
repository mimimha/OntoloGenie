import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach, vi } from 'vitest'
import { demoAccount, setMockAccount } from '../mocks/auth'
import { server } from '../mocks/node'
import { resetProposalMocks } from '../mocks/proposalHandlers'
import { resetAccountMocks } from '../mocks/accountHandlers'
import { resetActionMocks } from '../mocks/actionHandlers'
import { mockReactFlow } from './reactFlow'

mockReactFlow()

// findBy·waitFor 기본 대기는 1초다. 검사 서버(메모리 2GB 컨테이너에서 테스트 파일을 동시에 돌림)처럼 바쁠 때는
// 모의 API 응답이 1초를 넘겨 '불러오는 중'에서 실패한다. 찾으면 바로 넘어가므로 통과하는 테스트는 느려지지 않는다.
// 테스트 하나의 시간 한도(vite.config.ts testTimeout)보다 짧게 둔다
configure({ asyncUtilTimeout: 15000 })

beforeEach(() => {
  resetAccountMocks()
  resetActionMocks()
  resetProposalMocks()
  setMockAccount({ ...demoAccount })
})

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
})

// 테스트가 끝날 때 아직 응답 중인 모의 요청(핸들러의 delay 등)을 화면 정리로 끊으면, 바쁠 때 다음 테스트의
// 첫 요청이 끊긴 요청의 흐름을 이어받아 MSW를 거치지 않고 실제 네트워크로 나가 '서버에 연결하지 못했습니다'로
// 실패한다(분석 패널 테스트, 로컬 6배 부하에서 확인). 진행 중인 모의 요청이 끝날 때까지 잠깐 기다린 뒤 정리한다
let pendingRequests = 0
server.events.on('request:start', () => {
  pendingRequests += 1
})
server.events.on('request:end', () => {
  pendingRequests = Math.max(0, pendingRequests - 1)
})

async function waitForPendingRequests(limitMs = 3000) {
  // 가짜 타이머를 쓰는 테스트에서는 setTimeout이 흐르지 않으므로 기다리지 않는다
  if (vi.isFakeTimers()) return
  const until = Date.now() + limitMs
  while (pendingRequests > 0 && Date.now() < until) {
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
}

afterEach(async () => {
  await waitForPendingRequests()
  cleanup()
  server.resetHandlers()
  pendingRequests = 0
})

afterAll(() => {
  server.close()
})
