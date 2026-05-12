"use client"

import React, { useState, useEffect, useCallback } from "react"
import { useRouter } from "next/navigation"
import {
  Plus, Search, MoreVertical, FileText,
  Download, Trash2, Eye, LogOut, User, RefreshCw,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import {
  Table, TableBody, TableCell,
  TableHead, TableHeader, TableRow,
} from "@/components/ui/table"
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { toast } from "sonner"

type SlideStatus =
  | "UPLOADED" | "PENDING" | "PROCESSING" | "FINALIZING"
  | "DONE" | "DONE_WITH_ERRORS" | "FAILED" | "ERROR"

interface Slide {
  id: number
  jobId?: string | null
  filename: string
  patientId: string
  description: string
  status: SlideStatus
  createdAt: string
  diagnosis?: string | null
  totalEosinophils?: number
  maxHpfCount?: number
  reportReady?: boolean
  caseId?: number | null
  biopsyLocation?: string | null
}

export default function DashboardPage() {
  const router = useRouter()
  const [slides, setSlides] = useState<Slide[]>([])
  const [searchQuery, setSearchQuery] = useState("")
  const [isLoading, setIsLoading] = useState(true)

  const fetchSlides = useCallback(async () => {
    try {
      setIsLoading(true)
      const res = await fetch("/api/files/slides", {
        credentials: "include",
        cache: "no-store",
      })
      if (res.status === 401) {
        toast.error("Сессия истекла, войдите заново")
        router.push("/")
        return
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data: Slide[] = await res.json()
      setSlides(Array.isArray(data) ? data : [])
    } catch (e) {
      console.error(e)
      toast.error("Не удалось загрузить список исследований")
    } finally {
      setIsLoading(false)
    }
  }, [router])

  useEffect(() => { fetchSlides() }, [fetchSlides])

  // Авто-обновление каждые 5 секунд, если есть незавершённые джобы
  useEffect(() => {
    const hasPending = slides.some(s =>
      ["PENDING", "PROCESSING", "FINALIZING", "UPLOADED"].includes(s.status)
    )
    const interval = setInterval(fetchSlides, hasPending ? 5_000 : 30_000)
    return () => clearInterval(interval)
  }, [slides, fetchSlides])

  const filteredSlides = slides.filter(s =>
    (s.patientId ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
    (s.description ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
    (s.filename ?? "").toLowerCase().includes(searchQuery.toLowerCase())
  )

  const handleDelete = async (id: number) => {
    if (!confirm("Удалить это исследование? (soft-delete, файл в MinIO останется для аудита)")) return
    try {
      const res = await fetch(`/api/files/slides/${id}`, {
        method: "DELETE",
        credentials: "include",
      })
      if (res.status === 409) {
        toast.error("Нельзя удалить — кейс уже подписан")
        return
      }
      if (!res.ok) {
        toast.error(`Ошибка удаления: HTTP ${res.status}`)
        return
      }
      setSlides(slides.filter(s => s.id !== id))
      toast.success("Исследование удалено")
    } catch (e) {
      console.error(e)
      toast.error("Сетевая ошибка при удалении")
    }
  }

  const openViewer = (slide: Slide) => {
    // Если слайд в кейсе — открываем кейс-вьюер, иначе самостоятельный
    if (slide.caseId) {
      router.push(`/cases/${slide.caseId}/slides/${slide.id}`)
    } else {
      router.push(`/viewer/${slide.id}`)
    }
  }

  const handleDownloadPdf = (slide: Slide) => {
    if (!slide.jobId) {
      toast.error("Отчёт ещё не готов")
      return
    }
    // Простое решение — открываем прокси-URL в новой вкладке.
    // Браузер сам пришлёт cookie (same-origin), Next.js прокси прокинет Bearer на бек,
    // бек отдаст PDF с Content-Disposition: attachment → браузер его скачает.
    window.open(`/api/reports/${slide.jobId}/pdf`, "_blank")
  }

  const handleRegenerateReport = async (slide: Slide) => {
    if (!slide.jobId) return
    try {
      const res = await fetch(`/api/reports/${slide.jobId}/regenerate`, {
        method: "POST",
        credentials: "include",
      })
      if (res.status === 409) {
        toast.error("Кейс подписан — регенерация запрещена")
        return
      }
      if (!res.ok) {
        toast.error(`Ошибка: HTTP ${res.status}`)
        return
      }
      toast.success("Регенерация запущена. Подождите ~30 сек и обновите список.")
    } catch (e) {
      console.error(e)
      toast.error("Сетевая ошибка")
    }
  }

  const handleLogout = () => {
    document.cookie = "token=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/"
    router.push("/")
  }

  const formatDate = (iso: string) => {
    if (!iso) return "—"
    return new Date(iso).toLocaleDateString("ru-RU", {
      day: "2-digit", month: "2-digit", year: "numeric",
    })
  }

  const getStatusBadge = (status: SlideStatus) => {
    switch (status) {
      case "DONE":
      case "DONE_WITH_ERRORS":
        return <Badge className="bg-green-100 text-green-700 hover:bg-green-100 border-green-200">Готово</Badge>
      case "PROCESSING":
      case "FINALIZING":
        return <Badge className="bg-blue-100 text-blue-700 hover:bg-blue-100 border-blue-200 animate-pulse">Обработка...</Badge>
      case "PENDING":
      case "UPLOADED":
        return <Badge variant="outline" className="text-slate-500">В очереди</Badge>
      case "FAILED":
      case "ERROR":
        return <Badge variant="destructive">Ошибка</Badge>
      default:
        return <Badge variant="outline">{status}</Badge>
    }
  }

  const isDone = (s: Slide) => s.status === "DONE" || s.status === "DONE_WITH_ERRORS"

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-white border-b border-slate-200 sticky top-0 z-30">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="bg-blue-600 p-2 rounded-lg"><FileText className="h-5 w-5 text-white" /></div>
            <span className="text-xl font-bold text-slate-900 hidden md:block">
              Eosin-AI <span className="text-blue-600">Enterprise</span>
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => router.push("/cases")}
                    className="text-slate-700 hover:text-blue-700">
              Кейсы
            </Button>
            <Button variant="ghost" size="icon" onClick={fetchSlides} title="Обновить список">
              <RefreshCw className={`h-4 w-4 ${isLoading ? "animate-spin" : ""}`} />
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" className="relative h-10 w-10 rounded-full bg-slate-100 hover:bg-slate-200">
                  <User className="h-5 w-5 text-slate-600" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>Профиль</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={handleLogout}>
                  <LogOut className="mr-2 h-4 w-4" />Выйти
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">Список исследований</h1>
            <p className="text-slate-500">Управляйте загруженными слайдами и просматривайте результаты</p>
          </div>
          <Button onClick={() => router.push("/upload")}
                  className="bg-blue-600 hover:bg-blue-700 shadow-md shadow-blue-200">
            <Plus className="mr-2 h-4 w-4" />Загрузить новый скан
          </Button>
        </div>

        <div className="bg-white p-4 rounded-t-xl border border-slate-200 border-b-0 flex items-center gap-3">
          <div className="relative flex-1 max-w-sm">
            <Search className="absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
            <Input placeholder="Поиск по ID пациента или имени файла..."
                   className="pl-9 bg-slate-50 border-slate-200"
                   value={searchQuery}
                   onChange={(e) => setSearchQuery(e.target.value)} />
          </div>
          <span className="text-sm text-slate-400 ml-auto">
            Авто-обновление каждые 5 секунд при активной обработке
          </span>
        </div>

        <div className="bg-white border border-slate-200 rounded-b-xl overflow-hidden shadow-sm">
          <Table>
            <TableHeader className="bg-slate-50">
              <TableRow>
                <TableHead className="w-[60px]">Файл</TableHead>
                <TableHead>ID Пациента</TableHead>
                <TableHead>Имя файла</TableHead>
                <TableHead>Дата</TableHead>
                <TableHead>Статус</TableHead>
                <TableHead className="hidden md:table-cell">Диагноз</TableHead>
                <TableHead className="hidden lg:table-cell">Peak eos/HPF</TableHead>
                <TableHead className="text-right">Действия</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && slides.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="h-24 text-center text-slate-500">
                    <RefreshCw className="h-5 w-5 animate-spin mx-auto mb-2" />Загрузка...
                  </TableCell>
                </TableRow>
              ) : filteredSlides.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className="h-24 text-center text-slate-500">
                    {searchQuery ? "По вашему запросу ничего не найдено" : "Исследований пока нет"}
                  </TableCell>
                </TableRow>
              ) : (
                filteredSlides.map((slide) => (
                  <TableRow key={slide.id} className="hover:bg-slate-50/50">
                    <TableCell>
                      <div className="h-10 w-10 rounded-lg bg-slate-100 border border-slate-200 flex items-center justify-center">
                        <FileText className="h-5 w-5 text-slate-400" />
                      </div>
                    </TableCell>
                    <TableCell className="font-medium text-slate-900">
                      {slide.patientId || "—"}
                    </TableCell>
                    <TableCell className="text-slate-600 max-w-[180px] truncate">
                      {slide.filename}
                    </TableCell>
                    <TableCell className="text-slate-600">
                      {formatDate(slide.createdAt)}
                    </TableCell>
                    <TableCell>{getStatusBadge(slide.status)}</TableCell>
                    <TableCell className="hidden md:table-cell">
                      {isDone(slide) && slide.diagnosis ? (
                        <Badge className={
                          slide.diagnosis === "POSITIVE"
                            ? "bg-red-100 text-red-700 border-red-200"
                            : "bg-emerald-100 text-emerald-700 border-emerald-200"
                        }>
                          {slide.diagnosis === "POSITIVE" ? "ПОЗИТИВНЫЙ" : "НЕГАТИВНЫЙ"}
                        </Badge>
                      ) : (
                        <Badge variant="outline" className="text-slate-400">PENDING</Badge>
                      )}
                    </TableCell>
                    <TableCell className="hidden lg:table-cell font-mono text-sm">
                      {isDone(slide) && typeof slide.maxHpfCount === "number" ? (
                        <span className={slide.maxHpfCount >= 15 ? "text-red-600 font-bold" : "text-slate-600"}>
                          {slide.maxHpfCount}
                        </span>
                      ) : "—"}
                    </TableCell>
                    <TableCell className="text-right">
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="h-8 w-8">
                            <MoreVertical className="h-4 w-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuLabel>Действия</DropdownMenuLabel>
                          <DropdownMenuItem
                            onClick={() => openViewer(slide)}
                            disabled={!isDone(slide)}>
                            <Eye className="mr-2 h-4 w-4" />Открыть просмотрщик
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            disabled={!isDone(slide)}
                            onClick={() => handleDownloadPdf(slide)}>
                            <Download className="mr-2 h-4 w-4" />Скачать отчёт PDF
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            disabled={!isDone(slide)}
                            onClick={() => handleRegenerateReport(slide)}>
                            <RefreshCw className="mr-2 h-4 w-4" />Пересоздать отчёт
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem
                            className="text-red-600 focus:text-red-600 focus:bg-red-50"
                            onClick={() => handleDelete(slide.id)}>
                            <Trash2 className="mr-2 h-4 w-4" />Удалить
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>

        <div className="mt-4 flex items-center justify-between text-sm text-slate-500">
          <div>Показано {filteredSlides.length} из {slides.length}</div>
        </div>
      </main>
    </div>
  )
}