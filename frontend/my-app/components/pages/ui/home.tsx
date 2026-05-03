"use client"

import React, { useState, useRef } from "react"
import { useRouter } from "next/navigation"
import { toast } from "sonner"
import {
  UploadCloud, FileText, X, AlertCircle, ArrowLeft,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle,
} from "@/components/ui/card"
import { Progress } from "@/components/ui/progress"

interface FileWithPreview extends File {
  preview?: string
}

export default function UploadPage() {
  const router = useRouter()
  const [file, setFile] = useState<FileWithPreview | null>(null)
  const [isDragging, setIsDragging] = useState(false)
  const [uploadProgress, setUploadProgress] = useState(0)
  const [isUploading, setIsUploading] = useState(false)
  const [patientId, setPatientId] = useState("")
  const [description, setDescription] = useState("")
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleDragOver = (e: React.DragEvent) => { e.preventDefault(); setIsDragging(true) }
  const handleDragLeave = (e: React.DragEvent) => { e.preventDefault(); setIsDragging(false) }
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    if (e.dataTransfer.files?.[0]) validateAndSetFile(e.dataTransfer.files[0])
  }
  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files?.[0]) validateAndSetFile(e.target.files[0])
  }

  const validateAndSetFile = (selectedFile: File) => {
    const MAX_SIZE = 10 * 1024 * 1024 * 1024
    if (selectedFile.size > MAX_SIZE) {
      toast.error("Файл слишком большой (макс. 10 ГБ)")
      return
    }
    setFile(selectedFile)
    setUploadProgress(0)
    toast.success("Файл выбран", { description: `${selectedFile.name} готов к загрузке` })
  }

  const handleUpload = async () => {
    if (!file || !patientId) {
      toast.error("Ошибка валидации", {
        description: "Пожалуйста, выберите файл и укажите ID пациента",
      })
      return
    }

    setIsUploading(true)
    setUploadProgress(0)

    try {
      // ── Шаг 1: presigned URL ─────────────────────────────────────────────
      const presignRes = await fetch(
        `/api/files/get-upload-link?filename=${encodeURIComponent(file.name)}`,
        { credentials: "include", cache: "no-store" }
      )
      if (presignRes.status === 401) {
        toast.error("Сессия истекла, войдите заново")
        router.push("/")
        return
      }
      if (!presignRes.ok) {
        const errText = await presignRes.text().catch(() => "")
        throw new Error(`get-upload-link ${presignRes.status}: ${errText}`)
      }
      const { uploadUrl, objectKey } = await presignRes.json()
      if (!uploadUrl || !objectKey) {
        throw new Error("Бэкенд вернул некорректный ответ для presigned URL")
      }

      // ── Шаг 2: PUT в MinIO с прогрессом ─────────────────────────────────
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest()
        xhr.open("PUT", uploadUrl, true)
        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable) {
            setUploadProgress(Math.round((event.loaded / event.total) * 100))
          }
        }
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) resolve()
          else reject(new Error(`MinIO PUT ${xhr.status}: ${xhr.responseText}`))
        }
        xhr.onerror = () => reject(new Error("Сетевая ошибка при загрузке в MinIO"))
        xhr.send(file)
      })

      // ── Шаг 3: confirm-upload ────────────────────────────────────────────
      const confirmRes = await fetch("/api/files/confirm-upload", {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ objectKey, filename: file.name, patientId, description }),
      })
      if (confirmRes.status === 401) {
        toast.error("Сессия истекла, войдите заново")
        router.push("/")
        return
      }
      if (!confirmRes.ok) {
        const errText = await confirmRes.text().catch(() => "")
        throw new Error(`confirm-upload ${confirmRes.status}: ${errText}`)
      }

      toast.success("Загрузка завершена", {
        description: "Исследование отправлено на обработку",
      })
      setTimeout(() => router.push("/dashboard"), 1200)
    } catch (error) {
      console.error("Upload failed:", error)
      toast.error("Ошибка загрузки", {
        description: error instanceof Error ? error.message : "Проверьте сеть",
      })
      setUploadProgress(0)
    } finally {
      setIsUploading(false)
    }
  }

  const removeFile = () => { setFile(null); setUploadProgress(0) }

  return (
    <div className="min-h-screen bg-slate-50 p-4 md:p-8">
      <Button variant="ghost" className="mb-6 hover:bg-slate-200 text-slate-600"
              onClick={() => router.back()}>
        <ArrowLeft className="mr-2 h-4 w-4" />Вернуться к списку
      </Button>

      <div className="max-w-3xl mx-auto animate-in fade-in slide-in-from-bottom-4 duration-500">
        <Card className="shadow-lg border-slate-200 bg-white">
          <CardHeader>
            <CardTitle className="text-2xl text-slate-900">Загрузка нового исследования</CardTitle>
            <CardDescription className="text-slate-500">
              Загрузите цифровой срез (WSI) в формате .svs, .tiff или .ndpi.
              Максимальный размер файла: 10 ГБ.
            </CardDescription>
          </CardHeader>

          <CardContent className="space-y-6">
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="patient-id" className="text-slate-700 font-medium">
                  ID Пациента <span className="text-red-500">*</span>
                </Label>
                <Input id="patient-id" placeholder="Например: PAT-2026-001"
                       value={patientId} onChange={(e) => setPatientId(e.target.value)}
                       className="bg-white border-slate-300 focus:ring-blue-500"
                       disabled={isUploading} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="scan-date" className="text-slate-700 font-medium">
                  Дата забора биоматериала
                </Label>
                <Input id="scan-date" type="date"
                       className="bg-white border-slate-300" disabled={isUploading} />
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="description" className="text-slate-700 font-medium">
                Клинический комментарий
              </Label>
              <Textarea id="description"
                        placeholder="Предварительный диагноз, особенности образца..."
                        value={description}
                        onChange={(e) => setDescription(e.target.value)}
                        className="bg-white border-slate-300 h-24 resize-none focus:ring-blue-500"
                        disabled={isUploading} />
            </div>

            {!file ? (
              <div
                className={`relative border-2 border-dashed rounded-xl p-10 text-center cursor-pointer transition-all duration-300 flex flex-col items-center justify-center gap-4 group ${
                  isDragging
                    ? "border-blue-500 bg-blue-50 scale-[1.01] shadow-inner"
                    : "border-slate-300 hover:border-blue-400 hover:bg-slate-50 bg-white shadow-sm"
                }`}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}>
                <input type="file" ref={fileInputRef} className="hidden"
                       onChange={handleFileSelect}
                       accept=".svs,.tiff,.tif,.ndpi" />
                <div className="p-4 bg-blue-50 rounded-full group-hover:scale-110 transition-transform duration-300">
                  <UploadCloud className="h-10 w-10 text-blue-600" />
                </div>
                <div>
                  <p className="text-lg font-semibold text-slate-700">Перетащите файл сюда</p>
                  <p className="text-sm text-slate-500 mt-1">или нажмите для выбора с устройства</p>
                </div>
              </div>
            ) : (
              <div className="bg-slate-50 border border-slate-200 rounded-xl p-5 animate-in fade-in zoom-in duration-300 relative overflow-hidden">
                {isUploading && (
                  <div className="absolute inset-0 bg-blue-50/50 transition-all duration-300"
                       style={{ width: `${uploadProgress}%` }} />
                )}
                <div className="relative flex items-start justify-between z-10">
                  <div className="flex items-center gap-4">
                    <div className="p-3 bg-white rounded-lg border border-slate-200 shadow-sm">
                      <FileText className="h-8 w-8 text-blue-600" />
                    </div>
                    <div>
                      <p className="font-semibold text-slate-900 text-lg">{file.name}</p>
                      <p className="text-sm text-slate-500 font-medium">
                        {(file.size / (1024 * 1024)).toFixed(2)} MB • {file.type || "WSI Image"}
                      </p>
                    </div>
                  </div>
                  {!isUploading && (
                    <Button variant="ghost" size="icon"
                            className="text-slate-400 hover:text-red-600 hover:bg-red-50"
                            onClick={removeFile}>
                      <X className="h-5 w-5" />
                    </Button>
                  )}
                </div>
                {isUploading && (
                  <div className="mt-4 space-y-2 relative z-10">
                    <div className="flex justify-between text-xs text-blue-700 font-bold uppercase tracking-wider">
                      <span>Загрузка на сервер</span><span>{uploadProgress}%</span>
                    </div>
                    <Progress value={uploadProgress} className="h-2 bg-slate-200"
                              indicatorClassName="bg-blue-600" />
                  </div>
                )}
              </div>
            )}
          </CardContent>

          <CardFooter className="flex justify-between bg-slate-50/50 border-t p-6 rounded-b-xl">
            <Button variant="outline" onClick={() => router.back()} disabled={isUploading}
                    className="border-slate-300 text-slate-700">Отмена</Button>
            <Button className="bg-blue-600 hover:bg-blue-700 text-white shadow-md shadow-blue-200 min-w-[160px] transition-all active:scale-95"
                    onClick={handleUpload} disabled={!file || !patientId || isUploading}>
              {isUploading ? (
                <><div className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />Загрузка...</>
              ) : (
                <><UploadCloud className="mr-2 h-4 w-4" />Начать анализ</>
              )}
            </Button>
          </CardFooter>
        </Card>

        <div className="mt-6 flex gap-3 p-4 bg-amber-50 text-amber-900 rounded-lg border border-amber-200 text-sm shadow-sm">
          <AlertCircle className="h-5 w-5 shrink-0 text-amber-600" />
          <p>Загрузка файлов более 5 ГБ может занять некоторое время. Не закрывайте вкладку.</p>
        </div>
      </div>
    </div>
  )
}