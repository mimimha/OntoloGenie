import { NavLink, Outlet } from 'react-router'

const menus = [
  { to: '/', label: '업무 데이터' },
  { to: '/agent', label: 'AI 업무 도우미' },
]

function menuClassName({ isActive }: { isActive: boolean }) {
  return `border-b-3 px-4 py-3 ${
    isActive
      ? 'border-slate-900 font-semibold text-slate-900'
      : 'border-transparent text-slate-600 hover:text-slate-900'
  }`
}

function AppLayout() {
  return (
    <div className="min-h-screen bg-slate-100 p-4">
      <div className="mx-auto max-w-7xl overflow-clip rounded-lg border border-slate-300 bg-white">
        <header className="border-b border-slate-300 px-5 py-4">
          <span className="font-semibold tracking-tight text-slate-900">
            OntoloGenie
          </span>
        </header>

        <nav
          aria-label="주 메뉴"
          className="flex border-b border-slate-300 px-5"
        >
          {menus.map((menu) => (
            <NavLink
              key={menu.to}
              to={menu.to}
              end={menu.to === '/'}
              className={menuClassName}
            >
              {menu.label}
            </NavLink>
          ))}
        </nav>

        <main className="p-5">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export default AppLayout
