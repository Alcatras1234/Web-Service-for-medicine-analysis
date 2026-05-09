"use client"

import { useEffect, useState } from "react"
import Link from "next/link"

type CaseRow = {
  id: number
  patientId: string
  name: string | null
  status: string
  diagnosis: string | null
  createdAt: string
}

export default function CasesPage() {
  const [cases, setCases] = useState<CaseRow[]>([])
  const [loading, setLoading] = useState(true)
  const [name, setName] = useState("")
  const [patientId, setPatientId] = useState("")

  async function load() {
    setLoading(true)
    const res = await fetch("/api/cases")
    if (res.ok) setCases(await res.json())
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  async function createCase(e: React.FormEvent) {
    e.preventDefault()
    if (!patientId.trim()) return
    const res = await fetch("/api/cases", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ patientId, name }),
    })
    if (res.ok) { setName(""); setPatientId(""); load() }
  }

  async function deleteCase(id: number) {
    if (!confirm("Удалить кейс?")) return
    const res = await fetch(`/api/cases/${id}`, { method: "DELETE" })
    if (res.status === 409) alert("Подписанный кейс нельзя удалить")
    else load()
  }

  return (
    <div className="min-h-screen bg-slate-50 p-6">
      <h1 className="text-2xl font-bold mb-4">Клинические кейсы</h1>

      <form onSubmit={createCase} className="bg-white p-4 rounded shadow mb-6 flex gap-2">
        <input className="border p-2 flex-1" placeholder="Patient ID"
               value={patientId} onChange={e => setPatientId(e.target.value)} required />
        <input className="border p-2 flex-1" placeholder="Название кейса (опц.)"
               value={name} onChange={e => setName(e.target.value)} />
        <button className="bg-blue-600 text-white px-4 rounded" type="submit">Создать</button>
      </form>

      {loading ? <div>Загрузка...</div> : (
        <div className="bg-white rounded shadow">
          <table className="w-full">
            <thead className="bg-slate-100">
              <tr>
                <th className="text-left p-3">Patient ID</th>
                <th className="text-left p-3">Название</th>
                <th className="text-left p-3">Статус</th>
                <th className="text-left p-3">Диагноз</th>
                <th className="text-left p-3">Создан</th>
                <th className="p-3"></th>
              </tr>
            </thead>
            <tbody>
              {cases.map(c => (
                <tr key={c.id} className="border-t hover:bg-slate-50">
                  <td className="p-3"><Link href={`/cases/${c.id}`} className="text-blue-600">{c.patientId}</Link></td>
                  <td className="p-3">{c.name || "—"}</td>
                  <td className="p-3">
                    <span className={`px-2 py-1 rounded text-xs ${
                      c.status === "SIGNED_OFF" ? "bg-green-100 text-green-800"
                      : "bg-yellow-100 text-yellow-800"
                    }`}>{c.status}</span>
                  </td>
                  <td className="p-3">{c.diagnosis || "—"}</td>
                  <td className="p-3 text-sm text-slate-500">{new Date(c.createdAt).toLocaleString()}</td>
                  <td className="p-3">
                    <button onClick={() => deleteCase(c.id)}
                            className="text-red-600 text-sm">Удалить</button>
                  </td>
                </tr>
              ))}
              {cases.length === 0 && (
                <tr><td colSpan={6} className="p-6 text-center text-slate-500">Кейсов нет</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
