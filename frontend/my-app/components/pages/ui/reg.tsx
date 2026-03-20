"use client"

import React, { useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, Lock, User, Activity } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Card, CardContent, CardDescription,
  CardFooter, CardHeader, CardTitle
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"

export default function LoginPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  // Запоминаем откуда пришёл пользователь (middleware пробрасывает ?from=)
  const from = searchParams.get("from") || "/dashboard"

  const [showPassword, setShowPassword] = useState<boolean>(false)
  const [isLoading, setIsLoading]       = useState<boolean>(false)
  const [email, setEmail]               = useState<string>("")
  const [password, setPassword]         = useState<string>("")
  const [error, setError]               = useState<string>("")

  const handleLogin = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setIsLoading(true)
    setError("")

    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      })

      if (response.status === 401) {
        setError("Неверный email или пароль")
        return
      }

      if (!response.ok) {
        setError("Ошибка сервера. Попробуйте позже.")
        return
      }

      const data: { token: string; email: string; role: string } = await response.json()

      // Сохраняем токен в cookie — middleware его читает
      document.cookie = `token=${data.token}; path=/; max-age=86400; SameSite=Lax`

      // Сохраняем в localStorage — клиентские компоненты читают через authHeader()
      localStorage.setItem("token", data.token)
      localStorage.setItem("userEmail", data.email)
      localStorage.setItem("userRole", data.role)

      // Редирект туда откуда пришли (или на dashboard)
      router.push(from)

    } catch {
      setError("Нет соединения с сервером")
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen w-full flex items-center justify-center bg-[#3b82f6] p-4 relative overflow-hidden">
      
      <div className="absolute top-[-10%] left-[-10%] w-[500px] h-[500px] bg-blue-500 rounded-full opacity-30 blur-3xl" />
      <div className="absolute bottom-[-10%] right-[-10%] w-[500px] h-[500px] bg-indigo-500 rounded-full opacity-30 blur-3xl" />

      <Card className="w-full max-w-md border-none shadow-2xl rounded-3xl bg-white/95 backdrop-blur-sm z-10">
        <CardHeader className="space-y-1 text-center pb-2">
          <div className="mx-auto bg-blue-100 p-3 rounded-2xl w-fit mb-2">
            <Activity className="w-8 h-8 text-blue-600" />
          </div>
          <CardTitle className="text-2xl font-bold tracking-tight text-slate-900">
            Eosin-AI Enterprise
          </CardTitle>
          <CardDescription className="text-slate-500">
            Вход в систему анализа WSI изображений
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4 pt-4">
          <form onSubmit={handleLogin} className="space-y-4">

            {/* Блок ошибки */}
            {error && (
              <div className="bg-red-50 border border-red-200 text-red-700 text-sm rounded-xl px-4 py-3">
                {error}
              </div>
            )}

            <div className="space-y-2">
              <Label htmlFor="email" className="text-slate-600 font-medium">
                Корпоративная почта
              </Label>
              <div className="relative">
                <User className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                <Input
                  id="email"
                  name="email"
                  placeholder="doctor@hospital.ru"
                  type="email"
                  className="pl-10 h-11 bg-slate-50 border-slate-200 rounded-xl focus:ring-blue-500 focus:border-blue-500 transition-all"
                  required
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  disabled={isLoading}
                />
              </div>
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label htmlFor="password" className="text-slate-600 font-medium">Пароль</Label>
                <a href="#" className="text-xs text-blue-600 hover:text-blue-500 font-medium transition-colors">
                  Забыли пароль?
                </a>
              </div>
              <div className="relative">
                <Lock className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                <Input
                  id="password"
                  name="password"
                  type={showPassword ? "text" : "password"}
                  className="pl-10 pr-10 h-11 bg-slate-50 border-slate-200 rounded-xl focus:ring-blue-500 focus:border-blue-500 transition-all"
                  required
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  disabled={isLoading}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-3 text-slate-400 hover:text-slate-600 focus:outline-none"
                  aria-label={showPassword ? "Скрыть пароль" : "Показать пароль"}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>

            <div className="flex items-center space-x-2 pt-2">
              <Checkbox
                id="remember"
                className="rounded-[4px] border-slate-300 text-blue-600 focus:ring-blue-500"
              />
              <label
                htmlFor="remember"
                className="text-sm font-medium leading-none text-slate-600 cursor-pointer"
              >
                Запомнить устройство
              </label>
            </div>

            <Button
              type="submit"
              className="w-full h-11 rounded-xl bg-blue-600 hover:bg-blue-700 text-white font-semibold shadow-lg shadow-blue-500/30 transition-all duration-300 transform hover:-translate-y-0.5"
              disabled={isLoading}
            >
              {isLoading ? (
                <div className="flex items-center gap-2">
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  Авторизация...
                </div>
              ) : (
                "Войти в систему"
              )}
            </Button>
          </form>
        </CardContent>

        <CardFooter className="flex flex-col gap-4 text-center border-t border-slate-100 pt-6">
          <div className="text-sm text-slate-500">
            Нет учетной записи?{" "}
            <a href="#" className="text-blue-600 font-semibold hover:underline">
              Запросить доступ
            </a>
          </div>
          <p className="text-xs text-slate-400 px-4">
            Доступ разрешен только авторизованному медицинскому персоналу.
            Все действия логируются согласно 152-ФЗ.
          </p>
        </CardFooter>
      </Card>
    </div>
  )
}
