import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest'
import { demoAccount, setMockAccount } from '../mocks/auth'
import { server } from '../mocks/node'
import { resetProposalMocks } from '../mocks/proposalHandlers'
import { mockReactFlow } from './reactFlow'

mockReactFlow()

beforeEach(() => {
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
