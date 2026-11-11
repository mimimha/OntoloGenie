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
]

const headerLinks = [
  { to: '/audit', label: '실행 이력' },
  { to: '/admin/history', label: '정의·규칙 이력' },
  { to: '/admin/accounts', label: '계정·권한' },
]

function menuClassName({ isActive }: { isActive: boolean }) {
  return `border-b-3 px-4 py-3 ${
    isActive
      ? 'border-slate-900 font-semibold text-slate-900'
      : 'border-transparent text-slate-600 hover:text-slate-900'
  }`
}

function headerLinkClassName({ isActive }: { isActive: boolean }) {
  return `rounded-md px-3 py-1.5 text-sm ${
    isActive
      ? 'bg-slate-100 font-semibold text-slate-900'
      : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
  }`
}

function AppLayout() {
  const rulesWorkspace = useLocation().pathname.startsWith('/admin/rules')
  const { account } = useAuth()
  const workspaceAllowed = canAccess(account, 'workspace')
  const administrationAllowed = canAccess(account, 'administration')
  return (
    <div
      className={
        rulesWorkspace ? 'rules-shell' : 'min-h-screen bg-slate-100 p-4'
      }
    >
      <div
        className={
          rulesWorkspace
            ? 'rules-frame'
            : 'mx-auto max-w-7xl overflow-clip rounded-lg border border-slate-300 bg-white'
        }
      >
        <header className="app-header flex items-center justify-between border-b border-slate-300 px-5 py-4">
          <span
            className="app-brand font-semibold tracking-tight text-slate-900"
            aria-label="OntoloGenie"
          >
            {rulesWorkspace ? (
              <img
                src="/ontologenie-logo.svg"
                alt="OntoloGenie"
                width="250"
                height="34"
              />
            ) : (
              'OntoloGenie'
            )}
          </span>
          <div className="flex flex-wrap items-center justify-end gap-3">
            <nav aria-label="계정 메뉴" className="flex flex-wrap gap-1">
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
            <AccountMenu compact={rulesWorkspace} />
          </div>
        </header>

        <nav
          aria-label="주 메뉴"
          className="main-nav flex items-center border-b border-slate-300 px-5"
        >
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

        <main className="workspace-main p-5">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export default AppLayout
