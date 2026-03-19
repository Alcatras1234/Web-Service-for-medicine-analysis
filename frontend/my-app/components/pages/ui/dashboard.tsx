"use client"

import React, { useState } from "react"
import { useRouter } from "next/navigation"
import { 
  Plus, 
  Search, 
  MoreVertical, 
  FileText, 
  Download, 
  Trash2, 
  Eye,
  LogOut,
  User
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { toast } from "sonner"

// Тип данных исследования
type StudyStatus = "processing" | "completed" | "error" | "queued"

interface Study {
  id: string
  patientId: string
  date: string
  previewUrl?: string // URL миниатюры
  status: StudyStatus
  description: string
  fileName: string
}

// Моковые данные (пока нет бэкенда)
const MOCK_DATA: Study[] = [
  {
    id: "1",
    patientId: "PAT-2026-001",
    date: "29.01.2026",
    status: "completed",
    description: "Подозрение на эозинофилию",
    fileName: "slide_001.svs"
  },
  {
    id: "2",
    patientId: "PAT-2026-004",
    date: "29.01.2026",
    status: "processing",
    description: "Плановый осмотр",
    fileName: "biopsy_XY.tiff"
  },
  {
    id: "3",
    patientId: "PAT-2025-892",
    date: "28.01.2026",
    status: "error",
    description: "Ошибка формата файла",
    fileName: "unknown.jpg"
  },
  {
    id: "4",
    patientId: "PAT-2025-888",
    date: "27.01.2026",
    status: "completed",
    description: "Контрольный срез",
    fileName: "slide_v2.svs"
  },
]

export default function DashboardPage() {
  const router = useRouter()
  const [studies, setStudies] = useState<Study[]>(MOCK_DATA)
  const [searchQuery, setSearchQuery] = useState("")

  // Фильтрация поиска
  const filteredStudies = studies.filter(study => 
    study.patientId.toLowerCase().includes(searchQuery.toLowerCase()) ||
    study.description.toLowerCase().includes(searchQuery.toLowerCase())
  )

  const handleDelete = (id: string) => {
    // Тут будет запрос к API
    setStudies(studies.filter(s => s.id !== id))
    toast.success("Исследование удалено")
  }

  const getStatusBadge = (status: StudyStatus) => {
    switch (status) {
      case "completed":
        return <Badge className="bg-green-100 text-green-700 hover:bg-green-100 border-green-200">Готово</Badge>
      case "processing":
        return <Badge className="bg-blue-100 text-blue-700 hover:bg-blue-100 border-blue-200 animate-pulse">Обработка...</Badge>
      case "queued":
        return <Badge variant="outline" className="text-slate-500">В очереди</Badge>
      case "error":
        return <Badge variant="destructive">Ошибка</Badge>
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      
      {/* ВЕРХНЯЯ ПАНЕЛЬ (Header) */}
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

          <div className="flex items-center gap-4">
            {/* Меню профиля */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" className="relative h-10 w-10 rounded-full bg-slate-100 hover:bg-slate-200">
                  <User className="h-5 w-5 text-slate-600" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>Др. Иван Петров</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => router.push('/')}>
                  <LogOut className="mr-2 h-4 w-4" />
                  Выйти
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>

      {/* ОСНОВНОЙ КОНТЕНТ */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        
        {/* Заголовок и кнопки действий */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">Список исследований</h1>
            <p className="text-slate-500">Управляйте загруженными слайдами и просматривайте результаты</p>
          </div>
          <Button 
            onClick={() => router.push('/upload')} 
            className="bg-blue-600 hover:bg-blue-700 shadow-md shadow-blue-200"
          >
            <Plus className="mr-2 h-4 w-4" />
            Загрузить новый скан
          </Button>
        </div>

        {/* Панель фильтров */}
        <div className="bg-white p-4 rounded-t-xl border border-slate-200 border-b-0 flex items-center gap-3">
          <div className="relative flex-1 max-w-sm">
            <Search className="absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
            <Input 
              placeholder="Поиск по ID пациента..." 
              className="pl-9 bg-slate-50 border-slate-200"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        {/* ТАБЛИЦА */}
        <div className="bg-white border border-slate-200 rounded-b-xl overflow-hidden shadow-sm">
          <Table>
            <TableHeader className="bg-slate-50">
              <TableRow>
                <TableHead className="w-[100px]">Превью</TableHead>
                <TableHead>ID Пациента</TableHead>
                <TableHead>Дата</TableHead>
                <TableHead>Статус</TableHead>
                <TableHead className="hidden md:table-cell">Комментарий</TableHead>
                <TableHead className="text-right">Действия</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredStudies.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="h-24 text-center text-slate-500">
                    Исследований не найдено
                  </TableCell>
                </TableRow>
              ) : (
                filteredStudies.map((study) => (
                  <TableRow key={study.id} className="hover:bg-slate-50/50">
                    {/* Превью (Заглушка) */}
                    <TableCell>
                      <div className="h-12 w-12 rounded-lg bg-slate-100 border border-slate-200 flex items-center justify-center overflow-hidden">
                         {/* В будущем тут будет <img src={study.previewUrl} /> */}
                         <FileText className="h-6 w-6 text-slate-400" />
                      </div>
                    </TableCell>
                    
                    <TableCell className="font-medium text-slate-900">
                      {study.patientId}
                      <div className="text-xs text-slate-500 font-normal md:hidden">{study.date}</div>
                    </TableCell>
                    
                    <TableCell className="text-slate-600 hidden md:table-cell">
                      {study.date}
                    </TableCell>
                    
                    <TableCell>{getStatusBadge(study.status)}</TableCell>
                    
                    <TableCell className="text-slate-600 hidden md:table-cell truncate max-w-[200px]">
                      {study.description}
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
                          <DropdownMenuItem onClick={() => router.push(`/viewer/${study.id}`)}>
                            <Eye className="mr-2 h-4 w-4" />
                            Открыть просмотрщик
                          </DropdownMenuItem>
                          <DropdownMenuItem>
                            <Download className="mr-2 h-4 w-4" />
                            Скачать отчет PDF
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem 
                            className="text-red-600 focus:text-red-600 focus:bg-red-50"
                            onClick={() => handleDelete(study.id)}
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
        
        {/* Пагинация (простая заглушка) */}
        <div className="mt-4 flex items-center justify-between text-sm text-slate-500">
          <div>Показано {filteredStudies.length} из {studies.length}</div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled>Назад</Button>
            <Button variant="outline" size="sm" disabled>Вперед</Button>
          </div>
        </div>

      </main>
    </div>
  )
}
