"use client"

import { useEffect, useState, useMemo } from "react"
import { useRouter } from "next/navigation"
import { Search, RefreshCw, FileText, ArrowRight, AlertCircle } from "lucide-react"
import { Input } from "@/components/ui/input"
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
}

type PatientCard = {
  patientId: string
  slideCount: number
  caseCount: number
  signedOffCount: number
  maxPec: number
  diagnosis: string | null   // POSITIVE если хоть один слайд POSITIVE
  lastActivity: string | null
  cases: Case[]
}

export default function PatientsListPage() {
  const router = useRouter()
  const [slides, setSlides] = useState<Slide[]>([])
  const [cases, setCases] = useState<Case[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState("")

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
      setSlides(Array.isArray(sJson) ? sJson : [])
      setCases(Array.isArray(cJson) ? cJson : [])
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { load() }, [])

  // Группируем по patientId
  const patients = useMemo<PatientCard[]>(() => {
    const map = new Map<string, PatientCard>()
    for (const s of slides) {
      const pid = s.patientId?.trim() || "(без ID)"
      let p = map.get(pid)
      if (!p) {
        p = {
          patientId: pid, slideCount: 0, caseCount: 0, signedOffCount: 0,
          maxPec: 0, diagnosis: null, lastActivity: null, cases: [],
        }
        map.set(pid, p)
      }
      p.slideCount += 1
      const pec = s.maxHpfCount ?? 0
      if (pec > p.maxPec) p.maxPec = pec
      if (s.diagnosis === "POSITIVE") p.diagnosis = "POSITIVE"
      else if (!p.diagnosis && s.diagnosis === "NEGATIVE") p.diagnosis = "NEGATIVE"
      if (!p.lastActivity || (s.createdAt && s.createdAt > p.lastActivity)) {
        p.lastActivity = s.createdAt
      }
    }
    // Cases per patient
    for (const c of cases) {
      const p = map.get(c.patientId?.trim() || "(без ID)")
      if (!p) continue
      p.cases.push(c)
      p.caseCount += 1
      if (c.status === "SIGNED_OFF") p.signedOffCount += 1
    }
    return Array.from(map.values()).sort((a, b) =>
      (b.lastActivity ?? "").localeCompare(a.lastActivity ?? "")
    )
  }, [slides, cases])

  const filtered = patients.filter(p =>
    p.patientId.toLowerCase().includes(query.toLowerCase())
  )

  const fmt = (iso: string | null) => {
    if (!iso) return "—"
    try { return new Date(iso).toLocaleDateString("ru-RU", {
      day: "2-digit", month: "2-digit", year: "numeric",
    }) } catch { return iso }
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
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-slate-900">Карточки пациентов</h1>
          <p className="text-slate-500">Сводка по каждому пациенту: исследования, кейсы, диагноз</p>
        </div>

        <div className="flex items-center gap-3 mb-6">
          <div className="relative flex-1 max-w-sm">
            <Search className="absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
            <Input placeholder="Поиск по ID пациента..."
                   className="pl-9 bg-white"
                   value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <span className="text-sm text-slate-500 ml-auto">
            Всего: <b className="text-slate-900">{patients.length}</b> пациентов
          </span>
        </div>

        {loading && patients.length === 0 ? (
          <div className="text-center py-20 text-slate-500">
            <RefreshCw className="h-6 w-6 animate-spin mx-auto mb-2" /> Загрузка...
          </div>
        ) : filtered.length === 0 ? (
          <div className="text-center py-20 text-slate-500 bg-white rounded-xl border border-slate-200">
            <AlertCircle className="h-8 w-8 mx-auto mb-3 text-slate-400" />
            {query ? "Ничего не найдено" : "Пациентов пока нет"}
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {filtered.map(p => (
              <button key={p.patientId}
                      onClick={() => router.push(`/patients/${encodeURIComponent(p.patientId)}`)}
                      className="text-left bg-white border border-slate-200 rounded-xl p-5 shadow-sm hover:shadow-md hover:border-blue-300 transition-all group">
                <div className="flex items-start justify-between mb-3">
                  <div>
                    <div className="font-bold text-slate-900 text-lg group-hover:text-blue-700 transition-colors">
                      {p.patientId}
                    </div>
                    <div className="text-xs text-slate-500 mt-0.5">
                      Последняя активность: {fmt(p.lastActivity)}
                    </div>
                  </div>
                  <ArrowRight className="h-5 w-5 text-slate-300 group-hover:text-blue-600 group-hover:translate-x-0.5 transition-all" />
                </div>

                <div className="flex items-center gap-2 text-sm text-slate-600 mb-3">
                  <Badge variant="outline" className="bg-slate-50">
                    {p.slideCount} слайд{p.slideCount > 1 ? "ов" : ""}
                  </Badge>
                  <Badge variant="outline" className="bg-slate-50">
                    {p.caseCount} кейс{p.caseCount === 1 ? "" : (p.caseCount < 5 && p.caseCount > 0 ? "а" : "ов")}
                  </Badge>
                  {p.signedOffCount > 0 && (
                    <Badge className="bg-green-100 text-green-700 border-green-200">
                      ✓ подписано
                    </Badge>
                  )}
                </div>

                <div className="flex items-center justify-between pt-3 border-t border-slate-100">
                  <div>
                    <div className="text-xs text-slate-500">Peak intact / HPF</div>
                    <div className={`text-2xl font-bold ${p.maxPec >= 15 ? "text-red-600" : "text-emerald-600"}`}>
                      {p.maxPec}
                    </div>
                  </div>
                  {p.diagnosis === "POSITIVE" ? (
                    <Badge className="bg-red-100 text-red-700 border-red-200">EoE подтверждён</Badge>
                  ) : p.diagnosis === "NEGATIVE" ? (
                    <Badge className="bg-emerald-100 text-emerald-700 border-emerald-200">EoE не выявлен</Badge>
                  ) : (
                    <Badge variant="outline" className="text-slate-400">в обработке</Badge>
                  )}
                </div>
              </button>
            ))}
          </div>
        )}
      </main>
    </div>
  )
}
