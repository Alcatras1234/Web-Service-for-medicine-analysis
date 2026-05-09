"use client"

import { useEffect, useState } from "react"
import { useParams } from "next/navigation"
import Link from "next/link"

type Slide = {
  id: number
  filename: string
  biopsyLocation: string | null
  status: string
  jobId?: string
  jobStatus?: string
  diagnosis?: string
  totalEosinophils?: number
  maxHpfCount?: number
  modelVersion?: string
}

type CaseDetail = {
  id: number
  patientId: string
  name: string | null
  status: string
  diagnosis: string | null
  slides: Slide[]
  signoff?: {
    id: number
    diagnosis: string
    comments: string | null
    signedAt: string
    userId: number
  }
}

export default function CaseDetailPage() {
  const params = useParams<{ id: string }>()
  const id = params.id
  const [data, setData] = useState<CaseDetail | null>(null)
  const [diagnosis, setDiagnosis] = useState("POSITIVE")
  const [comments, setComments] = useState("")

  async function load() {
    const res = await fetch(`/api/cases/${id}`)
    if (res.ok) setData(await res.json())
  }
  useEffect(() => { load() }, [id])

  async function signoff() {
    if (!confirm(`Подписать кейс с диагнозом ${diagnosis}?`)) return
    const res = await fetch(`/api/cases/${id}/signoff`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ diagnosis, comments }),
    })
    if (res.ok) load()
    else alert("Ошибка подписи: " + (await res.text()))
  }

  if (!data) return <div className="p-6">Загрузка...</div>

  const signed = data.status === "SIGNED_OFF"

  return (
    <div className="min-h-screen bg-slate-50 p-6">
      <Link href="/cases" className="text-blue-600 text-sm">← К списку кейсов</Link>
      <h1 className="text-2xl font-bold mt-2 mb-1">{data.name || data.patientId}</h1>
      <div className="text-slate-500 mb-4">
        Patient: {data.patientId} • Status: {data.status}
        {data.diagnosis && ` • Diagnosis: ${data.diagnosis}`}
      </div>

      {!signed && (
        <div className="mb-6 p-3 bg-amber-50 border border-amber-200 rounded text-sm text-amber-900">
          ⚠️ <b>Decision-support tool.</b> Результаты автоматического анализа носят
          вспомогательный характер. Окончательный диагноз ставит врач-патоморфолог
          после визуальной верификации.
        </div>
      )}

      <div className="bg-white rounded shadow mb-6">
        <table className="w-full">
          <thead className="bg-slate-100">
            <tr>
              <th className="text-left p-3">Файл</th>
              <th className="text-left p-3">Локация</th>
              <th className="text-left p-3">Статус</th>
              <th className="text-left p-3">Эозинофилов</th>
              <th className="text-left p-3">Макс HPF</th>
              <th className="text-left p-3">Диагноз</th>
              <th className="text-left p-3">Модель</th>
              <th className="p-3"></th>
            </tr>
          </thead>
          <tbody>
            {data.slides.map(s => (
              <tr key={s.id} className="border-t">
                <td className="p-3">{s.filename}</td>
                <td className="p-3">{s.biopsyLocation || "—"}</td>
                <td className="p-3">{s.jobStatus || s.status}</td>
                <td className="p-3">{s.totalEosinophils ?? "—"}</td>
                <td className="p-3">{s.maxHpfCount ?? "—"}</td>
                <td className="p-3">{s.diagnosis || "—"}</td>
                <td className="p-3 text-xs text-slate-500">{s.modelVersion || "—"}</td>
                <td className="p-3 whitespace-nowrap">
                  <Link href={`/cases/${id}/slides/${s.id}`}
                        className="text-blue-600 text-sm mr-3">Посмотреть</Link>
                  {s.jobId && (
                    <>
                      <a href={`/api/reports/${s.jobId}/pdf`} target="_blank" rel="noreferrer"
                         className="text-blue-600 text-sm mr-3">PDF</a>
                      <a href={`/api/reports/${s.jobId}/heatmap`} target="_blank" rel="noreferrer"
                         className="text-blue-600 text-sm">Heatmap</a>
                    </>
                  )}
                </td>
              </tr>
            ))}
            {data.slides.length === 0 && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">Слайдов нет</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {data.signoff ? (
        <div className="bg-green-50 border border-green-200 rounded p-4">
          <div className="font-semibold text-green-900">Кейс подписан</div>
          <div className="text-sm mt-1">Диагноз: <b>{data.signoff.diagnosis}</b></div>
          <div className="text-sm">Подписан: {new Date(data.signoff.signedAt).toLocaleString()}</div>
          {data.signoff.comments && <div className="text-sm mt-2">{data.signoff.comments}</div>}
        </div>
      ) : !signed && (
        <div className="bg-white rounded shadow p-4">
          <h3 className="font-semibold mb-2">Подписать кейс (sign-off)</h3>
          <div className="flex gap-2 items-end">
            <select value={diagnosis} onChange={e => setDiagnosis(e.target.value)} className="border p-2">
              <option value="POSITIVE">POSITIVE</option>
              <option value="NEGATIVE">NEGATIVE</option>
              <option value="INCONCLUSIVE">INCONCLUSIVE</option>
            </select>
            <input className="border p-2 flex-1" placeholder="Комментарий"
                   value={comments} onChange={e => setComments(e.target.value)} />
            <button onClick={signoff} className="bg-green-600 text-white px-4 py-2 rounded">
              Подписать
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
