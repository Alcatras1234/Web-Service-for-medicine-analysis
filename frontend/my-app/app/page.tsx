import LoginPage from "@/components/pages/ui/reg"


export default function Home() {
  return (
    // Только разметка страницы (фон, центрирование)
    <div className="min-h-screen w-full flex items-center justify-center bg-[#3b82f6] p-4 relative overflow-hidden">
      
      {/* Декоративные пятна */}
      <div className="absolute top-[-10%] left-[-10%] w-[500px] h-[500px] bg-blue-500 rounded-full opacity-30 blur-3xl" />
      <div className="absolute bottom-[-10%] right-[-10%] w-[500px] h-[500px] bg-indigo-500 rounded-full opacity-30 blur-3xl" />

      {/* Вызов твоего компонента */}
      <LoginPage /> 
     
      
    </div>
  )
}
