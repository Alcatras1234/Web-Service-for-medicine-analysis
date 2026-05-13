"use client"

import { useEffect, useMemo, useState } from "react"
import { useParams, useRouter } from "next/navigation"
import Link from "next/link"
import { ArrowLeft, FileText, Eye, Download, RefreshCw } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"

type Slide = {
  id: number
  jobId?: string | null
  filename: string
  patientId: string | null
  description: string | null
  status: string
  createdAt: string
  diagnosis?: string | null
  totalEosinophils?: number
  maxHpfCount?: number
  reportReady?: boolean
  caseId?: number | null
  biopsyLocation?: string | null
}

type Case = {
  id: number
  patientId: string
  name: string | null
  status: string
  diagnosis: string | null
  description: string | null
  createdAt: string
}

const LOCATION_LABEL: Record<string, string> = {
  PROXIMAL: "Проксимальный отдел",
  MID: "Средний отдел",
  DISTAL: "Дистальный отдел",
  OTHER: "Другая локация",
}

export default function PatientDetailPage() {
  const params = useParams<{ patientId: string }>()
  const patientId = decodeURIComponent(params.patientId)
  const router = useRouter()
  const [slides, setSlides] = useState<Slide[]>([])
  const [cases, setCases] = useState<Case[]>([])
  const [loading, setLoading] = useState(true)

  async function load() {
    setLoading(true)
    try {
      const [sRes, cRes] = await Promise.all([
        fetch("/api/files/slides", { credentials: "include", cache: "no-store" }),
        fetch("/api/cases",       { credentials: "include", cache: "no-store" }),
      ])
      if (sRes.status === 401) { router.push("/"); return }
      const sJson: Slide[] = sRes.ok ? await sRes.json() : []
      const cJson: Case[]  = cRes.ok ? await cRes.json() : []
      setSlides((Array.isArray(sJson) ? sJson : []).filter(s =>
        (s.patientId?.trim() || "(без ID)") === patientId
      ))
      setCases((Array.isArray(cJson) ? cJson : []).filter(c =>
        (c.patientId?.trim() || "(без ID)") === patientId
      ))
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() }, [patientId])

  // Группируем слайды по кейсу. Слайды без кейса — отдельная группа "Без кейса".
  const groups = useMemo(() => {
    const byCase = new Map<number | "none", Slide[]>()
    for (const s of slides) {
      const key = s.caseId ?? "none"
      if (!byCase.has(key)) byCase.set(key, [])
      byCase.get(key)!.push(s)
    }
    return byCase
  }, [slides])

  const stats = useMemo(() => {
    let maxPec = 0, totalSlides = slides.length, signedCases = 0
    let pos = 0, neg = 0
    for (const s of slides) {
      if ((s.maxHpfCount ?? 0) > maxPec) maxPec = s.maxHpfCount!
      if (s.diagnosis === "POSITIVE") pos++
      if (s.diagnosis === "NEGATIVE") neg++
    }
    for (const c of cases) if (c.status === "SIGNED_OFF") signedCases++
    return { maxPec, totalSlides, signedCases, pos, neg }
  }, [slides, cases])

  const fmt = (iso: string | null | undefined) => {
    if (!iso) return "—"
    try { return new Date(iso).toLocaleString("ru-RU", {
      day: "2-digit", month: "2-digit", year: "numeric",
      hour: "2-digit", minute: "2-digit",
    }) } catch { return iso }
  }

  const openViewer = (slide: Slide) => {
    if (slide.caseId) router.push(`/cases/${slide.caseId}/slides/${slide.id}`)
    else              router.push(`/viewer/${slide.id}`)
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-white border-b border-slate-200 sticky top-0 z-30">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="bg-blue-600 p-2 rounded-lg"><FileText className="h-5 w-5 text-white" /></div>
            <span className="text-xl font-bold text-slate-900 hidden md:block">EosinAI</span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => router.push("/dashboard")}
                    className="text-slate-700">Все исследования</Button>
            <Button variant="ghost" onClick={() => router.push("/cases")}
                    className="text-slate-700">Кейсы</Button>
            <Button variant="ghost" size="icon" onClick={load} title="Обновить">
              <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
            </Button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <Link href="/patients" className="inline-flex items-center gap-1 text-blue-600 hover:underline text-sm mb-3">
          <ArrowLeft className="h-4 w-4" /> К списку пациентов
        </Link>

        <div className="bg-white rounded-xl border border-slate-200 p-6 shadow-sm mb-6">
          <h1 className="text-2xl font-bold text-slate-900">Пациент {patientId}</h1>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-4">
            <Stat label="Слайдов"           value={stats.totalSlides} />
            <Stat label="Кейсов"            value={cases.length} />
            <Stat label="Подписано кейсов"  value={stats.signedCases} />
            <Stat label="Peak intact / HPF" value={stats.maxPec}
                  highlight={stats.maxPec >= 15 ? "red" : "green"} />
          </div>
        </div>

        {loading && slides.length === 0 ? (
          <div className="text-center py-12 text-slate-500">
            <RefreshCw className="h-6 w-6 animate-spin mx-auto mb-2" /> Загрузка...
          </div>
        ) : (
          <div className="space-y-6">
            {Array.from(groups.entries()).map(([key, list]) => {
              const c = key === "none" ? null : cases.find(x => x.id === key)
              return (
                <div key={String(key)} className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                  <div className="px-5 py-3 border-b border-slate-100 bg-slate-50 flex items-center justify-between">
                    <div>
                      <div className="font-semibold text-slate-900">
                        {c ? (c.name || `Кейс #${c.id}`) : "Слайды без кейса"}
                      </div>
                      {c && (
                        <div className="text-xs text-slate-500">
                          Создан {fmt(c.createdAt)}
                          {c.description && ` · ${c.description}`}
                        </div>
                      )}
                    </div>
                    <div className="flex items-center gap-2">
                      {c?.status === "SIGNED_OFF" && (
                        <Badge className="bg-green-100 text-green-700 border-green-200">✓ подписан</Badge>
                      )}
                      {c && (
                        <Link href={`/cases/${c.id}`}
                              className="text-sm text-blue-600 hover:underline">
                          Открыть кейс →
                        </Link>
                      )}
                    </div>
                  </div>

                  <table className="w-full">
                    <thead className="bg-slate-50/50 text-left text-xs text-slate-500 uppercase">
                      <tr>
                        <th className="px-5 py-2">Файл</th>
                        <th className="py-2">Локация</th>
                        <th className="py-2">PEC intact</th>
                        <th className="py-2">Диагноз</th>
                        <th className="py-2">Дата</th>
                        <th className="px-5 py-2 text-right">Действия</th>
                      </tr>
                    </thead>
                    <tbody>
                      {list.map(s => (
                        <tr key={s.id} className="border-t border-slate-100 hover:bg-slate-50/50">
                          <td className="px-5 py-3 text-sm text-slate-700 max-w-[220px] truncate">{s.filename}</td>
                          <td className="py-3 text-sm text-slate-600">
                            {s.biopsyLocation ? LOCATION_LABEL[s.biopsyLocation] || s.biopsyLocation : "—"}
                          </td>
                          <td className="py-3 text-sm font-mono">
                            <span className={(s.maxHpfCount ?? 0) >= 15 ? "text-red-600 font-bold" : "text-slate-700"}>
                              {s.maxHpfCount ?? "—"}
                            </span>
                          </td>
                          <td className="py-3">
                            {s.diagnosis === "POSITIVE" ? (
                              <Badge className="bg-red-100 text-red-700 border-red-200">POSITIVE</Badge>
                            ) : s.diagnosis === "NEGATIVE" ? (
                              <Badge className="bg-emerald-100 text-emerald-700 border-emerald-200">NEGATIVE</Badge>
                            ) : (
                              <Badge variant="outline" className="text-slate-400">PENDING</Badge>
                            )}
                          </td>
                          <td className="py-3 text-sm text-slate-500">{fmt(s.createdAt)}</td>
                          <td className="px-5 py-3 text-right whitespace-nowrap">
                            <Button variant="ghost" size="sm" onClick={() => openViewer(s)}
                                    className="text-blue-600 hover:bg-blue-50"
                                    disabled={!(s.status === "DONE" || s.status === "DONE_WITH_ERRORS")}>
                              <Eye className="h-4 w-4 mr-1" /> Viewer
                            </Button>
                            {s.jobId && (
                              <Button variant="ghost" size="sm"
                                      onClick={() => window.open(`/api/reports/${s.jobId}/pdf`, "_blank")}
                                      className="text-blue-600 hover:bg-blue-50">
                                <Download className="h-4 w-4 mr-1" /> PDF
                              </Button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )
            })}
          </div>
        )}
      </main>
    </div>
  )
}

function Stat({ label, value, highlight }: {
  label: string; value: number | string; highlight?: "red" | "green"
}) {
  const color = highlight === "red"   ? "text-red-600"
              : highlight === "green" ? "text-emerald-600"
              : "text-slate-900"
  return (
    <div>
      <div className="text-xs text-slate-500 uppercase">{label}</div>
      <div className={`text-2xl font-bold ${color}`}>{value}</div>
    </div>
  )
}
