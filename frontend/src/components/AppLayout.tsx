import { NavLink, Outlet, useLocation } from 'react-router'
import AccountMenu from '../features/auth/AccountMenu'
import { useAuth } from '../features/auth/auth-context'
import { canAccess } from '../features/auth/access'

const userMenus = [
  { to: '/explorer', label: '업무 데이터' },
  { to: '/agent', label: 'AI 업무 도우미' },
]

const adminMenus = [
  { to: '/admin/sources', label: '데이터 연결' },
  { to: '/admin/rules', label: '연결 규칙' },
  { to: '/admin/actions', label: 'Action 정의' },
]

const headerLinks = [
  { to: '/audit', label: '실행 이력' },
  { to: '/admin/history', label: '정의·규칙 이력' },
  { to: '/admin/accounts', label: '계정·권한' },
]

function menuClassName({ isActive }: { isActive: boolean }) {
  return `app-nav-link${isActive ? ' is-active' : ''}`
}

function headerLinkClassName({ isActive }: { isActive: boolean }) {
  return `app-header-link${isActive ? ' is-active' : ''}`
}

function AppLayout() {
  const rulesWorkspace = useLocation().pathname.startsWith('/admin/rules')
  const { account } = useAuth()
  const workspaceAllowed = canAccess(account, 'workspace')
  const administrationAllowed = canAccess(account, 'administration')
  return (
    <div className={`app-shell${rulesWorkspace ? ' rules-shell' : ''}`}>
      <a className="app-skip-link" href="#workspace-main">
        본문으로 이동
      </a>
      <div className="app-frame">
        <header className="app-header">
          <span
            className="app-brand font-semibold tracking-tight text-slate-900"
            aria-label="OntoloGenie"
          >
            <img
              src="/ontologenie-logo.svg"
              alt="OntoloGenie"
              width="250"
              height="32"
            />
          </span>
          <div className="app-header-actions">
            <nav aria-label="계정 메뉴">
              {headerLinks
                .filter((link) =>
                  link.to.startsWith('/admin/')
                    ? administrationAllowed
                    : workspaceAllowed,
                )
                .map((link) => (
                  <NavLink
                    key={link.to}
                    to={link.to}
                    className={headerLinkClassName}
                  >
                    {link.label}
                  </NavLink>
                ))}
            </nav>
            <AccountMenu />
          </div>
        </header>

        <nav aria-label="주 메뉴" className="main-nav">
          {workspaceAllowed &&
            userMenus.map((menu) => (
              <NavLink key={menu.to} to={menu.to} className={menuClassName}>
                {menu.label}
              </NavLink>
            ))}

          {administrationAllowed &&
            adminMenus.map((menu) => (
              <NavLink key={menu.to} to={menu.to} className={menuClassName}>
                {menu.label}
              </NavLink>
            ))}
        </nav>

        <main id="workspace-main" tabIndex={-1} className="workspace-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export default AppLayout
