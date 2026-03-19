"use client"

import React, { useState, useRef } from "react"
import { useRouter } from "next/navigation"
import { toast } from "sonner"
import { 
  UploadCloud, 
  FileText, 
  X, 
  AlertCircle, 
  ArrowLeft 
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Progress } from "@/components/ui/progress"

interface FileWithPreview extends File {
  preview?: string;
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

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(true)
  }

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      validateAndSetFile(e.dataTransfer.files[0])
    }
  }

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      validateAndSetFile(e.target.files[0])
    }
  }

  const validateAndSetFile = (selectedFile: File) => {
    // Пример валидации размера (10 ГБ)
    const MAX_SIZE = 10 * 1024 * 1024 * 1024;
    if (selectedFile.size > MAX_SIZE) { 
       toast.error("Файл слишком большой (макс. 10 ГБ)")
       return
    }
    
    setFile(selectedFile)
    setUploadProgress(0)
    toast.success("Файл выбран", {
      description: `${selectedFile.name} готов к загрузке`
    })
  }

  const handleUpload = async () => {
    if (!file || !patientId) {
      toast.error("Ошибка валидации", {
        description: "Пожалуйста, выберите файл и укажите ID пациента"
      })
      return
    }
    
    setIsUploading(true)
    setUploadProgress(0)

    try {
      // ------------------------------------------------------------------
      // ШАГ 1: Получаем Presigned URL от нашего бэкенда
      // ------------------------------------------------------------------
      // Предполагаем, что контроллер доступен по /api/files/get-upload-link
      // и возвращает JSON: { uploadUrl: string, filename: string }
      
      const presignRes = await fetch(`/api/files/get-upload-link?filename=${encodeURIComponent(file.name)}`);
      
      if (!presignRes.ok) {
        throw new Error('Не удалось получить ссылку для загрузки');
      }
      
      const { uploadUrl, objectKey } = await presignRes.json();


      // ------------------------------------------------------------------
      // ШАГ 2: Грузим файл напрямую в MinIO через XMLHttpRequest (для прогресса)
      // ------------------------------------------------------------------
      // Используем XHR вместо fetch, чтобы отслеживать onprogress
      
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', uploadUrl, true);
        
        // Важно: не ставим лишних заголовков (Authorization и т.д.), 
        // иначе подпись URL может не совпасть. Content-Type часто нужен.
        // Если ваш Presigned URL генерировался без учета Content-Type, можно убрать.
        // Но обычно лучше указывать реальный тип.
        // xhr.setRequestHeader('Content-Type', file.type); 

        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable) {
            const percentComplete = Math.round((event.loaded / event.total) * 100);
            setUploadProgress(percentComplete);
          }
        };

        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            resolve();
          } else {
            reject(new Error(`Ошибка загрузки в MinIO: ${xhr.statusText}`));
          }
        };

        xhr.onerror = () => reject(new Error('Сетевая ошибка при загрузке'));
        
        xhr.send(file);
      });

      // ------------------------------------------------------------------
      // ШАГ 3 (Опционально): Сообщаем бэкенду, что загрузка успешна,
      // чтобы он сохранил запись в БД (ID пациента, имя файла в бакете и т.д.)
      // ------------------------------------------------------------------
      
      await fetch(`/api/files/confirm-upload`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          objectKey,
          filename: file.name,
          patientId,
          description,
        })
      });


      toast.success("Загрузка завершена", {
        description: "Исследование отправлено на обработку",
        action: {
          label: "К списку",
          onClick: () => router.push('/dashboard'),
        },
      })

      setTimeout(() => {
        router.push('/dashboard')
      }, 1500)

    } catch (error) {
      console.error(error);
      toast.error("Ошибка загрузки", {
        description: "Не удалось отправить файл. Проверьте сеть или повторите попытку."
      })
      setUploadProgress(0) // Сброс прогресса при ошибке
    } finally {
      setIsUploading(false)
    }
  }

  const removeFile = () => {
    setFile(null)
    setUploadProgress(0)
  }

  return (
    <div className="min-h-screen bg-slate-50 p-4 md:p-8">
      <Button 
        variant="ghost" 
        className="mb-6 hover:bg-slate-200 text-slate-600"
        onClick={() => router.back()}
      >
        <ArrowLeft className="mr-2 h-4 w-4" />
        Вернуться к списку
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
            
            {/* Метаданные */}
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="patient-id" className="text-slate-700 font-medium">ID Пациента <span className="text-red-500">*</span></Label>
                <Input 
                  id="patient-id" 
                  placeholder="Например: PAT-2026-001" 
                  value={patientId}
                  onChange={(e) => setPatientId(e.target.value)}
                  className="bg-white border-slate-300 focus:ring-blue-500"
                  disabled={isUploading}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="scan-date" className="text-slate-700 font-medium">Дата забора биоматериала</Label>
                <Input id="scan-date" type="date" className="bg-white border-slate-300" disabled={isUploading} />
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="description" className="text-slate-700 font-medium">Клинический комментарий</Label>
              <Textarea 
                id="description" 
                placeholder="Предварительный диагноз, особенности образца..." 
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="bg-white border-slate-300 h-24 resize-none focus:ring-blue-500"
                disabled={isUploading}
              />
            </div>

            {/* Зона загрузки */}
            {!file ? (
              <div
                className={`
                  relative border-2 border-dashed rounded-xl p-10 text-center cursor-pointer transition-all duration-300
                  flex flex-col items-center justify-center gap-4 group
                  ${isDragging 
                    ? "border-blue-500 bg-blue-50 scale-[1.01] shadow-inner" 
                    : "border-slate-300 hover:border-blue-400 hover:bg-slate-50 bg-white shadow-sm"
                  }
                `}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
              >
                <input 
                  type="file" 
                  ref={fileInputRef} 
                  className="hidden" 
                  onChange={handleFileSelect}
                  accept=".svs,.tiff,.tif,.ndpi"
                />
                
                <div className="p-4 bg-blue-50 rounded-full group-hover:scale-110 transition-transform duration-300">
                  <UploadCloud className="h-10 w-10 text-blue-600" />
                </div>
                <div>
                  <p className="text-lg font-semibold text-slate-700">
                    Перетащите файл сюда
                  </p>
                  <p className="text-sm text-slate-500 mt-1">
                    или нажмите для выбора с устройства
                  </p>
                </div>
              </div>
            ) : (
              // Карточка файла с прогрессом
              <div className="bg-slate-50 border border-slate-200 rounded-xl p-5 animate-in fade-in zoom-in duration-300 relative overflow-hidden">
                {/* Фоновая анимация загрузки */}
                {isUploading && (
                   <div 
                     className="absolute inset-0 bg-blue-50/50 transition-all duration-300" 
                     style={{ width: `${uploadProgress}%` }}
                   />
                )}
                
                <div className="relative flex items-start justify-between z-10">
                  <div className="flex items-center gap-4">
                    <div className="p-3 bg-white rounded-lg border border-slate-200 shadow-sm">
                      <FileText className="h-8 w-8 text-blue-600" />
                    </div>
                    <div>
                      <p className="font-semibold text-slate-900 text-lg">{file.name}</p>
                      <p className="text-sm text-slate-500 font-medium">
                        {(file.size / (1024 * 1024)).toFixed(2)} MB • {file.type || 'WSI Image'}
                      </p>
                    </div>
                  </div>
                  
                  {!isUploading && (
                    <Button 
                      variant="ghost" 
                      size="icon" 
                      className="text-slate-400 hover:text-red-600 hover:bg-red-50 transition-colors"
                      onClick={removeFile}
                    >
                      <X className="h-5 w-5" />
                    </Button>
                  )}
                </div>

                {isUploading && (
                  <div className="mt-4 space-y-2 relative z-10">
                    <div className="flex justify-between text-xs text-blue-700 font-bold uppercase tracking-wider">
                      <span>Загрузка на сервер</span>
                      <span>{uploadProgress}%</span>
                    </div>
                    <Progress value={uploadProgress} className="h-2 bg-slate-200" indicatorClassName="bg-blue-600" />
                  </div>
                )}
              </div>
            )}
          </CardContent>

          <CardFooter className="flex justify-between bg-slate-50/50 border-t p-6 rounded-b-xl">
            <Button variant="outline" onClick={() => router.back()} disabled={isUploading} className="border-slate-300 text-slate-700">
              Отмена
            </Button>
            <Button 
              className="bg-blue-600 hover:bg-blue-700 text-white shadow-md shadow-blue-200 min-w-[160px] transition-all active:scale-95"
              onClick={handleUpload}
              disabled={!file || !patientId || isUploading}
            >
              {isUploading ? (
                <>
                  <div className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  Загрузка...
                </>
              ) : (
                <>
                  <UploadCloud className="mr-2 h-4 w-4" />
                  Начать анализ
                </>
              )}
            </Button>
          </CardFooter>
        </Card>

        <div className="mt-6 flex gap-3 p-4 bg-amber-50 text-amber-900 rounded-lg border border-amber-200 text-sm shadow-sm">
          <AlertCircle className="h-5 w-5 shrink-0 text-amber-600" />
          <p>
            Обратите внимание: загрузка файлов более 5 ГБ может занять некоторое время. 
            Пожалуйста, не закрывайте вкладку до завершения процесса.
          </p>
        </div>
      </div>
    </div>
  )
}
