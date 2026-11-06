import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest'
import { demoAccount, setMockAccount } from '../mocks/auth'
import { server } from '../mocks/node'
import { resetProposalMocks } from '../mocks/proposalHandlers'
import { resetAccountMocks } from '../mocks/accountHandlers'
import { mockReactFlow } from './reactFlow'

mockReactFlow()

// findBy·waitFor 기본 대기는 1초다. 검사 서버(메모리 2GB 컨테이너에서 테스트 파일을 동시에 돌림)처럼 바쁠 때는
// 모의 API 응답이 1초를 넘겨 '불러오는 중'에서 실패한다. 찾으면 바로 넘어가므로 통과하는 테스트는 느려지지 않는다.
// 테스트 하나의 시간 한도(vite.config.ts testTimeout)보다 짧게 둔다
configure({ asyncUtilTimeout: 15000 })

beforeEach(() => {
  resetAccountMocks()
  resetProposalMocks()
  setMockAccount({ ...demoAccount })
})

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
})

afterEach(() => {
  cleanup()
  server.resetHandlers()
})

afterAll(() => {
  server.close()
})
