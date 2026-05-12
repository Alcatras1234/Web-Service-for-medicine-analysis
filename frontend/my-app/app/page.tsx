import { Suspense } from "react"
import LoginPage from "@/components/pages/ui/reg"

// LoginPage использует useSearchParams() — в App Router это требует
// Suspense-границы при пре-рендере, иначе билд падает с CSR bailout.
export default function Home() {
  return (
    <div className="min-h-screen w-full flex items-center justify-center bg-[#3b82f6] p-4 relative overflow-hidden">
      <div className="absolute top-[-10%] left-[-10%] w-[500px] h-[500px] bg-blue-500 rounded-full opacity-30 blur-3xl" />
      <div className="absolute bottom-[-10%] right-[-10%] w-[500px] h-[500px] bg-indigo-500 rounded-full opacity-30 blur-3xl" />

      <Suspense fallback={<div className="text-white">Загрузка...</div>}>
        <LoginPage />
      </Suspense>
    </div>
  )
}
