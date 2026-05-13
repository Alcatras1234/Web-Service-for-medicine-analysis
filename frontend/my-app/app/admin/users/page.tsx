"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { toast } from "sonner"
import { ArrowLeft, RefreshCw, Trash2, UserPlus, FileText, Shield } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"

type User = {
  id: number
  email: string
  fullName: string
  role: string
  createdAt: string
}

export default function AdminUsersPage() {
  const router = useRouter()
  const [users, setUsers] = useState<User[]>([])
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  const [email, setEmail] = useState("")
  const [fullName, setFullName] = useState("")
  const [password, setPassword] = useState("")
  const [role, setRole] = useState("USER")
  const [creating, setCreating] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const res = await fetch("/api/admin/users", { credentials: "include", cache: "no-store" })
      if (res.status === 401) { router.push("/"); return }
      if (res.status === 403) { setForbidden(true); return }
      if (!res.ok) { toast.error(`Ошибка ${res.status}`); return }
      setUsers(await res.json())
    } catch {
      toast.error("Сетевая ошибка")
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() }, [])

  async function createUser(e: React.FormEvent) {
    e.preventDefault()
    if (!email || !password) {
      toast.error("Email и пароль обязательны")
      return
    }
    setCreating(true)
    try {
      const res = await fetch("/api/admin/users", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, fullName, password, role }),
      })
      if (res.status === 403) { setForbidden(true); return }
      if (!res.ok) {
        const err = await res.json().catch(() => null)
        toast.error(err?.error || `Ошибка ${res.status}`)
        return
      }
      toast.success("Пользователь создан")
      setEmail(""); setFullName(""); setPassword(""); setRole("USER")
      load()
    } finally {
      setCreating(false)
    }
  }

  async function deleteUser(u: User) {
    if (!confirm(`Удалить пользователя ${u.email}? Это действие необратимо.`)) return
    const res = await fetch(`/api/admin/users/${u.id}`, {
      method: "DELETE",
      credentials: "include",
    })
    if (!res.ok && res.status !== 204) {
      toast.error(`Ошибка ${res.status}`)
      return
    }
    toast.success("Удалено")
    load()
  }

  if (forbidden) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
        <div className="bg-white border border-amber-200 rounded-xl p-8 max-w-md text-center">
          <Shield className="h-12 w-12 mx-auto mb-3 text-amber-500" />
          <h2 className="text-xl font-bold text-slate-900 mb-2">Доступ запрещён</h2>
          <p className="text-slate-600 mb-4">
            Управление пользователями доступно только администратору.
          </p>
          <Button onClick={() => router.push("/dashboard")} variant="outline">
            <ArrowLeft className="mr-2 h-4 w-4" />На дашборд
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-white border-b border-slate-200 sticky top-0 z-30">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="bg-blue-600 p-2 rounded-lg"><FileText className="h-5 w-5 text-white" /></div>
            <span className="text-xl font-bold text-slate-900 hidden md:block">EosinAI</span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => router.push("/dashboard")} className="text-slate-700">
              Дашборд
            </Button>
            <Button variant="ghost" onClick={() => router.push("/patients")} className="text-slate-700">
              Пациенты
            </Button>
            <Button variant="ghost" onClick={() => router.push("/cases")} className="text-slate-700">
              Кейсы
            </Button>
            <Button variant="ghost" size="icon" onClick={load} title="Обновить">
              <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
            </Button>
          </div>
        </div>
      </header>

      <main className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-slate-900">Управление пользователями</h1>
          <p className="text-slate-500">Создавайте учётные записи врачей и администраторов</p>
        </div>

        {/* Форма создания */}
        <form onSubmit={createUser} method="POST" action="#"
              className="bg-white border border-slate-200 rounded-xl p-5 shadow-sm mb-6">
          <h2 className="text-lg font-semibold text-slate-900 mb-4 flex items-center gap-2">
            <UserPlus className="h-5 w-5 text-blue-600" /> Новый пользователь
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
            <div>
              <Label htmlFor="email">Email <span className="text-red-500">*</span></Label>
              <Input id="email" type="email" required value={email}
                     onChange={e => setEmail(e.target.value)}
                     placeholder="doctor@hospital.ru"
                     disabled={creating} />
            </div>
            <div>
              <Label htmlFor="fullName">ФИО</Label>
              <Input id="fullName" value={fullName}
                     onChange={e => setFullName(e.target.value)}
                     placeholder="Иванов И. И."
                     disabled={creating} />
            </div>
            <div>
              <Label htmlFor="password">Пароль <span className="text-red-500">*</span></Label>
              <Input id="password" type="password" required value={password}
                     onChange={e => setPassword(e.target.value)}
                     placeholder="минимум 6 символов"
                     disabled={creating} />
            </div>
            <div>
              <Label htmlFor="role">Роль</Label>
              <select id="role" value={role} onChange={e => setRole(e.target.value)}
                      disabled={creating}
                      className="w-full bg-white border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500">
                <option value="USER">USER (врач)</option>
                <option value="DOCTOR">DOCTOR (с правом подписи)</option>
                <option value="ADMIN">ADMIN (полный доступ)</option>
              </select>
            </div>
          </div>
          <Button type="submit" disabled={creating}
                  className="bg-blue-600 hover:bg-blue-700">
            {creating
              ? <><div className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />Создаём...</>
              : <><UserPlus className="mr-2 h-4 w-4" />Создать</>}
          </Button>
        </form>

        {/* Список */}
        <div className="bg-white border border-slate-200 rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-slate-100 bg-slate-50 flex items-center justify-between">
            <h3 className="font-semibold text-slate-900">
              Все пользователи <span className="text-slate-500 font-normal">({users.length})</span>
            </h3>
          </div>
          <table className="w-full">
            <thead className="bg-slate-50/50 text-left text-xs text-slate-500 uppercase">
              <tr>
                <th className="px-5 py-2">Email</th>
                <th className="py-2">ФИО</th>
                <th className="py-2">Роль</th>
                <th className="py-2">Создан</th>
                <th className="px-5 py-2 text-right">Действия</th>
              </tr>
            </thead>
            <tbody>
              {loading && users.length === 0 ? (
                <tr><td colSpan={5} className="p-8 text-center text-slate-500">
                  <RefreshCw className="h-5 w-5 animate-spin mx-auto mb-2" />Загрузка...
                </td></tr>
              ) : users.length === 0 ? (
                <tr><td colSpan={5} className="p-8 text-center text-slate-500">Нет пользователей</td></tr>
              ) : users.map(u => (
                <tr key={u.id} className="border-t border-slate-100 hover:bg-slate-50/50">
                  <td className="px-5 py-3 font-medium text-slate-900">{u.email}</td>
                  <td className="py-3 text-slate-700">{u.fullName || "—"}</td>
                  <td className="py-3">
                    <Badge className={
                      u.role === "ADMIN" ? "bg-red-100 text-red-700 border-red-200" :
                      u.role === "DOCTOR" ? "bg-purple-100 text-purple-700 border-purple-200" :
                      "bg-slate-100 text-slate-700 border-slate-200"
                    }>{u.role}</Badge>
                  </td>
                  <td className="py-3 text-sm text-slate-500">
                    {u.createdAt ? new Date(u.createdAt).toLocaleDateString("ru-RU") : "—"}
                  </td>
                  <td className="px-5 py-3 text-right">
                    <Button variant="ghost" size="sm" onClick={() => deleteUser(u)}
                            className="text-red-600 hover:bg-red-50 hover:text-red-700">
                      <Trash2 className="h-4 w-4 mr-1" />Удалить
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </main>
    </div>
  )
}
