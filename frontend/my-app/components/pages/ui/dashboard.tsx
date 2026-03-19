"use client"

import React, { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import {
  Plus, Search, MoreVertical, FileText,
  Download, Trash2, Eye, LogOut, User, RefreshCw
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

type SlideStatus = "UPLOADED" | "PENDING" | "PROCESSING" | "DONE" | "ERROR"

interface Slide {
  id: number
  filename: string
  patientId: string
  description: string
  status: SlideStatus
  createdAt: string
}

export default function DashboardPage() {
  const router = useRouter()
  const [slides, setSlides] = useState<Slide[]>([])
  const [searchQuery, setSearchQuery] = useState("")
  const [isLoading, setIsLoading] = useState(true)

  const fetchSlides = async () => {
    try {
      setIsLoading(true)
      const res = await fetch("/api/slides")
      if (!res.ok) throw new Error("Ошибка загрузки")
      const data = await res.json()
      setSlides(data)
    } catch {
      toast.error("Не удалось загрузить список исследований")
    } finally {
      setIsLoading(false)
    }
  }

  // Загружаем при монтировании
  useEffect(() => {
    fetchSlides()
  }, [])

  // Автообновление каждые 10 секунд — статус PROCESSING → DONE обновится сам
  useEffect(() => {
    const interval = setInterval(fetchSlides, 10_000)
    return () => clearInterval(interval)
  }, [])

  const filteredSlides = slides.filter(s =>
    (s.patientId ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
    (s.description ?? "").toLowerCase().includes(searchQuery.toLowerCase()) ||
    (s.filename ?? "").toLowerCase().includes(searchQuery.toLowerCase())
  )

  const handleDelete = async (id: number) => {
    // TODO: добавить DELETE /api/slides/:id на бэкенде
    setSlides(slides.filter(s => s.id !== id))
    toast.success("Исследование удалено")
  }

  const formatDate = (iso: string) => {
    if (!iso) return "—"
    return new Date(iso).toLocaleDateString("ru-RU", {
      day: "2-digit", month: "2-digit", year: "numeric"
    })
  }

  const getStatusBadge = (status: SlideStatus) => {
    switch (status) {
      case "DONE":
        return (
          <Badge className="bg-green-100 text-green-700 hover:bg-green-100 border-green-200">
            Готово
          </Badge>
        )
      case "PROCESSING":
        return (
          <Badge className="bg-blue-100 text-blue-700 hover:bg-blue-100 border-blue-200 animate-pulse">
            Обработка...
          </Badge>
        )
      case "PENDING":
      case "UPLOADED":
        return (
          <Badge variant="outline" className="text-slate-500">
            В очереди
          </Badge>
        )
      case "ERROR":
        return <Badge variant="destructive">Ошибка</Badge>
      default:
        return <Badge variant="outline">{status}</Badge>
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">

      {/* HEADER */}
      <header className="bg-white border-b border-slate-200 sticky top-0 z-30">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="bg-blue-600 p-2 rounded-lg">
              <FileText className="h-5 w-5 text-white" />
            </div>
            <span className="text-xl font-bold text-slate-900 hidden md:block">
              Eosin-AI <span className="text-blue-600">Enterprise</span>
            </span>
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="icon"
              onClick={fetchSlides}
              title="Обновить список"
            >
              <RefreshCw className={`h-4 w-4 ${isLoading ? "animate-spin" : ""}`} />
            </Button>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  className="relative h-10 w-10 rounded-full bg-slate-100 hover:bg-slate-200"
                >
                  <User className="h-5 w-5 text-slate-600" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>Профиль</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => router.push("/")}>
                  <LogOut className="mr-2 h-4 w-4" />
                  Выйти
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>

      {/* MAIN */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">

        {/* Заголовок + кнопка */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">Список исследований</h1>
            <p className="text-slate-500">
              Управляйте загруженными слайдами и просматривайте результаты
            </p>
          </div>
          <Button
            onClick={() => router.push("/upload")}
            className="bg-blue-600 hover:bg-blue-700 shadow-md shadow-blue-200"
          >
            <Plus className="mr-2 h-4 w-4" />
            Загрузить новый скан
          </Button>
        </div>

        {/* ПОИСК */}
        <div className="bg-white p-4 rounded-t-xl border border-slate-200 border-b-0 flex items-center gap-3">
          <div className="relative flex-1 max-w-sm">
            <Search className="absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
            <Input
              placeholder="Поиск по ID пациента или имени файла..."
              className="pl-9 bg-slate-50 border-slate-200"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
          <span className="text-sm text-slate-400 ml-auto">
            Обновляется каждые 10 сек
          </span>
        </div>

        {/* ТАБЛИЦА */}
        <div className="bg-white border border-slate-200 rounded-b-xl overflow-hidden shadow-sm">
          <Table>
            <TableHeader className="bg-slate-50">
              <TableRow>
                <TableHead className="w-[60px]">Файл</TableHead>
                <TableHead>ID Пациента</TableHead>
                <TableHead>Имя файла</TableHead>
                <TableHead>Дата</TableHead>
                <TableHead>Статус</TableHead>
                <TableHead className="hidden md:table-cell">Комментарий</TableHead>
                <TableHead className="text-right">Действия</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={7} className="h-24 text-center text-slate-500">
                    <RefreshCw className="h-5 w-5 animate-spin mx-auto mb-2" />
                    Загрузка...
                  </TableCell>
                </TableRow>
              ) : filteredSlides.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="h-24 text-center text-slate-500">
                    {searchQuery
                      ? "По вашему запросу ничего не найдено"
                      : "Исследований пока нет. Загрузите первый скан!"}
                  </TableCell>
                </TableRow>
              ) : (
                filteredSlides.map((slide) => (
                  <TableRow key={slide.id} className="hover:bg-slate-50/50">

                    {/* Иконка файла */}
                    <TableCell>
                      <div className="h-10 w-10 rounded-lg bg-slate-100 border border-slate-200 flex items-center justify-center">
                        <FileText className="h-5 w-5 text-slate-400" />
                      </div>
                    </TableCell>

                    {/* ID Пациента */}
                    <TableCell className="font-medium text-slate-900">
                      {slide.patientId || "—"}
                      <div className="text-xs text-slate-500 font-normal md:hidden">
                        {formatDate(slide.createdAt)}
                      </div>
                    </TableCell>

                    {/* Имя файла */}
                    <TableCell className="text-slate-600 max-w-[180px] truncate">
                      {slide.filename}
                    </TableCell>

                    {/* Дата */}
                    <TableCell className="text-slate-600 hidden md:table-cell">
                      {formatDate(slide.createdAt)}
                    </TableCell>

                    {/* Статус */}
                    <TableCell>
                      {getStatusBadge(slide.status)}
                    </TableCell>

                    {/* Комментарий */}
                    <TableCell className="text-slate-600 hidden md:table-cell truncate max-w-[200px]">
                      {slide.description || "—"}
                    </TableCell>

                    {/* Действия */}
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
                            onClick={() => router.push(`/viewer/${slide.id}`)}
                            disabled={slide.status !== "DONE"}
                          >
                            <Eye className="mr-2 h-4 w-4" />
                            Открыть просмотрщик
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            disabled={slide.status !== "DONE"}
                          >
                            <Download className="mr-2 h-4 w-4" />
                            Скачать отчет PDF
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem
                            className="text-red-600 focus:text-red-600 focus:bg-red-50"
                            onClick={() => handleDelete(slide.id)}
                          >
                            <Trash2 className="mr-2 h-4 w-4" />
                            Удалить
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

        {/* Счётчик */}
        <div className="mt-4 flex items-center justify-between text-sm text-slate-500">
          <div>
            Показано {filteredSlides.length} из {slides.length}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled>Назад</Button>
            <Button variant="outline" size="sm" disabled>Вперед</Button>
          </div>
        </div>

      </main>
    </div>
  )
}
